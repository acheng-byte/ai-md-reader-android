package com.mdreader.app

import android.webkit.JavascriptInterface

/**
 * 暴露给 WebView 内 JS 的桥。
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
        fun openWikiLink(noteName: String)
        fun searchVault(query: String): String
        fun searchVaultAsync(query: String, callbackId: String)
        fun openVaultFile(uri: String)
        /** 按文件名在 Vault 中查找文件，返回 URI 字符串（供内联展开用）。 */
        fun searchVaultForEmbed(ref: String): String
        /** 加载 wikilink 嵌入文档的内容，返回 Markdown 文本（供内联展开用）。 */
        fun loadEmbedContent(uri: String): String
        /** 当前文档标题（文件名），供 JS 端隐藏重复的一级标题 */
        fun docTitle(): String
        /** 保存 Mermaid 图表 SVG 到本地文件 */
        fun saveMermaidImage(svgHtml: String)
        /** 保存表格或图表元素为 PNG 图片（type="mermaid"|"table"，html 为元素 HTML） */
        fun saveElementImage(type: String, html: String)
    }

    @JavascriptInterface fun getMarkdown(): String = provider.markdown()
    @JavascriptInterface fun getSettingsJson(): String = provider.settingsJson()
    @JavascriptInterface fun getInitialMode(): String = provider.initialMode()
    @JavascriptInterface fun getInitialScrollRatio(): Double = provider.readingRatio()
    @JavascriptInterface fun onModeChanged(mode: String) = provider.onModeChanged(mode)
    @JavascriptInterface fun copyText(text: String) = provider.copyText(text)
    @JavascriptInterface fun onCenterTap() = provider.onCenterTap()
    @JavascriptInterface fun saveScrollRatio(ratio: Double) = provider.saveReadingRatio(ratio)

    @JavascriptInterface
    fun openWikiLink(noteName: String) = provider.openWikiLink(noteName)

    @JavascriptInterface
    fun searchVault(query: String): String = provider.searchVault(query)

    @JavascriptInterface
    fun searchVaultAsync(query: String, callbackId: String) = provider.searchVaultAsync(query, callbackId)

    @JavascriptInterface
    fun openVaultFile(uri: String) = provider.openVaultFile(uri)

    @JavascriptInterface
    fun searchVaultForEmbed(ref: String): String = runCatching { provider.searchVaultForEmbed(ref) }.getOrDefault("")

    /** 同步加载嵌入文档内容（在 binder 线程执行 I/O，不阻塞 UI 线程）。 */
    @JavascriptInterface
    fun loadEmbedContent(uri: String): String = runCatching { provider.loadEmbedContent(uri) }.getOrDefault("")

    @JavascriptInterface
    fun getTitle(): String = provider.docTitle()

    @JavascriptInterface
    fun saveMermaidImage(svgHtml: String) = provider.saveMermaidImage(svgHtml)

    @JavascriptInterface
    fun saveElementImage(type: String, html: String) = provider.saveElementImage(type, html)
}
