package com.muhannadsatlink.cnmovie

// يفحص GitHub عن نسخة أحدث وينزّلها وينصّبها
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object UpdateChecker {
    private const val API = "https://api.github.com/repos/tara44x-code/CNMovie/releases/latest"
    private const val KEY = "lastUpdateCheck"

    fun auto(a: AppCompatActivity) {
        if (System.currentTimeMillis() - Prefs.getLong(a, KEY) < 6 * 3600_000L) return
        check(a, manual = false)
    }

    fun check(a: AppCompatActivity, manual: Boolean) {
        if (manual) Toast.makeText(a, "جارِ فحص التحديثات…", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val c = URL(API).openConnection() as HttpURLConnection
                c.connectTimeout = 10000; c.readTimeout = 10000
                c.setRequestProperty("Accept", "application/vnd.github+json")
                val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                c.disconnect()
                Prefs.setLong(a, KEY, System.currentTimeMillis())
                val tag = j.optString("tag_name").removePrefix("v")
                val url = j.optJSONArray("assets")?.optJSONObject(0)?.optString("browser_download_url") ?: return@Thread
                val cur = BuildConfigInfo.version(a)
                a.runOnUiThread {
                    if (newer(tag, cur)) ask(a, tag, url)
                    else if (manual) Toast.makeText(a, "عندك أحدث نسخة ($cur)", Toast.LENGTH_SHORT).show()
                }
            } catch (_: Throwable) {
                if (manual) a.runOnUiThread { Toast.makeText(a, "تعذّر فحص التحديثات", Toast.LENGTH_SHORT).show() }
            }
        }.start()
    }

    private fun newer(a: String, b: String): Boolean {
        val x = a.split('.').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val p = x.getOrElse(i) { 0 }; val q = y.getOrElse(i) { 0 }
            if (p != q) return p > q
        }
        return false
    }

    private fun ask(a: AppCompatActivity, ver: String, url: String) {
        if (a.isFinishing) return
        AlertDialog.Builder(a, R.style.Theme_CNMovie_Dialog)
            .setTitle("تحديث جديد • $ver")
            .setMessage("نسخة جديدة من CN.MOVIE جاهزة. تريد تحمّلها وتنصّبها هسه؟")
            .setPositiveButton("تحديث الآن") { _, _ -> download(a, url, ver) }
            .setNegativeButton("بعدين", null)
            .show().window?.decorView?.setBackgroundColor(Color.parseColor("#12101F"))
    }

    private fun download(a: AppCompatActivity, url: String, ver: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !a.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(a, "فعّل «السماح من هذا المصدر» لـ CN.MOVIE وارجع اضغط تحديث", Toast.LENGTH_LONG).show()
            a.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${a.packageName}")))
            return
        }
        val dm = a.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val name = "CN.MOVIE-$ver.apk"
        a.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.let { java.io.File(it, name).delete() }
        val id = dm.enqueue(DownloadManager.Request(Uri.parse(url)).apply {
            setTitle("تحديث CN.MOVIE $ver")
            setMimeType("application/vnd.android.package-archive")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            setDestinationInExternalFilesDir(a, Environment.DIRECTORY_DOWNLOADS, name)
        })
        Toast.makeText(a, "جارِ تحميل التحديث…", Toast.LENGTH_SHORT).show()
        val app = a.applicationContext
        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, i: Intent) {
                if (i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) != id) return
                try { app.unregisterReceiver(this) } catch (_: Throwable) { }
                val uri = dm.getUriForDownloadedFile(id) ?: return
                try {
                    app.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Throwable) { Toast.makeText(app, "افتح التحديث من الإشعارات", Toast.LENGTH_LONG).show() }
            }
        }
        ContextCompat.registerReceiver(app, r, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), ContextCompat.RECEIVER_EXPORTED)
    }
}
