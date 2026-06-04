package com.mdreader.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 历史文档的可访问状态：可用 / 授权过期（如微信临时授权失效）/ 已删除（物理文件不存在）。 */
enum class DocStatus { AVAILABLE, EXPIRED, DELETED }

/** 打开历史记录存储（SharedPreferences 内存一个 JSON 数组，按最近打开排序，去重，限量）。 */
class History(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("mdreader_history", Context.MODE_PRIVATE)

    data class Entry(val uri: String, val name: String, val time: Long)

    fun all(): List<Entry> {
        val raw = sp.getString(KEY, "[]") ?: "[]"
        val arr = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        val list = ArrayList<Entry>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val uri = o.optString("uri")
            if (uri.isNullOrEmpty()) continue
            list.add(Entry(uri, o.optString("name", uri), o.optLong("time")))
        }
        return list
    }

    /** 记录一次打开：相同 URI 去重并置顶，更新时间，最多保留 MAX 条。 */
    fun add(uri: String, name: String, time: Long) {
        val list = all().filterNot { it.uri == uri }.toMutableList()
        list.add(0, Entry(uri, name, time))
        while (list.size > MAX) list.removeAt(list.size - 1)
        save(list)
    }

    fun clear() = sp.edit().remove(KEY).apply()

    private fun save(list: List<Entry>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("uri", it.uri).put("name", it.name).put("time", it.time))
        }
        sp.edit().putString(KEY, arr.toString()).apply()
    }

    companion object {
        private const val KEY = "items"
        private const val MAX = 50
    }
}
