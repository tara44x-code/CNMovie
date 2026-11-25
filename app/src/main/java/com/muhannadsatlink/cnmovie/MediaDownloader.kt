package com.muhannadsatlink.cnmovie

// CN.MOVIE — تحميل الأفلام والترجمة بعدة صيغ (SRT / VTT / ASS)
// تطوير: مهند سات لنك

import android.app.DownloadManager
import android.content.ContentValues
import android.content.Intent
import android.graphics.Color
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
            buildDialog(videos, subs.map { Sub(it.key, it.value) })
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

    private fun buildDialog(videos: Map<String, String>, subs: List<Sub>) {
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        videos.forEach { (u, q) ->
            labels += "تحميل الفيلم — $q"
            actions += { downloadFile(u, "${title()} $q.${ext(u, "mp4")}") }
        }
        subs.forEach { s ->
            listOf("SRT", "VTT", "ASS").forEach { f ->
                labels += "ترجمة ${s.label} — $f"
                actions += { downloadSub(s.url, f, "${title()} ${s.label}") }
            }
            labels += "ترجمة ${s.label} — الملف الأصلي"
            actions += { downloadFile(s.url, "${title()} ${s.label}.${ext(s.url, "srt")}") }
        }
        labels += "فتح مجلد التحميلات"
        actions += {
            try { act.startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)) } catch (_: Exception) { }
        }

        val builder = AlertDialog.Builder(act, R.style.Theme_CNMovie_Dialog)
            .setTitle("تحميل")
            .setNegativeButton(R.string.close, null)
        if (videos.isEmpty() && subs.isEmpty()) {
            builder.setMessage("شغّل الفيلم أولاً لثواني، بعدين اضغط تحميل حتى نلقى روابط الفيديو والترجمة.")
            builder.setPositiveButton("فتح مجلد التحميلات") { _, _ -> actions.last().invoke() }
        } else {
            builder.setItems(labels.toTypedArray()) { _, i -> actions[i].invoke() }
        }
        val d = builder.show()
        d.window?.decorView?.setBackgroundColor(Color.parseColor("#12101F"))
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
