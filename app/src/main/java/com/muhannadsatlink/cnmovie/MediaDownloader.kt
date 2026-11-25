package com.muhannadsatlink.cnmovie

// CN.MOVIE — تحميل الأفلام والترجمة بعدة صيغ (SRT / VTT / ASS)
// تطوير: مهند سات لنك

import android.app.DownloadManager
import android.content.ContentValues
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.CookieManager
import android.webkit.WebView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Collections
import kotlin.math.min

class MediaDownloader(private val act: AppCompatActivity, private val web: WebView) {

    private data class Sub(val url: String, val label: String)
    private data class Cue(val start: Long, val end: Long, val text: String)

    /** روابط الفيديو والترجمة اللي مرّت بالشبكة أثناء التصفح */
    val seen: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet())

    fun onRequest(url: String) {
        val p = url.substringBefore('?').lowercase()
        if (VIDEO_EXT.any { p.endsWith(it) } || SUB_EXT.any { p.endsWith(it) }) {
            synchronized(seen) {
                seen.remove(url); seen.add(url)
                while (seen.size > 40) seen.remove(seen.first())
            }
        }
    }

    fun clear() = seen.clear()

    // ---------- النافذة ----------
    fun show() {
        web.evaluateJavascript(SCAN_JS) { raw ->
            val videos = LinkedHashMap<String, String>()
            val subs = LinkedHashMap<String, String>()
            try {
                val json = JSONObject(org.json.JSONTokener(raw).nextValue() as String)
                val v = json.getJSONArray("v")
                for (i in 0 until v.length()) addVideo(videos, v.getString(i))
                val s = json.getJSONArray("s")
                for (i in 0 until s.length()) {
                    val o = s.getJSONObject(i)
                    addSub(subs, o.getString("src"), o.optString("label"))
                }
            } catch (_: Exception) { }
            synchronized(seen) {
                seen.reversed().forEach { u ->
                    val p = u.substringBefore('?').lowercase()
                    if (SUB_EXT.any { p.endsWith(it) }) addSub(subs, u, "") else addVideo(videos, u)
                }
            }
            try {
                buildDialog(videos, subs.map { Sub(it.key, it.value) })
            } catch (e: Throwable) {
                CrashLog.save(act, e)
                toast("صار خطأ بنافذة التحميل، انحفظ تقريره")
            }
        }
    }

    private fun addVideo(map: MutableMap<String, String>, u: String) {
        if (!u.startsWith("http") || map.containsKey(u)) return
        val p = u.substringBefore('?').lowercase()
        if (p.endsWith(".m3u8")) return // بث مجزّأ؛ ما ينحفظ كملف واحد
        val q = Regex("(2160|1440|1080|720|576|480|360|240)p?").find(p)?.groupValues?.get(1)
        map[u] = if (q != null) "${q}p" else "جودة ${map.size + 1}"
    }

    private fun addSub(map: MutableMap<String, String>, u: String, label: String) {
        if (!u.startsWith("http") || map.containsKey(u)) return
        val name = label.ifBlank {
            val p = u.substringBefore('?').lowercase()
            when {
                Regex("(^|[^a-z])(ar|ara|arabic)([^a-z]|$)").containsMatchIn(p) -> "عربي"
                Regex("(^|[^a-z])(en|eng|english)([^a-z]|$)").containsMatchIn(p) -> "إنجليزي"
                else -> "ترجمة ${map.size + 1}"
            }
        }
        map[u] = name
    }

    // ---------- النافذة الاحترافية ----------
    private class VInfo(val url: String, var quality: String) {
        var ext = "MP4"; var size = -1L; var w = 0; var h = 0; var done = false
    }

    private var subFormat = "SRT"

    private fun buildDialog(videos: Map<String, String>, subs: List<Sub>) {
        val dp = act.resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val cyan = Color.parseColor("#22E4FF"); val pink = Color.parseColor("#FF2BD6")
        val muted = Color.parseColor("#A9A4C6")

        // الترجمة العربية أولاً
        val ordered = subs.sortedBy { if (it.label.contains("عرب") || it.label.lowercase().startsWith("ar")) 0 else 1 }
        val arabic = ordered.firstOrNull()
        val infos = videos.map { (u, q) -> VInfo(u, q).also { it.ext = ext(u, "mp4").uppercase() } }

        val root = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(px(18), px(6), px(18), px(10)); layoutDirection = View.LAYOUT_DIRECTION_RTL }
        val scroll = ScrollView(act).apply { addView(root) }

        fun section(t: String) = TextView(act).apply {
            text = t; setTextColor(cyan); textSize = 14f; setTypeface(typeface, Typeface.BOLD)
            setPadding(0, px(14), 0, px(6)); setShadowLayer(10f, 0f, 0f, cyan)
        }
        fun card() = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(14), px(12), px(14), px(12))
            background = GradientDrawable().apply { setColor(Color.parseColor("#0E0C1A")); cornerRadius = 14 * dp; setStroke(px(1), Color.parseColor("#2A2540")) }
            isClickable = true; isFocusable = true
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(8) }
        }

        // خيارات الترجمة
        val withSub = CheckBox(act).apply {
            text = if (arabic != null) "مع ترجمة ${arabic.label} (نفس اسم الفيلم)" else "لا توجد ترجمة لهذا الفيلم"
            isChecked = arabic != null; isEnabled = arabic != null
            setTextColor(Color.WHITE); textSize = 15f
            buttonTintList = android.content.res.ColorStateList.valueOf(pink)
        }
        val chips = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, px(4), 0, px(4)) }
        val chipViews = ArrayList<TextView>()
        fun paintChips() = chipViews.forEach { c ->
            val on = c.text == subFormat
            c.setTextColor(if (on) Color.WHITE else muted)
            c.background = GradientDrawable().apply {
                cornerRadius = 18 * dp
                if (on) { colors = intArrayOf(pink, Color.parseColor("#A23BFF")); orientation = GradientDrawable.Orientation.LEFT_RIGHT }
                else { setColor(Color.parseColor("#12101F")); setStroke(px(1), Color.parseColor("#2A2540")) }
            }
        }
        listOf("SRT", "VTT", "ASS").forEach { f ->
            val c = TextView(act).apply {
                text = f; textSize = 14f; setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
                setPadding(px(18), px(7), px(18), px(7))
                setOnClickListener { subFormat = f; paintChips() }
            }
            chipViews += c
            chips.addView(c, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = px(8) })
        }
        paintChips()

        lateinit var dialog: AlertDialog
        val videoBox = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }

        fun detail(v: VInfo): String {
            if (!v.done) return "${v.ext}  •  جارِ فحص الحجم والدقة…"
            val parts = arrayListOf(v.ext)
            if (v.w > 0 && v.h > 0) parts += "${v.w}×${v.h}"
            if (v.size > 0) parts += size(v.size)
            return parts.joinToString("  •  ")
        }
        fun renderVideos() {
            videoBox.removeAllViews()
            val sorted = if (infos.all { it.done }) infos.sortedByDescending { it.h } else infos
            sorted.forEach { v ->
                val c = card()
                c.addView(TextView(act).apply {
                    text = "تحميل الفيلم — ${v.quality}"; setTextColor(Color.WHITE); textSize = 16f; setTypeface(typeface, Typeface.BOLD)
                })
                c.addView(TextView(act).apply { text = "\u200F" + detail(v); setTextColor(muted); textSize = 13f; setPadding(0, px(3), 0, 0) })
                c.setOnClickListener {
                    val base = "${title()} ${v.quality}"
                    downloadFile(v.url, "$base.${v.ext.lowercase()}")
                    if (withSub.isChecked && arabic != null) downloadSub(arabic.url, subFormat, base)
                    dialog.dismiss()
                }
                videoBox.addView(c)
            }
        }

        if (infos.isNotEmpty()) {
            root.addView(section("الفيلم"))
            root.addView(videoBox)
            renderVideos()
        }
        if (ordered.isNotEmpty()) {
            root.addView(withSub)
            root.addView(section("صيغة الترجمة"))
            root.addView(chips)
            root.addView(section("الترجمة فقط"))
            ordered.forEach { sb ->
                val c = card()
                c.addView(TextView(act).apply { text = "ترجمة ${sb.label}"; setTextColor(Color.WHITE); textSize = 15f; setTypeface(typeface, Typeface.BOLD) })
                c.addView(TextView(act).apply { text = "تنحفظ بالصيغة المختارة أعلاه"; setTextColor(muted); textSize = 12f })
                c.setOnClickListener { downloadSub(sb.url, subFormat, "${title()} ${sb.label}"); dialog.dismiss() }
                root.addView(c)
            }
        }
        if (infos.isEmpty() && ordered.isEmpty()) {
            root.addView(TextView(act).apply {
                text = "شغّل الفيلم أولاً لثواني، بعدين اضغط تحميل حتى نلقى روابط الفيديو والترجمة."
                setTextColor(Color.WHITE); textSize = 15f; setPadding(0, px(10), 0, px(10))
            })
        }

        dialog = AlertDialog.Builder(act, R.style.Theme_CNMovie_Dialog)
            .setTitle("تحميل")
            .setView(scroll)
            .setNegativeButton(R.string.close, null)
            .setNeutralButton("مجلد التحميلات") { _, _ ->
                try { act.startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)) } catch (_: Exception) { }
            }
            .show()
        dialog.window?.decorView?.setBackgroundColor(Color.parseColor("#12101F"))

        // فحص الحجم والدقة بالخلفية
        infos.forEach { v ->
            Thread {
                try { probe(v) } catch (_: Throwable) { v.done = true }
                act.runOnUiThread {
                    try { if (dialog.isShowing) renderVideos() } catch (e: Throwable) { CrashLog.save(act, e) }
                }
            }.start()
        }
    }

    private fun probe(v: VInfo) {
        val headers = HashMap<String, String>().apply {
            CookieManager.getInstance().getCookie(v.url)?.let { put("Cookie", it) }
            put("User-Agent", web.settings.userAgentString)
        }
        try {
            val c = URL(v.url).openConnection() as HttpURLConnection
            c.requestMethod = "GET"; c.connectTimeout = 8000; c.readTimeout = 8000
            headers.forEach { (k, x) -> c.setRequestProperty(k, x) }
            c.setRequestProperty("Range", "bytes=0-0")
            val cr = c.getHeaderField("Content-Range")
            v.size = cr?.substringAfter('/')?.toLongOrNull() ?: c.getHeaderField("Content-Length")?.toLongOrNull() ?: -1
            c.disconnect()
        } catch (_: Exception) { }
        try {
            mp4Dims(v.url, headers)?.let { (w, h) ->
                v.w = w; v.h = h
                if (v.quality.startsWith("جودة")) v.quality = "${std(min(w, h))}p"
            }
        } catch (_: Throwable) { }
        v.done = true
    }

    // ---------- قراءة دقة MP4 بدون مكتبات: نمشي على صناديق الملف لحد moov ونقرأ tkhd ----------
    private fun rangeGet(url: String, headers: Map<String, String>, start: Long, len: Int): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 10000
        headers.forEach { (k, x) -> c.setRequestProperty(k, x) }
        c.setRequestProperty("Range", "bytes=$start-${start + len - 1}")
        try {
            if (c.responseCode != 206 && start > 0) return ByteArray(0)
            val out = java.io.ByteArrayOutputStream()
            c.inputStream.use { inp ->
                val buf = ByteArray(16384)
                while (out.size() < len) {
                    val n = inp.read(buf, 0, minOf(buf.size, len - out.size()))
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
            }
            return out.toByteArray()
        } finally { c.disconnect() }
    }

    private fun u32(b: ByteArray, i: Int): Long =
        ((b[i].toLong() and 0xFF) shl 24) or ((b[i + 1].toLong() and 0xFF) shl 16) or ((b[i + 2].toLong() and 0xFF) shl 8) or (b[i + 3].toLong() and 0xFF)

    private fun mp4Dims(url: String, headers: Map<String, String>): Pair<Int, Int>? {
        var off = 0L
        repeat(12) {
            val hdr = rangeGet(url, headers, off, 16)
            if (hdr.size < 8) return null
            var size = u32(hdr, 0)
            val type = String(hdr, 4, 4, Charsets.ISO_8859_1)
            if (size == 1L && hdr.size >= 16) size = (u32(hdr, 8) shl 32) or u32(hdr, 12)
            if (size < 8) return null
            if (type == "moov") {
                val data = rangeGet(url, headers, off, minOf(size, 262144L).toInt())
                var i = 4
                while (i + 4 <= data.size) {
                    if (data[i] == 't'.code.toByte() && data[i + 1] == 'k'.code.toByte() && data[i + 2] == 'h'.code.toByte() && data[i + 3] == 'd'.code.toByte()) {
                        val end = i - 4 + u32(data, i - 4).toInt()
                        if (end in (i + 8)..data.size) {
                            val w = (u32(data, end - 8) ushr 16).toInt()
                            val h = (u32(data, end - 4) ushr 16).toInt()
                            if (w > 0 && h > 0) return w to h
                        }
                    }
                    i++
                }
                return null
            }
            if (type !in listOf("ftyp", "free", "skip", "mdat", "wide", "uuid", "pdin", "styp", "sidx", "moof")) return null
            off += size
        }
        return null
    }

    private fun std(h: Int) = listOf(2160, 1440, 1080, 720, 576, 480, 360, 240).firstOrNull { h >= it * 0.85 } ?: h

    private fun size(b: Long): String = when {
        b >= 1L shl 30 -> String.format("%.2f GB", b / 1073741824.0)
        b >= 1L shl 20 -> String.format("%.0f MB", b / 1048576.0)
        else -> String.format("%.0f KB", b / 1024.0)
    }

    // ---------- تحميل الملفات ----------
    private fun downloadFile(url: String, name: String) {
        try {
            val clean = safe(name)
            val req = DownloadManager.Request(Uri.parse(url)).apply {
                CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
                addRequestHeader("User-Agent", web.settings.userAgentString)
                web.url?.let { addRequestHeader("Referer", it) }
                setTitle(clean)
                setDescription("CN.MOVIE")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "CN.MOVIE/$clean")
            }
            (act.getSystemService(AppCompatActivity.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
            toast("بدأ التحميل: $clean")
        } catch (e: Exception) {
            toast("تعذّر التحميل")
        }
    }

    private fun downloadSub(url: String, format: String, base: String) {
        toast("جارِ تجهيز الترجمة…")
        Thread {
            try {
                val text = decode(fetch(url))
                val cues = parse(text)
                val out = when (format) {
                    "VTT" -> toVtt(cues)
                    "ASS" -> toAss(cues)
                    else -> toSrt(cues)
                }
                val name = safe("$base.${format.lowercase()}")
                save(name, out.toByteArray(Charsets.UTF_8))
                act.runOnUiThread { toast(if (cues.isEmpty()) "الملف فارغ أو صيغته غير معروفة" else "انحفظت الترجمة: Download/CN.MOVIE/$name") }
            } catch (e: Exception) {
                act.runOnUiThread { toast("تعذّر تحميل الترجمة") }
            }
        }.start()
    }

    private fun fetch(url: String): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 20000
        CookieManager.getInstance().getCookie(url)?.let { c.setRequestProperty("Cookie", it) }
        c.setRequestProperty("User-Agent", web.settings.userAgentString)
        return c.inputStream.use { it.readBytes() }.also { c.disconnect() }
    }

    private fun save(name: String, data: ByteArray) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/CN.MOVIE")
            }
            val uri = act.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("no uri")
            act.contentResolver.openOutputStream(uri)!!.use { it.write(data) }
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "CN.MOVIE")
            dir.mkdirs()
            File(dir, name).writeBytes(data)
        }
    }

    // ---------- تحويل الترجمة ----------
    private fun decode(b: ByteArray): String {
        var bytes = b
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) bytes = bytes.copyOfRange(3, bytes.size)
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) {
            String(bytes, Charset.forName("windows-1256")) // ترجمات عربية قديمة
        }
    }

    private fun parse(raw: String): List<Cue> {
        val t = raw.replace("\r\n", "\n").replace('\r', '\n')
        if (t.contains("[Events]") || t.contains("Dialogue:")) return parseAss(t)
        val cues = ArrayList<Cue>()
        val time = Regex("((?:\\d+:)?\\d{1,2}:\\d{2}[.,]\\d{1,3})\\s*-->\\s*((?:\\d+:)?\\d{1,2}:\\d{2}[.,]\\d{1,3})")
        for (block in t.split(Regex("\n\\s*\n"))) {
            val lines = block.trim('\n').lines()
            val idx = lines.indexOfFirst { time.containsMatchIn(it) }
            if (idx < 0) continue
            val m = time.find(lines[idx])!!
            val text = lines.drop(idx + 1).joinToString("\n").trim()
            if (text.isNotEmpty()) cues += Cue(ms(m.groupValues[1]), ms(m.groupValues[2]), text)
        }
        return cues
    }

    private fun parseAss(t: String): List<Cue> {
        val cues = ArrayList<Cue>()
        t.lines().filter { it.startsWith("Dialogue:") }.forEach { line ->
            val parts = line.removePrefix("Dialogue:").split(",", limit = 10)
            if (parts.size < 10) return@forEach
            val text = parts[9].replace(Regex("\\{[^}]*\\}"), "").replace("\\N", "\n").replace("\\n", "\n").trim()
            if (text.isNotEmpty()) cues += Cue(ms(parts[1].trim()), ms(parts[2].trim()), text)
        }
        return cues.sortedBy { it.start }
    }

    private fun ms(s: String): Long {
        val p = s.replace(',', '.').split(':')
        val sec = p.last().toDouble()
        val min = if (p.size >= 2) p[p.size - 2].toLong() else 0
        val hr = if (p.size >= 3) p[p.size - 3].toLong() else 0
        return ((hr * 3600 + min * 60) * 1000 + sec * 1000).toLong()
    }

    private fun stamp(ms: Long, sep: Char): String =
        String.format("%02d:%02d:%02d%c%03d", ms / 3600000, ms / 60000 % 60, ms / 1000 % 60, sep, ms % 1000)

    private fun toSrt(c: List<Cue>) = buildString {
        c.forEachIndexed { i, q -> append("${i + 1}\n${stamp(q.start, ',')} --> ${stamp(q.end, ',')}\n${q.text}\n\n") }
    }

    private fun toVtt(c: List<Cue>) = buildString {
        append("WEBVTT\n\n")
        c.forEach { q -> append("${stamp(q.start, '.')} --> ${stamp(q.end, '.')}\n${q.text}\n\n") }
    }

    private fun toAss(c: List<Cue>) = buildString {
        fun a(ms: Long) = String.format("%d:%02d:%02d.%02d", ms / 3600000, ms / 60000 % 60, ms / 1000 % 60, ms % 1000 / 10)
        append("[Script Info]\nTitle: CN.MOVIE\nScriptType: v4.00+\nPlayResX: 1920\nPlayResY: 1080\nWrapStyle: 0\n\n")
        append("[V4+ Styles]\nFormat: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding\n")
        append("Style: Default,Arial,64,&H00FFFFFF,&H00FFFFFF,&H00000000,&H80000000,-1,0,0,0,100,100,0,0,1,3,1,2,40,40,50,178\n\n")
        append("[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n")
        c.forEach { q -> append("Dialogue: 0,${a(q.start)},${a(q.end)},Default,,0,0,0,,${q.text.replace("\n", "\\N")}\n") }
    }

    // ---------- أدوات ----------
    private fun title(): String {
        val t = (web.title ?: "").replace(Regex("\\s*[|\\-–]\\s*(سينمانا|Cinemana).*$", RegexOption.IGNORE_CASE), "").trim()
        return t.ifBlank { "CN.MOVIE" }
    }

    private fun ext(u: String, def: String): String {
        val e = u.substringBefore('?').substringAfterLast('.', "").lowercase()
        return if (e.length in 2..4) e else def
    }

    private fun safe(n: String) = n.replace(Regex("[\\\\/:*?\"<>|]"), " ").replace(Regex("\\s+"), " ").trim().take(120)

    private fun toast(s: String) = Toast.makeText(act, s, Toast.LENGTH_LONG).show()

    companion object {
        val VIDEO_EXT = listOf(".mp4", ".mkv", ".webm", ".m4v", ".mov", ".m3u8")
        val SUB_EXT = listOf(".vtt", ".srt", ".ass", ".ssa")

        // يجمع روابط الفيديو والترجمة من الصفحة (وداخل iframe من نفس الموقع)
        private const val SCAN_JS = "(function(){var V=[],S=[];function w(d){try{" +
            "d.querySelectorAll('video').forEach(function(v){if(v.currentSrc)V.push(v.currentSrc);if(v.src)V.push(v.src);" +
            "v.querySelectorAll('source').forEach(function(s){if(s.src)V.push(s.src)});" +
            "v.querySelectorAll('track').forEach(function(t){if(t.src)S.push({src:t.src,label:t.label||t.srclang||''})})});" +
            "d.querySelectorAll('a[href]').forEach(function(a){var h=a.href.split('?')[0].toLowerCase();" +
            "if(/\\.(mp4|mkv|webm)$/.test(h))V.push(a.href);if(/\\.(vtt|srt|ass|ssa)$/.test(h))S.push({src:a.href,label:(a.innerText||'').trim()})});" +
            "d.querySelectorAll('iframe').forEach(function(f){try{w(f.contentDocument)}catch(e){}})}catch(e){}}" +
            "w(document);return JSON.stringify({v:V.filter(function(x){return x.indexOf('blob:')!==0}),s:S});})()"
    }
}
