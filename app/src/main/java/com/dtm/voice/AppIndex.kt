package com.dtm.voice

import android.content.Context
import android.content.Intent

data class AppInfo(val label: String, val pkg: String)

object AppIndex {

    fun load(c: Context): List<AppInfo> {
        val pm = c.packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(i, 0)
            .map { AppInfo(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .filter { it.pkg != c.packageName }
            .distinctBy { it.pkg }
            .sortedBy { it.label.lowercase() }
    }

    fun norm(s: String): String =
        s.lowercase()
            .replace("ß", "ss")
            .replace(Regex("[^a-z0-9äöü ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    fun similarity(a: String, b: String): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
        }
        return 1.0 - d[a.length][b.length].toDouble() / maxOf(a.length, b.length)
    }

    private fun ns(s: String) = s.replace(" ", "")

    /** Findet die App, die am besten zum gesprochenen Namen passt ("you tube" = "YouTube"). */
    fun find(apps: List<AppInfo>, spoken: String): AppInfo? {
        val q = norm(spoken)
        if (q.isEmpty()) return null
        val qn = ns(q)
        apps.firstOrNull { norm(it.label) == q || ns(norm(it.label)) == qn }?.let { return it }
        val contains = apps.filter {
            val l = ns(norm(it.label))
            l.isNotEmpty() && (l.startsWith(qn) || (qn.length >= 3 && l.contains(qn)) || (l.length >= 3 && qn.contains(l)))
        }
        if (contains.isNotEmpty()) return contains.minByOrNull { Math.abs(ns(norm(it.label)).length - qn.length) }
        val best = apps.maxByOrNull { similarity(ns(norm(it.label)), qn) } ?: return null
        return if (similarity(ns(norm(best.label)), qn) >= 0.6) best else null
    }
}

/** Erkennt das Aktivierungswort auch bei leicht falscher Erkennung ("titen", "ti tan"). */
object Wake {
    private fun core(c: android.content.Context): String {
        val w = AppIndex.norm(Prefs.wakeWord(c)).split(" ").filter { it.isNotEmpty() }
        // "hey titan" -> "titan" (das markante Wort zaehlt)
        return w.filter { it !in setOf("hey", "hei", "hallo", "ok", "okay", "hi") }.lastOrNull() ?: w.lastOrNull() ?: ""
    }

    /** Index des ersten Worts nach dem Aktivierungswort, oder -1. */
    fun after(c: android.content.Context, text: String): Int {
        val k = core(c)
        if (k.length < 3) return -1
        val words = AppIndex.norm(text).split(" ").filter { it.isNotEmpty() }
        val limit = if (k.length <= 5) 0.75 else 0.7
        for (i in words.indices) {
            if (AppIndex.similarity(words[i], k) >= limit) return i + 1
            if (i + 1 < words.size && AppIndex.similarity(words[i] + words[i + 1], k) >= 0.8) return i + 2
        }
        return -1
    }

    fun matches(c: android.content.Context, text: String) = after(c, text) >= 0

    fun rest(c: android.content.Context, text: String): String {
        val i = after(c, text)
        if (i < 0) return text
        return AppIndex.norm(text).split(" ").filter { it.isNotEmpty() }.drop(i).joinToString(" ")
    }
}
