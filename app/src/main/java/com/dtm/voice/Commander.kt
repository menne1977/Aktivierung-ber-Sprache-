package com.dtm.voice

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent

/** Versteht die gesprochenen Befehle und fuehrt sie aus. */
object Commander {

    private val main = Handler(Looper.getMainLooper())

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

    /** Mehrere Erkennungs-Varianten: die erste, die zu einem Befehl und (bei Apps) zu einer echten App passt, gewinnt. */
    fun run(ctx: Context, candidates: List<String>, reply: (String) -> Unit) {
        val list = candidates.filter { it.isNotBlank() }
        if (list.isEmpty()) { reply("Nichts verstanden"); return }
        for ((i, c) in list.withIndex()) {
            if (tryRun(ctx, c, reply, i == list.lastIndex)) return
        }
        reply("Nicht verstanden: ${list[0]}")
    }

    fun run(ctx: Context, raw: String, reply: (String) -> Unit) = run(ctx, listOf(raw), reply)

    /** true = Befehl wurde verstanden und ausgefuehrt (Antwort kommt ueber reply). */
    private fun tryRun(ctx: Context, raw: String, reply: (String) -> Unit, last: Boolean): Boolean {
        var t = clean(raw)
        t = t.removePrefix("bitte ").removeSuffix(" bitte").trim()
        if (t.isBlank()) return false
        val a11y = VoiceAccessibilityService.instance
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // ---------- feste Befehle ----------
        when (t) {
            "hilfe", "was kann ich sagen", "befehle" -> { reply(HELP); return true }
            "sprachsteuerung aus", "sprachsteuerung beenden", "gute nacht" -> {
                reply("Sprachsteuerung aus")
                ctx.stopService(Intent(ctx, VoiceService::class.java))
                return true
            }
            "pause", "stopp", "stop", "musik aus", "musik stopp", "anhalten" -> { media(am, KeyEvent.KEYCODE_MEDIA_PAUSE); reply("Pause"); return true }
            "play", "abspielen", "musik an", "weiter", "musik weiter", "musik abspielen", "spielen" -> { media(am, KeyEvent.KEYCODE_MEDIA_PLAY); reply("Play"); return true }
            "zurück", "zurueck", "geh zurück" -> { reply(if (a11y?.back() == true) "Zurück" else NO_A11Y); return true }
            "startbildschirm", "home", "homescreen", "nach hause", "start bildschirm" -> { reply(if (a11y?.home() == true) "Startbildschirm" else NO_A11Y); return true }
            "letzte apps", "übersicht", "app übersicht", "zuletzt verwendet" -> { reply(if (a11y?.recents() == true) "Übersicht" else NO_A11Y); return true }
        }

        // ---------- schliessen ----------
        if (t.startsWith("mach ") && t.endsWith(" zu")) return closeCmd(ctx, target(t.removePrefix("mach ").removeSuffix(" zu")), reply, last)
        (pre(t, CLOSE_PRE) ?: post(t, CLOSE_POST))?.let { return closeCmd(ctx, target(it), reply, last) }

        // ---------- oeffnen ----------
        if (t.startsWith("mach ") && t.endsWith(" auf")) return openCmd(ctx, target(t.removePrefix("mach ").removeSuffix(" auf")), reply, last)
        (pre(t, OPEN_PRE) ?: post(t, OPEN_POST))?.let { return openCmd(ctx, target(it), reply, last) }

        // ---------- in der App: schreiben / suchen / tippen ----------
        pre(t, WRITE)?.let { text ->
            if (text.isBlank()) { reply("Was soll ich schreiben?"); return true }
            if (!inAppAllowed(ctx, a11y, reply)) return true
            reply(if (a11y!!.typeText(text, false)) "Geschrieben" else "Kein Textfeld gefunden")
            return true
        }
        pre(t, SEARCH)?.let { text ->
            if (text.isBlank()) { reply("Wonach soll ich suchen?"); return true }
            if (!inAppAllowed(ctx, a11y, reply)) return true
            reply(if (a11y!!.typeText(text, true)) "Suche $text" else "Kein Suchfeld gefunden")
            return true
        }
        pre(t, CLICK)?.let { raw2 ->
            val what = leading(raw2)
            if (what.isBlank()) { reply("Worauf soll ich tippen?"); return true }
            if (!inAppAllowed(ctx, a11y, reply)) return true
            reply(if (a11y!!.clickText(what)) "Getippt: $what" else "Nicht gefunden: $what")
            return true
        }

        // ---------- Stichworte ----------
        fun has(vararg k: String) = k.any { Regex("\\b${Regex.escape(it)}\\b").containsMatchIn(t) }
        if (has("lauter")) { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI); reply("Lauter"); return true }
        if (has("leiser")) { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI); reply("Leiser"); return true }
        if (has("nächster", "nächstes", "nächste", "überspringen", "skip")) { media(am, KeyEvent.KEYCODE_MEDIA_NEXT); reply("Nächster Titel"); return true }
        if (has("vorheriger", "voriger", "vorheriges", "vorherige", "letzter titel")) { media(am, KeyEvent.KEYCODE_MEDIA_PREVIOUS); reply("Vorheriger Titel"); return true }
        if (has("runter", "unten", "hoch", "oben", "scrolle", "scrollen", "scroll", "blättern", "blättere")) {
            if (!inAppAllowed(ctx, a11y, reply)) return true
            val fwd = !has("hoch", "oben", "rauf")
            reply(if (a11y!!.scroll(fwd)) (if (fwd) "Runter" else "Hoch") else "Nichts zum Scrollen")
            return true
        }
        return false
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

    // ---------- App oeffnen (mit Pruefung und zweitem Versuch) ----------

    private fun openCmd(ctx: Context, name: String, reply: (String) -> Unit, last: Boolean): Boolean {
        if (name.isBlank()) { if (last) reply("Welche App soll ich öffnen?"); return last }
        val app = AppIndex.find(AppIndex.load(ctx), name)
        if (app == null) { if (last) reply("App nicht gefunden: $name"); return last }
        if (app.pkg in Prefs.disabled(ctx)) { reply("${app.label} ist gesperrt"); return true }
        val a11y = VoiceAccessibilityService.instance
        fun launch(): Boolean {
            val i = ctx.packageManager.getLaunchIntentForPackage(app.pkg) ?: return false
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            return try { (a11y ?: ctx).startActivity(i); true } catch (e: Exception) { false }
        }
        if (!launch()) { reply("${app.label} lässt sich nicht öffnen"); return true }
        if (a11y == null) { reply("${app.label} geöffnet"); return true }
        // nach 1,5 s pruefen, ob die App wirklich vorne ist; sonst nochmal
        main.postDelayed({
            if (a11y.topPkg() == app.pkg) { reply("${app.label} geöffnet"); return@postDelayed }
            launch()
            main.postDelayed({
                reply(if (a11y.topPkg() == app.pkg) "${app.label} geöffnet" else "${app.label} reagiert nicht")
            }, 2200)
        }, 1500)
        return true
    }

    // ---------- App schliessen ----------

    private fun closeCmd(ctx: Context, name: String, reply: (String) -> Unit, last: Boolean): Boolean {
        val a11y = VoiceAccessibilityService.instance
        if (a11y == null) { reply(NO_A11Y); return true }

        if (name in ALL) {
            a11y.closeAll { ok -> reply(if (ok) "Alle Apps geschlossen" else "Alle schließen nicht gefunden") }
            return true
        }

        val pkg: String
        if (name in CURRENT) {
            pkg = a11y.currentPkg() ?: run { reply("Keine App offen"); return true }
        } else {
            val app = AppIndex.find(AppIndex.load(ctx), name)
            if (app == null) { if (last) reply("App nicht gefunden: $name"); return last }
            pkg = app.pkg
        }
        val lbl = label(ctx, pkg)
        if (pkg in NEVER || pkg == ctx.packageName) { reply("$lbl kann nicht geschlossen werden"); return true }
        if (pkg in Prefs.disabled(ctx)) { reply("$lbl ist gesperrt"); return true }

        try { (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).killBackgroundProcesses(pkg) } catch (_: Exception) {}
        // bis zu zwei Versuche, falls die Einstellungen-Seite zu langsam war
        a11y.forceStop(pkg) { ok1 ->
            if (ok1) reply("$lbl geschlossen")
            else a11y.forceStop(pkg) { ok2 -> reply(if (ok2) "$lbl geschlossen" else "$lbl ließ sich nicht schließen, Diagnose steht in der App") }
        }
        return true
    }
}
