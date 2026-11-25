package com.muhannadsatlink.cnmovie

// يحفظ سبب أي توقف حتى يطلع بالتشغيلة الجاية ونگدر نصلّحه
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File

object CrashLog {
    private fun file(c: Context) = File(c.filesDir, "crash.txt")

    fun install(c: Context) {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            save(c, e)
            prev?.uncaughtException(t, e)
        }
    }

    fun save(c: Context, e: Throwable) {
        try {
            file(c).writeText("CN.MOVIE ${BuildConfigInfo.version(c)}\n" + Log.getStackTraceString(e).take(6000))
        } catch (_: Throwable) { }
    }

    fun showIfAny(a: AppCompatActivity) {
        val f = file(a)
        if (!f.exists()) return
        val text = f.readText()
        f.delete()
        AlertDialog.Builder(a, R.style.Theme_CNMovie_Dialog)
            .setTitle("تقرير آخر توقف")
            .setMessage("التطبيق توقف آخر مرة. انسخ التقرير ودزّه للمطوّر حتى يتصلّح:\n\n" + text.take(1500))
            .setPositiveButton("نسخ التقرير") { _, _ ->
                (a.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("CN.MOVIE crash", text))
                Toast.makeText(a, "انتسخ التقرير", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.close, null)
            .show().window?.decorView?.setBackgroundColor(Color.parseColor("#12101F"))
    }
}

object BuildConfigInfo {
    fun version(c: Context): String = try {
        c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: ""
    } catch (_: Throwable) { "" }
}
