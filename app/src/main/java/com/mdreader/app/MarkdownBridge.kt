package com.mdreader.app

import android.webkit.JavascriptInterface

/**
 * 暴露给 WebView 内 JS 的桥。v1.5.1 新增：wikilink 导航、Vault 搜索、文件打开。
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
        // v1.5.1
        fun openWikiLink(noteName: String)
        fun searchVault(query: String): String
        fun openVaultFile(uri: String)
    }

    @JavascriptInterface fun getMarkdown(): String = provider.markdown()
    @JavascriptInterface fun getSettingsJson(): String = provider.settingsJson()
    @JavascriptInterface fun getInitialMode(): String = provider.initialMode()
    @JavascriptInterface fun getInitialScrollRatio(): Double = provider.readingRatio()
    @JavascriptInterface fun onModeChanged(mode: String) = provider.onModeChanged(mode)
    @JavascriptInterface fun copyText(text: String) = provider.copyText(text)
    @JavascriptInterface fun onCenterTap() = provider.onCenterTap()
    @JavascriptInterface fun saveScrollRatio(ratio: Double) = provider.saveReadingRatio(ratio)

    /** 导航到 [[wikilink]] 指向的文档（在原生侧解析 Vault 文件夹）。 */
    @JavascriptInterface
    fun openWikiLink(noteName: String) = provider.openWikiLink(noteName)

    /** 全库搜索，返回 JSON 数组 [{uri, name, excerpt}]。 */
    @JavascriptInterface
    fun searchVault(query: String): String = provider.searchVault(query)

    /** 从搜索结果打开指定文件（content:// URI 字符串）。 */
    @JavascriptInterface
    fun openVaultFile(uri: String) = provider.openVaultFile(uri)
}
