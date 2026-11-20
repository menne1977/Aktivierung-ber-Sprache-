package com.dtm.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import org.json.JSONObject
import org.vosk.Recognizer
import org.vosk.android.SpeechService
import java.util.Locale

/**
 * Phase 1: Offline-Erkennung (Vosk) laeuft dauerhaft und reagiert NUR auf das Aktivierungswort.
 *          Alles andere wird verworfen. Kein Neustart, kein Mikro-An/Aus.
 * Phase 2: Nach dem Aktivierungswort Ton, dann genau EIN Befehl
 *          (Google-Erkennung, falls vorhanden – sonst Offline-Erkennung). Danach zurueck zu Phase 1.
 */
class VoiceService : Service(), TextToSpeech.OnInitListener {

    companion object {
        @Volatile var running = false
        @Volatile var lastHeard = ""
        @Volatile var lastReply = ""
        private const val CHANNEL = "dtm_voice"
        private const val NOTIF_ID = 1
        private const val ACTION_STOP = "com.dtm.voice.STOP"
        private const val COMMAND_TIMEOUT_MS = 9000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val cmdToken = Any()
    private lateinit var audio: AudioManager
    private var wake: SpeechService? = null
    private var google: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var speaking = false
    private var btActive = false
    private var tone: ToneGenerator? = null
    private var busy = false          // Phase 2 laeuft (Google)
    private var voskCommand = false   // Phase 2 laeuft (offline)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        audio = getSystemService(AUDIO_SERVICE) as AudioManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (running) return START_STICKY

        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Sprachsteuerung", NotificationManager.IMPORTANCE_LOW))
        val n = buildNotification("Nur das Aktivierungswort wird ausgewertet")
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(NOTIF_ID, n)

        if (!ModelManager.ready(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        running = true

        if (Prefs.bluetooth(this)) startBluetooth()
        tone = try {
            ToneGenerator(if (btActive) AudioManager.STREAM_VOICE_CALL else AudioManager.STREAM_MUSIC, 90)
        } catch (e: Exception) { null }
        tts = TextToSpeech(this, this)
        startWake()
        return START_STICKY
    }

    private fun buildNotification(text: String): Notification {
        val stopPi = PendingIntent.getService(
            this, 0, Intent(this, VoiceService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openPi = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("DTM Voice wartet auf „${Prefs.wakeWord(this)}“")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openPi)
            .addAction(Notification.Action.Builder(null, "Stopp", stopPi).build())
            .setOngoing(true)
            .build()
    }

    private fun updateNotification() {
        if (!running) return
        val t = if (lastHeard.isEmpty()) "Nur das Aktivierungswort wird ausgewertet" else "Gehört: $lastHeard → $lastReply"
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, buildNotification(t))
    }

    // ---------- Sprachausgabe ----------

    override fun onInit(status: Int) {
        val t = tts ?: return
        if (status != TextToSpeech.SUCCESS) return
        t.language = Locale.GERMANY
        t.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(if (btActive) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { handler.post { speakingDone() } }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { handler.post { speakingDone() } }
        })
        ttsReady = true
    }

    private fun speakingDone() {
        if (!speaking) return
        speaking = false
        startWake()
    }

    /** Antwort sprechen. Waehrenddessen ist das Mikro aus, damit sich die App nicht selbst hoert. */
    private fun say(text: String) {
        lastReply = text
        updateNotification()
        if (!running) return
        if (Prefs.speak(this) && ttsReady) {
            stopWake()
            speaking = true
            val r = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "r") ?: TextToSpeech.ERROR
            if (r != TextToSpeech.SUCCESS) { speaking = false; startWake(); return }
            handler.postDelayed({ speakingDone() }, 10000)   // Sicherheitsnetz
        } else {
            startWake()
        }
    }

