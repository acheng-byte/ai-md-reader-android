package com.mdreader.app

import android.content.Context
import android.content.res.Configuration
import org.json.JSONObject
import kotlin.math.roundToInt

/** SharedPreferences 封装：字号、行间距、段间距、主题、视图模式，并生成给 WebView 的设置 JSON。 */
class Prefs(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("mdreader_prefs", Context.MODE_PRIVATE)

    var fontSize: Float
        get() = sp.getFloat(KEY_FONT, DEFAULT_FONT)
        set(v) { sp.edit().putFloat(KEY_FONT, v).apply() }

    var lineHeight: Float
        get() = sp.getFloat(KEY_LINE, DEFAULT_LINE)
        set(v) { sp.edit().putFloat(KEY_LINE, v).apply() }

    var paraGap: Float
        get() = sp.getFloat(KEY_PARA, DEFAULT_PARA)
        set(v) { sp.edit().putFloat(KEY_PARA, v).apply() }

    /** 0=跟随系统 1=浅色 2=深色 */
    var themeMode: Int
        get() = sp.getInt(KEY_THEME, DEFAULT_THEME)
        set(v) { sp.edit().putInt(KEY_THEME, v).apply() }

    /** "preview" 或 "code" */
    var viewMode: String
        get() = sp.getString(KEY_MODE, DEFAULT_MODE) ?: DEFAULT_MODE
        set(v) { sp.edit().putString(KEY_MODE, v).apply() }

    fun isDark(context: Context): Boolean = when (themeMode) {
        1 -> false
        2 -> true
        else -> {
            val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            night == Configuration.UI_MODE_NIGHT_YES
        }
    }

    /** 供 evaluateJavascript / JS 桥使用，数值已规整避免浮点噪声。 */
    fun settingsJson(context: Context): String = JSONObject().apply {
        put("fontSize", fontSize.roundToInt())
        put("lineHeight", round1(lineHeight))
        put("paraGap", round1(paraGap))
        put("dark", isDark(context))
    }.toString()

    private fun round1(v: Float): Double = (v * 10).roundToInt() / 10.0

    companion object {
        const val DEFAULT_FONT = 16f
        const val DEFAULT_LINE = 1.7f
        const val DEFAULT_PARA = 1.0f
        const val DEFAULT_THEME = 0
        const val DEFAULT_MODE = "preview"

        const val FONT_MIN = 12f
        const val FONT_MAX = 30f
        const val LINE_MIN = 1.0f
        const val LINE_MAX = 2.4f
        const val PARA_MIN = 0.0f
        const val PARA_MAX = 2.0f

        private const val KEY_FONT = "font_size"
        private const val KEY_LINE = "line_height"
        private const val KEY_PARA = "para_gap"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_MODE = "view_mode"
    }
}
