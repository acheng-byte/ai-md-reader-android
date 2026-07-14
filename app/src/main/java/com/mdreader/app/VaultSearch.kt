package com.mdreader.app

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject

object VaultSearch {

    data class Result(val uri: String, val name: String, val excerpt: String)

    /** 缓存：vaultUri -> (文件名小写 -> DocumentFile)，避免每次图片请求都递归扫描 */
    private val fileCache = HashMap<String, HashMap<String, DocumentFile>>()
    private var cacheVaultUri: String? = null

    /** 清除缓存（vault 切换时调用） */
    fun clearCache() {
        fileCache.clear()
        cacheVaultUri = null
    }

    /** 构建/获取缓存：递归扫描 vault，建立 文件名小写 -> DocumentFile 映射 */
    private fun buildCache(context: Context, vaultUri: Uri): HashMap<String, DocumentFile> {
        val uriStr = vaultUri.toString()
        if (cacheVaultUri == uriStr && fileCache[uriStr] != null) {
            return fileCache[uriStr]!!
        }
        val map = HashMap<String, DocumentFile>()
        val root = DocumentFile.fromTreeUri(context, vaultUri)
        if (root != null) {
            scanDir(root, map)
        }
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
                        val name = file.name
                        if (name != null) {
                            // 用文件名小写作为 key（不含路径），支持快速查找
                            map[name.lowercase()] = file
                            // 也存一份原始大小写
                            map[name] = file
                            // 存一份 URL 解码后的名称（某些设备上 DocumentFile.getName() 可能返回编码后的名称）
                            try {
                                val decoded = java.net.URLDecoder.decode(name, "UTF-8")
                                if (decoded != name) {
                                    map[decoded.lowercase()] = file
                                    map[decoded] = file
                                }
                            } catch (_: Exception) {}
                        }
                    }
                }
            }
        }
    }

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

    fun findFile(context: Context, vaultUri: Uri, noteName: String): DocumentFile? {
        // Strip heading anchor: [[File#Heading]] → "File"
        val cleanName = noteName.substringBefore('#').trim()
        // 不管路径前缀，只取文件名，全库搜索（Obsidian 行为）
        // [[话术类/必学的甜言蜜语(1).md]] → 搜 "必学的甜言蜜语(1).md"
        val fileName = cleanName.substringAfterLast('/').trim()
        if (fileName.isEmpty()) return null

        val cache = buildCache(context, vaultUri)

        // 尝试多种名称变体在缓存中匹配
        val candidates = buildNameCandidates(fileName)
        for (name in candidates) {
            cache[name]?.let { return it }
        }

        // 回退到递归扫描（缓存未覆盖的情况）
        val root = DocumentFile.fromTreeUri(context, vaultUri) ?: return null
        return findInDir(root, fileName)
    }

    /**
     * 为一个文件名生成所有可能的缓存 key 变体：
     * 原始、小写、URL解码、去扩展名、加.md 等
     */
    private fun buildNameCandidates(fileName: String): List<String> {
        val list = mutableListOf<String>()
        list.add(fileName)
        list.add(fileName.lowercase())

        // URL 解码版本
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

    private fun findInDir(dir: DocumentFile, noteName: String): DocumentFile? {
        runCatching {
            dir.listFiles().forEach { file ->
                if (file.isDirectory) {
                    findInDir(file, noteName)?.let { return it }
                } else {
                    val fn = file.name ?: return@forEach
                    val base = fn.substringBeforeLast(".")
                    if (base.equals(noteName, ignoreCase = true) ||
                        fn.equals(noteName, ignoreCase = true) ||
                        fn.equals("$noteName.md", ignoreCase = true)
                    ) return file
                }
            }
        }
        return null
    }

    /**
     * 全库查找文件（支持图片等任意类型）。
     * 优先用缓存快速匹配，找不到时回退到路径导航。
     */
    fun findAssetInVault(context: Context, vaultUri: Uri, relativePath: String): DocumentFile? {
        val cleanPath = relativePath.replace('\\', '/').trimStart('/')
        if (cleanPath.isEmpty()) return null

        val fileName = cleanPath.substringAfterLast('/')  // 取最后一段文件名
        if (fileName.isEmpty()) return null
        val cache = buildCache(context, vaultUri)

        // 尝试多种名称变体在缓存中匹配
        val candidates = buildNameCandidates(fileName)
        for (name in candidates) {
            cache[name]?.let { return it }
        }

        // 按完整相对路径从根目录导航
        val root = DocumentFile.fromTreeUri(context, vaultUri) ?: return null
        val pathFile = findFileInDir(root, cleanPath)
        if (pathFile != null) return pathFile

        // 回退：遍历缓存做模糊匹配（去掉扩展名后忽略大小写比较）
        val nameNoExt = fileName.substringBeforeLast('.')
        for ((key, file) in cache) {
            val keyNoExt = key.substringBeforeLast('.')
            if (keyNoExt.equals(nameNoExt, ignoreCase = true)) return file
        }

        return null
    }

    /** Resolve an image path relative to the current document inside the vault tree. */
    fun resolveRelativeAsset(
        context: Context,
        vaultUri: Uri,
        currentDocUri: Uri?,
        relativePath: String
    ): DocumentFile? {
        val root = DocumentFile.fromTreeUri(context, vaultUri) ?: return null
        // First try: find relative to current doc's directory
        if (currentDocUri != null) {
            val currentDoc = DocumentFile.fromSingleUri(context, currentDocUri)
            val parentPath = currentDoc?.parentFile
            if (parentPath != null) {
                val candidate = findFileInDir(parentPath, relativePath)
                if (candidate != null) return candidate
            }
        }
        // Fallback: search entire vault using cache
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
