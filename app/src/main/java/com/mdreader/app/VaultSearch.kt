package com.mdreader.app

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject

object VaultSearch {

    data class Result(val uri: String, val name: String, val excerpt: String)

    // ---- 缓存 ----
    private val fileCache = HashMap<String, HashMap<String, DocumentFile>>()
    private var cacheVaultUri: String? = null

    fun clearCache() {
        fileCache.clear()
        cacheVaultUri = null
    }

    /**
     * 列出目录子项。三种方式依次尝试：
     * 1. DocumentFile.listFiles()
     * 2. DocumentsContract 查询（从 tree URI 提取 docId）
     * 3. DocumentsContract 查询（从 URI last path segment 提取 docId）
     */
    private fun listDir(context: Context, dir: DocumentFile): List<DocumentFile> {
        // 方式1: DocumentFile.listFiles()
        runCatching {
            val list = dir.listFiles()
            if (!list.isNullOrEmpty()) return list.toList()
        }

        // 方式2+3: DocumentsContract 直接查询
        val treeUri = dir.uri
        // 尝试多种方式获取 document ID
        val docIds = mutableListOf<String>()

        // 方式2: 标准 API
        runCatching { docIds.add(DocumentsContract.getDocumentId(treeUri)) }

        // 方式3: 从 URI 手动提取
        treeUri.lastPathSegment?.let { segment ->
            // content://.../tree/primary%3AVault/document/primary%3AVault%2Fsubdir
            // 或 content://.../tree/primary%3AVault
            val afterTree = segment.substringAfter("tree/", "")
            if (afterTree.isNotEmpty() && afterTree != segment) {
                docIds.add(java.net.URLDecoder.decode(afterTree, "UTF-8"))
            }
            // 也试 document/ 后面的部分
            val afterDoc = segment.substringAfter("document/", "")
            if (afterDoc.isNotEmpty() && afterDoc != segment) {
                docIds.add(java.net.URLDecoder.decode(afterDoc, "UTF-8"))
            }
        }

        for (docId in docIds.distinct()) {
            if (docId.isBlank()) continue
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
            if (!results.isNullOrEmpty()) return results
        }

        return emptyList()
    }

    /**
     * 构建缓存：递归扫描 vault，为每个文件存储多种名称变体作为 key。
     */
    private fun buildCache(context: Context, vaultUri: Uri): HashMap<String, DocumentFile>? {
        val uriStr = vaultUri.toString()
        if (cacheVaultUri == uriStr && fileCache[uriStr] != null) {
            return fileCache[uriStr]!!
        }
        val map = HashMap<String, DocumentFile>()
        val root = DocumentFile.fromTreeUri(context, vaultUri) ?: return null
        // 不检查 canRead()，直接尝试扫描
        scanDir(context, root, map)
        if (map.isEmpty()) return null
        fileCache[uriStr] = map
        cacheVaultUri = uriStr
        return map
    }

    private fun scanDir(context: Context, dir: DocumentFile, map: HashMap<String, DocumentFile>) {
        val children = listDir(context, dir)
        if (children.isEmpty()) return
        for (file in children) {
            when {
                file.isDirectory -> scanDir(context, file, map)
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
        val root = DocumentFile.fromTreeUri(context, vaultUri) ?: return "[]"
        val list = mutableListOf<Result>()
        val q = query.trim().lowercase()
        if (q.isNotEmpty()) searchDir(context, root, q, list, maxResults)
        return toJson(list)
    }

    private fun searchDir(context: Context, dir: DocumentFile, query: String, out: MutableList<Result>, max: Int) {
        if (out.size >= max) return
        val children = listDir(context, dir)
        if (children.isEmpty()) return
        for (file in children) {
            if (out.size >= max) return
            when {
                file.isDirectory -> searchDir(context, file, query, out, max)
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
        val cleanName = noteName.substringBefore('#').trim()
        if (cleanName.isEmpty()) return null

        // 优先缓存
        val cache = buildCache(context, vaultUri)
        if (cache != null) {
            val fileName = cleanName.substringAfterLast('/').trim()
            if (fileName.isNotEmpty()) {
                for (name in buildNameCandidates(fileName)) {
                    cache[name]?.let { return it }
                }
            }
        }

        // 回退：递归搜索
        val root = DocumentFile.fromTreeUri(context, vaultUri) ?: return null

        if (cleanName.contains('/')) {
            val parts = cleanName.split('/').filter { it.isNotEmpty() }
            val filename = parts.last()
            val dirParts = parts.dropLast(1)

            // 路径导航
            var dir: DocumentFile? = root
            for (part in dirParts) {
                val children = listDir(context, dir ?: break)
                dir = children.find {
                    it.isDirectory && nameMatches(it.name, part)
                }
                if (dir == null) break
            }

            if (dir != null) {
                findInDir(context, dir, filename)?.let { return it }
            }

            // 全库搜索
            return findInDir(context, root, filename)
        }

        return findInDir(context, root, cleanName)
    }

    /** 名称匹配：原始、URL解码、大小写不敏感 */
    private fun nameMatches(actual: String?, target: String): Boolean {
        if (actual == null) return false
        if (actual.equals(target, ignoreCase = true)) return true
        try {
            if (java.net.URLDecoder.decode(actual, "UTF-8").equals(target, ignoreCase = true)) return true
        } catch (_: Exception) {}
        return false
    }

    /** 递归搜索目录 */
    private fun findInDir(context: Context, dir: DocumentFile, noteName: String): DocumentFile? {
        val children = listDir(context, dir)
        if (children.isEmpty()) return null
        for (file in children) {
            if (file.isDirectory) {
                findInDir(context, file, noteName)?.let { return it }
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
        val cleanPath = relativePath.replace('\\', '/').trimStart('/')
        if (cleanPath.isEmpty()) return null
        val fileName = cleanPath.substringAfterLast('/')
        if (fileName.isEmpty()) return null

        val cache = buildCache(context, vaultUri)
        if (cache != null) {
            for (name in buildNameCandidates(fileName)) {
                cache[name]?.let { return it }
            }
        }

        val root = DocumentFile.fromTreeUri(context, vaultUri) ?: return null
        findFileInDir(context, root, cleanPath)?.let { return it }
        return findInDir(context, root, fileName)
    }

    fun resolveRelativeAsset(context: Context, vaultUri: Uri, currentDocUri: Uri?, relativePath: String): DocumentFile? {
        val root = DocumentFile.fromTreeUri(context, vaultUri) ?: return null
        if (currentDocUri != null) {
            val currentDoc = DocumentFile.fromSingleUri(context, currentDocUri)
            val parentPath = currentDoc?.parentFile
            if (parentPath != null) {
                findFileInDir(context, parentPath, relativePath)?.let { return it }
            }
        }
        return findAssetInVault(context, vaultUri, relativePath)
    }

    internal fun findFileInDir(context: Context, dir: DocumentFile, name: String): DocumentFile? {
        val parts = name.replace('\\', '/').split('/')
        var current: DocumentFile? = dir
        for (part in parts) {
            if (part.isEmpty() || part == ".") continue
            if (part == "..") { current = current?.parentFile; continue }
            val decodedPart = try { java.net.URLDecoder.decode(part, "UTF-8") } catch (_: Exception) { part }
            val children = listDir(context, current ?: return null)
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
