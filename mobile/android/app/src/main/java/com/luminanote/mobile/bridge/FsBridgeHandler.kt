package com.luminanote.mobile.bridge

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.ArrayDeque

class FsBridgeHandler(private val context: Context) {

    private val ignoredDirs = setOf(
        "node_modules", ".git", "target", "dist", "build",
        ".next", ".nuxt", ".turbo", ".cache", ".venv",
        "__pycache__", ".pnpm-store", "out", "coverage",
        ".idea", ".vscode", ".gradle"
    )
    private val ignoredFiles = setOf(".DS_Store", "Thumbs.db")

    private fun shouldSkip(name: String): Boolean {
        if (ignoredDirs.contains(name)) return true
        if (ignoredFiles.contains(name)) return true
        if (name.startsWith(".") && name != ".lumina") return true
        return false
    }

    fun getDefaultVaultDir(): File {
        val external = context.getExternalFilesDir(null)
        val vaultDir = if (external != null) {
            File(external, "LuminaVault")
        } else {
            File(context.filesDir, "LuminaVault")
        }
        if (!vaultDir.exists()) {
            vaultDir.mkdirs()
        }
        return vaultDir
    }

    fun vaultInitialize(args: JSONObject): JSONObject {
        val workspacePath = args.optString("workspacePath").ifBlank { getDefaultVaultDir().absolutePath }
        val root = File(workspacePath)
        if (!root.exists()) {
            root.mkdirs()
        }

        // Check if vault is empty. If so, create a welcome starter note
        val files = root.listFiles()
        if (files == null || files.isEmpty()) {
            val welcomeFile = File(root, "Welcome to Lumina.md")
            val welcomeContent = """# Welcome to Lumina Note on Android 🚀

Lumina Note is your fast, local-first markdown notebook with AI capabilities.

## Getting Started
- 📝 **Write in Markdown**: Full support for headings, task lists, code blocks, and math equations ($$E=mc^2$$).
- 🎨 **Canvas & Diagrams**: Draw sketches, flowcharts, and mind maps.
- 🤖 **AI Assistant**: Connect OpenAI, Anthropic, Gemini, DeepSeek, or local Ollama models in Settings.
- 🔄 **Cloud Sync**: Sync your vault seamlessly via WebDAV or self-hosted server.
- 📱 **Offline-first**: All your notes are stored directly on your device in plain Markdown files.

Enjoy writing!
""".trimIndent()
            welcomeFile.writeText(welcomeContent, Charsets.UTF_8)
        }

        val res = JSONObject()
        res.put("success", true)
        res.put("path", root.absolutePath)
        return res
    }

    fun listWorkspace(args: JSONObject): JSONObject {
        val rootPath = args.optString("path").ifBlank { getDefaultVaultDir().absolutePath }
        val root = File(rootPath)
        if (!root.exists()) {
            root.mkdirs()
        }

        val entriesArray = JSONArray()
        var totalEntries = 0
        var unreadableCount = 0

        val fileEntries = buildFileTree(root)
        for (entry in fileEntries) {
            entriesArray.put(entry)
            totalEntries++
        }

        val result = JSONObject()
        result.put("entries", entriesArray)
        result.put("totalEntries", totalEntries)
        result.put("truncated", false)
        result.put("unreadableDirCount", unreadableCount)
        return result
    }

