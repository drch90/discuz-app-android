package com.discuz.mobile

import android.annotation.SuppressLint
import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ContentValues
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Message
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.File
import java.io.ByteArrayInputStream
import java.util.UUID
import java.util.concurrent.Executors
import org.json.JSONObject
import org.json.JSONArray
import com.google.zxing.integration.android.IntentIntegrator

class MainActivity : Activity() {
    private lateinit var root: FrameLayout
    private lateinit var progress: ProgressBar
    private var webView: WebView? = null
    private var policy: SitePolicy? = null
    private var errorView: View? = null
    private var failedMainDocument = false
    private var lastBackAt = 0L
    private var backPending = false
    private val io = Executors.newSingleThreadExecutor()
    private val connections = Executors.newSingleThreadExecutor()
    private var directory: DomainDirectory? = null
    private var directoryKey = ""
    private var connectionEpoch = 0
    private var connecting = false
    private var domainSyncAt = 0L
    private val failedDomains = mutableSetOf<String>()
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var cameraUri: Uri? = null
    private var scanReply: BridgeReply? = null
    private var pendingCameraChooser: Intent? = null
    private var pendingDownload: PendingDownload? = null
    private var downloadBusy = false
    private var returnToApp: Button? = null
    private var lastAppUrl: String? = null
    private val imageCache by lazy { ImageDiskCache(File(cacheDir, "post-images")) }
    private data class ImagePermit(val url: String, val scope: String, val public: Boolean, var force: Boolean, val epoch: Long, @Volatile var status: Int = 0)
    private val imagePermits = LinkedHashMap<String, ImagePermit>()
    private val imageHttpErrors = LinkedHashMap<String, Int>()
    @Volatile private var imageEpoch = 0L
    @Volatile private var imageIdentity: String? = null
    private var imageUserAgent = ""

    private fun invalidateImages() {
        synchronized(imagePermits) { imageEpoch++; imagePermits.clear(); imageHttpErrors.clear() }
    }

