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
 * Macht die eigentliche Arbeit in fremden Apps: App beenden (Einstellungen -> "Beenden erzwingen"),
 * Klicken, Scrollen, Schreiben, Zurueck/Home/Uebersicht.
 */
class VoiceAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: VoiceAccessibilityService? = null
        /** Zuletzt im Vordergrund gesehene normale App (null = Startbildschirm). */
        @Volatile var lastPkg: String? = null

        private val FORCE = listOf("Beenden erzwingen", "Erzwingen beenden", "Force stop", "Anhalten erzwingen")
        private val CONFIRM = listOf("OK", "Ok", "Beenden erzwingen", "Force stop", "Beenden")
        private val CLEAR_ALL = listOf("Alle schließen", "Alle löschen", "Alle entfernen", "Close all", "Clear all", "Alles löschen")
    }

    private val handler = Handler(Looper.getMainLooper())
    private var launcherPkg: String? = null

    override fun onServiceConnected() {
        instance = this
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        launcherPkg = packageManager.resolveActivity(i, 0)?.activityInfo?.packageName
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e == null || e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val p = e.packageName?.toString() ?: return
        if (p == launcherPkg) { lastPkg = null; return }
        if (isIgnored(p)) return
        lastPkg = p
    }

    private fun isIgnored(p: String): Boolean =
        p == packageName || p == "android" || p.startsWith("com.android.systemui") ||
            p == "com.android.settings" || p.contains("inputmethod") || p.contains("keyboard") ||
            p == "com.google.android.googlequicksearchbox"

    // ---------- Aktionen ----------

    fun back() = performGlobalAction(GLOBAL_ACTION_BACK)
    fun home() = performGlobalAction(GLOBAL_ACTION_HOME)
    fun recents() = performGlobalAction(GLOBAL_ACTION_RECENTS)

    fun clickText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        return clickAny(root, listOf(text))
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
        val field = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findEditable(root) ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val ok = field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (ok && enter && Build.VERSION.SDK_INT >= 30) {
            field.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        }
        return ok
    }

    /** Oeffnet die App-Info der App und tippt auf "Beenden erzwingen" + OK. */
    fun forceStop(pkg: String, done: (Boolean) -> Unit) {
        try {
            val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
        } catch (e: Exception) {
            done(false); return
        }
        var step = 0
        var tries = 0
        val r = object : Runnable {
            override fun run() {
                tries++
                val root = rootInActiveWindow
                if (root != null) {
                    if (step == 0) {
                        if (clickAny(root, FORCE)) step = 1
                    } else if (clickAny(root, CONFIRM)) {
                        handler.postDelayed({ back() }, 400)
                        done(true)
                        return
                    }
                }
                if (tries > 25) {
                    back()
                    done(false)
                    return
                }
                handler.postDelayed(this, 350)
            }
        }
        handler.postDelayed(r, 800)
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
                if (tries > 8) {
                    home()
                    done(false)
                    return
                }
                handler.postDelayed(this, 350)
            }
        }
        handler.postDelayed(r, 900)
    }

    // ---------- Hilfsfunktionen ----------

    private fun clickAny(root: AccessibilityNodeInfo, texts: List<String>): Boolean {
        for (t in texts) {
            val nodes = root.findAccessibilityNodeInfosByText(t) ?: continue
            for (n in nodes) {
                var cur: AccessibilityNodeInfo? = n
                var depth = 0
                while (cur != null && depth < 6) {
                    if (cur.isClickable && cur.isEnabled) {
                        if (cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                        break
                    }
                    cur = cur.parent
                    depth++
                }
            }
        }
        return false
    }

    private fun findScrollable(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (n.isScrollable) return n
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            findScrollable(c)?.let { return it }
        }
        return null
    }

    private fun findEditable(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (n.isEditable) return n
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            findEditable(c)?.let { return it }
        }
        return null
    }
}
