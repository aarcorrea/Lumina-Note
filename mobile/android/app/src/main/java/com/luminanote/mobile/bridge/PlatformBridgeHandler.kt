package com.luminanote.mobile.bridge

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class PlatformBridgeHandler(
    private val context: Context,
    private val fsHandler: FsBridgeHandler
) {

    fun getVersion(): String = "1.4.11"

    fun getPlatform(): String = "android"

    fun getOsVersion(): String = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

    fun getOsType(): String = "Linux"

    fun getOsArch(): String = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"

    fun getHomeDir(): String {
        return context.getExternalFilesDir(null)?.absolutePath ?: context.filesDir.absolutePath
    }

    fun getAppDataDir(): String {
        return context.filesDir.absolutePath
    }

    fun getDocumentDir(): String {
        return fsHandler.getDefaultVaultDir().absolutePath
    }

    fun pathJoin(args: JSONObject): String {
        val parts = args.optJSONArray("parts") ?: JSONArray()
        val fileParts = mutableListOf<String>()
        for (i in 0 until parts.length()) {
            fileParts.add(parts.getString(i))
        }
        return fileParts.joinToString(File.separator)
    }

    fun pathBasename(args: JSONObject): String {
        val path = args.getString("path")
        val ext = args.optString("ext")
        var base = File(path).name
        if (ext.isNotBlank() && base.endsWith(ext)) {
            base = base.removeSuffix(ext)
        }
        return base
    }

    fun pathDirname(args: JSONObject): String {
        val path = args.getString("path")
        return File(path).parent ?: ""
    }

    fun pathExtname(args: JSONObject): String {
        val path = args.getString("path")
        val ext = File(path).extension
        return if (ext.isNotEmpty()) ".$ext" else ""
    }

    fun pathNormalize(args: JSONObject): String {
        val path = args.getString("path")
        return File(path).normalize().path
    }

    fun pathIsAbsolute(args: JSONObject): Boolean {
        val path = args.getString("path")
        return File(path).isAbsolute
    }

    fun openExternal(args: JSONObject) {
        val url = args.optString("path", args.optString("url"))
        if (url.isNotBlank()) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(intent)
            } catch (_: Exception) {}
        }
    }

    fun showInExplorer(args: JSONObject) {
        val path = args.getString("path")
        val file = File(path)
        val dir = if (file.isDirectory) file else file.parentFile ?: file
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(dir.absolutePath), "*/*")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (_: Exception) {}
    }
}
