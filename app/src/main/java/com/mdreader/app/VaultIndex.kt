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

    /** 诊断：返回索引中属于指定目录的文件名样本（同时尝试解码路径匹配） */
    fun sampleByDir(dirName: String, maxSamples: Int = 5): List<String> {
        if (!ready) return emptyList()
        val prefix = "$dirName/"
        val result = mutableListOf<String>()
        synchronized(this) {
            for (e in allEntries) {
                val p = e.path.replace('\\', '/')
                val pDecoded = try { java.net.URLDecoder.decode(p, "UTF-8") } catch (_: Exception) { p }
                if (p.startsWith(prefix) || pDecoded.startsWith(prefix)) {
                    result.add("${e.name} (path=${e.path})")
                    if (result.size >= maxSamples) break
                }
            }
        }
        return result
    }

    /** 模糊查找：子串匹配（编码/解码双重比较），最后手段 */
    fun fuzzyFindByName(fileName: String): Entry? {
        if (!ready) return null
        val decoded = try { java.net.URLDecoder.decode(fileName, "UTF-8") } catch (_: Exception) { null }
        val searchTerms = listOfNotNull(fileName, decoded).distinct()
        synchronized(this) {
            for (e in allEntries) {
                val storedDecoded = try { java.net.URLDecoder.decode(e.name, "UTF-8") } catch (_: Exception) { e.name }
                for (term in searchTerms) {
                    if (storedDecoded.contains(term, ignoreCase = true)) return e
                    if (e.name.contains(term, ignoreCase = true)) return e
                }
            }
        }
        return null
    }

    /** 按文件名查找（大小写不敏感 + URL 解码兼容） */
    fun findByName(fileName: String): Entry? {
        if (!ready) return null
        val lower = fileName.lowercase()

        // 1. 精确匹配（map O(1) 查找）
        entries[lower]?.let { return it }

        // 2. 搜索词解码后再查 map
        val decoded = try { java.net.URLDecoder.decode(fileName, "UTF-8") } catch (_: Exception) { null }
        if (decoded != null && decoded != fileName) {
            entries[decoded.lowercase()]?.let { return it }
        }

        // 3. 线性扫描：同时尝试解码存储的名称
        for (e in allEntries) {
            if (e.name.equals(fileName, ignoreCase = true)) return e
            if (decoded != null && e.name.equals(decoded, ignoreCase = true)) return e
            // 关键：也尝试解码存储的文件名（SAF 有时返回 URL 编码的名称）
            try {
                val decodedStored = java.net.URLDecoder.decode(e.name, "UTF-8")
                if (decodedStored != e.name) {
                    if (decodedStored.equals(fileName, ignoreCase = true)) return e
                    if (decoded != null && decodedStored.equals(decoded, ignoreCase = true)) return e
                }
            } catch (_: Exception) {}
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

        // 按路径段匹配，避免 endsWith 误匹配部分目录名
        val targetSegments = normalized.split('/').map { it.lowercase() }
        for (e in allEntries) {
            if (e.name.equals(fileName, ignoreCase = true)) {
                val entrySegments = e.path.replace('\\', '/').trimStart('/').split('/').map { it.lowercase() }
                if (entrySegments.size >= targetSegments.size) {
                    val tail = entrySegments.subList(entrySegments.size - targetSegments.size, entrySegments.size)
                    if (tail == targetSegments) return e
                }
            }
        }
        return byName
    }

    /** 后台扫描库文件夹（增量：跳过已扫描目录，定期保存） */
    fun scanInBackground(context: Context, vaultUri: Uri, onDone: (() -> Unit)? = null) {
        if (scanning) {
            Logger.w(TAG, "scanInBackground: 已在扫描中，跳过")
            return
        }
        Thread {
            val startTime = System.currentTimeMillis()
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_LOWEST)
            scanning = true
            try {
                val uriStr = vaultUri.toString()
                val encoded = VaultSearch.ensureEncoded(vaultUri)
                // 关键：扫描前就设置 indexVaultUri，确保中途 saveToDisk 保存正确的 URI
                synchronized(this) { indexVaultUri = uriStr }
                Logger.i(TAG, "开始索引库文件夹... 当前已有 ${allEntries.size} 个文件, ${scannedDirs.size} 个已扫描目录")

                val root = DocumentFile.fromTreeUri(context, encoded)
                if (root != null) {
                    Logger.i(TAG, "根目录创建成功，开始迭代扫描...")
                    scanQueue(context, encoded, root, context)
                } else {
                    Logger.e(TAG, "根目录创建失败！URI: ${encoded.toString().take(80)}")
                }

                // 扫描完成，标记就绪
                try {
                    synchronized(this) {
                        indexVaultUri = uriStr
                        ready = true
                    }
                    // 最终保存（流式写入，防 OOM）
                    saveToDisk(context)
                    val elapsed = System.currentTimeMillis() - startTime
                    val timeStr = if (elapsed >= 60000) {
                        "${elapsed / 60000}分${(elapsed % 60000) / 1000}秒"
                    } else {
                        "${elapsed / 1000}秒"
                    }
                    val runtime = Runtime.getRuntime()
                    val usedMem = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024
                    val maxMem = runtime.maxMemory() / 1024 / 1024
                    Logger.i(TAG, "索引完成: ${allEntries.size} 个文件, ${scannedDirs.size} 个目录, 耗时 $timeStr, 内存 ${usedMem}MB/${maxMem}MB")
                } catch (e: Throwable) {
                    Logger.e(TAG, "索引完成阶段异常: ${e.message}")
                }
            } catch (e: Throwable) {
                Logger.e(TAG, "索引失败: ${e.message}, 当前已索引 ${allEntries.size} 个文件")
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

    /** 需要过滤的 Obsidian 系统目录 */
    private val SKIP_DIRS = setOf(".obsidian", ".trash")

    /** 扫描单个目录（非递归，由 scanQueue 调用） */
    private data class DirToScan(val dir: DocumentFile, val relPath: String)

    private fun scanQueue(context: Context, treeUri: Uri, root: DocumentFile, ctx: Context) {
        val queue = ArrayDeque<DirToScan>()
        queue.add(DirToScan(root, ""))
        var skippedCount = 0

        while (queue.isNotEmpty()) {
            val (dir, relPath) = queue.removeFirst()

            // 增量：跳过已扫描的目录
            if (relPath.isNotEmpty() && scannedDirs.contains(relPath)) {
                continue
            }

            val children = try {
                listDir(context, treeUri, dir)
            } catch (e: Exception) {
                Logger.w(TAG, "列目录失败: $relPath - ${e.message}")
                emptyList()
            }

            if (children.isEmpty()) {
                // 不标记为已扫描！listDir返回空可能是临时失败（权限/时序），
                // 标记后该目录永远不会被重试，导致其中的文件永远不被索引。
                // 下次扫描会重新尝试列出该目录。
                Logger.w(TAG, "目录返回空(将重试): $relPath")
                continue
            }

            // 记录子目录数用于诊断
            val dirCount = children.count { it.isDirectory }
            val fileCount = children.size - dirCount
            if (relPath.isEmpty() || dirCount > 0) {
                Logger.i(TAG, "扫描目录: ${relPath.ifEmpty { "(根)" }} - ${children.size} 项 ($fileCount 文件, $dirCount 子目录)")
            }

            for (file in children) {
                try {
                    val name = file.name ?: continue
                    if (file.isDirectory) {
                        // 过滤 Obsidian 系统目录
                        if (name in SKIP_DIRS) {
                            skippedCount++
                            continue
                        }
                        val childPath = if (relPath.isEmpty()) name else "$relPath/$name"
                        // 加入队列而非递归调用
                        queue.addLast(DirToScan(file, childPath))
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
                } catch (e: Exception) {
                    Logger.w(TAG, "扫描文件异常: $relPath - ${e.message}")
                }
            }

            // 目录扫描完毕，记录断点
            if (relPath.isNotEmpty()) {
                scannedDirs.add(relPath)
            }
        }
        if (skippedCount > 0) {
            Logger.i(TAG, "共过滤 $skippedCount 个系统目录")
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
            val currentEntries: List<Entry>
            val currentDirs: Set<String>
            val currentUri: String?
            synchronized(this) {
                currentEntries = ArrayList(allEntries)
                currentDirs = HashSet(scannedDirs)
                currentUri = indexVaultUri
            }
            // 流式写入 JSON，避免 19000+ 条目时 OOM
            FileWriter(file).use { writer ->
                val json = android.util.JsonWriter(writer)
                json.beginObject()
                json.name("vaultUri").value(currentUri ?: "")
                json.name("files").beginArray()
                for (e in currentEntries) {
                    json.beginObject()
                    json.name("name").value(e.name)
                    json.name("path").value(e.path)
                    json.name("uri").value(e.uri)
                    json.endObject()
                }
                json.endArray()
                json.name("scannedDirs").beginArray()
                for (d in currentDirs) {
                    json.value(d)
                }
                json.endArray()
                json.endObject()
                json.flush()
            }
        } catch (e: Exception) {
            Logger.e(TAG, "保存索引失败: ${e.message}")
        }
    }
}
