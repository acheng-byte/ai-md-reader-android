package com.mdreader.app

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
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
import java.io.FileOutputStream
import java.net.URLDecoder
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity(), MarkdownBridge.Provider {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs
    private lateinit var history: History
    private lateinit var favorites: Favorites
    private lateinit var reading: ReadingProgress
    private lateinit var annotations: Annotations
    private lateinit var webView: WebView

    @Volatile private var currentMarkdown: String = ""
    @Volatile private var currentMode: String = Prefs.DEFAULT_MODE
    private var currentTitle: String = ""
    @Volatile private var currentUri: String? = null
    @Volatile private var currentDocumentUri: Uri? = null
    private var pageReady: Boolean = false
    private var elementSaveCounter: Int = 0

    // Edit mode state
    private var isEditing: Boolean = false

    // Annotation state
    private var isAnnotating: Boolean = false
    private var annotationColor: String = "#FF0000"
    private var annotationMode: String = "free"
    private val annotationColors = listOf("#FF0000", "#0000FF", "#00AA00", "#FF8800", "#AA00FF", "#000000")
    private val annotationModes = listOf("free", "highlight", "circle", "wavy")
    private var colorIndex = 0
    private var modeIndex = 0
    private var annotationSaveRunnable: Runnable? = null

    // Cancel token: increment before each new load; JS render checks if it's stale
    private val loadGeneration = AtomicInteger(0)

    // Debounce renderCurrent
    private val renderHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingRender: Runnable? = null

    // Debounce settings apply (slider drag fires dozens of events per second)
    private val settingsHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingSettings: Runnable? = null

    private val openPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) {
                val name = FileUtils.displayName(this, uri)
                if (!isAllowedFile(name)) {
                    Toast.makeText(this, R.string.only_md, Toast.LENGTH_LONG).show()
                } else {
                    runCatching {
                        contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        )
                    }
                    loadDocument(uri)
                }
            }
        }

    private val saveDocLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri: Uri? ->
            if (uri != null) {
                val text = binding.editText.text.toString()
                val ok = writeTextToUri(uri, text)
                if (ok) {
                    currentMarkdown = text
                    currentUri = uri.toString()
                    currentDocumentUri = uri
                    supportActionBar?.title = currentTitle
                    currentMode = "preview"
                    prefs.viewMode = currentMode
                    binding.editScroll.visibility = View.GONE
                    binding.webview.visibility = View.VISIBLE
                    invalidateOptionsMenu()
                    renderCurrent()
                    Toast.makeText(this, R.string.edit_saved, Toast.LENGTH_SHORT).show()
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
        // 在创建 Activity 之前同步夜间模式，确保 DayNight 主题正确应用到工具栏和系统栏
        val p = Prefs(this)
        val nightMode = when (p.themeMode) {
            1 -> AppCompatDelegate.MODE_NIGHT_NO
            2 -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(nightMode)
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        history = History(this)
        favorites = Favorites(this)
        reading = ReadingProgress(this)
        annotations = Annotations(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        // 状态栏图标颜色：深色模式用白色图标，浅色模式用深色图标
        val isDarkInit = prefs.isDark(this)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if (isDarkInit) 0 else android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        // 标题栏点击 → 字符统计（防重复点击）
        var lastCharCountClick = 0L
        binding.toolbar.setOnClickListener {
            val now = System.currentTimeMillis()
            if (now - lastCharCountClick < 500) return@setOnClickListener
            lastCharCountClick = now
            if (currentMode == "preview") triggerCharCount()
        }
        webView = binding.webview
        currentMode = prefs.viewMode

        setupWebView()

        val handled = handleIntent(intent)
        if (!handled) {
            val lastUri = prefs.lastDocUri
            if (lastUri != null) {
                // Reopen last document; fall back to welcome screen on failure
                runCatching { loadDocument(Uri.parse(lastUri), prefs.lastDocName.ifEmpty { null }) }
                    .onFailure {
                        currentMarkdown = WELCOME_MD
                        currentTitle = getString(R.string.app_name)
                        supportActionBar?.title = currentTitle
                    }
            } else {
                // First launch ever: show welcome screen
                currentMarkdown = WELCOME_MD
                currentTitle = getString(R.string.app_name)
                supportActionBar?.title = currentTitle
                if (history.all().isNotEmpty()) {
                    webView.post { showHistory() }
                }
            }
        }
        webView.setBackgroundColor(bgColor())
        webView.loadUrl(VIEWER_URL)

        // Back press: in source mode, check for unsaved changes
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (currentMode == "code") {
                    val edited = binding.editText.text.toString()
                    if (edited != currentMarkdown) {
                        confirmDiscardEdit()
                    } else {
                        // 无修改，切换到预览模式
                        binding.editScroll.visibility = View.GONE
                        binding.webview.visibility = View.VISIBLE
                        currentMode = "preview"
                        prefs.viewMode = currentMode
                        renderCurrent()
                        invalidateOptionsMenu()
                    }
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        checkForUpdates()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun setupWebView() {
        // Use WebViewAssetLoader for both bundled assets AND vault assets
        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .addPathHandler("/vault/", VaultPathHandler())
            .build()

        webView.webViewClient = object : WebViewClientCompat() {
            override fun shouldInterceptRequest(
                view: WebView, request: WebResourceRequest
            ): WebResourceResponse? {
                val url = request.url
                return assetLoader.shouldInterceptRequest(url)
            }

            override fun onPageFinished(view: WebView, url: String) {
                pageReady = true
                // 如果保存的模式是源码模式，显示 editText
                if (currentMode == "code") {
                    binding.editText.setText(currentMarkdown)
                    binding.editText.setTextColor(if (prefs.isDark(this@MainActivity)) Color.WHITE else Color.BLACK)
                    binding.editText.setBackgroundColor(bgColor())
                    binding.editScroll.setBackgroundColor(bgColor())
                    binding.webview.visibility = View.GONE
                    binding.editScroll.visibility = View.VISIBLE
                    setupSourceAutoSave()
                }
                renderCurrent()
                invalidateOptionsMenu()
            }

            override fun shouldOverrideUrlLoading(
                view: WebView, request: WebResourceRequest
            ): Boolean {
                val url = request.url
                if (url.host == ASSET_HOST) return false
                if (url.scheme == "mdreader" && url.host == "open") {
                    val noteName = try {
                        URLDecoder.decode(url.path?.trimStart('/') ?: "", "UTF-8")
                    } catch (e: Exception) { "" }
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

    /** Custom path handler for vault:// scheme via WebViewAssetLoader at /vault/ path. */
    private inner class VaultPathHandler : WebViewAssetLoader.PathHandler {
        override fun handle(path: String): WebResourceResponse? {
            val filename = try { URLDecoder.decode(path.trimStart('/'), "UTF-8") } catch (e: Exception) { path.trimStart('/') }
            val vaultUriStr = prefs.vaultUri ?: return null
            val vaultUri = Uri.parse(vaultUriStr)
            return runCatching {
                val file = VaultSearch.resolveRelativeAsset(this@MainActivity, vaultUri, currentDocumentUri, filename)
                    ?: return null
                val mime = guessMime(filename)
                val stream = contentResolver.openInputStream(file.uri) ?: return null
                WebResourceResponse(mime, null, stream)
            }.getOrNull()
        }
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
            "ico" -> "image/x-icon"
            "mp4" -> "video/mp4"
            "webm" -> "video/webm"
            "ogv" -> "video/ogg"
            "mov" -> "video/quicktime"
            "mp3" -> "audio/mpeg"
            "ogg" -> "audio/ogg"
            "wav" -> "audio/wav"
            "m4a" -> "audio/mp4"
            "aac" -> "audio/aac"
            "flac" -> "audio/flac"
            "wma" -> "audio/x-ms-wma"
            "pdf" -> "application/pdf"
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
        // Exit source/edit mode when opening a different document
        if (currentMode == "code") {
            syncSourceContent()
            binding.editScroll.visibility = View.GONE
            binding.webview.visibility = View.VISIBLE
            currentMode = "preview"
            prefs.viewMode = currentMode
            invalidateOptionsMenu()
        }
        // Increment generation to cancel any in-flight load
        val gen = loadGeneration.incrementAndGet()

        Thread {
            val result = runCatching {
                val name = displayNameOverride
                    ?: FileUtils.displayName(this, readUri)
                    ?: getString(R.string.app_name)
                val text = FileUtils.readText(this, readUri)
                name to text
            }
            runOnUiThread {
                // Stale load — a newer one is already running
                if (gen != loadGeneration.get()) return@runOnUiThread
                result.onSuccess { (name, text) ->
                    currentMarkdown = text
                    currentTitle = name
                    currentUri = identityUri
                    currentDocumentUri = readUri
                    elementSaveCounter = 0
                    supportActionBar?.title = name
                    prefs.lastDocUri = identityUri
                    prefs.lastDocName = name
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
        // Debounce: cancel pending render and schedule a fresh one
        pendingRender?.let { renderHandler.removeCallbacks(it) }
        val r = Runnable {
            pendingRender = null
            js("window.appRender && window.appRender()")
            // WebView 始终渲染预览内容，即使当前处于源码模式（编辑器覆盖在 WebView 上方）
            js("window.appSetMode && window.appSetMode('preview')")
            applySettingsToWeb()
            js("window.appRestoreScroll && window.appRestoreScroll()")
        }
        pendingRender = r
        renderHandler.postDelayed(r, 150)
    }

    private fun applySettingsToWeb() {
        js("window.appApplySettings && window.appApplySettings(${prefs.settingsJson(this)})")
        val bg = bgColor()
        webView.setBackgroundColor(bg)
        // 同步工具栏和状态栏颜色
        binding.toolbar.setBackgroundColor(bg)
        window.statusBarColor = bg
        val isDark = prefs.isDark(this)
        // 状态栏图标：深色模式用白色图标，浅色模式用深色图标
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if (isDark) 0 else android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        if (currentMode == "code") {
            binding.editText.setTextColor(if (isDark) Color.WHITE else Color.BLACK)
            binding.editText.setBackgroundColor(bg)
            binding.editScroll.setBackgroundColor(bg)
        }
    }

    /** 防抖 300ms：滑块拖拽期间只更新标签，松手后才批量写 SP + 推送 WebView */
    private fun debouncedApplySettings(sheet: SheetSettingsBinding) {
        updateLabels(sheet)
        pendingSettings?.let { settingsHandler.removeCallbacks(it) }
        val r = Runnable {
            pendingSettings = null
            prefs.fontSize = sheet.sliderFont.value
            prefs.lineHeight = sheet.sliderLine.value
            prefs.paraGap = sheet.sliderPara.value
            applySettingsToWeb()
        }
        pendingSettings = r
        settingsHandler.postDelayed(r, 300)
    }

    private fun js(code: String) {
        if (pageReady) webView.evaluateJavascript(code, null)
    }

    private fun bgColor(): Int {
        if (prefs.eyeProtection) {
            return if (prefs.isDark(this)) 0xFF1A1610.toInt() else 0xFFF5ECD7.toInt()
        }
        return if (prefs.isDark(this)) 0xFF0D1117.toInt() else 0xFFFFFFFF.toInt()
    }

    private fun isAllowedFile(name: String?): Boolean {
        if (name == null) return true
        val n = name.lowercase()
        return n.endsWith(".md") || n.endsWith(".markdown") ||
               n.endsWith(".txt") || n.endsWith(".docx") || n.endsWith(".doc") ||
               n.endsWith(".pdf")
    }

    // ---- 自动更新检查 ----

    private fun checkForUpdates() {
        val now = System.currentTimeMillis()
        val lastCheck = prefs.lastUpdateCheck
        if (now - lastCheck < TimeUnit.HOURS.toMillis(12)) return
        prefs.lastUpdateCheck = now
        Thread {
            try {
                val info = UpdateChecker.checkLatest() ?: return@Thread
                val currentVersion = runCatching {
                    packageManager.getPackageInfo(packageName, 0).versionName
                }.getOrNull() ?: return@Thread
                if (UpdateChecker.isNewer(info.tagName, currentVersion)) {
                    runOnUiThread { showUpdateDialog(info) }
                }
            } catch (_: Exception) { /* silent for auto-check */ }
        }.start()
    }

    /** 用户手动点击「检查更新」按钮，绕过节流，带 Toast 反馈 */
    private fun manualCheckForUpdates() {
        Toast.makeText(this, R.string.update_checking, Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val info = UpdateChecker.checkLatest()
                if (info == null) {
                    runOnUiThread {
                        Toast.makeText(this, getString(R.string.update_check_failed, "网络请求失败"), Toast.LENGTH_LONG).show()
                    }
                    return@Thread
                }
                val currentVersion = runCatching {
                    packageManager.getPackageInfo(packageName, 0).versionName
                }.getOrNull() ?: "0.0.0"
                if (UpdateChecker.isNewer(info.tagName, currentVersion)) {
                    runOnUiThread { showUpdateDialog(info) }
                } else {
                    runOnUiThread {
                        Toast.makeText(this, getString(R.string.update_latest, currentVersion), Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.update_check_failed, e.message ?: "未知错误"), Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun showUpdateDialog(info: UpdateChecker.ReleaseInfo) {
        val builder = AlertDialog.Builder(this)
            .setTitle(R.string.update_available_title)
            .setMessage(getString(R.string.update_available_msg, info.tagName))
            .setNegativeButton(android.R.string.cancel, null)

        if (info.apkDownloadUrl != null) {
            builder.setPositiveButton(R.string.update_download) { _, _ ->
                downloadApk(info.apkDownloadUrl, info.tagName)
            }
        } else {
            builder.setPositiveButton(R.string.update_go) { _, _ ->
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info.htmlUrl)))
            }
        }
        builder.show()
    }

    private fun downloadApk(url: String, tagName: String) {
        try {
            val fileName = "MDReader-$tagName.apk"
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setTitle(getString(R.string.update_downloading, tagName))
                setDescription(getString(R.string.app_name))
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                setMimeType("application/vnd.android.package-archive")
                allowScanningByMediaScanner()
            }
            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val downloadId = dm.enqueue(request)
            Toast.makeText(this, R.string.update_downloading_toast, Toast.LENGTH_SHORT).show()

            // Register receiver to trigger install when download completes
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                    if (id == downloadId) {
                        unregisterReceiver(this)
                        val apkUri = dm.getUriForDownloadedFile(downloadId) ?: return
                        installApk(apkUri)
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                    Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
            }
        } catch (e: Exception) {
            // Fallback: open browser
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(UpdateChecker.RELEASES_PAGE)))
        }
    }

    private fun installApk(apkUri: Uri) {
        runCatching {
            val install = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(install)
        }.onFailure {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(UpdateChecker.RELEASES_PAGE)))
        }
    }

    // ---- 菜单 ----

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val inSource = currentMode == "code"
        // 源码模式：显示保存/取消，隐藏其他操作
        menu.findItem(R.id.action_save)?.isVisible = inSource
        menu.findItem(R.id.action_cancel_edit)?.isVisible = inSource
        val normalVisible = !inSource
        listOf(
            R.id.action_share, R.id.action_toc, R.id.action_search,
            R.id.action_toggle, R.id.action_favorite,
            R.id.action_open, R.id.action_favorites, R.id.action_history,
            R.id.action_add_shortcut,
            R.id.action_export_image, R.id.action_export_html,
            R.id.action_annotate
        ).forEach { menu.findItem(it)?.isVisible = normalVisible }
        // edit 按钮不再需要（源码模式即可编辑），但保留 toggle 按钮
        menu.findItem(R.id.action_edit)?.isVisible = false

        // 更新 toggle 按钮图标/标题
        if (!inSource) {
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
        }

        // 标注工具：仅在标注模式开启时显示
        val annotateToolsVisible = isAnnotating
        listOf(
            R.id.action_annotate_color, R.id.action_annotate_mode,
            R.id.action_annotate_undo, R.id.action_annotate_clear
        ).forEach { menu.findItem(it)?.isVisible = annotateToolsVisible }

        if (annotateToolsVisible) {
            menu.findItem(R.id.action_annotate_color)?.title =
                getString(R.string.annotate_color, annotationColor)
            menu.findItem(R.id.action_annotate_mode)?.title =
                getString(R.string.annotate_mode, annotationModeName(annotationMode))
        }
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_save -> {
            // 源码模式保存：同步内容并尝试原地保存
            syncSourceContent()
            val saved = trySaveInPlace(currentMarkdown)
            if (saved) {
                Toast.makeText(this, R.string.edit_saved, Toast.LENGTH_SHORT).show()
                // 切换到预览模式
                binding.editScroll.visibility = View.GONE
                binding.webview.visibility = View.VISIBLE
                currentMode = "preview"
                prefs.viewMode = currentMode
                renderCurrent()
                invalidateOptionsMenu()
            } else {
                Toast.makeText(this, R.string.edit_save_as_hint, Toast.LENGTH_LONG).show()
                val safeName = shareFileName(currentTitle).removeSuffix(".md") + ".md"
                saveDocLauncher.launch(safeName)
            }
            true
        }
        R.id.action_cancel_edit -> {
            // 取消编辑：检查是否有未保存修改
            val edited = binding.editText.text.toString()
            if (edited != currentMarkdown) {
                confirmDiscardEdit()
            } else {
                // 无修改，直接切换到预览
                binding.editScroll.visibility = View.GONE
                binding.webview.visibility = View.VISIBLE
                currentMode = "preview"
                prefs.viewMode = currentMode
                renderCurrent()
                invalidateOptionsMenu()
            }
            true
        }
        R.id.action_share -> { shareCurrentDocument(); true }
        R.id.action_open -> {
            openPicker.launch(arrayOf("*/*"))
            true
        }
        R.id.action_toc -> { js("window.appToggleToc && window.appToggleToc()"); true }
        R.id.action_search -> { js("window.appOpenSearch && window.appOpenSearch()"); true }
        R.id.action_toggle -> { toggleMode(); true }
        R.id.action_favorite -> { toggleFavorite(); true }
        R.id.action_favorites -> { showFavorites(); true }
        R.id.action_history -> { showHistory(); true }
        R.id.action_add_shortcut -> { addShortcutToHomeScreen(); true }
        R.id.action_export_image -> { requestStorageAndExportImage(); true }
        R.id.action_export_html -> { exportHtml(); true }
        R.id.action_annotate -> { toggleAnnotationMode(); true }
        R.id.action_annotate_color -> { cycleAnnotationColor(); true }
        R.id.action_annotate_mode -> { cycleAnnotationMode(); true }
        R.id.action_annotate_undo -> { binding.annotationOverlay.undo(); true }
        R.id.action_annotate_clear -> {
            binding.annotationOverlay.clearAll()
            Toast.makeText(this, R.string.annotate_cleared, Toast.LENGTH_SHORT).show()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    // ---- 阅读标注 ----

    private fun toggleAnnotationMode() {
        isAnnotating = !isAnnotating
        val overlay = binding.annotationOverlay
        if (isAnnotating) {
            overlay.visibility = View.VISIBLE
            overlay.annotationEnabled = true
            overlay.drawColor = annotationColor
            overlay.drawMode = annotationMode
            // 加载已有标注
            currentUri?.let { uri ->
                val strokes = annotations.load(uri)
                if (strokes.isNotEmpty()) overlay.loadStrokes(strokes)
            }
            overlay.onStrokesChanged = {
                // 异步防抖保存标注，避免文件 IO 阻塞主线程
                annotationSaveRunnable?.let { settingsHandler.removeCallbacks(it) }
                val r = Runnable {
                    currentUri?.let { uri ->
                        val strokesCopy = overlay.exportStrokes()
                        Thread { annotations.save(uri, strokesCopy) }.start()
                    }
                }
                annotationSaveRunnable = r
                settingsHandler.postDelayed(r, 500)
            }
            Toast.makeText(this, R.string.annotate_on, Toast.LENGTH_SHORT).show()
        } else {
            overlay.annotationEnabled = false
            overlay.visibility = View.GONE
            // 取消待执行的防抖保存，立即异步保存最终状态
            annotationSaveRunnable?.let { settingsHandler.removeCallbacks(it) }
            currentUri?.let { uri ->
                val strokesCopy = overlay.exportStrokes()
                Thread { annotations.save(uri, strokesCopy) }.start()
                Toast.makeText(this, R.string.annotate_saved, Toast.LENGTH_SHORT).show()
            }
            Toast.makeText(this, R.string.annotate_off, Toast.LENGTH_SHORT).show()
        }
        invalidateOptionsMenu()
    }

    private fun cycleAnnotationColor() {
        colorIndex = (colorIndex + 1) % annotationColors.size
        annotationColor = annotationColors[colorIndex]
        binding.annotationOverlay.drawColor = annotationColor
        invalidateOptionsMenu()
    }

    private fun cycleAnnotationMode() {
        modeIndex = (modeIndex + 1) % annotationModes.size
        annotationMode = annotationModes[modeIndex]
        binding.annotationOverlay.drawMode = annotationMode
        invalidateOptionsMenu()
    }

    private fun annotationModeName(mode: String): String = when (mode) {
        "highlight" -> getString(R.string.annotate_mode_highlight)
        "circle" -> getString(R.string.annotate_mode_circle)
        "wavy" -> getString(R.string.annotate_mode_wavy)
        else -> getString(R.string.annotate_mode_free)
    }

    private fun toggleMode() {
        if (currentMode == "preview") {
            // 切换到源码模式：显示可编辑的 editText
            currentMode = "code"
            prefs.viewMode = currentMode
            binding.editText.setText(currentMarkdown)
            binding.editText.setTextColor(if (prefs.isDark(this)) Color.WHITE else Color.BLACK)
            binding.editText.setBackgroundColor(bgColor())
            binding.editScroll.setBackgroundColor(bgColor())
            binding.webview.visibility = View.GONE
            binding.editScroll.visibility = View.VISIBLE
            binding.editText.requestFocus()
            // 根据预览模式的阅读位置，将光标定位到源码中对应位置
            val ratio = readingRatio()
            val textLen = currentMarkdown.length
            val targetPos = if (ratio > 0.01 && textLen > 0) {
                (ratio * textLen).toInt().coerceIn(0, textLen)
            } else {
                0
            }
            binding.editText.setSelection(targetPos)
            // 滚动 EditText 到对应位置
            binding.editText.post {
                val layout = binding.editText.layout ?: return@post
                val line = layout.getLineForOffset(targetPos)
                val y = layout.getLineTop(line)
                binding.editScroll.scrollTo(0, y)
            }
            setupSourceAutoSave()
        } else {
            // 切换到预览模式：保存编辑内容并渲染
            syncSourceContent()
            binding.editScroll.visibility = View.GONE
            binding.webview.visibility = View.VISIBLE
            currentMode = "preview"
            prefs.viewMode = currentMode
            renderCurrent()
        }
        invalidateOptionsMenu()
    }

    /** 防抖自动保存：源码模式下停止输入 2 秒后自动保存 */
    private var sourceSaveRunnable: Runnable? = null
    private fun setupSourceAutoSave() {
        sourceSaveRunnable?.let { settingsHandler.removeCallbacks(it) }
        binding.editText.removeTextChangedListener(sourceTextWatcher)
        binding.editText.addTextChangedListener(sourceTextWatcher)
    }

    private val sourceTextWatcher = object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: android.text.Editable) {
            sourceSaveRunnable?.let { settingsHandler.removeCallbacks(it) }
            val r = Runnable {
                currentMarkdown = binding.editText.text.toString()
                // 异步尝试原地保存
                currentUri?.let { uri ->
                    Thread { trySaveInPlace(currentMarkdown) }.start()
                }
            }
            sourceSaveRunnable = r
            settingsHandler.postDelayed(r, 2000)
        }
    }

    /** 同步源码编辑内容到 currentMarkdown */
    private fun syncSourceContent() {
        sourceSaveRunnable?.let { settingsHandler.removeCallbacks(it) }
        binding.editText.removeTextChangedListener(sourceTextWatcher)
        currentMarkdown = binding.editText.text.toString()
    }

    // ---- 编辑模式 ----

    private fun enterEditMode() {
        isEditing = true
        binding.editText.setText(currentMarkdown)
        binding.editText.setTextColor(if (prefs.isDark(this)) Color.WHITE else Color.BLACK)
        binding.editText.setBackgroundColor(bgColor())
        binding.editScroll.setBackgroundColor(bgColor())
        binding.webview.visibility = View.GONE
        binding.editScroll.visibility = View.VISIBLE
        binding.editText.requestFocus()
        // 根据预览模式的阅读位置，将光标定位到源码中对应位置
        val ratio = readingRatio()
        val textLen = currentMarkdown.length
        val targetPos = if (ratio > 0.01 && textLen > 0) {
            (ratio * textLen).toInt().coerceIn(0, textLen)
        } else {
            0
        }
        binding.editText.setSelection(targetPos)
        binding.editText.post {
            val layout = binding.editText.layout ?: return@post
            val line = layout.getLineForOffset(targetPos)
            val y = layout.getLineTop(line)
            binding.editScroll.scrollTo(0, y)
        }
        supportActionBar?.title = currentTitle + getString(R.string.edit_title_suffix)
        invalidateOptionsMenu()
    }

    private fun exitEditMode(save: Boolean) {
        if (save) {
            val newText = binding.editText.text.toString()
            val saved = trySaveInPlace(newText)
            if (saved) {
                currentMarkdown = newText
                Toast.makeText(this, R.string.edit_saved, Toast.LENGTH_SHORT).show()
            } else {
                // No write permission — offer Save As
                Toast.makeText(this, R.string.edit_save_as_hint, Toast.LENGTH_LONG).show()
                val safeName = shareFileName(currentTitle).removeSuffix(".md") + ".md"
                saveDocLauncher.launch(safeName)
                return
            }
        }
        isEditing = false
        binding.editScroll.visibility = View.GONE
        binding.webview.visibility = View.VISIBLE
        supportActionBar?.title = currentTitle
        invalidateOptionsMenu()
        if (save) renderCurrent()
    }

    private fun confirmDiscardEdit() {
        AlertDialog.Builder(this)
            .setTitle(R.string.edit_discard_title)
            .setMessage(R.string.edit_discard_msg)
            .setPositiveButton(R.string.edit_discard_confirm) { _, _ ->
                // 放弃修改，切换到预览模式
                binding.editScroll.visibility = View.GONE
                binding.webview.visibility = View.VISIBLE
                currentMode = "preview"
                prefs.viewMode = currentMode
                renderCurrent()
                invalidateOptionsMenu()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Try to write [text] back to the current URI in-place. Returns true on success.
     *  Refuses for binary formats (DOC/DOCX/PDF) — writing markdown into them would corrupt the file. */
    private fun trySaveInPlace(text: String): Boolean {
        val uri = currentDocumentUri ?: return false
        // 检查当前文件是否为二进制格式（DOC/DOCX/PDF），这些格式不支持原地保存
        val ext = currentTitle.substringAfterLast('.', "").lowercase()
        val name = FileUtils.displayName(this, uri)?.lowercase() ?: ""
        val isBinaryFormat = name.endsWith(".doc") || name.endsWith(".docx") ||
            name.endsWith(".pdf") || ext == "doc" || ext == "docx" || ext == "pdf"
        if (isBinaryFormat) return false  // 强制走「另存为」流程
        if (uri.scheme == "file") {
            return runCatching {
                val path = uri.path ?: return false
                File(path).writeText(text, Charsets.UTF_8)
                true
            }.getOrDefault(false)
        }
        return writeTextToUri(uri, text)
    }

    private fun writeTextToUri(uri: Uri, text: String): Boolean =
        runCatching {
            val out = contentResolver.openOutputStream(uri, "wt") ?: return@runCatching false
            out.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            true
        }.getOrDefault(false)

    // ---- 桌面快捷方式 ----

    private fun addShortcutToHomeScreen() {
        val uri = currentUri
        if (uri == null) {
            Toast.makeText(this, R.string.shortcut_no_doc, Toast.LENGTH_SHORT).show()
            return
        }
        val shortcutManager = getSystemService(ShortcutManager::class.java)
        if (shortcutManager == null || !shortcutManager.isRequestPinShortcutSupported) {
            Toast.makeText(this, R.string.shortcut_not_supported, Toast.LENGTH_SHORT).show()
            return
        }
        val docUri = currentDocumentUri ?: Uri.parse(uri)
        val displayName = currentTitle.ifEmpty { "MD文档" }

        // 构建 Intent：通过 ACTION_VIEW 打开文档，handleIntent() 会自动处理
        val intent = Intent(Intent.ACTION_VIEW, docUri).apply {
            setClass(this@MainActivity, MainActivity::class.java)
        }

        val shortcutInfo = ShortcutInfo.Builder(this, "doc_${uri.hashCode()}")
            .setShortLabel(displayName)
            .setLongLabel(displayName)
            .setIcon(android.graphics.drawable.Icon.createWithResource(this, R.mipmap.ic_launcher))
            .setIntent(intent)
            .build()

        shortcutManager.requestPinShortcut(shortcutInfo, null)
        Toast.makeText(this, getString(R.string.shortcut_added, displayName), Toast.LENGTH_SHORT).show()
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
        base = base.replace(FILENAME_UNSAFE_REGEX, "_")
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

        // 护眼模式和字体选择
        sheet.switchEyeProtection.isChecked = prefs.eyeProtection

        // 字体 Spinner（9 种字体）
        val fontKeys = arrayOf("default", "serif", "mono", "sans", "kai", "fangsong", "xiaobiao", "lishu", "yahei")
        val fontNames = arrayOf(
            getString(R.string.font_default),
            getString(R.string.font_serif),
            getString(R.string.font_mono),
            getString(R.string.font_sans),
            getString(R.string.font_kai),
            getString(R.string.font_fangsong),
            getString(R.string.font_xiaobiao),
            getString(R.string.font_lishu),
            getString(R.string.font_yahei)
        )
        val fontAdapter = object : android.widget.ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, fontNames) {
            override fun getDropDownView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                val v = super.getDropDownView(position, convertView, parent)
                val tv = v.findViewById<android.widget.TextView>(android.R.id.text1)
                tv.setTypeface(getFontTypeface(fontKeys[position]))
                return v
            }
        }
        sheet.spinnerFontFamily.adapter = fontAdapter
        val currentFontIdx = fontKeys.indexOf(prefs.fontFamily).coerceAtLeast(0)
        sheet.spinnerFontFamily.setSelection(currentFontIdx)
        sheet.spinnerFontFamily.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                prefs.fontFamily = fontKeys[position]
                applySettingsToWeb()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        // Frontmatter and citations toggles
        sheet.switchFrontmatter.isChecked = prefs.showFrontmatter
        sheet.switchCitations.isChecked = prefs.showCitations
        sheet.switchHideTitle.isChecked = prefs.hideTitleHeading

        // Show current vault folder name
        val vaultStr = prefs.vaultUri
        sheet.tvVaultPath.text = if (vaultStr != null) {
            runCatching {
                DocumentFile.fromTreeUri(this, Uri.parse(vaultStr))?.name ?: vaultStr
            }.getOrDefault(vaultStr)
        } else getString(R.string.vault_not_set)

        sheet.btnSelectVault.setOnClickListener { vaultPicker.launch(null) }

        sheet.sliderFont.addOnChangeListener { _, _, _ -> debouncedApplySettings(sheet) }
        sheet.sliderLine.addOnChangeListener { _, _, _ -> debouncedApplySettings(sheet) }
        sheet.sliderPara.addOnChangeListener { _, _, _ -> debouncedApplySettings(sheet) }
        sheet.toggleTheme.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                prefs.themeMode = when (checkedId) {
                    R.id.btn_theme_light -> 1
                    R.id.btn_theme_dark -> 2
                    else -> 0
                }
                // 同步夜间模式并重建 Activity，使工具栏/状态栏颜色跟随主题
                val nightMode = when (prefs.themeMode) {
                    1 -> AppCompatDelegate.MODE_NIGHT_NO
                    2 -> AppCompatDelegate.MODE_NIGHT_YES
                    else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
                AppCompatDelegate.setDefaultNightMode(nightMode)
                recreate()
            }
        }
        sheet.switchEyeProtection.setOnCheckedChangeListener { _, checked ->
            prefs.eyeProtection = checked
            applySettingsToWeb()
        }
        sheet.switchFrontmatter.setOnCheckedChangeListener { _, checked ->
            prefs.showFrontmatter = checked
            applySettingsToWeb()
        }
        sheet.switchCitations.setOnCheckedChangeListener { _, checked ->
            prefs.showCitations = checked
            applySettingsToWeb()
        }
        sheet.switchHideTitle.setOnCheckedChangeListener { _, checked ->
            prefs.hideTitleHeading = checked
            applySettingsToWeb()
        }

        // 版本号显示 & 检查更新
        val ver = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrDefault("?")
        sheet.tvVersionInfo.text = "v$ver"
        sheet.btnCheckUpdate.setOnClickListener { manualCheckForUpdates() }

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
        val entries = history.all().toMutableList()
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
            adapter = HistoryAdapter(
                items = entries,
                favorites = favSet,
                onClick = { entry ->
                    onHistoryEntryClicked(entry, adapter.statusOf(entry.uri), dialog)
                },
                onDelete = { entry, position ->
                    // 从持久化存储中删除
                    history.remove(entry.uri)
                    // 从列表中移除（adapter.removeAt 内部已同时移除 items 引用中的元素）
                    adapter.removeAt(position)
                    Toast.makeText(this, R.string.history_deleted_confirm, Toast.LENGTH_SHORT).show()
                    // 如果列表空了，显示空状态
                    if (entries.isEmpty()) {
                        sheet.historyEmpty.visibility = View.VISIBLE
                        sheet.historyList.visibility = View.GONE
                    }
                }
            )
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

    override fun onPause() {
        super.onPause()
        reading.flush()
    }

    override fun onDestroy() {
        pageReady = false // 防止 JS 回调在 WebView 销毁后仍执行
        pendingRender?.let { renderHandler.removeCallbacks(it) }
        pendingSettings?.let { settingsHandler.removeCallbacks(it) }
        sourceSaveRunnable?.let { settingsHandler.removeCallbacks(it) }
        annotationSaveRunnable?.let { settingsHandler.removeCallbacks(it) }
        // 源码模式下退出时同步内容
        if (currentMode == "code") syncSourceContent()
        reading.flush()
        binding.webview.apply { (parent as? android.view.ViewGroup)?.removeView(this); destroy() }
        super.onDestroy()
    }

    // ---- MarkdownBridge.Provider ----

    override fun markdown(): String = currentMarkdown
    override fun settingsJson(): String = prefs.settingsJson(this)
    override fun initialMode(): String = currentMode
    override fun readingRatio(): Double = currentUri?.let { reading.get(it) } ?: 0.0
    override fun saveReadingRatio(ratio: Double) { currentUri?.let { reading.set(it, ratio) } }
    override fun docTitle(): String = currentTitle

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

    override fun searchVaultAsync(query: String, callbackId: String) {
        val vaultUriStr = prefs.vaultUri
        if (vaultUriStr == null) {
            val escaped = org.json.JSONArray().toString()
            runOnUiThread { js("window.appVaultSearchResult && window.appVaultSearchResult('${escapeJs(callbackId)}', '${escapeJs(escaped)}')") }
            return
        }
        Thread {
            val result = VaultSearch.search(this, Uri.parse(vaultUriStr), query)
            runOnUiThread {
                js("window.appVaultSearchResult && window.appVaultSearchResult('${escapeJs(callbackId)}', ${org.json.JSONObject.quote(result)})")
            }
        }.start()
    }

    override fun openVaultFile(uri: String) {
        runOnUiThread { runCatching { loadDocument(Uri.parse(uri)) } }
    }

    override fun searchVaultForEmbed(ref: String): String {
        val vaultUriStr = prefs.vaultUri ?: return ""
        return runCatching {
            VaultSearch.findFile(this, Uri.parse(vaultUriStr), ref.substringBeforeLast('.'))?.uri?.toString() ?: ""
        }.getOrDefault("")
    }

    override fun loadEmbedContent(uri: String): String {
        return runCatching {
            FileUtils.readText(this, Uri.parse(uri))
        }.getOrDefault("")
    }

    private fun escapeJs(s: String): String = s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n")

    // ---- 表格/图表保存为图片 ----

    /** 保存表格或 Mermaid 图表元素为 PNG 图片。
     *  创建离屏 WebView 渲染 HTML 内容，然后截图保存。 */
    override fun saveElementImage(type: String, html: String) {
        runOnUiThread {
            val baseName = exportFileName(currentTitle)
            val counter = ++elementSaveCounter
            val fileName = "${baseName}_${type}_$counter"

            // 构建带样式的 HTML，确保渲染效果与正文一致
            val isDark = prefs.isDark(this)
            val bgColor = if (isDark) "#0d1117" else "#ffffff"
            val fgColor = if (isDark) "#e6edf3" else "#1f2328"
            val borderColor = if (isDark) "#30363d" else "#d0d7de"
            val stripeBg = if (isDark) "#161b22" else "#f6f8fa"

            // 清理 HTML 内容，移除可能导致渲染问题的字符
            val cleanHtml = html.replace("</body>", "").replace("</html>", "")
                .replace("<!DOCTYPE html>", "").replace("<html>", "").replace("<head>", "")
                .replace("</head>", "").replace("<body>", "")

            val styledHtml = """
                <!DOCTYPE html>
                <html><head><meta charset="utf-8">
                <meta name="viewport" content="width=${if (type == "table") 1080 else 800}, initial-scale=1">
                <style>
                * { box-sizing: border-box; margin: 0; padding: 0; }
                body { background: $bgColor; color: $fgColor; padding: 24px;
                  font-family: -apple-system, "PingFang SC", "Microsoft YaHei", sans-serif;
                  font-size: 15px; line-height: 1.6; }
                table { border-collapse: collapse; width: 100%; margin: 0; }
                th, td { border: 1px solid $borderColor; padding: 8px 14px; text-align: left; color: $fgColor; }
                th { font-weight: 600; background: $stripeBg; }
                tr:nth-child(2n) td { background: $stripeBg; }
                svg { max-width: 100%; height: auto; }
                .mermaid-container { text-align: center; }
                </style></head><body>
                ${if (type == "mermaid") "<div class=\"mermaid-container\">$cleanHtml</div>" else cleanHtml}
                </body></html>
            """.trimIndent()

            // 创建离屏 WebView 渲染内容
            val offscreen = WebView(this)
            offscreen.settings.javaScriptEnabled = true
            offscreen.settings.useWideViewPort = true
            offscreen.settings.loadWithOverviewMode = true
            offscreen.setBackgroundColor(if (isDark) 0xFF0D1117.toInt() else 0xFFFFFFFF.toInt())
            // 强制软件渲染：确保 view.draw(canvas) 能正确绘制内容（硬件加速下离屏 WebView 可能输出空白）
            offscreen.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            // 根据内容估算宽度：表格用 1080px，mermaid 用 800px
            val renderWidth = if (type == "table") 1080 else 800

            offscreen.webViewClient = object : androidx.webkit.WebViewClientCompat() {
                override fun onPageFinished(view: WebView, url: String) {
                    // 等待布局完成后截图（增加延迟确保渲染完成）
                    view.postDelayed({
                        try {
                            // 强制测量和布局
                            view.measure(
                                android.view.View.MeasureSpec.makeMeasureSpec(renderWidth, android.view.View.MeasureSpec.EXACTLY),
                                android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED)
                            )
                            view.layout(0, 0, view.measuredWidth, view.measuredHeight)

                            // 获取内容高度，确保不为 0
                            val contentHeight = view.contentHeight
                            val h = if (contentHeight > 0) {
                                (contentHeight * view.scale).toInt().coerceAtLeast(view.measuredHeight)
                            } else {
                                view.measuredHeight.coerceAtLeast(600)
                            }
                            val w = view.measuredWidth

                            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                            val canvas = Canvas(bitmap)
                            canvas.drawColor(if (isDark) 0xFF0D1117.toInt() else 0xFFFFFFFF.toInt())
                            view.draw(canvas)

                            // 异步保存
                            Thread {
                                try {
                                    saveImageToGallery(bitmap, fileName)
                                    bitmap.recycle()
                                    runOnUiThread {
                                        Toast.makeText(this@MainActivity,
                                            getString(R.string.element_saved, "$fileName.png"), Toast.LENGTH_LONG).show()
                                    }
                                } catch (e: Exception) {
                                    bitmap.recycle()
                                    runOnUiThread {
                                        Toast.makeText(this@MainActivity,
                                            getString(R.string.element_save_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
                                    }
                                }
                            }.start()
                        } catch (e: Exception) {
                            runOnUiThread {
                                Toast.makeText(this@MainActivity,
                                    getString(R.string.element_save_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
                            }
                        }
                        // 清理离屏 WebView 及其临时容器
                        val parent = view.parent as? android.view.ViewGroup
                        parent?.removeView(view)
                        view.destroy()
                        (parent?.parent as? android.view.ViewGroup)?.removeView(parent) // 移除 tempContainer
                    }, 1000)
                }
            }
            // 添加到临时 ViewGroup 以触发 WebView 内部初始化（不替换 Activity 布局）
            // 关键：必须用 INVISIBLE（而非 GONE），否则子 View 不参与布局，draw(canvas) 输出空白
            // 容器给实际尺寸，平移到屏幕外，确保 WebView 能正常渲染
            val tempContainer = android.widget.FrameLayout(this)
            tempContainer.visibility = View.INVISIBLE
            val screenW = resources.displayMetrics.widthPixels
            (window.decorView as android.view.ViewGroup).addView(tempContainer,
                android.view.ViewGroup.LayoutParams(renderWidth, android.view.ViewGroup.LayoutParams.WRAP_CONTENT))
            tempContainer.translationX = screenW.toFloat() + 100f
            tempContainer.addView(offscreen,
                android.view.ViewGroup.LayoutParams(renderWidth, android.view.ViewGroup.LayoutParams.WRAP_CONTENT))
            offscreen.loadDataWithBaseURL(null, styledHtml, "text/html", "utf-8", null)
        }
    }

    // ---- JS 端 SVG→PNG base64 保存 ----

    /** 保存 JS 端通过 Canvas 转换的 PNG base64 数据。
     *  Mermaid 图表在主 WebView 中已渲染为 SVG，JS 端通过 Canvas API 转为 PNG 后传回。
     *  完全绕过离屏 WebView 渲染，解决 draw(canvas) 空白问题。 */
    override fun savePngBase64(base64: String, elementType: String) {
        runOnUiThread {
            val baseName = exportFileName(currentTitle)
            val counter = ++elementSaveCounter
            val fileName = "${baseName}_${elementType}_$counter"

            Thread {
                try {
                    val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
                    val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bitmap == null) {
                        runOnUiThread {
                            Toast.makeText(this, getString(R.string.element_save_failed, "base64 解码失败"),
                                Toast.LENGTH_LONG).show()
                        }
                        return@Thread
                    }
                    val saved = saveImageToGallery(bitmap, fileName)
                    bitmap.recycle()
                    runOnUiThread {
                        if (saved != null) {
                            Toast.makeText(this, getString(R.string.element_saved, "$fileName.png"),
                                Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(this, getString(R.string.element_save_failed, "无法创建文件"),
                                Toast.LENGTH_LONG).show()
                        }
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        Toast.makeText(this, getString(R.string.element_save_failed, e.message ?: ""),
                            Toast.LENGTH_LONG).show()
                    }
                }
            }.start()
        }
    }

    // ---- 字符统计 ----

    override fun showCharCount(stats: String) {
        runOnUiThread {
            AlertDialog.Builder(this)
                .setTitle(R.string.char_count_title)
                .setMessage(stats)
                .setPositiveButton(R.string.ok, null)
                .show()
        }
    }

    /** 显示字符统计（由标题栏点击触发） */
    fun triggerCharCount() {
        webView.evaluateJavascript("window.appShowCharCount && window.appShowCharCount()") {}
    }

    // ---- 字体辅助 ----

    /** 根据字体 key 返回对应的 Typeface（用于 Spinner 预览） */
    private fun getFontTypeface(key: String): android.graphics.Typeface {
        return when (key) {
            "serif" -> android.graphics.Typeface.SERIF
            "mono" -> android.graphics.Typeface.MONOSPACE
            "kai" -> runCatching { android.graphics.Typeface.create("KaiTi", android.graphics.Typeface.NORMAL) }.getOrDefault(android.graphics.Typeface.DEFAULT)
            "fangsong" -> runCatching { android.graphics.Typeface.create("FangSong", android.graphics.Typeface.NORMAL) }.getOrDefault(android.graphics.Typeface.DEFAULT)
            "xiaobiao" -> runCatching { android.graphics.Typeface.create("FZXiaoBiaoSong-B05S", android.graphics.Typeface.NORMAL) }.getOrDefault(android.graphics.Typeface.DEFAULT)
            "lishu" -> runCatching { android.graphics.Typeface.create("LiSu", android.graphics.Typeface.NORMAL) }.getOrDefault(android.graphics.Typeface.DEFAULT)
            "yahei" -> runCatching { android.graphics.Typeface.create("Microsoft YaHei", android.graphics.Typeface.NORMAL) }.getOrDefault(android.graphics.Typeface.DEFAULT)
            "sans" -> android.graphics.Typeface.SANS_SERIF
            else -> android.graphics.Typeface.DEFAULT
        }
    }

    // ---- 导出功能 ----

    /** Android 9 及以下需要 WRITE_EXTERNAL_STORAGE 权限，先检查再导出 */
    private fun requestStorageAndExportImage() {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_WRITE_STORAGE)
        } else {
            exportLongImage()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_WRITE_STORAGE) {
            if (grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                exportLongImage()
            } else {
                Toast.makeText(this, getString(R.string.export_image_failed, "需要存储权限"), Toast.LENGTH_LONG).show()
            }
        }
    }

    /** 导出当前渲染内容为长图片（滚动截图拼接） */
    private fun exportLongImage() {
        if (currentMode != "preview") {
            toggleMode()
            webView.postDelayed({ exportLongImage() }, 300)
            return
        }
        Toast.makeText(this, R.string.export_image_saving, Toast.LENGTH_SHORT).show()
        // 通过 JS 获取内容实际高度
        webView.evaluateJavascript(
            "(function(){ return JSON.stringify({h: document.documentElement.scrollHeight, w: document.documentElement.clientWidth, vh: window.innerHeight}); })()"
        ) { result ->
            try {
                val json = org.json.JSONObject(result.trim().removeSurrounding("\"").replace("\\\"", "\""))
                val contentHeight = json.getInt("h")
                val viewWidth = json.getInt("w")
                val viewHeight = json.getInt("vh")
                if (contentHeight <= 0 || viewHeight <= 0) {
                    Toast.makeText(this, getString(R.string.export_image_failed, "内容为空"), Toast.LENGTH_LONG).show()
                    return@evaluateJavascript
                }

                // 限制最大高度防止 OOM（32768px ≈ 128MB @1080px，现代设备可承受）
                // 正常长文档（<32768px）使用 scale=1.0 保证完整清晰度
                val maxBitmapHeight = 32768
                val scale = if (contentHeight > maxBitmapHeight) maxBitmapHeight.toFloat() / contentHeight else 1f
                val finalHeight = (contentHeight * scale).toInt()
                val finalWidth = (viewWidth * scale).toInt()
                val bgColor = if (prefs.isDark(this)) 0xFF0D1117.toInt() else 0xFFFFFFFF.toInt()
                val savedTitle = currentTitle

                Thread {
                    try {
                        val bitmap = Bitmap.createBitmap(finalWidth, finalHeight, Bitmap.Config.ARGB_8888)
                        val canvas = Canvas(bitmap)
                        canvas.drawColor(bgColor)

                        if (contentHeight <= viewHeight) {
                            // 内容不超过一屏，直接截图
                            val latch = CountDownLatch(1)
                            runOnUiThread {
                                webView.scrollTo(0, 0)
                                webView.postDelayed({
                                    webView.draw(canvas)
                                    latch.countDown()
                                }, 300)
                            }
                            latch.await(5, TimeUnit.SECONDS)
                        } else {
                            // 滚动截图拼接：使用 75% 视口高度作为步长，避免拼接缝隙
                            val step = (viewHeight * 0.75).toInt()
                            var scrollY = 0
                            while (scrollY < contentHeight) {
                                val currentScroll = scrollY
                                val srcTop = (scrollY * scale).toInt()
                                val srcBottom = minOf(((scrollY + viewHeight) * scale).toInt(), finalHeight)
                                if (srcBottom > srcTop) {
                                    val tempBmp = Bitmap.createBitmap(viewWidth, viewHeight, Bitmap.Config.ARGB_8888)
                                    val tempCanvas = Canvas(tempBmp)
                                    val latch = CountDownLatch(1)
                                    runOnUiThread {
                                        webView.scrollTo(0, currentScroll)
                                        // 等待 400ms 让 WebView 完成重绘（含图片/代码块等复杂内容）
                                        webView.postDelayed({
                                            tempCanvas.drawColor(bgColor)
                                            webView.draw(tempCanvas)
                                            val srcRect = android.graphics.Rect(0, 0, viewWidth, viewHeight)
                                            val dstRect = android.graphics.Rect(0, srcTop, finalWidth, srcBottom)
                                            canvas.drawBitmap(tempBmp, srcRect, dstRect, null)
                                            tempBmp.recycle()
                                            latch.countDown()
                                        }, 400)
                                    }
                                    latch.await(6, TimeUnit.SECONDS)
                                }
                                scrollY += step
                            }
                            // 确保最后一屏也被完整捕获
                            if (scrollY - step < contentHeight - viewHeight) {
                                val lastScroll = contentHeight - viewHeight
                                val srcTop = (lastScroll * scale).toInt()
                                val srcBottom = finalHeight
                                if (srcBottom > srcTop) {
                                    val tempBmp = Bitmap.createBitmap(viewWidth, viewHeight, Bitmap.Config.ARGB_8888)
                                    val tempCanvas = Canvas(tempBmp)
                                    val latch = CountDownLatch(1)
                                    runOnUiThread {
                                        webView.scrollTo(0, lastScroll)
                                        webView.postDelayed({
                                            tempCanvas.drawColor(bgColor)
                                            webView.draw(tempCanvas)
                                            val srcRect = android.graphics.Rect(0, 0, viewWidth, viewHeight)
                                            val dstRect = android.graphics.Rect(0, srcTop, finalWidth, srcBottom)
                                            canvas.drawBitmap(tempBmp, srcRect, dstRect, null)
                                            tempBmp.recycle()
                                            latch.countDown()
                                        }, 400)
                                    }
                                    latch.await(6, TimeUnit.SECONDS)
                                }
                            }
                            runOnUiThread { webView.scrollTo(0, 0) }
                        }

                        // 保存到 Pictures/MD阅读器/
                        val safeName = exportFileName(savedTitle)
                        val saved = saveImageToGallery(bitmap, safeName)
                        bitmap.recycle()

                        if (saved == null) {
                            runOnUiThread {
                                Toast.makeText(this, getString(R.string.export_image_failed, "无法创建文件"), Toast.LENGTH_LONG).show()
                            }
                            return@Thread
                        }

                        runOnUiThread {
                            Toast.makeText(this, getString(R.string.export_image_saved), Toast.LENGTH_LONG).show()
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            Toast.makeText(this, getString(R.string.export_image_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
                        }
                    }
                }.start()
            } catch (e: Exception) {
                Toast.makeText(this, getString(R.string.export_image_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
            }
        }
    }

    /** 导出当前渲染结果为独立 HTML 文件（保留加粗、表格、图片、Mermaid 等格式） */
    private fun exportHtml() {
        if (currentMode != "preview") {
            toggleMode()
            webView.postDelayed({ exportHtml() }, 300)
            return
        }
        // 获取 preview 的完整 innerHTML，通过 JSON 编码安全传回
        webView.evaluateJavascript(
            "(function(){ var el = document.getElementById('preview'); return el ? el.innerHTML : ''; })()"
        ) { htmlContent ->
            if (htmlContent.isNullOrBlank() || htmlContent == "null" || htmlContent == "\"\"") {
                Toast.makeText(this, getString(R.string.export_html_failed, "内容为空"), Toast.LENGTH_LONG).show()
                return@evaluateJavascript
            }
            try {
                // 安全解码 JS 返回的 JSON 字符串
                var cleanHtml = decodeJsString(htmlContent)

                // 将 vault 图片 URL 转换为 base64 data URI，使导出 HTML 自包含
                cleanHtml = convertVaultImagesToBase64(cleanHtml)

                val fullHtml = buildString {
                    appendLine("<!DOCTYPE html>")
                    appendLine("<html lang=\"zh-CN\">")
                    appendLine("<head>")
                    appendLine("<meta charset=\"utf-8\">")
                    appendLine("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">")
                    appendLine("<title>${escapeHtml(currentTitle)}</title>")
                    appendLine("<style>")
                    appendLine(EXPORT_CSS)
                    appendLine("</style>")
                    appendLine("<script src=\"https://cdn.jsdelivr.net/npm/mermaid@10/dist/mermaid.min.js\"></script>")
                    appendLine("</head>")
                    appendLine("<body>")
                    appendLine("<div class=\"markdown-body\">")
                    appendLine(cleanHtml)
                    appendLine("</div>")
                    appendLine("<script>if(window.mermaid)mermaid.init();</script>")
                    appendLine("</body>")
                    appendLine("</html>")
                }

                val safeName = exportFileName(currentTitle)
                val saved = saveHtmlToGallery(fullHtml, safeName)

                if (saved == null) {
                    runOnUiThread {
                        Toast.makeText(this, getString(R.string.export_html_failed, "无法创建文件"), Toast.LENGTH_LONG).show()
                    }
                    return@evaluateJavascript
                }

                runOnUiThread {
                    Toast.makeText(this, getString(R.string.export_html_saved), Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.export_html_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** 将 HTML 中的 vault 图片 URL 替换为 base64 data URI */
    private fun convertVaultImagesToBase64(html: String): String {
        val vaultBase = "https://appassets.androidplatform.net/vault/"
        val vaultUri = prefs.vaultUri ?: return html
        if (!html.contains(vaultBase)) return html

        return IMG_SRC_REGEX.replace(html) { match ->
            val prefix = match.groupValues[1]
            val src = match.groupValues[2]
            val suffix = match.groupValues[3]
            if (src.startsWith(vaultBase)) {
                val fileName = java.net.URLDecoder.decode(src.removePrefix(vaultBase), "UTF-8")
                val dataUri = vaultImageToBase64(vaultUri, fileName)
                if (dataUri != null) "$prefix$dataUri$suffix" else match.value
            } else {
                match.value
            }
        }
    }

    /** 从 Vault 读取图片并转为 base64 data URI */
    private fun vaultImageToBase64(vaultUriStr: String, fileName: String): String? {
        return try {
            val vaultUri = Uri.parse(vaultUriStr)
            val asset = VaultSearch.resolveRelativeAsset(this, vaultUri, currentUri?.let { Uri.parse(it) }, fileName)
                ?: return null
            val mimeType = when {
                fileName.endsWith(".png", true) -> "image/png"
                fileName.endsWith(".jpg", true) || fileName.endsWith(".jpeg", true) -> "image/jpeg"
                fileName.endsWith(".gif", true) -> "image/gif"
                fileName.endsWith(".webp", true) -> "image/webp"
                fileName.endsWith(".svg", true) -> "image/svg+xml"
                fileName.endsWith(".bmp", true) -> "image/bmp"
                else -> "image/png"
            }
            val bytes = contentResolver.openInputStream(asset.uri)?.use { it.readBytes() } ?: return null
            val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            "data:$mimeType;base64,$b64"
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 安全解码 evaluateJavascript 返回的 JSON 字符串。
     * evaluateJavascript 返回值是一个 JSON 编码的字符串（带外层引号，内部转义）。
     * 特别注意：WebView 会将 < > 编码为 \u003C \u003E，必须显式还原。
     */
    private fun decodeJsString(raw: String): String {
        val decoded = try {
            // raw 形如 "\"<div>...</div>\""，用 JSONArray 安全解码
            val arr = org.json.JSONArray("[$raw]")
            arr.getString(0)
        } catch (e: Exception) {
            // 降级：手动去除外层引号和转义
            raw.trim()
                .removeSurrounding("\"")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\n", "\n")
                .replace("\\/", "/")
        }
        // WebView evaluateJavascript 总是将 < > / 编码为 unicode 转义
        // JSONArray 解码后有时仍保留这些转义，需要显式还原
        return decoded
            .replace("\\u003C", "<")
            .replace("\\u003c", "<")
            .replace("\\u003E", ">")
            .replace("\\u003e", ">")
            .replace("\\u0026", "&")
    }

    /** 获取导出目录（Android 9 及以下使用公共目录） */
    private fun getExportDir(subDir: String): File {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val target = File(dir, "MD阅读器/$subDir")
        target.mkdirs()
        return target
    }

    /** 保存长图片到 Download/MD阅读器/Picture/ （Android 10+ 用 MediaStore，9 及以下用 File） */
    private fun saveImageToGallery(bitmap: Bitmap, fileName: String): File? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+：使用 MediaStore 保存到 Download/MD阅读器/Picture/
            val resolver = contentResolver
            val contentValues = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, "$fileName.png")
                put(android.provider.MediaStore.Downloads.MIME_TYPE, "image/png")
                put(android.provider.MediaStore.Downloads.RELATIVE_PATH, "Download/MD阅读器/Picture")
            }
            val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                ?: return null
            resolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            return File(uri.path ?: "")
        } else {
            // Android 9 及以下：直接写文件
            val dir = getExportDir("Picture")
            val file = File(dir, "$fileName.png")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            scanExportedFile(file)
            return file
        }
    }

    /** 保存 HTML 到 Download/MD阅读器/HTML/ （Android 10+ 用 MediaStore，9 及以下用 File） */
    private fun saveHtmlToGallery(htmlContent: String, fileName: String): File? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = contentResolver
            val contentValues = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, "$fileName.html")
                put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/html")
                put(android.provider.MediaStore.Downloads.RELATIVE_PATH, "Download/MD阅读器/HTML")
            }
            val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                ?: return null
            resolver.openOutputStream(uri, "wt")?.use { out ->
                out.write(htmlContent.toByteArray(Charsets.UTF_8))
            }
            return File(uri.path ?: "")
        } else {
            val dir = getExportDir("HTML")
            val file = File(dir, "$fileName.html")
            FileOutputStream(file).use { out ->
                out.write(htmlContent.toByteArray(Charsets.UTF_8))
            }
            scanExportedFile(file)
            return file
        }
    }

    /** 生成导出文件名：日期前缀 + 笔记名，仅保留文件系统允许的字符 */
    private fun exportFileName(title: String): String {
        val datePrefix = java.text.SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(java.util.Date())
        var name = title.trim().ifEmpty { "document" }
        // 仅去除文件系统禁止的字符：\ / : * ? " < > | 和换行
        name = name.replace(FILENAME_UNSAFE_REGEX, "")
        // 去除首尾空格和点（Windows 不允许）
        name = name.trim(' ', '.')
        if (name.isEmpty()) name = "document"
        // 限制长度防止文件名过长（日期前缀8字符 + 下划线 + 名称）
        if (name.length > 80) name = name.substring(0, 80)
        return "${datePrefix}_${name}"
    }

    /** 通知系统媒体扫描器，使导出文件在「下载」中可见 */
    private fun scanExportedFile(file: File) {
        try {
            val uri = Uri.fromFile(file)
            val intent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, uri)
            sendBroadcast(intent)
        } catch (_: Exception) {
            // 忽略扫描失败，文件已成功保存
        }
    }

    private fun escapeHtml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    companion object {
        private const val ASSET_HOST = "appassets.androidplatform.net"
        private const val VIEWER_URL = "https://$ASSET_HOST/assets/viewer.html"
        private const val REQ_WRITE_STORAGE = 1001

        /** 预编译 Regex：文件名非法字符替换 */
        private val FILENAME_UNSAFE_REGEX = Regex("""[\\/:*?"<>|\r\n]""")
        /** 预编译 Regex：HTML 中 img 标签 src 提取 */
        private val IMG_SRC_REGEX = Regex("""(<img[^>]+src=")([^"]+)("[^>]*>)""")

        /** 导出 HTML 时内嵌的样式表 */
        private val EXPORT_CSS = """
:root { --font-size: 16px; --line-height: 1.7; --para-gap: 1em; --max-width: 880px;
  --bg: #ffffff; --fg: #1f2328; --muted: #656d76; --border: #d0d7de;
  --code-bg: #f6f8fa; --code-fg: #1f2328; --link: #0969da; --table-stripe: #f6f8fa; }
* { box-sizing: border-box; }
body { background: var(--bg); color: var(--fg); font-family: -apple-system, "PingFang SC", "Microsoft YaHei", "Noto Sans CJK SC", "Helvetica Neue", Arial, sans-serif;
  font-size: var(--font-size); line-height: var(--line-height); margin: 0; padding: 0; }
.markdown-body { max-width: var(--max-width); margin: 0 auto; padding: 24px 16px 48px; }
.markdown-body p, .markdown-body ul, .markdown-body ol, .markdown-body blockquote,
.markdown-body table, .markdown-body pre, .markdown-body dl { margin-top: 0; margin-bottom: var(--para-gap); }
.markdown-body h1, .markdown-body h2, .markdown-body h3, .markdown-body h4, .markdown-body h5, .markdown-body h6 { margin: 1.4em 0 0.6em; line-height: 1.3; font-weight: 600; }
.markdown-body h1 { font-size: 1.8em; padding-bottom: 0.3em; border-bottom: 1px solid var(--border); }
.markdown-body h2 { font-size: 1.5em; padding-bottom: 0.3em; border-bottom: 1px solid var(--border); }
.markdown-body h3 { font-size: 1.25em; }
.markdown-body a { color: var(--link); text-decoration: none; }
.markdown-body ul, .markdown-body ol { padding-left: 2em; }
.markdown-body blockquote { margin-left: 0; margin-right: 0; padding: 0 1em; color: var(--muted); border-left: 0.25em solid var(--border); }
.markdown-body img { max-width: 100%; height: auto; }
.markdown-body code { font-family: ui-monospace, SFMono-Regular, "SF Mono", Menlo, Consolas, monospace; background: var(--code-bg); padding: 0.2em 0.4em; border-radius: 6px; font-size: 0.88em; }
.markdown-body pre { background: var(--code-bg); padding: 14px 16px; border-radius: 8px; overflow: auto; line-height: 1.5; }
.markdown-body pre code { background: transparent; padding: 0; font-size: 0.86em; }
.markdown-body table { border-collapse: collapse; width: 100%; }
.markdown-body th, .markdown-body td { border: 1px solid var(--border); padding: 6px 13px; }
.markdown-body th { font-weight: 600; background: var(--table-stripe); }
.markdown-body tr:nth-child(2n) td { background: var(--table-stripe); }
.markdown-body hr { border: 0; height: 1px; background: var(--border); margin: 1.6em 0; }
.ob-highlight { background: #fff176; border-radius: 2px; padding: 0 2px; }
.ob-tag { display: inline-block; font-size: 0.82em; color: var(--link); background: rgba(9,105,218,0.12); border-radius: 10px; padding: 1px 7px; }
.task-list-item { list-style: none; margin-left: -1.4em; }
.task-checkbox { margin-right: 0.4em; }
.callout { border-left: 4px solid var(--link); border-radius: 6px; background: var(--code-bg); padding: 0; margin-bottom: var(--para-gap); overflow: hidden; }
.callout-title { display: flex; align-items: center; gap: 6px; padding: 8px 14px; font-weight: 600; background: rgba(0,0,0,0.06); border-bottom: 1px solid var(--border); }
.callout > *:not(.callout-title) { padding: 8px 14px; margin: 0; }
.frontmatter { margin-bottom: var(--para-gap); border: 1px solid var(--border); border-radius: 8px; background: var(--code-bg); }
.frontmatter table { width: 100%; border-collapse: collapse; display: table; }
.frontmatter .fm-key { font-weight: 600; color: var(--muted); font-size: 0.85em; padding: 5px 12px; border: 1px solid var(--border); width: 30%; }
.frontmatter .fm-val { padding: 5px 12px; font-size: 0.88em; border: 1px solid var(--border); }
.frontmatter .fm-tag { display: inline-block; background: var(--link); color: #fff; border-radius: 4px; padding: 1px 7px; font-size: 0.8em; margin: 2px 3px; }
.markdown-body mark { background: #fff176; border-radius: 2px; padding: 0 2px; }
.markdown-body .ob-highlight { background: #fff176; border-radius: 2px; padding: 0 2px; }
.mermaid-container { text-align: center; margin: var(--para-gap) 0; overflow: auto; }
.mermaid-container svg { max-width: 100%; height: auto; }
.markdown-body details { margin-bottom: var(--para-gap); border: 1px solid var(--border); border-radius: 6px; padding: 8px 12px; }
.markdown-body details summary { cursor: pointer; font-weight: 600; }
.markdown-body details[open] summary { margin-bottom: 8px; }
.footnotes { font-size: 0.88em; color: var(--muted); border-top: 1px solid var(--border); margin-top: 2em; padding-top: 1em; }
.footnotes ol { padding-left: 1.5em; }
        """.trimIndent()

        private val WELCOME_MD = """
# 欢迎使用 MD 阅读器

这是一个功能丰富的本地 **Markdown 阅读器**（v2.1.1），支持多种文档格式与 Obsidian 兼容语法。

## 快速上手

- 点击 **目录** 唤出大纲，点击标题快速跳转
- 点击 **搜索** 打开搜索栏（再次点击关闭），输入关键词搜索，支持全库搜索切换
- 点击 **源码 / 预览** 切换呈现方式；预览模式点击标题可折叠/展开
- **点击屏幕中央** 调出「显示设置」
- 在设置中选择 **Vault 文件夹** 后，可使用 `[[wikilink]]` 导航与全库搜索
- 重新打开同一文档时，会自动回到上次阅读位置（断点续读）
- 启动时自动弹出打开历史，快速继续阅读
- 点击右上角 **⋮** 菜单：转发分享、收藏、添加桌面快捷方式、导出长图/HTML 等
- 设置中可手动 **检查更新**，下载最新版本

## 支持的 Markdown 语法

### 标题

```
# 一级标题
## 二级标题
### 三级标题
```

支持 H1 ~ H6 六级标题，预览模式下点击标题可 **折叠/展开** 下方内容。

### 列表

无序列表：

```
- 苹果
- 香蕉
- 橘子
```

有序列表：

```
1. 第一步
2. 第二步
3. 第三步
```

任务列表：

```
- [x] 已完成的任务
- [ ] 未完成的任务
```

### 表格

```
| 功能 | 状态 |
| --- | :---: |
| Markdown 渲染 | ✅ |
| 代码高亮 | ✅ |
```

### 代码块

````
```kotlin
fun main() {
    println("Hello, MD Reader!")
}
```
````

支持 100+ 种编程语言的语法高亮，右上角有 **复制按钮**。支持 `plaintext`/`text` 等纯文本代码块跳过语法高亮。

### 引用块

```
> 这是一段引用文字
> 可以多行书写
```

支持 20+ 种 Callout 类型（NOTE、TIP、WARNING、DANGER、ERROR、SUCCESS、QUESTION、ABSTRACT、BUG、QUOTE、EXAMPLE、TODO、IMPORTANT 等）。

### 链接与图片

```
[链接文字](https://example.com)
![图片描述](image.png)
```

### 分隔线

```
---
```

### 行内样式

```
**粗体** *斜体* ~~删除线~~ `行内代码` ==高亮标记==
```

## Obsidian 兼容语法

### Wikilinks

```
[[另一篇笔记]]
[[目录/子目录/文件|显示名称]]
[[#某个标题]]
```

设置 Vault 文件夹后，点击 wikilink 可跳转到对应笔记。

### 嵌入文件

```
![[图片.png]]
![[视频.mp4]]
![[另一篇笔记]]
```

图片直接显示，视频可播放，其他文档可展开查看内容。

### Callout 标注

```
> [!NOTE] 提示
> 这是一条提示信息

> [!WARNING] 注意
> 这是一条警告信息

> [!TIP] 建议
> 这是一条建议信息
```

### ==高亮==

```
这是 ==高亮== 的文字
```

### #标签

```
这是一段带有 #标签 和 #阅读/笔记 的文字
```

渲染为圆角标签胶囊样式（蓝色），不影响标题层级。

### %%注释%%

```
这段文字 %%会被隐藏%% 不会显示
```

### 脚注

```
这是一段正文[^1]。

[^1]: 这是脚注的内容。
```

行内渲染为上标链接，文末自动生成脚注列表，支持反向跳回。

### YAML Frontmatter

```
---
title: 文档标题
tags:
  - 阅读
  - 笔记
date: 2024-01-01
---
```

自动解析并以表格形式显示在文档顶部，可在设置中开关。

## Mermaid 图表

````
```mermaid
graph TD
    A[开始] --> B{判断}
    B -->|是| C[执行]
    B -->|否| D[结束]
```
````

支持流程图、时序图、甘特图、饼图、类图等 Mermaid 图表。单击可全屏预览，支持双指缩放。

## 支持的文档格式

| 格式 | 说明 |
| --- | --- |
| `.md` / `.markdown` | Markdown 文件 |
| `.txt` | 纯文本（UTF-8 / GBK 自动识别） |
| `.docx` | Word 文档（OOXML 格式，含表格渲染） |
| `.doc` | Word 97-2003 文档（OLE2 格式） |
| `.pdf` | PDF 文件（逐页渲染为图片，支持缩放翻页） |

## 阅读设置

- **字号**：12px ~ 30px 自由调节
- **行间距**：1.0 ~ 2.4 倍
- **段间距**：0 ~ 2.0 倍
- **主题**：跟随系统 / 浅色 / 深色
- **护眼模式**：暖色羊皮纸背景，减轻视觉疲劳
- **字体**：默认 / 宋体 / 等宽 / 黑体 / 楷体 / 仿宋 / 小标宋 / 隶书 / 微软雅黑（九种）
- **Frontmatter 显示**：可开关元数据表格
- **引用块样式**：可开关引用块渲染
- **隐藏文件名标题**：正文中与文件名相同的一级标题自动隐藏

## 特色功能

- **阅读标注**：手指绘画标注，支持 6 种颜色和 4 种模式（自由画笔/荧光笔/圈注/波浪线）
- **字符统计**：点击标题栏查看总字符、纯文字（去除标点和 Markdown 语法）、总行数、代码字符数
- **表格预览**：单击表格全屏预览，支持双指缩放和预览内下载
- **源码直接编辑**：源码模式即可编辑，停止输入 2 秒后自动保存
- **导出长图片**：滚动截图拼接（最大 32768px），保存至 `Download/MD阅读器/Picture`
- **导出 HTML**：包含完整样式的独立 HTML 文件，保存至 `Download/MD阅读器/HTML`
- **转发分享**：一键把完整文档经系统分享转发到微信等应用
- **桌面快捷方式**：将当前文档添加为桌面快捷方式，一键直达
- **收藏夹**：收藏常用文档，原文件被删除后仍可打开
- **打开历史**：记录最近 200 条打开文件，支持单条删除
- **文内搜索**：在当前文档中搜索关键词，高亮显示并逐个定位
- **全库搜索**：在 Vault 文件夹中搜索所有文档
- **自动更新**：启动时自动检查 GitHub Release 更新，也可手动检查

---

打开一个文件开始阅读吧
        """.trimIndent()
    }
}
