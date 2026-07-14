package com.mdreader.app

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject

object VaultSearch {

    data class Result(val uri: String, val name: String, val excerpt: String)
    private const val TAG = "VaultSearch"

    // ---- 缓存 ----
    private val fileCache = HashMap<String, HashMap<String, DocumentFile>>()
    private var cacheVaultUri: String? = null
    private var isScanning = false

    fun clearCache() {
        fileCache.clear()
        cacheVaultUri = null
        Logger.i(TAG, "【清除缓存】已清空文件缓存")
    }

    // ---- URI 编码修复 ----

    /**
     * 修复 vault URI 中非 ASCII 字符（如中文）未编码的问题。
     *
     * SAF picker 返回的 URI 可能包含未编码的非 ASCII 字符，例如：
     *   content://.../tree/primary:Documents/精华帖子收集箱
     * Uri.parse() 不会自动编码这些字符，导致 Content Provider 匹配失败。
     *
     * 方案：逐字符扫描，只对非 ASCII 字符做 UTF-8 percent-encode。
     * 所有结构字符（/ : ? = & 等）保持原样不变。
     * 结果：content://.../tree/primary:Documents/%E7%B2%BE%E5%8D%8E%E5%B8%96%E5%AD%90%E6%94%B6%E9%9B%86%E7%AE%B1
     *
     * 注意：这里 / 保持为路径分隔符（不编码为 %2F），
     * 因为 ExternalStorageProvider 的 getTreeDocumentId() 从路径段中提取 docId，
     * 而 fromTreeUri() 内部能正确处理这种格式。
     */
    fun ensureEncoded(vaultUri: Uri): Uri {
        val raw = vaultUri.toString()
        if (raw.all { it.code < 128 }) return vaultUri

        Logger.i(TAG, "【URI编码修复】检测到非ASCII字符，正在规范化: ${raw.take(80)}")
        return try {
            val sb = StringBuilder(raw.length + 20)
            for (ch in raw) {
                if (ch.code < 128) {
                    sb.append(ch)
                } else {
                    // 将字符编码为 UTF-8 字节，每个字节转为 %XX
                    val bytes = ch.toString().toByteArray(Charsets.UTF_8)
                    for (b in bytes) {
                        sb.append('%')
                        sb.append(String.format("%02X", b))
                    }
                }
            }
            val result = Uri.parse(sb.toString())
            Logger.i(TAG, "【URI编码修复】结果: ${result.toString().take(100)}")
            result
        } catch (e: Exception) {
            Logger.e(TAG, "【URI编码修复】失败: ${e.message}")
            vaultUri
        }
    }

    // ---- 工具方法 ----

    /**
     * 从 DocumentFile 的 URI 中提取 document ID。
     * 对 tree URI：合并 "tree" 后所有段为完整 docId（含子文件夹路径）。
     * 对 single URI：使用 DocumentsContract.getDocumentId()。
     */
    private fun extractDocId(uri: Uri): String? {
        val path = uri.path ?: return null
        val segments = path.split("/").filter { it.isNotEmpty() }

        // 检查是否是 tree URI（包含 "tree" 段）
        val treeIdx = segments.indexOf("tree")
        if (treeIdx >= 0 && treeIdx < segments.size - 1) {
            // 收集 tree 后面到 "document" 之前的所有段
            val docParts = mutableListOf<String>()
            var i = treeIdx + 1
            while (i < segments.size && segments[i] != "document") {
                docParts.add(segments[i])
                i++
            }
            if (docParts.isNotEmpty()) {
                val fullDocId = docParts.joinToString("/")
                Logger.d(TAG, "【extractDocId】tree URI → fullDocId=$fullDocId (from ${docParts.size} segments)")
                return fullDocId
            }
        }

        // 非 tree URI：用标准 API
        runCatching { return DocumentsContract.getDocumentId(uri) }

        // 最后尝试从 lastPathSegment 提取
        uri.lastPathSegment?.let { segment ->
            val afterDoc = segment.substringAfter("document/", "")
            if (afterDoc.isNotEmpty() && afterDoc != segment) {
                return try { java.net.URLDecoder.decode(afterDoc, "UTF-8") } catch (_: Exception) { null }
            }
        }
        return null
    }

