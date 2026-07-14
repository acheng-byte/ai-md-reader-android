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
                            // 也存一份含路径的 key，支持路径匹配
                            map[name] = file
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

        // 策略1：精确匹配（小写 + 原始大小写）
        cache[fileName.lowercase()]?.let { return it }
        cache[fileName]?.let { return it }

        // 策略2：去掉扩展名匹配（wikilink 可能不带 .md）
        val nameNoExt = fileName.substringBeforeLast('.')
        cache[nameNoExt.lowercase()]?.let { return it }
        cache[nameNoExt]?.let { return it }

        // 策略3：加 .md 后缀匹配
        cache["${fileName.lowercase()}.md"]?.let { return it }
        cache["$fileName.md"]?.let { return it }

        // 策略4：回退到递归扫描（缓存未覆盖的情况）
        val root = DocumentFile.fromTreeUri(context, vaultUri) ?: return null
        return findInDir(root, fileName)
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
        val cache = buildCache(context, vaultUri)

        // 策略1：用文件名（不含路径）在缓存中快速匹配
        cache[fileName.lowercase()]?.let { return it }
        cache[fileName]?.let { return it }

        // 策略2：按完整相对路径从根目录导航
        val root = DocumentFile.fromTreeUri(context, vaultUri) ?: return null
        val pathFile = findFileInDir(root, cleanPath)
        if (pathFile != null) return pathFile

        // 策略3：去掉扩展名后在缓存中模糊匹配
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
            current = current?.listFiles()?.find { it.name == part } ?: return null
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
