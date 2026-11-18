package com.carlos.bloqueador

import android.accessibilityservice.AccessibilityService
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
    private var lastToast = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (!BlockStore.isActive(this)) return
        val pkg = event.packageName?.toString() ?: return

        // Apps elegidas, y Bloqueador mismo
        if (pkg == packageName || BlockStore.isBlocked(this, pkg)) {
            goHome()
            return
        }
        // Pantallas de sistema que muestren "Bloqueador"
        if (guardedPrefixes.any { pkg.startsWith(it) } && screenMentionsKeyword()) {
            goHome()
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

    private fun goHome() {
        performGlobalAction(GLOBAL_ACTION_HOME)
        val now = System.currentTimeMillis()
        if (now - lastToast > 3000) {
            lastToast = now
            Toast.makeText(
                this,
                "Bloqueado. Quedan ${BlockStore.remainingMinutes(this)} min.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onInterrupt() {}
}
