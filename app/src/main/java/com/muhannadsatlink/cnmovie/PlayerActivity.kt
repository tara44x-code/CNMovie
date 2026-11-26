package com.muhannadsatlink.cnmovie

// CN.MOVIE — المشغّل الداخلي للأفلام المحمّلة (بدون نت) مع الترجمة وكل الإيماءات
// تطوير: مهند سات لنك

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.util.TypedValue
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView

class PlayerActivity : AppCompatActivity() {

    companion object {
        fun start(c: Context, video: Uri, sub: Uri?, subName: String?, title: String) {
            c.startActivity(Intent(c, PlayerActivity::class.java).apply {
                data = video
                putExtra("sub", sub?.toString())
                putExtra("subName", subName)
                putExtra("title", title)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        }
    }

    private lateinit var player: ExoPlayer
    private lateinit var view: PlayerView
    private lateinit var layer: PlayerGestureLayer
    private val prefs by lazy { getSharedPreferences("resume", MODE_PRIVATE) }
    private val key by lazy { "pos:" + intent.data.toString() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply { layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES }
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        player = ExoPlayer.Builder(this).setSeekBackIncrementMs(10_000).setSeekForwardIncrementMs(10_000).build()
        view = PlayerView(this).apply {
            this.player = this@PlayerActivity.player
            setShowSubtitleButton(true)
        }
        applySubStyle()
        root.addView(view, FrameLayout.LayoutParams(-1, -1))
        // شريط التقديم بألوان النيون
        view.findViewById<androidx.media3.ui.DefaultTimeBar>(androidx.media3.ui.R.id.exo_progress)?.apply {
            setPlayedColor(Color.parseColor("#FF2BD6")); setScrubberColor(Color.parseColor("#22E4FF"))
            setBufferedColor(Color.parseColor("#5522E4FF")); setUnplayedColor(Color.parseColor("#33FFFFFF"))
        }
        // اسم الفيلم وزر رجوع
        val dp = resources.displayMetrics.density
        val head = android.widget.LinearLayout(this).apply {
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding((12 * dp).toInt(), (14 * dp).toInt(), (12 * dp).toInt(), (14 * dp).toInt())
            background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.parseColor("#CC000000"), Color.TRANSPARENT))
        }
        head.addView(android.widget.ImageButton(this).apply {
            setImageResource(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            setColorFilter(Color.WHITE); background = null; contentDescription = "رجوع"
            setOnClickListener { finish() }
        }, android.widget.LinearLayout.LayoutParams((44 * dp).toInt(), (44 * dp).toInt()))
        head.addView(android.widget.TextView(this).apply {
            text = intent.getStringExtra("title")?.substringBeforeLast('.') ?: ""
            setTextColor(Color.WHITE); textSize = 16f; maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setShadowLayer(12f, 0f, 0f, Color.parseColor("#22E4FF"))
            setPadding((8 * dp).toInt(), 0, 0, 0)
        }, android.widget.LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(head, FrameLayout.LayoutParams(-1, -2, android.view.Gravity.TOP))
        // يطلع ويختفي ويّا أزرار المشغّل
        view.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v -> head.visibility = v })

        layer = PlayerGestureLayer(this, ExoControl())
        layer.target = view
        layer.onPip = { enterPip() }
        layer.addPill("الترجمة") {
            if (intent.getStringExtra("sub") != null) subDialog()
            else Toast.makeText(this, "ماكو ترجمة لهذا الفيلم — اضغط «ملف ترجمة» واختارها", Toast.LENGTH_LONG).show()
        }
        layer.addPill("ملف ترجمة") { pickSub.launch(arrayOf("*/*")) }
        boostBtn = layer.addPill("الصوت 100%") { cycleBoost() }
        root.addView(layer, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)

        val video = intent.data ?: run { finish(); return }
        // إذا ما انطت ترجمة، ندوّر على ملف بنفس اسم الفيلم
        if (intent.getStringExtra("sub") == null) {
            SubFinder.find(this, intent.getStringExtra("title") ?: "")?.let { (u, n) ->
                intent.putExtra("sub", u.toString()); intent.putExtra("subName", n)
            }
        }
        val item = MediaItem.Builder().setUri(video)
        intent.getStringExtra("sub")?.let { s ->
            val name = intent.getStringExtra("subName")?.lowercase() ?: ""
            val mime = when {
                name.endsWith(".vtt") -> MimeTypes.TEXT_VTT
                name.endsWith(".ass") || name.endsWith(".ssa") -> MimeTypes.TEXT_SSA
                else -> MimeTypes.APPLICATION_SUBRIP
            }
            item.setSubtitleConfigurations(listOf(
                MediaItem.SubtitleConfiguration.Builder(Uri.parse(s))
                    .setMimeType(mime).setLanguage("ar").setLabel(name.substringBeforeLast('.'))
                    .setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()))
        }
        player.setMediaItem(item.build())
        player.prepare()
        val pos = prefs.getLong(key, 0L)
        if (pos > 30_000) {
            player.seekTo(pos)
            Toast.makeText(this, "كمّلنا من وين وقفت", Toast.LENGTH_SHORT).show()
        }
        player.playWhenReady = true
    }

