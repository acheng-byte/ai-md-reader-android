package com.mdreader.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 阅读位置记忆：以文档身份 URI 为键，保存上次阅读的滚动比例（0..1）。
 * 存比例而非绝对像素，是为了在两次会话间字号/行距/段距变化导致重排后仍能大致定位。
 *
 * 性能优化：内存缓存 + 延迟刷盘。
 * - get/set 操作直接读写内存 HashMap，不触发 SP I/O
 * - set 仅标记 dirty，由 flush() 统一写盘
 * - MainActivity 在 onPause/onDestroy 时调用 flush()
 */
class ReadingProgress(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("mdreader_readpos", Context.MODE_PRIVATE)

    private data class Item(val uri: String, val ratio: Double, val time: Long)

    /** 内存缓存：uri -> Item */
    private val cache = HashMap<String, Item>()
    /** 是否已从 SP 加载过 */
    private var loaded = false
    /** 自上次 flush 以来是否有修改 */
    @Volatile private var dirty = false

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val raw = sp.getString(KEY, "[]") ?: "[]"
        val arr = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val uri = o.optString("uri")
            if (uri.isNullOrEmpty()) continue
            cache[uri] = Item(uri, o.optDouble("ratio", 0.0), o.optLong("time"))
        }
    }

    /** 取某文档上次阅读比例；无记录返回 0.0（即顶部）。 */
    fun get(uri: String): Double {
        ensureLoaded()
        return cache[uri]?.ratio ?: 0.0
    }

    /** 记录某文档的阅读比例：只写内存缓存，标记 dirty。 */
    fun set(uri: String, ratio: Double) {
        ensureLoaded()
        val r = ratio.coerceIn(0.0, 1.0)
        cache[uri] = Item(uri, r, System.currentTimeMillis())
        dirty = true
        // 防止缓存无限增长
        if (cache.size > MAX) trimCache()
    }

    /** 将脏数据刷入 SP。应在 onPause/onDestroy 时调用。 */
    fun flush() {
        if (!dirty) return
        dirty = false
        val sorted = cache.values.sortedByDescending { it.time }.take(MAX)
        val arr = JSONArray()
        sorted.forEach {
            arr.put(JSONObject().put("uri", it.uri).put("ratio", it.ratio).put("time", it.time))
        }
        sp.edit().putString(KEY, arr.toString()).apply()
    }

    /** 缓存超限时按时间淘汰最旧的条目 */
    private fun trimCache() {
        val sorted = cache.values.sortedByDescending { it.time }
        val toRemove = sorted.drop(MAX)
        toRemove.forEach { cache.remove(it.uri) }
    }

    companion object {
        private const val KEY = "items"
        private const val MAX = 300
    }
}
