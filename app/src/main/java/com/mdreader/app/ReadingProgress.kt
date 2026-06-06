package com.mdreader.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 阅读位置记忆：以文档身份 URI 为键，保存上次阅读的滚动比例（0..1）。
 * 存比例而非绝对像素，是为了在两次会话间字号/行距/段距变化导致重排后仍能大致定位。
 * 身份 URI 在历史/收藏重开时保持稳定，故位置可跨「原文/副本」「收藏/取消」延续。
 * 存储与 History 一致：SharedPreferences 内一个 JSON 数组，按最近写入排序、按 URI 去重、限量。
 */
class ReadingProgress(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("mdreader_readpos", Context.MODE_PRIVATE)

    private data class Item(val uri: String, val ratio: Double, val time: Long)

    private fun all(): List<Item> {
        val raw = sp.getString(KEY, "[]") ?: "[]"
        val arr = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        val list = ArrayList<Item>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val uri = o.optString("uri")
            if (uri.isNullOrEmpty()) continue
            list.add(Item(uri, o.optDouble("ratio", 0.0), o.optLong("time")))
        }
        return list
    }

    /** 取某文档上次阅读比例；无记录返回 0.0（即顶部）。 */
    fun get(uri: String): Double = all().firstOrNull { it.uri == uri }?.ratio ?: 0.0

    /** 记录某文档的阅读比例：同一 URI 去重并置顶，更新时间，最多保留 MAX 条。 */
    fun set(uri: String, ratio: Double) {
        val r = ratio.coerceIn(0.0, 1.0)
        val list = all().filterNot { it.uri == uri }.toMutableList()
        list.add(0, Item(uri, r, System.currentTimeMillis()))
        while (list.size > MAX) list.removeAt(list.size - 1)
        save(list)
    }

    private fun save(list: List<Item>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("uri", it.uri).put("ratio", it.ratio).put("time", it.time))
        }
        sp.edit().putString(KEY, arr.toString()).apply()
    }

    companion object {
        private const val KEY = "items"
        private const val MAX = 300
    }
}
