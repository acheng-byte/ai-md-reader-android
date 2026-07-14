package com.mdreader.app

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 轻量日志系统：环状缓冲区，保留最近 10000 条。
 * 支持磁盘持久化，重启后自动加载。
 */
object Logger {

    private const val MAX_ENTRIES = 10000
    private const val LOG_FILE = "app_log.txt"
    private const val SAVE_INTERVAL = 100  // 每 N 条写入一次磁盘

    private val entries = ArrayDeque<String>(MAX_ENTRIES)
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    private var errorCounter = 0
    private var logFile: File? = null
    private var pendingWrites = 0

    /** 初始化日志系统，从磁盘加载历史日志 */
    fun init(context: Context) {
        logFile = File(context.filesDir, LOG_FILE)
        loadFromDisk()
    }

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
            val removed = entries.removeFirst()
            if (removed.contains("/E/")) errorCounter--
        }
        entries.addLast(line)
        if (level == "E") errorCounter++

        // 异步写入磁盘
        pendingWrites++
        if (pendingWrites >= SAVE_INTERVAL) {
            pendingWrites = 0
            Thread { saveToDisk() }.start()
        }

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

    /** 获取所有日志条目列表（倒序，最新在前），用于 RecyclerView 显示。 */
    @Synchronized
    fun getAllEntries(): List<String> {
        return entries.asReversed().toList()
    }

    /** 获取摘要条目列表（倒序，仅 W/E），用于 RecyclerView 显示。 */
    @Synchronized
    fun getSummaryEntries(): List<String> {
        return entries.asReversed()
            .filter { it.contains("/W/") || it.contains("/E/") }
    }

    /** 获取条目数 */
    @Synchronized
    fun size(): Int = entries.size

    /** 获取错误数量 */
    @Synchronized
    fun errorCount(): Int = errorCounter

    /** 清空日志 */
    @Synchronized
    fun clear() {
        entries.clear()
        errorCounter = 0
        pendingWrites = 0
        // 清空磁盘文件
        try {
            logFile?.delete()
        } catch (_: Exception) {}
    }

    /** 从磁盘加载历史日志 */
    private fun loadFromDisk() {
        try {
            val file = logFile ?: return
            if (!file.exists()) return
            val lines = file.readLines()
            entries.clear()
            errorCounter = 0
            // 只保留最后 MAX_ENTRIES 条
            val start = maxOf(0, lines.size - MAX_ENTRIES)
            for (i in start until lines.size) {
                val line = lines[i]
                entries.addLast(line)
                if (line.contains("/E/")) errorCounter++
            }
            android.util.Log.i("Logger", "从磁盘加载 ${entries.size} 条日志")
        } catch (e: Exception) {
            android.util.Log.e("Logger", "加载日志失败: ${e.message}")
        }
    }

    /** 保存日志到磁盘 */
    @Synchronized
    fun saveToDisk() {
        try {
            val file = logFile ?: return
            val lines: List<String>
            synchronized(this) {
                lines = entries.toList()
            }
            file.writeText(lines.joinToString("\n"))
        } catch (e: Exception) {
            android.util.Log.e("Logger", "保存日志失败: ${e.message}")
        }
    }
}
