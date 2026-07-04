package com.mdreader.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object UpdateChecker {
    private const val RELEASES_API =
        "https://api.github.com/repos/acheng-byte/ai-md-reader-android/releases/latest"
    const val RELEASES_PAGE =
        "https://github.com/acheng-byte/ai-md-reader-android/releases/latest"

    data class ReleaseInfo(val tagName: String, val htmlUrl: String)

    fun checkLatest(): ReleaseInfo? = runCatching {
        val conn = URL(RELEASES_API).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
        conn.connectTimeout = 8_000
        conn.readTimeout = 8_000
        if (conn.responseCode == 200) {
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            ReleaseInfo(
                tagName = json.optString("tag_name", ""),
                htmlUrl = json.optString("html_url", RELEASES_PAGE)
            )
        } else null
    }.getOrNull()

    fun isNewer(tagName: String, current: String): Boolean {
        val remote = tagName.trimStart('v', 'V')
        return compareVersions(remote, current) > 0
    }

    private fun compareVersions(a: String, b: String): Int {
        val pa = a.split(".").map { it.toIntOrNull() ?: 0 }
        val pb = b.split(".").map { it.toIntOrNull() ?: 0 }
        val len = maxOf(pa.size, pb.size)
        for (i in 0 until len) {
            val diff = (pa.getOrElse(i) { 0 }) - (pb.getOrElse(i) { 0 })
            if (diff != 0) return diff
        }
        return 0
    }
}
