package com.luminanote.mobile.bridge

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

class StoreBridgeHandler(private val context: Context) {

    private val lock = ReentrantReadWriteLock()
    private val storeFile = File(context.filesDir, "lumina-store.json")
    private var cache = mutableMapOf<String, Any?>()
    private var isLoaded = false

    private fun ensureLoaded() {
        if (isLoaded) return
        lock.write {
            if (isLoaded) return
            if (storeFile.exists()) {
                try {
                    val raw = storeFile.readText(Charsets.UTF_8)
                    val json = JSONObject(raw)
                    val map = mutableMapOf<String, Any?>()
                    val keys = json.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        map[k] = json.opt(k)
                    }
                    cache = map
                } catch (_: Exception) {
                    cache = mutableMapOf()
                }
            } else {
                cache = mutableMapOf()
            }
            isLoaded = true
        }
    }

    private fun persist() {
        lock.read {
            try {
                val json = JSONObject()
                for ((k, v) in cache) {
                    json.put(k, v ?: JSONObject.NULL)
                }
                storeFile.parentFile?.mkdirs()
                storeFile.writeText(json.toString(2), Charsets.UTF_8)
            } catch (_: Exception) {}
        }
    }

    fun get(key: String): Any? {
        ensureLoaded()
        return lock.read { cache[key] }
    }

    fun set(key: String, value: Any?) {
        ensureLoaded()
        lock.write {
            cache[key] = value
        }
        persist()
    }

    fun delete(key: String) {
        ensureLoaded()
        lock.write {
            cache.remove(key)
        }
        persist()
    }

    fun clear() {
        ensureLoaded()
        lock.write {
            cache.clear()
        }
        persist()
    }

    fun keys(): JSONArray {
        ensureLoaded()
        return lock.read {
            val arr = JSONArray()
            cache.keys.forEach { arr.put(it) }
            arr
        }
    }

    fun values(): JSONArray {
        ensureLoaded()
        return lock.read {
            val arr = JSONArray()
            cache.values.forEach { arr.put(it ?: JSONObject.NULL) }
            arr
        }
    }

    fun entries(): JSONArray {
        ensureLoaded()
        return lock.read {
            val arr = JSONArray()
            for ((k, v) in cache) {
                val pair = JSONArray()
                pair.put(k)
                pair.put(v ?: JSONObject.NULL)
                arr.put(pair)
            }
            arr
        }
    }

    fun length(): Int {
        ensureLoaded()
        return lock.read { cache.size }
    }
}
