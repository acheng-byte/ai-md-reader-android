package com.mdreader.app

import android.webkit.JavascriptInterface

/**
 * 暴露给 WebView 内 JS 的桥。
 * - 读取：Markdown 文本、初始设置、初始模式。
 * - 回写：onModeChanged（JS 切换模式时同步原生菜单）、copyText（代码块复制走原生剪贴板）、
 *   onCenterTap（点击正文中央区域时唤出显示设置）。
 * 注意：以下方法运行在 WebView 的 binder 线程，provider 实现需自行切回主线程。
 */
class MarkdownBridge(private val provider: Provider) {

    interface Provider {
        fun markdown(): String
        fun settingsJson(): String
        fun initialMode(): String
        fun onModeChanged(mode: String)
        fun copyText(text: String)
        fun onCenterTap()
    }

    @JavascriptInterface
    fun getMarkdown(): String = provider.markdown()

    @JavascriptInterface
    fun getSettingsJson(): String = provider.settingsJson()

    @JavascriptInterface
    fun getInitialMode(): String = provider.initialMode()

    @JavascriptInterface
    fun onModeChanged(mode: String) = provider.onModeChanged(mode)

    @JavascriptInterface
    fun copyText(text: String) = provider.copyText(text)

    @JavascriptInterface
    fun onCenterTap() = provider.onCenterTap()
}
