package com.mdreader.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 历史文档的可访问状态：可用 / 授权过期（如微信临时授权失效）/ 已删除（物理文件不存在）。 */
enum class DocStatus { AVAILABLE, EXPIRED, DELETED }

/**
 * 打开历史记录存储。
 *
 * 性能优化：内存缓存 + 延迟刷盘。
 * - all() 首次从 SP 加载后缓存，后续直接返回内存列表
 * - add()/remove() 直接修改内存缓存，异步写 SP
 */
class History(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("mdreader_history", Context.MODE_PRIVATE)

    data class Entry(val uri: String, val name: String, val time: Long)

    /** 内存缓存 */
    private var cache: MutableList<Entry>? = null

    private fun ensureLoaded(): MutableList<Entry> {
        cache?.let { return it }
        val raw = sp.getString(KEY, "[]") ?: "[]"
        val arr = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        val list = ArrayList<Entry>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val uri = o.optString("uri")
            if (uri.isNullOrEmpty()) continue
            list.add(Entry(uri, o.optString("name", uri), o.optLong("time")))
        }
        cache = list
        return list
    }

    fun all(): List<Entry> = ensureLoaded().toList()

    /** 记录一次打开：相同 URI 去重并置顶，更新时间，最多保留 MAX 条。 */
    fun add(uri: String, name: String, time: Long) {
        val list = ensureLoaded()
        list.removeAll { it.uri == uri }
        list.add(0, Entry(uri, name, time))
        while (list.size > MAX) list.removeAt(list.size - 1)
        saveAsync(list)
    }

    fun clear() {
        cache = mutableListOf()
        sp.edit().remove(KEY).apply()
    }

    /** 删除单条历史记录 */
    fun remove(uri: String) {
        val list = ensureLoaded()
        list.removeAll { it.uri == uri }
        saveAsync(list)
    }

    /** 异步写 SP，不阻塞主线程 */
    private fun saveAsync(list: List<Entry>) {
        Thread {
            val arr = JSONArray()
            list.forEach {
                arr.put(JSONObject().put("uri", it.uri).put("name", it.name).put("time", it.time))
            }
            sp.edit().putString(KEY, arr.toString()).apply()
        }.start()
    }

    companion object {
        private const val KEY = "items"
        private const val MAX = 200
    }
}