    private data class BridgeReply(val id: String, val proxy: JavaScriptReplyProxy)
    private data class PendingDownload(val file: DownloadedFile, val reply: BridgeReply?)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        applySystemBars(darkAppearance())
        root = FrameLayout(this).apply { setBackgroundColor(appearanceColor()) }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, keyboard.bottom))
            // The native container owns these insets; WebView must not apply them again.
            WindowInsetsCompat.CONSUMED
        }
        setContentView(root)
        ViewCompat.requestApplyInsets(root)
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) {
                navigateBack()
            }
        }
        cacheDir.listFiles()?.filter { it.name.startsWith("discuz-download-") }?.forEach { it.delete() }
        File(cacheDir, "camera").listFiles()?.filter { it.lastModified() < System.currentTimeMillis() - 86_400_000 }?.forEach { it.delete() }
        val custom = if (BuildConfig.DEBUG) preferences().getString("siteUrl", "").orEmpty() else ""
        val addresses = if (custom.isNotBlank()) listOf(custom) else BuildConfig.SITE_URLS.lines().filter { it.isNotBlank() }
        if (addresses.isEmpty()) showAddressDialog(false)
        else runCatching { initializeDomains(addresses); connectSites(savedInstanceState) }
            .onFailure { showAddressDialog(false, it.message.orEmpty()) }
    }

    private fun initializeDomains(addresses: List<String>) {
        connectionEpoch++
        val seeds = addresses.map { SitePolicy.parse(it, BuildConfig.DEBUG) }.distinctBy { it.forumUrl }
        val next = DomainDirectory(seeds, BuildConfig.DEBUG)
        directoryKey = "domains-" + ImageDiskCache.hash(seeds.joinToString("|") { it.forumUrl })
        runCatching {
            val saved = JSONObject(preferences().getString(directoryKey, "").orEmpty())
            val urls = saved.getJSONArray("domains")
            next.update(DomainManifest(saved.getString("site_id"), (0 until urls.length()).map { urls.getString(it) }))
        }
        directory = next
        failedDomains.clear()
        connecting = false
    }

    private fun saveDirectory(snapshot: DomainManifest?) {
        if (snapshot == null) return
        preferences().edit().putString(directoryKey, JSONObject().put("site_id", snapshot.siteId)
            .put("domains", JSONArray(snapshot.domains)).toString()).apply()
    }

    private fun connectSites(state: Bundle? = null, reply: BridgeReply? = null) {
        if (connecting) { complete(reply, false, "正在检测连接线路，请稍候。"); return }
        val currentDirectory = directory ?: return
        val token = ++connectionEpoch
        val oldSite = policy?.forumUrl
        val preferred = oldSite ?: preferences().getString("$directoryKey-last", null)
        val excluded = failedDomains.toSet()
        connecting = true
        if (reply == null) showConnectionProgress()
        connections.execute {
            val result = runCatching {
                val selected = currentDirectory.select(preferred, excluded) { DomainProbe().fetch(it) }
                selected to currentDirectory.snapshot()
            }
            runOnUiThread {
                if (isDestroyed || token != connectionEpoch) return@runOnUiThread
                connecting = false
                result.onSuccess { (selected, snapshot) ->
                    saveDirectory(snapshot)
                    preferences().edit().putString("$directoryKey-last", selected.site.forumUrl).apply()
                    domainSyncAt = android.os.SystemClock.elapsedRealtime()
                    val changed = selected.site.forumUrl != oldSite
                    // A healthy current origin needs no reload. A disabled
                    // service must reload so the common App switch takes effect.
                    if (reply != null && !changed && !failedMainDocument && !selected.result.disabled) {
                        complete(reply, true, "", JSONObject().put("switched", false))
                    } else {
                        complete(reply, true, "", JSONObject().put("switched", changed))
                        loadSite(selected.site, if (changed) null else state)
                        if (changed && oldSite != null) toast("已切换可用域名，登录状态以新域名为准。")
                    }
                }.onFailure {
                    complete(reply, false, if (reply != null) it.message.orEmpty() else "")
                    if (reply == null) showFatalError(it.message ?: "无法连接社区")
                }
            }
        }
    }

    private fun syncDomains() {
        if (connecting || android.os.SystemClock.elapsedRealtime() - domainSyncAt < 60_000) return
        val currentDirectory = directory ?: return
        val site = policy ?: return
        val token = connectionEpoch
        domainSyncAt = android.os.SystemClock.elapsedRealtime()
        connections.execute {
            val snapshot = runCatching {
                val result = DomainProbe().fetch(site)
                result.manifest?.let { currentDirectory.update(it) }
                currentDirectory.snapshot()
            }.getOrNull()
            runOnUiThread { if (!isDestroyed && token == connectionEpoch) saveDirectory(snapshot) }
        }
    }

    private fun showConnectionProgress() {
        errorView?.let { root.removeView(it) }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setBackgroundColor(appearanceColor())
            addView(ProgressBar(this@MainActivity))
            addView(TextView(this@MainActivity).apply { text = "正在检测可用线路…"; setPadding(dp(16), dp(20), dp(16), 0) })
        }
        errorView = box
        root.addView(box, FrameLayout.LayoutParams(-1, -1))
    }

    private fun systemDark(): Boolean =
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    private fun darkAppearance(): Boolean = when (preferences().getString("appearanceMode", "system")) {
        "dark" -> true
        "light" -> false
        else -> systemDark()
    }

    private fun updateAppearance() {
        val dark = darkAppearance()
        val color = if (dark) Color.BLACK else Color.rgb(246, 247, 251)
        root.setBackgroundColor(color)
        webView?.setBackgroundColor(color)
        if (::progress.isInitialized) progress.applyAppearance(dark)
        applySystemBars(dark)
    }

    private fun notifySystemAppearance() {
        val view = webView ?: return
        if (failedMainDocument || policy?.isAppPage(view.url) != true) return
        // Only a boolean crosses into the trusted App document, never a URL or script from settings.
        view.evaluateJavascript("window.dispatchEvent(new CustomEvent('discuz:system-theme',{detail:{dark:${systemDark()}}}));", null)
    }
    @Suppress("DEPRECATION") // Pre-Android 15 system bars still use explicit colors.
    private fun applySystemBars(dark: Boolean) {
        // Let the inset area painted by the native root show through in both themes.
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }

    private fun appearanceColor(): Int = if (darkAppearance()) Color.BLACK else Color.rgb(246, 247, 251)
    private fun ProgressBar.applyAppearance(dark: Boolean = darkAppearance()) {
        progressTintList = android.content.res.ColorStateList.valueOf(if (dark) Color.LTGRAY else Color.rgb(82, 104, 244))
        secondaryProgressTintList = progressTintList
        progressBackgroundTintList = android.content.res.ColorStateList.valueOf(if (dark) Color.DKGRAY else Color.rgb(230, 234, 250))
    }
    private fun preferences() = getSharedPreferences("site", MODE_PRIVATE)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun showAddressDialog(cancelable: Boolean, message: String = "") {
        if (!BuildConfig.DEBUG) {
            showFatalError("站点配置有误，请重新编译安装正式版。")
            return
        }
        val input = EditText(this).apply {
            hint = "http://192.168.1.100/discuz/"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setText(policy?.forumUrl ?: preferences().getString("siteUrl", BuildConfig.SITE_URL))
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(TextView(this@MainActivity).apply {
                text = message.ifBlank { "填写论坛根地址，可包含子目录。调试版支持 HTTP 和 HTTPS。" }
                setPadding(0, 0, 0, dp(12))
            })
            addView(input)
        }
        val dialog = AlertDialog.Builder(this).setTitle("连接你的社区 · 调试版").setView(box)
            .setPositiveButton("连接", null).setCancelable(cancelable)
        if (cancelable) dialog.setNegativeButton("取消", null)
        val shown = dialog.create()
        shown.setOnShowListener {
            shown.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                runCatching { SitePolicy.parse(input.text.toString(), true) }.onSuccess { next ->
                    preferences().edit().putString("siteUrl", next.forumUrl).apply()
                    shown.dismiss()
                    initializeDomains(listOf(next.forumUrl))
                    connectSites()
                }.onFailure { input.error = it.message }
            }
        }
        shown.show()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun loadSite(next: SitePolicy, state: Bundle?) {
        complete(scanReply, false, ""); scanReply = null
        fileCallback?.onReceiveValue(null); fileCallback = null; pendingCameraChooser = null
        webView?.apply { stopLoading(); destroy() }
        root.removeAllViews()
        errorView = null
        policy = next
        invalidateImages()
        imageIdentity = null
        lastAppUrl = next.appUrl
        failedMainDocument = false
        val view = WebView(this)
        webView = view
        view.setBackgroundColor(appearanceColor())
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(true)
            mediaPlaybackRequiresUserGesture = true
            userAgentString = "$userAgentString DiscuzApp/${BuildConfig.VERSION_NAME}"
        }
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(view, false)
        }
        imageUserAgent = view.settings.userAgentString
        view.webViewClient = client()
        view.webChromeClient = chromeClient()
        view.setDownloadListener { url, _, disposition, mime, _ ->
            val name = android.webkit.URLUtil.guessFileName(url, disposition, mime)
            if (next.isAppPage(view.url) || next.isForumUrl(view.url)) startDownload(url, name, false, null)
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(view, "DiscuzNative", setOf(next.origin)) { sender, message, sourceOrigin, mainFrame, reply ->
                if (mainFrame && next.isAppPage(sender.url) && next.isOrigin(sourceOrigin.toString())) {
                    runCatching { handleBridge(message.data.orEmpty(), reply) }
                }
            }
        }
        root.addView(view, FrameLayout.LayoutParams(-1, -1))
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            applyAppearance()
        }
        root.addView(progress, FrameLayout.LayoutParams(-1, dp(3), Gravity.TOP))
        returnToApp = Button(this).apply {
            text = "返回 App"
            contentDescription = "返回社区 App 并刷新账户"
            visibility = View.GONE
            setOnClickListener { view.loadUrl(lastAppUrl?.takeIf(next::isAppPage) ?: next.appUrl) }
        }
        root.addView(returnToApp, FrameLayout.LayoutParams(-2, dp(44), Gravity.BOTTOM or Gravity.END).apply {
            setMargins(dp(12), dp(12), dp(12), dp(12))
        })
        if (BuildConfig.DEBUG) {
            root.addView(Button(this).apply {
                text = "⚙"
                textSize = 15f
                contentDescription = "设置调试站点"
                alpha = 0.75f
                minWidth = 0
                minimumWidth = 0
                setPadding(0, 0, 0, 0)
                setOnClickListener { showAddressDialog(true) }
            }, FrameLayout.LayoutParams(dp(36), dp(36), Gravity.TOP or Gravity.END))
        }
        if (state != null && next.isForumUrl(state.getString("lastUrl")) && view.restoreState(state) != null) return
        view.loadUrl(next.appUrl)
    }

    private fun client() = object : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val site = policy ?: return null
            val url = request.url.toString()
            if (!url.startsWith(site.appUrl + "__image_cache/")) {
                synchronized(imagePermits) { imageHttpErrors.remove(url) }
                return null
            }
            fun unavailable() = WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(ByteArray(0)))
            if (request.method != "GET" || request.isForMainFrame) return unavailable()
            val permit = synchronized(imagePermits) { imagePermits[url] } ?: return unavailable()
            return runCatching {
                if (permit.epoch != imageEpoch) return unavailable()
                val force = synchronized(imagePermits) { permit.force.also { permit.force = false } }
                val result = imageCache.open(permit.scope, permit.url, force, valid = { permit.epoch == imageEpoch }) {
                    check(permit.epoch == imageEpoch) { "图片请求已取消" }
                    ImageFetcher(site, imageUserAgent,
                        { CookieManager.getInstance().getCookie(it) },
                        { address, cookie -> if (permit.epoch == imageEpoch) CookieManager.getInstance().setCookie(address, cookie) }
                    ).open(permit.url, permit.public, force)
                }
                if (permit.epoch != imageEpoch) { result.close(); return unavailable() }
                permit.status = 200
                // Synthetic URLs never enter WebView's HTTP cache. Each new render
                // gets a new permit, so a forced refresh also bypasses memory reuse.
                val headers = mutableMapOf("Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff")
                if (result.length >= 0) headers["Content-Length"] = result.length.toString()
                WebResourceResponse(result.mime, null, 200, "OK", headers, result.stream)
            }.getOrElse { error ->
                // A network/decoding failure is not evidence that the file is gone.
                permit.status = (error as? ImageHttpError)?.status ?: 0
                val status = permit.status.takeIf { it in 400..599 } ?: 502
                WebResourceResponse("text/plain", "UTF-8", status, "Image unavailable", mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(ByteArray(0)))
            }
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()
            if (policy?.isForumUrl(url) == true) return false
            // Original payment pages may invoke a known payment app after a redirect.
            val trustedPayment = policy?.isForumUrl(view.url) == true && paymentPackage(Uri.parse(url).scheme) != null
            if (request.isForMainFrame && (request.hasGesture() || trustedPayment)) openExternal(url)
            return true
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
            invalidateImages()
            if (policy?.isForumUrl(url) != true) {
                view.stopLoading()
                showFatalError("页面跳转到了站外，请返回社区后重试。")
                return
            }
            failedMainDocument = false
            if (policy?.isAppPage(url) == true) lastAppUrl = url
            returnToApp?.visibility = if (policy?.isAppPage(url) == true) View.GONE else View.VISIBLE
            errorView?.let { root.removeView(it) }
            errorView = null
            progress.visibility = View.VISIBLE
        }

        override fun onPageFinished(view: WebView, url: String?) {
            if (view !== webView) return
            progress.visibility = View.GONE
            CookieManager.getInstance().flush()
            if (!failedMainDocument && policy?.isAppPage(url) == true) { failedDomains.clear(); syncDomains(); notifySystemAppearance() }
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (view === webView && request.isForMainFrame) {
                failedMainDocument = true
                if (request.method == "GET" && !connecting) {
                    policy?.forumUrl?.let { failedDomains.add(it) }; connectSites()
                } else if (!connecting) showFatalError("连接中断，提交结果尚未确认。请重新连接后查看账户或记录。")
            }
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
            if (view === webView && !request.isForMainFrame && policy?.isAppPage(view.url) == true) {
                synchronized(imagePermits) {
                    while (imageHttpErrors.size >= 256) imageHttpErrors.remove(imageHttpErrors.keys.first())
                    imageHttpErrors[request.url.toString()] = response.statusCode
                }
            }
            if (view === webView && request.isForMainFrame) {
                failedMainDocument = true
                if (request.method == "GET" && !connecting) {
                    policy?.forumUrl?.let { failedDomains.add(it) }; connectSites()
                } else if (!connecting) showFatalError("社区暂时无法访问（${response.statusCode}），请重新连接后确认操作结果。")
            }
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            handler.cancel()
            // TLS errors are never bypassed, including debug builds.
            if (view === webView && !connecting && (error.url == view.url || policy?.isAppPage(error.url) == true)) {
                failedMainDocument = true
                if (policy?.isAppPage(error.url) == true) {
                    policy?.forumUrl?.let { failedDomains.add(it) }; connectSites()
                } else showFatalError("HTTPS 证书验证失败，请修复站点证书后重试。")
            }
        }

        override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
            root.removeView(view)
            view.destroy()
            webView = null
            showFatalError("页面已暂停，请重新连接社区。")
            return true
        }
    }

    private fun chromeClient() = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            progress.progress = newProgress
            progress.visibility = if (newProgress == 100 || failedMainDocument) View.GONE else View.VISIBLE
        }

        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            if (!isUserGesture) return false
            val popup = WebView(this@MainActivity)
            popup.settings.javaScriptEnabled = false
            popup.settings.allowFileAccess = false
            popup.settings.allowContentAccess = false
            popup.settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            popup.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(child: WebView, request: WebResourceRequest): Boolean {
                    val url = request.url.toString()
                    if (policy?.isForumUrl(url) == true) view.loadUrl(url) else openExternal(url)
                    child.post { child.destroy() }
                    return true
                }
            }
            (resultMsg.obj as WebView.WebViewTransport).webView = popup
            resultMsg.sendToTarget()
            return true
        }

        override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
            if (policy?.isAppPage(view.url) != true) return false
            fileCallback?.onReceiveValue(null)
            fileCallback = callback
            cameraUri = null
            val accepted = params.acceptTypes.filter { it.isNotBlank() }.toTypedArray()
            val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = if (accepted.size == 1) accepted.first() else "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, accepted)
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.mode == FileChooserParams.MODE_OPEN_MULTIPLE)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val acceptsImages = accepted.isEmpty() || accepted.any { it.startsWith("image/") || it == "*/*" }
            if (!acceptsImages) {
                launchUploadChooser(picker)
                return true
            }
            val multiple = params.mode == FileChooserParams.MODE_OPEN_MULTIPLE
            AlertDialog.Builder(this@MainActivity)
                .setTitle("选择上传方式")
                .setItems(arrayOf("相册", "拍照", "文件")) { _, which ->
                    when (which) {
                        0 -> launchAlbum(multiple)
                        1 -> launchCamera()
                        else -> launchUploadChooser(picker)
                    }
                }
                .setOnCancelListener { fileCallback?.onReceiveValue(null); fileCallback = null }
                .show()
            return true
        }
    }

    private fun launchAlbum(multiple: Boolean) {
        // The system grants access only to selected photos; no library-wide permission.
        val album = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Intent(MediaStore.ACTION_PICK_IMAGES).apply {
                type = "image/*"
                if (multiple) putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, minOf(10, MediaStore.getPickImagesMaxLimit()))
            }
        } else if (multiple) {
            // ACTION_PICK does not define multi-selection; many galleries ignore
            // EXTRA_ALLOW_MULTIPLE. GET_CONTENT supports selecting multiple images.
            Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
        } else {
            Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI).apply {
                type = "image/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple)
            }
        }
        album.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { startActivityForResult(album, REQUEST_UPLOAD) }
        catch (_: ActivityNotFoundException) {
            // Devices without a gallery can still select images through a provider.
            launchUploadChooser(Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (_: Exception) {
            fileCallback?.onReceiveValue(null); fileCallback = null
            toast("无法打开相册，请重试或选择文件。")
        }
    }

    private fun launchCamera() {
        try {
            val camera = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            if (camera.resolveActivity(packageManager) == null) {
                fileCallback?.onReceiveValue(null); fileCallback = null
                toast("设备没有可用的相机应用。")
                return
            }
            val directory = File(cacheDir, "camera").apply { mkdirs() }
            val file = File.createTempFile("upload-", ".jpg", directory)
            cameraUri = FileProvider.getUriForFile(this, "$packageName.files", file)
            camera.putExtra(MediaStore.EXTRA_OUTPUT, cameraUri)
            camera.clipData = ClipData.newRawUri("拍摄图片", cameraUri)
            camera.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                pendingCameraChooser = camera
                requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA_PERMISSION)
            } else launchUploadChooser(camera)
        } catch (_: Exception) {
            fileCallback?.onReceiveValue(null); fileCallback = null; cameraUri = null
            toast("无法打开相机，请重试或选择相册。")
        }
    }

    private fun launchUploadChooser(chooser: Intent) {
        try { startActivityForResult(chooser, REQUEST_UPLOAD) }
        catch (_: Exception) {
            fileCallback?.onReceiveValue(null); fileCallback = null
            toast("无法打开文件选择器，请检查设备权限。")
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CAMERA_PERMISSION) return
        val chooser = pendingCameraChooser ?: return
        pendingCameraChooser = null
        if (grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
            cameraUri = null
            fileCallback?.onReceiveValue(null); fileCallback = null
            toast("未授予相机权限，可重新选择相册或文件上传。")
            return
        }
        if (fileCallback != null) launchUploadChooser(chooser)
    }

    private fun showFatalError(message: String) {
        errorView?.let { root.removeView(it) }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(24), dp(28), dp(24))
            setBackgroundColor(Color.rgb(247, 248, 252))
            addView(TextView(this@MainActivity).apply { text = "连接社区"; textSize = 24f; gravity = Gravity.CENTER })
            addView(TextView(this@MainActivity).apply {
                text = message; textSize = 15f; gravity = Gravity.CENTER; setPadding(0, dp(16), 0, dp(24))
            })
            addView(Button(this@MainActivity).apply {
                text = "重新连接"
                setOnClickListener { failedDomains.clear(); if (directory != null) connectSites() else showAddressDialog(false) }
            })
            if (BuildConfig.DEBUG) addView(Button(this@MainActivity).apply {
                text = "修改站点地址"; setOnClickListener { showAddressDialog(true) }
            })
        }
        errorView = container
        root.addView(container, FrameLayout.LayoutParams(-1, -1))
        if (::progress.isInitialized) progress.visibility = View.GONE
    }

    private fun handleBridge(raw: String, proxy: JavaScriptReplyProxy) {
        if (raw.length > 262_144) return
        var reply: BridgeReply? = null
        try {
            val request = JSONObject(raw)
            reply = BridgeReply(request.optString("id").take(128), proxy)
            val url = request.optString("url")
            when (request.optString("action")) {
                "copyText" -> {
                    val text = request.optString("text")
                    require(text.isNotEmpty() && text.length <= 100_000) { "复制文字过长或为空" }
                    (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("社区文字", text))
                    complete(reply, true, "")
                }
                "share" -> {
                    val safeUrl = policy?.resolveDownload(url) ?: error("站点尚未连接")
                    val title = request.optString("title").take(300)
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, title)
                        putExtra(Intent.EXTRA_TEXT, "$title\n$safeUrl")
                    }
                    startActivity(Intent.createChooser(intent, "分享链接"))
                    complete(reply, true, "")
                }
                "appInfo" -> complete(reply, true, "", JSONObject().put("version", BuildConfig.VERSION_NAME).put("versionCode", BuildConfig.VERSION_CODE).put("imageCacheVersion", 1).put("domainVersion", 1).put("qrLoginVersion", 1).put("buildType", if (BuildConfig.DEBUG) "debug" else "release"))
                "login.scan" -> {
                    require(scanReply == null) { "扫码正在进行中" }
                    scanReply = reply
                    try {
                        IntentIntegrator(this).setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
                            .setPrompt("扫描电脑端个人设置中的 App 登录二维码")
                            .setBeepEnabled(false).setOrientationLocked(false).setRequestCode(REQUEST_SCAN).initiateScan()
                    } catch (error: Exception) { scanReply = null; throw error }
                }
                "site.sync" -> { syncDomains(); complete(reply, true, "") }
                "site.reconnect" -> { failedDomains.clear(); connectSites(reply = reply) }
                "image.session" -> {
                    val site = policy ?: error("站点尚未连接")
                    val uid = request.optLong("uid", -1)
                    require(uid >= 0)
                    val identity = ImageDiskCache.hash(site.forumUrl + "|user:" + uid)
                    val previous = preferences().getString("imageIdentity", null)
                    invalidateImages(); imageIdentity = identity
                    preferences().edit().putString("imageIdentity", identity).apply()
                    val target = reply
                    io.execute {
                        if (previous != identity) imageCache.clearPrivate()
                        runOnUiThread {
                            if (previous != identity) webView?.clearCache(true)
                            complete(target, true, "")
                        }
                    }
                }
                "image.prepare" -> {
                    val site = policy ?: error("站点尚未连接")
                    val identity = imageIdentity ?: error("账号状态尚未更新")
                    val safe = site.resolveImageDownload(url)
                    require(!(site.origin.startsWith("https:") && safe.startsWith("http:"))) { "图片需要安全连接" }
                    val parsed = Uri.parse(safe)
                    require(!safe.startsWith(site.appUrl + "__image_cache/") &&
                        !(site.isForumUrl(safe) && (parsed.path.orEmpty().endsWith("/misc.php") || parsed.path.orEmpty().contains("/api/app")))) { "此资源不使用图片缓存" }
                    val public = request.optBoolean("public", false)
                    val scope = if (public) "public-" + ImageDiskCache.hash(site.forumUrl) else "private-$identity"
                    val local = site.appUrl + "__image_cache/" + UUID.randomUUID().toString()
                    synchronized(imagePermits) {
                        while (imagePermits.size >= 2048) imagePermits.remove(imagePermits.keys.first())
                        imagePermits[local] = ImagePermit(safe, scope, public, request.optBoolean("force", false), imageEpoch)
                    }
                    complete(reply, true, "", JSONObject().put("url", local))
                }
                "image.status" -> {
                    val site = policy ?: error("站点尚未连接")
                    val safe = site.resolveImageDownload(url)
                    val status = synchronized(imagePermits) {
                        if (safe.startsWith(site.appUrl + "__image_cache/")) {
                            imagePermits[safe]?.takeIf { it.epoch == imageEpoch }?.status ?: 0
                        } else imageHttpErrors[safe] ?: 0
                    }
                    // Observe a completed request only; never fetch or authorize here.
                    complete(reply, true, "", JSONObject().put("status", status))
                }
                "image.stats", "image.clear" -> {
                    val clear = request.optString("action") == "image.clear"
                    if (clear) invalidateImages()
                    val target = reply
                    io.execute {
                        val removed = if (clear) imageCache.clear() else 0L
                        val bytes = imageCache.size()
                        runOnUiThread {
                            if (clear) webView?.clearCache(true)
                            complete(target, true, "", JSONObject().put("bytes", bytes).put("removed", removed).put("limit", 200L * 1024 * 1024).put("maxAgeDays", 7))
                        }
                    }
                }
                "appearance" -> {
                    val theme = request.optString("theme")
                    require(theme == "dark" || theme == "light")
                    val mode = request.optString("mode", theme)
                    require(mode in setOf("system", "light", "dark"))
                    preferences().edit().putString("appearanceMode", mode).apply()
                    // In system mode, Android is authoritative even if WebView's
                    // prefers-color-scheme remains light under the host theme.
                    updateAppearance()
                    notifySystemAppearance()
                    complete(reply, true, "")
                }
                "saveImage" -> startDownload(url, request.optString("filename", "community-image.png"), true, reply)
                "download" -> startDownload(url, request.optString("filename", "attachment"), false, reply)
                "openExternal" -> complete(reply, openExternal(url), "")
                else -> complete(reply, false, "未知操作")
            }
        } catch (error: Exception) { complete(reply, false, error.message ?: "操作失败") }
    }

    private fun complete(reply: BridgeReply?, ok: Boolean, message: String, data: JSONObject? = null) {
        if (reply != null && WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) runCatching {
            reply.proxy.postMessage(JSONObject().put("id", reply.id).put("ok", ok).put("message", message).put("data", data).toString())
        }
        if (message.isNotEmpty() && !isDestroyed) toast(message)
    }

    private fun startDownload(url: String, filename: String, imageOnly: Boolean, reply: BridgeReply?) {
        val site = policy ?: return
        if (downloadBusy) { complete(reply, false, "已有文件正在保存，请稍后重试"); return }
        val safeUrl = runCatching { if (imageOnly) site.resolveImageDownload(url) else site.resolveDownload(url) }.getOrElse {
            complete(reply, false, it.message.orEmpty()); return
        }
        val userAgent = webView?.settings?.userAgentString.orEmpty()
        downloadBusy = true
        toast("正在准备保存…")
        io.execute {
            runCatching { SiteDownloader(site, userAgent).fetch(safeUrl, imageOnly, cacheDir) }.onSuccess { downloaded ->
                runOnUiThread {
                    if (isDestroyed) { downloaded.file.delete(); return@runOnUiThread }
                    val safeName = filename.substringAfterLast('/').substringAfterLast('\\')
                        .replace(Regex("[\\p{Cntrl}:*?\"<>|]"), "_").take(120).ifBlank { "attachment" }
                    if (imageOnly && Build.VERSION.SDK_INT >= 29) saveImage(downloaded, safeName, reply)
                    else {
                        pendingDownload = PendingDownload(downloaded, reply)
                        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = downloaded.mime
                            putExtra(Intent.EXTRA_TITLE, safeName)
                        }
                        try { startActivityForResult(intent, REQUEST_SAVE) }
                        catch (_: ActivityNotFoundException) {
                            downloaded.file.delete()
                            pendingDownload = null
                            downloadBusy = false
                            complete(reply, false, "设备没有可用的文件保存器")
                        }
                    }
                }
            }.onFailure { error -> runOnUiThread {
                downloadBusy = false
                complete(reply, false, error.message ?: "保存失败，请稍后重试")
            } }
        }
    }

    @androidx.annotation.RequiresApi(29)
    private fun saveImage(downloaded: DownloadedFile, name: String, reply: BridgeReply?) {
        io.execute {
            var destination: Uri? = null
            val result = runCatching {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, downloaded.mime)
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + getString(R.string.app_name))
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                destination = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: error("无法创建图片")
                contentResolver.openOutputStream(destination!!)?.use { output ->
                    downloaded.file.inputStream().use { it.copyTo(output) }
                } ?: error("无法写入图片")
                contentResolver.update(destination!!, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            }
            if (result.isFailure) destination?.let { contentResolver.delete(it, null, null) }
            downloaded.file.delete()
            runOnUiThread {
                downloadBusy = false
                complete(reply, result.isSuccess, if (result.isSuccess) "图片已保存到相册" else "图片保存失败，请重试")
            }
        }
    }

    @Deprecated("Platform result callback retained to keep the shell small")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_SCAN) {
            val target = scanReply ?: return
            scanReply = null
            val result = IntentIntegrator.parseActivityResult(resultCode, data)
            if (result?.contents == null) { complete(target, false, "扫码已取消。"); return }
            runCatching { LoginQr.parse(result.contents) }.onSuccess {
                complete(target, true, "", JSONObject().put("site_id", it.siteId).put("uid", it.uid).put("key", it.key))
            }.onFailure { complete(target, false, it.message.orEmpty()) }
        } else if (requestCode == REQUEST_UPLOAD) {
            val result = if (resultCode != RESULT_OK) null
                else data?.clipData?.let { clip -> Array(clip.itemCount) { clip.getItemAt(it).uri } }
                    ?: data?.data?.let { arrayOf(it) } ?: cameraUri?.let { arrayOf(it) }
            fileCallback?.onReceiveValue(result?.filter { it.scheme == "content" }?.toTypedArray()?.takeIf { it.isNotEmpty() })
            fileCallback = null
            cameraUri = null
        } else if (requestCode == REQUEST_SAVE) {
            val download = pendingDownload ?: return
            pendingDownload = null
            val target = if (resultCode == RESULT_OK) data?.data else null
            if (target == null) {
                download.file.file.delete()
                downloadBusy = false
                complete(download.reply, false, "已取消保存")
                return
            }
            io.execute {
                val result = runCatching {
                    contentResolver.openOutputStream(target)?.use { output ->
                        download.file.file.inputStream().use { it.copyTo(output) }
                    } ?: error("无法打开目标文件")
                }
                download.file.file.delete()
                runOnUiThread {
                    downloadBusy = false
                    complete(download.reply, result.isSuccess, if (result.isSuccess) "文件已保存" else "文件保存失败")
                }
            }
        }
    }

    private fun openExternal(url: String): Boolean {
        try {
            val parsed = Uri.parse(url)
            val scheme = parsed.scheme?.lowercase()
            if (scheme == "intent") {
                val incoming = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                val data = incoming.data ?: return false
                val expectedPackage = paymentPackage(data.scheme) ?: return false
                if (incoming.`package` != null && incoming.`package` != expectedPackage) return false
                // Build a new intent: never inherit component, extras, selectors or fallback URLs.
                startActivity(Intent(Intent.ACTION_VIEW, data).addCategory(Intent.CATEGORY_BROWSABLE).setPackage(expectedPackage))
            } else {
                val payment = paymentPackage(scheme)
                val intent = when {
                    payment != null -> Intent(Intent.ACTION_VIEW, parsed).addCategory(Intent.CATEGORY_BROWSABLE).setPackage(payment)
                    SitePolicy.isExternalWebUrl(url) -> Intent(Intent.ACTION_VIEW, parsed).addCategory(Intent.CATEGORY_BROWSABLE)
                    else -> { toast("暂不支持此链接"); return false }
                }
                startActivity(intent)
            }
            return true
        } catch (_: ActivityNotFoundException) { toast("没有安装对应的应用") }
        catch (_: Exception) { toast("链接无法打开") }
        return false
    }

    private fun paymentPackage(scheme: String?): String? = when (scheme?.lowercase()) {
        "alipays", "alipay" -> "com.eg.android.AlipayGphone"
        "weixin" -> "com.tencent.mm"
        "mqqapi" -> "com.tencent.mobileqq"
        else -> null
    }

    @Deprecated("API 33+ uses OnBackInvokedDispatcher")
    override fun onBackPressed() { navigateBack() }

    private fun navigateBack() {
        val view = webView
        if (connecting) return
        if (errorView != null && directory != null) { failedDomains.clear(); connectSites(); return }
        if (view != null && policy?.isAppPage(view.url) == true) {
            if (backPending) return
            backPending = true
            view.evaluateJavascript("(function(){try{return !!(window.DiscuzApp && typeof window.DiscuzApp.handleBack === 'function' && window.DiscuzApp.handleBack());}catch(e){return false;}})();") { handled ->
                backPending = false
                if (!isDestroyed && webView === view && handled != "true") navigateHistory(view)
            }
            return
        }
        navigateHistory(view)
    }

    private fun navigateHistory(view: WebView?) {
        if (view?.canGoBack() == true) { view.goBack(); return }
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastBackAt < 2000) finish()
        else { lastBackAt = now; toast("再按一次返回退出社区") }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateAppearance()
        notifySystemAppearance()
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
        updateAppearance()
        notifySystemAppearance()
        if (policy?.isAppPage(webView?.url) == true) {
            syncDomains()
            webView?.evaluateJavascript("window.dispatchEvent(new Event('discuz:resume'));", null)
        }
    }

    override fun onPause() {
        CookieManager.getInstance().flush()
        webView?.onPause()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        webView?.saveState(outState)
        outState.putString("lastUrl", webView?.url)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        connectionEpoch++
        invalidateImages()
        fileCallback?.onReceiveValue(null)
        fileCallback = null
        scanReply = null
        pendingCameraChooser = null
        pendingDownload?.file?.file?.delete()
        pendingDownload = null
        root.removeAllViews()
        webView?.apply { stopLoading(); destroy() }
        webView = null
        io.shutdown()
        connections.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_UPLOAD = 100
        private const val REQUEST_SAVE = 101
        private const val REQUEST_SCAN = 102
        private const val REQUEST_CAMERA_PERMISSION = 103
    }
}
