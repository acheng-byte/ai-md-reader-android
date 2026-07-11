package com.mdreader.app

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
    private lateinit var webView: WebView

    @Volatile private var currentMarkdown: String = ""
    @Volatile private var currentMode: String = Prefs.DEFAULT_MODE
    private var currentTitle: String = ""
    @Volatile private var currentUri: String? = null
    @Volatile private var currentDocumentUri: Uri? = null
    private var pageReady: Boolean = false

    // Edit mode state
    private var isEditing: Boolean = false

    // Cancel token: increment before each new load; JS render checks if it's stale
    private val loadGeneration = AtomicInteger(0)

    // Debounce renderCurrent
    private val renderHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingRender: Runnable? = null

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
                    isEditing = false
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

        // Back press: prompt if editing with unsaved changes
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isEditing) {
                    val original = currentMarkdown
                    val edited = binding.editText.text.toString()
                    if (edited != original) {
                        confirmDiscardEdit()
                    } else {
                        exitEditMode(save = false)
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
                renderCurrent()
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
        // Exit edit mode silently when opening a different document
        if (isEditing) {
            isEditing = false
            binding.editScroll.visibility = View.GONE
            binding.webview.visibility = View.VISIBLE
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
            js("window.appSetMode && window.appSetMode('$currentMode')")
            applySettingsToWeb()
            js("window.appRestoreScroll && window.appRestoreScroll()")
        }
        pendingRender = r
        renderHandler.postDelayed(r, 50)
    }

    private fun applySettingsToWeb() {
        js("window.appApplySettings && window.appApplySettings(${prefs.settingsJson(this)})")
        val bg = bgColor()
        webView.setBackgroundColor(bg)
        if (isEditing) {
            binding.editText.setTextColor(if (prefs.isDark(this)) Color.WHITE else Color.BLACK)
            binding.editText.setBackgroundColor(bg)
            binding.editScroll.setBackgroundColor(bg)
        }
    }

    private fun js(code: String) {
        if (pageReady) webView.evaluateJavascript(code, null)
    }

    private fun bgColor(): Int =
        if (prefs.isDark(this)) 0xFF0D1117.toInt() else 0xFFFFFFFF.toInt()

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
        if (now - lastCheck < TimeUnit.HOURS.toMillis(24)) return
        prefs.lastUpdateCheck = now
        Thread {
            val info = UpdateChecker.checkLatest() ?: return@Thread
            val currentVersion = runCatching {
                packageManager.getPackageInfo(packageName, 0).versionName
            }.getOrNull() ?: return@Thread
            if (UpdateChecker.isNewer(info.tagName, currentVersion)) {
                runOnUiThread { showUpdateDialog(info) }
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
        // Edit mode: show save/cancel, hide normal actions
        menu.findItem(R.id.action_save)?.isVisible = isEditing
        menu.findItem(R.id.action_cancel_edit)?.isVisible = isEditing
        val normalVisible = !isEditing
        listOf(
            R.id.action_share, R.id.action_toc, R.id.action_search,
            R.id.action_toggle, R.id.action_favorite, R.id.action_edit,
            R.id.action_open, R.id.action_favorites, R.id.action_history,
            R.id.action_export_image, R.id.action_export_html
        ).forEach { menu.findItem(it)?.isVisible = normalVisible }

        if (!isEditing) {
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
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_save -> { exitEditMode(save = true); true }
        R.id.action_cancel_edit -> {
            val edited = binding.editText.text.toString()
            if (edited != currentMarkdown) confirmDiscardEdit() else exitEditMode(save = false)
            true
        }
        R.id.action_edit -> { enterEditMode(); true }
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
        R.id.action_export_image -> { requestStorageAndExportImage(); true }
        R.id.action_export_html -> { exportHtml(); true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun toggleMode() {
        currentMode = if (currentMode == "preview") "code" else "preview"
        prefs.viewMode = currentMode
        js("window.appSetMode && window.appSetMode('$currentMode')")
        invalidateOptionsMenu()
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
            .setPositiveButton(R.string.edit_discard_confirm) { _, _ -> exitEditMode(save = false) }
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
            contentResolver.openOutputStream(uri, "wt")?.use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
            }
            true
        }.getOrDefault(false)

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

        // Frontmatter and citations toggles
        sheet.switchFrontmatter.isChecked = prefs.showFrontmatter
        sheet.switchCitations.isChecked = prefs.showCitations

        // Show current vault folder name
        val vaultStr = prefs.vaultUri
        sheet.tvVaultPath.text = if (vaultStr != null) {
            runCatching {
                DocumentFile.fromTreeUri(this, Uri.parse(vaultStr))?.name ?: vaultStr
            }.getOrDefault(vaultStr)
        } else getString(R.string.vault_not_set)

        sheet.btnSelectVault.setOnClickListener { vaultPicker.launch(null) }

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
        sheet.switchFrontmatter.setOnCheckedChangeListener { _, checked ->
            prefs.showFrontmatter = checked
            applySettingsToWeb()
        }
        sheet.switchCitations.setOnCheckedChangeListener { _, checked ->
            prefs.showCitations = checked
            applySettingsToWeb()
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

    override fun onDestroy() {
        pendingRender?.let { renderHandler.removeCallbacks(it) }
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

                // 限制最大高度防止 OOM，使用 2x 密度保证清晰度
                val maxBitmapHeight = 8192
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

                        // 保存到应用私有 Downloads 目录（Android 10+ 兼容）
                        val dir = getExportDir()
                        val safeName = exportFileName(savedTitle)
                        val file = File(dir, "$safeName.png")
                        FileOutputStream(file).use { out ->
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                        }
                        bitmap.recycle()

                        // 通知媒体扫描器以便在系统「下载」中可见
                        scanExportedFile(file)

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
                // 安全解码 JS 返回的 JSON 字符串：evaluateJavascript 返回的是 JSON 编码的字符串
                val cleanHtml = decodeJsString(htmlContent)

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
                    // Mermaid CDN：使导出的 HTML 中 Mermaid SVG 可继续交互/渲染
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

                val dir = getExportDir()
                val safeName = exportFileName(currentTitle)
                val file = File(dir, "$safeName.html")
                FileOutputStream(file).use { out ->
                    out.write(fullHtml.toByteArray(Charsets.UTF_8))
                }
                scanExportedFile(file)

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

    /**
     * 安全解码 evaluateJavascript 返回的 JSON 字符串。
     * evaluateJavascript 返回值是一个 JSON 编码的字符串（带外层引号，内部转义）。
     * 使用 org.json.JSONObject 来安全解码，避免手动 replace 导致的格式损坏。
     */
    private fun decodeJsString(raw: String): String {
        return try {
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
    }

    /** 获取导出目录：优先使用应用私有 Downloads（Android 10+ 兼容），无需额外权限 */
    private fun getExportDir(): File {
        // Android 10+ (API 29) 使用 getExternalFilesDir 无需申请存储权限
        // 文件保存在 /sdcard/Android/data/com.mdreader.app/files/Downloads/
        val dir = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: File(filesDir, "Downloads")
        } else {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        }
        dir.mkdirs()
        return dir
    }

    /** 生成导出文件名：以笔记名命名，仅去除文件系统不允许的字符 */
    private fun exportFileName(title: String): String {
        var name = title.trim().ifEmpty { "document" }
        // 仅去除文件系统禁止的字符：\ / : * ? " < > | 和换行
        name = name.replace(Regex("[\\\\/:*?\"<>|\\r\\n]"), "")
        // 去除首尾空格和点（Windows 不允许）
        name = name.trim(' ', '.')
        if (name.isEmpty()) name = "document"
        // 限制长度防止文件名过长
        if (name.length > 100) name = name.substring(0, 100)
        return name
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

这是一个本地 **Markdown 阅读器**（v1.7.2）。

## 怎么用

- 点击 **目录** 唤出大纲，点击标题快速跳转
- 点击 **搜索** 在当前文档中搜索，也可切换为全库搜索
- 点击 **源码 / 预览** 切换呈现方式；预览模式点击标题可折叠/展开
- **点击屏幕中央** 调出「显示设置」（字号 / 行距 / 段距 / 主题 / Vault 文件夹）
- 在设置中选择 **Vault 文件夹** 后，可使用 `[[wikilink]]` 导航与全库搜索
- 重新打开同一文档时，会自动回到上次阅读位置
- 启动时自动弹出打开历史，快速继续阅读
- 点击右上角 **⋮ → 编辑**，直接在应用内编辑 Markdown，保存后立即刷新预览

## v1.7.2 更新

- **修复**：历史记录单条删除闪退问题
- **修复**：导出长图不完整、代码块被截断的问题
- **修复**：导出 HTML 丢失加粗、表格、图片、Mermaid 格式
- **修复**：导出文件名包含无效字符、保存后在下载目录看不到
- **修复**：编辑 DOC 文档后再打开其他 DOC 显示解析失败
- **优化**：DOC 文档图片过滤不支持的格式（EMF/WMF），避免显示空白

## 支持的格式

| 功能 | 是否支持 |
| --- | :---: |
| 标题 / 列表 / 表格 | ✅ |
| 代码高亮 | ✅ |
| Mermaid 图表 | ✅ |
| Wikilinks `[[链接]]` | ✅ |
| 路径式 Wikilinks `[[目录/文件]]` | ✅ |
| Frontmatter 元数据 | ✅ |
| HTML 渲染 | ✅ |
| 任务列表 `- [ ]` | ✅ |
| 图片 & 视频（Vault 内）| ✅ |
| TXT（UTF-8 / GBK）/ DOCX / DOC | ✅ |
| PDF（逐页渲染） | ✅ |
| Callout `> [!NOTE]` | ✅ |
| 折叠标题 | ✅ |
| `==高亮==` | ✅ |
| `#标签` | ✅ |
| `%%注释%%` ✅ |
| 脚注 `[^1]` | ✅ |
| 导出长图片 / HTML | ✅ |

---

打开一个文件开始阅读吧
        """.trimIndent()
    }
}
