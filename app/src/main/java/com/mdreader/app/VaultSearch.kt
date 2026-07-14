package com.mdreader.app

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject

object VaultSearch {

    data class Result(val uri: String, val name: String, val excerpt: String)

    /** 清除缓存（vault 切换时调用）— 已移除缓存，保留接口兼容 */
    fun clearCache() {}

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
     * 先尝试路径导航，找不到再递归搜索文件名。
     */
    fun findAssetInVault(context: Context, vaultUri: Uri, relativePath: String): DocumentFile? {
        val cleanPath = relativePath.replace('\\', '/').trimStart('/')
        if (cleanPath.isEmpty()) return null

        val root = DocumentFile.fromTreeUri(context, vaultUri) ?: return null

        // 先尝试按完整路径导航
        val pathResult = findFileInDir(root, cleanPath)
        if (pathResult != null) return pathResult

        // 回退：按文件名递归搜索
        val fileName = cleanPath.substringAfterLast('/')
        if (fileName.isNotEmpty()) {
            return findInDir(root, fileName)
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
        // Fallback: search anywhere in vault
        return findInDir(root, relativePath.substringAfterLast('/'))
    }

    private fun findFileInDir(dir: DocumentFile, name: String): DocumentFile? {
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
