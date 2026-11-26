package com.muhannadsatlink.cnmovie

// يلقى ملف الترجمة اللي بنفس اسم الفيلم بمجلد CN.MOVIE
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

object SubFinder {
    private val EXT = listOf("srt", "vtt", "ass", "ssa")

    private fun norm(n: String) = n.lowercase()
        .replace(Regex(" \\(\\d+\\)(?=\\.[a-z0-9]+$)"), "")          // (1) اللي يضيفه النظام للمكرر
        .substringBeforeLast('.')
        .replace(Regex("\\.(ar|ara|arabic|en|eng)$"), "")             // Name.ar.srt
        .replace(Regex("\\s*(2160|1440|1080|720|576|480|360|240)p$"), "") // بدون الجودة
        .replace(Regex("[._]+"), " ").trim()

    /** يرجّع (رابط الترجمة، اسمها) أو null */
    fun find(c: Context, videoName: String): Pair<Uri, String>? {
        val exactBase = videoName.substringBeforeLast('.').lowercase()
        val key = norm(videoName)
        val found = ArrayList<Pair<Uri, String>>()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val col = MediaStore.Downloads.EXTERNAL_CONTENT_URI
                c.contentResolver.query(col, arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME),
                    "${MediaStore.Downloads.RELATIVE_PATH} LIKE ?", arrayOf("%CN.MOVIE%"),
                    "${MediaStore.Downloads.DATE_ADDED} DESC")?.use { q ->
                    while (q.moveToNext()) {
                        val n = q.getString(1) ?: continue
                        if (EXT.any { n.lowercase().endsWith(".$it") }) found += ContentUris.withAppendedId(col, q.getLong(0)) to n
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "CN.MOVIE")
                dir.listFiles()?.sortedByDescending { it.lastModified() }?.forEach { f ->
                    if (EXT.any { f.name.lowercase().endsWith(".$it") }) found += Uri.fromFile(f) to f.name
                }
            }
        } catch (_: Throwable) { }
        // 1) نفس الاسم بالضبط  2) نفس الاسم بعد التنظيف  3) يبدي بنفس اسم الفيلم
        return found.firstOrNull { it.second.substringBeforeLast('.').lowercase() == exactBase }
            ?: found.firstOrNull { norm(it.second) == key }
            ?: found.firstOrNull { key.length >= 3 && (norm(it.second).startsWith(key) || key.startsWith(norm(it.second))) }
    }
}
