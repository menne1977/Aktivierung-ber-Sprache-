package com.dtm.voice

import android.content.Context
import org.vosk.Model
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/** Kleines deutsches Offline-Sprachmodell (ca. 45 MB), einmalig geladen. Danach braucht das Aktivierungswort kein Internet mehr. */
object ModelManager {
    private const val URL_ZIP = "https://alphacephei.com/vosk/models/vosk-model-small-de-0.15.zip"
    private const val DIR = "vosk-model-small-de-0.15"

    @Volatile var downloading = false
    @Volatile private var model: Model? = null

    private fun dir(c: Context) = File(File(c.filesDir, "vosk"), DIR)

    fun ready(c: Context) = File(dir(c), "am").isDirectory

    @Synchronized
    fun get(c: Context): Model? {
        if (model == null && ready(c)) model = Model(dir(c).absolutePath)
        return model
    }

    fun download(c: Context, progress: (String) -> Unit, done: (Boolean) -> Unit) {
        if (downloading) return
        downloading = true
        Thread {
            var ok = false
            try {
                val root = File(c.filesDir, "vosk")
                root.mkdirs()
                val conn = URL(URL_ZIP).openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 30000
                val total = conn.contentLength.toLong()
                var read = 0L
                var lastPct = -1
                ZipInputStream(conn.inputStream.buffered()).use { zin ->
                    var e = zin.nextEntry
                    val buf = ByteArray(64 * 1024)
                    while (e != null) {
                        val out = File(root, e.name)
                        if (!out.canonicalPath.startsWith(root.canonicalPath)) throw SecurityException("zip")
                        if (e.isDirectory) out.mkdirs() else {
                            out.parentFile?.mkdirs()
                            FileOutputStream(out).use { fo ->
                                while (true) {
                                    val n = zin.read(buf)
                                    if (n < 0) break
                                    fo.write(buf, 0, n)
                                    read += n
                                    if (total > 0) {
                                        val pct = (read * 100 / total).toInt().coerceAtMost(99)
                                        if (pct != lastPct) { lastPct = pct; progress("Sprachmodell wird geladen: $pct %") }
                                    }
                                }
                            }
                        }
                        e = zin.nextEntry
                    }
                }
                ok = ready(c)
            } catch (_: Exception) {
                ok = false
            }
            downloading = false
            done(ok)
        }.start()
    }
}
