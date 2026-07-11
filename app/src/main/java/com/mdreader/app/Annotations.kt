package com.mdreader.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 阅读标注持久化：以文档 URI 为 key，存储手绘路径（归一化坐标）。
 * 每条标注包含：颜色、笔宽、模式（free/highlight/circle/wavy）、归一化坐标点列表。
 */
class Annotations(context: Context) {

    private val dir = File(context.filesDir, "annotations").also { it.mkdirs() }

    data class Stroke(
        val mode: String,      // "free" | "highlight" | "circle" | "wavy"
        val color: String,     // "#RRGGBB"
        val width: Float,      // 笔宽（dp）
        val points: List<Pair<Float, Float>>  // 归一化坐标 (0~1)
    )

    fun load(docUri: String): List<Stroke> {
        val file = fileFor(docUri)
        if (!file.exists()) return emptyList()
        return try {
            val json = JSONArray(file.readText())
            val strokes = mutableListOf<Stroke>()
            for (i in 0 until json.length()) {
                val obj = json.getJSONObject(i)
                val pts = obj.getJSONArray("points")
                val pointList = mutableListOf<Pair<Float, Float>>()
                for (j in 0 until pts.length() step 2) {
                    pointList.add(Pair(pts.getDouble(j).toFloat(), pts.getDouble(j + 1).toFloat()))
                }
                strokes.add(Stroke(
                    mode = obj.optString("mode", "free"),
                    color = obj.optString("color", "#FF0000"),
                    width = obj.optDouble("width", 3.0).toFloat(),
                    points = pointList
                ))
            }
            strokes
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun save(docUri: String, strokes: List<Stroke>) {
        val file = fileFor(docUri)
        if (strokes.isEmpty()) {
            file.delete()
            return
        }
        val json = JSONArray()
        strokes.forEach { s ->
            val pts = JSONArray()
            s.points.forEach { p ->
                pts.put(p.first.toDouble())
                pts.put(p.second.toDouble())
            }
            json.put(JSONObject().apply {
                put("mode", s.mode)
                put("color", s.color)
                put("width", s.width.toDouble())
                put("points", pts)
            })
        }
        file.writeText(json.toString())
    }

    fun clear(docUri: String) {
        fileFor(docUri).delete()
    }

    private fun fileFor(docUri: String): File {
        val safeName = docUri.hashCode().toString()
        return File(dir, "$safeName.json")
    }
}
