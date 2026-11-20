package com.dtm.voice

import android.accessibilityservice.AccessibilityService
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

    /** Oeffnet die App-Info und tippt "Beenden erzwingen" + OK, danach zurueck. */
    fun forceStop(pkg: String, done: (Boolean) -> Unit) {
        try {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            )
        } catch (e: Exception) {
            done(false); return
        }
        var step = 0
        var tries = 0
        var finished = false
        fun finish(ok: Boolean) {
            if (finished) return
            finished = true
            handler.postDelayed({ back() }, 350)
            done(ok)
        }
        val r = object : Runnable {
            override fun run() {
                tries++
                val root = rootInActiveWindow
                if (root != null && inSettings(root)) {
                    if (step == 0) {
                        when (findButton(root, FORCE)) {
                            ButtonState.ENABLED -> { if (clickAny(root, FORCE)) step = 1 }
                            ButtonState.DISABLED -> { finish(false); return }   // App laeuft gar nicht
                            ButtonState.NONE -> {}
                        }
                    } else {
                        if (clickAny(root, CONFIRM)) { finish(true); return }
                        // manche Handys fragen nicht nach: Knopf ist dann schon grau
                        if (tries > 8 && findButton(root, FORCE) == ButtonState.DISABLED) { finish(true); return }
                    }
                }
                if (tries > 30) { finish(step == 1); return }
                handler.postDelayed(this, 300)
            }
        }
        handler.postDelayed(r, 700)
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

    private enum class ButtonState { NONE, ENABLED, DISABLED }

    /** Klickbares Element zu einem Text: das Element selbst oder ein Elternelement. */
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

    private fun findButton(root: AccessibilityNodeInfo, texts: List<String>): ButtonState {
        var seen = false
        for (t in texts) {
            for (n in root.findAccessibilityNodeInfosByText(t) ?: continue) {
                if (t.length <= 3 && n.text?.toString()?.trim()?.equals(t, true) != true) continue
                val target = clickTarget(n) ?: continue
                seen = true
                if (target.isEnabled && n.isEnabled) return ButtonState.ENABLED
            }
        }
        return if (seen) ButtonState.DISABLED else ButtonState.NONE
    }

    private fun clickAny(root: AccessibilityNodeInfo, texts: List<String>): Boolean {
        for (t in texts) {
            val nodes = root.findAccessibilityNodeInfosByText(t) ?: continue
            // exakte Treffer zuerst
            val sorted = nodes.sortedBy { if (it.text?.toString()?.equals(t, true) == true || it.contentDescription?.toString()?.equals(t, true) == true) 0 else 1 }
            for (n in sorted) {
                if (!n.isEnabled) continue
                val target = clickTarget(n) ?: continue
                if (!target.isEnabled) continue
                if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
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