    // ---------- تقوية الصوت ----------
    private var boostBtn: android.widget.TextView? = null
    private var enhancer: android.media.audiofx.LoudnessEnhancer? = null
    private val boosts = listOf(0, 600, 1200, 1800) // ميلي ديسيبل
    private val boostLabels = listOf("100%", "130%", "160%", "200%")
    private var boostIdx = 0
    private fun cycleBoost() {
        boostIdx = (boostIdx + 1) % boosts.size
        try {
            if (enhancer == null) enhancer = android.media.audiofx.LoudnessEnhancer(player.audioSessionId)
            enhancer!!.setTargetGain(boosts[boostIdx]); enhancer!!.enabled = boostIdx > 0
            boostBtn?.text = "الصوت ${boostLabels[boostIdx]}"
            Toast.makeText(this, "الصوت ${boostLabels[boostIdx]}", Toast.LENGTH_SHORT).show()
        } catch (e: Throwable) { Toast.makeText(this, "تقوية الصوت غير مدعومة بهذا الجهاز", Toast.LENGTH_SHORT).show() }
    }

    // ---------- تحميل ملف ترجمة من الجهاز ----------
    private val pickSub = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Throwable) { }
        val name = (contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null } ?: "sub.srt")
        if (!name.lowercase().matches(Regex(".*\\.(srt|vtt|ass|ssa)$"))) {
            Toast.makeText(this, "اختار ملف ترجمة (SRT أو VTT أو ASS)", Toast.LENGTH_SHORT).show(); return@registerForActivityResult
        }
        intent.putExtra("sub", uri.toString()); intent.putExtra("subName", name)
        subDelay = 0
        applyDelay()
        Toast.makeText(this, "انضافت الترجمة: $name", Toast.LENGTH_SHORT).show()
    }

    // ---------- تحكم بالترجمة: الحجم واللون والخلفية والتوقيت ----------
    private val sizes = listOf("صغير" to 16f, "متوسط" to 22f, "كبير" to 28f, "ضخم" to 36f)
    private val colors = listOf("أبيض" to Color.WHITE, "أصفر" to Color.parseColor("#FFE14D"), "سماوي" to Color.parseColor("#22E4FF"))
    private var subDelay = 0L

    private fun applySubStyle() {
        val size = sizes[Prefs.getInt(this, "subSize", 1).coerceIn(0, sizes.size - 1)].second
        val color = colors[Prefs.getInt(this, "subColor", 0).coerceIn(0, colors.size - 1)].second
        val bg = Prefs.get(this, "subBg", false)
        view.subtitleView?.apply {
            setStyle(CaptionStyleCompat(color, if (bg) Color.parseColor("#99000000") else Color.TRANSPARENT, Color.TRANSPARENT,
                if (bg) CaptionStyleCompat.EDGE_TYPE_NONE else CaptionStyleCompat.EDGE_TYPE_OUTLINE, Color.BLACK, Typeface.DEFAULT_BOLD))
            setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            setApplyEmbeddedStyles(false)
        }
    }

    private fun subDialog() {
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val pink = Color.parseColor("#FF2BD6")
        val root = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL; setPadding(px(20), px(6), px(20), px(6)); layoutDirection = View.LAYOUT_DIRECTION_RTL }
        fun title(t: String) = root.addView(android.widget.TextView(this).apply { text = t; setTextColor(Color.parseColor("#22E4FF")); textSize = 14f; setPadding(0, px(12), 0, px(6)) })
        fun row(items: List<String>, sel: () -> Int, onPick: (Int) -> Unit) {
            val r = android.widget.LinearLayout(this)
            val views = ArrayList<android.widget.TextView>()
            fun paint() = views.forEachIndexed { i, v ->
                val on = i == sel()
                v.setTextColor(if (on) Color.WHITE else Color.parseColor("#A9A4C6"))
                v.background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 16 * dp
                    if (on) setColor(pink) else { setColor(Color.parseColor("#12101F")); setStroke(px(1), Color.parseColor("#2A2540")) }
                }
            }
            items.forEachIndexed { i, t ->
                val v = android.widget.TextView(this).apply {
                    text = t; textSize = 14f; setPadding(px(14), px(7), px(14), px(7))
                    setOnClickListener { onPick(i); paint() }
                }
                views += v
                r.addView(v, android.widget.LinearLayout.LayoutParams(-2, -2).apply { marginEnd = px(8) })
            }
            paint(); root.addView(r)
        }
        title("حجم الخط")
        row(sizes.map { it.first }, { Prefs.getInt(this, "subSize", 1) }) { Prefs.setInt(this, "subSize", it); applySubStyle() }
        title("اللون")
        row(colors.map { it.first }, { Prefs.getInt(this, "subColor", 0) }) { Prefs.setInt(this, "subColor", it); applySubStyle() }
        title("الخلفية")
        row(listOf("بدون", "خلفية سوداء"), { if (Prefs.get(this, "subBg", false)) 1 else 0 }) { Prefs.set(this, "subBg", it == 1); applySubStyle() }
        title("التوقيت")
        val delayLabel = android.widget.TextView(this).apply { textSize = 15f; setTextColor(Color.WHITE); gravity = android.view.Gravity.CENTER }
        fun showDelay() { delayLabel.text = "\u200E" + (if (subDelay > 0) "+" else "") + String.format("%.1f", subDelay / 1000.0) + " ثانية" }
        showDelay()
        row(listOf("-1", "-0.5", "0", "+0.5", "+1"), { -1 }) { i ->
            subDelay = when (i) { 0 -> subDelay - 1000; 1 -> subDelay - 500; 2 -> 0L; 3 -> subDelay + 500; else -> subDelay + 1000 }
            showDelay(); applyDelay()
        }
        root.addView(delayLabel)
        root.addView(android.widget.TextView(this).apply {
            text = "سالب = الترجمة تطلع أبكر، موجب = تتأخر"; textSize = 12f; setTextColor(Color.parseColor("#8C88A8")); setPadding(0, px(4), 0, 0)
        })
        androidx.appcompat.app.AlertDialog.Builder(this, R.style.Theme_CNMovie_Dialog)
            .setTitle("الترجمة")
            .setView(android.widget.ScrollView(this).apply { addView(root) })
            .setPositiveButton(R.string.close, null)
            .show().window?.decorView?.setBackgroundColor(Color.parseColor("#12101F"))
    }

    private var shiftN = 0
    private fun applyDelay() {
        val src = intent.getStringExtra("sub") ?: return
        val video = intent.data ?: return
        Thread {
            try {
                val raw = contentResolver.openInputStream(Uri.parse(src))!!.use { it.readBytes() }
                val cues = SubUtil.shift(SubUtil.parse(SubUtil.decode(raw)), subDelay)
                cacheDir.listFiles { f -> f.name.startsWith("sub_shift_") }?.forEach { it.delete() }
                val f = java.io.File(cacheDir, "sub_shift_${++shiftN}.srt")
                f.writeText(SubUtil.toSrt(cues), Charsets.UTF_8)
                runOnUiThread {
                    val pos = player.currentPosition; val playing = player.playWhenReady
                    val sp = player.playbackParameters
                    player.setMediaItem(MediaItem.Builder().setUri(video).setSubtitleConfigurations(listOf(
                        MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(f)).setMimeType(MimeTypes.APPLICATION_SUBRIP)
                            .setLanguage("ar").setLabel("عربي").setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build())).build(), pos)
                    player.prepare(); player.playbackParameters = sp; player.playWhenReady = playing
                }
            } catch (e: Throwable) {
                runOnUiThread { Toast.makeText(this, "تعذّر تعديل التوقيت", Toast.LENGTH_SHORT).show() }
            }
        }.start()
    }

    private inner class ExoControl : PlayerControl {
        override fun info(cb: (Double, Double) -> Unit) {
            val d = player.duration
            cb(player.currentPosition / 1000.0, if (d > 0) d / 1000.0 else -1.0)
        }
        override fun seekBy(sec: Double) {
            val d = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
            player.seekTo((player.currentPosition + (sec * 1000).toLong()).coerceIn(0, d - 500))
        }
        override fun togglePlay(cb: (Boolean?) -> Unit) {
            if (player.isPlaying) player.pause() else player.play()
            cb(player.playWhenReady)
        }
        override fun setFit(mode: String, cb: (Boolean) -> Unit) {
            view.resizeMode = when (mode) {
                "cover" -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                "fill" -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
            cb(true)
        }
        override fun setSpeed(x: Float) { player.playbackParameters = PlaybackParameters(x) }
        override fun pause() { player.pause() }
    }

    private fun enterPip() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try { enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()) } catch (_: Throwable) { }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (player.isPlaying) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPip: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPip, newConfig)
        layer.visibility = if (isInPip) View.GONE else View.VISIBLE
        view.useController = !isInPip
    }

    override fun onStop() {
        super.onStop()
        val d = player.duration
        val p = player.currentPosition
        prefs.edit().putLong(key, if (d > 0 && p > d - 60_000) 0L else p).apply()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || !isInPictureInPictureMode) player.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { enhancer?.release() } catch (_: Throwable) { }
        layer.restoreBrightness()
        player.release()
    }
}
