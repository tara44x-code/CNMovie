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

    private fun isSub(n: String) = n.lowercase().removeSuffix(".txt").let { x -> EXT.any { x.endsWith(".$it") } }

    /** الملفات القديمة اللي انحفظت name.srt.txt: نرجّعها name.srt */
    private fun fixTxt(c: Context, uri: Uri, name: String): String {
        if (!name.lowercase().endsWith(".txt") || !isSub(name)) return name
        val good = name.dropLast(4)
        return try {
            val v = android.content.ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, good)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
            }
            if (c.contentResolver.update(uri, v, null, null) > 0) good else name
        } catch (_: Throwable) { name }
    }

    private fun norm(n: String) = n.lowercase().removeSuffix(".txt")
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
                        if (isSub(n)) {
                            val u = ContentUris.withAppendedId(col, q.getLong(0))
                            found += u to fixTxt(c, u, n)
                        }
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "CN.MOVIE")
                dir.listFiles()?.sortedByDescending { it.lastModified() }?.forEach { f ->
                    if (isSub(f.name)) {
                        var ff = f
                        if (f.name.lowercase().endsWith(".txt")) { val g = File(f.parentFile, f.name.dropLast(4)); if (f.renameTo(g)) ff = g }
                        found += Uri.fromFile(ff) to ff.name
                    }
                }
            }
        } catch (_: Throwable) { }
        // 1) نفس الاسم بالضبط  2) نفس الاسم بعد التنظيف  3) يبدي بنفس اسم الفيلم
        return found.firstOrNull { it.second.lowercase().removeSuffix(".txt").substringBeforeLast('.') == exactBase }
            ?: found.firstOrNull { norm(it.second) == key }
            ?: found.firstOrNull { key.length >= 3 && (norm(it.second).startsWith(key) || key.startsWith(norm(it.second))) }
    }
}
