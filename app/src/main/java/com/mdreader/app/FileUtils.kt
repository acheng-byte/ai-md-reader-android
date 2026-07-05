package com.mdreader.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/** 读取本地/分享而来的文件内容与显示名（兼容 content:// 与 file://）。 */
object FileUtils {

    @Throws(IOException::class)
    fun readText(context: Context, uri: Uri): String {
        val name = displayName(context, uri) ?: ""
        val ext = name.substringAfterLast('.', "").lowercase()
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IOException("无法打开文件输入流")

        return when (ext) {
            "txt" -> {
                val text = decodeText(bytes)
                wrapPlainText(name, text)
            }
            "docx" -> extractDocxText(name, bytes)
            "doc" -> {
                val text = extractDocLegacyText(bytes)
                wrapPlainText(name, text)
            }
            else -> {
                decodeText(bytes)
            }
        }
    }

    private fun decodeText(bytes: ByteArray): String {
        // Detect encoding: count UTF-8 replacement characters to decide if it's really GBK
        val utf8Text = bytes.toString(Charsets.UTF_8)
        val replacements = utf8Text.count { it == '�' }
        val text = if (replacements > 0 && replacements.toDouble() / utf8Text.length > 0.01) {
            // Likely GBK/GB2312 (common for Chinese Windows files)
            runCatching { bytes.toString(charset("GBK")) }.getOrElse { utf8Text }
        } else {
            utf8Text
        }
        // Strip UTF-8 BOM
        return if (text.isNotEmpty() && text[0] == '﻿') text.substring(1) else text
    }

    private fun wrapPlainText(filename: String, text: String): String {
        val title = filename.substringBeforeLast('.')
        val escaped = text.replace("```", "\\`\\`\\`")
        return "# $title\n\n```\n$escaped\n```"
    }

    /** Extract readable markdown from DOCX by parsing word/document.xml inside the ZIP. */
    private fun extractDocxText(filename: String, bytes: ByteArray): String {
        return runCatching {
            var xmlContent: String? = null
            ZipInputStream(bytes.inputStream()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (entry.name == "word/document.xml") {
                        xmlContent = parseWordXml(zip)
                        break
                    }
                    entry = zip.nextEntry
                }
            }
            val content = xmlContent?.trim() ?: ""
            val title = filename.substringBeforeLast('.')
            if (content.isEmpty()) "# $title\n\n（文档内容为空）"
            else "# $title\n\n$content"
        }.getOrElse { e ->
            "# 解析失败\n\n（DOCX 解析失败：${e.message}）"
        }
    }

    /** Parse word/document.xml and extract text as markdown paragraphs. */
    private fun parseWordXml(input: InputStream): String {
        val sb = StringBuilder()
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = true
        val parser = factory.newPullParser()
        parser.setInput(input, "UTF-8")

        var paraBuffer = StringBuilder()
        var headingLevel = 0
        var event = parser.eventType

        while (event != XmlPullParser.END_DOCUMENT) {
            val ns = parser.namespace ?: ""
            val localName = parser.name ?: ""
            val isWordNs = ns.contains("wordprocessingml")

            when (event) {
                XmlPullParser.START_TAG -> {
                    when {
                        localName == "p" && isWordNs -> {
                            paraBuffer = StringBuilder()
                            headingLevel = 0
                        }
                        localName == "pStyle" && isWordNs -> {
                            val styleVal = parser.getAttributeValue(null, "val") ?: ""
                            headingLevel = when {
                                styleVal.equals("Heading1", ignoreCase = true) || styleVal == "1" -> 1
                                styleVal.equals("Heading2", ignoreCase = true) || styleVal == "2" -> 2
                                styleVal.equals("Heading3", ignoreCase = true) || styleVal == "3" -> 3
                                styleVal.equals("Heading4", ignoreCase = true) || styleVal == "4" -> 4
                                else -> 0
                            }
                        }
                        localName == "t" && isWordNs -> {
                            val text = parser.nextText()
                            paraBuffer.append(text)
                        }
                        localName == "br" && isWordNs -> {
                            paraBuffer.append('\n')
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (localName == "p" && isWordNs) {
                        val paraText = paraBuffer.toString()
                        if (paraText.isNotBlank()) {
                            if (headingLevel > 0) {
                                sb.append("#".repeat(headingLevel)).append(' ')
                            }
                            sb.append(paraText.trim()).append("\n\n")
                        } else {
                            sb.append('\n')
                        }
                    }
                }
            }
            event = try { parser.next() } catch (e: Exception) { break }
        }
        return sb.toString().trimEnd()
    }

    /** Best-effort legacy .doc text extraction. */
    private fun extractDocLegacyText(bytes: ByteArray): String {
        val sb = StringBuilder()
        var spaceCount = 0
        for (byte in bytes) {
            val b = byte.toInt() and 0xFF
            when {
                b in 0x20..0x7E -> { sb.append(b.toChar()); spaceCount = 0 }
                b == 0x0A || b == 0x0D -> { if (spaceCount < 2) sb.append('\n'); spaceCount++ }
                else -> { if (spaceCount == 0) sb.append(' '); spaceCount++ }
            }
        }
        val text = sb.toString().replace(Regex("[ ]{4,}"), "\n\n").trim()
        return if (text.length > 200) text
        else "（旧版 .doc 格式解析内容有限，建议转换为 .docx 后重新打开）\n\n$text"
    }

    fun displayName(context: Context, uri: Uri): String? {
        if (uri.scheme == "content") {
            runCatching {
                context.contentResolver.query(
                    uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
                )?.use { c ->
                    if (c.moveToFirst()) {
                        val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) return c.getString(idx)
                    }
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')
    }
}
