package com.carlos.bloqueador

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

class LimitsActivity : Activity() {

    private lateinit var apps: List<Pair<String, String>>
    private lateinit var list: ListView

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
        root.addView(TextView(this).apply {
            text = "Límite diario: toca una app y pon los minutos por día (vacío o 0 = quitar)"
            textSize = 16f
        })
        list = ListView(this)
        list.setOnItemClickListener { _, _, position, _ -> askLimit(apps[position]) }
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val labels = apps.map { (pkg, name) ->
            val lim = LimitStore.limitMinutes(this, pkg)
            if (lim == null) {
                name
            } else {
                val used = LimitStore.usedMs(this, pkg) / 60_000L
                "$name — $lim min/día (usados: $used)"
            }
        }
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
    }

    private fun askLimit(app: Pair<String, String>) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "Minutos por día"
            LimitStore.limitMinutes(this@LimitsActivity, app.first)
                ?.let { setText(it.toString()) }
        }
        AlertDialog.Builder(this)
            .setTitle(app.second)
            .setView(input)
            .setPositiveButton("Guardar") { _, _ ->
                LimitStore.setLimit(this, app.first, input.text.toString().toIntOrNull())
                refresh()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
}
