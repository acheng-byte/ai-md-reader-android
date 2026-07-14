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
        Logger.i(TAG, "clearCache()")
    }

    /**
     * 从 DocumentFile 提取 document ID（多种方式尝试）。
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
     * treeUri: 原始 vault 的 tree URI，递归时始终保持不变，确保子目录也能走 DocumentsContract 回退。
     */
    private fun listDir(context: Context, treeUri: Uri, dir: DocumentFile): List<DocumentFile> {
        val dirName = dir.name ?: dir.uri.toString().take(60)

        // 方式1: DocumentFile.listFiles()
        val dfList = runCatching { dir.listFiles() }.getOrNull()
        if (!dfList.isNullOrEmpty()) {
            Logger.d(TAG, "listDir DF_OK: $dirName → ${dfList.size} items")
            return dfList.toList()
        }
        Logger.w(TAG, "listDir DF_EMPTY: $dirName (DocumentFile.listFiles returned null/empty)")

        // 方式2+3: DocumentsContract 查询（始终使用原始 treeUri）
        val docId = extractDocId(dir.uri)
        Logger.d(TAG, "listDir extractDocId: dir.uri=${dir.uri.toString().take(80)}, docId=$docId")

        if (docId != null && docId.isNotBlank()) {
            val results = runCatching {
                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
                Logger.d(TAG, "listDir DC query: childrenUri=$childrenUri")
                val list = mutableListOf<DocumentFile>()
                context.contentResolver.query(
                    childrenUri,
                    arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                    null, null, null
                )?.use { cursor ->
                    Logger.d(TAG, "listDir DC query rows: ${cursor.count}")
                    while (cursor.moveToNext()) {
                        val childDocId = cursor.getString(0) ?: continue
                        // 关键修复：用原始 treeUri 构建子文件的 tree-based URI
                        val childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childDocId)
                        DocumentFile.fromSingleUri(context, childUri)?.let { list.add(it) }
                    }
                }
                list
            }.getOrNull()
            if (!results.isNullOrEmpty()) {
                Logger.d(TAG, "listDir DC_OK: $dirName → ${results.size} items (docId=$docId)")
                return results
            }
            Logger.w(TAG, "listDir DC_EMPTY: docId=$docId")
        } else {
            Logger.e(TAG, "listDir: could not extract docId from dir.uri=${dir.uri.toString().take(100)}")
        }

        Logger.e(TAG, "listDir FAIL: $dirName — all methods returned empty. uri=${dir.uri.toString().take(80)}")
        return emptyList()
    }

    /**
     * 构建缓存
     */
    private fun buildCache(context: Context, vaultUri: Uri): HashMap<String, DocumentFile>? {
        val uriStr = vaultUri.toString()
        if (cacheVaultUri == uriStr && fileCache[uriStr] != null) {
            Logger.d(TAG, "buildCache HIT: ${fileCache[uriStr]!!.size} entries")
            return fileCache[uriStr]!!
        }
        Logger.i(TAG, "buildCache MISS, scanning: $uriStr")
        val map = HashMap<String, DocumentFile>()
        val root = DocumentFile.fromTreeUri(context, vaultUri)
        if (root == null) {
            Logger.e(TAG, "buildCache: fromTreeUri returned null")
            return null
        }
        Logger.d(TAG, "buildCache: root=${root.name}, canRead=${root.canRead()}, uri=$uriStr")
        scanDir(context, vaultUri, root, map)
        Logger.i(TAG, "buildCache: scanned ${map.size} entries")
        if (map.isEmpty()) {
            Logger.e(TAG, "buildCache: scan returned 0 entries — URI permission may be expired")
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
        Logger.i(TAG, "search: query='$query', vaultUri=${vaultUri.toString().take(60)}")
        val root = DocumentFile.fromTreeUri(context, vaultUri)
        if (root == null) {
            Logger.e(TAG, "search: fromTreeUri returned null")
            return "[]"
        }
        val list = mutableListOf<Result>()
        val q = query.trim().lowercase()
        if (q.isNotEmpty()) searchDir(context, vaultUri, root, q, list, maxResults)
        Logger.i(TAG, "search: found ${list.size} results")
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
        Logger.i(TAG, "findFile: noteName='$noteName'")
        val cleanName = noteName.substringBefore('#').trim()
        if (cleanName.isEmpty()) {
            Logger.w(TAG, "findFile: cleanName is empty")
            return null
        }

        // 优先缓存
        val cache = buildCache(context, vaultUri)
        if (cache != null) {
            val fileName = cleanName.substringAfterLast('/').trim()
            if (fileName.isNotEmpty()) {
                val candidates = buildNameCandidates(fileName)
                Logger.d(TAG, "findFile cache lookup: fileName='$fileName', candidates=$candidates")
                for (name in candidates) {
                    val found = cache[name]
                    if (found != null) {
                        Logger.i(TAG, "findFile CACHE HIT: '$name' → ${found.name}")
                        return found
                    }
                }
                Logger.d(TAG, "findFile cache MISS for all candidates")
            }
        } else {
            Logger.w(TAG, "findFile: cache is null, falling back to recursive search")
        }

        // 回退：递归搜索
        val root = DocumentFile.fromTreeUri(context, vaultUri)
        if (root == null) {
            Logger.e(TAG, "findFile: fromTreeUri returned null (fallback)")
            return null
        }
        Logger.d(TAG, "findFile fallback: root=${root.name}, canRead=${root.canRead()}")

        if (cleanName.contains('/')) {
            val parts = cleanName.split('/').filter { it.isNotEmpty() }
            val filename = parts.last()
            val dirParts = parts.dropLast(1)
            Logger.d(TAG, "findFile path: dirParts=$dirParts, filename='$filename'")

            // 路径导航
            var dir: DocumentFile? = root
            for (part in dirParts) {
                val children = listDir(context, vaultUri, dir ?: break)
                Logger.d(TAG, "findFile nav: looking for '$part' in ${children.size} children")
                dir = children.find {
                    it.isDirectory && nameMatches(it.name, part)
                }
                if (dir == null) {
                    Logger.w(TAG, "findFile nav: directory '$part' not found")
                    break
                }
                Logger.d(TAG, "findFile nav: found dir '${dir.name}'")
            }

            if (dir != null) {
                val found = findInDir(context, vaultUri, dir, filename)
                if (found != null) {
                    Logger.i(TAG, "findFile FOUND (path nav): ${found.name}")
                    return found
                }
                Logger.d(TAG, "findFile: path nav search missed, trying full vault search")
            }

            // 全库搜索
            Logger.d(TAG, "findFile: full vault search for '$filename'")
            val result = findInDir(context, vaultUri, root, filename)
            if (result != null) {
                Logger.i(TAG, "findFile FOUND (full search): ${result.name}")
            } else {
                Logger.e(TAG, "findFile NOT FOUND: '$filename' in vault")
            }
            return result
        }

        Logger.d(TAG, "findFile: simple search for '$cleanName'")
        val result = findInDir(context, vaultUri, root, cleanName)
        if (result != null) {
            Logger.i(TAG, "findFile FOUND: ${result.name}")
        } else {
            Logger.e(TAG, "findFile NOT FOUND: '$cleanName'")
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
        val cleanPath = relativePath.replace('\\', '/').trimStart('/')
        if (cleanPath.isEmpty()) return null
        val fileName = cleanPath.substringAfterLast('/')
        if (fileName.isEmpty()) return null

        Logger.d(TAG, "findAssetInVault: path='$cleanPath', file='$fileName'")
        val cache = buildCache(context, vaultUri)
        if (cache != null) {
            for (name in buildNameCandidates(fileName)) {
                cache[name]?.let {
                    Logger.i(TAG, "findAssetInVault CACHE HIT: $name")
                    return it
                }
            }
        }

        val root = DocumentFile.fromTreeUri(context, vaultUri) ?: return null
        findFileInDir(context, vaultUri, root, cleanPath)?.let { return it }
        val result = findInDir(context, vaultUri, root, fileName)
        if (result != null) Logger.i(TAG, "findAssetInVault FOUND: ${result.name}")
        else Logger.e(TAG, "findAssetInVault NOT FOUND: $fileName")
        return result
    }

    fun resolveRelativeAsset(context: Context, vaultUri: Uri, currentDocUri: Uri?, relativePath: String): DocumentFile? {
        val root = DocumentFile.fromTreeUri(context, vaultUri) ?: return null
        if (currentDocUri != null) {
            val currentDoc = DocumentFile.fromSingleUri(context, currentDocUri)
            val parentPath = currentDoc?.parentFile
            if (parentPath != null) {
                findFileInDir(context, vaultUri, parentPath, relativePath)?.let { return it }
            }
        }
        return findAssetInVault(context, vaultUri, relativePath)
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
