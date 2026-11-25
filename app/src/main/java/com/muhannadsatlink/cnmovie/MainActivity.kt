package com.muhannadsatlink.cnmovie

// CN.MOVIE — تطوير: مهند سات لنك

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
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
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    companion object {
        const val HOME_URL = "https://cinemana.shabakaty.com/home"
        // النطاقات التي تفتح داخل التطبيق؛ أي رابط غيرها يفتح بالمتصفح
        val INTERNAL_HOSTS = listOf("shabakaty.com", "earthlink.iq")
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

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

        // نبضة توهّج لشعار البداية
        findViewById<View>(R.id.splashLogo).apply {
            alpha = 0f; scaleX = 0.85f; scaleY = 0.85f
            animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(700).start()
        }
        // إخفاء شاشة البداية بعد 4 ثواني كحد أقصى حتى لو الصفحة بطيئة
        splash.postDelayed({ hideSplash() }, 4000)

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
        }
        // حفظ تسجيل الدخول (الكوكيز) بين مرات فتح التطبيق
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.setBackgroundColor(Color.parseColor("#07060F"))

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                val host = uri.host ?: return false
                if (uri.scheme == "http" || uri.scheme == "https") {
                    if (INTERNAL_HOSTS.any { host == it || host.endsWith(".$it") }) return false
                }
                openExternal(uri)
                return true
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                progress.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView, url: String?) {
                progress.visibility = View.GONE
                CookieManager.getInstance().flush()
                hideSplash()
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
                window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }

            override fun onHideCustomView() {
                exitFullscreen()
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

        findViewById<ImageButton>(R.id.btnHome).setOnClickListener { web.loadUrl(HOME_URL) }
        findViewById<ImageButton>(R.id.btnRefresh).setOnClickListener { web.reload() }
        findViewById<ImageButton>(R.id.btnAbout).setOnClickListener { showAbout() }
        findViewById<Button>(R.id.btnRetry).setOnClickListener {
            offline.visibility = View.GONE
            web.reload()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
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
    }

    private fun hideSplash() {
        if (splashHidden) return
        splashHidden = true
        splash.animate().alpha(0f).setDuration(400).withEndAction { splash.visibility = View.GONE }.start()
    }

    private fun exitFullscreen() {
        val v = customView ?: return
        fullscreen.removeView(v)
        fullscreen.visibility = View.GONE
        content.visibility = View.VISIBLE
        customView = null
        customCallback?.onCustomViewHidden()
        customCallback = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
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