    private fun buildFileTree(dir: File): List<JSONObject> {
        val result = mutableListOf<JSONObject>()
        val files = dir.listFiles() ?: return result

        val sortedFiles = files.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))

        for (file in sortedFiles) {
            if (shouldSkip(file.name)) continue

            val entry = JSONObject()
            entry.put("name", file.name)
            entry.put("path", file.absolutePath)
            entry.put("is_dir", file.isDirectory)
            entry.put("isDirectory", file.isDirectory)
            entry.put("childrenLoaded", !file.isDirectory)
            entry.put("size", if (file.isFile) file.length() else null)
            entry.put("modified_at", file.lastModified())
            entry.put("created_at", file.lastModified())

            if (file.isDirectory) {
                val children = buildFileTree(file)
                val childrenArray = JSONArray()
                for (child in children) {
                    childrenArray.put(child)
                }
                entry.put("children", childrenArray)
            } else {
                entry.put("children", JSONObject.NULL)
            }
            result.add(entry)
        }
        return result
    }

    fun listDirectory(args: JSONObject): JSONArray {
        val path = args.getString("path")
        val dir = File(path)
        val result = JSONArray()
        val files = dir.listFiles() ?: return result

        val sortedFiles = files.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        for (file in sortedFiles) {
            if (shouldSkip(file.name)) continue
            val entry = JSONObject()
            entry.put("name", file.name)
            entry.put("path", file.absolutePath)
            entry.put("is_dir", file.isDirectory)
            entry.put("isDirectory", file.isDirectory)
            entry.put("childrenLoaded", !file.isDirectory)
            entry.put("size", if (file.isFile) file.length() else null)
            entry.put("modified_at", file.lastModified())
            entry.put("created_at", file.lastModified())
            entry.put("children", if (file.isDirectory) JSONArray() else JSONObject.NULL)
            result.put(entry)
        }
        return result
    }

    fun listDirShallow(args: JSONObject): JSONArray {
        val dirPath = args.optString("dirPath", args.optString("path"))
        val dir = File(dirPath)
        val result = JSONArray()
        val files = dir.listFiles() ?: return result

        val sortedFiles = files.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        for (file in sortedFiles) {
            if (shouldSkip(file.name)) continue
            val entry = JSONObject()
            entry.put("name", file.name)
            entry.put("path", file.absolutePath)
            entry.put("is_dir", file.isDirectory)
            entry.put("isDirectory", file.isDirectory)
            entry.put("childrenLoaded", !file.isDirectory)
            entry.put("size", if (file.isFile) file.length() else null)
            entry.put("modified_at", file.lastModified())
            entry.put("created_at", file.lastModified())
            entry.put("children", if (file.isDirectory) JSONArray() else JSONObject.NULL)
            result.put(entry)
        }
        return result
    }

    fun readFile(args: JSONObject): String {
        val path = args.getString("path")
        val file = File(path)
        if (!file.exists()) {
            throw NoSuchFileException(file, reason = "File does not exist: $path")
        }
        return file.readText(Charsets.UTF_8)
    }

    fun saveFile(args: JSONObject) {
        val path = args.getString("path")
        val content = args.getString("content")
        val file = File(path)

        val expectedVersion = args.optJSONObject("expectedVersion")
        if (expectedVersion != null && file.exists()) {
            val expectedMtime = expectedVersion.optLong("mtimeMs", -1)
            val expectedSize = expectedVersion.optLong("size", -1)
            if (expectedMtime > 0 && Math.abs(file.lastModified() - expectedMtime) > 2000) {
                // Modified concurrently
                val overwrite = args.optBoolean("overwrite", false)
                if (!overwrite) {
                    throw IllegalStateException("FILE_MODIFIED_SINCE:changed: File changed on disk before save: $path")
                }
            }
        }

        file.parentFile?.let {
            if (!it.exists()) it.mkdirs()
        }
        file.writeText(content, Charsets.UTF_8)
    }

    fun createFile(args: JSONObject) {
        val path = args.getString("path")
        val file = File(path)
        file.parentFile?.let {
            if (!it.exists()) it.mkdirs()
        }
        if (!file.exists()) {
            file.createNewFile()
        }
    }

    fun createDir(args: JSONObject) {
        val path = args.getString("path")
        val dir = File(path)
        if (!dir.exists()) {
            dir.mkdirs()
        }
    }

    fun deleteFile(args: JSONObject) {
        val path = args.getString("path")
        val file = File(path)
        if (file.exists()) {
            file.deleteRecursively()
        }
    }

    fun renameFile(args: JSONObject) {
        val oldPath = args.optString("oldPath", args.optString("from"))
        val newPath = args.optString("newPath", args.optString("to"))
        val oldFile = File(oldPath)
        val newFile = File(newPath)
        newFile.parentFile?.let {
            if (!it.exists()) it.mkdirs()
        }
        oldFile.renameTo(newFile)
    }

    fun copyFile(args: JSONObject) {
        val from = args.getString("from")
        val to = args.getString("to")
        val src = File(from)
        val dest = File(to)
        dest.parentFile?.let {
            if (!it.exists()) it.mkdirs()
        }
        src.copyTo(dest, overwrite = true)
    }

    fun writeBinaryFile(args: JSONObject) {
        val path = args.getString("path")
        val dataArray = args.getJSONArray("data")
        val bytes = ByteArray(dataArray.length())
        for (i in 0 until dataArray.length()) {
            bytes[i] = dataArray.getInt(i).toByte()
        }
        val file = File(path)
        file.parentFile?.let {
            if (!it.exists()) it.mkdirs()
        }
        file.writeBytes(bytes)
    }

    fun readBinaryFileBase64(args: JSONObject): String {
        val path = args.getString("path")
        val file = File(path)
        val bytes = file.readBytes()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    fun exists(args: JSONObject): Boolean {
        val path = args.getString("path")
        return File(path).exists()
    }

    fun stat(args: JSONObject): JSONObject {
        val path = args.getString("path")
        val file = File(path)
        val res = JSONObject()
        res.put("size", if (file.isFile) file.length() else 0L)
        res.put("isDirectory", file.isDirectory)
        res.put("isFile", file.isFile)
        res.put("isSymlink", false)
        res.put("mtime", file.lastModified())
        res.put("atime", file.lastModified())
        res.put("ctime", file.lastModified())
        res.put("readonly", !file.canWrite())
        return res
    }

    fun walkPaths(args: JSONObject): JSONObject {
        val rootPath = args.optString("root").ifBlank { getDefaultVaultDir().absolutePath }
        val root = File(rootPath)
        val extensionsArray = args.optJSONArray("extensions")
        val allowedExts = mutableSetOf<String>()
        if (extensionsArray != null) {
            for (i in 0 until extensionsArray.length()) {
                allowedExts.add(extensionsArray.getString(i).removePrefix("."))
            }
        }
        val maxPaths = args.optInt("maxPaths", 50000)

        val paths = mutableListOf<String>()
        val queue = ArrayDeque<File>()
        if (root.exists()) {
            queue.add(root)
        }

        var truncated = false
        while (!queue.isEmpty()) {
            val current = queue.poll() ?: break
            val files = current.listFiles() ?: continue
            for (file in files) {
                if (shouldSkip(file.name)) continue
                if (file.isDirectory) {
                    queue.add(file)
                } else if (file.isFile) {
                    val ext = file.extension
                    if (allowedExts.isEmpty() || allowedExts.contains(ext)) {
                        paths.add(file.absolutePath)
                        if (paths.size >= maxPaths) {
                            truncated = true
                            break
                        }
                    }
                }
            }
            if (truncated) break
        }

        val res = JSONObject()
        val pathsArray = JSONArray()
        for (p in paths) {
            pathsArray.put(p)
        }
        res.put("paths", pathsArray)
        res.put("truncated", truncated)
        res.put("skippedOversize", 0)
        return res
    }

    fun estimateDirSize(args: JSONObject): JSONObject {
        val path = args.getString("path")
        val dir = File(path)
        val count = dir.listFiles()?.size ?: 0
        val res = JSONObject()
        res.put("topLevelCount", count)
        res.put("isSystemDir", false)
        res.put("warning", count > 500)
        return res
    }
}
