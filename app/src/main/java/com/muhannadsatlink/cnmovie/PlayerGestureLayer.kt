package com.muhannadsatlink.cnmovie

// CN.MOVIE — طبقة إيماءات المشغّل الاحترافية
// تطوير: مهند سات لنك
//
// تعمل فوق الفيديو بملء الشاشة:
//  • سحب عمودي يسار الشاشة  ← السطوع
//  • سحب عمودي يمين الشاشة  ← الصوت
//  • سحب أفقي              ← تقديم / ترجيع حر
//  • نقرتين يمين / يسار      ← +10 / -10 ثواني (وكل نقرة إضافية +10)
//  • نقرتين بالنص            ← تشغيل / إيقاف
//  • قرص (Pinch) للخارج      ← تكبير يحذف الإطارات السوداء، وللداخل يرجّع
//  • نقرة واحدة              ← تظهر أزرار المشغّل الأصلية + أزرار CN

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.SystemClock
import android.provider.Settings
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class PlayerGestureLayer(private val act: AppCompatActivity, var ctl: PlayerControl) : FrameLayout(act) {

    var target: View? = null
    var onDownload: (() -> Unit)? = null

    private val dp = resources.displayMetrics.density
    private val cyan = Color.parseColor("#22E4FF")
    private val pink = Color.parseColor("#FF2BD6")
    private val audio = act.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val maxVol = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)

    // ---------- الواجهة ----------
    private val indicator = LinearLayout(act)
    private val indTitle = TextView(act)
    private val indBar = ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal)
    private val leftRipple = rippleText()
    private val rightRipple = rippleText()
    private val topBar = LinearLayout(act)
    private val aspectBtn = pill("ملاءمة")
    private val lockBtn = pill("قفل")
    private val dlBtn = pill("تحميل")
    private val speedBtn = pill("1x")
    private val sleepBtn = pill("مؤقت")
    private val pipBtn = pill("نافذة")
    var onPip: (() -> Unit)? = null
    private val speeds = listOf(1f, 1.25f, 1.5f, 2f, 0.5f, 0.75f)
    private var speedIdx = 0
    private val sleeps = listOf(0, 15, 30, 45, 60, 90)
    private var sleepIdx = 0
    private var sleepAt = 0L
    private val sleepRun = Runnable {
        ctl.pause(); sleepIdx = 0; sleepBtn.text = "مؤقت"
        showIndicator("مؤقت النوم: انتهى الوقت، تصبح على خير", -1)
        removeCallbacks(hideIndicator); postDelayed(hideIndicator, 2500)
    }
    private val unlockBtn = pill("فتح القفل")

    private val aspects = listOf("contain" to "ملاءمة", "cover" to "تكبير", "fill" to "تمديد")
    private var aspectIdx = 0
    private var locked = false

    // ---------- حالة السحب ----------
    private var mode = 0 // 0 لا شيء، 1 سطوع، 2 صوت، 3 تقديم
    private var startX = 0f
    private var startY = 0f
    private var startVal = 0f
    private var seekCur = -1.0
    private var seekDur = -1.0
    private var seekDelta = 0.0

    // ---------- سلسلة النقرات ----------
    private var streakUntil = 0L
    private var streakTotal = 0
    private var downX = 0f
    private var downY = 0f

    private val hideIndicator = Runnable { indicator.animate().alpha(0f).setDuration(250).start() }
    private val hideTop = Runnable { topBar.animate().alpha(0f).setDuration(250).withEndAction { topBar.visibility = GONE }.start() }
    private val hideUnlock = Runnable { unlockBtn.visibility = GONE }
    private val hideRipples = Runnable {
        leftRipple.animate().alpha(0f).setDuration(250).start()
        rightRipple.animate().alpha(0f).setDuration(250).start()
        streakTotal = 0
    }

    init {
        isClickable = true

        // المؤشر الوسطي (سطوع / صوت / تقديم)
        indicator.orientation = LinearLayout.VERTICAL
        indicator.gravity = Gravity.CENTER
        indicator.setPadding((22 * dp).toInt(), (14 * dp).toInt(), (22 * dp).toInt(), (16 * dp).toInt())
        indicator.background = rounded(Color.parseColor("#CC07060F"), cyan, 18f)
        indicator.alpha = 0f
        indTitle.setTextColor(Color.WHITE)
        indTitle.textSize = 18f
        indTitle.gravity = Gravity.CENTER
        indTitle.setShadowLayer(14f, 0f, 0f, cyan)
        indBar.max = 100
        indBar.progressDrawable = act.getDrawable(R.drawable.neon_progress)
        indicator.addView(indTitle)
        indicator.addView(indBar, LinearLayout.LayoutParams((170 * dp).toInt(), (5 * dp).toInt()).apply { topMargin = (10 * dp).toInt() })
        addView(indicator, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        // تأثير النقرتين يمين ويسار
        addView(leftRipple, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL or Gravity.LEFT).apply { leftMargin = (60 * dp).toInt() })
        addView(rightRipple, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL or Gravity.RIGHT).apply { rightMargin = (60 * dp).toInt() })

        // الأزرار العلوية
        topBar.orientation = LinearLayout.HORIZONTAL
        topBar.visibility = GONE
        topBar.addView(aspectBtn)
        topBar.addView(lockBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = (10 * dp).toInt() })
        topBar.addView(dlBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = (10 * dp).toInt() })
        dlBtn.setOnClickListener { onDownload?.invoke() }
        listOf(speedBtn, sleepBtn, pipBtn).forEach {
            topBar.addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = (10 * dp).toInt() })
        }
        speedBtn.setOnClickListener {
            speedIdx = (speedIdx + 1) % speeds.size
            val x = speeds[speedIdx]
            ctl.setSpeed(x)
            speedBtn.text = fmtSpeed(x)
            showIndicator("السرعة ${fmtSpeed(x)}", -1); removeCallbacks(hideIndicator); postDelayed(hideIndicator, 800)
            keepTop()
        }
        sleepBtn.setOnClickListener {
            sleepIdx = (sleepIdx + 1) % sleeps.size
            removeCallbacks(sleepRun)
            val m = sleeps[sleepIdx]
            if (m == 0) { sleepBtn.text = "مؤقت"; showIndicator("مؤقت النوم: مطفي", -1) }
            else { postDelayed(sleepRun, m * 60_000L); sleepAt = SystemClock.uptimeMillis() + m * 60_000L; sleepBtn.text = "$m د"; showIndicator("يوقف بعد $m دقيقة", -1) }
            removeCallbacks(hideIndicator); postDelayed(hideIndicator, 900)
            keepTop()
        }
        pipBtn.setOnClickListener { onPip?.invoke() }
        addView(topBar, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.RIGHT).apply {
            topMargin = (18 * dp).toInt(); rightMargin = (22 * dp).toInt()
        })
        aspectBtn.setOnClickListener { setAspect((aspectIdx + 1) % aspects.size); keepTop() }
        lockBtn.setOnClickListener { setLocked(true) }

        unlockBtn.visibility = GONE
        addView(unlockBtn, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.RIGHT).apply {
            topMargin = (18 * dp).toInt(); rightMargin = (22 * dp).toInt()
        })
        unlockBtn.setOnClickListener { setLocked(false) }
    }

    /** زر إضافي بأزرار المشغّل */
    fun addPill(label: String, onClick: () -> Unit): TextView {
        val t = pill(label)
        t.setOnClickListener { onClick() }
        topBar.addView(t, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = (10 * dp).toInt() })
        return t
    }

    private fun fmtSpeed(x: Float) = if (x == x.toInt().toFloat()) "${x.toInt()}x" else "${x}x"

    /** نرجّع السرعة المختارة بعد ما يتغيّر الفيديو */
    fun reapplySpeed() { if (speedIdx != 0) ctl.setSpeed(speeds[speedIdx]) }

    fun reset() {
        aspectIdx = 0
        aspectBtn.text = aspects[0].second
        if (speedIdx != 0) postDelayed({ ctl.setSpeed(speeds[speedIdx]) }, 400)
        locked = false
        mode = 0
        streakUntil = 0
        indicator.alpha = 0f
        topBar.visibility = GONE
        unlockBtn.visibility = GONE
    }

    fun restoreBrightness() {
        val lp = act.window.attributes
        lp.screenBrightness = -1f
        act.window.attributes = lp
    }

    // ---------- اللمس ----------
    private val gestures = GestureDetector(act, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (locked) { showUnlock(); return true }
            forwardTap(e)
            if (topBar.visibility == VISIBLE) hideTop.run() else keepTop()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (locked) return true
            val third = width / 3f
            when {
                e.x < third -> seekStep(-1)
                e.x > third * 2 -> seekStep(1)
                else -> togglePlay()
            }
            return true
        }
    })

    private var pinch = 1f
    private val scaler = ScaleGestureDetector(act, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(d: ScaleGestureDetector): Boolean { pinch = 1f; return !locked }
        override fun onScale(d: ScaleGestureDetector): Boolean { pinch *= d.scaleFactor; return true }
        override fun onScaleEnd(d: ScaleGestureDetector) {
            if (pinch > 1.12f) setAspect(1) else if (pinch < 0.9f) setAspect(0)
        }
    })

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val now = SystemClock.uptimeMillis()

        // نقرات متتالية بعد النقرتين: كل نقرة +10 / -10
        if (!locked && now < streakUntil && e.pointerCount == 1) {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y }
                MotionEvent.ACTION_UP -> if (abs(e.x - downX) < 24 * dp && abs(e.y - downY) < 24 * dp) {
                    val third = width / 3f
                    if (e.x < third) seekStep(-1) else if (e.x > third * 2) seekStep(1)
                }
            }
            return true
        }

        scaler.onTouchEvent(e)
        gestures.onTouchEvent(e)
        if (locked) return true

        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { startX = e.x; startY = e.y; mode = 0 }
            MotionEvent.ACTION_POINTER_DOWN -> { mode = -1; hideIndicator.run() }
            MotionEvent.ACTION_MOVE -> if (e.pointerCount == 1 && !scaler.isInProgress && mode != -1) onDrag(e)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (mode == 3) applyScrub()
                if (mode > 0) { removeCallbacks(hideIndicator); postDelayed(hideIndicator, 600) }
                mode = 0
            }
        }
        return true
    }

    private fun onDrag(e: MotionEvent) {
        val dx = e.x - startX
        val dy = e.y - startY
        val slop = 16 * dp
        if (mode == 0) {
            if (abs(dy) > slop && abs(dy) > abs(dx)) {
                mode = if (startX < width / 2f) 1 else 2
                startVal = if (mode == 1) currentBrightness() else audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVol
                startY = e.y
            } else if (abs(dx) > slop) {
                mode = 3
                startX = e.x
                seekDelta = 0.0
                seekCur = -1.0; seekDur = -1.0
                ctl.info { c, d -> seekCur = c; seekDur = d }
            }
            return
        }
        when (mode) {
            1, 2 -> {
                val f = (startVal + (startY - e.y) / (height * 0.75f)).coerceIn(0f, 1f)
                if (mode == 1) setBrightness(f) else audio.setStreamVolume(AudioManager.STREAM_MUSIC, (f * maxVol).roundToInt(), 0)
                showIndicator(if (mode == 1) "السطوع  ${(f * 100).roundToInt()}%" else if (f == 0f) "الصوت  صامت" else "الصوت  ${(f * 100).roundToInt()}%", (f * 100).roundToInt())
            }
            3 -> {
                seekDelta = (e.x - startX) / width.toDouble() * 120.0
                if (seekCur >= 0 && seekDur > 0) seekDelta = seekDelta.coerceIn(-seekCur, seekDur - seekCur - 1)
                val sign = if (seekDelta >= 0) "+" else "-"
                val txt = if (seekCur >= 0 && seekDur > 0)
                    "‎$sign${fmt(abs(seekDelta))}\n${fmt(seekCur + seekDelta)} / ${fmt(seekDur)}"
                else "‎$sign${fmt(abs(seekDelta))}"
                showIndicator(txt, if (seekDur > 0) ((seekCur + seekDelta) / seekDur * 100).roundToInt() else -1)
            }
        }
    }

    // ---------- الأوامر ----------
    private fun forwardTap(e: MotionEvent) {
        val t = target ?: return
        val down = MotionEvent.obtain(e.downTime, e.downTime, MotionEvent.ACTION_DOWN, e.x, e.y, 0)
        val up = MotionEvent.obtain(e.downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, e.x, e.y, 0)
        t.dispatchTouchEvent(down); t.dispatchTouchEvent(up)
        down.recycle(); up.recycle()
    }

    private fun seekStep(dir: Int) {
        streakUntil = SystemClock.uptimeMillis() + 750
        streakTotal += 10
        val r = if (dir < 0) leftRipple else rightRipple
        (if (dir < 0) rightRipple else leftRipple).alpha = 0f
        r.text = if (dir < 0) "‎« ${streakTotal} ثانية" else "‎${streakTotal} ثانية »"
        r.alpha = 1f
        removeCallbacks(hideRipples); postDelayed(hideRipples, 800)
        ctl.seekBy(dir * 10.0)
    }

    private fun applyScrub() {
        if (abs(seekDelta) < 1) return
        val d = seekDelta
        ctl.seekBy(d)
    }

    private fun togglePlay() {
        ctl.togglePlay { r ->
            when (r) {
                true -> showIndicator("تشغيل", -1)
                false -> showIndicator("إيقاف مؤقت", -1)
                null -> {}
            }
            removeCallbacks(hideIndicator); postDelayed(hideIndicator, 700)
        }
    }

    private fun setAspect(i: Int) {
        aspectIdx = i
        val (fit, label) = aspects[i]
        aspectBtn.text = label
        ctl.setFit(fit) { ok ->
            showIndicator(if (ok) label else "هذا المشغّل لا يدعم التكبير", -1)
            removeCallbacks(hideIndicator); postDelayed(hideIndicator, 800)
        }
    }

    private fun setLocked(l: Boolean) {
        locked = l
        hideTop.run()
        unlockBtn.visibility = GONE
        showIndicator(if (l) "تم قفل الشاشة" else "تم فتح القفل", -1)
        removeCallbacks(hideIndicator); postDelayed(hideIndicator, 800)
    }

    private fun showUnlock() {
        unlockBtn.visibility = VISIBLE
        removeCallbacks(hideUnlock); postDelayed(hideUnlock, 2500)
    }

    private fun keepTop() {
        topBar.animate().cancel()
        topBar.alpha = 1f
        topBar.visibility = VISIBLE
        removeCallbacks(hideTop); postDelayed(hideTop, 3500)
    }

    // ---------- أدوات ----------
    private fun currentBrightness(): Float {
        val b = act.window.attributes.screenBrightness
        if (b >= 0) return b
        return try { Settings.System.getInt(act.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f } catch (_: Exception) { 0.5f }
    }

    private fun setBrightness(f: Float) {
        val lp = act.window.attributes
        lp.screenBrightness = max(0.01f, min(1f, f))
        act.window.attributes = lp
    }

    private fun showIndicator(text: String, percent: Int) {
        removeCallbacks(hideIndicator)
        indicator.animate().cancel()
        indicator.alpha = 1f
        indTitle.text = text
        indBar.visibility = if (percent >= 0) VISIBLE else GONE
        if (percent >= 0) indBar.progress = percent.coerceIn(0, 100)
    }

    private fun fmt(sec: Double): String {
        val s = max(0, sec.roundToInt())
        val h = s / 3600; val m = (s % 3600) / 60; val ss = s % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, ss) else String.format("%d:%02d", m, ss)
    }

    private fun rounded(fill: Int, stroke: Int, r: Float) = GradientDrawable().apply {
        setColor(fill); cornerRadius = r * dp; setStroke((1.5f * dp).toInt(), stroke)
    }

    private fun pill(t: String) = TextView(act).apply {
        text = t
        setTextColor(Color.WHITE)
        textSize = 14f
        setPadding((16 * dp).toInt(), (9 * dp).toInt(), (16 * dp).toInt(), (9 * dp).toInt())
        background = rounded(Color.parseColor("#B307060F"), pink, 20f)
        setShadowLayer(10f, 0f, 0f, pink)
        isClickable = true
    }

    private fun rippleText() = TextView(act).apply {
        setTextColor(Color.WHITE)
        textSize = 20f
        setShadowLayer(18f, 0f, 0f, Color.parseColor("#22E4FF"))
        setPadding((22 * dp).toInt(), (16 * dp).toInt(), (22 * dp).toInt(), (16 * dp).toInt())
        background = GradientDrawable().apply { setColor(Color.parseColor("#6607060F")); cornerRadius = 40 * dp }
        alpha = 0f
    }

    companion object {
        // يلقى الفيديو الشغّال بالصفحة أو داخل iframe من نفس الموقع
        private const val FIND = "function f(d){var a=d.querySelectorAll('video'),b=null;for(var i=0;i<a.length;i++){if(!a[i].paused)return a[i];if(!b&&a[i].readyState>0)b=a[i]}if(b)return b;" +
            "var fr=d.querySelectorAll('iframe');for(var j=0;j<fr.length;j++){try{var x=f(fr[j].contentDocument);if(x)return x}catch(e){}}return a[0]||null}var v=f(document);"
        fun js(body: String) = "(function(){$FIND$body})()"
    }
}
