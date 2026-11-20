package com.dtm.voice

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.view.KeyEvent

/** Versteht die gesprochenen Befehle und fuehrt sie aus. */
object Commander {

    // Verb vorne ("öffne Spotify") oder hinten ("Spotify öffnen")
    private val OPEN_PRE = listOf("öffne", "öffnen", "starte", "starten", "start", "open", "zeige", "zeig")
    private val OPEN_POST = listOf("öffnen", "starten", "aufmachen", "auf", "öffne", "starte")
    private val CLOSE_PRE = listOf("schliesse", "schliessen", "schliess", "beende", "beenden", "stoppe", "kill")
    private val CLOSE_POST = listOf("schliessen", "beenden", "zumachen", "zu", "weg", "aus", "stoppen", "schliesse", "beende")
    private val CLICK = listOf("tippe auf", "tippe", "klicke auf", "klicke", "klick auf", "klick", "drücke auf", "drücke", "drück auf", "drück", "wähle")
    private val WRITE = listOf("schreibe", "schreib", "eingeben", "gib ein")
    private val SEARCH = listOf("suche nach", "suche", "such nach", "such")
    private val FILLER = setOf("die", "den", "das", "der", "app", "apps", "anwendung", "applikation", "bitte", "mal", "mir", "jetzt", "mit")
    private val CURRENT = setOf("", "diese", "dies", "aktuelle", "hier", "fenster", "es", "sie")
    private val ALL = setOf("alle", "alles")
    private val NEVER = setOf("com.android.settings", "com.android.systemui", "android")

    private const val NO_A11Y = "Bedienungshilfe ist nicht aktiv. Bitte in der App Schritt 2 erledigen."

    const val HELP = "Sage das Aktivierungswort, warte auf den Ton, dann zum Beispiel: Spotify öffnen. " +
        "Diese App schließen. Maps schließen. Alle Apps schließen. Tippe auf Start. Runter. Hoch. " +
        "Schreibe Hallo. Suche Tankstelle. Zurück. Startbildschirm. Pause. Play. Nächster Titel. Lauter. Leiser."

    private fun clean(s: String): String =
        s.lowercase().replace("ß", "ss").replace(Regex("[.,!?;:\"]"), " ").replace(Regex("\\s+"), " ").trim()

    private fun pre(t: String, list: List<String>): String? {
        for (p in list.sortedByDescending { it.length }) {
            if (t == p) return ""
            if (t.startsWith("$p ")) return t.removePrefix(p).trim()
        }
        return null
    }

    private fun post(t: String, list: List<String>): String? {
        for (p in list.sortedByDescending { it.length }) {
            if (t.endsWith(" $p")) return t.removeSuffix(p).trim()
        }
        return null
    }

    private fun target(s: String): String = s.split(" ").filter { it.isNotEmpty() && it !in FILLER }.joinToString(" ")
    private fun leading(s: String): String = s.split(" ").dropWhile { it in FILLER }.joinToString(" ").trim()

