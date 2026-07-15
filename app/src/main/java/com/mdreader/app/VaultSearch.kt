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

    // ---- 缓存（已改为按需查找，不再全库扫描） ----

    @Synchronized
    fun clearCache() {
        Logger.i(TAG, "【清除缓存】缓存已清空")
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

        val fileName = cleanName.substringAfterLast('/').trim()
        if (fileName.isEmpty()) return null

        Logger.i(TAG, "查找WikiLink: cleanName=$cleanName, fileName=$fileName")

        // 优先使用持久化索引（O(1) 查找，无 SAF 调用）
        if (VaultIndex.isReady()) {
            Logger.i(TAG, "索引就绪，共 ${VaultIndex.entryCount()} 个条目，开始查找")
            // 带路径的查找（如 话术类/100条郭德纲经典语录）
            if (cleanName.contains('/')) {
                val pathWithExt = if (cleanName.endsWith(".md", ignoreCase = true) || cleanName.endsWith(".markdown", ignoreCase = true))
                    cleanName else "$cleanName.md"
                val indexEntry = VaultIndex.findByPath(pathWithExt)
                if (indexEntry != null) {
                    Logger.i(TAG, "索引命中(路径): ${indexEntry.path}")
                    runCatching {
                        val df = DocumentFile.fromSingleUri(context, Uri.parse(indexEntry.uri))
                        if (df?.exists() == true) {
                            Logger.i(TAG, "文件验证存在，返回: ${df.name}")
                            return df
                        } else {
                            Logger.w(TAG, "索引命中但文件不存在: ${indexEntry.uri}")
                        }
                    }
                } else {
                    Logger.i(TAG, "findByPath 未命中: $pathWithExt")
                }
            }
            // 按文件名查找（带扩展名）
            val nameWithExt = if (fileName.endsWith(".md", ignoreCase = true) || fileName.endsWith(".markdown", ignoreCase = true))
                fileName else "$fileName.md"
            val nameEntry = VaultIndex.findByName(nameWithExt)
            if (nameEntry != null) {
                Logger.i(TAG, "索引命中(文件名+扩展名): ${nameEntry.path}")
                runCatching {
                    val df = DocumentFile.fromSingleUri(context, Uri.parse(nameEntry.uri))
                    if (df?.exists() == true) {
                        Logger.i(TAG, "文件验证存在，返回: ${df.name}")
                        return df
                    } else {
                        Logger.w(TAG, "索引命中但文件不存在: ${nameEntry.uri}")
                    }
                }
            } else {
                Logger.i(TAG, "findByName 未命中(带扩展名): $nameWithExt")
            }
            // 也尝试不带扩展名
            val baseName = fileName.substringBeforeLast('.')
            val nameEntryNoExt = VaultIndex.findByName(baseName)
            if (nameEntryNoExt != null && nameEntryNoExt != nameEntry) {
                Logger.i(TAG, "索引命中(无扩展名): ${nameEntryNoExt.path}")
                runCatching {
                    val df = DocumentFile.fromSingleUri(context, Uri.parse(nameEntryNoExt.uri))
                    if (df?.exists() == true) {
                        Logger.i(TAG, "文件验证存在，返回: ${df.name}")
                        return df
                    } else {
                        Logger.w(TAG, "索引命中但文件不存在: ${nameEntryNoExt.uri}")
                    }
                }
            }
            // 诊断：在索引中搜索包含目标目录名的条目，确认索引是否包含该目录的文件
            if (cleanName.contains('/')) {
                val dirPart = cleanName.substringBefore('/')
                val samples = VaultIndex.sampleByDir(dirPart, 5)
                if (samples.isEmpty()) {
                    Logger.w(TAG, "索引中完全没有目录 '$dirPart' 的文件！")
                } else {
                    Logger.i(TAG, "索引中 '$dirPart' 目录的示例文件: ${samples.joinToString(", ")}")
                }
            }
            // 最后手段：模糊子串匹配
            val fuzzyEntry = VaultIndex.fuzzyFindByName(baseName)
            if (fuzzyEntry != null) {
                Logger.i(TAG, "模糊匹配命中: search=$baseName, found=${fuzzyEntry.name} (path=${fuzzyEntry.path})")
                runCatching {
                    val df = DocumentFile.fromSingleUri(context, Uri.parse(fuzzyEntry.uri))
                    if (df?.exists() == true) {
                        Logger.i(TAG, "模糊匹配文件存在，返回: ${df.name}")
                        return df
                    } else {
                        Logger.w(TAG, "模糊匹配但文件不存在: ${fuzzyEntry.uri}")
                    }
                }
            } else {
                Logger.w(TAG, "模糊匹配也失败: $baseName")
            }
            Logger.i(TAG, "索引全部未命中，回退到 SAF 路径导航")
        } else {
            Logger.w(TAG, "索引未就绪，直接使用 SAF 路径导航")
        }

        val root = DocumentFile.fromTreeUri(context, encoded)
        if (root == null) {
            Logger.e(TAG, "SAF回退: root为null, encoded=${encoded.toString().take(80)}")
            return null
        }
        Logger.i(TAG, "SAF回退: root有效, exists=${root.exists()}, isDir=${root.isDirectory}")
        val rootChildren = listDir(context, encoded, root)
        Logger.i(TAG, "SAF回退: root列出 ${rootChildren.size} 项, 子目录: ${rootChildren.filter { it.isDirectory }.map { it.name }.joinToString(",")}")

        if (cleanName.contains('/')) {
            val parts = cleanName.split('/').filter { it.isNotEmpty() }
            val dirParts = parts.dropLast(1)

            // 路径导航：先尝试指定目录
            var dir: DocumentFile? = root
            for (part in dirParts) {
                val children = listDir(context, encoded, dir ?: break)
                Logger.i(TAG, "SAF路径导航: 在 '${dir?.name ?: "root"}' 中查找目录 '$part', 列出 ${children.size} 项")
                dir = children.find {
                    it.isDirectory && nameMatches(it.name, part)
                }
                if (dir == null) {
                    Logger.w(TAG, "SAF路径导航: 未找到目录 '$part'")
                    break
                } else {
                    Logger.i(TAG, "SAF路径导航: 找到目录 '$part'")
                }
            }

            if (dir != null) {
                val targetChildren = listDir(context, encoded, dir)
                Logger.i(TAG, "SAF目标目录: '${dir.name}' 包含 ${targetChildren.size} 项")
                findInDir(context, encoded, dir, fileName)?.let {
                    Logger.i(TAG, "SAF路径导航成功找到: ${it.name}")
                    return it
                }
            }

            // 回退：全库根目录递归搜索文件名
            Logger.i(TAG, "SAF全库递归搜索: fileName=$fileName")
            val found = findInDir(context, encoded, root, fileName)
            if (found != null) {
                Logger.i(TAG, "SAF全库递归找到: ${found.name}")
            } else {
                Logger.w(TAG, "SAF全库递归也未找到: $fileName")
            }
            return found
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
        val cleanPath = relativePath.replace('\\', '/').trimStart('/')
        if (cleanPath.isEmpty()) return null

        // 索引未就绪 → 直接返回，绝不做 SAF 调用阻塞 WebView IO 线程（否则白屏）
        if (!VaultIndex.isReady()) return null

        // 1. 优先查持久化索引（HashMap 瞬间命中，无 SAF 调用）
        val indexEntry = VaultIndex.findByPath(cleanPath)
        if (indexEntry != null) {
            runCatching {
                val df = DocumentFile.fromSingleUri(context, Uri.parse(indexEntry.uri))
                if (df?.exists() == true) return df
            }
        }

        // 2. 索引已就绪但未命中 → 定向路径导航（大小写不敏感，单步 listDir）
        val encoded = ensureEncoded(vaultUri)
        val root = DocumentFile.fromTreeUri(context, encoded) ?: return null
        val parts = cleanPath.split('/').filter { it.isNotEmpty() }

        var current: DocumentFile? = root
        for (part in parts) {
            if (current == null) return null
            val children = listDir(context, encoded, current)
            val decoded = try { java.net.URLDecoder.decode(part, "UTF-8") } catch (_: Exception) { part }
            current = children.find {
                it.name?.equals(part, ignoreCase = true) == true ||
                    it.name?.equals(decoded, ignoreCase = true) == true
            }
        }
        current?.takeIf { it.isFile }?.let { return it }

        // 3. 路径导航未命中 → 对简单文件名做递归搜索（最后手段，索引已就绪时才会执行）
        if (!cleanPath.contains('/')) {
            return findInDir(context, encoded, root, cleanPath)
        }
        return null
    }

    fun resolveRelativeAsset(context: Context, vaultUri: Uri, currentDocUri: Uri?, relativePath: String): DocumentFile? {
        // 索引未就绪 → 不做 SAF 调用，避免阻塞 WebView IO 线程
        if (!VaultIndex.isReady()) return null
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
