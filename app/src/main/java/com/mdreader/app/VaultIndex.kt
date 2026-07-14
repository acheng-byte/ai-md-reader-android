package com.mdreader.app

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 库文件索引：持久化缓存 + 增量扫描。
 *
 * 扫描结果存到内部存储 filesDir/vault_index.json。
 * 下次启动直接加载（瞬间就绪）。
 * 扫描过程中每 50 个文件保存一次，中断后下次从断点继续。
 */
object VaultIndex {

    private const val TAG = "VaultIndex"
    private const val CACHE_FILE = "vault_index.json"
    private const val SAVE_INTERVAL = 50  // 每 N 个文件保存一次

    data class Entry(
        val name: String,
        val path: String,
        val uri: String
    )

    private val entries = HashMap<String, Entry>()
    private val allEntries = ArrayList<Entry>()
    private val scannedDirs = HashSet<String>()   // 已扫描的目录相对路径
    private var indexVaultUri: String? = null
    @Volatile private var scanning = false
    @Volatile private var ready = false
    private var fileCountSinceSave = 0

    fun isScanning(): Boolean = scanning
    fun isReady(): Boolean = ready
    fun entryCount(): Int = synchronized(this) { allEntries.size }

    /** 按文件名查找（大小写不敏感 + URL 解码兼容） */
    fun findByName(fileName: String): Entry? {
        if (!ready) return null
        val lower = fileName.lowercase()

        entries[lower]?.let { return it }

        val decoded = try { java.net.URLDecoder.decode(fileName, "UTF-8") } catch (_: Exception) { null }
        if (decoded != null && decoded != fileName) {
            entries[decoded.lowercase()]?.let { return it }
        }

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

        val byName = findByName(fileName) ?: return null
        if (!normalized.contains('/')) return byName

        for (e in allEntries) {
            if (e.name.equals(fileName, ignoreCase = true)) {
                val entryPath = e.path.replace('\\', '/').trimStart('/')
                if (entryPath.endsWith(normalized, ignoreCase = true) ||
                    entryPath.equals(normalized, ignoreCase = true)) {
                    return e
                }
            }
        }
        return byName
    }

    /** 后台扫描库文件夹（增量：跳过已扫描目录，定期保存） */
    fun scanInBackground(context: Context, vaultUri: Uri, onDone: (() -> Unit)? = null) {
        if (scanning) return
        Thread {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_LOWEST)
            scanning = true
            try {
                val uriStr = vaultUri.toString()
                val encoded = VaultSearch.ensureEncoded(vaultUri)
                Logger.i(TAG, "开始索引库文件夹...")

                val root = DocumentFile.fromTreeUri(context, encoded)
                if (root != null) {
                    scanDir(context, encoded, root, "", context)
                }

                // 扫描完成，标记就绪
                synchronized(this) {
                    indexVaultUri = uriStr
                    ready = true
                }
                // 最终保存
                saveToDisk(context)
                Logger.i(TAG, "索引完成: ${allEntries.size} 个文件")
            } catch (e: Exception) {
                Logger.e(TAG, "索引失败: ${e.message}")
            } finally {
                scanning = false
            }
            onDone?.invoke()
        }.start()
    }

    /** 从磁盘加载缓存索引（含增量扫描断点） */
    fun loadFromDisk(context: Context, vaultUri: Uri): Boolean {
        val uriStr = vaultUri.toString()
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

            // 加载已扫描目录（增量断点）
            val dirsArr = obj.optJSONArray("scannedDirs")
            val dirs = HashSet<String>()
            if (dirsArr != null) {
                for (i in 0 until dirsArr.length()) {
                    dirs.add(dirsArr.getString(i))
                }
            }

            synchronized(this) {
                allEntries.clear()
                allEntries.addAll(list)
                entries.clear()
                entries.putAll(map)
                scannedDirs.clear()
                scannedDirs.addAll(dirs)
                indexVaultUri = uriStr
                ready = true
            }
            context_filesDir_ref = context.filesDir
            Logger.i(TAG, "从磁盘加载索引: ${list.size} 个文件, ${dirs.size} 个已扫描目录")
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
            scannedDirs.clear()
            indexVaultUri = null
            ready = false
            fileCountSinceSave = 0
        }
        runCatching { File(context_filesDir_ref, CACHE_FILE).delete() }
        Logger.i(TAG, "索引已清除")
    }

    // ---- 内部方法 ----

    private var context_filesDir_ref: File? = null

    private fun scanDir(context: Context, treeUri: Uri, dir: DocumentFile, relPath: String,
                        ctx: Context) {
        // 增量：跳过已扫描的目录
        if (relPath.isNotEmpty() && scannedDirs.contains(relPath)) return

        val children = listDir(context, treeUri, dir)
        if (children.isEmpty()) {
            if (relPath.isNotEmpty()) scannedDirs.add(relPath)
            return
        }

        for (file in children) {
            val name = file.name ?: continue
            if (file.isDirectory) {
                val childPath = if (relPath.isEmpty()) name else "$relPath/$name"
                scanDir(context, treeUri, file, childPath, ctx)
            } else {
                val entry = Entry(
                    name = name,
                    path = if (relPath.isEmpty()) name else "$relPath/$name",
                    uri = file.uri.toString()
                )
                synchronized(this) {
                    allEntries.add(entry)
                    val lower = name.lowercase()
                    if (entries[lower] == null) entries[lower] = entry
                }
                fileCountSinceSave++
                // 定期保存（增量断点）
                if (fileCountSinceSave >= SAVE_INTERVAL) {
                    fileCountSinceSave = 0
                    val currentCount = allEntries.size
                    Logger.i(TAG, "增量扫描进度: 已索引 $currentCount 个文件，保存断点...")
                    saveToDisk(ctx)
                    Logger.i(TAG, "断点保存完毕，当前已索引 $currentCount 个文件")
                }
            }
        }

        // 目录扫描完毕，记录断点
        if (relPath.isNotEmpty()) {
            scannedDirs.add(relPath)
        }
    }

    private fun listDir(context: Context, treeUri: Uri, dir: DocumentFile): List<DocumentFile> {
        val dfList = runCatching { dir.listFiles() }.getOrNull()
        if (!dfList.isNullOrEmpty()) return dfList.toList()

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

    private fun saveToDisk(context: Context) {
        context_filesDir_ref = context.filesDir
        val file = File(context.filesDir, CACHE_FILE)
        try {
            val arr = JSONArray()
            val currentEntries: List<Entry>
            val currentDirs: Set<String>
            synchronized(this) {
                currentEntries = ArrayList(allEntries)
                currentDirs = HashSet(scannedDirs)
            }
            for (e in currentEntries) {
                arr.put(JSONObject().apply {
                    put("name", e.name)
                    put("path", e.path)
                    put("uri", e.uri)
                })
            }
            val dirsArr = JSONArray()
            for (d in currentDirs) {
                dirsArr.put(d)
            }
            val obj = JSONObject().apply {
                put("vaultUri", indexVaultUri ?: "")
                put("files", arr)
                put("scannedDirs", dirsArr)
            }
            file.writeText(obj.toString())
        } catch (e: Exception) {
            Logger.e(TAG, "保存索引失败: ${e.message}")
        }
    }
}