    fun run(ctx: Context, raw: String, reply: (String) -> Unit) {
        var t = clean(raw)
        t = t.removePrefix("bitte ").removeSuffix(" bitte").trim()
        if (t.isBlank()) { reply("Nichts verstanden"); return }
        val a11y = VoiceAccessibilityService.instance
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // ---------- feste Befehle ----------
        when (t) {
            "hilfe", "was kann ich sagen", "befehle" -> { reply(HELP); return }
            "sprachsteuerung aus", "sprachsteuerung beenden", "gute nacht" -> {
                reply("Sprachsteuerung aus")
                ctx.stopService(Intent(ctx, VoiceService::class.java))
                return
            }
            "pause", "stopp", "stop", "musik aus", "musik stopp", "anhalten" -> { media(am, KeyEvent.KEYCODE_MEDIA_PAUSE); reply("Pause"); return }
            "play", "abspielen", "musik an", "weiter", "musik weiter", "musik abspielen", "spielen" -> { media(am, KeyEvent.KEYCODE_MEDIA_PLAY); reply("Play"); return }
            "zurück", "zurueck", "geh zurück" -> { reply(if (a11y?.back() == true) "Zurück" else NO_A11Y); return }
            "startbildschirm", "home", "homescreen", "nach hause", "start bildschirm" -> { reply(if (a11y?.home() == true) "Startbildschirm" else NO_A11Y); return }
            "letzte apps", "übersicht", "app übersicht", "zuletzt verwendet" -> { reply(if (a11y?.recents() == true) "Übersicht" else NO_A11Y); return }
        }

        // ---------- schliessen ----------
        if (t.startsWith("mach ") && t.endsWith(" zu")) { closeCmd(ctx, target(t.removePrefix("mach ").removeSuffix(" zu")), reply); return }
        (pre(t, CLOSE_PRE) ?: post(t, CLOSE_POST))?.let { closeCmd(ctx, target(it), reply); return }

        // ---------- oeffnen ----------
        if (t.startsWith("mach ") && t.endsWith(" auf")) { openCmd(ctx, target(t.removePrefix("mach ").removeSuffix(" auf")), reply); return }
        (pre(t, OPEN_PRE) ?: post(t, OPEN_POST))?.let { openCmd(ctx, target(it), reply); return }

        // ---------- in der App: schreiben / suchen / tippen ----------
        pre(t, WRITE)?.let { text ->
            if (text.isBlank()) { reply("Was soll ich schreiben?"); return }
            if (!inAppAllowed(ctx, a11y, reply)) return
            reply(if (a11y!!.typeText(text, false)) "Geschrieben" else "Kein Textfeld gefunden")
            return
        }
        pre(t, SEARCH)?.let { text ->
            if (text.isBlank()) { reply("Wonach soll ich suchen?"); return }
            if (!inAppAllowed(ctx, a11y, reply)) return
            reply(if (a11y!!.typeText(text, true)) "Suche $text" else "Kein Suchfeld gefunden")
            return
        }
        pre(t, CLICK)?.let { raw2 ->
            val what = leading(raw2)
            if (what.isBlank()) { reply("Worauf soll ich tippen?"); return }
            if (!inAppAllowed(ctx, a11y, reply)) return
            reply(if (a11y!!.clickText(what)) "Getippt: $what" else "Nicht gefunden: $what")
            return
        }

        // ---------- Stichworte ----------
        fun has(vararg k: String) = k.any { Regex("\\b${Regex.escape(it)}\\b").containsMatchIn(t) }
        if (has("lauter")) { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI); reply("Lauter"); return }
        if (has("leiser")) { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI); reply("Leiser"); return }
        if (has("nächster", "nächstes", "nächste", "überspringen", "skip")) { media(am, KeyEvent.KEYCODE_MEDIA_NEXT); reply("Nächster Titel"); return }
        if (has("vorheriger", "voriger", "vorheriges", "vorherige", "letzter titel")) { media(am, KeyEvent.KEYCODE_MEDIA_PREVIOUS); reply("Vorheriger Titel"); return }
        if (has("runter", "unten", "hoch", "oben", "scrolle", "scrollen", "scroll", "blättern", "blättere")) {
            if (!inAppAllowed(ctx, a11y, reply)) return
            val fwd = !has("hoch", "oben", "rauf")
            reply(if (a11y!!.scroll(fwd)) (if (fwd) "Runter" else "Hoch") else "Nichts zum Scrollen")
            return
        }

        reply("Nicht verstanden: $raw")
    }

    private fun label(ctx: Context, pkg: String): String =
        try { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { pkg }

    /** Bedienbefehle nur, wenn die Bedienungshilfe laeuft und die offene App auf AN steht. */
    private fun inAppAllowed(ctx: Context, a11y: VoiceAccessibilityService?, reply: (String) -> Unit): Boolean {
        if (a11y == null) { reply(NO_A11Y); return false }
        val fg = a11y.currentPkg()
        if (fg != null && fg in Prefs.disabled(ctx)) { reply("${label(ctx, fg)} ist gesperrt"); return false }
        return true
    }

    private fun media(am: AudioManager, code: Int) {
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    private fun openCmd(ctx: Context, name: String, reply: (String) -> Unit) {
        if (name.isBlank()) { reply("Welche App soll ich öffnen?"); return }
        val app = AppIndex.find(AppIndex.load(ctx), name)
        if (app == null) { reply("App nicht gefunden: $name"); return }
        if (app.pkg in Prefs.disabled(ctx)) { reply("${app.label} ist gesperrt"); return }
        val i = ctx.packageManager.getLaunchIntentForPackage(app.pkg)
        if (i == null) { reply("${app.label} lässt sich nicht öffnen"); return }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try {
            // Start ueber die Bedienungshilfe ist von Android aus dem Hintergrund erlaubt
            (VoiceAccessibilityService.instance ?: ctx).startActivity(i)
            reply("${app.label} geöffnet")
        } catch (e: Exception) {
            reply("${app.label} konnte nicht geöffnet werden")
        }
    }

    private fun closeCmd(ctx: Context, name: String, reply: (String) -> Unit) {
        val a11y = VoiceAccessibilityService.instance
        if (a11y == null) { reply(NO_A11Y); return }

        if (name in ALL) {
            a11y.closeAll { ok -> reply(if (ok) "Alle Apps geschlossen" else "Alle schließen nicht gefunden") }
            return
        }

        val pkg: String
        if (name in CURRENT) {
            pkg = a11y.currentPkg() ?: run { reply("Keine App offen"); return }
        } else {
            val app = AppIndex.find(AppIndex.load(ctx), name) ?: run { reply("App nicht gefunden: $name"); return }
            pkg = app.pkg
        }
        val lbl = label(ctx, pkg)
        if (pkg in NEVER || pkg == ctx.packageName) { reply("$lbl kann nicht geschlossen werden"); return }
        if (pkg in Prefs.disabled(ctx)) { reply("$lbl ist gesperrt"); return }

        try { (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).killBackgroundProcesses(pkg) } catch (_: Exception) {}
        a11y.forceStop(pkg) { ok -> reply(if (ok) "$lbl geschlossen" else "$lbl läuft nicht") }
    }
}