    /**
     * 列出目录子项。三种方式依次尝试。
     * treeUri: 原始 vault 的 tree URI（已编码），递归时始终保持不变。
     */
    private fun listDir(context: Context, treeUri: Uri, dir: DocumentFile): List<DocumentFile> {
        val dirName = dir.name ?: dir.uri.toString().take(60)

        // 方式1: DocumentFile.listFiles()
        val dfList = runCatching { dir.listFiles() }.getOrNull()
        if (!dfList.isNullOrEmpty()) {
            return dfList.toList()
        }

        // 方式2: DocumentsContract 回退
        val docId = extractDocId(dir.uri)
        if (docId != null && docId.isNotBlank()) {
            val results = runCatching {
                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
                val list = mutableListOf<DocumentFile>()
                context.contentResolver.query(
                    childrenUri,
                    arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                    null, null, null
                )?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val childDocId = cursor.getString(0) ?: continue
                        val childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childDocId)
                        DocumentFile.fromSingleUri(context, childUri)?.let { list.add(it) }
                    }
                }
                list
            }.getOrNull()
            if (!results.isNullOrEmpty()) {
                return results
            }
            Logger.w(TAG, "列目录失败: $dirName (docId=$docId)")
        } else {
            Logger.e(TAG, "无法提取docId: ${dir.uri.toString().take(80)}")
        }

        return emptyList()
    }

    /**
     * 构建缓存：递归扫描 vault 目录。
     */
    private fun buildCache(context: Context, vaultUri: Uri): HashMap<String, DocumentFile>? {
        val uriStr = vaultUri.toString()
        if (cacheVaultUri == uriStr && fileCache[uriStr] != null) {
            return fileCache[uriStr]!!
        }
        // 防止多线程重复扫描
        if (isScanning) return fileCache[uriStr]
        isScanning = true
        try {
            Logger.i(TAG, "开始扫描库文件夹...")
            val map = HashMap<String, DocumentFile>()
            val root = DocumentFile.fromTreeUri(context, vaultUri)
            if (root == null) {
                Logger.e(TAG, "库文件夹无效 (fromTreeUri=null) — 请重新选择")
                return null
            }
            scanDir(context, vaultUri, root, map)
            Logger.i(TAG, "库扫描完成: ${map.size} 个文件")
            if (map.isEmpty()) {
                Logger.e(TAG, "扫描结果为 0 — 权限可能已过期，请重新选择文件夹")
                return null
            }
            fileCache[uriStr] = map
            cacheVaultUri = uriStr
            return map
        } finally {
            isScanning = false
        }
    }

    private fun scanDir(context: Context, treeUri: Uri, dir: DocumentFile, map: HashMap<String, DocumentFile>) {
        val children = listDir(context, treeUri, dir)
        if (children.isEmpty()) return
        for (file in children) {
            when {
                file.isDirectory -> scanDir(context, treeUri, file, map)
                file.isFile -> {
                    val name = file.name ?: continue
                    map[name] = file
                    map[name.lowercase()] = file
                    try {
                        val decoded = java.net.URLDecoder.decode(name, "UTF-8")
                        if (decoded != name) {
                            map[decoded] = file
                            map[decoded.lowercase()] = file
                        }
                    } catch (_: Exception) {}
                }
            }
        }
    }

    private fun buildNameCandidates(fileName: String): List<String> {
        val list = mutableListOf<String>()
        list.add(fileName)
        list.add(fileName.lowercase())
        val decoded = try {
            java.net.URLDecoder.decode(fileName, "UTF-8")
        } catch (_: Exception) { fileName }
        if (decoded != fileName) {
            list.add(decoded)
            list.add(decoded.lowercase())
        }
        val nameNoExt = fileName.substringBeforeLast('.')
        if (nameNoExt != fileName) {
            list.add(nameNoExt)
            list.add(nameNoExt.lowercase())
        }
        if (!fileName.endsWith(".md", ignoreCase = true)) {
            list.add("$fileName.md")
            list.add("${fileName.lowercase()}.md")
        }
        return list
    }

    // ---- 全文搜索 ----

    fun search(context: Context, vaultUri: Uri, query: String, maxResults: Int = 40): String {
        val encoded = ensureEncoded(vaultUri)
        val root = DocumentFile.fromTreeUri(context, encoded)
        if (root == null) {
            Logger.e(TAG, "全文搜索失败: 库文件夹无效")
            return "[]"
        }
        val list = mutableListOf<Result>()
        val q = query.trim().lowercase()
        if (q.isNotEmpty()) searchDir(context, encoded, root, q, list, maxResults)
        if (list.isEmpty()) Logger.w(TAG, "全文搜索 '$query': 无结果")
        return toJson(list)
    }

    private fun searchDir(context: Context, treeUri: Uri, dir: DocumentFile, query: String, out: MutableList<Result>, max: Int) {
        if (out.size >= max) return
        val children = listDir(context, treeUri, dir)
        if (children.isEmpty()) return
        for (file in children) {
            if (out.size >= max) return
            when {
                file.isDirectory -> searchDir(context, treeUri, file, query, out, max)
                file.isFile -> tryMatchFile(context, file, query, out)
            }
        }
    }

    private fun tryMatchFile(context: Context, file: DocumentFile, query: String, out: MutableList<Result>) {
        val name = file.name ?: return
        if (!name.endsWith(".md", ignoreCase = true) && !name.endsWith(".markdown", ignoreCase = true)) return
        runCatching {
            val content = context.contentResolver.openInputStream(file.uri)
                ?.use { it.bufferedReader().readText() } ?: return
            val nameLower = name.lowercase()
            val contentLower = content.lowercase()
            val nameHit = nameLower.contains(query)
            val contentIdx = contentLower.indexOf(query)
            if (nameHit || contentIdx >= 0) {
                val excerpt = if (contentIdx >= 0) {
                    val s = maxOf(0, contentIdx - 40)
                    val e = minOf(content.length, contentIdx + query.length + 80)
                    "…" + content.substring(s, e).trim().replace('\n', ' ') + "…"
                } else name
                out.add(Result(file.uri.toString(), name, excerpt))
            }
        }
    }

    // ---- Wikilink 文件查找 ----

    fun findFile(context: Context, vaultUri: Uri, noteName: String): DocumentFile? {
        val encoded = ensureEncoded(vaultUri)
        val cleanName = noteName.substringBefore('#').trim()
        if (cleanName.isEmpty()) return null

        // 优先缓存
        val cache = buildCache(context, encoded)
        if (cache != null) {
            val fileName = cleanName.substringAfterLast('/').trim()
            if (fileName.isNotEmpty()) {
                for (name in buildNameCandidates(fileName)) {
                    cache[name]?.let { return it }
                }
            }
        }

        // 回退：递归搜索
        val root = DocumentFile.fromTreeUri(context, encoded) ?: return null

        if (cleanName.contains('/')) {
            val parts = cleanName.split('/').filter { it.isNotEmpty() }
            val filename = parts.last()
            val dirParts = parts.dropLast(1)

            // 路径导航
            var dir: DocumentFile? = root
            for (part in dirParts) {
                val children = listDir(context, encoded, dir ?: break)
                dir = children.find {
                    it.isDirectory && nameMatches(it.name, part)
                }
                if (dir == null) break
            }

            if (dir != null) {
                findInDir(context, encoded, dir, filename)?.let { return it }
            }

            // 全库搜索
            return findInDir(context, encoded, root, filename)
        }

        return findInDir(context, encoded, root, cleanName)
    }

    private fun nameMatches(actual: String?, target: String): Boolean {
        if (actual == null) return false
        if (actual.equals(target, ignoreCase = true)) return true
        try {
            if (java.net.URLDecoder.decode(actual, "UTF-8").equals(target, ignoreCase = true)) return true
        } catch (_: Exception) {}
        return false
    }

    private fun findInDir(context: Context, treeUri: Uri, dir: DocumentFile, noteName: String): DocumentFile? {
        val children = listDir(context, treeUri, dir)
        if (children.isEmpty()) return null
        for (file in children) {
            if (file.isDirectory) {
                findInDir(context, treeUri, file, noteName)?.let { return it }
            } else {
                val fn = file.name ?: continue
                if (fileNameMatches(fn, noteName)) return file
            }
        }
        return null
    }

    private fun fileNameMatches(actualName: String, targetName: String): Boolean {
        val base = actualName.substringBeforeLast(".")
        if (base.equals(targetName, ignoreCase = true)) return true
        if (actualName.equals(targetName, ignoreCase = true)) return true
        if (actualName.equals("$targetName.md", ignoreCase = true)) return true
        try {
            val decoded = java.net.URLDecoder.decode(actualName, "UTF-8")
            val decodedBase = decoded.substringBeforeLast(".")
            if (decodedBase.equals(targetName, ignoreCase = true)) return true
            if (decoded.equals(targetName, ignoreCase = true)) return true
            if (decoded.equals("$targetName.md", ignoreCase = true)) return true
        } catch (_: Exception) {}
        return false
    }

    // ---- 资源文件查找 ----

    fun findAssetInVault(context: Context, vaultUri: Uri, relativePath: String): DocumentFile? {
        val encoded = ensureEncoded(vaultUri)
        val cleanPath = relativePath.replace('\\', '/').trimStart('/')
        if (cleanPath.isEmpty()) return null
        val fileName = cleanPath.substringAfterLast('/')
        if (fileName.isEmpty()) return null

        val cache = buildCache(context, encoded)
        if (cache != null) {
            for (name in buildNameCandidates(fileName)) {
                cache[name]?.let { return it }
            }
        }

        val root = DocumentFile.fromTreeUri(context, encoded) ?: return null
        findFileInDir(context, encoded, root, cleanPath)?.let { return it }
        return findInDir(context, encoded, root, fileName)
    }

    fun resolveRelativeAsset(context: Context, vaultUri: Uri, currentDocUri: Uri?, relativePath: String): DocumentFile? {
        val encoded = ensureEncoded(vaultUri)
        val root = DocumentFile.fromTreeUri(context, encoded) ?: return null
        if (currentDocUri != null) {
            val currentDoc = DocumentFile.fromSingleUri(context, currentDocUri)
            val parentPath = currentDoc?.parentFile
            if (parentPath != null) {
                findFileInDir(context, encoded, parentPath, relativePath)?.let { return it }
            }
        }
        return findAssetInVault(context, encoded, relativePath)
    }

    internal fun findFileInDir(context: Context, treeUri: Uri, dir: DocumentFile, name: String): DocumentFile? {
        val parts = name.replace('\\', '/').split('/')
        var current: DocumentFile? = dir
        for (part in parts) {
            if (part.isEmpty() || part == ".") continue
            if (part == "..") { current = current?.parentFile; continue }
            val decodedPart = try { java.net.URLDecoder.decode(part, "UTF-8") } catch (_: Exception) { part }
            val children = listDir(context, treeUri, current ?: return null)
            current = children.find {
                val n = it.name
                n == part || n == decodedPart ||
                    n?.equals(part, ignoreCase = true) == true ||
                    n?.equals(decodedPart, ignoreCase = true) == true
            } ?: return null
        }
        return current?.takeIf { it.isFile }
    }

    private fun toJson(results: List<Result>): String {
        val arr = JSONArray()
        results.forEach { r ->
            arr.put(JSONObject().apply {
                put("uri", r.uri)
                put("name", r.name)
                put("excerpt", r.excerpt)
            })
        }
        return arr.toString()
    }
}
