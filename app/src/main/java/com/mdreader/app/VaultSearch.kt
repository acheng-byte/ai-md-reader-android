package com.mdreader.app

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject

object VaultSearch {

    data class Result(val uri: String, val name: String, val excerpt: String)

    // ---- 缓存：vaultUri -> (文件名变体 -> DocumentFile) ----
    private val fileCache = HashMap<String, HashMap<String, DocumentFile>>()
    private var cacheVaultUri: String? = null

    fun clearCache() {
        fileCache.clear()
        cacheVaultUri = null
    }

    /**
     * 构建缓存：递归扫描 vault，为每个文件存储多种名称变体作为 key。
     * 如果根目录不可读（URI 权限过期），返回 null。
     */
    private fun buildCache(context: Context, vaultUri: Uri): HashMap<String, DocumentFile>? {
        val uriStr = vaultUri.toString()
        if (cacheVaultUri == uriStr && fileCache[uriStr] != null) {
            return fileCache[uriStr]!!
        }
        val map = HashMap<String, DocumentFile>()
        val root = DocumentFile.fromTreeUri(context, vaultUri)
        if (root == null || !root.canRead()) return null
        scanDir(root, map)
        if (map.isEmpty()) return null  // 扫描结果为空，可能是权限问题
        fileCache[uriStr] = map
        cacheVaultUri = uriStr
        return map
    }

