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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.IntentCompat
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.mdreader.app.databinding.ActivityMainBinding
import com.mdreader.app.databinding.SheetFavoritesBinding
import com.mdreader.app.databinding.SheetHistoryBinding
import com.mdreader.app.databinding.SheetSettingsBinding
import java.io.File
import java.net.URLDecoder
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity(), MarkdownBridge.Provider {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs
    private lateinit var history: History
    private lateinit var favorites: Favorites
    private lateinit var reading: ReadingProgress
    private lateinit var webView: WebView

    @Volatile private var currentMarkdown: String = ""
    @Volatile private var currentMode: String = Prefs.DEFAULT_MODE
    private var currentTitle: String = ""
    @Volatile private var currentUri: String? = null
    @Volatile private var currentDocumentUri: Uri? = null   // actual file URI for resolving relative paths
    private var pageReady: Boolean = false

    private val openPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) {
                val name = FileUtils.displayName(this, uri)
                if (!isMarkdownAllowed(name)) {
                    Toast.makeText(this, R.string.only_md, Toast.LENGTH_LONG).show()
                } else {
                    runCatching {
                        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    loadDocument(uri)
                }
            }
        }

    private val vaultPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            if (uri != null) {
                runCatching {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                }
                prefs.vaultUri = uri.toString()
                Toast.makeText(this, getString(R.string.vault_set), Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        history = History(this)
        favorites = Favorites(this)
        reading = ReadingProgress(this)
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

        checkForUpdates()
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
            ): WebResourceResponse? {
                val url = request.url
                // Serve bundled assets
                assetLoader.shouldInterceptRequest(url)?.let { return it }
                // Serve local vault assets: vault://encoded-filename
                if (url.scheme == "vault") {
                    return serveVaultAsset(url)
                }
                return null
            }

            override fun onPageFinished(view: WebView, url: String) {
                pageReady = true
                renderCurrent()
            }

            override fun shouldOverrideUrlLoading(
                view: WebView, request: WebResourceRequest
            ): Boolean {
                val url = request.url
                if (url.host == ASSET_HOST) return false
                // Handle wikilink navigation
                if (url.scheme == "mdreader" && url.host == "open") {
                    val noteName = try { URLDecoder.decode(url.path?.trimStart('/') ?: "", "UTF-8") } catch (e: Exception) { "" }
                    if (noteName.isNotEmpty()) {
                        runOnUiThread { openWikiLink(noteName) }
                        return true
                    }
                }
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

    /** Intercept vault:// URLs to serve local assets (images, etc.) from vault folder. */
    private fun serveVaultAsset(url: Uri): WebResourceResponse? {
        val encoded = url.toString().removePrefix("vault://")
        val filename = try { URLDecoder.decode(encoded, "UTF-8") } catch (e: Exception) { encoded }
        val vaultUriStr = prefs.vaultUri ?: return null
        val vaultUri = Uri.parse(vaultUriStr)
        return runCatching {
            val file = VaultSearch.resolveRelativeAsset(this, vaultUri, currentDocumentUri, filename)
                ?: return null
            val mime = guessMime(filename)
            val stream = contentResolver.openInputStream(file.uri) ?: return null
            WebResourceResponse(mime, "utf-8", stream)
        }.getOrNull()
    }

    private fun guessMime(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "svg" -> "image/svg+xml"
            "webp" -> "image/webp"
            "bmp" -> "image/bmp"
            else -> "application/octet-stream"
        }
    }

    private fun handleIntent(intent: Intent?): Boolean {
        if (intent == null) return false
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        }
        return if (uri != null) {
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            loadDocument(uri)
            true
        } else false
    }

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
                    currentDocumentUri = readUri
                    supportActionBar?.title = name
                    history.add(identityUri, name, System.currentTimeMillis())
                    renderCurrent()
                    invalidateOptionsMenu()
                }.onFailure { e ->
                    Toast.makeText(this, getString(R.string.open_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun renderCurrent() {
        if (!pageReady) return
        js("window.appRender && window.appRender()")
        js("window.appSetMode && window.appSetMode('$currentMode')")
        applySettingsToWeb()
        js("window.appRestoreScroll && window.appRestoreScroll()")
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
        if (name == null) return true
        val n = name.lowercase()
        return n.endsWith(".md") || n.endsWith(".markdown")
    }

    // ---- 自动更新检查 ----

    private fun checkForUpdates() {
        val now = System.currentTimeMillis()
        val lastCheck = prefs.lastUpdateCheck
        // Only check once per day
        if (now - lastCheck < TimeUnit.HOURS.toMillis(24)) return
        prefs.lastUpdateCheck = now
        Thread {
            val info = UpdateChecker.checkLatest() ?: return@Thread
            val currentVersion = packageManager.getPackageInfo(packageName, 0).versionName ?: return@Thread
            if (UpdateChecker.isNewer(info.tagName, currentVersion)) {
                runOnUiThread { showUpdateDialog(info) }
            }
        }.start()
    }

    private fun showUpdateDialog(info: UpdateChecker.ReleaseInfo) {
        AlertDialog.Builder(this)
            .setTitle(R.string.update_available_title)
            .setMessage(getString(R.string.update_available_msg, info.tagName))
            .setPositiveButton(R.string.update_go) { _, _ ->
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info.htmlUrl)))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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
        R.id.action_share -> { shareCurrentDocument(); true }
        R.id.action_open -> { openPicker.launch(arrayOf("*/*")); true }
        R.id.action_toc -> { js("window.appToggleToc && window.appToggleToc()"); true }
        R.id.action_search -> { js("window.appOpenSearch && window.appOpenSearch()"); true }
        R.id.action_toggle -> { toggleMode(); true }
        R.id.action_favorite -> { toggleFavorite(); true }
        R.id.action_favorites -> { showFavorites(); true }
        R.id.action_history -> { showHistory(); true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun toggleMode() {
        currentMode = if (currentMode == "preview") "code" else "preview"
        prefs.viewMode = currentMode
        js("window.appSetMode && window.appSetMode('$currentMode')")
        invalidateOptionsMenu()
    }

    // ---- 分享 ----

    private fun shareCurrentDocument() {
        if (currentUri == null || currentMarkdown.isEmpty()) {
            Toast.makeText(this, R.string.share_empty, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val dir = File(cacheDir, "shared").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val name = shareFileName(currentTitle)
            val file = File(dir, name)
            file.writeText(currentMarkdown, Charsets.UTF_8)
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "*/*"
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

    private fun shareFileName(title: String): String {
        var base = title.trim().ifEmpty { "document" }
        base = base.replace(Regex("[\\\\/:*?\"<>|\\r\\n]"), "_")
        if (!base.endsWith(".md", true) && !base.endsWith(".markdown", true)) base += ".md"
        return base
    }

    // ---- 收藏 ----

    private fun toggleFavorite() {
        val id = currentUri
        if (id == null) { Toast.makeText(this, R.string.fav_need_doc, Toast.LENGTH_SHORT).show(); return }
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
            onOpen = { fav -> dialog.dismiss(); loadDocument(Uri.fromFile(favorites.fileOf(fav)), fav.name, fav.uri) },
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

    // ---- 设置底部面板（含 Vault 选择）----

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

        // Show current vault folder name
        val vaultStr = prefs.vaultUri
        sheet.tvVaultPath.text = if (vaultStr != null) {
            runCatching {
                DocumentFile.fromTreeUri(this, Uri.parse(vaultStr))?.name ?: vaultStr
            }.getOrDefault(vaultStr)
        } else getString(R.string.vault_not_set)

        sheet.btnSelectVault.setOnClickListener {
            vaultPicker.launch(null)
        }

        sheet.sliderFont.addOnChangeListener { _, value, _ -> prefs.fontSize = value; updateLabels(sheet); applySettingsToWeb() }
        sheet.sliderLine.addOnChangeListener { _, value, _ -> prefs.lineHeight = value; updateLabels(sheet); applySettingsToWeb() }
        sheet.sliderPara.addOnChangeListener { _, value, _ -> prefs.paraGap = value; updateLabels(sheet); applySettingsToWeb() }
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
            prefs.fontSize = Prefs.DEFAULT_FONT; prefs.lineHeight = Prefs.DEFAULT_LINE; prefs.paraGap = Prefs.DEFAULT_PARA
            sheet.sliderFont.value = Prefs.DEFAULT_FONT; sheet.sliderLine.value = Prefs.DEFAULT_LINE; sheet.sliderPara.value = Prefs.DEFAULT_PARA
            updateLabels(sheet); applySettingsToWeb()
        }

        BottomSheetDialog(this).apply { setContentView(sheet.root); show() }
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

    // ---- 历史面板 ----

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
            Thread {
                val map = HashMap<String, DocStatus>(entries.size)
                entries.forEach { map[it.uri] = statusOf(it.uri) }
                runOnUiThread { adapter.setStatuses(map) }
            }.start()
        }
        sheet.btnClearHistory.setOnClickListener {
            history.clear(); dialog.dismiss()
            Toast.makeText(this, R.string.history_cleared, Toast.LENGTH_SHORT).show()
        }
        dialog.show()
    }

    private fun onHistoryEntryClicked(entry: History.Entry, status: DocStatus, dialog: BottomSheetDialog) {
        val fav = favorites.find(entry.uri)
        if (fav != null && favorites.fileOf(fav).exists()) {
            dialog.dismiss(); loadDocument(Uri.fromFile(favorites.fileOf(fav)), fav.name, fav.uri); return
        }
        if (status == DocStatus.AVAILABLE) {
            dialog.dismiss(); loadDocument(Uri.parse(entry.uri), entry.name, entry.uri); return
        }
        val msg = if (status == DocStatus.EXPIRED) R.string.toast_expired else R.string.toast_deleted
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private fun statusOf(uriStr: String): DocStatus {
        favorites.find(uriStr)?.let { if (favorites.fileOf(it).exists()) return DocStatus.AVAILABLE }
        val uri = Uri.parse(uriStr)
        if (uri.scheme == "file") {
            return if (uri.path?.let { File(it).exists() } == true) DocStatus.AVAILABLE else DocStatus.DELETED
        }
        val hasPerm = contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
        return try {
            val stream = contentResolver.openInputStream(uri)
            if (stream != null) { stream.close(); DocStatus.AVAILABLE }
            else if (hasPerm) DocStatus.DELETED else DocStatus.EXPIRED
        } catch (e: SecurityException) { DocStatus.EXPIRED
        } catch (e: Exception) { if (hasPerm) DocStatus.DELETED else DocStatus.EXPIRED }
    }

    override fun onDestroy() {
        binding.webview.apply { (parent as? android.view.ViewGroup)?.removeView(this); destroy() }
        super.onDestroy()
    }

    // ---- MarkdownBridge.Provider ----

    override fun markdown(): String = currentMarkdown
    override fun settingsJson(): String = prefs.settingsJson(this)
    override fun initialMode(): String = currentMode
    override fun readingRatio(): Double = currentUri?.let { reading.get(it) } ?: 0.0
    override fun saveReadingRatio(ratio: Double) { currentUri?.let { reading.set(it, ratio) } }

    override fun onModeChanged(mode: String) {
        runOnUiThread {
            if (mode == "preview" || mode == "code") { currentMode = mode; prefs.viewMode = mode; invalidateOptionsMenu() }
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

    override fun onCenterTap() { runOnUiThread { showSettings() } }

    override fun openWikiLink(noteName: String) {
        val vaultUriStr = prefs.vaultUri
        if (vaultUriStr == null) {
            runOnUiThread { Toast.makeText(this, R.string.vault_not_set_toast, Toast.LENGTH_LONG).show() }
            return
        }
        Thread {
            val file = VaultSearch.findFile(this, Uri.parse(vaultUriStr), noteName)
            runOnUiThread {
                if (file != null) {
                    loadDocument(file.uri, file.name)
                } else {
                    Toast.makeText(this, getString(R.string.wikilink_not_found, noteName), Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    override fun searchVault(query: String): String {
        val vaultUriStr = prefs.vaultUri ?: return "[]"
        return VaultSearch.search(this, Uri.parse(vaultUriStr), query)
    }

    override fun openVaultFile(uri: String) {
        runOnUiThread {
            runCatching { loadDocument(Uri.parse(uri)) }
        }
    }

    companion object {
        private const val ASSET_HOST = "appassets.androidplatform.net"
        private const val VIEWER_URL = "https://$ASSET_HOST/assets/viewer.html"

        private val WELCOME_MD = """
# 👋 欢迎使用 MD 阅读器

这是一个本地 **Markdown 阅读器**。下面用一段示例展示渲染效果，你可以立刻试试顶部的几个按钮。

## 怎么用

- 点击 **目录** 唤出大纲，点击标题快速跳转
- 点击 **搜索** 在当前文档中搜索，也可切换为全库搜索
- 点击 **源码 / 预览** 在两种呈现方式间切换；预览模式点击标题可折叠/展开
- **点击屏幕中央** 调出「显示设置」（字号 / 行距 / 段距 / 主题 / Vault 文件夹）
- 在设置中选择 **Vault 文件夹** 后，可使用 `[[wikilink]]` 导航与全库搜索
- 重新打开同一文档时，会自动回到上次阅读位置
- 打开文件后，顶部可一键 **转发** 完整 `.md`，也可一键 **收藏** 当前文档

## 新功能（v1.5.1）

- `[[Page Name]]` — Obsidian wikilink 导航
- `![[image.png]]` — 本地图片嵌入（需设置 Vault 文件夹）
- Mermaid 图表渲染
- YAML frontmatter 渲染
- 全文搜索（点击搜索图标）
- 跨文件全库搜索（需设置 Vault 文件夹）
- 自动检查更新

## Mermaid 示例

```mermaid
graph TD
    A[开始] --> B{是否有 Vault?}
    B -->|是| C[全库搜索]
    B -->|否| D[设置 Vault 文件夹]
    C --> E[找到文件]
```

## 支持的语法

| 功能 | 是否支持 |
| --- | :---: |
| 标题 / 列表 | ✅ |
| 表格 | ✅ |
| 代码高亮 | ✅ |
| Mermaid 图表 | ✅ |
| Wikilinks | ✅ |
| Frontmatter | ✅ |
| HTML 渲染 | ✅ |

---

打开一个文件开始阅读吧 📖
        """.trimIndent()
    }
}
