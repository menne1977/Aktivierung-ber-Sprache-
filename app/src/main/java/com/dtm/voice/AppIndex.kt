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

    /** Findet die App, die am besten zum gesprochenen Namen passt. */
    fun find(apps: List<AppInfo>, spoken: String): AppInfo? {
        val q = norm(spoken)
        if (q.isEmpty()) return null
        apps.firstOrNull { norm(it.label) == q }?.let { return it }
        val contains = apps.filter {
            val l = norm(it.label)
            l.isNotEmpty() && (l.contains(q) || (l.length >= 3 && q.contains(l)))
        }
        if (contains.isNotEmpty()) return contains.minByOrNull { Math.abs(it.label.length - q.length) }
        val best = apps.maxByOrNull { similarity(norm(it.label), q) } ?: return null
        return if (similarity(norm(best.label), q) >= 0.6) best else null
    }
}
