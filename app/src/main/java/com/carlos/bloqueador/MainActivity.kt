package com.carlos.bloqueador

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var apps: List<Pair<String, String>>
    private lateinit var list: ListView
    private lateinit var minutes: EditText
    private lateinit var status: TextView
    private lateinit var startBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        apps = packageManager.queryIntentActivities(launcher, 0)
            .map { it.activityInfo.packageName to it.loadLabel(packageManager).toString() }
            .filter { it.first != packageName }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }

        status = TextView(this).apply { textSize = 16f }
        root.addView(status)

        list = ListView(this).apply {
            choiceMode = ListView.CHOICE_MODE_MULTIPLE
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_list_item_multiple_choice,
                apps.map { it.second }
            )
        }
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))

        minutes = EditText(this).apply {
            hint = "Minutos de bloqueo"
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        root.addView(minutes)

        startBtn = Button(this).apply {
            text = "Iniciar bloqueo"
            setOnClickListener { startBlock() }
        }
        root.addView(startBtn)

        root.addView(Button(this).apply {
            text = "1) Activar servicio de accesibilidad"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        })

        root.addView(Button(this).apply {
            text = "2) Activar administrador (anti-desinstalación)"
            setOnClickListener {
                val i = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                    .putExtra(
                        DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                        ComponentName(this@MainActivity, AdminReceiver::class.java)
                    )
                    .putExtra(
                        DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        "Evita que Bloqueador se desinstale."
                    )
                startActivity(i)
            }
        })

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun startBlock() {
        if (!isServiceEnabled()) {
            Toast.makeText(this, "Primero activa la accesibilidad", Toast.LENGTH_LONG).show()
            return
        }
        if (!isAdminActive()) {
            Toast.makeText(this, "Primero activa el administrador", Toast.LENGTH_LONG).show()
            return
        }
        val mins = minutes.text.toString().toIntOrNull()
        if (mins == null || mins <= 0) {
            Toast.makeText(this, "Pon los minutos", Toast.LENGTH_SHORT).show()
            return
        }
        val selected = (0 until apps.size)
            .filter { list.isItemChecked(it) }
            .map { apps[it].first }
            .toSet()
        if (selected.isEmpty()) {
            Toast.makeText(this, "Elige al menos una app", Toast.LENGTH_SHORT).show()
            return
        }
        BlockStore.start(this, selected, mins)
        Toast.makeText(this, "Bloqueo iniciado", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun refreshStatus() {
        val active = BlockStore.isActive(this)
        startBtn.isEnabled = !active
        status.text = when {
            active -> "Bloqueo activo: quedan ${BlockStore.remainingMinutes(this)} min"
            !isServiceEnabled() -> "Falta: servicio de accesibilidad"
            !isAdminActive() -> "Falta: administrador de dispositivo"
            else -> "Listo. Elige apps y minutos."
        }
    }

    private fun isAdminActive(): Boolean {
        val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return dpm.isAdminActive(ComponentName(this, AdminReceiver::class.java))
    }

    private fun isServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.contains(packageName) && enabled.contains("BlockerService")
    }
}
