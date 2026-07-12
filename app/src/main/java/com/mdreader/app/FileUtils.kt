package com.mdreader.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Base64
import org.apache.poi.hwpf.HWPFDocument
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayOutputStream
import java.io.File
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
            "doc" -> extractDocText(name, bytes)
            "pdf" -> extractPdfImages(context, name, uri)
            else -> {
                decodeText(bytes)
            }
        }
    }

    private fun decodeText(bytes: ByteArray): String {
        var text: String

        // 1. BOM-based detection (most reliable)
        if (bytes.size >= 2) {
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            // UTF-16LE: FF FE
            if (b0 == 0xFF && b1 == 0xFE) {
                text = bytes.toString(charset("UTF-16LE"))
                    .replace("\uFEFF", "")
                return normalizeLineEndings(text)
            }
            // UTF-16BE: FE FF
            if (b0 == 0xFE && b1 == 0xFF) {
                text = bytes.toString(charset("UTF-16BE"))
                    .replace("\uFEFF", "")
                return normalizeLineEndings(text)
            }
            // UTF-8 BOM: EF BB BF
            if (bytes.size >= 3 && b0 == 0xEF && b1 == 0xBB && (bytes[2].toInt() and 0xFF) == 0xBF) {
                text = bytes.toString(Charsets.UTF_8)
                if (text.isNotEmpty() && text[0] == '\uFEFF') text = text.substring(1)
                return normalizeLineEndings(text)
            }
        }

        // 2. Try UTF-8; count replacement characters to decide fallback
        val utf8Text = bytes.toString(Charsets.UTF_8)
        val replacements = utf8Text.count { it == '\uFFFD' }
        text = if (replacements > 0 && replacements.toDouble() / maxOf(utf8Text.length, 1) > 0.01) {
            // GB18030 is a superset of GBK/GB2312, covers more Chinese characters
            runCatching { bytes.toString(charset("GB18030")) }.getOrElse {
                runCatching { bytes.toString(charset("GBK")) }.getOrElse { utf8Text }
            }
        } else {
            utf8Text
        }

        // 3. Strip UTF-8 BOM if still present (safety net)
        if (text.isNotEmpty() && text[0] == '\uFEFF') text = text.substring(1)

        return normalizeLineEndings(text)
    }

    /** \r\n 和孤立 \r 统一为 \n，避免不同平台的换行符差异。 */
    private fun normalizeLineEndings(s: String): String =
        s.replace("\r\n", "\n").replace('\r', '\n')

    private fun wrapPlainText(filename: String, text: String): String {
        val title = filename.substringBeforeLast('.')
        // Escape markdown special chars so plain text renders as-is
        val escaped = text
            .replace("\\", "\\\\")       // 反斜杠（最先处理）
            .replace("`", "\\`")         // 行内代码
            .replace("*", "\\*")         // 强调 / 列表
            .replace("_", "\\_")         // 强调
            .replace("#", "\\#")         // 标题
            .replace("[", "\\[")         // 链接 / 图片
            .replace("|", "\\|")         // 表格
            .replace(">", "\\>")         // 引用
            .replace("~", "\\~")         // 删除线 ~~text~~
            .replace("=", "\\=")         // Obsidian 高亮 ==text==
            .replace("!", "\\!")         // 图片 ![]()
            .replace("<", "\\<")         // 内嵌 HTML
            .replace("+", "\\+")         // 无序列表 +
        // 行首数字 + . / ) 会被解析为有序列表，需转义数字
        val lineStartList = StringBuilder()
        var atLineStart = true
        for (ch in escaped) {
            if (atLineStart && ch.isDigit()) {
                lineStartList.append('\\')
            }
            lineStartList.append(ch)
            atLineStart = (ch == '\n')
        }
        return "# $title\n\n$lineStartList"
    }

    /* ========== DOCX（ZIP + XML）提取文本 + 内嵌图片 ========== */

    private fun extractDocxText(filename: String, bytes: ByteArray): String {
        return runCatching {
            var xmlContent: String? = null
            val images = HashMap<String, String>()  // rId → base64 data URI

            ZipInputStream(bytes.inputStream()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    when {
                        entry.name == "word/document.xml" -> {
                            xmlContent = zip.readBytes().toString(Charsets.UTF_8)
                        }
                        entry.name.startsWith("word/media/") -> {
                            val imgBytes = zip.readBytes()
                            val rId = "media_" + entry.name.substringAfterLast('/')
                            images[rId] = encodeImageAsDataUri(imgBytes, entry.name)
                        }
                    }
                    entry = zip.nextEntry
                }
            }

            val rawXml = xmlContent?.trim() ?: ""
            val title = filename.substringBeforeLast('.')
            if (rawXml.isEmpty()) return "# $title\n\n（文档内容为空）"

            val (text, inlineImgs) = parseDocxXml(rawXml, images)
            val sb = StringBuilder("# $title\n\n")

            // 文档顶部图片（出现在第一段文字之前）
            if (inlineImgs.isNotEmpty()) {
                inlineImgs.forEach { sb.append(it).append("\n\n") }
            }
            sb.append(text)

            // 文档内提取到的其他图片（在文末展示）
            val unusedImgs = images.values.toSet() - inlineImgs.toSet()
            if (unusedImgs.isNotEmpty()) {
                sb.append("\n\n---\n\n**文档内嵌图片：**\n\n")
                unusedImgs.forEach { sb.append("![]($it)\n\n") }
            }

            if (text.isBlank() && inlineImgs.isEmpty())
                "# $title\n\n（文档内容为空）"
            else sb.toString().trimEnd()
        }.getOrElse { e ->
            "# 解析失败\n\n（DOCX 解析失败：${e.message}）"
        }
    }

    /**
     * 解析 word/document.xml，提取段落文本（含标题级别）、表格（转 Markdown 表格）并识别内嵌图片。
     * 返回 Pair(文本内容, 图片 data URI 列表)
     */
    private fun parseDocxXml(
        xml: String,
        images: Map<String, String>
    ): Pair<String, List<String>> {
        val sb = StringBuilder()
        val foundImages = ArrayList<String>()
        val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }
        val parser = factory.newPullParser()
        parser.setInput(xml.byteInputStream(), "UTF-8")

        var paraBuffer = StringBuilder()
        var headingLevel = 0

        // 表格状态
        var inTable = false
        var tableRows = ArrayList<ArrayList<String>>()   // rows → cells
        var currentRow = ArrayList<String>()
        var cellBuffer = StringBuilder()
        var inCell = false

        var event = parser.eventType

        while (event != XmlPullParser.END_DOCUMENT) {
            val ns = parser.namespace ?: ""
            val localName = parser.name ?: ""
            val isWordNs = ns.contains("wordprocessingml")

            when (event) {
                XmlPullParser.START_TAG -> {
                    when {
                        // ── 表格 ──────────────────────────────────────────────
                        localName == "tbl" && isWordNs -> {
                            inTable = true
                            tableRows = ArrayList()
                        }
                        localName == "tr" && isWordNs && inTable -> {
                            currentRow = ArrayList()
                        }
                        localName == "tc" && isWordNs && inTable -> {
                            inCell = true
                            cellBuffer = StringBuilder()
                        }
                        // ── 段落（表格外）────────────────────────────────────
                        localName == "p" && isWordNs && !inTable -> {
                            paraBuffer = StringBuilder()
                            headingLevel = 0
                        }
                        localName == "pStyle" && isWordNs -> {
                            val styleVal = parser.getAttributeValue(null, "val") ?: ""
                            headingLevel = detectHeadingLevel(styleVal)
                        }
                        // ── 文本 ──────────────────────────────────────────────
                        localName == "t" && isWordNs -> {
                            val text = parser.nextText()
                            if (inCell) cellBuffer.append(text)
                            else paraBuffer.append(text)
                        }
                        localName == "br" && isWordNs -> {
                            if (inCell) cellBuffer.append(' ')
                            else paraBuffer.append('\n')
                        }
                        // ── 图片（DrawingML）─────────────────────────────────
                        localName == "blip" && ns.contains("drawingml") -> {
                            val rId = parser.getAttributeValue(
                                "http://schemas.openxmlformats.org/officeDocument/2006/relationships",
                                "embed"
                            )
                            if (rId != null && images.containsKey(rId)) {
                                foundImages.add(images[rId]!!)
                            }
                        }
                        // ── 图片（旧版 VML）──────────────────────────────────
                        localName == "imagedata" -> {
                            val rId = parser.getAttributeValue(
                                "http://schemas.openxmlformats.org/officeDocument/2006/relationships",
                                "id"
                            )
                            if (rId != null && images.containsKey(rId)) {
                                foundImages.add(images[rId]!!)
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    when {
                        // ── 表格结束：转 Markdown 表格 ────────────────────────
                        localName == "tbl" && isWordNs -> {
                            inTable = false
                            if (tableRows.isNotEmpty()) {
                                val colCount = tableRows.maxOf { it.size }
                                // 表头（第一行）
                                val header = tableRows[0]
                                sb.append("| ")
                                sb.append(header.joinToString(" | ") { it.ifBlank { " " } })
                                // 补齐列数
                                repeat(colCount - header.size) { sb.append(" |  ") }
                                sb.append(" |\n")
                                // 分隔行
                                sb.append("|")
                                repeat(colCount) { sb.append(" --- |") }
                                sb.append("\n")
                                // 数据行
                                for (row in tableRows.drop(1)) {
                                    sb.append("| ")
                                    sb.append(row.joinToString(" | ") { it.ifBlank { " " } })
                                    repeat(colCount - row.size) { sb.append(" |  ") }
                                    sb.append(" |\n")
                                }
                                sb.append("\n")
                            }
                        }
                        localName == "tr" && isWordNs && inTable -> {
                            if (currentRow.isNotEmpty()) tableRows.add(currentRow)
                        }
                        localName == "tc" && isWordNs && inTable -> {
                            // 合并单元格内多段落文字，去掉换行保持单元格整洁
                            currentRow.add(cellBuffer.toString().replace('\n', ' ').trim())
                            inCell = false
                        }
                        // ── 段落结束（表格外）────────────────────────────────
                        localName == "p" && isWordNs && !inTable -> {
                            val paraText = paraBuffer.toString()
                            if (paraText.isNotBlank()) {
                                if (headingLevel > 0) {
                                    sb.append("#".repeat(headingLevel)).append(' ')
                                    sb.append(paraText.trim()).append("\n\n")
                                } else {
                                    val line = paraText.trim()
                                    val escaped = if (line.startsWith("#")) "\\$line" else line
                                    sb.append(escaped).append("\n\n")
                                }
                            } else {
                                sb.append('\n')
                            }
                        }
                    }
                }
            }
            event = try { parser.next() } catch (e: Exception) { break }
        }
        return Pair(sb.toString().trimEnd(), foundImages)
    }

    private fun detectHeadingLevel(styleVal: String): Int = when {
        styleVal.equals("Heading1", ignoreCase = true) ||
        styleVal.equals("标题1") || styleVal.equals("标题 1") -> 1
        styleVal.equals("Heading2", ignoreCase = true) ||
        styleVal.equals("标题2") || styleVal.equals("标题 2") -> 2
        styleVal.equals("Heading3", ignoreCase = true) ||
        styleVal.equals("标题3") || styleVal.equals("标题 3") -> 3
        styleVal.equals("Heading4", ignoreCase = true) ||
        styleVal.equals("标题4") || styleVal.equals("标题 4") -> 4
        else -> 0
    }

    /* ========== 旧版 .doc（OLE2）—— 使用 Apache POI HWPF ========== */

    private fun extractDocText(filename: String, bytes: ByteArray): String {
        return runCatching {
            val doc = HWPFDocument(bytes.inputStream())
            val range = doc.range
            val text = range.text()
                .replace('\r', '\n')
                .replace('\u0007', '\t')  // 表格单元格分隔符
                .replace(Regex("[\\x00-\\x06\\x08\\x0e-\\x1f]"), "")  // 去除控制字符
                .trim()

            val title = filename.substringBeforeLast('.')
            val sb = StringBuilder("# $title\n\n")

            if (text.isNotEmpty()) {
                sb.append(text)
            }

            // 提取文档中嵌入的图片（仅保留浏览器可渲染的格式：png/jpg/gif/bmp/webp）
            // EMF/WMF 是 Windows 矢量格式，WebView 无法显示，需过滤掉
            val picturesTable = doc.picturesTable
            @Suppress("UNCHECKED_CAST")
            val allPictures = picturesTable.allPictures as List<org.apache.poi.hwpf.usermodel.Picture>
            val webCompatibleExts = setOf("png", "jpg", "jpeg", "gif", "bmp", "webp")
            val displayablePics = allPictures.filter {
                it.suggestFileExtension().lowercase() in webCompatibleExts
            }
            if (displayablePics.isNotEmpty()) {
                if (text.isNotEmpty()) sb.append("\n\n---\n\n")
                sb.append("**文档内嵌图片：**\n\n")
                for (pic in displayablePics) {
                    val imgBytes = pic.content
                    val ext = pic.suggestFileExtension().lowercase()
                    val mime = when (ext) {
                        "jpg" -> "jpeg"
                        else -> ext
                    }
                    val dataUri = "data:image/$mime;base64," +
                        Base64.encodeToString(imgBytes, Base64.NO_WRAP)
                    sb.append("![]($dataUri)\n\n")
                }
            }

            if (text.isEmpty() && displayablePics.isEmpty())
                "# $title\n\n（文档内容为空）"
            else sb.toString().trimEnd()
        }.getOrElse { e ->
            "# 解析失败\n\n（DOC 解析失败：${e.message}）"
        }
    }

    /* ========== PDF 逐页渲染为图片 ========== */

    private fun extractPdfImages(context: Context, filename: String, uri: Uri): String {
        val title = filename.substringBeforeLast('.')
        val sb = StringBuilder("# $title\n\n")
        var pageNum = 0

        try {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return errorMsg(title)
            val renderer = PdfRenderer(pfd)
            val maxPages = minOf(renderer.pageCount, MAX_PDF_PAGES)

            for (i in 0 until maxPages) {
                val page = renderer.openPage(i)
                // 使用 2x 缩放以获得清晰文字
                val bmp = Bitmap.createBitmap(
                    page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888
                )
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()

                val baos = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, 75, baos)
                bmp.recycle()
                val encoded = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
                sb.append("![第 ${i + 1} 页](data:image/jpeg;base64,$encoded)\n\n")
                pageNum++
            }
            renderer.close()
            pfd.close()
        } catch (e: Exception) {
            return "# $title\n\n（PDF 解析失败：${e.message}）"
        }

        if (pageNum == 0) return "# $title\n\n（PDF 无内容）"
        return sb.toString().trimEnd()
    }

    private fun errorMsg(title: String) = "# $title\n\n（PDF 无法打开）"

    /* ========== 工具方法 ========== */

    private fun encodeImageAsDataUri(imgBytes: ByteArray, fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val mime = when (ext) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "bmp" -> "image/bmp"
            "webp" -> "image/webp"
            else -> "image/png"
        }
        return "data:$mime;base64," + Base64.encodeToString(imgBytes, Base64.NO_WRAP)
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

    private const val MAX_PDF_PAGES = 50
}
