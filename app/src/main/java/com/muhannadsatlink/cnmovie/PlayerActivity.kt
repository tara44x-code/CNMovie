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
            subtitleView?.apply {
                setStyle(CaptionStyleCompat(Color.WHITE, Color.TRANSPARENT, Color.TRANSPARENT,
                    CaptionStyleCompat.EDGE_TYPE_OUTLINE, Color.BLACK, Typeface.DEFAULT_BOLD))
                setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                setApplyEmbeddedStyles(false)
            }
        }
        root.addView(view, FrameLayout.LayoutParams(-1, -1))

        layer = PlayerGestureLayer(this, ExoControl())
        layer.target = view
        layer.onPip = { enterPip() }
        root.addView(layer, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)

        val video = intent.data ?: run { finish(); return }
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
                    .setMimeType(mime).setLanguage("ar").setLabel("عربي")
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
        layer.restoreBrightness()
        player.release()
    }
}
