package com.mdreader.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * 透明覆盖层，用于在 WebView 上手绘标注。
 * 支持模式：free（自由画笔）、highlight（荧光笔半透明）、circle（不规则圈）、wavy（波浪线）。
 * 坐标归一化存储，适配不同屏幕尺寸。
 */
class AnnotationOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** 标注模式 */
    var drawMode: String = "free"
        set(value) { field = value; updatePaint(); invalidate() }

    /** 画笔颜色 */
    var drawColor: String = "#FF0000"
        set(value) { field = value; updatePaint(); invalidate() }

    /** 画笔宽度（dp） */
    var drawWidth: Float = 3f
        set(value) { field = value; updatePaint(); invalidate() }

    /** 是否拦截触摸事件（标注模式开启时为 true） */
    var annotationEnabled: Boolean = false
        set(value) {
            field = value
            isEnabled = value
            visibility = if (value) VISIBLE else GONE
        }

    /** 笔画列表（归一化坐标） */
    private val strokes = mutableListOf<StrokeData>()
    /** 当前正在绘制的笔画 */
    private var currentStroke: StrokeData? = null

    /** 笔画变化监听 */
    var onStrokesChanged: (() -> Unit)? = null

    private val paint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private data class StrokeData(
        val mode: String,
        val color: String,
        val width: Float,
        val path: Path,
        val points: MutableList<Pair<Float, Float>> = mutableListOf()
    )

    init {
        setWillNotDraw(false)
        annotationEnabled = false
    }

    private fun updatePaint() {
        paint.color = try {
            if (drawMode == "highlight") {
                // 荧光笔：半透明
                Color.parseColor(drawColor).let { c ->
                    Color.argb(80, Color.red(c), Color.green(c), Color.blue(c))
                }
            } else {
                Color.parseColor(drawColor)
            }
        } catch (e: Exception) {
            Color.RED
        }
        paint.strokeWidth = drawWidth * resources.displayMetrics.density
        if (drawMode == "highlight") {
            paint.strokeWidth = drawWidth * resources.displayMetrics.density * 4f
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!annotationEnabled) return false
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return false

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                updatePaint()
                currentStroke = StrokeData(
                    mode = drawMode,
                    color = drawColor,
                    width = drawWidth,
                    path = Path().apply { moveTo(event.x, event.y) }
                ).also {
                    it.points.add(Pair(event.x / w, event.y / h))
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                currentStroke?.let { s ->
                    s.path.lineTo(event.x, event.y)
                    s.points.add(Pair(event.x / w, event.y / h))
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                currentStroke?.let { s ->
                    strokes.add(s)
                    onStrokesChanged?.invoke()
                }
                currentStroke = null
                invalidate()
                return true
            }
        }
        return false
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // 绘制已完成的笔画
        strokes.forEach { s ->
            paint.color = if (s.mode == "highlight") {
                try {
                    Color.parseColor(s.color).let { c ->
                        Color.argb(80, Color.red(c), Color.green(c), Color.blue(c))
                    }
                } catch (e: Exception) { Color.argb(80, 255, 255, 0) }
            } else {
                try { Color.parseColor(s.color) } catch (e: Exception) { Color.RED }
            }
            paint.strokeWidth = if (s.mode == "highlight") {
                s.width * resources.displayMetrics.density * 4f
            } else {
                s.width * resources.displayMetrics.density
            }
            canvas.drawPath(s.path, paint)
        }
        // 绘制当前笔画
        currentStroke?.let { s ->
            canvas.drawPath(s.path, paint)
        }
    }

    /** 加载归一化笔画数据 */
    fun loadStrokes(strokeList: List<Annotations.Stroke>) {
        strokes.clear()
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) {
            // View 尚未布局，延迟加载
            post { loadStrokes(strokeList) }
            return
        }
        strokeList.forEach { s ->
            val path = Path()
            val points = mutableListOf<Pair<Float, Float>>()
            s.points.forEachIndexed { idx, (nx, ny) ->
                val px = nx * w
                val py = ny * h
                points.add(Pair(nx, ny))
                if (idx == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            if (points.isNotEmpty()) {
                strokes.add(StrokeData(s.mode, s.color, s.width, path, points))
            }
        }
        invalidate()
    }

    /** 导出为 Annotations.Stroke 列表 */
    fun exportStrokes(): List<Annotations.Stroke> {
        return strokes.map { s ->
            Annotations.Stroke(s.mode, s.color, s.width, s.points.toList())
        }
    }

    /** 清除所有笔画 */
    fun clearAll() {
        strokes.clear()
        currentStroke = null
        invalidate()
        onStrokesChanged?.invoke()
    }

    /** 撤销最后一笔 */
    fun undo() {
        if (strokes.isNotEmpty()) {
            strokes.removeAt(strokes.size - 1)
            invalidate()
            onStrokesChanged?.invoke()
        }
    }
}
