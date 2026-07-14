package com.mdreader.app

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 库文件索引：持久化缓存，避免每次打开都重新扫描。
 *
 * 扫描结果存到内部存储 filesDir/vault_index.json，下次启动直接加载。
 * 提供文件名→DocumentFile 的快速查找（大小写不敏感、URL 解码兼容）。
 */
object VaultIndex {

    private const val TAG = "VaultIndex"
    private const val CACHE_FILE = "vault_index.json"

    data class Entry(
        val name: String,
        val path: String,
        val uri: String
    )

    private val entries = HashMap<String, Entry>()       // lowercase name → first entry
    private val allEntries = ArrayList<Entry>()           // 全部条目（用于精确匹配）
    private var indexVaultUri: String? = null
    @Volatile private var scanning = false
    @Volatile private var ready = false

    fun isScanning(): Boolean = scanning
    fun isReady(): Boolean = ready
    fun entryCount(): Int = synchronized(this) { allEntries.size }

    /** 按文件名查找（大小写不敏感 + URL 解码兼容） */
    fun findByName(fileName: String): Entry? {
        if (!ready) return null
        val lower = fileName.lowercase()

        // 1. 精确匹配（小写键）
        entries[lower]?.let { return it }

        // 2. URL 解码后匹配
        val decoded = try { java.net.URLDecoder.decode(fileName, "UTF-8") } catch (_: Exception) { null }
        if (decoded != null && decoded != fileName) {
            entries[decoded.lowercase()]?.let { return it }
        }

        // 3. 遍历全部条目做大小写不敏感匹配
        for (e in allEntries) {
            if (e.name.equals(fileName, ignoreCase = true)) return e
            if (decoded != null && e.name.equals(decoded, ignoreCase = true)) return e
        }
        return null
    }

    /** 按完整路径查找（如 images/photo.png） */
    fun findByPath(relativePath: String): Entry? {
        if (!ready) return null
        val normalized = relativePath.replace('\\', '/').trimStart('/')
        val fileName = normalized.substringAfterLast('/')

        // 先按文件名快速查找
        val byName = findByName(fileName) ?: return null

        // 如果是简单文件名（无路径），直接返回
        if (!normalized.contains('/')) return byName

        // 有路径前缀，验证路径匹配
        for (e in allEntries) {
            if (e.name.equals(fileName, ignoreCase = true)) {
                val entryPath = e.path.replace('\\', '/').trimStart('/')
                if (entryPath.endsWith(normalized, ignoreCase = true) ||
                    entryPath.equals(normalized, ignoreCase = true)) {
                    return e
                }
            }
        }
        return byName  // 路径不匹配时返回文件名匹配的结果
    }

