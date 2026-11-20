package com.dtm.voice

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Macht die Arbeit in fremden Apps: App beenden (Einstellungen -> "Beenden erzwingen"),
 * Tippen, Scrollen, Schreiben, Zurueck/Home/Uebersicht.
 */
class VoiceAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: VoiceAccessibilityService? = null
        @Volatile var lastPkg: String? = null
        /** Was zuletzt auf der Einstellungen-Seite zu sehen war, falls das Schliessen nicht klappte. */
        @Volatile var dump: String = ""

        // Texte je nach Hersteller (Pixel, Samsung, Xiaomi, Motorola ...)
        private val FORCE = listOf(
            "Beenden erzwingen", "Stopp erzwingen", "Stoppen erzwingen", "Erzwingen beenden",
            "Erzwungenes Beenden", "Anhalten erzwingen", "Force stop", "Stopp erzw."
        )
        private val CONFIRM = listOf("OK", "Ok", "Beenden erzwingen", "Stopp erzwingen", "Stoppen erzwingen", "Force stop", "Beenden", "Stoppen")
        private val CLEAR_ALL = listOf("Alle schließen", "Alle löschen", "Alle entfernen", "Alles löschen", "Alles schließen", "Close all", "Clear all")
    }

    private val handler = Handler(Looper.getMainLooper())
    private var homePkgs: Set<String> = emptySet()

    override fun onServiceConnected() {
        instance = this
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        homePkgs = packageManager.queryIntentActivities(i, 0).map { it.activityInfo.packageName }.toSet()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e == null || e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val p = e.packageName?.toString() ?: return
        if (p in homePkgs) { lastPkg = null; return }
        if (isIgnored(p)) return
        lastPkg = p
    }

    private fun isIgnored(p: String): Boolean =
        p == packageName || p == "android" || p.startsWith("com.android.systemui") ||
            p == "com.android.settings" || p.contains("inputmethod") || p.contains("keyboard") ||
            p.contains("honeyboard") || p == "com.google.android.googlequicksearchbox"

    /** App, die gerade im Vordergrund ist (null = Startbildschirm). */
    fun currentPkg(): String? {
        val p = try { rootInActiveWindow?.packageName?.toString() } catch (_: Exception) { null }
        if (p != null) {
            if (p in homePkgs) return null
            if (!isIgnored(p)) return p
        }
        return lastPkg
    }

    /** Paket des Fensters, das gerade wirklich vorne ist (ohne Rueckfall). */
    fun topPkg(): String? = try { rootInActiveWindow?.packageName?.toString() } catch (_: Exception) { null }

    private fun inSettings(root: AccessibilityNodeInfo): Boolean {
        val p = root.packageName?.toString() ?: return false
        return p.contains("settings", true) || p.contains("packageinstaller", true) || p.contains("permissioncontroller", true)
    }

    // ---------- Aktionen ----------

    fun back() = performGlobalAction(GLOBAL_ACTION_BACK)
    fun home() = performGlobalAction(GLOBAL_ACTION_HOME)
    fun recents() = performGlobalAction(GLOBAL_ACTION_RECENTS)

    fun clickText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        // zuerst exakt, dann Wortanfang gross geschrieben
        return clickAny(root, listOf(text, text.replaceFirstChar { it.uppercase() }))
    }

    fun scroll(forward: Boolean): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = findScrollable(root) ?: return false
        return node.performAction(
            if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        )
    }

    fun typeText(text: String, enter: Boolean): Boolean {
        val root = rootInActiveWindow ?: return false
        val field = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable } ?: findEditable(root) ?: return false
        field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val ok = field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (ok && enter && Build.VERSION.SDK_INT >= 30) {
            field.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        }
        return ok
    }

    /** Oeffnet die App-Info und tippt "Beenden erzwingen" + Bestaetigung, danach zurueck. */
    fun forceStop(pkg: String, done: (Boolean) -> Unit) {
        dump = ""
        fun openInfo(): Boolean = try {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            )
            true
        } catch (e: Exception) { false }
        if (!openInfo()) { done(false); return }
        var relaunched = 0
        var step = 0          // 0 = Knopf suchen, 1 = Bestaetigung suchen
        var tries = 0
        var finished = false
        fun leave() {
            // aus den Einstellungen herausgehen, notfalls bis zum Startbildschirm
            handler.postDelayed({ back() }, 300)
            handler.postDelayed({ if (inSettingsNow()) back() }, 1000)
            handler.postDelayed({ if (inSettingsNow()) home() }, 1700)
        }
        fun finish(ok: Boolean) {
            if (finished) return
            finished = true
            if (!ok) dump = dumpScreen()
            leave()
            done(ok)
        }
        val r = object : Runnable {
            override fun run() {
                tries++
                val roots = allRoots()
                if (roots.none { inSettings(it) } && step == 0 && relaunched < 2 && tries in setOf(7, 15)) {
                    // Ein System-Fenster (z. B. Mikrofon-Hinweis) verdeckt die Einstellungen: wegdruecken und neu oeffnen
                    relaunched++
                    back()
                    handler.postDelayed({ openInfo() }, 500)
                }
                if (roots.any { inSettings(it) }) {
                    if (step == 0) {
                        val node = findClickable(roots, listOf("erzwingen", "force stop", "force-stop", "forcestop"))
                        if (node != null) {
                            if (!node.isEnabled) { finish(false); return }   // App laeuft nicht (Knopf grau)
                            if (press(node)) { step = 1; tries = 0 }
                        } else if (tries % 5 == 0) {
                            roots.firstNotNullOfOrNull { findScrollable(it) }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                        }
                    } else {
                        val ok = findClickable(roots, listOf("ok", "erzwingen", "force stop", "stoppen"), exact = setOf("ok"))
                        if (ok != null && ok.isEnabled && press(ok)) { finish(true); return }
                        if (tries > 6) {
                            // kein Dialog mehr, Knopf ist grau -> App ist beendet
                            val again = findClickable(roots, listOf("erzwingen", "force stop"))
                            if (again != null && !again.isEnabled) { finish(true); return }
                        }
                    }
                }
                if (tries > 30) { finish(step == 1); return }
                handler.postDelayed(this, 300)
            }
        }
        handler.postDelayed(r, 900)
    }

    /** Uebersicht oeffnen und "Alle schliessen" druecken. */
    fun closeAll(done: (Boolean) -> Unit) {
        recents()
        var tries = 0
        val r = object : Runnable {
            override fun run() {
                tries++
                val root = rootInActiveWindow
                if (root != null && clickAny(root, CLEAR_ALL)) {
                    handler.postDelayed({ home() }, 500)
                    done(true)
                    return
                }
                if (tries > 10) {
                    home()
                    done(false)
                    return
                }
                handler.postDelayed(this, 300)
            }
        }
        handler.postDelayed(r, 800)
    }

    // ---------- Hilfsfunktionen ----------

    private fun allRoots(): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        try { rootInActiveWindow?.let { out.add(it) } } catch (_: Exception) {}
        try { windows?.forEach { w -> w.root?.let { out.add(it) } } } catch (_: Exception) {}
        return out
    }

    private fun inSettingsNow(): Boolean = allRoots().any { inSettings(it) }

    private fun walk(n: AccessibilityNodeInfo, f: (AccessibilityNodeInfo) -> Unit) {
        f(n)
        for (i in 0 until n.childCount) {
            val c = try { n.getChild(i) } catch (_: Exception) { null } ?: continue
            walk(c, f)
        }
    }

    private fun label(n: AccessibilityNodeInfo): String =
        ((n.text?.toString() ?: "") + " " + (n.contentDescription?.toString() ?: "")).trim().lowercase()

    /** Klickbares Element, dessen Text/Beschreibung eines der Woerter enthaelt (oder bei exact: genau gleich ist). */
    private fun findClickable(roots: List<AccessibilityNodeInfo>, words: List<String>, exact: Set<String> = emptySet()): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        for (root in roots) {
            walk(root) { n ->
                if (best != null && best!!.isEnabled) return@walk
                val l = label(n)
                if (l.isEmpty()) return@walk
                val hit = words.any { w -> if (w in exact) (n.text?.toString()?.trim()?.lowercase() == w || n.contentDescription?.toString()?.trim()?.lowercase() == w) else l.contains(w) }
                if (!hit) return@walk
                var cur: AccessibilityNodeInfo? = n
                var d = 0
                while (cur != null && d < 6) {
                    if (cur.isClickable || cur.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }) {
                        if (best == null || (!best!!.isEnabled && cur.isEnabled)) best = cur
                        break
                    }
                    cur = cur.parent
                    d++
                }
            }
        }
        return best
    }

    /** Klick; wenn Android ihn nicht annimmt, ein Tipp auf die Mitte des Elements. */
    private fun press(n: AccessibilityNodeInfo): Boolean {
        if (n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        val r = Rect()
        n.getBoundsInScreen(r)
        if (r.isEmpty) return false
        val path = Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 60)).build()
        return try { dispatchGesture(g, null, null) } catch (_: Exception) { false }
    }

    private fun dumpScreen(): String {
        val sb = StringBuilder()
        for (root in allRoots()) {
            sb.append("[").append(root.packageName).append("] ")
            var count = 0
            walk(root) { n ->
                val l = label(n)
                if (l.isNotEmpty() && count < 40) { sb.append(l.take(30)).append(if (n.isClickable) "*" else "").append(" | "); count++ }
            }
            sb.append("\n")
        }
        return sb.toString().take(1500)
    }

    private fun clickTarget(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = n
        var depth = 0
        while (cur != null && depth < 6) {
            if (cur.isClickable) return cur
            cur = cur.parent
            depth++
        }
        return null
    }

    private fun clickAny(root: AccessibilityNodeInfo, texts: List<String>): Boolean {
        for (t in texts) {
            val nodes = root.findAccessibilityNodeInfosByText(t) ?: continue
            val sorted = nodes.sortedBy { if (it.text?.toString()?.equals(t, true) == true || it.contentDescription?.toString()?.equals(t, true) == true) 0 else 1 }
            for (n in sorted) {
                if (t.length <= 3 && n.text?.toString()?.trim()?.equals(t, true) != true) continue
                if (!n.isEnabled) continue
                val target = clickTarget(n) ?: continue
                if (!target.isEnabled) continue
                if (press(target)) return true
            }
        }
        return false
    }

    private fun findScrollable(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (n.isScrollable && n.isVisibleToUser) return n
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            findScrollable(c)?.let { return it }
        }
        return null
    }

    private fun findEditable(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (n.isEditable && n.isVisibleToUser) return n
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            findEditable(c)?.let { return it }
        }
        return null
    }
}
