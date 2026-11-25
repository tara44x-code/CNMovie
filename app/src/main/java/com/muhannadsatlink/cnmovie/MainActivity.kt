package com.muhannadsatlink.cnmovie

// CN.MOVIE — تطوير: مهند سات لنك

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.os.SystemClock
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.view.Gravity
import android.view.ViewGroup
import android.os.Environment
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import android.webkit.WebResourceResponse
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : AppCompatActivity() {

    companion object {
        const val HOME_URL = "https://cinemana.shabakaty.com/home"
        // النطاقات التي تفتح داخل التطبيق؛ أي رابط غيرها يفتح بالمتصفح
        val INTERNAL_HOSTS = listOf(
            "shabakaty.com", "earthlink.iq",
            // تسجيل الدخول بـ Google و Facebook يبقى داخل التطبيق
            "google.com", "google.iq", "googleapis.com", "gstatic.com", "googleusercontent.com", "accounts.youtube.com",
            "facebook.com", "fb.com", "facebook.net", "fbcdn.net"
        )
        fun isInternal(host: String) = INTERNAL_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    private lateinit var web: WebView
    private lateinit var progress: ProgressBar
    private lateinit var splash: View
    private lateinit var offline: View
    private lateinit var fullscreen: FrameLayout
    private lateinit var content: View

    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private var lastBack = 0L
    private var splashHidden = false
    private var pageReady = false
    private val introStart = SystemClock.uptimeMillis()
    private val INTRO_MS = 7000L   // مدة شعار البداية
    private val INTRO_MAX_MS = 11000L
    private val helperJs by lazy { assets.open("cn.js").bufferedReader(Charsets.UTF_8).use { it.readText() } }
    private lateinit var tabs: List<LinearLayout>
    private lateinit var player: PlayerGestureLayer
    private lateinit var root: FrameLayout
    private var popupHolder: View? = null
    private var popupWeb: WebView? = null
    private lateinit var cleanUa: String
    private lateinit var downloader: MediaDownloader

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        // شاشة بداية وحدة تبقى لحد ما تجهز الصفحة (حد أقصى 3 ثواني)
        installSplashScreen()
        super.onCreate(savedInstanceState)
        CrashLog.install(applicationContext)
        setContentView(R.layout.activity_main)

        web = findViewById(R.id.web)
        progress = findViewById(R.id.progress)
        splash = findViewById(R.id.splash)
        offline = findViewById(R.id.offline)
        fullscreen = findViewById(R.id.fullscreen)
        content = findViewById(R.id.content)

        // شعار CN بالأبيض و .MOVIE بالوردي النيون
        val brand = SpannableString("CN.MOVIE")
        brand.setSpan(ForegroundColorSpan(Color.parseColor("#FF2BD6")), 2, 8, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        findViewById<TextView>(R.id.brand).text = brand

        // شعار البداية المتحرك (GIF) والموقع يحمّل وراه
        val logo = findViewById<ImageView>(R.id.splashLogo)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val d = ImageDecoder.decodeDrawable(ImageDecoder.createSource(resources, R.raw.intro))
                logo.setImageDrawable(d)
                (d as? AnimatedImageDrawable)?.start()
            } catch (_: Throwable) { }
        }
        logo.alpha = 0f
        logo.animate().alpha(1f).setDuration(500).start()
        splash.postDelayed({ maybeHideSplash() }, INTRO_MS)
        splash.postDelayed({ maybeHideSplash() }, INTRO_MAX_MS)

        // إعدادات الويب
        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            setSupportZoom(false)
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
        }
        // متصفح عادي بدل علامة WebView حتى تقبل Google تسجيل الدخول
        cleanUa = web.settings.userAgentString.replace("; wv", "").replace(Regex("Version/\\d+(\\.\\d+)* "), "")
        web.settings.userAgentString = cleanUa

        root = findViewById(android.R.id.content)
        player = PlayerGestureLayer(this, web).apply { visibility = View.GONE }
        root.addView(player, FrameLayout.LayoutParams(-1, -1))
        downloader = MediaDownloader(this, web)
        player.onDownload = { downloader.show() }
        // حفظ تسجيل الدخول (الكوكيز) بين مرات فتح التطبيق
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.setBackgroundColor(Color.parseColor("#07060F"))
        // الصفحة تبلّغنا لما تجهز (بعد تخطي صفحة «حمّل التطبيق»)
        web.addJavascriptInterface(object {
            @JavascriptInterface fun ready() { runOnUiThread { hideSplash() } }
        }, "CNApp")

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                if (uri.scheme == "intent") {
                    try { startActivity(Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)) } catch (_: Exception) { }
                    return true
                }
                val host = uri.host ?: return false
                if ((uri.scheme == "http" || uri.scheme == "https") && isInternal(host)) return false
                openExternal(uri)
                return true
            }

            // نراقب روابط الفيديو والترجمة حتى نگدر نحمّلها
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                downloader.onRequest(request.url.toString())
                return null
            }

            private var lastPath: String? = null
            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                val path = url?.let { Uri.parse(it).path }
                if (path != lastPath) { lastPath = path; downloader.clear() }
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                progress.visibility = View.VISIBLE
            }

            override fun onPageCommitVisible(view: WebView, url: String?) {
                view.evaluateJavascript(helperJs, null)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                progress.visibility = View.GONE
                view.evaluateJavascript(helperJs, null)
                CookieManager.getInstance().flush()
                // مهلة قصيرة حتى تنتخطى صفحة «حمّل التطبيق» قبل إخفاء شاشة البداية

            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    offline.visibility = View.VISIBLE
                    hideSplash()
                }
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.progress = newProgress
            }

            // تشغيل الفيديو بملء الشاشة
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (customView != null) { callback.onCustomViewHidden(); return }
                customView = view
                customCallback = callback
                fullscreen.addView(view, FrameLayout.LayoutParams(-1, -1))
                fullscreen.visibility = View.VISIBLE
                content.visibility = View.GONE
                player.target = view
                player.reset()
                player.visibility = View.VISIBLE
                player.bringToFront()
                setVideoImmersive(true)
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }

            override fun onHideCustomView() {
                exitFullscreen()
            }

            // نوافذ منبثقة (تسجيل الدخول بـ Google / Facebook)
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                val popup = openPopup()
                (resultMsg.obj as WebView.WebViewTransport).webView = popup
                resultMsg.sendToTarget()
                return true
            }
        }

        // التحميلات عبر مدير التنزيلات في أندرويد
        web.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            try {
                val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
                val req = DownloadManager.Request(Uri.parse(url)).apply {
                    setMimeType(mimeType)
                    addRequestHeader("cookie", CookieManager.getInstance().getCookie(url))
                    addRequestHeader("User-Agent", userAgent)
                    setTitle(name)
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "CN.MOVIE/$name")
                }
                (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
                Toast.makeText(this, R.string.download_started, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                openExternal(Uri.parse(url))
            }
        }

        // الشريط السفلي
        tabs = listOf(R.id.tabHome, R.id.tabCats, R.id.tabSearch, R.id.tabAccount).map { findViewById(it) }
        selectTab(0)
        tabs[0].setOnClickListener { selectTab(0); web.loadUrl(HOME_URL) }
        tabs[1].setOnClickListener { selectTab(1); runHelper("cats") }
        tabs[2].setOnClickListener { selectTab(2); runHelper("search") }
        tabs[3].setOnClickListener { selectTab(3); runHelper("account") }
        findViewById<ImageButton>(R.id.btnDownload).setOnClickListener { downloader.show() }
        findViewById<ImageButton>(R.id.btnRefresh).setOnClickListener { web.reload() }
        findViewById<ImageButton>(R.id.btnAbout).setOnClickListener { showAbout() }
        findViewById<Button>(R.id.btnRetry).setOnClickListener {
            offline.visibility = View.GONE
            web.reload()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    popupWeb != null -> if (popupWeb!!.canGoBack()) popupWeb!!.goBack() else closePopup()
                    customView != null -> exitFullscreen()
                    web.canGoBack() -> web.goBack()
                    System.currentTimeMillis() - lastBack < 2000 -> finish()
                    else -> {
                        lastBack = System.currentTimeMillis()
                        Toast.makeText(this@MainActivity, R.string.exit_hint, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        })

        if (savedInstanceState != null) web.restoreState(savedInstanceState) else web.loadUrl(HOME_URL)
        splash.postDelayed({ CrashLog.showIfAny(this) }, INTRO_MS + 800)
    }

    private fun selectTab(index: Int) {
        val on = Color.parseColor("#22E4FF")
        val off = Color.parseColor("#8C88A8")
        tabs.forEachIndexed { i, tab ->
            val c = if (i == index) on else off
            (tab.getChildAt(0) as ImageView).setColorFilter(c)
            (tab.getChildAt(1) as TextView).apply {
                setTextColor(c)
                setShadowLayer(if (i == index) 14f else 0f, 0f, 0f, on)
            }
        }
    }

    private fun runHelper(action: String) {
        web.evaluateJavascript("$helperJs\n;(window.CN ? CN.$action() : 'no')") { result ->
            if (result?.contains("ok") == true) {
                if (action == "search") {
                    web.requestFocus()
                    web.postDelayed({
                        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                            .showSoftInput(web, InputMethodManager.SHOW_IMPLICIT)
                    }, 450)
                }
            } else {
                Toast.makeText(this, R.string.tab_wait, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // تُستدعى لما الصفحة تجهز
    private fun hideSplash() {
        pageReady = true
        maybeHideSplash()
    }

    private fun maybeHideSplash() {
        if (splashHidden) return
        val t = SystemClock.uptimeMillis() - introStart
        if (t < INTRO_MS) return
        if (!pageReady && t < INTRO_MAX_MS) return
        splashHidden = true
        splash.animate().alpha(0f).setDuration(450).withEndAction { splash.visibility = View.GONE }.start()
    }

    private fun exitFullscreen() {
        val v = customView ?: return
        fullscreen.removeView(v)
        fullscreen.visibility = View.GONE
        content.visibility = View.VISIBLE
        customView = null
        customCallback?.onCustomViewHidden()
        customCallback = null
        player.visibility = View.GONE
        player.target = null
        player.restoreBrightness()
        setVideoImmersive(false)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    private fun setVideoImmersive(on: Boolean) {
        val ctl = WindowCompat.getInsetsController(window, window.decorView)
        if (on) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            ctl.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            ctl.show(WindowInsetsCompat.Type.systemBars())
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode = if (on) WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
            window.attributes = lp
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun openPopup(): WebView {
        closePopup()
        val dp = resources.displayMetrics.density
        val holder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#07060F"))
            isClickable = true
        }
        val bar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.topbar_bg)
            setPadding((16 * dp).toInt(), 0, (8 * dp).toInt(), 0)
        }
        bar.addView(TextView(this).apply {
            text = "تسجيل الدخول"
            setTextColor(Color.WHITE)
            textSize = 17f
            setShadowLayer(12f, 0f, 0f, Color.parseColor("#22E4FF"))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(Button(this).apply {
            text = getString(R.string.close)
            setTextColor(Color.WHITE)
            setBackgroundResource(R.drawable.neon_pill)
            setOnClickListener { closePopup() }
        }, LinearLayout.LayoutParams(-2, (40 * dp).toInt()))
        holder.addView(bar, LinearLayout.LayoutParams(-1, (60 * dp).toInt()))

        val pw = WebView(this)
        with(pw.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false)
            userAgentString = cleanUa
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(pw, true)
        pw.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                // بعد نجاح الدخول ورجوعه لسينمانا: نسكر النافذة ونحمّلها بالواجهة الرئيسية
                if (u.host?.endsWith("shabakaty.com") == true) {
                    closePopup()
                    web.loadUrl(u.toString())
                    return true
                }
                return u.scheme != "http" && u.scheme != "https"
            }
        }
        pw.webChromeClient = object : WebChromeClient() {
            override fun onCloseWindow(window: WebView) {
                closePopup()
            }
        }
        holder.addView(pw, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(holder, FrameLayout.LayoutParams(-1, -1))
        popupHolder = holder
        popupWeb = pw
        return pw
    }

    private fun closePopup() {
        popupWeb?.let { it.stopLoading(); it.destroy() }
        popupHolder?.let { (it.parent as? ViewGroup)?.removeView(it) }
        popupWeb = null
        popupHolder = null
        CookieManager.getInstance().flush()
    }

    private fun openExternal(uri: Uri) {
        try { startActivity(Intent(Intent.ACTION_VIEW, uri)) } catch (_: Exception) { }
    }

    private fun showAbout() {
        AlertDialog.Builder(this, R.style.Theme_CNMovie_Dialog)
            .setTitle(R.string.about_title)
            .setIcon(R.drawable.logo_cn)
            .setMessage(R.string.about_body)
            .setPositiveButton(R.string.close, null)
            .show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
    }
}
