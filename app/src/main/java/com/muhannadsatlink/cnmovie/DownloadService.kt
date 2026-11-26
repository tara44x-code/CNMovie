package com.muhannadsatlink.cnmovie

// CN.MOVIE — مدير تحميل خاص: يحفظ الملف باسمه الصحيح، مع إيقاف واستئناف
// تطوير: مهند سات لنك

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

data class DlTask(
    val id: String, val url: String, val name: String,
    val cookie: String, val ua: String, val referer: String,
    var total: Long = -1, var done: Long = 0,
    var status: String = "queued", // queued, running, paused, done, failed, wifi
    var uri: String = "", var error: String = "", val created: Long = System.currentTimeMillis()
) {
    fun json() = JSONObject().apply {
        put("id", id); put("url", url); put("name", name); put("cookie", cookie); put("ua", ua); put("referer", referer)
        put("total", total); put("done", done); put("status", status); put("uri", uri); put("error", error); put("created", created)
    }
    companion object {
        fun of(o: JSONObject) = DlTask(o.getString("id"), o.getString("url"), o.getString("name"), o.optString("cookie"),
            o.optString("ua"), o.optString("referer"), o.optLong("total", -1), o.optLong("done"), o.optString("status"),
            o.optString("uri"), o.optString("error"), o.optLong("created"))
    }
}

object DlStore {
    private val tasks = LinkedHashMap<String, DlTask>()
    private var loaded = false
    private fun prefs(c: Context) = c.getSharedPreferences("dltasks", Context.MODE_PRIVATE)

    @Synchronized fun all(c: Context): List<DlTask> { load(c); return tasks.values.map { it.copy() }.sortedByDescending { it.created } }
    @Synchronized fun get(c: Context, id: String): DlTask? { load(c); return tasks[id] }
    @Synchronized fun put(c: Context, t: DlTask) { load(c); tasks[t.id] = t; save(c) }
    @Synchronized fun remove(c: Context, id: String) { load(c); tasks.remove(id); save(c) }
    @Synchronized fun save(c: Context) {
        val a = JSONArray(); tasks.values.forEach { a.put(it.json()) }
        prefs(c).edit().putString("list", a.toString()).apply()
    }
    private fun load(c: Context) {
        if (loaded) return
        loaded = true
        try {
            val a = JSONArray(prefs(c).getString("list", "[]"))
            for (i in 0 until a.length()) DlTask.of(a.getJSONObject(i)).let {
                // أي تحميل جان شغّال وانسد التطبيق: يصير متوقف
                if (it.status == "running" || it.status == "queued") it.status = "paused"
                tasks[it.id] = it
            }
        } catch (_: Throwable) { }
    }
}

class DownloadService : Service() {

