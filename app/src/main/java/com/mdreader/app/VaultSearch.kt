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
        val root = DocumentFile.fromTreeUri(context, vaultUri) ?: return null
        // Strip heading anchor: [[File#Heading]] → "File"
        val cleanName = noteName.substringBefore('#').trim()
        // Handle path-based wikilinks: [[Folder/Filename]]
        if (cleanName.contains('/')) {
            val parts = cleanName.split('/').filter { it.isNotEmpty() }
            val filename = parts.last()
            val dirParts = parts.dropLast(1)
            // Try exact path navigation first
            var dir: DocumentFile? = root
            for (part in dirParts) {
                dir = dir?.listFiles()?.find { it.isDirectory && (it.name ?: "").equals(part, ignoreCase = true) }
                if (dir == null) break
            }
            if (dir != null) {
                val found = findInDir(dir, filename)
                if (found != null) return found
            }
            // Fallback: search entire vault by filename only
            return findInDir(root, filename)
        }
        return findInDir(root, cleanName)
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

        // 策略1：缓存快速查找 — 用文件名（最后一段）小写匹配
        val fileName = cleanPath.substringAfterLast('/')
        val cache = buildCache(context, vaultUri)
        cache[fileName.lowercase()]?.let { return it }
        cache[fileName]?.let { return it }

        // 策略2：按完整相对路径从根目录导航
        val root = DocumentFile.fromTreeUri(context, vaultUri) ?: return null
        val pathFile = findFileInDir(root, cleanPath)
        if (pathFile != null) return pathFile

        // 策略3：路径导航失败时，用文件名在缓存中模糊匹配（去掉扩展名再试）
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
