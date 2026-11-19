package com.carlos.bloqueador

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

class BlockerService : AccessibilityService() {

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
        listOf("notificaciones de la aplicación", "por ubicación")
    )

    private val spaces = Regex("\\s+")
    private val handler = Handler(Looper.getMainLooper())
    private var lastToast = 0L
    private var currentPkg: String? = null
    private var lastTick = 0L
    private var lastScan = 0L
    private var lastScanPkg: String? = null
    private var lastScanResult = false

    // Cada segundo: revisa qué app está en pantalla, aunque no llegue ningún aviso
    private val tick = object : Runnable {
        override fun run() {
            try {
                val pm = getSystemService(POWER_SERVICE) as PowerManager
                if (pm.isInteractive) {
                    val pkg = rootInActiveWindow?.packageName?.toString()
                    if (pkg != null) handlePackage(pkg)
                }
                accumulate()
            } catch (e: Exception) {
                // si algo falla, el ciclo sigue
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
        val pkg = event?.packageName?.toString() ?: return
        handlePackage(pkg)
    }

    private fun isIgnored(pkg: String): Boolean =
        pkg.startsWith("com.android.systemui") ||
                pkg.contains("inputmethod") || pkg.contains("keyboard")

    private fun handlePackage(pkg: String) {
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
        if (guardedPrefixes.any { pkg.startsWith(it) } && screenIsGuarded(pkg)) {
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
        if (pkg == lastScanPkg && now - lastScan < 300) return lastScanResult
        val root = rootInActiveWindow ?: return false
        val sb = StringBuilder()
        collect(root, sb, 0)
        val text = spaces.replace(sb.toString().lowercase(), " ")
        val result = guardedGroups.any { group -> group.all { text.contains(it) } }
        lastScan = now
        lastScanPkg = pkg
        lastScanResult = result
        return result
    }

    private fun collect(node: AccessibilityNodeInfo?, sb: StringBuilder, depth: Int) {
        if (node == null || depth > 40 || sb.length > 20000) return
        node.text?.let { sb.append(it).append(' ') }
        node.contentDescription?.let { sb.append(it).append(' ') }
        for (i in 0 until node.childCount) {
            collect(node.getChild(i), sb, depth + 1)
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

    override fun onInterrupt() {}
}
