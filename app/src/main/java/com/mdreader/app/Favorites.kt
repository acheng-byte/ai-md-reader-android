package com.mdreader.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * 收藏夹：收藏文档时把内容复制一份到应用私有目录 filesDir/favorites/，
 * 这样原始文件被删除或授权过期后仍可从收藏夹打开。
 * - 以原始 URI 为身份去重：同一 URI 不会重复复制（add 前先查 find）。
 * - 物理文件名由 URI 的哈希决定，故同一文档在目录下始终只有一份副本。
 * - 取消收藏会同步删除该副本文件。
 */
class Favorites(context: Context) {

    private val app = context.applicationContext
    private val sp = app.getSharedPreferences("mdreader_favorites", Context.MODE_PRIVATE)
    private val dir = File(app.filesDir, "favorites").apply { if (!exists()) mkdirs() }

    data class Fav(val uri: String, val name: String, val file: String, val time: Long)

    fun all(): List<Fav> {
        val raw = sp.getString(KEY, "[]") ?: "[]"
        val arr = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        val list = ArrayList<Fav>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val uri = o.optString("uri")
            if (uri.isNullOrEmpty()) continue
            list.add(Fav(uri, o.optString("name", uri), o.optString("file"), o.optLong("time")))
        }
        return list
    }

    fun isFavorite(uri: String): Boolean = all().any { it.uri == uri }

    fun find(uri: String): Fav? = all().firstOrNull { it.uri == uri }

    fun fileOf(fav: Fav): File = File(dir, fav.file)

    /** 收藏：把内容写入收藏目录。已收藏（同一 URI）则直接返回已有项，不重复复制。 */
    fun add(uri: String, name: String, content: ByteArray): Fav? {
        find(uri)?.let { return it }
        val fileName = fileNameFor(uri, name)
        return try {
            File(dir, fileName).writeBytes(content)
            val fav = Fav(uri, name, fileName, System.currentTimeMillis())
            save(all().toMutableList().apply { add(0, fav) })
            fav
        } catch (e: Exception) {
            null
        }
    }

    /** 取消收藏：删除条目并同步删除副本文件。 */
    fun remove(uri: String) {
        val list = all().toMutableList()
        val idx = list.indexOfFirst { it.uri == uri }
        if (idx >= 0) {
            val fav = list.removeAt(idx)
            runCatching { File(dir, fav.file).delete() }
            save(list)
        }
    }

    private fun save(list: List<Fav>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject().put("uri", it.uri).put("name", it.name)
                    .put("file", it.file).put("time", it.time)
            )
        }
        sp.edit().putString(KEY, arr.toString()).apply()
    }

    // 文件名由 URI 哈希决定，保证同一文档只对应一个副本
    private fun fileNameFor(uri: String, name: String): String {
        val ext = if (name.endsWith(".markdown", ignoreCase = true)) "markdown" else "md"
        return sha1(uri) + "." + ext
    }

    private fun sha1(s: String): String {
        val bytes = MessageDigest.getInstance("SHA-1").digest(s.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val KEY = "items"
    }
}
