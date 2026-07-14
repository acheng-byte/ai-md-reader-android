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
import java.net.URLDecoder
import java.util.Locale
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
    private lateinit var backCallback: OnBackPressedCallback

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
                    Logger.i("MainActivity", "另存为: ${FileUtils.displayName(this, uri) ?: currentTitle}")
                    Toast.makeText(this, R.string.edit_saved, Toast.LENGTH_SHORT).show()
                }
            }
        }

    private val vaultPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            if (uri != null) {
                val encoded = VaultSearch.ensureEncoded(uri)
                runCatching {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                }.onFailure {
                    Logger.e("MainActivity", "文件夹权限获取失败: ${it.message}")
                }
                prefs.vaultUri = encoded.toString()
                VaultSearch.clearCache()
                val vaultName = DocumentFile.fromTreeUri(this, encoded)?.name ?: "未知"
                Logger.i("MainActivity", "设置库文件夹: $vaultName")
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
        // 启动时仅检查 Vault 根目录有效性（后台，不阻塞 UI）
        // 文件索引加载移到文档打开后延迟 3 秒执行，确保文档先渲染
        val vaultUriStr = prefs.vaultUri
        if (vaultUriStr != null) {
            Thread {
                try {
                    val encoded = VaultSearch.ensureEncoded(Uri.parse(vaultUriStr))
                    val root = androidx.documentfile.provider.DocumentFile.fromTreeUri(this, encoded)
                    if (root == null) {
                        Logger.e("MainActivity", "Vault 根目录无效 — 请重新选择文件夹")
                    }
                } catch (_: Exception) {}
            }.start()
        }
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

        // 文档加载后延迟 3 秒再启动库索引（确保文档先渲染，不抢 I/O）
        val vaultUriForIndex = prefs.vaultUri
        if (vaultUriForIndex != null) {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                Thread {
                    try {
                        val encoded = VaultSearch.ensureEncoded(Uri.parse(vaultUriForIndex))
                        // 先尝试从磁盘加载缓存（瞬间），失败则后台扫描
                        if (!VaultIndex.loadFromDisk(this, encoded)) {
                            VaultIndex.scanInBackground(this, encoded)
                        }
                    } catch (_: Exception) {}
                }.start()
            }, 3000)
        }

        // Back press: 图片预览关闭 / 源码模式放弃确认 / 预览模式退出确认
        backCallback = object : OnBackPressedCallback(true) {
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
                    // 预览模式：先检查是否有图片预览 overlay 打开
                    if (!pageReady) return@handleOnBackPressed
                    try {
                        webView.evaluateJavascript("window.isPreviewOverlayOpen && window.isPreviewOverlayOpen()") { result ->
                            val overlayOpen = result?.trim()?.removeSurrounding("\"") == "true"
                            if (overlayOpen) {
                                // 关闭图片预览
                                try {
                                    webView.evaluateJavascript("window.closeImagePreview && window.closeImagePreview()", null)
                                } catch (_: Exception) {}
                            } else {
                                // 确认是否退出
                                confirmExit()
                            }
                        }
                    } catch (_: Exception) {
                        confirmExit()
                    }
                }
            }
        }
        onBackPressedDispatcher.addCallback(this, backCallback)

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
            .addPathHandler("/vault/", VaultPathHandler())
            .build()

        webView.webViewClient = object : WebViewClientCompat() {
            override fun shouldInterceptRequest(
                view: WebView, request: WebResourceRequest
            ): WebResourceResponse? {
                val url = request.url
                if (url.host != ASSET_HOST) return null
                val path = try {
                    URLDecoder.decode(url.path?.trimStart('/') ?: "", "UTF-8")
                } catch (_: Exception) { return null }

                // /vault/ 路径 → 直接从 Vault 加载
                if (path.startsWith("vault/")) {
                    return assetLoader.shouldInterceptRequest(url)
                }

                // /assets/ 路径 → 先查 Vault，找不到再回退 APK 捆绑资源
                if (path.startsWith("assets/")) {
                    val relativePath = path.removePrefix("assets/")
                    if (relativePath.isEmpty()) return null

                    // 先查 Vault（用户文件优先）
                    val vaultUriStr = prefs.vaultUri
                    if (vaultUriStr != null && relativePath.isNotEmpty()) {
                        val vaultUri = VaultSearch.ensureEncoded(Uri.parse(vaultUriStr))
                        val curDocUri = runCatching { currentDocumentUri }.getOrNull()
                        val found = runCatching {
                            // 优先查当前文档所在目录（快速定向查找）
                            VaultSearch.resolveRelativeAsset(this@MainActivity, vaultUri, curDocUri, relativePath)
                                ?: VaultSearch.findAssetInVault(this@MainActivity, vaultUri, relativePath)
                        }.getOrNull()
                        if (found != null) {
                            val mime = guessMime(relativePath)
                            val stream = runCatching { contentResolver.openInputStream(found.uri) }.getOrNull()
                            if (stream != null) return WebResourceResponse(mime, null, stream)
                        }
                    }

                    // 回退：APK 捆绑资源（viewer.html / app.js / 第三方库等）
                    return assetLoader.shouldInterceptRequest(url)
                }

                return null
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
                // 拦截指向 .md 文件的 asset URL，转为 vault 内导航
                if (url.host == ASSET_HOST) {
                    val path = URLDecoder.decode(url.path?.trimStart('/') ?: "", "UTF-8")
                    val assetPath = path.removePrefix("assets/").removePrefix("vault/")
                    if (assetPath.endsWith(".md", ignoreCase = true) || assetPath.endsWith(".markdown", ignoreCase = true)) {
                        runOnUiThread { openWikiLink(assetPath) }
                        return true
                    }
                    return false
                }
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
            val mime = guessMime(filename)

            val vaultUriStr = prefs.vaultUri
            if (vaultUriStr != null) {
                val vaultUri = VaultSearch.ensureEncoded(Uri.parse(vaultUriStr))

                // 用缓存快速查找（全库文件名匹配 + 路径导航）
                val file = runCatching {
                    VaultSearch.findAssetInVault(this@MainActivity, vaultUri, filename)
                }.getOrNull()
                if (file != null) {
                    val stream = contentResolver.openInputStream(file.uri)
                    if (stream != null) return WebResourceResponse(mime, null, stream)
                }

                // 回退：从当前文档所在目录加载（支持非 vault 内的本地图片）
                val docUri = currentDocumentUri
                if (docUri != null) {
                    val found = runCatching {
                        val docFile = DocumentFile.fromSingleUri(this@MainActivity, docUri)
                        val parent = docFile?.parentFile ?: return@runCatching null
                        VaultSearch.findFileInDir(this@MainActivity, vaultUri, parent, filename)
                    }.getOrNull()
                    if (found != null) {
                        val stream = contentResolver.openInputStream(found.uri)
                        if (stream != null) return WebResourceResponse(mime, null, stream)
                    }
                }
            }

            return null
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
                    Logger.i("MainActivity", "打开文档: $name")
                    startReadingSession(name)
                    renderCurrent()
                    invalidateOptionsMenu()
                }.onFailure { e ->
                    Logger.e("MainActivity", "打开文档失败: ${e.message}")
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
            R.id.action_settings, R.id.action_reading_stats,
            R.id.action_annotate, R.id.action_log
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
                Logger.i("MainActivity", "保存文档: $currentTitle")
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
        R.id.action_settings -> { showSettings(); true }
        R.id.action_reading_stats -> { showReadingStats(); true }
        R.id.action_annotate -> { toggleAnnotationMode(); true }
        R.id.action_annotate_color -> { cycleAnnotationColor(); true }
        R.id.action_annotate_mode -> { cycleAnnotationMode(); true }
        R.id.action_annotate_undo -> { binding.annotationOverlay.undo(); true }
        R.id.action_annotate_clear -> {
            binding.annotationOverlay.clearAll()
            Toast.makeText(this, R.string.annotate_cleared, Toast.LENGTH_SHORT).show()
            true
        }
        R.id.action_log -> { showLogViewer(); true }
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
            Logger.i("MainActivity", "标注模式: 开启 (${annotationModeName(annotationMode)})")
        } else {
            overlay.annotationEnabled = false
            overlay.visibility = View.GONE
            // 取消待执行的防抖保存，立即异步保存最终状态
            annotationSaveRunnable?.let { settingsHandler.removeCallbacks(it) }
            currentUri?.let { uri ->
                val strokesCopy = overlay.exportStrokes()
                Thread { annotations.save(uri, strokesCopy) }.start()
                Logger.i("MainActivity", "标注已保存 (${strokesCopy.size} 笔)")
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
        Logger.i("MainActivity", "标注颜色: $annotationColor")
        invalidateOptionsMenu()
    }

    private fun cycleAnnotationMode() {
        modeIndex = (modeIndex + 1) % annotationModes.size
        annotationMode = annotationModes[modeIndex]
        binding.annotationOverlay.drawMode = annotationMode
        Logger.i("MainActivity", "标注模式: ${annotationModeName(annotationMode)}")
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
            // PDF 不支持编辑，仅允许查看
            val ext = currentTitle.substringAfterLast('.', "").lowercase()
            val name = currentDocumentUri?.let { FileUtils.displayName(this, it) }?.lowercase() ?: ""
            val isPdf = name.endsWith(".pdf") || ext == "pdf"
            if (isPdf) {
                Toast.makeText(this, "PDF 文档不支持编辑，仅可查看", Toast.LENGTH_SHORT).show()
                return
            }
            // 切换到源码模式：显示可编辑的 editText
            currentMode = "code"
            prefs.viewMode = currentMode
            Logger.i("MainActivity", "切换到编辑模式")
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

    /** 退出确认对话框，防止误触返回键退出 */
    private fun confirmExit() {
        AlertDialog.Builder(this)
            .setTitle("退出阅读")
            .setMessage("确定要退出当前文档吗？")
            .setPositiveButton("退出") { _, _ ->
                backCallback.isEnabled = false
                finish()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** Try to write [text] back to the current URI in-place. Returns true on success.
     *  Refuses for binary formats (DOC/DOCX/PDF) — writing markdown into them would corrupt the file. */
    private fun trySaveInPlace(text: String): Boolean {
        val uri = currentDocumentUri ?: return false
        // 检查当前文件是否为二进制格式（DOC/DOCX/PDF），这些格式不支持原地保存
        val ext = currentTitle.substringAfterLast('.', "").lowercase()
        val name = FileUtils.displayName(this, uri)?.lowercase() ?: ""
        val mime = contentResolver.getType(uri)?.lowercase() ?: ""
        val isBinaryFormat = name.endsWith(".doc") || name.endsWith(".docx") ||
            name.endsWith(".pdf") || ext == "doc" || ext == "docx" || ext == "pdf" ||
            mime.startsWith("application/pdf") || mime.startsWith("application/msword") ||
            mime.contains("officedocument")
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
            Logger.i("MainActivity", "取消收藏: $currentTitle")
            Toast.makeText(this, R.string.fav_removed, Toast.LENGTH_SHORT).show()
        } else {
            val ok = favorites.add(id, currentTitle, currentMarkdown.toByteArray(Charsets.UTF_8)) != null
            if (ok) Logger.i("MainActivity", "添加收藏: $currentTitle")
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
                Logger.i("MainActivity", "字体: ${fontKeys[position]}")
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
                val treeUri = Uri.parse(vaultStr)
                val docFile = DocumentFile.fromTreeUri(this, treeUri)
                val name = docFile?.name
                if (!name.isNullOrBlank()) {
                    name
                } else {
                    // 回退：从 URI 路径提取文件夹名
                    treeUri.lastPathSegment?.substringAfterLast(':')
                        ?: treeUri.lastPathSegment?.substringAfterLast('/')
                        ?: vaultStr
                }
            }.getOrDefault(vaultStr)
        } else getString(R.string.vault_not_set)

        sheet.btnSelectVault.setOnClickListener { vaultPicker.launch(null) }

        sheet.btnClearVault.setOnClickListener {
            prefs.vaultUri = null
            VaultSearch.clearCache()
            VaultIndex.clear()
            sheet.tvVaultPath.text = getString(R.string.vault_not_set)
            Logger.i("MainActivity", "清空库文件夹")
            Toast.makeText(this, "库文件夹已清空", Toast.LENGTH_SHORT).show()
        }

        sheet.btnRescanVault.setOnClickListener {
            val vaultStr = prefs.vaultUri
            if (vaultStr == null) {
                Toast.makeText(this, R.string.vault_not_set_toast, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            Toast.makeText(this, R.string.rescan_started, Toast.LENGTH_SHORT).show()
            VaultIndex.clear()
            val encoded = VaultSearch.ensureEncoded(Uri.parse(vaultStr))
            VaultIndex.scanInBackground(this, encoded) {
                runOnUiThread {
                    val count = VaultIndex.entryCount()
                    Toast.makeText(this, getString(R.string.rescan_done, count), Toast.LENGTH_SHORT).show()
                }
            }
            Logger.i("MainActivity", "手动触发重新扫描库")
        }

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
                val themeName = when (prefs.themeMode) { 1 -> "浅色"; 2 -> "深色"; else -> "跟随系统" }
                Logger.i("MainActivity", "主题切换: $themeName")
                // 优化：避免 recreate()，直接通过 JS + 颜色更新切换主题，减少卡顿
                val nightMode = when (prefs.themeMode) {
                    1 -> AppCompatDelegate.MODE_NIGHT_NO
                    2 -> AppCompatDelegate.MODE_NIGHT_YES
                    else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
                AppCompatDelegate.setDefaultNightMode(nightMode)
                // 只更新颜色和 CSS，不重建 Activity
                applySettingsToWeb()
            }
        }
        sheet.switchEyeProtection.setOnCheckedChangeListener { _, checked ->
            prefs.eyeProtection = checked
            Logger.i("MainActivity", "护眼模式: ${if (checked) "开" else "关"}")
            applySettingsToWeb()
        }
        sheet.switchFrontmatter.setOnCheckedChangeListener { _, checked ->
            prefs.showFrontmatter = checked
            Logger.i("MainActivity", "显示 Frontmatter: ${if (checked) "开" else "关"}")
            applySettingsToWeb()
        }
        sheet.switchCitations.setOnCheckedChangeListener { _, checked ->
            prefs.showCitations = checked
            Logger.i("MainActivity", "显示引用块: ${if (checked) "开" else "关"}")
            applySettingsToWeb()
        }
        sheet.switchHideTitle.setOnCheckedChangeListener { _, checked ->
            prefs.hideTitleHeading = checked
            Logger.i("MainActivity", "隐藏标题: ${if (checked) "开" else "关"}")
            applySettingsToWeb()
        }

        // 版本号显示 & 检查更新
        val ver = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrDefault("?")
        sheet.tvVersionInfo.text = "v$ver"
        sheet.btnCheckUpdate.setOnClickListener { manualCheckForUpdates() }

        sheet.btnReset.setOnClickListener {
            prefs.fontSize = Prefs.DEFAULT_FONT
            prefs.lineHeight = Prefs.DEFAULT_LINE
            prefs.paraGap = Prefs.DEFAULT_PARA
            prefs.themeMode = Prefs.DEFAULT_THEME
            prefs.eyeProtection = false
            prefs.fontFamily = "default"
            prefs.showFrontmatter = false
            prefs.showCitations = false
            prefs.hideTitleHeading = true
            Logger.i("MainActivity", "设置已重置为默认值")
            // 更新 UI
            sheet.sliderFont.value = Prefs.DEFAULT_FONT
            sheet.sliderLine.value = Prefs.DEFAULT_LINE
            sheet.sliderPara.value = Prefs.DEFAULT_PARA
            sheet.toggleTheme.check(R.id.btn_theme_system)
            sheet.switchEyeProtection.isChecked = false
            sheet.spinnerFontFamily.setSelection(0)
            updateLabels(sheet)
            applySettingsToWeb()
            Toast.makeText(this, R.string.settings_reset_done, Toast.LENGTH_SHORT).show()
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

    /** 显示阅读统计对话框 */
    private fun showReadingStats() {
        val days = prefs.companionDays()
        val installStr = java.text.SimpleDateFormat("yyyy年M月d日", java.util.Locale.getDefault())
            .format(java.util.Date(prefs.installDate))
        val msg = buildString {
            appendLine(getString(R.string.reading_stats_companion, days))
            appendLine()
            appendLine(getString(R.string.reading_stats_active_days, prefs.activeDays))
            appendLine(getString(R.string.reading_stats_sessions, prefs.totalReadingSessions))
            appendLine(getString(R.string.reading_stats_minutes, prefs.totalReadingMinutes))
            appendLine(getString(R.string.reading_stats_books, prefs.totalBooksRead))
            appendLine()
            appendLine(getString(R.string.reading_stats_since, installStr))
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.reading_stats_title)
            .setMessage(msg)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

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
                    Logger.i("MainActivity", "删除历史记录: ${entry.name}")
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
            Logger.i("MainActivity", "清空历史记录")
            Toast.makeText(this, R.string.history_cleared, Toast.LENGTH_SHORT).show()
        }
        dialog.show()
    }

    /** 日志查看弹窗：倒序显示，默认仅警告/错误，支持切换全部。使用 RecyclerView 高效渲染 5000+ 条 */
    private fun showLogViewer() {
        var showAll = false
        val logEntries = mutableListOf<String>()
        logEntries.addAll(Logger.getSummaryEntries())

        val rvAdapter = object : androidx.recyclerview.widget.RecyclerView.Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): androidx.recyclerview.widget.RecyclerView.ViewHolder {
                val tv = android.widget.TextView(parent.context).apply {
                    textSize = 10f
                    typeface = android.graphics.Typeface.MONOSPACE
                    setPadding(0, 4, 0, 4)
                    setTextIsSelectable(true)
                }
                return object : androidx.recyclerview.widget.RecyclerView.ViewHolder(tv) {}
            }
            override fun onBindViewHolder(holder: androidx.recyclerview.widget.RecyclerView.ViewHolder, position: Int) {
                (holder.itemView as android.widget.TextView).text = logEntries.getOrElse(position) { "" }
            }
            override fun getItemCount(): Int = logEntries.size
        }

        val ctx = this@MainActivity
        val recyclerView = androidx.recyclerview.widget.RecyclerView(ctx).apply {
            layoutManager = LinearLayoutManager(ctx)
            setPadding(32, 16, 32, 8)
            isVerticalScrollBarEnabled = true
            adapter = rvAdapter
        }

        fun refreshEntries() {
            logEntries.clear()
            logEntries.addAll(if (showAll) Logger.getAllEntries() else Logger.getSummaryEntries())
            rvAdapter.notifyDataSetChanged()
            if (logEntries.isEmpty()) {
                // 空状态提示
                logEntries.add("暂无日志")
                rvAdapter.notifyDataSetChanged()
            }
        }
        refreshEntries()

        val errCount = Logger.errorCount()
        val titleSuffix = if (errCount > 0) " (${Logger.size()} 条, $errCount 个错误)" else " (${Logger.size()} 条)"

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("运行日志$titleSuffix")
            .setView(recyclerView)
            .setPositiveButton("复制全部") { _: android.content.DialogInterface, _: Int ->
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("MDReader Log", Logger.getAllText()))
                Toast.makeText(this, "已复制 ${Logger.size()} 条日志", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("清空") { _: android.content.DialogInterface, _: Int ->
                Logger.clear()
                refreshEntries()
                Toast.makeText(this, "日志已清空", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("全部", null) // null listener 防止自动关闭
            .create()

        dialog.setOnShowListener {
            val btn = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL)
            btn.setOnClickListener {
                showAll = !showAll
                btn.text = if (showAll) "仅错误" else "全部"
                refreshEntries()
                recyclerView.scrollToPosition(0)
            }
        }

        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.92).toInt(),
            (resources.displayMetrics.heightPixels * 0.7).toInt()
        )
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
        // 记录阅读时长：每次 pause 时计算本次会话时长
        recordReadingSession()
    }

    override fun onResume() {
        super.onResume()
        // 刷新 URI 持久化权限：修复手机重启后授权过期问题
        refreshUriPermissions()
        // 修复 Vault URI 乱码：检查并解码存储的 URI
        fixVaultUriEncoding()
    }

    /** 刷新所有历史文档的 URI 持久化权限，修复重启后授权过期问题 */
    private fun refreshUriPermissions() {
        Thread {
            try {
                for (entry in history.all()) {
                    try {
                        val uri = Uri.parse(entry.uri)
                        if (uri.scheme == "content") {
                            contentResolver.takePersistableUriPermission(
                                uri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION
                            )
                        }
                    } catch (_: Exception) {
                        // 某些 provider 不支持持久化权限，忽略
                    }
                }
                // 同时刷新 Vault URI 权限
                prefs.vaultUri?.let { vaultUriStr ->
                    try {
                        val vaultUri = VaultSearch.ensureEncoded(Uri.parse(vaultUriStr))
                        contentResolver.takePersistableUriPermission(
                            vaultUri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        )
                    } catch (_: Exception) {
                        // 忽略
                    }
                }
            } catch (_: Exception) {
                // 忽略整体刷新失败
            }
        }.start()
    }

    /** 规范化 Vault URI：确保非 ASCII 字符（如中文）已正确编码 */
    private fun fixVaultUriEncoding() {
        val vaultUri = prefs.vaultUri ?: return
        try {
            val encoded = VaultSearch.ensureEncoded(Uri.parse(vaultUri))
            val encodedStr = encoded.toString()
            if (encodedStr != vaultUri) {
                prefs.vaultUri = encodedStr
                Logger.i("MainActivity", "【URI规范化】已修复编码: ${encodedStr.take(80)}")
            }
        } catch (_: Exception) {
            // 忽略失败
        }
    }

    /** 记录阅读会话：更新活跃天数、阅读时长、场次等统计 */
    private var sessionStartTime: Long = 0L
    private var sessionDocName: String = ""

    private fun recordReadingSession() {
        if (sessionStartTime <= 0 || sessionDocName.isEmpty()) return
        val elapsed = (System.currentTimeMillis() - sessionStartTime) / 60000 // 分钟
        if (elapsed > 0) {
            prefs.totalReadingMinutes = prefs.totalReadingMinutes + elapsed.toInt()
        }
        // 更新活跃天数
        val today = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.ROOT).format(java.util.Date())
        if (prefs.lastActiveDate != today) {
            prefs.activeDays = prefs.activeDays + 1
            prefs.lastActiveDate = today
        }
    }

    private fun startReadingSession(docName: String) {
        // 记录上一次会话时长
        recordReadingSession()
        // 开始新会话
        sessionStartTime = System.currentTimeMillis()
        sessionDocName = docName
        prefs.totalReadingSessions = prefs.totalReadingSessions + 1
        // 更新触达文件数
        prefs.addKnownBook(docName)
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
            if (mode == "preview" || mode == "code") {
                currentMode = mode; prefs.viewMode = mode; invalidateOptionsMenu()
                Logger.i("MainActivity", "视图模式: ${if (mode == "preview") "预览" else "编辑"}")
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

    override fun onCenterTap() { runOnUiThread { showSettings() } }

    override fun openWikiLink(noteName: String) {
        val vaultUriStr = prefs.vaultUri
        if (vaultUriStr == null) {
            runOnUiThread { Toast.makeText(this, R.string.vault_not_set_toast, Toast.LENGTH_LONG).show() }
            return
        }
        Thread {
            val file = VaultSearch.findFile(this, VaultSearch.ensureEncoded(Uri.parse(vaultUriStr)), noteName)
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
        return VaultSearch.search(this, VaultSearch.ensureEncoded(Uri.parse(vaultUriStr)), query)
    }

    override fun searchVaultAsync(query: String, callbackId: String) {
        val vaultUriStr = prefs.vaultUri
        if (vaultUriStr == null) {
            val escaped = org.json.JSONArray().toString()
            runOnUiThread { js("window.appVaultSearchResult && window.appVaultSearchResult('${escapeJs(callbackId)}', '${escapeJs(escaped)}')") }
            return
        }
        Logger.i("MainActivity", "搜索库: $query")
        Thread {
            val result = VaultSearch.search(this, VaultSearch.ensureEncoded(Uri.parse(vaultUriStr)), query)
            runOnUiThread {
                js("window.appVaultSearchResult && window.appVaultSearchResult('${escapeJs(callbackId)}', ${org.json.JSONObject.quote(result)})")
            }
        }.start()
    }

    override fun openVaultFile(uri: String) {
        runOnUiThread { runCatching { loadDocument(Uri.parse(uri)) } }
    }

    override fun searchVaultForEmbed(ref: String): String {
        val vaultUriStr = prefs.vaultUri
        if (vaultUriStr == null) return ""
        val searchName = ref.substringBeforeLast('.')
        return runCatching {
            val result = VaultSearch.findFile(this, VaultSearch.ensureEncoded(Uri.parse(vaultUriStr)), searchName)
            result?.uri?.toString() ?: ""
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
            val fileName = currentTitle.ifEmpty { getString(R.string.app_name) }
            val message = "$fileName\n\n$stats"
            AlertDialog.Builder(this)
                .setTitle(R.string.char_count_title)
                .setMessage(message)
                .setPositiveButton(R.string.ok, null)
                .show()
        }
    }

    /** 显示字符统计（由标题栏点击触发） */
    fun triggerCharCount() {
        if (!pageReady) return
        try {
            webView.evaluateJavascript("window.appShowCharCount && window.appShowCharCount()") {}
        } catch (_: Exception) {}
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

    /** 保存 Bitmap 到系统相册（Pictures/MD阅读器），返回保存的文件路径 */
    private fun saveImageToGallery(bitmap: Bitmap, fileName: String): String? {
        val resolver = contentResolver
        val collection = android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "$fileName.png")
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/MD阅读器")
        }
        val uri = resolver.insert(collection, values) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            }
            val path = android.os.Environment.getExternalStorageDirectory()
                .absolutePath + "/Pictures/MD阅读器/$fileName.png"
            path
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    /** 生成文件名：日期前缀 + 笔记名，仅保留文件系统允许的字符 */
    private fun exportFileName(title: String): String {
        val datePrefix = java.text.SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(java.util.Date())
        var name = title.trim().ifEmpty { "document" }
        name = name.replace(FILENAME_UNSAFE_REGEX, "")
        name = name.trim(' ', '.')
        if (name.isEmpty()) name = "document"
        if (name.length > 80) name = name.substring(0, 80)
        return "${datePrefix}_${name}"
    }

    companion object {
        private const val ASSET_HOST = "appassets.androidplatform.net"
        private const val VIEWER_URL = "https://$ASSET_HOST/assets/viewer.html"
        private const val REQ_WRITE_STORAGE = 1001

        /** 预编译 Regex：文件名非法字符替换 */
        private val FILENAME_UNSAFE_REGEX = Regex("""[\\/:*?"<>|\r\n]""")
        /** 预编译 Regex：HTML 中 img 标签 src 提取 */

        private val WELCOME_MD = """
# 欢迎使用 MD 阅读器

这是一个功能丰富的本地 **Markdown 阅读器**，支持多种文档格式与 Obsidian 兼容语法。

## 👨‍💻 关于作者

你好，我是 **阿成**，一个酷爱 AI 编程的创作者。

本项目是我经历一星期的持续迭代打磨而成，从文档解析到阅读体验，每一个细节都力求做到最好。

如果你喜欢这款应用，欢迎到我的仓库点个免费的 Star ⭐，你的支持是我最大的动力！

- 📦 **项目仓库**：[github.com/acheng-byte/ai-md-reader-android](https://github.com/acheng-byte/ai-md-reader-android)
- 👤 **个人主页**：[github.com/acheng-byte](https://github.com/acheng-byte)

## 快速上手

- 点击 **目录** 唤出大纲，点击标题快速跳转
- 点击 **搜索** 打开搜索栏（再次点击关闭），输入关键词搜索，支持全库搜索切换
- 点击 **源码 / 预览** 切换呈现方式；预览模式点击标题可折叠/展开
- **点击屏幕中央** 调出「显示设置」
- 在设置中选择 **Vault 文件夹** 后，可使用 `[[wikilink]]` 导航与全库搜索
- 重新打开同一文档时，会自动回到上次阅读位置（断点续读）
- 启动时自动弹出打开历史，快速继续阅读
- 点击右上角 **⋮** 菜单：转发分享、收藏、添加桌面快捷方式等
- 设置中可手动 **检查更新**，下载最新版本

## 支持的 Markdown 语法

### 标题

# 一级标题
## 二级标题
### 三级标题

支持 H1 ~ H6 六级标题，预览模式下点击标题可 **折叠/展开** 下方内容。

### 列表

无序列表：

- 苹果
- 香蕉
- 橘子

有序列表：

1. 第一步
2. 第二步
3. 第三步

任务列表：

- [x] 已完成的任务
- [ ] 未完成的任务

### 表格

| 功能 | 状态 |
| --- | :---: |
| Markdown 渲染 | ✅ |
| 代码高亮 | ✅ |

### 代码块

```kotlin
fun main() {
    println("Hello, MD Reader!")
}
```

支持 100+ 种编程语言的语法高亮，右上角有 **复制按钮**。支持 `plaintext`/`text` 等纯文本代码块跳过语法高亮。

### 引用块

> 这是一段引用文字
> 可以多行书写

支持 20+ 种 Callout 类型（NOTE、TIP、WARNING、DANGER、ERROR、SUCCESS、QUESTION、ABSTRACT、BUG、QUOTE、EXAMPLE、TODO、IMPORTANT 等）。

### 链接与图片

[链接文字](https://example.com)
![图片描述](image.png)

### 分隔线

---

### 行内样式

**粗体** *斜体* ~~删除线~~ `行内代码` ==高亮标记==

## Obsidian 兼容语法

### Wikilinks

[[另一篇笔记]]
[[目录/子目录/文件|显示名称]]
[[#某个标题]]

设置 Vault 文件夹后，点击 wikilink 可跳转到对应笔记。

### 嵌入文件

![[图片.png]]
![[视频.mp4]]
![[另一篇笔记]]

图片直接显示，视频可播放，其他文档可展开查看内容。

### Callout 标注

> [!NOTE] 提示
> 这是一条提示信息

> [!WARNING] 注意
> 这是一条警告信息

> [!TIP] 建议
> 这是一条建议信息

### ==高亮==

这是 ==高亮== 的文字

### #标签

这是一段带有 #标签 和 #阅读/笔记 的文字

渲染为圆角标签胶囊样式（蓝色），不影响标题层级。

### %%注释%%

这段文字 %%会被隐藏%% 不会显示

### 脚注

这是一段正文[^1]。

[^1]: 这是脚注的内容。

行内渲染为上标链接，文末自动生成脚注列表，支持反向跳回。

### YAML Frontmatter

---
title: 文档标题
tags:
  - 阅读
  - 笔记
date: 2024-01-01
---

自动解析并以表格形式显示在文档顶部，可在设置中开关。

## Mermaid 图表

```mermaid
graph TD
    A[开始] --> B{判断}
    B -->|是| C[执行]
    B -->|否| D[结束]
```

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