    private fun ping() {
        try { tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 180) } catch (_: Exception) {}
    }

    // ---------- Bluetooth (Sena) ----------

    private fun startBluetooth() {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                val d = audio.availableCommunicationDevices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
                }
                if (d != null) {
                    audio.mode = AudioManager.MODE_IN_COMMUNICATION
                    btActive = audio.setCommunicationDevice(d)
                    if (!btActive) audio.mode = AudioManager.MODE_NORMAL
                }
            } else if (audio.isBluetoothScoAvailableOffCall) {
                audio.mode = AudioManager.MODE_IN_COMMUNICATION
                audio.startBluetoothSco()
                audio.isBluetoothScoOn = true
                btActive = true
            }
        } catch (_: Exception) {
            btActive = false
        }
    }

    private fun stopBluetooth() {
        try {
            if (Build.VERSION.SDK_INT >= 31) audio.clearCommunicationDevice()
            else { audio.stopBluetoothSco(); audio.isBluetoothScoOn = false }
            audio.mode = AudioManager.MODE_NORMAL
        } catch (_: Exception) {}
    }

    // ---------- Phase 1: offline, dauerhaft ----------

    private fun startWake() {
        if (!running || busy || speaking || wake != null) return
        val model = ModelManager.get(this) ?: run { handler.postDelayed({ startWake() }, 3000); return }
        try {
            // Ohne feste Wortliste: so wird auch ein Wort erkannt, das das Modell nicht kennt (z. B. "Titan")
            val rec = Recognizer(model, 16000f)
            val s = SpeechService(rec, 16000f)
            wake = s
            s.startListening(object : org.vosk.android.RecognitionListener {
                override fun onPartialResult(hypothesis: String?) {
                    if (voskCommand) return
                    val t = field(hypothesis, "partial")
                    if (t.isNotBlank() && Wake.matches(this@VoiceService, t)) onWake()
                }
                override fun onResult(hypothesis: String?) {
                    val t = field(hypothesis, "text")
                    if (t.isBlank()) return
                    if (voskCommand) {
                        val cmd = Wake.rest(this@VoiceService, t)
                        if (cmd.isBlank()) return
                        voskCommand = false
                        handler.removeCallbacksAndMessages(cmdToken)
                        execute(cmd)
                    } else if (Wake.matches(this@VoiceService, t)) {
                        onWake()
                    }
                    // alles andere wird ignoriert
                }
                override fun onFinalResult(hypothesis: String?) {}
                override fun onError(exception: Exception?) {
                    handler.post { stopWake(); handler.postDelayed({ startWake() }, 2000) }
                }
                override fun onTimeout() {}
            })
        } catch (e: Exception) {
            wake = null
            handler.postDelayed({ startWake() }, 3000)
        }
    }

    private fun stopWake() {
        val s = wake ?: return
        wake = null
        try { s.stop() } catch (_: Exception) {}
        try { s.shutdown() } catch (_: Exception) {}
    }

    private fun field(json: String?, key: String): String =
        try { JSONObject(json ?: "").optString(key) } catch (_: Exception) { "" }

    // ---------- Phase 2: genau ein Befehl ----------

    private fun onWake() {
        if (busy || voskCommand || !running) return
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            busy = true
            stopWake()
            ping()
            handler.postDelayed({ listenGoogle(true) }, 300)
        } else {
            // keine Google-Erkennung: Offline-Modell hoert einfach weiter auf den Befehl
            voskCommand = true
            ping()
            handler.postAtTime({ voskCommand = false }, cmdToken, SystemClock.uptimeMillis() + COMMAND_TIMEOUT_MS)
        }
    }

    private fun execute(text: String) {
        lastHeard = text
        Commander.run(this, text) { r -> handler.post { say(r) } }
    }

    private fun endGoogle() {
        handler.removeCallbacksAndMessages(cmdToken)
        try { google?.destroy() } catch (_: Exception) {}
        google = null
        busy = false
    }

    private fun listenGoogle(offline: Boolean) {
        if (!running) return
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        google = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                endGoogle()
                if (text.isNullOrBlank()) startWake() else execute(text)
            }
            override fun onError(error: Int) {
                endGoogle()
                // 12/13 = deutsches Offline-Paket fehlt -> einmal online versuchen
                if (offline && (error == 12 || error == 13)) {
                    busy = true
                    listenGoogle(false)
                    return
                }
                if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    lastHeard = "(nichts verstanden)"; lastReply = ""; updateNotification()
                }
                startWake()
            }
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "de-DE")
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, offline)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }
        try { r.startListening(i) } catch (_: Exception) { endGoogle(); startWake(); return }
        handler.postAtTime({ endGoogle(); startWake() }, cmdToken, SystemClock.uptimeMillis() + COMMAND_TIMEOUT_MS)
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        stopWake()
        try { google?.destroy() } catch (_: Exception) {}
        tts?.shutdown()
        tone?.release()
        stopBluetooth()
        super.onDestroy()
    }
}
