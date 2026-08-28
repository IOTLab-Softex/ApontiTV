package br.com.softextv.player

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class ApontiHomeGuardAccessibilityService : AccessibilityService() {

    private var lastLaunchAtMs = 0L
    private val mainHandler = Handler(Looper.getMainLooper())
    private val launcherWatcher = object : Runnable {
        override fun run() {
            try {
                val activePackageName = rootInActiveWindow?.packageName?.toString()
                if (activePackageName != null) {
                    openApontiIfGuardedPackage(activePackageName)
                }
            } catch (error: Exception) {
                Log.w(TAG, "Home guard watcher failed", error)
            } finally {
                mainHandler.postDelayed(this, WATCH_INTERVAL_MS)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOWS_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 100
        }
        mainHandler.removeCallbacks(launcherWatcher)
        mainHandler.post(launcherWatcher)
        Log.i(TAG, "Home guard accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val eventPackageName = event?.packageName?.toString() ?: return
        Log.d(TAG, "Accessibility event package=$eventPackageName type=${event.eventType}")
        openApontiIfGuardedPackage(eventPackageName)
    }

    private fun openApontiIfGuardedPackage(eventPackageName: String) {
        if (eventPackageName == packageName) return
        if (!ApontiForegroundState.homeGuardEnabled(applicationContext)) return
        if (!guardedPackages.contains(eventPackageName)) return

        val now = SystemClock.elapsedRealtime()
        if (now - lastLaunchAtMs < LAUNCH_COOLDOWN_MS) return
        lastLaunchAtMs = now

        val intent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
        Log.i(TAG, "Guarded package detected ($eventPackageName), opening Aponti TV")
    }

    override fun onInterrupt() {
        Log.w(TAG, "Home guard accessibility service interrupted")
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(launcherWatcher)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ApontiHomeGuard"
        private const val AMAZON_LAUNCHER_PACKAGE = "com.amazon.tv.launcher"
        private val guardedPackages = setOf(
            AMAZON_LAUNCHER_PACKAGE,
            "com.amazon.firebat",
            "com.amazon.pyrocore",
            "com.amazon.firehomestarter",
            "com.amazon.tv.launcherx",
            "com.amazon.tv.settings.v2",
            "com.amazon.venezia",
            "com.amazon.device.software.ota",
            "com.google.android.tvlauncher",
            "com.google.android.apps.tv.launcherx",
            "com.google.android.apps.tv.launcher",
            "com.google.android.apps.tv.launcherx.home",
            "com.android.tv.settings",
            "com.android.launcher3"
        )
        private const val WATCH_INTERVAL_MS = 900L
        private const val LAUNCH_COOLDOWN_MS = 1_500L
    }
}
