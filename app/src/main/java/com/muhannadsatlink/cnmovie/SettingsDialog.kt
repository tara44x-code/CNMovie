package com.muhannadsatlink.cnmovie

// الإعدادات + حول التطبيق
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat

object SettingsDialog {
    fun show(a: AppCompatActivity) {
        val dp = a.resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val cyan = Color.parseColor("#22E4FF"); val pink = Color.parseColor("#FF2BD6")
        val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(px(20), px(8), px(20), px(8)); layoutDirection = View.LAYOUT_DIRECTION_RTL }

        fun sw(title: String, sub: String, key: String, def: Boolean, onChange: ((SwitchCompat, Boolean) -> Unit)? = null) {
            val s = SwitchCompat(a).apply {
                text = title; textSize = 16f; setTextColor(Color.WHITE)
                isChecked = Prefs.get(a, key, def)
                thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(pink, Color.parseColor("#8C88A8")))
                trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(Color.parseColor("#66FF2BD6"), Color.parseColor("#332A2540")))
                setPadding(0, px(12), 0, 0)
                setOnCheckedChangeListener { b, v -> if (onChange != null) onChange(b as SwitchCompat, v) else Prefs.set(a, key, v) }
            }
            root.addView(s, LinearLayout.LayoutParams(-1, -2))
            root.addView(TextView(a).apply { text = sub; textSize = 12f; setTextColor(Color.parseColor("#A9A4C6")); setPadding(0, 0, 0, px(6)) })
        }

        sw("وضع الموقع الأصلي", "يطفي ثيم النيون واسم CN وكل تعديلات التطبيق على الموقع (للتجربة). يحتاج تعيد فتح التطبيق", "original", false) { _, v ->
            Prefs.set(a, "original", v)
            Toast.makeText(a, "سكّر التطبيق من الخلفية وافتحه حتى يتطبّق", Toast.LENGTH_LONG).show()
        }
        sw("التحميل بالواي فاي فقط", "التحميلات ما تستهلك بيانات الموبايل", Prefs.WIFI_ONLY, false)
        sw("قفل التطبيق", "بالبصمة أو رمز الجهاز عند الفتح", Prefs.LOCK, false) { b, v ->
            if (!v) { Prefs.set(a, Prefs.LOCK, false); return@sw }
            if (!AppLock.available(a)) {
                Toast.makeText(a, "فعّل بصمة أو رمز قفل للجهاز أولاً", Toast.LENGTH_LONG).show()
                b.isChecked = false; return@sw
            }
            AppLock.prompt(a, { Prefs.set(a, Prefs.LOCK, true); Toast.makeText(a, "تم تفعيل القفل", Toast.LENGTH_SHORT).show() }, { b.isChecked = false })
        }

        root.addView(TextView(a).apply {
            text = "نسخ تقرير التشخيص"; textSize = 14f; setTextColor(Color.parseColor("#22E4FF")); gravity = Gravity.CENTER
            setPadding(0, px(14), 0, 0)
            setOnClickListener { (a as? MainActivity)?.copyDiag() }
        })
        root.addView(TextView(a).apply {
            text = "فحص التحديثات"; textSize = 16f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable().apply { cornerRadius = 16 * dp; colors = intArrayOf(pink, Color.parseColor("#A23BFF")); orientation = GradientDrawable.Orientation.LEFT_RIGHT }
            setPadding(0, px(12), 0, px(12))
            setOnClickListener { UpdateChecker.check(a, manual = true) }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(14) })

        root.addView(TextView(a).apply {
            text = "CN.MOVIE • الإصدار ${BuildConfigInfo.version(a)}\nتطوير: مهند سات لنك"
            textSize = 14f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setShadowLayer(12f, 0f, 0f, cyan); setPadding(0, px(18), 0, px(4))
        })

        AlertDialog.Builder(a, R.style.Theme_CNMovie_Dialog)
            .setTitle("الإعدادات")
            .setView(ScrollView(a).apply { addView(root) })
            .setPositiveButton(R.string.close, null)
            .show().window?.decorView?.setBackgroundColor(Color.parseColor("#12101F"))
    }
}
