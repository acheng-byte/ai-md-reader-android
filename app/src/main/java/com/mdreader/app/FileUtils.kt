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
        val utf8Repl = utf8Text.count { it == '\uFFFD' }
        text = if (utf8Repl > 0 && utf8Repl.toDouble() / maxOf(utf8Text.length, 1) > 0.001) {
            // UTF-8 有较多替换字符，可能是 GBK/GB18030 编码
            // 但必须先验证 GB18030 解码确实更好，否则保留 UTF-8
            val gb18030Text = runCatching { bytes.toString(charset("GB18030")) }.getOrNull()
            if (gb18030Text != null) {
                val gbRepl = gb18030Text.count { it == '\uFFFD' }
                if (gbRepl < utf8Repl) gb18030Text else utf8Text
            } else {
                val gbkText = runCatching { bytes.toString(charset("GBK")) }.getOrNull()
                if (gbkText != null) {
                    val gbkRepl = gbkText.count { it == '\uFFFD' }
                    if (gbkRepl < utf8Repl) gbkText else utf8Text
                } else {
                    utf8Text
                }
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
        // 行首数字 + . / ) 会被解析为有序列表，在数字和标点之间插入零宽空格打断模式
        val noList = escaped.replace(Regex("(^|\n)(\\d+)([.)])"), "$1$2\u200B$3")
        return "# $title\n\n$noList"
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

        // 格式追踪
        var inRunProps = false
        var runBold = false
        var runItalic = false
        var isList = false
        var listCounter = 0

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
                            runBold = false
                            runItalic = false
                            isList = false
                        }
                        localName == "pStyle" && isWordNs -> {
                            val styleVal = parser.getAttributeValue(null, "val") ?: ""
                            headingLevel = detectHeadingLevel(styleVal)
                            if (isListStyle(styleVal)) isList = true
                        }
                        // ── Run 属性（追踪加粗/斜体）────────────────────────
                        localName == "rPr" && isWordNs -> {
                            inRunProps = true
                        }
                        localName == "b" && isWordNs && inRunProps -> {
                            runBold = true
                        }
                        localName == "i" && isWordNs && inRunProps -> {
                            runItalic = true
                        }
                        // ── 文本 ─────────────────────────────────────────────
                        localName == "t" && isWordNs -> {
                            var text = parser.nextText()
                            // 应用 run 级格式：加粗/斜体文本先去除原文中的 * _ 避免 **** 冲突
                            if (runBold && runItalic) {
                                text = "***${text.replace("*", "").replace("_", "")}***"
                            } else if (runBold) {
                                text = "**${text.replace("*", "").replace("_", "")}**"
                            } else if (runItalic) {
                                text = "*${text.replace("*", "").replace("_", "")}*"
                            } else {
                                // 普通文本：转义 markdown 特殊字符，防止原文 * ~ # 干扰渲染
                                text = text.replace("*", "\\*").replace("_", "\\_")
                                    .replace("~", "\\~").replace("#", "\\#")
                            }
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
                        localName == "rPr" && isWordNs -> {
                            inRunProps = false
                        }
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
                                    listCounter = 0
                                } else if (isList) {
                                    listCounter++
                                    sb.append("$listCounter. ")
                                    sb.append(paraText.trim()).append("\n")
                                } else {
                                    listCounter = 0
                                    val line = paraText.trim()
                                    val escaped = if (line.startsWith("#")) "\\$line" else line
                                    sb.append(escaped).append("\n\n")
                                }
                            } else {
                                sb.append('\n')
                                listCounter = 0
                            }
                        }
                    }
                }
            }
            event = try { parser.next() } catch (e: Exception) { break }
        }
        return Pair(sb.toString().trimEnd(), foundImages)
    }

    private fun detectHeadingLevel(styleVal: String): Int {
        val lower = styleVal.lowercase().replace(" ", "")
        return when {
            lower.startsWith("heading1") || lower == "标题1" -> 1
            lower.startsWith("heading2") || lower == "标题2" -> 2
            lower.startsWith("heading3") || lower == "标题3" -> 3
            lower.startsWith("heading4") || lower == "标题4" -> 4
            lower.startsWith("heading5") || lower == "标题5" -> 5
            lower.startsWith("heading6") || lower == "标题6" -> 6
            lower.startsWith("title") && lower != "titlepage" -> 1
            lower.startsWith("subtitle") -> 2
            else -> 0
        }
    }

    private fun isListStyle(styleVal: String): Boolean {
        val lower = styleVal.lowercase()
        return lower.contains("list") || lower.contains("列表") ||
            lower.startsWith("listparagraph")
    }

    /* ========== 旧版 .doc（OLE2）—— 使用 Apache POI HWPF ========== */

    private fun extractDocText(filename: String, bytes: ByteArray): String {
        return runCatching {
            val doc = HWPFDocument(bytes.inputStream())
            val range = doc.range
            val title = filename.substringBeforeLast('.')
            val sb = StringBuilder("# $title\n\n")
            var hasText = false

            // 逐 CharacterRun 迭代（HWPF Paragraph 无 numRuns/getRun API）
            // 通过 \r 检测段落边界，\u0007 检测表格单元格
            var paraBuffer = StringBuilder()
            var paraBold = false
            var tableBuffer = StringBuilder()
            var tableHeaderDone = false
            var tableCells = ArrayList<String>()
            var cellBuffer = StringBuilder()
            var cellBold = false

            for (i in 0 until range.numCharacterRuns()) {
                val run = range.getCharacterRun(i)
                var text = run.text() ?: continue
                val bold = run.isBold
                val italic = run.isItalic

                // 处理文本中的单元格分隔符 \u0007
                if (text.contains("\u0007")) {
                    val parts = text.split("\u0007")
                    for ((idx, part) in parts.withIndex()) {
                        val clean = part.replace(Regex("[\\r\\u000b]"), "")
                        if (clean.isNotEmpty()) {
                            val formatted = when {
                                bold && italic -> "***${clean.replace("*", "").replace("_", "")}***"
                                bold -> "**${clean.replace("*", "").replace("_", "")}**"
                                italic -> "*${clean.replace("*", "").replace("_", "")}*"
                                else -> clean.replace("*", "\\*").replace("_", "\\_")
                                    .replace("~", "\\~").replace("#", "\\#")
                            }
                            cellBuffer.append(formatted)
                        }
                        if (bold) cellBold = true
                        // 每个 \u0007 完成一个单元格
                        if (idx < parts.size - 1) {
                            val cellText = cellBuffer.toString().trim()
                            if (cellText.isNotEmpty()) tableCells.add(cellText)
                            cellBuffer = StringBuilder()
                            cellBold = false
                        }
                    }
                    // \u0007 后的文本属于新单元格或行结束
                    continue
                }

                // 检查是否为表格内容（tableBuffer 有内容说明在表格中）
                val inTable = tableBuffer.isNotEmpty() || text.contains("\u0007")

                if (text.contains("\r")) {
                    // 段落结束
                    val clean = text.replace("\r", "").replace(Regex("[\\x00-\\x09\\x0b-\\x1f]"), "")
                    if (clean.isNotEmpty()) {
                        val formatted = when {
                            bold && italic -> "***${clean.replace("*", "").replace("_", "")}***"
                            bold -> "**${clean.replace("*", "").replace("_", "")}**"
                            italic -> "*${clean.replace("*", "").replace("_", "")}*"
                            else -> clean.replace("*", "\\*").replace("_", "\\_")
                                .replace("~", "\\~").replace("#", "\\#")
                        }
                        if (inTable) {
                            cellBuffer.append(formatted)
                            if (bold) cellBold = true
                        } else {
                            paraBuffer.append(formatted)
                            if (bold) paraBold = true
                        }
                    }

                    // 刷新表格或段落
                    if (inTable) {
                        // 行结束：把剩余 cellBuffer 作为最后一个单元格
                        val lastCell = cellBuffer.toString().trim()
                        if (lastCell.isNotEmpty()) tableCells.add(lastCell)
                        if (tableCells.isNotEmpty()) {
                            if (!tableHeaderDone) {
                                sb.append(formatDocTableRow(tableCells))
                                sb.append(formatDocTableSep(tableCells.size))
                                tableHeaderDone = true
                            } else {
                                sb.append(formatDocTableRow(tableCells))
                            }
                        }
                        tableCells = ArrayList()
                        tableBuffer = StringBuilder()
                        cellBuffer = StringBuilder()
                        tableHeaderDone = false
                        hasText = true
                    } else if (paraBuffer.isNotEmpty()) {
                        val paraText = paraBuffer.toString().trim()
                        if (paraText.isNotEmpty()) {
                            if (paraBold && looksLikeHeading(paraText)) {
                                val level = headingLevelFromLength(paraText)
                                sb.append("#".repeat(level)).append(' ')
                                sb.append(paraText.trim('*')).append("\n\n")
                            } else {
                                sb.append(paraText).append("\n\n")
                            }
                            hasText = true
                        }
                        paraBuffer = StringBuilder()
                        paraBold = false
                    } else {
                        // 空段落 → 换行
                        if (hasText) sb.append('\n')
                    }
                } else {
                    // 非段落结束：检查是否进入表格
                    val clean = text.replace(Regex("[\\x00-\\x09\\x0b-\\x1f]"), "")
                    if (clean.isNotEmpty()) {
                        val formatted = when {
                            bold && italic -> "***${clean.replace("*", "").replace("_", "")}***"
                            bold -> "**${clean.replace("*", "").replace("_", "")}**"
                            italic -> "*${clean.replace("*", "").replace("_", "")}*"
                            else -> clean.replace("*", "\\*").replace("_", "\\_")
                                .replace("~", "\\~").replace("#", "\\#")
                        }
                        // 检测表格开始：当前累积的段落文本含 \u0007
                        if (paraBuffer.contains("\u0007")) {
                            tableBuffer.append(paraBuffer)
                            paraBuffer = StringBuilder()
                            paraBold = false
                        }
                        if (tableBuffer.isNotEmpty()) {
                            cellBuffer.append(formatted)
                            if (bold) cellBold = true
                            tableBuffer.append(text)
                        } else {
                            paraBuffer.append(formatted)
                            if (bold) paraBold = true
                        }
                    }
                }
            }

            // 刷新未结束的段落
            val remaining = paraBuffer.toString().trim()
            if (remaining.isNotEmpty()) {
                sb.append(remaining).append("\n\n")
                hasText = true
            }

            // 提取嵌入图片
            val picturesTable = doc.picturesTable
            @Suppress("UNCHECKED_CAST")
            val allPictures = picturesTable.allPictures as List<org.apache.poi.hwpf.usermodel.Picture>
            val webCompatibleExts = setOf("png", "jpg", "jpeg", "gif", "bmp", "webp")
            val displayablePics = allPictures.filter {
                it.suggestFileExtension().lowercase() in webCompatibleExts
            }
            if (displayablePics.isNotEmpty()) {
                if (hasText) sb.append("\n---\n\n")
                sb.append("**文档内嵌图片：**\n\n")
                for (pic in displayablePics) {
                    val imgBytes = pic.content
                    val ext = pic.suggestFileExtension().lowercase()
                    val mime = if (ext == "jpg") "jpeg" else ext
                    val dataUri = "data:image/$mime;base64," +
                        Base64.encodeToString(imgBytes, Base64.NO_WRAP)
                    sb.append("![]($dataUri)\n\n")
                }
            }

            if (!hasText && displayablePics.isEmpty())
                "# $title\n\n（文档内容为空）"
            else sb.toString().trimEnd()
        }.getOrElse { e ->
            "# 解析失败\n\n（DOC 解析失败：${e.message}）"
        }
    }

    /** 表格单元格 → Markdown 表格行 */
    private fun formatDocTableRow(cells: List<String>): String {
        val cleaned = cells.map { it.replace(Regex("[\\r\\u0007\\u000b]"), "").trim() }
            .filter { it.isNotEmpty() }
        if (cleaned.isEmpty()) return ""
        val row = cleaned.joinToString(" | ") { it.ifBlank { " " } }
        return "| $row |\n"
    }

    /** 表格分隔行 */
    private fun formatDocTableSep(colCount: Int): String =
        "|" + " --- |".repeat(colCount) + "\n"

    /** 整段加粗的短文本可能实际是标题 */
    private fun looksLikeHeading(text: String): Boolean {
        val clean = text.trim('*')
        return clean.length < 60 && '\n' !in clean && '。' !in clean
    }

    /** 根据文本长度推测标题级别 */
    private fun headingLevelFromLength(text: String): Int = when {
        text.length < 15 -> 1
        text.length < 30 -> 2
        else -> 3
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
