package com.carlos.bloqueador

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import java.text.Normalizer

class BlockerService : AccessibilityService() {

    private val spaces = Regex("\\s+")
    private val marks = Regex("\\p{Mn}+")

    // Quita acentos y mayúsculas para comparar textos
    private fun norm(s: String): String =
        spaces.replace(
            marks.replace(Normalizer.normalize(s, Normalizer.Form.NFD), "").lowercase(),
            " "
        )

    // Apps que se cierran por completo mientras corre el temporizador de bloqueo
    private val alwaysBlockedPrefixes = listOf(
        "com.google.android.apps.wellbeing"
    )

    // Paquetes de sistema donde se revisa el texto de la pantalla
    private val guardedPrefixes = listOf(
        "com.android.settings",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.google.android.permissioncontroller",
        "com.android.permissioncontroller",
        "com.coloros", "com.oplus", "com.heytap", "com.oppo"
    )

    // Una pantalla se cierra si contiene TODAS las frases de algún grupo
    private val guardedGroups = listOf(
        listOf("bloqueador"),
        listOf("hora automáticamente"),
        listOf("zona horaria automáticamente"),
        listOf("notificaciones de la aplicación", "por ubicación"),
        // Ajustes adicionales (la página anterior a Fecha y hora)
        listOf("idiomas de la aplicación", "método de entrada"),
        // Pantalla de apps recientes
        listOf("cerrar todo", "gb disponible")
    ).map { group -> group.map { norm(it) } }

    private val handler = Handler(Looper.getMainLooper())
    private var lastToast = 0L
    private var lastError = 0L
    private var currentPkg: String? = null
    private var lastTick = 0L
    private var lastScan = 0L
    private var lastScanPkg: String? = null
    private var lastScanResult = false
    private var nodeBudget = 0

    private val tick = object : Runnable {
        override fun run() {
            try {
                val pm = getSystemService(POWER_SERVICE) as PowerManager
                if (pm.isInteractive) {
                    val pkg = rootInActiveWindow?.packageName?.toString()
                    if (pkg != null) handlePackage(pkg, true)
                }
                accumulate()
            } catch (e: Throwable) {
                reportError(e)
            }
            handler.postDelayed(this, 1000)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        lastTick = SystemClock.elapsedRealtime()
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, 1000)
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            val pkg = event?.packageName?.toString() ?: return
            handlePackage(pkg, event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
        } catch (e: Throwable) {
            reportError(e)
        }
    }

    private fun isIgnored(pkg: String): Boolean =
        pkg.startsWith("com.android.systemui") ||
                pkg.contains("inputmethod") || pkg.contains("keyboard")

    private fun isGuardedPackage(pkg: String): Boolean =
        guardedPrefixes.any { pkg.startsWith(it) } ||
                pkg.contains("settings") || pkg.contains("launcher")

    private fun handlePackage(pkg: String, allowScan: Boolean) {
        if (isIgnored(pkg)) return

        if (pkg != currentPkg) {
            accumulate()
            currentPkg = pkg
        }

        // Límite diario
        if (LimitStore.isOverLimit(this, pkg)) {
            goHome("Límite diario alcanzado")
            return
        }

        // Temporizador de bloqueo total
        if (!BlockStore.isActive(this)) return
        if (pkg == packageName ||
            BlockStore.isBlocked(this, pkg) ||
            alwaysBlockedPrefixes.any { pkg.startsWith(it) }
        ) {
            goHome(null)
            return
        }

        if (allowScan && isGuardedPackage(pkg) && screenIsGuarded(pkg)) {
            goHome(null)
        }
    }

    private fun accumulate() {
        val now = SystemClock.elapsedRealtime()
        val elapsed = now - lastTick
        lastTick = now
        val pkg = currentPkg ?: return
        if (elapsed <= 0L) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isInteractive) return
        if (LimitStore.limitMinutes(this, pkg) == null) return
        LimitStore.addUsage(this, pkg, minOf(elapsed, 5000L))
    }

    private fun screenIsGuarded(pkg: String): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (pkg == lastScanPkg && now - lastScan < 800) return lastScanResult
        val root = rootInActiveWindow ?: return false
        val sb = StringBuilder()
        nodeBudget = 600
        collect(root, sb)
        val text = norm(sb.toString())
        val result = guardedGroups.any { group -> group.all { text.contains(it) } }
        lastScan = now
        lastScanPkg = pkg
        lastScanResult = result
        return result
    }

    private fun collect(node: AccessibilityNodeInfo?, sb: StringBuilder) {
        if (node == null || nodeBudget <= 0) return
        nodeBudget--
        node.text?.let { sb.append(it).append(' ') }
        node.contentDescription?.let { sb.append(it).append(' ') }
        for (i in 0 until node.childCount) {
            collect(node.getChild(i), sb)
        }
    }

    private fun goHome(msg: String?) {
        performGlobalAction(GLOBAL_ACTION_HOME)
        val now = SystemClock.elapsedRealtime()
        if (now - lastToast > 3000) {
            lastToast = now
            val text = msg ?: "Bloqueado. Quedan ${BlockStore.remainingMinutes(this)} min."
            Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
        }
    }

    private fun reportError(e: Throwable) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastError < 10_000L) return
        lastError = now
        Toast.makeText(
            this,
            "Error: ${e.javaClass.simpleName} ${e.message}",
            Toast.LENGTH_LONG
        ).show()
    }

    override fun onInterrupt() {}
}
