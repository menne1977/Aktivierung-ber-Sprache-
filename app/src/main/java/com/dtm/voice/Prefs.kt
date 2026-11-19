package com.dtm.voice

import android.content.Context

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("dtm_voice", Context.MODE_PRIVATE)

    fun wakeWord(c: Context): String = sp(c).getString("wake", "hey titan") ?: "hey titan"
    fun setWakeWord(c: Context, w: String) = sp(c).edit().putString("wake", w.trim().lowercase()).apply()

    /** Apps, die per Sprache NICHT gesteuert werden duerfen (Schalter AUS). Neue Apps sind standardmaessig AN. */
    fun disabled(c: Context): Set<String> = HashSet(sp(c).getStringSet("disabled", emptySet()) ?: emptySet())
    fun setDisabled(c: Context, s: Set<String>) = sp(c).edit().putStringSet("disabled", HashSet(s)).apply()

    fun speak(c: Context): Boolean = sp(c).getBoolean("speak", true)
    fun setSpeak(c: Context, v: Boolean) = sp(c).edit().putBoolean("speak", v).apply()

    fun bluetooth(c: Context): Boolean = sp(c).getBoolean("bt", true)
    fun setBluetooth(c: Context, v: Boolean) = sp(c).edit().putBoolean("bt", v).apply()
}