    private fun scanDir(dir: DocumentFile, map: HashMap<String, DocumentFile>) {
        runCatching {
            dir.listFiles().forEach { file ->
                when {
                    file.isDirectory -> scanDir(file, map)
                    file.isFile -> {
                        val name = file.name ?: return@forEach
                        // 原始名称
                        map[name] = file
                        map[name.lowercase()] = file
                        // URL 解码版本（某些 Storage Provider 返回编码后的名称）
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
    }

    /**
     * 为文件名生成所有可能的匹配变体：
     * 原始、小写、URL解码、去扩展名、加.md 等
     */
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

        // 去扩展名
        val nameNoExt = fileName.substringBeforeLast('.')
        if (nameNoExt != fileName) {
            list.add(nameNoExt)
            list.add(nameNoExt.lowercase())
        }

        // 加 .md 后缀
        if (!fileName.endsWith(".md", ignoreCase = true)) {
            list.add("$fileName.md")
            list.add("${fileName.lowercase()}.md")
        }

        return list
    }

    // ---- 全文搜索 ----

    fun search(context: Context, vaultUri: Uri, query: String, maxResults: Int = 40): String {
        val root = DocumentFile.fromTreeUri(context, vaultUri)
            ?: return "[]"
        val list = mutableListOf<Result>()
        val q = query.trim().lowercase()
        if (q.isNotEmpty()) searchDir(context, root, q, list, maxResults)
        return toJson(list)
    }

    private fun searchDir(
        context: Context,
        dir: DocumentFile,
        query: String,
        out: MutableList<Result>,
        max: Int
    ) {
        if (out.size >= max) return
        runCatching {
            dir.listFiles().forEach { file ->
                if (out.size >= max) return
                when {
                    file.isDirectory -> searchDir(context, file, query, out, max)
                    file.isFile -> tryMatchFile(context, file, query, out)
                }
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

        // 优先用缓存查找（快速，支持多种名称变体匹配）
        val cache = buildCache(context, vaultUri)
        if (cache != null) {
            val fileName = cleanName.substringAfterLast('/').trim()
            if (fileName.isNotEmpty()) {
                val candidates = buildNameCandidates(fileName)
                for (name in candidates) {
                    cache[name]?.let { return it }
                }
            }
        }

        // 缓存不可用或未命中：回退到递归搜索
        val root = DocumentFile.fromTreeUri(context, vaultUri) ?: return null

        // 有路径前缀：先尝试路径导航定位目录，再在该目录下搜索
        if (cleanName.contains('/')) {
            val parts = cleanName.split('/').filter { it.isNotEmpty() }
            val filename = parts.last()
            val dirParts = parts.dropLast(1)
            var dir: DocumentFile? = root
            for (part in dirParts) {
                dir = dir?.listFiles()?.find {
                    it.isDirectory && dirNameMatches(it.name, part)
                }
                if (dir == null) break
            }
            if (dir != null) {
                val found = findInDir(dir, filename)
                if (found != null) return found
            }
            // 路径导航失败：全库按文件名搜索
            return findInDir(root, filename)
        }

        return findInDir(root, cleanName)
    }

    /**
     * 目录名匹配：支持原始、URL解码、大小写不敏感
     */
    private fun dirNameMatches(dirName: String?, target: String): Boolean {
        if (dirName == null) return false
        if (dirName.equals(target, ignoreCase = true)) return true
        try {
            val decoded = java.net.URLDecoder.decode(dirName, "UTF-8")
            if (decoded.equals(target, ignoreCase = true)) return true
        } catch (_: Exception) {}
        return false
    }

    /**
     * 递归搜索目录，匹配文件名。
     * 支持多种名称变体：原始、去扩展名、加.md、URL解码。
     */
    private fun findInDir(dir: DocumentFile, noteName: String): DocumentFile? {
        runCatching {
            dir.listFiles().forEach { file ->
                if (file.isDirectory) {
                    findInDir(file, noteName)?.let { return it }
                } else {
                    val fn = file.name ?: return@forEach
                    if (fileNameMatches(fn, noteName)) return file
                }
            }
        }
        return null
    }

    /**
     * 文件名匹配：检查多种变体
     * - base.equals(noteName)  （去扩展名后比较）
     * - fn.equals(noteName)    （完整名比较）
     * - fn.equals(noteName.md) （加.md 后缀比较）
     * - URL 解码后以上三种
     */
    private fun fileNameMatches(actualName: String, targetName: String): Boolean {
        val base = actualName.substringBeforeLast(".")
        // 直接匹配
        if (base.equals(targetName, ignoreCase = true)) return true
        if (actualName.equals(targetName, ignoreCase = true)) return true
        if (actualName.equals("$targetName.md", ignoreCase = true)) return true
        // URL 解码后匹配
        try {
            val decoded = java.net.URLDecoder.decode(actualName, "UTF-8")
            val decodedBase = decoded.substringBeforeLast(".")
            if (decodedBase.equals(targetName, ignoreCase = true)) return true
            if (decoded.equals(targetName, ignoreCase = true)) return true
            if (decoded.equals("$targetName.md", ignoreCase = true)) return true
        } catch (_: Exception) {}
        return false
    }

    // ---- 资源文件查找（图片等） ----

    fun findAssetInVault(context: Context, vaultUri: Uri, relativePath: String): DocumentFile? {
        val cleanPath = relativePath.replace('\\', '/').trimStart('/')
        if (cleanPath.isEmpty()) return null

        val fileName = cleanPath.substringAfterLast('/')
        if (fileName.isEmpty()) return null

        // 优先缓存
        val cache = buildCache(context, vaultUri)
        if (cache != null) {
            val candidates = buildNameCandidates(fileName)
            for (name in candidates) {
                cache[name]?.let { return it }
            }
        }

        // 回退：路径导航 + 递归搜索
        val root = DocumentFile.fromTreeUri(context, vaultUri) ?: return null
        val pathResult = findFileInDir(root, cleanPath)
        if (pathResult != null) return pathResult

        return findInDir(root, fileName)
    }

    fun resolveRelativeAsset(
        context: Context,
        vaultUri: Uri,
        currentDocUri: Uri?,
        relativePath: String
    ): DocumentFile? {
        val root = DocumentFile.fromTreeUri(context, vaultUri) ?: return null
        if (currentDocUri != null) {
            val currentDoc = DocumentFile.fromSingleUri(context, currentDocUri)
            val parentPath = currentDoc?.parentFile
            if (parentPath != null) {
                val candidate = findFileInDir(parentPath, relativePath)
                if (candidate != null) return candidate
            }
        }
        return findAssetInVault(context, vaultUri, relativePath)
    }

    internal fun findFileInDir(dir: DocumentFile, name: String): DocumentFile? {
        val parts = name.replace('\\', '/').split('/')
        var current: DocumentFile? = dir
        for (part in parts) {
            if (part.isEmpty() || part == ".") continue
            if (part == "..") { current = current?.parentFile; continue }
            val decodedPart = try {
                java.net.URLDecoder.decode(part, "UTF-8")
            } catch (_: Exception) { part }
            current = current?.listFiles()?.find {
                it.name == part || it.name == decodedPart ||
                    it.name?.equals(part, ignoreCase = true) == true ||
                    it.name?.equals(decodedPart, ignoreCase = true) == true
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
