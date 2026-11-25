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

    /** طلبات API الموقع (منها نقرأ كل الجودات والترجمات) */
    private val apiSeen: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet())

    fun onRequest(url: String) {
        val p = url.substringBefore('?').lowercase()
        if (p.contains("/api/") && (p.contains("shabakaty") || p.contains("earthlink"))) {
            synchronized(apiSeen) {
                apiSeen.remove(url); apiSeen.add(url)
                while (apiSeen.size > 40) apiSeen.remove(apiSeen.first())
            }
        }
        if (VIDEO_EXT.any { p.endsWith(it) } || SUB_EXT.any { p.endsWith(it) }) {
            synchronized(seen) {
                seen.remove(url); seen.add(url)
                while (seen.size > 40) seen.remove(seen.first())
            }
        }
    }

    fun clear() { seen.clear(); apiSeen.clear() }

    // ---------- تقرير التشخيص ----------
    private val diag: MutableList<String> = Collections.synchronizedList(ArrayList())
    private fun log(s: String) { synchronized(diag) { diag += s; while (diag.size > 80) diag.removeAt(0) } }
    private fun diagText(): String = buildString {
        append("CN.MOVIE ${BuildConfigInfo.version(act)} • Android ${Build.VERSION.SDK_INT}\n")
        append("page: ${web.url}\n")
        synchronized(apiSeen) { apiSeen.forEach { append("API $it\n".take(240)) } }
        synchronized(diag) { diag.forEach { append(it.take(300)).append('\n') } }
    }
    private fun copyDiag() {
        val cm = act.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("CN.MOVIE diag", diagText()))
        toast("انتسخ تقرير التشخيص، الصقه ودزّه")
    }

    // ---------- الترجمة عن طريق الصفحة نفسها ----------
    private class PendingSub(val url: String, val format: String, val base: String, val wantAr: Boolean)
    private fun hasArabic(c: List<Cue>) = c.take(400).sumOf { q -> q.text.count { it in '\u0600'..'\u06FF' } } > 30
    private val pending = HashMap<String, PendingSub>()

    fun onSubResult(id: String, kind: String, data: String) {
        val p = synchronized(pending) { pending.remove(id) } ?: return
        Thread {
            try {
                val cues: List<Cue> = when (kind) {
                    "text" -> parse(data)
                    "cues" -> {
                        val arr = org.json.JSONArray(data)
                        (0 until arr.length()).map { i -> val c = arr.getJSONArray(i)
                            Cue((c.getDouble(0) * 1000).toLong(), (c.getDouble(1) * 1000).toLong(), c.getString(2).replace(Regex("<[^>]+>"), "")) }
                    }
                    else -> { log("SUB page-fail ${data.take(150)} ${p.url}"); parse(decode(fetch(p.url))) }
                }
                log("SUB ok via $kind cues=${cues.size} arabic=${hasArabic(cues)} want=${p.wantAr}")
                if (p.wantAr && !hasArabic(cues)) {
                    act.runOnUiThread { toast("الترجمة العربية بعدها ما وصلت. فعّل الترجمة العربية بالمشغّل، خليها تشتغل كم ثانية، وارجع حمّل.") }
                    return@Thread
                }
                writeSub(cues, p.format, p.base)
            } catch (e: Throwable) {
                log("SUB ERR ${e.javaClass.simpleName}: ${e.message} ${p.url}")
                act.runOnUiThread { toast("تعذّر تحميل الترجمة — اضغط «نسخ تقرير التشخيص» ودزّه") }
            }
        }.start()
    }

    private fun writeSub(cues: List<Cue>, format: String, base: String) {
        val out = when (format) { "VTT" -> toVtt(cues); "ASS" -> toAss(cues); else -> toSrt(cues) }
        val name = safe("$base.${format.lowercase()}")
        save(name, out.toByteArray(Charsets.UTF_8))
        act.runOnUiThread { toast(if (cues.isEmpty()) "الترجمة فارغة" else "انحفظت الترجمة: Download/CN.MOVIE/$name") }
    }

    // ---------- النافذة ----------
    fun show() {
        web.evaluateJavascript(SCAN_JS) { raw ->
            val videos = LinkedHashMap<String, String>()
            val subs = LinkedHashMap<String, String>()
            try {
                val json = JSONObject(org.json.JSONTokener(raw).nextValue() as String)
                val v = json.getJSONArray("v")
                for (i in 0 until v.length()) {
                    val o = v.getJSONObject(i)
                    addVideo(videos, o.optString("src"), o.optString("label"))
                }
                log("JS hook=${json.optBoolean("hook")} videos=${v.length()} subs=${json.getJSONArray("s").length()}")
                json.optJSONArray("log")?.let { l -> for (i in 0 until l.length()) log("XHR ${l.optString(i)}") }
                val s = json.getJSONArray("s")
                for (i in 0 until s.length()) {
                    val o = s.getJSONObject(i)
                    addSub(subs, o.getString("src"), o.optString("label"))
                }
            } catch (e: Exception) { log("SCAN ERR ${e.javaClass.simpleName}: ${e.message} raw=${raw.take(200)}") }
            synchronized(seen) {
                seen.reversed().forEach { u ->
                    val p = u.substringBefore('?').lowercase()
                    if (SUB_EXT.any { p.endsWith(it) }) addSub(subs, u, "") else addVideo(videos, u)
                    log("NET $u".take(220))
                }
            }
            toast("جارِ جمع الجودات والترجمات…")
            val pageUrl = web.url ?: ""
            lastReferer = pageUrl
            val apis = synchronized(apiSeen) { apiSeen.toList().reversed() }
            Thread {
                val apiVideos = LinkedHashMap<String, String>()
                val apiSubs = LinkedHashMap<String, String>()
                try { fromApis(pageUrl, apis, apiVideos, apiSubs) } catch (_: Throwable) { }
                act.runOnUiThread {
                    // نتائج الـ API أولاً (أدق تسمية)، بعدها اللي التقطناه من المشغّل
                    val v = LinkedHashMap<String, String>(); v.putAll(apiVideos); videos.forEach { (k, x) -> if (!v.containsKey(k)) v[k] = x }
                    val s = LinkedHashMap<String, String>(); s.putAll(apiSubs); subs.forEach { (k, x) -> if (!s.containsKey(k)) s[k] = x }
                    openDialog(v, s)
                }
            }.start()
        }
    }

    private fun openDialog(videos: Map<String, String>, subs: Map<String, String>) {
        run {
            try {
                buildDialog(videos, subs.entries.mapIndexed { i, e -> Sub(e.key, e.value.ifBlank { "${i + 1}" }) })
            } catch (e: Throwable) {
                CrashLog.save(act, e)
                toast("صار خطأ بنافذة التحميل، انحفظ تقريره")
            }
        }
    }

    // ---------- قراءة الجودات والترجمات من API الموقع ----------
    private fun fromApis(pageUrl: String, apis: List<String>, videos: MutableMap<String, String>, subs: MutableMap<String, String>) {
        val urls = LinkedHashSet<String>()
        // معرّف الفيلم من رابط الصفحة، ونجرّب روابط API المعروفة لسينمانا
        Regex("(\\d{4,})").findAll(pageUrl).map { it.value }.toList().takeLast(2).forEach { id ->
            urls += "https://cinemana.shabakaty.com/api/android/transcoddedFiles/id/$id"
            urls += "https://cinemana.shabakaty.com/api/android/translationFiles/id/$id"
        }
        urls.addAll(apis.take(15))
        for (u in urls) {
            try {
                val body = String(fetch(u, 6000), Charsets.UTF_8).trim()
                if (!body.startsWith("{") && !body.startsWith("[")) continue
                walk(org.json.JSONTokener(body).nextValue(), videos, subs)
            } catch (_: Throwable) { }
        }
    }

    private fun walk(node: Any?, videos: MutableMap<String, String>, subs: MutableMap<String, String>) {
        when (node) {
            is org.json.JSONArray -> for (i in 0 until node.length()) walk(node.opt(i), videos, subs)
            is JSONObject -> {
                val keys = node.keys().asSequence().toList()
                for (k in keys) {
                    val v = node.opt(k)
                    if (v is String && v.startsWith("http")) {
                        val p = v.substringBefore('?').lowercase()
                        if (VIDEO_EXT.any { p.endsWith(it) } && !p.endsWith(".m3u8")) {
                            val q = listOf("resolution", "quality", "name", "label", "title").firstNotNullOfOrNull { f ->
                                node.optString(f).takeIf { it.isNotBlank() && it.length < 20 && !it.startsWith("http") } }
                            if (!videos.containsKey(v)) videos[v] = q?.let { if (it.matches(Regex("\\d+"))) "${it}p" else it } ?: ""
                        } else if (SUB_EXT.any { p.endsWith(it) }) {
                            val raw = listOf("name", "lang", "language", "label", "title").firstNotNullOfOrNull { f ->
                                node.optString(f).takeIf { it.isNotBlank() && it.length < 30 && !it.startsWith("http") } } ?: ""
                            val lab = when {
                                raw.contains("عرب") || raw.lowercase().let { it == "ar" || it.startsWith("arab") } -> "عربي"
                                raw.lowercase().let { it == "en" || it.startsWith("eng") } -> "إنجليزي"
                                else -> raw
                            }
                            if (!subs.containsKey(v)) subs[v] = lab
                        }
                    } else if (v is JSONObject || v is org.json.JSONArray) walk(v, videos, subs)
                }
            }
        }
    }

    // ---------- قائمة التحميلات داخل التطبيق ----------
    fun showDownloads() {
        val dm = act.getSystemService(AppCompatActivity.DOWNLOAD_SERVICE) as DownloadManager
        val dp = act.resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val list = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(px(18), px(6), px(18), px(10)); layoutDirection = View.LAYOUT_DIRECTION_RTL }
        val scroll = ScrollView(act).apply { addView(list) }
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        lateinit var dialog: AlertDialog

        fun refresh() {
            list.removeAllViews()
            val c = try { dm.query(DownloadManager.Query()) } catch (_: Throwable) { null }
            var any = false
            c?.use {
                val rows = ArrayList<Array<Any>>()
                while (it.moveToNext()) {
                    rows += arrayOf(
                        it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)),
                        it.getString(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE)) ?: "",
                        it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                        it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                        it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    )
                }
                rows.reversed().forEach { r ->
                    any = true
                    val id = r[0] as Long; val title = r[1] as String; val st = r[2] as Int
                    val got = r[3] as Long; val tot = r[4] as Long
                    val pct = if (tot > 0) (got * 100 / tot).toInt() else 0
                    val status = when (st) {
                        DownloadManager.STATUS_SUCCESSFUL -> "مكتمل • ${if (tot > 0) size(tot) else ""}"
                        DownloadManager.STATUS_RUNNING -> "جارِ التحميل $pct% • ${size(got)} / ${if (tot > 0) size(tot) else "؟"}"
                        DownloadManager.STATUS_PAUSED -> "متوقف مؤقتاً $pct%"
                        DownloadManager.STATUS_PENDING -> "بالانتظار"
                        else -> "فشل التحميل"
                    }
                    val card = LinearLayout(act).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(px(14), px(10), px(14), px(10))
                        background = GradientDrawable().apply { setColor(Color.parseColor("#0E0C1A")); cornerRadius = 14 * dp; setStroke(px(1), Color.parseColor("#2A2540")) }
                        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(8) }
                    }
                    card.addView(TextView(act).apply { text = title; setTextColor(Color.WHITE); textSize = 15f; setTypeface(typeface, Typeface.BOLD) })
                    card.addView(TextView(act).apply { text = "\u200F$status"; setTextColor(if (st == DownloadManager.STATUS_FAILED) Color.parseColor("#FF7B93") else Color.parseColor("#A9A4C6")); textSize = 13f })
                    if (st == DownloadManager.STATUS_RUNNING || st == DownloadManager.STATUS_PAUSED) {
                        card.addView(android.widget.ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal).apply {
                            max = 100; progress = pct; progressDrawable = act.getDrawable(R.drawable.neon_progress)
                        }, LinearLayout.LayoutParams(-1, px(5)).apply { topMargin = px(6) })
                    }
                    fun openExternal() {
                        val uri = dm.getUriForDownloadedFile(id) ?: return
                        try {
                            act.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(uri, dm.getMimeTypeForDownloadedFile(id)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "تشغيل بواسطة"))
                        } catch (_: Throwable) { toast("ما لگينا تطبيق يفتح الملف") }
                    }
                    card.setOnClickListener {
                        if (st != DownloadManager.STATUS_SUCCESSFUL) return@setOnClickListener
                        val uri = dm.getUriForDownloadedFile(id) ?: return@setOnClickListener
                        val mime = dm.getMimeTypeForDownloadedFile(id) ?: ""
                        if (mime.startsWith("video") || title.lowercase().matches(Regex(".*\\.(mp4|mkv|webm|m4v|mov)$"))) {
                            val sub = findSubtitle(title.substringBeforeLast('.'))
                            dialog.dismiss()
                            PlayerActivity.start(act, uri, sub?.first, sub?.second, title)
                        } else openExternal()
                    }
                    card.setOnLongClickListener {
                        AlertDialog.Builder(act, R.style.Theme_CNMovie_Dialog).setTitle(title)
                            .setItems(arrayOf("تشغيل بتطبيق آخر", "حذف")) { _, w ->
                                if (w == 0) openExternal()
                                else { dm.remove(id); refresh() }
                            }.show()
                        true
                    }
                    list.addView(card)
                }
            }
            if (!any) list.addView(TextView(act).apply {
                text = "ماكو تحميلات بعد. شغّل فيلم واضغط زر التحميل."; setTextColor(Color.WHITE); textSize = 15f; setPadding(0, px(12), 0, px(12))
            })
        }

        refresh()
        dialog = AlertDialog.Builder(act, R.style.Theme_CNMovie_Dialog)
            .setTitle("التحميلات")
            .setView(scroll)
            .setNegativeButton(R.string.close, null)
            .setNeutralButton("فتح المجلد") { _, _ -> openFolder() }
            .show()
        dialog.window?.decorView?.setBackgroundColor(Color.parseColor("#12101F"))
        val tick = object : Runnable {
            override fun run() { if (dialog.isShowing) { try { refresh() } catch (_: Throwable) { }; handler.postDelayed(this, 1000) } }
        }
        handler.postDelayed(tick, 1000)
        toast("اضغط على الملف لتشغيله، ومطوّلاً لحذفه")
    }

    /** يدوّر على ملف ترجمة بنفس اسم الفيلم بمجلد CN.MOVIE */
    private fun findSubtitle(base: String): Pair<Uri, String>? {
        val names = listOf("srt", "vtt", "ass", "ssa").map { "$base.$it" }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val col = MediaStore.Downloads.EXTERNAL_CONTENT_URI
                act.contentResolver.query(col, arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME),
                    "${MediaStore.Downloads.RELATIVE_PATH} LIKE ?", arrayOf("%CN.MOVIE%"),
                    "${MediaStore.Downloads.DATE_ADDED} DESC")?.use { c ->
                    while (c.moveToNext()) {
                        val n = c.getString(1) ?: continue
                        // MediaStore قد يضيف (1) للاسم المكرر
                        val clean = n.replace(Regex(" \\(\\d+\\)(?=\\.[a-z]+$)"), "")
                        if (names.any { it.equals(clean, true) }) return android.content.ContentUris.withAppendedId(col, c.getLong(0)) to n
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "CN.MOVIE")
                names.map { File(dir, it) }.firstOrNull { it.exists() }?.let { return Uri.fromFile(it) to it.name }
            }
        } catch (e: Throwable) { log("FINDSUB ERR ${e.message}") }
        return null
    }

    private fun openFolder() {
        try {
            val uri = Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload%2FCN.MOVIE")
            act.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "vnd.android.document/directory").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (_: Throwable) {
            try { act.startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)) } catch (_: Throwable) { }
        }
    }

    private fun addVideo(map: MutableMap<String, String>, u: String, label: String = "") {
        if (!u.startsWith("http")) return
        val p = u.substringBefore('?').lowercase()
        if (p.endsWith(".m3u8")) return // بث مجزّأ؛ ما ينحفظ كملف واحد
        val fromLabel = Regex("(\\d{3,4})").find(label)?.value
        val q = fromLabel ?: Regex("(2160|1440|1080|720|576|480|360|240)p?").find(p)?.groupValues?.get(1)
        val name = if (q != null) "${q}p" else label.ifBlank { "" }
        if (map.containsKey(u) && (map[u]!!.isNotBlank() || name.isBlank())) return
        map[u] = name
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
        val infos = videos.entries.mapIndexed { i, (u, q) -> VInfo(u, q.ifBlank { "جودة ${i + 1}" }).also { it.ext = ext(u, "mp4").uppercase() } }

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
            fun rank(v: VInfo) = if (v.h > 0) min(v.w, v.h) else (Regex("(\\d{3,4})").find(v.quality)?.value?.toIntOrNull() ?: 0)
            val sorted = infos.sortedByDescending { rank(it) }
            sorted.forEach { v ->
                val c = card()
                c.addView(TextView(act).apply {
                    text = "تحميل الفيلم — ${v.quality}"; setTextColor(Color.WHITE); textSize = 16f; setTypeface(typeface, Typeface.BOLD)
                })
                c.addView(TextView(act).apply { text = "\u200F" + detail(v); setTextColor(muted); textSize = 13f; setPadding(0, px(3), 0, 0) })
                c.setOnClickListener {
                    val base = "${title()} ${v.quality}"
                    downloadFile(v.url, "$base.${v.ext.lowercase()}")
                    if (withSub.isChecked && arabic != null) downloadSub(arabic.url, subFormat, base, arabic.label.contains("عرب"))
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

        root.addView(TextView(act).apply {
            text = "نسخ تقرير التشخيص"
            setTextColor(Color.parseColor("#8C88A8")); textSize = 13f; gravity = Gravity.CENTER
            setPadding(0, px(14), 0, px(4))
            paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
            setOnClickListener { copyDiag() }
        })
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
            lastReferer?.let { put("Referer", it) }
        }
        try {
            val c = URL(v.url).openConnection() as HttpURLConnection
            c.requestMethod = "GET"; c.connectTimeout = 8000; c.readTimeout = 8000
            headers.forEach { (k, x) -> c.setRequestProperty(k, x) }
            c.setRequestProperty("Range", "bytes=0-0")
            val cr = c.getHeaderField("Content-Range")
            val code = c.responseCode
            v.size = cr?.substringAfter('/')?.toLongOrNull() ?: c.getHeaderField("Content-Length")?.toLongOrNull() ?: -1
            log("PROBE $code size=${v.size} ${v.url}".take(240))
            c.disconnect()
        } catch (e: Exception) { log("PROBE ERR ${e.javaClass.simpleName}: ${e.message} ${v.url}".take(260)) }
        try {
            mp4Dims(v.url, headers)?.let { (w, h) ->
                v.w = w; v.h = h
                if (v.quality.startsWith("جودة")) v.quality = "${std(min(w, h))}p"
            }
        } catch (e: Throwable) { log("DIMS ERR ${e.javaClass.simpleName}: ${e.message}") }
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

    private fun downloadSub(url: String, format: String, base: String, arabicHint: Boolean? = null) {
        toast("جارِ تجهيز الترجمة…")
        val id = "s" + System.nanoTime()
        val wantAr = arabicHint ?: base.contains("عربي")
        synchronized(pending) { pending[id] = PendingSub(url, format, base, wantAr) }
        val q = org.json.JSONObject.quote(url)
        web.evaluateJavascript("window.CNSub ? (CNSub('$id', $q, $wantAr), 'ok') : 'no'") { r ->
            if (!r.contains("ok")) onSubResult(id, "error", "no CNSub")
        }
        // احتياط إذا الصفحة ما ردّت
        web.postDelayed({ if (synchronized(pending) { pending.containsKey(id) }) onSubResult(id, "error", "timeout") }, 12000)
    }

    private var lastReferer: String? = null

    private fun fetch(url: String, timeout: Int = 15000): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeout; c.readTimeout = timeout + 5000
        lastReferer?.let { c.setRequestProperty("Referer", it) }
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
            "d.querySelectorAll('video').forEach(function(v){if(v.currentSrc)V.push({src:v.currentSrc,label:v.videoHeight?v.videoHeight+'p':''});if(v.src)V.push({src:v.src,label:''});" +
            "v.querySelectorAll('source').forEach(function(s){V.push({src:s.src,label:s.getAttribute('label')||s.getAttribute('size')||s.getAttribute('res')||s.getAttribute('title')||''})});" +
            "v.querySelectorAll('track').forEach(function(t){if(t.src)S.push({src:t.src,label:t.label||t.srclang||''})})});" +
            "d.querySelectorAll('a[href]').forEach(function(a){var h=a.href.split('?')[0].toLowerCase();" +
            "if(/\\.(mp4|mkv|webm)$/.test(h))V.push({src:a.href,label:(a.innerText||'').trim().slice(0,20)});if(/\\.(vtt|srt|ass|ssa)$/.test(h))S.push({src:a.href,label:(a.innerText||'').trim().slice(0,20)})});" +
            "d.querySelectorAll('iframe').forEach(function(f){try{w(f.contentDocument)}catch(e){}})}catch(e){}}" +
            "w(document);var M=window.__cnMedia||{v:{},s:{},log:[]};for(var k in M.v)V.push({src:k,label:M.v[k]});for(var k2 in M.s)S.push({src:k2,label:M.s[k2]});" +
            "return JSON.stringify({v:V.filter(function(x){return x.src&&x.src.indexOf('blob:')!==0}),s:S,log:M.log,hook:!!window.__cnHook});})()"
    }
}
