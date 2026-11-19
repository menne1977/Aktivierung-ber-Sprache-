package com.dtm.voice

import android.content.Context
import org.vosk.Model
import java.io.File

/** Deutsches Offline-Sprachmodell steckt in der APK und wird beim ersten Start einmal entpackt. Kein Internet noetig. */
object ModelManager {
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

    private fun copy(c: Context, asset: String, target: File) {
        val list = c.assets.list(asset)
        if (list != null && list.isNotEmpty()) {
            target.mkdirs()
            for (n in list) copy(c, "$asset/$n", File(target, n))
        } else {
            target.parentFile?.mkdirs()
            c.assets.open(asset).use { i -> target.outputStream().use { o -> i.copyTo(o) } }
        }
    }

    /** Name bleibt "download" fuer MainActivity; entpackt nur die mitgelieferten Dateien. */
    fun download(c: Context, progress: (String) -> Unit, done: (Boolean) -> Unit) {
        if (downloading) return
        downloading = true
        Thread {
            var ok = false
            try {
                progress("Sprachmodell wird eingerichtet (einmalig, ca. 1 Minute) …")
                val root = File(c.filesDir, "vosk")
                val tmp = File(root, "tmp")
                tmp.deleteRecursively()
                copy(c, DIR, tmp)
                dir(c).deleteRecursively()
                ok = tmp.renameTo(dir(c)) && ready(c)
            } catch (_: Exception) {
                ok = false
            }
            downloading = false
            done(ok)
        }.start()
    }
}
