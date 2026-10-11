package com.luminanote.mobile.bridge

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.luminanote.mobile.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class LuminaNativeBridge(
    private val activity: MainActivity,
    private val webView: WebView
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioScope = CoroutineScope(Dispatchers.IO)

    val fsHandler = FsBridgeHandler(activity)
    val storeHandler = StoreBridgeHandler(activity)
    val platformHandler = PlatformBridgeHandler(activity, fsHandler)
    val webdavHandler = WebDAVBridgeHandler(activity)

    @JavascriptInterface
    fun postMessage(messageJson: String) {
        ioScope.launch {
            try {
                val req = JSONObject(messageJson)
                val id = req.optLong("id", -1)
                val cmd = req.getString("cmd")
                val args = req.optJSONObject("args") ?: JSONObject()

                val result = executeCommand(cmd, args)
                sendSuccess(id, result)
            } catch (e: Exception) {
                try {
                    val req = JSONObject(messageJson)
                    val id = req.optLong("id", -1)
                    sendError(id, e.message ?: "Unknown native error")
                } catch (_: Exception) {}
            }
        }
    }

    private fun executeCommand(cmd: String, args: JSONObject): Any? {
        return when (cmd) {
            // File system
            "read_file" -> fsHandler.readFile(args)
            "save_file" -> {
                fsHandler.saveFile(args)
                null
            }
            "create_file" -> {
                fsHandler.createFile(args)
                null
            }
            "create_dir", "plugin:fs|create_dir", "plugin:fs|mkdir" -> {
                fsHandler.createDir(args)
                null
            }
            "delete_file", "plugin:fs|remove" -> {
                fsHandler.deleteFile(args)
                null
            }
            "rename_file", "plugin:fs|rename" -> {
                fsHandler.renameFile(args)
                null
            }
            "plugin:fs|copy_file" -> {
                fsHandler.copyFile(args)
                null
            }
            "write_binary_file" -> {
                fsHandler.writeBinaryFile(args)
                null
            }
            "read_binary_file_base64" -> fsHandler.readBinaryFileBase64(args)
            "list_workspace" -> fsHandler.listWorkspace(args)
            "list_directory" -> fsHandler.listDirectory(args)
            "list_dir_shallow" -> fsHandler.listDirShallow(args)
            "walk_paths" -> fsHandler.walkPaths(args)
            "estimate_dir_size" -> fsHandler.estimateDirSize(args)
            "exists" -> fsHandler.exists(args)
            "plugin:fs|stat", "plugin:fs|lstat" -> fsHandler.stat(args)
            "vault_initialize" -> fsHandler.vaultInitialize(args)
            "fs_set_allowed_roots" -> null
            "write_text_file", "plugin:fs|write_text_file" -> {
                fsHandler.saveFile(args)
                null
            }
            "ensure_dir" -> {
                fsHandler.createDir(args)
                null
            }
            "start_file_watcher", "stop_file_watcher" -> null

            // Store & Preferences
            "secure_store_get", "plugin:store|get" -> storeHandler.get(args.optString("key"))
            "secure_store_set", "plugin:store|set" -> {
                storeHandler.set(args.getString("key"), args.opt("value"))
                null
            }
            "secure_store_delete", "plugin:store|delete" -> {
                storeHandler.delete(args.getString("key"))
                null
            }
            "plugin:store|clear" -> {
                storeHandler.clear()
                null
            }
            "plugin:store|keys" -> storeHandler.keys()
            "plugin:store|values" -> storeHandler.values()
            "plugin:store|entries" -> storeHandler.entries()
            "plugin:store|length" -> storeHandler.length()
            "plugin:store|save", "plugin:store|load" -> null

            // Platform & Path
            "get_version" -> platformHandler.getVersion()
            "plugin:path|home_dir" -> platformHandler.getHomeDir()
            "plugin:path|app_data_dir", "plugin:path|app_config_dir", "plugin:path|app_local_data_dir" -> platformHandler.getAppDataDir()
            "plugin:path|document_dir" -> platformHandler.getDocumentDir()
            "plugin:path|desktop_dir", "plugin:path|download_dir", "plugin:path|picture_dir" -> platformHandler.getHomeDir()
            "plugin:path|join" -> platformHandler.pathJoin(args)
            "plugin:path|basename" -> platformHandler.pathBasename(args)
            "plugin:path|dirname" -> platformHandler.pathDirname(args)
            "plugin:path|extname" -> platformHandler.pathExtname(args)
            "plugin:path|normalize" -> platformHandler.pathNormalize(args)
            "plugin:path|is_absolute" -> platformHandler.pathIsAbsolute(args)
            "plugin:os|platform" -> platformHandler.getPlatform()
            "plugin:os|version" -> platformHandler.getOsVersion()
            "plugin:os|type" -> platformHandler.getOsType()
            "plugin:os|arch" -> platformHandler.getOsArch()
            "plugin:shell|open" -> {
                platformHandler.openExternal(args)
                null
            }
            "show_in_explorer" -> {
                platformHandler.showInExplorer(args)
                null
            }

            // Dialogs
            "plugin:dialog|open" -> {
                val directory = args.optBoolean("directory", false)
                if (directory) {
                    activity.chooseFolderSync()
                } else {
                    activity.chooseFileSync()
                }
            }
            "plugin:dialog|save" -> {
                null
            }
            "plugin:dialog|message", "plugin:dialog|ask", "plugin:dialog|confirm" -> {
                null
            }

            // WebDAV Cloud Sync
            "webdav_set_config" -> {
                webdavHandler.setConfig(args)
                null
            }
            "webdav_upload" -> webdavHandler.upload(args)
            "webdav_create_dir" -> webdavHandler.createDir(args)
            "webdav_delete" -> webdavHandler.delete(args)

            // Agent & Provider settings
            "agent_set_active_provider" -> {
                storeHandler.set("agent_active_provider", args.optString("provider_id"))
                null
            }
            "agent_set_provider_settings" -> {
                storeHandler.set("agent_provider_settings", args.optJSONObject("settings"))
                null
            }
            "agent_set_provider_api_key" -> {
                storeHandler.set("api_key_" + args.optString("provider_id"), args.optString("api_key"))
                null
            }
            "image_set_provider_settings" -> {
                storeHandler.set("image_provider_settings", args.optJSONObject("settings"))
                null
            }
            "image_set_provider_api_key" -> {
                storeHandler.set("image_api_key_" + args.optString("provider_id"), args.optString("api_key"))
                null
            }
            "agent_set_auto_approve" -> {
                storeHandler.set("agent_auto_approve", args.optBoolean("value"))
                null
            }

            // Stubs / No-ops
            "set_dirty_state", "set_proxy_config", "test_proxy_connection",
            "plugin:resources|close", "plugin:window|set_size",
            "update_cancel_resumable_install", "update_clear_resumable_cache",
            "update_quit_and_install", "mobile_stop_server", "mobile_set_workspace",
            "plugin_list", "plugin_read_entry", "plugin_get_workspace_dir",
            "plugin_scaffold_example", "plugin_scaffold_theme", "plugin_scaffold_ui_overhaul",
            "vscode_extensions_get_state", "vscode_extensions_get_diagnostics",
            "vscode_extensions_get_runtime_state", "vscode_extensions_stop_host" -> null

            else -> {
                if (cmd.startsWith("typesetting_")) {
                    null
                } else if (cmd.startsWith("plugin:event|")) {
                    1
                } else {
                    null
                }
            }
        }
    }

    private fun sendSuccess(id: Long, result: Any?) {
        if (id < 0) return
        val resultStr = when (result) {
            null -> "null"
            is String -> JSONObject.quote(result)
            is Number, is Boolean -> result.toString()
            is JSONObject -> result.toString()
            is JSONArray -> result.toString()
            else -> JSONObject.quote(result.toString())
        }
        val js = "window.__TAURI_INTERNALS__ && window.__TAURI_INTERNALS__.__handleResponse($id, $resultStr, null);"
        mainHandler.post {
            webView.evaluateJavascript(js, null)
        }
    }

    private fun sendError(id: Long, errorMessage: String) {
        if (id < 0) return
        val errStr = JSONObject.quote(errorMessage)
        val js = "window.__TAURI_INTERNALS__ && window.__TAURI_INTERNALS__.__handleResponse($id, null, $errStr);"
        mainHandler.post {
            webView.evaluateJavascript(js, null)
        }
    }

    fun emitEvent(eventName: String, payloadJson: String = "null") {
        val js = "window.__TAURI_INTERNALS__ && window.__TAURI_INTERNALS__.__handleEvent('$eventName', $payloadJson);"
        mainHandler.post {
            webView.evaluateJavascript(js, null)
        }
    }
}
