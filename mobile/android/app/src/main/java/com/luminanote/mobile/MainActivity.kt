package com.luminanote.mobile

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewAssetLoader
import com.luminanote.mobile.bridge.LuminaNativeBridge
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var assetLoader: WebViewAssetLoader
    private lateinit var bridge: LuminaNativeBridge

    private var pendingFolderResult: String? = null
    private var folderLatch: CountDownLatch? = null

    private var pendingFileResult: String? = null
    private var fileLatch: CountDownLatch? = null

    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null

    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            val path = uri.path ?: uri.toString()
            pendingFolderResult = path
        } else {
            pendingFolderResult = null
        }
        folderLatch?.countDown()
    }

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            pendingFileResult = uris.first().path
        } else {
            pendingFileResult = null
        }
        fileLatch?.countDown()
    }

    private val fileUploadLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        fileUploadCallback?.onReceiveValue(uris.toTypedArray())
        fileUploadCallback = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)

        webView = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        setContentView(webView)

        ViewCompat.setOnApplyWindowInsetsListener(webView) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, systemBars.top, 0, 0)
            insets
        }

        assetLoader = WebViewAssetLoader.Builder()
            .setDomain("appassets.androidplatform.net")
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .addPathHandler("/res/", WebViewAssetLoader.ResourcesPathHandler(this))
            .build()

        bridge = LuminaNativeBridge(this, webView)

        setupWebView()
        setupBackNavigation()

        // Load the app
        webView.loadUrl("https://appassets.androidplatform.net/assets/public/index.html")
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.builtInZoomControls = false
        settings.displayZoomControls = false
        settings.cacheMode = WebSettings.LOAD_DEFAULT

        webView.addJavascriptInterface(bridge, "__ANDROID_NATIVE__")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                val url = request.url
                // Fallback / index rewriting for SPA routing
                val path = url.path ?: ""
                val response = assetLoader.shouldInterceptRequest(url)
                if (response == null && url.host == "appassets.androidplatform.net") {
                    if (!path.contains(".")) {
                        val indexUri = Uri.parse("https://appassets.androidplatform.net/assets/public/index.html")
                        return assetLoader.shouldInterceptRequest(indexUri)
                    }
                }
                return response
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                injectBridgeScript()
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                consoleMessage?.let {
                    android.util.Log.d("LuminaWeb", "[${it.messageLevel()}] ${it.message()} -- From line ${it.lineNumber()} of ${it.sourceId()}")
                }
                return true
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                fileUploadCallback?.onReceiveValue(null)
                fileUploadCallback = filePathCallback
                fileUploadLauncher.launch(arrayOf("*/*"))
                return true
            }
        }
    }

    private fun injectBridgeScript() {
        val bridgeInitJs = """
        (function() {
            if (window.__LUMINA_ANDROID_INSTALLED__) return;
            window.__LUMINA_ANDROID_INSTALLED__ = true;

            let reqId = 1;
            const pendingRequests = new Map();
            const eventHandlers = new Map();
            const callbacks = new Map();
            let nextCallbackId = 1;

            const tauriInternals = {
                invoke: function(cmd, args) {
                    return new Promise(function(resolve, reject) {
                        const id = reqId++;
                        pendingRequests.set(id, { resolve: resolve, reject: reject });
                        try {
                            window.__ANDROID_NATIVE__.postMessage(JSON.stringify({
                                id: id,
                                cmd: cmd,
                                args: args || {}
                            }));
                        } catch (err) {
                            pendingRequests.delete(id);
                            reject(err);
                        }
                    });
                },
                listen: function(event, handler) {
                    if (!eventHandlers.has(event)) {
                        eventHandlers.set(event, new Set());
                    }
                    eventHandlers.get(event).add(handler);
                    return Promise.resolve(function() {
                        const handlers = eventHandlers.get(event);
                        if (handlers) {
                            handlers.delete(handler);
                        }
                    });
                },
                emit: function(event, payload) {
                    const handlers = eventHandlers.get(event);
                    if (handlers) {
                        handlers.forEach(function(fn) {
                            try { fn({ event: event, payload: payload }); } catch (e) { console.error(e); }
                        });
                    }
                    return Promise.resolve();
                },
                once: function(event, handler) {
                    let unlisten;
                    const wrapped = function(e) {
                        if (unlisten) unlisten();
                        handler(e);
                    };
                    return tauriInternals.listen(event, wrapped).then(function(u) {
                        unlisten = u;
                        return unlisten;
                    });
                },
                transformCallback: function(callback, once) {
                    const id = nextCallbackId++;
                    callbacks.set(id, function(args) {
                        if (once) callbacks.delete(id);
                        return callback(args);
                    });
                    return id;
                },
                unregisterCallback: function(id) {
                    callbacks.delete(id);
                },
                runCallback: function(id, args) {
                    const cb = callbacks.get(id);
                    return cb ? cb(args) : undefined;
                },
                __handleResponse: function(id, result, error) {
                    const p = pendingRequests.get(id);
                    if (!p) return;
                    pendingRequests.delete(id);
                    if (error) {
                        p.reject(new Error(error));
                    } else {
                        p.resolve(result);
                    }
                },
                __handleEvent: function(event, payload) {
                    const handlers = eventHandlers.get(event);
                    if (handlers) {
                        handlers.forEach(function(fn) {
                            try { fn({ event: event, payload: payload }); } catch (e) { console.error(e); }
                        });
                    }
                }
            };

            window.__TAURI_INTERNALS__ = tauriInternals;
            window.__TAURI__ = {
                core: {
                    invoke: tauriInternals.invoke
                }
            };

            window.lumina = {
                windowControls: {
                    minimize: function() {},
                    maximize: function() {},
                    close: function() {}
                }
            };

            window.__IS_ANDROID__ = true;
            document.documentElement.classList.add('is-android');
            console.log('[AndroidBridge] Native Android Host Bridge initialized');
        })();
        """.trimIndent()

        webView.evaluateJavascript(bridgeInitJs, null)
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Dispatch event to web app to close modals / sidebars if any
                webView.evaluateJavascript(
                    """
                    (function() {
                        const event = new CustomEvent('lumina:android-back');
                        const handled = window.dispatchEvent(event);
                        return handled;
                    })();
                    """.trimIndent()
                ) { result ->
                    if (result != "false" && webView.canGoBack()) {
                        webView.goBack()
                    } else {
                        // Let user exit or background app
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            }
        })
    }

    fun chooseFolderSync(): String {
        pendingFolderResult = null
        folderLatch = CountDownLatch(1)
        runOnUiThread {
            folderPickerLauncher.launch(null)
        }
        folderLatch?.await(60, TimeUnit.SECONDS)
        return pendingFolderResult ?: bridge.fsHandler.getDefaultVaultDir().absolutePath
    }

    fun chooseFileSync(): String? {
        pendingFileResult = null
        fileLatch = CountDownLatch(1)
        runOnUiThread {
            filePickerLauncher.launch(arrayOf("*/*"))
        }
        fileLatch?.await(60, TimeUnit.SECONDS)
        return pendingFileResult
    }
}