    /** 后台扫描库文件夹，结果持久化到内部存储 */
    fun scanInBackground(context: Context, vaultUri: Uri, onDone: (() -> Unit)? = null) {
        if (scanning) return
        Thread {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_LOWEST)
            scanning = true
            try {
                val encoded = VaultSearch.ensureEncoded(vaultUri)
                val uriStr = encoded.toString()
                Logger.i(TAG, "开始索引库文件夹...")
                val list = ArrayList<Entry>()
                val map = HashMap<String, Entry>()
                val root = DocumentFile.fromTreeUri(context, encoded)
                if (root != null) {
                    scanDir(context, encoded, root, "", list, map)
                }
                // 写入内存
                synchronized(this) {
                    allEntries.clear()
                    allEntries.addAll(list)
                    entries.clear()
                    entries.putAll(map)
                    indexVaultUri = uriStr
                    ready = true
                }
                // 持久化到磁盘
                saveToDisk(context, list)
                Logger.i(TAG, "索引完成: ${list.size} 个文件")
            } catch (e: Exception) {
                Logger.e(TAG, "索引失败: ${e.message}")
            } finally {
                scanning = false
            }
            onDone?.invoke()
        }.start()
    }

    /** 从磁盘加载缓存索引 */
    fun loadFromDisk(context: Context, vaultUri: Uri): Boolean {
        val uriStr = VaultSearch.ensureEncoded(vaultUri).toString()
        val file = File(context.filesDir, CACHE_FILE)
        if (!file.exists()) return false
        return try {
            val json = file.readText()
            val obj = JSONObject(json)
            val cachedUri = obj.optString("vaultUri", "")
            if (cachedUri != uriStr) {
                Logger.i(TAG, "库 URI 已变更，忽略旧索引")
                return false
            }
            val arr = obj.getJSONArray("files")
            val list = ArrayList<Entry>()
            val map = HashMap<String, Entry>()
            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                val entry = Entry(
                    name = item.getString("name"),
                    path = item.getString("path"),
                    uri = item.getString("uri")
                )
                list.add(entry)
                val lower = entry.name.lowercase()
                if (map[lower] == null) map[lower] = entry
            }
            synchronized(this) {
                allEntries.clear()
                allEntries.addAll(list)
                entries.clear()
                entries.putAll(map)
                indexVaultUri = uriStr
                ready = true
            }
            Logger.i(TAG, "从磁盘加载索引: ${list.size} 个文件")
            true
        } catch (e: Exception) {
            Logger.e(TAG, "加载索引失败: ${e.message}")
            false
        }
    }

    /** 清除索引 */
    fun clear() {
        synchronized(this) {
            allEntries.clear()
            entries.clear()
            indexVaultUri = null
            ready = false
        }
        runCatching { File(context_filesDir_ref, CACHE_FILE).delete() }
        Logger.i(TAG, "索引已清除")
    }

    // ---- 内部方法 ----

    private var context_filesDir_ref: File? = null

    private fun scanDir(context: Context, treeUri: Uri, dir: DocumentFile, relPath: String,
                        list: ArrayList<Entry>, map: HashMap<String, Entry>) {
        val children = listDir(context, treeUri, dir)
        if (children.isEmpty()) return
        for (file in children) {
            val name = file.name ?: continue
            if (file.isDirectory) {
                val childPath = if (relPath.isEmpty()) name else "$relPath/$name"
                scanDir(context, treeUri, file, childPath, list, map)
            } else {
                val entry = Entry(
                    name = name,
                    path = if (relPath.isEmpty()) name else "$relPath/$name",
                    uri = file.uri.toString()
                )
                list.add(entry)
                val lower = name.lowercase()
                if (map[lower] == null) map[lower] = entry
            }
        }
    }

    private fun listDir(context: Context, treeUri: Uri, dir: DocumentFile): List<DocumentFile> {
        // 方式1: DocumentFile.listFiles()
        val dfList = runCatching { dir.listFiles() }.getOrNull()
        if (!dfList.isNullOrEmpty()) return dfList.toList()

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
            if (!results.isNullOrEmpty()) return results
        }
        return emptyList()
    }

    private fun extractDocId(uri: Uri): String? {
        val path = uri.path ?: return null
        val segments = path.split("/").filter { it.isNotEmpty() }
        val treeIdx = segments.indexOf("tree")
        if (treeIdx >= 0 && treeIdx < segments.size - 1) {
            val docParts = mutableListOf<String>()
            var i = treeIdx + 1
            while (i < segments.size && segments[i] != "document") {
                docParts.add(segments[i])
                i++
            }
            if (docParts.isNotEmpty()) return docParts.joinToString("/")
        }
        runCatching { return DocumentsContract.getDocumentId(uri) }
        return null
    }

    private fun saveToDisk(context: Context, list: ArrayList<Entry>) {
        context_filesDir_ref = context.filesDir
        val file = File(context.filesDir, CACHE_FILE)
        return try {
            val arr = JSONArray()
            for (e in list) {
                arr.put(JSONObject().apply {
                    put("name", e.name)
                    put("path", e.path)
                    put("uri", e.uri)
                })
            }
            val obj = JSONObject().apply {
                put("vaultUri", indexVaultUri ?: "")
                put("files", arr)
            }
            file.writeText(obj.toString())
        } catch (e: Exception) {
            Logger.e(TAG, "保存索引失败: ${e.message}")
        }
    }
}
