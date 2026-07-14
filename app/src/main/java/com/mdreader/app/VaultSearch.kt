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

    fun clearCache() {
        fileCache.clear()
        cacheVaultUri = null
        Logger.i(TAG, "【清除缓存】已清空文件缓存")
    }

    // ---- URI 编码修复 ----

    /**
     * 修复 vault URI 中非 ASCII 字符（如中文）未编码的问题。
     * Uri.parse() 不会自动编码非 ASCII，导致 Content Provider 匹配失败。
     * 此方法将路径分段重新编码，保留 / 分隔符。
     */
    fun ensureEncoded(vaultUri: Uri): Uri {
        val raw = vaultUri.toString()
        // 快速检查：如果没有非 ASCII 字符，直接返回
        if (raw.all { it.code < 128 }) return vaultUri

        Logger.i(TAG, "【URI编码修复】检测到非ASCII字符，正在规范化: ${raw.take(80)}")
        return try {
            val authority = vaultUri.authority ?: return vaultUri
            val path = vaultUri.path ?: return vaultUri
            // 提取 /tree/ 和 /document/ 之间的 docId 部分
            // 格式: /tree/{docId} 或 /tree/{docId}/document/{docId}
            val segments = path.split("/").filter { it.isNotEmpty() }
            val builder = Uri.Builder().scheme("content").authority(authority)
            // 重新构建路径，对每段进行编码
            val rebuilt = mutableListOf<String>()
            for (seg in segments) {
                val encoded = Uri.encode(seg, ":")  // 保留冒号（如 primary:Documents）
                rebuilt.add(encoded)
            }
            // 重建完整路径
            if (rebuilt.isNotEmpty()) {
                builder.appendPath(rebuilt[0])
                for (i in 1 until rebuilt.size) {
                    builder.appendPath(rebuilt[i])
                }
            }
            // 重新拼接：/tree/xxx/document/xxx 格式
            // 由于 appendPath 会再编码，我们用直接拼接方式
            val encodedPath = "/" + rebuilt.joinToString("/")
            Uri.parse("content://$authority$encodedPath")
        } catch (e: Exception) {
            Logger.e(TAG, "【URI编码修复】失败: ${e.message}")
            vaultUri
        }
    }

    // ---- 工具方法 ----

    /**
     * 从 DocumentFile 的 URI 中提取 document ID（多种方式尝试）。
     */
    private fun extractDocId(uri: Uri): String? {
        // 方式1: 标准 API
        runCatching { return DocumentsContract.getDocumentId(uri) }
        // 方式2: 从 lastPathSegment 提取 tree/ 后面部分
        uri.lastPathSegment?.let { segment ->
            val afterTree = segment.substringAfter("tree/", "")
            if (afterTree.isNotEmpty() && afterTree != segment) {
                return try { java.net.URLDecoder.decode(afterTree, "UTF-8") } catch (_: Exception) { null }
            }
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
            Logger.d(TAG, "【列目录·成功】$dirName → ${dfList.size} 项 (DocumentFile)")
            return dfList.toList()
        }
        Logger.w(TAG, "【列目录·DocumentFile为空】$dirName — 尝试 DocumentsContract 回退")

        // 方式2+3: DocumentsContract 查询（始终使用原始 treeUri）
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
                        // 关键：用原始 treeUri 构建子文件的 tree-based URI，保持上下文
                        val childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childDocId)
                        DocumentFile.fromSingleUri(context, childUri)?.let { list.add(it) }
                    }
                }
                list
            }.getOrNull()
            if (!results.isNullOrEmpty()) {
                Logger.d(TAG, "【列目录·DC回退成功】$dirName → ${results.size} 项 (docId=$docId)")
                return results
            }
            Logger.w(TAG, "【列目录·DC回退为空】docId=$docId, treeUri=${treeUri.toString().take(60)}")
        } else {
            Logger.e(TAG, "【列目录·无法提取docId】uri=${dir.uri.toString().take(80)}")
        }

        Logger.e(TAG, "【列目录·全部失败】$dirName — 所有方法返回空")
        return emptyList()
    }

    /**
     * 构建缓存：递归扫描 vault 目录。
     */
    private fun buildCache(context: Context, vaultUri: Uri): HashMap<String, DocumentFile>? {
        val uriStr = vaultUri.toString()
        if (cacheVaultUri == uriStr && fileCache[uriStr] != null) {
            Logger.d(TAG, "【缓存命中】${fileCache[uriStr]!!.size} 条记录")
            return fileCache[uriStr]!!
        }
        Logger.i(TAG, "【构建缓存】开始扫描: ${uriStr.take(80)}")
        val map = HashMap<String, DocumentFile>()
        val root = DocumentFile.fromTreeUri(context, vaultUri)
        if (root == null) {
            Logger.e(TAG, "【构建缓存失败】fromTreeUri 返回 null — URI 可能无效或权限已过期")
            return null
        }
        Logger.d(TAG, "【构建缓存】根目录: name=${root.name}, canRead=${root.canRead()}")
        scanDir(context, vaultUri, root, map)
        Logger.i(TAG, "【构建缓存完成】扫描到 ${map.size} 条记录")
        if (map.isEmpty()) {
            Logger.e(TAG, "【构建缓存失败】扫描结果为 0 — 请检查文件夹权限是否过期")
            return null
        }
        fileCache[uriStr] = map
        cacheVaultUri = uriStr
        return map
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
        Logger.i(TAG, "【全文搜索】关键词='$query', vaultUri=${encoded.toString().take(60)}")
        val root = DocumentFile.fromTreeUri(context, encoded)
        if (root == null) {
            Logger.e(TAG, "【全文搜索失败】fromTreeUri 返回 null — URI 无效")
            return "[]"
        }
        val list = mutableListOf<Result>()
        val q = query.trim().lowercase()
        if (q.isNotEmpty()) searchDir(context, encoded, root, q, list, maxResults)
        Logger.i(TAG, "【全文搜索完成】找到 ${list.size} 个结果")
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
        Logger.i(TAG, "【查找文件】noteName='$noteName'")
        val cleanName = noteName.substringBefore('#').trim()
        if (cleanName.isEmpty()) {
            Logger.w(TAG, "【查找文件】文件名为空")
            return null
        }

        // 优先缓存
        val cache = buildCache(context, encoded)
        if (cache != null) {
            val fileName = cleanName.substringAfterLast('/').trim()
            if (fileName.isNotEmpty()) {
                val candidates = buildNameCandidates(fileName)
                for (name in candidates) {
                    val found = cache[name]
                    if (found != null) {
                        Logger.i(TAG, "【查找文件·缓存命中】'$name' → ${found.name}")
                        return found
                    }
                }
                Logger.d(TAG, "【查找文件·缓存未命中】候选名: $candidates")
            }
        } else {
            Logger.w(TAG, "【查找文件】缓存为空，使用递归搜索")
        }

        // 回退：递归搜索
        val root = DocumentFile.fromTreeUri(context, encoded)
        if (root == null) {
            Logger.e(TAG, "【查找文件失败】fromTreeUri 返回 null")
            return null
        }

        if (cleanName.contains('/')) {
            val parts = cleanName.split('/').filter { it.isNotEmpty() }
            val filename = parts.last()
            val dirParts = parts.dropLast(1)
            Logger.d(TAG, "【查找文件·路径模式】目录=$dirParts, 文件名='$filename'")

            // 路径导航
            var dir: DocumentFile? = root
            for (part in dirParts) {
                val children = listDir(context, encoded, dir ?: break)
                dir = children.find {
                    it.isDirectory && nameMatches(it.name, part)
                }
                if (dir == null) {
                    Logger.w(TAG, "【查找文件】目录'$part'未找到")
                    break
                }
            }

            if (dir != null) {
                val found = findInDir(context, encoded, dir, filename)
                if (found != null) {
                    Logger.i(TAG, "【查找文件·成功】路径导航: ${found.name}")
                    return found
                }
            }

            // 全库搜索
            Logger.d(TAG, "【查找文件】全库搜索 '$filename'")
            val result = findInDir(context, encoded, root, filename)
            if (result != null) {
                Logger.i(TAG, "【查找文件·成功】全库搜索: ${result.name}")
            } else {
                Logger.e(TAG, "【查找文件·未找到】'$filename'")
            }
            return result
        }

        Logger.d(TAG, "【查找文件】简单搜索 '$cleanName'")
        val result = findInDir(context, encoded, root, cleanName)
        if (result != null) {
            Logger.i(TAG, "【查找文件·成功】${result.name}")
        } else {
            Logger.e(TAG, "【查找文件·未找到】'$cleanName'")
        }
        return result
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
                cache[name]?.let {
                    Logger.d(TAG, "【资源查找·缓存命中】$name")
                    return it
                }
            }
        }

        val root = DocumentFile.fromTreeUri(context, encoded) ?: return null
        findFileInDir(context, encoded, root, cleanPath)?.let { return it }
        val result = findInDir(context, encoded, root, fileName)
        if (result == null) Logger.d(TAG, "【资源查找·未找到】$fileName")
        return result
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
