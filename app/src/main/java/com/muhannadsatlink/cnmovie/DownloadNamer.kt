package com.muhannadsatlink.cnmovie

// بعض الأجهزة (مثل شاومي) تبدّل اسم الملف بعد التحميل لاسم السيرفر (video.mp4)
// هنا نحفظ الاسم الصحيح ونرجّعه أول ما يكمل التحميل
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.provider.MediaStore
import java.io.File

object DownloadNamer {
    private fun p(c: Context) = c.getSharedPreferences("dlnames", Context.MODE_PRIVATE)

    fun remember(c: Context, id: Long, name: String) = p(c).edit().putString(id.toString(), name).apply()
    fun nameOf(c: Context, id: Long): String? = p(c).getString(id.toString(), null)

    /** يرجّع الاسم الصحيح؛ يرجّع true إذا الاسم صار صحيح */
    fun fix(c: Context, id: Long): Boolean {
        val want = nameOf(c, id) ?: return true
        val dm = c.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        var local: String? = null
        var ok = false
        try {
            dm.query(DownloadManager.Query().setFilterById(id))?.use { cur ->
                if (cur.moveToFirst()) {
                    ok = cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) == DownloadManager.STATUS_SUCCESSFUL
                    local = cur.getString(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
                }
            }
        } catch (_: Throwable) { }
        if (!ok || local == null) return false
        val path = Uri.parse(local).let { if (it.scheme == "file") it.path else null } ?: return false
        val f = File(path)
        if (f.name == want) return true
        var target = File(f.parentFile, want)
        var n = 1
        while (target.exists()) { target = File(f.parentFile, want.substringBeforeLast('.') + " ($n)." + want.substringAfterLast('.')); n++ }

        // 1) عن طريق MediaStore (التطبيق صاحب الملف)
        try {
            val files = MediaStore.Files.getContentUri("external")
            c.contentResolver.query(files, arrayOf(MediaStore.MediaColumns._ID), "${MediaStore.MediaColumns.DATA}=?", arrayOf(path), null)?.use { q ->
                if (q.moveToFirst()) {
                    val uri = android.content.ContentUris.withAppendedId(files, q.getLong(0))
                    val cv = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, target.name) }
                    if (c.contentResolver.update(uri, cv, null, null) > 0) {
                        CrashLog.note(c, "RENAME ms ok ${f.name} -> ${target.name}")
                        return true
                    }
                }
            }
        } catch (e: Throwable) { CrashLog.note(c, "RENAME ms ${e.javaClass.simpleName}: ${e.message}") }

        // 2) إعادة تسمية مباشرة
        return try {
            if (f.renameTo(target)) {
                MediaScannerConnection.scanFile(c, arrayOf(f.absolutePath, target.absolutePath), null, null)
                CrashLog.note(c, "RENAME file ok ${f.name} -> ${target.name}")
                true
            } else { CrashLog.note(c, "RENAME file failed ${f.absolutePath}"); false }
        } catch (e: Throwable) { CrashLog.note(c, "RENAME file ${e.message}"); false }
    }

    fun fixAll(c: Context) {
        p(c).all.keys.mapNotNull { it.toLongOrNull() }.forEach { fix(c, it) }
    }
}

/** يستلم «اكتمل التحميل» حتى لو التطبيق مسدود */
class DownloadDoneReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val id = i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
        if (id < 0) return
        val r = goAsync()
        Thread {
            try {
                // ننطي النظام لحظة يكمل تسميته، وبعدين نرجّع اسمنا
                Thread.sleep(1500)
                if (!DownloadNamer.fix(c, id)) { Thread.sleep(4000); DownloadNamer.fix(c, id) }
            } catch (_: Throwable) { } finally { r.finish() }
        }.start()
    }
}
