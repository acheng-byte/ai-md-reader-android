package com.mdreader.app

import android.webkit.JavascriptInterface

/**
 * 暴露给 WebView 内 JS 的桥。
 * - 读取：Markdown 文本、初始设置、初始模式、上次阅读位置（滚动比例）。
 * - 回写：onModeChanged（JS 切换模式时同步原生菜单）、copyText（代码块复制走原生剪贴板）、
 *   onCenterTap（点击正文中央区域时唤出显示设置）、saveScrollRatio（滚动时记忆阅读位置）。
 * 注意：以下方法运行在 WebView 的 binder 线程，provider 实现需自行切回主线程。
 */
class MarkdownBridge(private val provider: Provider) {

    interface Provider {
        fun markdown(): String
        fun settingsJson(): String
        fun initialMode(): String
        fun readingRatio(): Double
        fun onModeChanged(mode: String)
        fun copyText(text: String)
        fun onCenterTap()
        fun saveReadingRatio(ratio: Double)
    }

    @JavascriptInterface
    fun getMarkdown(): String = provider.markdown()

    @JavascriptInterface
    fun getSettingsJson(): String = provider.settingsJson()

    @JavascriptInterface
    fun getInitialMode(): String = provider.initialMode()

    /** 当前文档上次阅读的滚动比例（0..1），无记录或欢迎页返回 0。 */
    @JavascriptInterface
    fun getInitialScrollRatio(): Double = provider.readingRatio()

    @JavascriptInterface
    fun onModeChanged(mode: String) = provider.onModeChanged(mode)

    @JavascriptInterface
    fun copyText(text: String) = provider.copyText(text)

    @JavascriptInterface
    fun onCenterTap() = provider.onCenterTap()

    /** 阅读滚动时记忆当前文档的滚动比例（0..1）。 */
    @JavascriptInterface
    fun saveScrollRatio(ratio: Double) = provider.saveReadingRatio(ratio)
}
