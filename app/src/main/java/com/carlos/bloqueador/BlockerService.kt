package com.carlos.bloqueador

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast

class BlockerService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return

        if (BlockStore.isBlocked(this, pkg)) {
            performGlobalAction(GLOBAL_ACTION_HOME)
            Toast.makeText(
                this,
                "Bloqueada. Quedan ${BlockStore.remainingMinutes(this)} min.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onInterrupt() {}
}
