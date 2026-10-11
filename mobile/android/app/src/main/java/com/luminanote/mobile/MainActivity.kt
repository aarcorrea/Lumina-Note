package com.luminanote.mobile

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.MimeTypeMap
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
import com.luminanote.mobile.bridge.LuminaNativeBridge
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
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
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) {}
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
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val bottomPadding = if (ime.bottom > 0) ime.bottom else systemBars.bottom
            v.setPadding(0, systemBars.top, 0, bottomPadding)
            insets
        }

        bridge = LuminaNativeBridge(this, webView)

        setupWebView()
        setupBackNavigation()

        // Load the web app
        webView.loadUrl("https://appassets.androidplatform.net/index.html")
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
                if (url.host == "appassets.androidplatform.net") {
                    return handleAppAssetRequest(url)
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                injectBridgeScript()
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                consoleMessage?.let {
                    Log.d("LuminaWeb", "[${it.messageLevel()}] ${it.message()} (${it.sourceId()}:${it.lineNumber()})")
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

    private fun handleAppAssetRequest(uri: Uri): WebResourceResponse? {
        val path = uri.path?.trimStart('/') ?: ""
        val resolved = openAssetStream(path) ?: return null

        val assetPath = resolved.second
        val mimeType = getMimeType(assetPath)
        val isHtml = mimeType == "text/html"

        return if (isHtml) {
            // Read HTML and inject bridge script synchronously into <head>
            try {
                val rawHtml = resolved.first.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val scriptTag = "<script>\n${getBridgeScriptCode()}\n</script>\n"
                val injectedHtml = if (rawHtml.contains("<head>", ignoreCase = true)) {
                    rawHtml.replaceFirst("<head>", "<head>\n$scriptTag", ignoreCase = true)
                } else {
                    scriptTag + rawHtml
                }
                val stream = ByteArrayInputStream(injectedHtml.toByteArray(Charsets.UTF_8))
                val res = WebResourceResponse("text/html", "utf-8", stream)
                res.responseHeaders = mapOf(
                    "Access-Control-Allow-Origin" to "*",
                    "Cache-Control" to "no-cache"
                )
                res
            } catch (e: Exception) {
                Log.e("LuminaAsset", "Failed to inject bridge in HTML: $assetPath", e)
                WebResourceResponse("text/html", "utf-8", resolved.first)
            }
        } else {
            val encoding = if (mimeType.startsWith("text/") || mimeType.contains("javascript") || mimeType.contains("json")) "utf-8" else null
            val res = WebResourceResponse(mimeType, encoding, resolved.first)
            res.responseHeaders = mapOf(
                "Access-Control-Allow-Origin" to "*",
                "Cache-Control" to "no-cache"
            )
            res
        }
    }

    private fun openAssetStream(rawPath: String): Pair<InputStream, String>? {
        var p = rawPath.trimStart('/')
        if (p.isEmpty() || p == "index.html") {
            p = "index.html"
        }

        val candidates = mutableListOf<String>()

        // 1. If path starts with assets/public/ strip assets/
        if (p.startsWith("assets/public/")) {
            candidates.add(p.removePrefix("assets/"))
        }

        // 2. Try with public/ prefix (e.g. public/index.html, public/assets/index-DsO8IBSq.js)
        if (!p.startsWith("public/")) {
            candidates.add("public/$p")
        }

        // 3. Try exact path
        candidates.add(p)

        // 4. Try inside assets/ directly
        if (!p.startsWith("assets/")) {
            candidates.add("assets/$p")
        }

        // 5. SPA fallback: if not pointing to a file with extension, fallback to public/index.html
        if (!p.contains(".")) {
            candidates.add("public/index.html")
        }

        for (candidate in candidates) {
            try {
                val stream = assets.open(candidate)
                Log.d("LuminaAsset", "Found asset for '$rawPath' at '$candidate'")
                return Pair(stream, candidate)
            } catch (_: Exception) {}
        }

        Log.e("LuminaAsset", "Asset not found for '$rawPath'. Tried: $candidates")
        return null
    }

    private fun getMimeType(filePath: String): String {
        val ext = filePath.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "html", "htm" -> "text/html"
            "js", "mjs" -> "application/javascript"
            "css" -> "text/css"
            "json" -> "application/json"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "svg" -> "image/svg+xml"
            "webp" -> "image/webp"
            "ico" -> "image/x-icon"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "ttf" -> "font/ttf"
            "otf" -> "font/otf"
            "wasm" -> "application/wasm"
            "txt", "md" -> "text/plain"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        }
    }

    private fun getBridgeScriptCode(): String {
        return """
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
    }

    private fun injectBridgeScript() {
        webView.evaluateJavascript(getBridgeScriptCode(), null)
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                webView.evaluateJavascript(
                    """
                    (function() {
                        const event = new CustomEvent('lumina:android-back', { cancelable: true });
                        const allowed = window.dispatchEvent(event);
                        return allowed ? "continue" : "handled";
                    })();
                    """.trimIndent()
                ) { result ->
                    val cleanRes = result?.replace("\"", "")?.trim()
                    if (cleanRes == "handled") {
                        return@evaluateJavascript
                    }
                    if (webView.canGoBack()) {
                        webView.goBack()
                    } else {
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
