package com.dtm.voice

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.view.KeyEvent

/** Versteht die gesprochenen Befehle und fuehrt sie aus. */
object Commander {

    private val OPEN = listOf("öffne", "öffnen", "starte", "starten", "open")
    private val CLOSE = listOf("schliesse", "schliessen", "schliess", "beende", "beenden")
    private val CLICK = listOf("tippe auf", "tippe", "klicke auf", "klicke", "klick auf", "klick", "drücke auf", "drücke", "drück", "wähle")
    private val WRITE = listOf("schreibe", "schreib", "tippe ein", "eingeben")
    private val FILLER = setOf("die", "den", "das", "app", "anwendung", "applikation", "bitte", "mal", "mir")
    private val NEVER = setOf("com.android.settings", "com.android.systemui", "android")

    const val HELP = "Sage zuerst das Aktivierungswort. Dann zum Beispiel: öffne Maps. Schließe diese App. " +
        "Schließe Spotify. Schließe alle Apps. Tippe auf Start. Scrolle runter. Schreibe Hallo. " +
        "Zurück. Startbildschirm. Pause. Play. Nächster Titel. Lauter. Leiser. Sprachsteuerung aus."

    private fun clean(s: String): String =
        s.lowercase().replace("ß", "ss").replace(Regex("[.,!?;:\"]"), " ").replace(Regex("\\s+"), " ").trim()

    private fun after(t: String, prefixes: List<String>): String? {
        for (p in prefixes.sortedByDescending { it.length }) {
            if (t == p) return ""
            if (t.startsWith("$p ")) return t.removePrefix(p).trim()
        }
        return null
    }

    private fun stripFiller(s: String): String =
        s.split(" ").dropWhile { it in FILLER }.joinToString(" ").trim()

