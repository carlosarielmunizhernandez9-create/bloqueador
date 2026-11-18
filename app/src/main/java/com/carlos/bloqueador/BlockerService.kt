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

    // Pantallas de sistema donde puede aparecer "Bloqueador"
    private val guardedPrefixes = listOf(
        "com.android.settings",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.google.android.permissioncontroller",
        "com.android.permissioncontroller",
        "com.coloros", "com.oplus", "com.heytap", "com.oppo"
    )
    private val keywords = listOf("bloqueador")

    private val handler = Handler(Looper.getMainLooper())
    private var lastToast = 0L
    private var currentPkg: String? = null
    private var lastTick = 0L

    // Cada 2 segundos: suma el uso y saca la app si ya llegó al límite
    private val tick = object : Runnable {
        override fun run() {
            accumulate()
            val pkg = currentPkg
            if (pkg != null && LimitStore.isOverLimit(this@BlockerService, pkg)) {
                goHome("Límite diario alcanzado")
            }
            handler.postDelayed(this, 2000)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        lastTick = SystemClock.elapsedRealtime()
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, 2000)
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        super.onDestroy()
    }

    private fun isIgnored(pkg: String): Boolean =
        pkg.startsWith("com.android.systemui") ||
                pkg.contains("inputmethod") || pkg.contains("keyboard")

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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return

        // 1) Seguimiento de la app en pantalla (límites diarios)
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && !isIgnored(pkg)) {
            if (pkg != currentPkg) {
                accumulate()
                currentPkg = pkg
            }
            if (LimitStore.isOverLimit(this, pkg)) {
                goHome("Límite diario alcanzado")
                return
            }
        }

        // 2) Temporizador de bloqueo total
        if (!BlockStore.isActive(this)) return
        if (pkg == packageName || BlockStore.isBlocked(this, pkg)) {
            goHome(null)
            return
        }
        if (guardedPrefixes.any { pkg.startsWith(it) } && screenMentionsKeyword()) {
            goHome(null)
        }
    }

    private fun screenMentionsKeyword(): Boolean {
        val root = rootInActiveWindow ?: return false
        return search(root, 0)
    }

    private fun search(node: AccessibilityNodeInfo?, depth: Int): Boolean {
        if (node == null || depth > 40) return false
        val text = (node.text?.toString() ?: "") + " " +
                (node.contentDescription?.toString() ?: "")
        if (keywords.any { text.contains(it, ignoreCase = true) }) return true
        for (i in 0 until node.childCount) {
            if (search(node.getChild(i), depth + 1)) return true
        }
        return false
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
