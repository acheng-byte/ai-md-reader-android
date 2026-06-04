package com.mdreader.app

import android.webkit.JavascriptInterface

/**
 * 暴露给 WebView 内 JS 的桥。
 * - 读取：Markdown 文本、初始设置、初始模式（规避把内容拼进 HTML 的转义/大小问题）。
 * - 回写：onModeChanged —— 当 JS 自行切换模式（如目录跳转时从源码切回预览）时通知原生，保持菜单状态同步。
 * 注意：以下方法运行在 WebView 的 binder 线程，provider 实现需自行处理线程安全/切回主线程。
 */
class MarkdownBridge(private val provider: Provider) {

    interface Provider {
        fun markdown(): String
        fun settingsJson(): String
        fun initialMode(): String
        fun onModeChanged(mode: String)
    }

    @JavascriptInterface
    fun getMarkdown(): String = provider.markdown()

    @JavascriptInterface
    fun getSettingsJson(): String = provider.settingsJson()

    @JavascriptInterface
    fun getInitialMode(): String = provider.initialMode()

    @JavascriptInterface
    fun onModeChanged(mode: String) = provider.onModeChanged(mode)
}