    companion object {
        private const val CH = "downloads"
        const val START = "start"; const val PAUSE = "pause"; const val CANCEL = "cancel"

        fun enqueue(c: Context, url: String, name: String, cookie: String, ua: String, referer: String): DlTask {
            val t = DlTask("d" + System.currentTimeMillis(), url, name, cookie, ua, referer)
            DlStore.put(c, t)
            send(c, START, t.id)
            return t
        }
        fun send(c: Context, action: String, id: String) {
            val i = Intent(c, DownloadService::class.java).setAction(action).putExtra("id", id)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) c.startForegroundService(i) else c.startService(i)
        }
        fun formatSize(b: Long): String = when {
            b >= 1L shl 30 -> String.format("%.2f GB", b / 1073741824.0)
            b >= 1L shl 20 -> String.format("%.0f MB", b / 1048576.0)
            b > 0 -> String.format("%.0f KB", b / 1024.0)
            else -> "0"
        }
    }

    private val workers = ConcurrentHashMap<String, Thread>()
    private val stopFlags = ConcurrentHashMap<String, String>() // id -> pause/cancel
    private lateinit var nm: NotificationManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            nm.createNotificationChannel(NotificationChannel(CH, "التحميلات", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra("id")
        // لازم نصير خدمة أمامية فوراً
        ServiceCompat.startForeground(this, 7001, summary(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        when (intent?.action) {
            START -> id?.let { start(it) }
            PAUSE -> id?.let { stopFlags[it] = PAUSE }
            CANCEL -> id?.let {
                if (workers.containsKey(it)) stopFlags[it] = CANCEL else { deleteFile(it); DlStore.remove(this, it); nm.cancel(it.hashCode()) }
            }
        }
        stopIfIdle()
        return START_NOT_STICKY
    }

    private fun summary(): Notification = NotificationCompat.Builder(this, CH)
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setContentTitle("CN.MOVIE")
        .setContentText(if (workers.isEmpty()) "التحميلات" else "جارِ تحميل ${workers.size}")
        .setOngoing(true).setSilent(true).build()

    private fun stopIfIdle() {
        if (workers.isEmpty()) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun start(id: String) {
        if (workers.containsKey(id)) return
        val t = DlStore.get(this, id) ?: return
        if (t.status == "done") return
        if (Prefs.get(this, Prefs.WIFI_ONLY, false)) {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            if (cm.isActiveNetworkMetered) { t.status = "wifi"; DlStore.put(this, t); notify(t); return }
        }
        t.status = "running"; t.error = ""; DlStore.put(this, t)
        stopFlags.remove(id)
        val th = Thread { run(t) }
        workers[id] = th
        th.start()
    }

    private fun run(t: DlTask) {
        try {
            download(t)
        } catch (e: Throwable) {
            if (t.status == "running") { t.status = "failed"; t.error = "${e.javaClass.simpleName}: ${e.message}".take(200) }
        } finally {
            when (stopFlags.remove(t.id)) {
                PAUSE -> t.status = "paused"
                CANCEL -> { deleteFile(t.id, t); DlStore.remove(this, t.id); nm.cancel(t.id.hashCode()); workers.remove(t.id); onIdle(); return }
            }
            DlStore.put(this, t)
            notify(t)
            workers.remove(t.id)
            onIdle()
        }
    }

    private fun onIdle() {
        android.os.Handler(mainLooper).post {
            if (workers.isEmpty()) stopIfIdle() else nm.notify(7001, summary())
        }
    }

    private fun download(t: DlTask) {
        var attempt = 0
        while (true) {
            val c = URL(t.url).openConnection() as HttpURLConnection
            c.connectTimeout = 20000; c.readTimeout = 30000
            c.instanceFollowRedirects = true
            if (t.cookie.isNotEmpty()) c.setRequestProperty("Cookie", t.cookie)
            if (t.ua.isNotEmpty()) c.setRequestProperty("User-Agent", t.ua)
            if (t.referer.isNotEmpty()) c.setRequestProperty("Referer", t.referer)
            if (t.done > 0) c.setRequestProperty("Range", "bytes=${t.done}-")
            val code = c.responseCode
            if (code !in 200..299) { c.disconnect(); throw IllegalStateException("HTTP $code") }
            val resumed = code == 206 && t.done > 0
            if (!resumed) t.done = 0
            val len = c.getHeaderField("Content-Length")?.toLongOrNull() ?: -1
            val cr = c.getHeaderField("Content-Range")?.substringAfter('/')?.toLongOrNull()
            t.total = cr ?: if (len > 0) t.done + len else -1
            try {
                openOut(t, append = resumed).use { out ->
                    c.inputStream.use { inp ->
                        val buf = ByteArray(256 * 1024)
                        var last = 0L
                        while (true) {
                            if (stopFlags.containsKey(t.id)) return
                            val n = inp.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            t.done += n
                            val now = System.currentTimeMillis()
                            if (now - last > 1000) { last = now; DlStore.put(this, t); notify(t) }
                        }
                    }
                }
            } catch (e: java.io.IOException) {
                c.disconnect()
                // انقطاع بالنص: نعيد المحاولة من نفس المكان
                if (++attempt <= 5 && !stopFlags.containsKey(t.id)) { Thread.sleep(3000L * attempt); continue }
                throw e
            }
            c.disconnect()
            if (t.total > 0 && t.done < t.total) {
                if (++attempt <= 5) { Thread.sleep(2000); continue }
                throw IllegalStateException("انقطع التحميل")
            }
            finish(t)
            t.status = "done"
            return
        }
    }

    private fun mime(name: String) = when (name.substringAfterLast('.').lowercase()) {
        "mkv" -> "video/x-matroska"; "webm" -> "video/webm"; "srt", "vtt", "ass", "ssa" -> "application/octet-stream"; else -> "video/mp4"
    }

    private fun openOut(t: DlTask, append: Boolean): OutputStream {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (t.uri.isEmpty()) {
                val v = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, t.name)
                    put(MediaStore.Downloads.MIME_TYPE, mime(t.name))
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/CN.MOVIE")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                t.uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)?.toString() ?: error("تعذّر إنشاء الملف")
                DlStore.put(this, t)
            }
            return contentResolver.openOutputStream(Uri.parse(t.uri), if (append) "wa" else "wt") ?: error("تعذّر فتح الملف")
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "CN.MOVIE").apply { mkdirs() }
            if (t.uri.isEmpty()) { t.uri = Uri.fromFile(File(dir, t.name)).toString(); DlStore.put(this, t) }
            return FileOutputStream(File(Uri.parse(t.uri).path!!), append)
        }
    }

    private fun finish(t: DlTask) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && t.uri.startsWith("content")) {
            contentResolver.update(Uri.parse(t.uri), ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } else if (t.uri.startsWith("file")) {
            android.media.MediaScannerConnection.scanFile(this, arrayOf(Uri.parse(t.uri).path), null, null)
        }
    }

    private fun deleteFile(id: String, task: DlTask? = null) {
        val t = task ?: DlStore.get(this, id) ?: return
        try {
            if (t.uri.startsWith("content")) contentResolver.delete(Uri.parse(t.uri), null, null)
            else if (t.uri.startsWith("file")) File(Uri.parse(t.uri).path!!).delete()
        } catch (_: Throwable) { }
    }

    private fun pi(action: String, id: String, req: Int): PendingIntent {
        val i = Intent(this, DownloadService::class.java).setAction(action).putExtra("id", id)
        val f = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) PendingIntent.getForegroundService(this, req, i, f)
        else PendingIntent.getService(this, req, i, f)
    }

    private fun notify(t: DlTask) {
        val pct = if (t.total > 0) (t.done * 100 / t.total).toInt() else 0
        val b = NotificationCompat.Builder(this, CH).setContentTitle(t.name).setSilent(true)
            .setContentIntent(PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java).putExtra("openDownloads", true),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        when (t.status) {
            "running" -> b.setSmallIcon(android.R.drawable.stat_sys_download).setOngoing(true)
                .setProgress(100, pct, t.total <= 0)
                .setContentText("$pct% • ${formatSize(t.done)} / ${if (t.total > 0) formatSize(t.total) else "؟"}")
                .addAction(0, "إيقاف مؤقت", pi(PAUSE, t.id, t.id.hashCode() + 1))
                .addAction(0, "إلغاء", pi(CANCEL, t.id, t.id.hashCode() + 2))
            "done" -> b.setSmallIcon(android.R.drawable.stat_sys_download_done).setAutoCancel(true).setContentText("اكتمل التحميل • ${formatSize(t.done)}")
            "paused" -> b.setSmallIcon(android.R.drawable.ic_media_pause).setContentText("متوقف مؤقتاً $pct%")
                .addAction(0, "استئناف", pi(START, t.id, t.id.hashCode() + 3))
            "wifi" -> b.setSmallIcon(android.R.drawable.stat_sys_warning).setContentText("بالانتظار للواي فاي")
                .addAction(0, "استئناف", pi(START, t.id, t.id.hashCode() + 3))
            else -> b.setSmallIcon(android.R.drawable.stat_notify_error).setContentText("فشل التحميل — اضغط استئناف")
                .addAction(0, "استئناف", pi(START, t.id, t.id.hashCode() + 3))
        }
        try { nm.notify(t.id.hashCode(), b.build()) } catch (_: Throwable) { }
    }
}
