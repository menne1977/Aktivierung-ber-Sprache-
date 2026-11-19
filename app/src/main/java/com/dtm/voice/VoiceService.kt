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
import java.util.Locale

/**
 * Hoert dauerhaft zu. Erst das Aktivierungswort, dann der Befehl
 * (im selben Satz oder innerhalb von 8 Sekunden danach).
 */
class VoiceService : Service(), RecognitionListener, TextToSpeech.OnInitListener {

    companion object {
        @Volatile var running = false
        private const val CHANNEL = "dtm_voice"
        private const val NOTIF_ID = 1
        private const val ACTION_STOP = "com.dtm.voice.STOP"
        private const val AWAKE_MS = 8000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private lateinit var audio: AudioManager
    private var awakeUntil = 0L
    private var btActive = false
    private var tone: ToneGenerator? = null

    private val restartRun = Runnable { startListening() }

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
        running = true

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
            .setContentTitle("DTM Voice hört zu")
            .setContentText("Aktivierungswort: ${Prefs.wakeWord(this)}")
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

        if (Prefs.bluetooth(this)) startBluetooth()
        tone = try {
            ToneGenerator(if (btActive) AudioManager.STREAM_VOICE_CALL else AudioManager.STREAM_MUSIC, 80)
        } catch (e: Exception) { null }
        tts = TextToSpeech(this, this)
        startListening()
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
        ttsReady = true
    }

    private fun say(text: String) {
        if (Prefs.speak(this) && ttsReady) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "r")
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

    // ---------- Spracherkennung ----------

    private fun startListening() {
        if (!running) return
        recognizer?.destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        r.setRecognitionListener(this)
        recognizer = r
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "de-DE")
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }
        try { r.startListening(i) } catch (_: Exception) { restart(1500) }
    }

    private fun restart(delay: Long) {
        handler.removeCallbacks(restartRun)
        if (running) handler.postDelayed(restartRun, delay)
    }

    override fun onResults(results: Bundle?) {
        val c = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        if (!c.isNullOrEmpty()) handle(c)
        restart(150)
    }

    override fun onError(error: Int) {
        if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) { stopSelf(); return }
        restart(if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) 200 else 1200)
    }

    private fun handle(cands: List<String>) {
        val now = SystemClock.elapsedRealtime()
        for (c in cands) {
            val rest = splitWake(c)
            if (rest != null) {
                if (rest.isBlank()) {
                    awakeUntil = now + AWAKE_MS
                    ping()
                } else {
                    awakeUntil = 0
                    Commander.run(this, rest) { say(it) }
                }
                return
            }
        }
        if (now < awakeUntil) {
            awakeUntil = 0
            Commander.run(this, cands[0]) { say(it) }
        }
    }

    /** Liefert den Text nach dem Aktivierungswort, oder null wenn es nicht vorkommt. */
    private fun splitWake(text: String): String? {
        val core = AppIndex.norm(Prefs.wakeWord(this)).split(" ").lastOrNull { it.isNotEmpty() } ?: return null
        val words = AppIndex.norm(text).split(" ").filter { it.isNotEmpty() }
        for ((idx, w) in words.withIndex()) {
            if (AppIndex.similarity(w, core) >= 0.75) {
                return words.drop(idx + 1).joinToString(" ")
            }
        }
        return null
    }

    override fun onReadyForSpeech(params: Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onPartialResults(partialResults: Bundle?) {}
    override fun onEvent(eventType: Int, params: Bundle?) {}

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        recognizer = null
        tts?.shutdown()
        tone?.release()
        stopBluetooth()
        super.onDestroy()
    }
}
