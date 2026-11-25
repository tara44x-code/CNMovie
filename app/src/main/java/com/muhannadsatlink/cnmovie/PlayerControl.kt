package com.muhannadsatlink.cnmovie

// واجهة موحّدة للمشغّل: نفس الإيماءات تشتغل على مشغّل الموقع وعلى المشغّل الداخلي
import android.view.KeyEvent
import android.webkit.WebView

interface PlayerControl {
    fun info(cb: (cur: Double, dur: Double) -> Unit)
    fun seekBy(sec: Double)
    fun togglePlay(cb: (playing: Boolean?) -> Unit)
    fun setFit(mode: String, cb: (ok: Boolean) -> Unit) // contain / cover / fill
    fun setSpeed(x: Float)
    fun pause()
}

class WebPlayerControl(private val web: WebView) : PlayerControl {
    private fun js(body: String) = PlayerGestureLayer.js(body)

    override fun info(cb: (Double, Double) -> Unit) {
        web.evaluateJavascript(js("return v?(v.currentTime+','+v.duration):'';")) { r ->
            val p = r.trim('"').split(',')
            cb(p.getOrNull(0)?.toDoubleOrNull() ?: -1.0, p.getOrNull(1)?.toDoubleOrNull() ?: -1.0)
        }
    }

    override fun seekBy(sec: Double) {
        web.evaluateJavascript(js("if(!v)return 'none';v.currentTime=Math.max(0,Math.min((v.duration||1e9)-0.5,v.currentTime+($sec)));return 'ok';")) { res ->
            if (!res.contains("ok")) {
                val key = if (sec < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
                repeat(2) {
                    web.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
                    web.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
                }
            }
        }
    }

    override fun togglePlay(cb: (Boolean?) -> Unit) {
        web.evaluateJavascript(js("if(!v)return 'none';if(v.paused){v.play();return 'play'}v.pause();return 'pause';")) { r ->
            cb(if (r.contains("play")) true else if (r.contains("pause")) false else null)
        }
    }

    override fun setFit(mode: String, cb: (Boolean) -> Unit) {
        web.evaluateJavascript(js("if(!v)return 'none';v.style.setProperty('object-fit','$mode','important');return 'ok';")) { cb(it.contains("ok")) }
    }

    override fun setSpeed(x: Float) {
        web.evaluateJavascript(js("if(v)v.playbackRate=$x;return 'ok';"), null)
    }

    override fun pause() {
        web.evaluateJavascript(js("if(v)v.pause();return 'ok';"), null)
    }
}
