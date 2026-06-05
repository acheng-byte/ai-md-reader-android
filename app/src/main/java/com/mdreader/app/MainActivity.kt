package com.mdreader.app

import android.content.ClipData
import android.content.ClipboardManager
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
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.mdreader.app.databinding.ActivityMainBinding
import com.mdreader.app.databinding.SheetFavoritesBinding
import com.mdreader.app.databinding.SheetHistoryBinding
import com.mdreader.app.databinding.SheetSettingsBinding
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity(), MarkdownBridge.Provider {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs
    private lateinit var history: History
    private lateinit var favorites: Favorites
    private lateinit var webView: WebView

    @Volatile private var currentMarkdown: String = ""
    @Volatile private var currentMode: String = Prefs.DEFAULT_MODE
    private var currentTitle: String = ""
    private var currentUri: String? = null   // 当前文档身份 URI（欢迎页为 null）
    private var pageReady: Boolean = false

    // 系统文件选择器（SAF），无需任何存储权限；选择后校验仅允许 .md / .markdown
    private val openPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) {
                val name = FileUtils.displayName(this, uri)
                if (!isMarkdownAllowed(name)) {
                    Toast.makeText(this, R.string.only_md, Toast.LENGTH_LONG).show()
                } else {
                    runCatching {
                        contentResolver.takePersistableUriPermission(
                            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    }
                    loadDocument(uri)
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        history = History(this)
        favorites = Favorites(this)
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
                renderCurrent()
            }

            override fun shouldOverrideUrlLoading(
                view: WebView, request: WebResourceRequest
            ): Boolean {
                val url = request.url
                if (url.host == ASSET_HOST) return false
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
            // 尽力持久化读权限，便于之后从历史重新打开（仅当来源授权可持久化时生效）
            runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            loadDocument(uri)
            true
        } else false
    }

    /**
     * @param readUri 实际读取内容的 URI（收藏夹打开时为本地副本）
     * @param displayNameOverride 标题覆盖（收藏/历史已知名称时传入）
     * @param identityUri 文档身份（用于历史/收藏去重；收藏夹打开时为原始 URI）
     */
    private fun loadDocument(
        readUri: Uri,
        displayNameOverride: String? = null,
        identityUri: String = readUri.toString()
    ) {
        Thread {
            val result = runCatching {
                val name = displayNameOverride
                    ?: FileUtils.displayName(this, readUri)
                    ?: getString(R.string.app_name)
                val text = FileUtils.readText(this, readUri)
                name to text
            }
            runOnUiThread {
                result.onSuccess { (name, text) ->
                    currentMarkdown = text
                    currentTitle = name
                    currentUri = identityUri
                    supportActionBar?.title = name
                    history.add(identityUri, name, System.currentTimeMillis())
                    renderCurrent()
                    invalidateOptionsMenu()   // 刷新收藏星标状态
                }.onFailure { e ->
                    Toast.makeText(
                        this, getString(R.string.open_failed, e.message ?: ""), Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

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

    private fun isMarkdownAllowed(name: String?): Boolean {
        if (name == null) return true // 无法获知名称时不拦截
        val n = name.lowercase()
        return n.endsWith(".md") || n.endsWith(".markdown")
    }

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
        menu.findItem(R.id.action_favorite)?.let { item ->
            val fav = currentUri?.let { favorites.isFavorite(it) } == true
            item.setIcon(if (fav) R.drawable.ic_star else R.drawable.ic_star_border)
            item.setTitle(if (fav) R.string.action_unfavorite else R.string.action_favorite)
        }
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_share -> {
            shareCurrentDocument()
            true
        }
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
        R.id.action_favorite -> {
            toggleFavorite()
            true
        }
        R.id.action_favorites -> {
            showFavorites()
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

    // ---- 转发 / 分享 ----

    /**
     * 把当前正文以 .md 文件形式通过系统分享面板转发（微信/QQ 等）。
     * 内容写入缓存目录，经 FileProvider 暴露为 content:// 并授予临时读权限；
     * 选择微信后由微信自身的“发送给朋友”选择联系人（系统能力，无法绕过其选人界面）。
     */
    private fun shareCurrentDocument() {
        if (currentUri == null || currentMarkdown.isEmpty()) {
            Toast.makeText(this, R.string.share_empty, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val dir = File(cacheDir, "shared").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }   // 清理旧的临时文件，目录内只保留本次
            val name = shareFileName(currentTitle)
            val file = File(dir, name)
            file.writeText(currentMarkdown, Charsets.UTF_8)

            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "*/*"                       // .md 无标准 MIME，用 */* 让微信按文件接收
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, name)
                putExtra(Intent.EXTRA_TITLE, name)
                clipData = ClipData.newUri(contentResolver, name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, getString(R.string.share_chooser_title)))
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.share_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
        }
    }

    /** 由文档标题生成合法的 .md 文件名。 */
    private fun shareFileName(title: String): String {
        var base = title.trim().ifEmpty { "document" }
        base = base.replace(Regex("[\\\\/:*?\"<>|\\r\\n]"), "_")
        if (!base.endsWith(".md", true) && !base.endsWith(".markdown", true)) base += ".md"
        return base
    }

    // ---- 收藏 ----

    private fun toggleFavorite() {
        val id = currentUri
        if (id == null) {
            Toast.makeText(this, R.string.fav_need_doc, Toast.LENGTH_SHORT).show()
            return
        }
        if (favorites.isFavorite(id)) {
            favorites.remove(id)
            Toast.makeText(this, R.string.fav_removed, Toast.LENGTH_SHORT).show()
        } else {
            val ok = favorites.add(id, currentTitle, currentMarkdown.toByteArray(Charsets.UTF_8)) != null
            Toast.makeText(this, if (ok) R.string.fav_added else R.string.fav_failed, Toast.LENGTH_SHORT).show()
        }
        invalidateOptionsMenu()
    }

    private fun showFavorites() {
        val sheet = SheetFavoritesBinding.inflate(layoutInflater)
        val favs = favorites.all().toMutableList()
        val dialog = BottomSheetDialog(this)
        dialog.setContentView(sheet.root)

        fun refreshEmpty() {
            val empty = favs.isEmpty()
            sheet.favEmpty.visibility = if (empty) View.VISIBLE else View.GONE
            sheet.favList.visibility = if (empty) View.GONE else View.VISIBLE
        }
        refreshEmpty()

        val adapter = FavoritesAdapter(
            favs,
            onOpen = { fav ->
                dialog.dismiss()
                loadDocument(Uri.fromFile(favorites.fileOf(fav)), fav.name, fav.uri)
            },
            onRemove = { fav ->
                favorites.remove(fav.uri)
                if (currentUri == fav.uri) invalidateOptionsMenu()
                Toast.makeText(this, R.string.fav_removed, Toast.LENGTH_SHORT).show()
                refreshEmpty()
            }
        )
        sheet.favList.layoutManager = LinearLayoutManager(this)
        sheet.favList.adapter = adapter
        dialog.show()
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
        sheet.valLine.text = String.format(Locale.ROOT, "%.1f", prefs.lineHeight)
        sheet.valPara.text = String.format(Locale.ROOT, "%.1f", prefs.paraGap)
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
            val favSet = favorites.all().map { it.uri }.toHashSet()
            lateinit var adapter: HistoryAdapter
            adapter = HistoryAdapter(entries, favSet) { entry ->
                onHistoryEntryClicked(entry, adapter.statusOf(entry.uri), dialog)
            }
            sheet.historyList.layoutManager = LinearLayoutManager(this)
            sheet.historyList.adapter = adapter
            // 后台逐条检测状态（可用 / 授权过期 / 已删除）
            Thread {
                val map = HashMap<String, DocStatus>(entries.size)
                entries.forEach { map[it.uri] = statusOf(it.uri) }
                runOnUiThread { adapter.setStatuses(map) }
            }.start()
        }

        sheet.btnClearHistory.setOnClickListener {
            history.clear()
            dialog.dismiss()
            Toast.makeText(this, R.string.history_cleared, Toast.LENGTH_SHORT).show()
        }

        dialog.show()
    }

    private fun onHistoryEntryClicked(entry: History.Entry, status: DocStatus, dialog: BottomSheetDialog) {
        if (status == DocStatus.AVAILABLE) {
            dialog.dismiss()
            loadDocument(Uri.parse(entry.uri), entry.name, entry.uri)
            return
        }
        // 不可用：若已收藏，则从收藏夹的本地副本打开；否则按状态友好提示
        val fav = favorites.find(entry.uri)
        if (fav != null) {
            dialog.dismiss()
            Toast.makeText(this, R.string.opened_from_fav, Toast.LENGTH_SHORT).show()
            loadDocument(Uri.fromFile(favorites.fileOf(fav)), fav.name, fav.uri)
        } else {
            val msg = if (status == DocStatus.EXPIRED) R.string.toast_expired else R.string.toast_deleted
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }
    }

    /** 区分 已删除（物理文件不存在）与 授权过期（无访问权限，如微信临时授权失效）。 */
    private fun statusOf(uriStr: String): DocStatus {
        val uri = Uri.parse(uriStr)
        if (uri.scheme == "file") {
            return if (uri.path?.let { File(it).exists() } == true) DocStatus.AVAILABLE else DocStatus.DELETED
        }
        val hasPerm = contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
        return try {
            val stream = contentResolver.openInputStream(uri)
            if (stream != null) {
                stream.close()
                DocStatus.AVAILABLE
            } else if (hasPerm) DocStatus.DELETED else DocStatus.EXPIRED
        } catch (e: SecurityException) {
            DocStatus.EXPIRED                 // 明确的权限问题 -> 授权过期
        } catch (e: Exception) {
            if (hasPerm) DocStatus.DELETED    // 有持久权限却打不开 -> 文件已删除
            else DocStatus.EXPIRED            // 无持久权限 -> 授权过期/丢失
        }
    }

    override fun onDestroy() {
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
        runOnUiThread {
            if (mode == "preview" || mode == "code") {
                currentMode = mode
                prefs.viewMode = mode
                invalidateOptionsMenu()
            }
        }
    }

    override fun copyText(text: String) {
        runOnUiThread {
            runCatching {
                getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText("code", text))
            }
        }
    }

    override fun onCenterTap() {
        // 点击正文中央区域唤出显示设置（电子书阅读器常见手势）
        runOnUiThread { showSettings() }
    }

    companion object {
        private const val ASSET_HOST = "appassets.androidplatform.net"
        private const val VIEWER_URL = "https://$ASSET_HOST/assets/viewer.html"

        private val WELCOME_MD = """
# 👋 欢迎使用 MD 阅读器

这是一个本地 **Markdown 阅读器**。下面用一段示例展示渲染效果，你可以立刻试试顶部的几个按钮。

## 怎么用

- 点击 **目录** 唤出大纲，点击标题快速跳转
- 点击 **源码 / 预览** 在两种呈现方式间切换；预览模式点击标题可折叠/展开
- **点击屏幕中央** 调出「显示设置」（字号 / 行距 / 段距 / 主题）
- 打开文件后，顶部可一键 **转发** 完整 `.md`，也可一键 **收藏** 当前文档
- **⋮** 里可打开 **收藏夹** 与 **打开历史**
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

行内代码 `val x = 1`，以及代码块（右上角可一键复制）：

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
