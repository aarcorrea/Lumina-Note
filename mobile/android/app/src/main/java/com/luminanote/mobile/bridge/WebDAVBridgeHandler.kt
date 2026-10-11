package com.luminanote.mobile.bridge

import android.content.Context
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class WebDAVBridgeHandler(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private var serverUrl: String = ""
    private var authHeader: String? = null

    fun setConfig(args: JSONObject) {
        val config = args.optJSONObject("config") ?: args
        serverUrl = config.optString("serverUrl", config.optString("url")).trimEnd('/')
        val user = config.optString("username", config.optString("user"))
        val pass = config.optString("password", config.optString("pass"))
        authHeader = if (user.isNotBlank() || pass.isNotBlank()) {
            Credentials.basic(user, pass)
        } else {
            null
        }
    }

    fun upload(args: JSONObject): JSONObject {
        val localPath = args.getString("localPath")
        val remotePath = args.getString("remotePath").trimStart('/')
        val url = "$serverUrl/$remotePath"

        val file = File(localPath)
        val body = file.readBytes().toRequestBody("application/octet-stream".toMediaTypeOrNull())

        val reqBuilder = Request.Builder()
            .url(url)
            .put(body)

        authHeader?.let { reqBuilder.addHeader("Authorization", it) }

        val response = client.newCall(reqBuilder.build()).execute()
        val res = JSONObject()
        res.put("success", response.isSuccessful)
        res.put("statusCode", response.code)
        return res
    }

    fun createDir(args: JSONObject): JSONObject {
        val remotePath = args.getString("remotePath").trimStart('/')
        val url = "$serverUrl/$remotePath"

        val reqBuilder = Request.Builder()
            .url(url)
            .method("MKCOL", null)

        authHeader?.let { reqBuilder.addHeader("Authorization", it) }

        val response = client.newCall(reqBuilder.build()).execute()
        val res = JSONObject()
        res.put("success", response.isSuccessful || response.code == 405) // 405 means already exists
        res.put("statusCode", response.code)
        return res
    }

    fun delete(args: JSONObject): JSONObject {
        val remotePath = args.getString("remotePath").trimStart('/')
        val url = "$serverUrl/$remotePath"

        val reqBuilder = Request.Builder()
            .url(url)
            .delete()

        authHeader?.let { reqBuilder.addHeader("Authorization", it) }

        val response = client.newCall(reqBuilder.build()).execute()
        val res = JSONObject()
        res.put("success", response.isSuccessful || response.code == 404)
        res.put("statusCode", response.code)
        return res
    }
}
