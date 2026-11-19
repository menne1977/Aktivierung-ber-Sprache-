package com.dtm.voice

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {

    private val BG = Color.parseColor("#101820")
    private val FG = Color.WHITE
    private val DIM = Color.parseColor("#9AA5B1")

    private lateinit var adapter: AppAdapter
    private lateinit var statusView: TextView
    private lateinit var startStop: Button
    private var modelMsg = ""

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val lv = ListView(this)
        lv.setBackgroundColor(BG)
        lv.divider = null
        lv.addHeaderView(buildHeader(), null, false)
        adapter = AppAdapter()
        lv.adapter = adapter
        setContentView(lv)
        Thread {
            val apps = AppIndex.load(this)
            runOnUiThread { adapter.setData(apps) }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    // ---------- Kopfbereich ----------

    private fun text(s: String, sp: Float, color: Int = FG): TextView =
        TextView(this).apply {
            text = s
            textSize = sp
            setTextColor(color)
        }

    private fun button(s: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = s
            isAllCaps = false
            setOnClickListener { onClick() }
        }

    private fun buildHeader(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(32), dp(16), dp(8))
        }

        box.addView(text("DTM Voice", 28f))
        box.addView(text("Apps per Sprache öffnen, schließen und bedienen", 14f, DIM))

        startStop = button("Sprachsteuerung starten") { toggleService() }
        box.addView(startStop)

        statusView = text("", 14f)
        statusView.setPadding(0, dp(8), 0, dp(8))
        box.addView(statusView)

        box.addView(button("1. Berechtigungen erteilen") { askRuntimePermissions() })
        box.addView(button("2. Bedienungshilfe aktivieren") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        box.addView(button("3. Über anderen Apps einblenden erlauben") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })

        // Aktivierungswort
        box.addView(text("Aktivierungswort", 16f).apply { setPadding(0, dp(16), 0, 0) })
        val wake = EditText(this).apply {
            setText(Prefs.wakeWord(this@MainActivity))
            setTextColor(FG)
            setSingleLine()
        }
        box.addView(wake)
        box.addView(button("Aktivierungswort speichern") {
            val w = wake.text.toString().trim()
            if (w.isNotEmpty()) {
                Prefs.setWakeWord(this, w)
                if (VoiceService.running) {
                    stopService(Intent(this, VoiceService::class.java))
                    window.decorView.postDelayed({ startServiceNow() }, 500)
                }
            }
        })

        // Schalter
        box.addView(Switch(this).apply {
            text = "Antworten per Sprache (Sprachausgabe)"
            setTextColor(FG)
            isChecked = Prefs.speak(this@MainActivity)
            setOnCheckedChangeListener { _, v -> Prefs.setSpeak(this@MainActivity, v) }
            setPadding(0, dp(8), 0, dp(8))
        })
        box.addView(Switch(this).apply {
            text = "Helm-Mikrofon nutzen (Sena / Bluetooth)"
            setTextColor(FG)
            isChecked = Prefs.bluetooth(this@MainActivity)
            setOnCheckedChangeListener { _, v -> Prefs.setBluetooth(this@MainActivity, v) }
            setPadding(0, dp(8), 0, dp(8))
        })

        // App-Liste
        box.addView(text("Apps", 20f).apply { setPadding(0, dp(16), 0, 0) })
        box.addView(text("AN = darf per Sprache geöffnet und geschlossen werden. AUS = geschützt.", 13f, DIM))

        val filter = EditText(this).apply {
            hint = "App suchen"
            setHintTextColor(DIM)
            setTextColor(FG)
            setSingleLine()
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { adapter.setFilter(s?.toString() ?: "") }
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            })
        }
        box.addView(filter)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button("Alle an") { adapter.setAll(true) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(button("Alle aus") { adapter.setAll(false) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(row)

        return box
    }

    // ---------- Berechtigungen / Dienst ----------

    private fun micGranted() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun a11yEnabled(): Boolean {
        val s = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return s.contains(packageName)
    }

    private fun refreshStatus() {
        fun ok(b: Boolean) = if (b) "✓" else "✗"
        statusView.text = "Mikrofon ${ok(micGranted())}    Bedienungshilfe ${ok(a11yEnabled())}    Überlagerung ${ok(Settings.canDrawOverlays(this))}"
        val model = if (ModelManager.ready(this)) "Sprachmodell ✓" else if (ModelManager.downloading) modelMsg else "Sprachmodell ✗ (wird beim Start eingerichtet)"
        statusView.text = statusView.text.toString() + "\n" + model
        startStop.text = if (VoiceService.running) "Sprachsteuerung stoppen" else "Sprachsteuerung starten"
    }

    private fun askRuntimePermissions() {
        val p = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) p.add("android.permission.POST_NOTIFICATIONS")
        if (Build.VERSION.SDK_INT >= 31) p.add(Manifest.permission.BLUETOOTH_CONNECT)
        requestPermissions(p.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshStatus()
    }

    private fun startServiceNow() {
        startForegroundService(Intent(this, VoiceService::class.java))
        window.decorView.postDelayed({ refreshStatus() }, 400)
    }

    private fun toggleService() {
        if (VoiceService.running) {
            stopService(Intent(this, VoiceService::class.java))
            window.decorView.postDelayed({ refreshStatus() }, 400)
        } else if (!micGranted()) {
            askRuntimePermissions()
        } else if (!ModelManager.ready(this)) {
            if (!ModelManager.downloading) {
                modelMsg = "Sprachmodell wird eingerichtet …"
                refreshStatus()
                ModelManager.download(this, { m -> runOnUiThread { modelMsg = m; refreshStatus() } }) { ok ->
                    runOnUiThread {
                        refreshStatus()
                        if (ok) startServiceNow() else statusView.text = "Sprachmodell konnte nicht eingerichtet werden. Speicherplatz prüfen und erneut starten."
                    }
                }
            }
        } else {
            startServiceNow()
        }
    }

    // ---------- App-Liste mit Schiebeschaltern ----------

    private inner class AppAdapter : BaseAdapter() {
        private var all: List<AppInfo> = emptyList()
        private var shown: List<AppInfo> = emptyList()
        private var filter = ""
        private val disabled = HashSet(Prefs.disabled(this@MainActivity))
        private val icons = HashMap<String, Drawable?>()

        fun setData(apps: List<AppInfo>) { all = apps; applyFilter() }
        fun setFilter(f: String) { filter = AppIndex.norm(f); applyFilter() }

        private fun applyFilter() {
            shown = if (filter.isEmpty()) all else all.filter { AppIndex.norm(it.label).contains(filter) }
            notifyDataSetChanged()
        }

        fun setAll(on: Boolean) {
            for (a in shown) if (on) disabled.remove(a.pkg) else disabled.add(a.pkg)
            Prefs.setDisabled(this@MainActivity, disabled)
            notifyDataSetChanged()
        }

        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val app = shown[position]
            val row: LinearLayout
            if (convertView is LinearLayout) {
                row = convertView
            } else {
                row = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(16), dp(6), dp(16), dp(6))
                }
                row.addView(ImageView(this@MainActivity), LinearLayout.LayoutParams(dp(40), dp(40)))
                row.addView(
                    TextView(this@MainActivity).apply { setTextColor(FG); textSize = 16f; setPadding(dp(12), 0, dp(8), 0) },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                )
                row.addView(Switch(this@MainActivity))
            }
            val icon = row.getChildAt(0) as ImageView
            val label = row.getChildAt(1) as TextView
            val sw = row.getChildAt(2) as Switch

            label.text = app.label
            icon.setImageDrawable(icons.getOrPut(app.pkg) {
                try { packageManager.getApplicationIcon(app.pkg) } catch (e: Exception) { null }
            })
            sw.setOnCheckedChangeListener(null)
            sw.isChecked = app.pkg !in disabled
            sw.setOnCheckedChangeListener { _, on ->
                if (on) disabled.remove(app.pkg) else disabled.add(app.pkg)
                Prefs.setDisabled(this@MainActivity, disabled)
            }
            row.setOnClickListener {
                packageManager.getLaunchIntentForPackage(app.pkg)?.let { startActivity(it) }
            }
            return row
        }
    }
}