    fun run(ctx: Context, raw: String, reply: (String) -> Unit) {
        val t = clean(raw)
        if (t.isBlank()) return
        fun has(vararg k: String) = k.any { t.contains(it) }
        val a11y = VoiceAccessibilityService.instance
        // Bedienbefehle (tippen, scrollen, schreiben) nur in Apps mit AN-Schalter
        val fg = VoiceAccessibilityService.lastPkg
        val inAppBlocked = fg != null && fg in Prefs.disabled(ctx)
        fun guardedInApp(): Boolean {
            if (inAppBlocked) { reply("${fg} ist gesperrt"); return false }
            return true
        }

        // --- Hilfe / Dienst ---
        if (has("hilfe", "was kann ich sagen")) { reply(HELP); return }
        if (t == "sprachsteuerung aus" || t == "gute nacht" || t == "beende sprachsteuerung") {
            reply("Sprachsteuerung aus")
            ctx.stopService(Intent(ctx, VoiceService::class.java))
            return
        }

        // --- Medien / Lautstaerke ---
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (t.length < 20 && has("lauter")) { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI); reply("Lauter"); return }
        if (t.length < 20 && has("leiser")) { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI); reply("Leiser"); return }
        if (t in setOf("pause", "stopp", "stop", "musik aus", "musik pausieren")) { media(am, KeyEvent.KEYCODE_MEDIA_PAUSE); reply("Pause"); return }
        if (t in setOf("play", "musik an", "abspielen", "musik abspielen", "weiter spielen")) { media(am, KeyEvent.KEYCODE_MEDIA_PLAY); reply("Play"); return }
        if (has("nächster", "nächstes", "überspringen")) { media(am, KeyEvent.KEYCODE_MEDIA_NEXT); reply("Nächster Titel"); return }
        if (has("vorheriger", "voriger", "vorheriges")) { media(am, KeyEvent.KEYCODE_MEDIA_PREVIOUS); reply("Vorheriger Titel"); return }

        // --- Navigation ---
        if (t == "zurück" || t == "zurueck") { if (a11y?.back() == true) reply("Zurück") else reply(NO_A11Y); return }
        if (t in setOf("startbildschirm", "home", "nach hause")) { if (a11y?.home() == true) reply("Startbildschirm") else reply(NO_A11Y); return }
        if (has("letzte apps", "übersicht", "zuletzt")) { if (a11y?.recents() == true) reply("Übersicht") else reply(NO_A11Y); return }
        if (has("scroll", "blätter", "runter", "nach unten", "nach oben") || t == "hoch") {
            if (a11y == null) { reply(NO_A11Y); return }
            if (!guardedInApp()) return
            val fwd = has("runter", "unten")
            reply(if (a11y.scroll(fwd)) (if (fwd) "Runter" else "Hoch") else "Nichts zum Scrollen")
            return
        }

        // --- Apps schliessen ---
        after(t, CLOSE)?.let { closeCmd(ctx, stripFiller(it), reply); return }

        // --- Apps oeffnen ---
        after(t, OPEN)?.let { openCmd(ctx, stripFiller(it), reply); return }

        // --- Schreiben ---
        after(t, WRITE)?.let {
            if (a11y == null) { reply(NO_A11Y); return }
            if (!guardedInApp()) return@let
            reply(if (a11y.typeText(it, false)) "Geschrieben" else "Kein Textfeld gefunden")
            return
        }
        after(t, listOf("suche", "suche nach"))?.let {
            if (it.isBlank()) return@let
            if (a11y == null) { reply(NO_A11Y); return }
            if (!guardedInApp()) return@let
            reply(if (a11y.typeText(it, true)) "Suche $it" else "Kein Suchfeld gefunden")
            return
        }

        // --- Klicken ---
        after(t, CLICK)?.let {
            val target = stripFiller(it)
            if (target.isBlank()) return@let
            if (a11y == null) { reply(NO_A11Y); return }
            if (!guardedInApp()) return@let
            reply(if (a11y.clickText(target)) "Getippt: $target" else "Nicht gefunden: $target")
            return
        }

        reply("Nicht verstanden")
    }

    private const val NO_A11Y = "Bedienungshilfe ist nicht aktiv"

    private fun media(am: AudioManager, code: Int) {
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    private fun openCmd(ctx: Context, name: String, reply: (String) -> Unit) {
        val apps = AppIndex.load(ctx)
        val app = AppIndex.find(apps, name)
        if (app == null) { reply("App nicht gefunden: $name"); return }
        if (app.pkg in Prefs.disabled(ctx)) { reply("${app.label} ist gesperrt"); return }
        val i = ctx.packageManager.getLaunchIntentForPackage(app.pkg)
        if (i == null) { reply("${app.label} lässt sich nicht öffnen"); return }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try {
            (VoiceAccessibilityService.instance ?: ctx).startActivity(i)
            reply("${app.label} geöffnet")
        } catch (e: Exception) {
            reply("${app.label} konnte nicht geöffnet werden")
        }
    }

    private fun closeCmd(ctx: Context, target: String, reply: (String) -> Unit) {
        val a11y = VoiceAccessibilityService.instance
        if (a11y == null) { reply(NO_A11Y); return }

        if (target in setOf("alle", "alles", "alle apps", "alles apps")) {
            a11y.closeAll { reply(if (it) "Alle Apps geschlossen" else "Alle schließen nicht gefunden") }
            return
        }

        val apps = AppIndex.load(ctx)
        val pkg: String
        val label: String
        if (target.isBlank() || target in setOf("diese", "dies", "das", "aktuelle", "aktuelle app", "diese app", "hier", "fenster")) {
            val p = VoiceAccessibilityService.lastPkg
            if (p == null) { reply("Keine App offen"); return }
            pkg = p
            label = apps.firstOrNull { it.pkg == p }?.label ?: p
        } else {
            val app = AppIndex.find(apps, target)
            if (app == null) { reply("App nicht gefunden: $target"); return }
            pkg = app.pkg
            label = app.label
        }

        if (pkg in NEVER) { reply("$label kann nicht geschlossen werden"); return }
        if (pkg in Prefs.disabled(ctx)) { reply("$label ist gesperrt"); return }

        try {
            (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).killBackgroundProcesses(pkg)
        } catch (_: Exception) {}
        a11y.forceStop(pkg) { ok -> reply(if (ok) "$label geschlossen" else "$label läuft nicht") }
    }
}
