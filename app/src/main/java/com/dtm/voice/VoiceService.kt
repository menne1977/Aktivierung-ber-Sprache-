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
 * Dauerhaft hoert nur die Offline-Erkennung auf das Aktivierungswort (kein Neustart, kein Piepen).
 * Erst nach dem Aktivierungswort startet einmalig die normale Spracherkennung fuer genau einen Befehl.
 */
class VoiceService : Service(), TextToSpeech.OnInitListener {

    companion object {
        @Volatile var running = false
        private const val CHANNEL = "dtm_voice"
        private const val NOTIF_ID = 1
        private const val ACTION_STOP = "com.dtm.voice.STOP"
        private const val COMMAND_TIMEOUT_MS = 9000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var audio: AudioManager
    private var wake: SpeechService? = null
    private var cmd: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var speaking = false
    private var btActive = false
    private var tone: ToneGenerator? = null
    private var busy = false

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
        val stopPi = PendingIntent.getService(
            this, 0, Intent(this, VoiceService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openPi = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle("DTM Voice wartet auf: ${Prefs.wakeWord(this)}")
            .setContentText("Nur das Aktivierungswort wird ausgewertet")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openPi)
            .addAction(Notification.Action.Builder(null, "Stopp", stopPi).build())
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, n)
        }

        if (!ModelManager.ready(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        running = true

        if (Prefs.bluetooth(this)) startBluetooth()
        tone = try {
            ToneGenerator(if (btActive) AudioManager.STREAM_VOICE_CALL else AudioManager.STREAM_MUSIC, 80)
        } catch (e: Exception) { null }
        tts = TextToSpeech(this, this)
        startWakeListening()
        return START_STICKY
    }

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
            override fun onDone(utteranceId: String?) { handler.post { speaking = false; startWakeListening() } }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { handler.post { speaking = false; startWakeListening() } }
        })
        ttsReady = true
    }

    /** Antwort sprechen; das Zuhoeren pausiert so lange, damit sich die App nicht selbst hoert. */
    private fun say(text: String) {
        if (!running) return
        if (Prefs.speak(this) && ttsReady) {
            stopWakeListening()
            speaking = true
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "r")
        } else {
            startWakeListening()
        }
    }

    private fun ping() {
        try { tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 150) } catch (_: Exception) {}
    }

    // ---------- Bluetooth (Sena) ----------

    private fun startBluetooth() {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                audio.mode = AudioManager.MODE_IN_COMMUNICATION
                val d = audio.availableCommunicationDevices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
                }
                if (d != null) btActive = audio.setCommunicationDevice(d)
                if (!btActive) audio.mode = AudioManager.MODE_NORMAL
            } else {
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

    // ---------- Phase 1: nur Aktivierungswort (offline, Dauerlauf ohne Neustart) ----------

    private fun wakeTokens(): Pair<String, String> {
        val phrase = AppIndex.norm(Prefs.wakeWord(this))
        val core = phrase.split(" ").lastOrNull { it.isNotEmpty() } ?: phrase
        return phrase to core
    }

    private fun startWakeListening() {
        if (!running || busy || speaking || wake != null) return
        val model = ModelManager.get(this) ?: return
        val (phrase, core) = wakeTokens()
        val grammar = org.json.JSONArray(listOf(phrase, core, "[unk]")).toString()
        try {
            val rec = Recognizer(model, 16000f, grammar)
            val s = SpeechService(rec, 16000f)
            wake = s
            s.startListening(object : org.vosk.android.RecognitionListener {
                override fun onPartialResult(hypothesis: String?) {}
                override fun onResult(hypothesis: String?) { check(hypothesis) }
                override fun onFinalResult(hypothesis: String?) {}
                override fun onError(exception: Exception?) { handler.post { stopWakeListening(); handler.postDelayed({ startWakeListening() }, 2000) } }
                override fun onTimeout() {}
            })
        } catch (e: Exception) {
            wake = null
            handler.postDelayed({ startWakeListening() }, 3000)
        }
    }

    private fun stopWakeListening() {
        val s = wake ?: return
        wake = null
        try { s.stop() } catch (_: Exception) {}
        try { s.shutdown() } catch (_: Exception) {}
    }

    private fun check(json: String?) {
        val text = try { JSONObject(json ?: return).optString("text") } catch (_: Exception) { return }
        if (text.isBlank()) return
        val (phrase, core) = wakeTokens()
        val words = AppIndex.norm(text).split(" ")
        if (core in words || AppIndex.norm(text).contains(phrase)) {
            handler.post { onWakeDetected() }
        }
    }

    // ---------- Phase 2: ein Befehl, danach zurueck zu Phase 1 ----------

    private fun onWakeDetected() {
        if (busy || !running) return
        busy = true
        stopWakeListening()
        ping()
        handler.postDelayed({ listenForCommand() }, 250)
    }

    private fun finishCommand() {
        handler.removeCallbacksAndMessages("cmdTimeout")
        try { cmd?.destroy() } catch (_: Exception) {}
        cmd = null
        busy = false
        startWakeListening()
    }

    private fun listenForCommand() {
        if (!running) return
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        cmd = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val c = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = c?.firstOrNull()
                try { cmd?.destroy() } catch (_: Exception) {}
                cmd = null
                busy = false
                handler.removeCallbacksAndMessages("cmdTimeout")
                if (text.isNullOrBlank()) startWakeListening()
                else Commander.run(this@VoiceService, text) { say(it) }
            }
            override fun onError(error: Int) { finishCommand() }
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
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }
        try { r.startListening(i) } catch (_: Exception) { finishCommand(); return }
        handler.postAtTime({ finishCommand() }, "cmdTimeout", android.os.SystemClock.uptimeMillis() + COMMAND_TIMEOUT_MS)
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        stopWakeListening()
        try { cmd?.destroy() } catch (_: Exception) {}
        tts?.shutdown()
        tone?.release()
        stopBluetooth()
        super.onDestroy()
    }
}
