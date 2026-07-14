package com.mdreader.app

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 轻量日志系统：环状缓冲区，保留最近 5000 条。
 * 用于诊断 Vault 文件查找等问题。
 */
object Logger {

    private const val MAX_ENTRIES = 5000
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

    /** 获取日志文本（倒序，最新在前）。仅包含 W 和 E 级别，适合手机查看。 */
    @Synchronized
    fun getSummaryText(): String {
        return entries.asReversed()
            .filter { it.contains("/W/") || it.contains("/E/") }
            .joinToString("\n")
    }

    /** 获取所有日志文本（倒序，最新在前），用于复制。 */
    @Synchronized
    fun getAllText(): String {
        return entries.asReversed().joinToString("\n")
    }

    /** 获取条目数 */
    @Synchronized
    fun size(): Int = entries.size

    /** 获取错误数量 */
    @Synchronized
    fun errorCount(): Int = entries.count { it.contains("/E/") }

    /** 清空日志 */
    @Synchronized
    fun clear() {
        entries.clear()
    }
}
