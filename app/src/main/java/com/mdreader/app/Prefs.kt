package com.mdreader.app

import android.content.Context
import android.content.res.Configuration
import org.json.JSONObject
import kotlin.math.roundToInt

/** SharedPreferences 封装：字号、行间距、段间距、主题、视图模式，以及 Vault 路径等扩展设置。 */
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

    /** Vault 文件夹的持久化 content:// URI（null = 未设置） */
    var vaultUri: String?
        get() = sp.getString(KEY_VAULT_URI, null)
        set(v) { sp.edit().putString(KEY_VAULT_URI, v).apply() }

    /** 最后一次检查更新的时间戳（ms），避免每次启动都请求 */
    var lastUpdateCheck: Long
        get() = sp.getLong(KEY_LAST_UPDATE_CHECK, 0L)
        set(v) { sp.edit().putLong(KEY_LAST_UPDATE_CHECK, v).apply() }

    /** 上次关闭时正在阅读的文档 URI（null = 从未打开过文件） */
    var lastDocUri: String?
        get() = sp.getString(KEY_LAST_DOC_URI, null)
        set(v) { sp.edit().putString(KEY_LAST_DOC_URI, v).apply() }

    /** 上次关闭时正在阅读的文档显示名 */
    var lastDocName: String
        get() = sp.getString(KEY_LAST_DOC_NAME, "") ?: ""
        set(v) { sp.edit().putString(KEY_LAST_DOC_NAME, v).apply() }

    /** 是否自动解析并显示 YAML frontmatter 元数据表格 */
    var showFrontmatter: Boolean
        get() = sp.getBoolean(KEY_SHOW_FRONTMATTER, true)
        set(v) { sp.edit().putBoolean(KEY_SHOW_FRONTMATTER, v).apply() }

    /** 是否显示引用块样式（blockquote / citation）*/
    var showCitations: Boolean
        get() = sp.getBoolean(KEY_SHOW_CITATIONS, true)
        set(v) { sp.edit().putBoolean(KEY_SHOW_CITATIONS, v).apply() }

    /** 护眼模式：暖色背景减轻视觉疲劳 */
    var eyeProtection: Boolean
        get() = sp.getBoolean(KEY_EYE_PROTECTION, false)
        set(v) { sp.edit().putBoolean(KEY_EYE_PROTECTION, v).apply() }

    /** 字体族：default / serif / mono */
    var fontFamily: String
        get() = sp.getString(KEY_FONT_FAMILY, "default") ?: "default"
        set(v) { sp.edit().putString(KEY_FONT_FAMILY, v).apply() }

    fun isDark(context: Context): Boolean = when (themeMode) {
        1 -> false
        2 -> true
        else -> {
            val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            night == Configuration.UI_MODE_NIGHT_YES
        }
    }

    fun settingsJson(context: Context): String = JSONObject().apply {
        put("fontSize", fontSize.roundToInt())
        put("lineHeight", round1(lineHeight))
        put("paraGap", round1(paraGap))
        put("dark", isDark(context))
        put("eyeProtection", eyeProtection)
        put("fontFamily", fontFamily)
        put("showFrontmatter", showFrontmatter)
        put("showCitations", showCitations)
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
        private const val KEY_VAULT_URI = "vault_uri"
        private const val KEY_LAST_UPDATE_CHECK = "last_update_check"
        private const val KEY_SHOW_FRONTMATTER = "show_frontmatter"
        private const val KEY_SHOW_CITATIONS = "show_citations"
        private const val KEY_EYE_PROTECTION = "eye_protection"
        private const val KEY_FONT_FAMILY = "font_family"
        private const val KEY_LAST_DOC_URI = "last_doc_uri"
        private const val KEY_LAST_DOC_NAME = "last_doc_name"
    }
}
