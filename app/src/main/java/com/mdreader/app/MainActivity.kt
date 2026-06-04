package com.mdreader.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.IntentCompat
import androidx.webkit.WebViewAssetLoader
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.webkit.WebViewClientCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.mdreader.app.databinding.ActivityMainBinding
import com.mdreader.app.databinding.SheetHistoryBinding
import com.mdreader.app.databinding.SheetSettingsBinding
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity(), MarkdownBridge.Provider {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs
    private lateinit var history: History
    private lateinit var webView: WebView

    @Volatile private var currentMarkdown: String = ""
    @Volatile private var currentMode: String = Prefs.DEFAULT_MODE
    private var currentTitle: String = ""
    private var pageReady: Boolean = false

    // 系统文件选择器（SAF），无需任何存储权限
    private val openPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) {
                runCatching {
                    contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
                loadDocument(uri)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        history = History(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        webView = binding.webview
        currentMode = prefs.viewMode

        setupWebView()

        val handled = handleIntent(intent)
        if (!handled) {
            currentMarkdown = WELCOME_MD
            currentTitle = getString(R.string.app_name)
            supportActionBar?.title = currentTitle
        }
        webView.setBackgroundColor(bgColor())
        webView.loadUrl(VIEWER_URL)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun setupWebView() {
        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView.webViewClient = object : WebViewClientCompat() {
            override fun shouldInterceptRequest(
                view: WebView, request: WebResourceRequest
            ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)

            override fun onPageFinished(view: WebView, url: String) {
                pageReady = true
                // 页面就绪后统一套用一次内容/模式/样式，确保与定时无关地呈现正确状态
                renderCurrent()
            }

            override fun shouldOverrideUrlLoading(
                view: WebView, request: WebResourceRequest
            ): Boolean {
                val url = request.url
                if (url.host == ASSET_HOST) return false // 站内资源放行
                // 外部链接交给系统（浏览器/对应应用）打开
                return runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, url))
                    true
                }.getOrDefault(true)
            }
        }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            textZoom = 100
        }
        webView.addJavascriptInterface(MarkdownBridge(this), "Android")
    }

    private fun handleIntent(intent: Intent?): Boolean {
        if (intent == null) return false
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND ->
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        }
        return if (uri != null) {
            // 尽力持久化读权限，便于之后从历史记录重新打开（仅当来源授权可持久化时生效）
            runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            loadDocument(uri)
            true
        } else false
    }

    private fun loadDocument(uri: Uri) {
        Thread {
            val result = runCatching {
                val name = FileUtils.displayName(this, uri) ?: getString(R.string.app_name)
                val text = FileUtils.readText(this, uri)
                name to text
            }
            runOnUiThread {
                result.onSuccess { (name, text) ->
                    currentMarkdown = text
                    currentTitle = name
                    supportActionBar?.title = name
                    history.add(uri.toString(), name, System.currentTimeMillis())
                    renderCurrent()
                }.onFailure { e ->
                    Toast.makeText(
                        this, getString(R.string.open_failed, e.message ?: ""), Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    /** 把当前内容/模式/样式推给已就绪的页面；未就绪时由 onPageFinished 统一处理。 */
    private fun renderCurrent() {
        if (!pageReady) return
        js("window.appRender && window.appRender()")
        js("window.appSetMode && window.appSetMode('$currentMode')")
        applySettingsToWeb()
    }

    private fun applySettingsToWeb() {
        js("window.appApplySettings && window.appApplySettings(${prefs.settingsJson(this)})")
        webView.setBackgroundColor(bgColor())
    }

    private fun js(code: String) {
        if (pageReady) webView.evaluateJavascript(code, null)
    }

    private fun bgColor(): Int =
        if (prefs.isDark(this)) 0xFF0D1117.toInt() else 0xFFFFFFFF.toInt()

    // ---- 菜单 ----

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_toggle)?.let { item ->
            if (currentMode == "preview") {
                item.setTitle(R.string.action_view_code)
                item.setIcon(R.drawable.ic_code)
            } else {
                item.setTitle(R.string.action_view_preview)
                item.setIcon(R.drawable.ic_preview)
            }
        }
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_open -> {
            openPicker.launch(arrayOf("*/*"))
            true
        }
        R.id.action_toc -> {
            js("window.appToggleToc && window.appToggleToc()")
            true
        }
        R.id.action_toggle -> {
            toggleMode()
            true
        }
        R.id.action_settings -> {
            showSettings()
            true
        }
        R.id.action_history -> {
            showHistory()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun toggleMode() {
        currentMode = if (currentMode == "preview") "code" else "preview"
        prefs.viewMode = currentMode
        js("window.appSetMode && window.appSetMode('$currentMode')")
        invalidateOptionsMenu()
    }

    // ---- 显示设置底部面板 ----

    private fun showSettings() {
        val sheet = SheetSettingsBinding.inflate(layoutInflater)

        sheet.sliderFont.value = snap(prefs.fontSize, Prefs.FONT_MIN, Prefs.FONT_MAX, 1f)
        sheet.sliderLine.value = snap(prefs.lineHeight, Prefs.LINE_MIN, Prefs.LINE_MAX, 0.1f)
        sheet.sliderPara.value = snap(prefs.paraGap, Prefs.PARA_MIN, Prefs.PARA_MAX, 0.1f)
        updateLabels(sheet)

        sheet.toggleTheme.check(
            when (prefs.themeMode) {
                1 -> R.id.btn_theme_light
                2 -> R.id.btn_theme_dark
                else -> R.id.btn_theme_system
            }
        )

        sheet.sliderFont.addOnChangeListener { _, value, _ ->
            prefs.fontSize = value; updateLabels(sheet); applySettingsToWeb()
        }
        sheet.sliderLine.addOnChangeListener { _, value, _ ->
            prefs.lineHeight = value; updateLabels(sheet); applySettingsToWeb()
        }
        sheet.sliderPara.addOnChangeListener { _, value, _ ->
            prefs.paraGap = value; updateLabels(sheet); applySettingsToWeb()
        }
        sheet.toggleTheme.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                prefs.themeMode = when (checkedId) {
                    R.id.btn_theme_light -> 1
                    R.id.btn_theme_dark -> 2
                    else -> 0
                }
                applySettingsToWeb()
            }
        }
        sheet.btnReset.setOnClickListener {
            prefs.fontSize = Prefs.DEFAULT_FONT
            prefs.lineHeight = Prefs.DEFAULT_LINE
            prefs.paraGap = Prefs.DEFAULT_PARA
            sheet.sliderFont.value = Prefs.DEFAULT_FONT
            sheet.sliderLine.value = Prefs.DEFAULT_LINE
            sheet.sliderPara.value = Prefs.DEFAULT_PARA
            updateLabels(sheet)
            applySettingsToWeb()
        }

        BottomSheetDialog(this).apply {
            setContentView(sheet.root)
            show()
        }
    }

    private fun updateLabels(sheet: SheetSettingsBinding) {
        sheet.valFont.text = getString(R.string.val_font, prefs.fontSize.roundToInt())
        sheet.valLine.text = String.format("%.1f", prefs.lineHeight)
        sheet.valPara.text = String.format("%.1f", prefs.paraGap)
    }

    private fun snap(v: Float, min: Float, max: Float, step: Float): Float {
        val c = v.coerceIn(min, max)
        val steps = ((c - min) / step).roundToInt()
        val r = (((min + steps * step) * 100).roundToInt() / 100f)
        return r.coerceIn(min, max)
    }

    // ---- 打开历史底部面板 ----

    private fun showHistory() {
        val sheet = SheetHistoryBinding.inflate(layoutInflater)
        val entries = history.all()
        val dialog = BottomSheetDialog(this)
        dialog.setContentView(sheet.root)

        if (entries.isEmpty()) {
            sheet.historyEmpty.visibility = View.VISIBLE
            sheet.historyList.visibility = View.GONE
        } else {
            sheet.historyEmpty.visibility = View.GONE
            sheet.historyList.visibility = View.VISIBLE
            lateinit var adapter: HistoryAdapter
            adapter = HistoryAdapter(entries) { entry ->
                if (adapter.isUnavailable(entry.uri)) {
                    Toast.makeText(this, R.string.history_unavailable, Toast.LENGTH_SHORT).show()
                } else {
                    dialog.dismiss()
                    loadDocument(Uri.parse(entry.uri))
                }
            }
            sheet.historyList.layoutManager = LinearLayoutManager(this)
            sheet.historyList.adapter = adapter
            // 后台检测每条记录是否仍可访问（被删除 / 权限丢失 -> 标注「已删除」）
            Thread {
                val map = HashMap<String, Boolean>(entries.size)
                entries.forEach { map[it.uri] = isAccessible(Uri.parse(it.uri)) }
                runOnUiThread { adapter.setAvailability(map) }
            }.start()
        }

        sheet.btnClearHistory.setOnClickListener {
            history.clear()
            dialog.dismiss()
            Toast.makeText(this, R.string.history_cleared, Toast.LENGTH_SHORT).show()
        }

        dialog.show()
    }

    private fun isAccessible(uri: Uri): Boolean = try {
        when (uri.scheme) {
            "file" -> uri.path?.let { java.io.File(it).exists() } ?: false
            else -> contentResolver.openInputStream(uri)?.use { true } ?: false
        }
    } catch (e: Exception) {
        false
    }

    override fun onDestroy() {
        // 释放 WebView，避免泄漏
        binding.webview.apply {
            (parent as? android.view.ViewGroup)?.removeView(this)
            destroy()
        }
        super.onDestroy()
    }

    // ---- MarkdownBridge.Provider（运行在 binder 线程）----
    override fun markdown(): String = currentMarkdown
    override fun settingsJson(): String = prefs.settingsJson(this)
    override fun initialMode(): String = currentMode
    override fun onModeChanged(mode: String) {
        // JS 自行切换了模式（如目录跳转时从源码切回预览），同步原生状态与菜单图标
        runOnUiThread {
            if (mode == "preview" || mode == "code") {
                currentMode = mode
                prefs.viewMode = mode
                invalidateOptionsMenu()
            }
        }
    }

    companion object {
        private const val ASSET_HOST = "appassets.androidplatform.net"
        private const val VIEWER_URL = "https://$ASSET_HOST/assets/viewer.html"

        private val WELCOME_MD = """
# 👋 欢迎使用 MD 阅读器

这是一个本地 **Markdown 阅读器**。下面用一段示例展示渲染效果，你可以立刻试试顶部的几个按钮。

## 怎么用

- 点击右上角 **📂 打开**，选择手机里的 `.md` 文件
- 点击 **源码 / 预览** 在两种呈现方式间切换
- 点击 **显示设置**，拖动滑块调节 **字号 / 行间距 / 段间距**，并可切换浅色/深色
- 在微信里长按 `.md` 文件 →「用其他应用打开」→ 选择「MD阅读器」

## 支持的语法示例

### 列表与引用

1. 有序列表项一
2. 有序列表项二
   - 嵌套无序项

> 这是一段引用文字（blockquote）。

### 表格

| 功能 | 是否支持 |
| --- | :---: |
| 标题 / 列表 | ✅ |
| 表格 | ✅ |
| 代码高亮 | ✅ |

### 代码

行内代码 `val x = 1`，以及代码块：

```kotlin
fun main() {
    println("Hello, Markdown!")
}
```

---

打开一个文件开始阅读吧 📖
        """.trimIndent()
    }
}
