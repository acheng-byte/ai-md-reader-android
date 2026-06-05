package com.mdreader.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.IOException

/** 读取本地/分享而来的文件内容与显示名（兼容 content:// 与 file://）。 */
object FileUtils {

    @Throws(IOException::class)
    fun readText(context: Context, uri: Uri): String {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IOException("无法打开文件输入流")
        var text = bytes.toString(Charsets.UTF_8)
        // 去除 UTF-8 BOM
        if (text.isNotEmpty() && text[0] == '\uFEFF') text = text.substring(1)
        return text
    }

    fun displayName(context: Context, uri: Uri): String? {
        if (uri.scheme == "content") {
            runCatching {
                context.contentResolver.query(
                    uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
                )?.use { c ->
                    if (c.moveToFirst()) {
                        val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) return c.getString(idx)
                    }
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')
    }
}
