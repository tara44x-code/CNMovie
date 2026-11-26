package com.muhannadsatlink.cnmovie

import android.content.Context

object Prefs {
    private fun p(c: Context) = c.getSharedPreferences("cn", Context.MODE_PRIVATE)
    fun get(c: Context, k: String, def: Boolean) = p(c).getBoolean(k, def)
    fun set(c: Context, k: String, v: Boolean) = p(c).edit().putBoolean(k, v).apply()
    fun getLong(c: Context, k: String) = p(c).getLong(k, 0L)
    fun setLong(c: Context, k: String, v: Long) = p(c).edit().putLong(k, v).apply()
    fun getInt(c: Context, k: String, def: Int) = p(c).getInt(k, def)
    fun setInt(c: Context, k: String, v: Int) = p(c).edit().putInt(k, v).apply()

    const val AUTO_SKIP = "autoSkip"
    const val AUTO_NEXT = "autoNext"
    const val WIFI_ONLY = "wifiOnly"
    const val LOCK = "lock"
}
