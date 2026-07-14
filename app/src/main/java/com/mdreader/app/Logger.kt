package com.mdreader.app

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 轻量日志系统：环状缓冲区，保留最近 500 条。
 * 用于诊断 Vault 文件查找等问题。
 */
object Logger {

    private const val MAX_ENTRIES = 500
    private val entries = ArrayDeque<String>(MAX_ENTRIES)
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    @Synchronized
    fun i(tag: String, msg: String) {
        add("I", tag, msg)
    }

    @Synchronized
    fun w(tag: String, msg: String) {
        add("W", tag, msg)
    }

    @Synchronized
    fun e(tag: String, msg: String) {
        add("E", tag, msg)
    }

    @Synchronized
    fun d(tag: String, msg: String) {
        add("D", tag, msg)
    }

    private fun add(level: String, tag: String, msg: String) {
        val time = timeFormat.format(Date())
        val line = "[$time] $level/$tag: $msg"
        if (entries.size >= MAX_ENTRIES) {
            entries.removeFirst()
        }
        entries.addLast(line)
        // 同时输出到 Logcat
        when (level) {
            "E" -> android.util.Log.e(tag, msg)
            "W" -> android.util.Log.w(tag, msg)
            "D" -> android.util.Log.d(tag, msg)
            else -> android.util.Log.i(tag, msg)
        }
    }

    /** 获取所有日志文本（用于复制） */
    @Synchronized
    fun getAllText(): String {
        return entries.joinToString("\n")
    }

    /** 获取条目数 */
    @Synchronized
    fun size(): Int = entries.size

    /** 清空日志 */
    @Synchronized
    fun clear() {
        entries.clear()
    }
}
