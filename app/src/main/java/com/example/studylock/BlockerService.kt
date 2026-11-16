package com.example.studylock

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast

class BlockerService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val ev = event ?: return
        if (ev.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = ev.packageName?.toString() ?: return
        if (pkg !in Focus.BLOCKED) return
        if (!Focus.isLocked(this)) return
        if (pkg == Focus.YOUTUBE && Focus.allowYoutube(this)) return

        performGlobalAction(GLOBAL_ACTION_HOME)
        Toast.makeText(this, "خلّص مذاكرتك الأول 📚", Toast.LENGTH_SHORT).show()
    }

    override fun onInterrupt() {}
}
