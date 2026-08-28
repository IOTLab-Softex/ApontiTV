package br.com.softextv.player

import android.content.Context

object ApontiForegroundState {
    private const val PREFS_NAME = "aponti_foreground_state"
    private const val KEY_FOREGROUND = "foreground"
    private const val KEY_KEEP_OPEN_ENABLED = "keep_open_enabled"
    private const val KEY_LAST_FOREGROUND_AT_MS = "last_foreground_at_ms"
    private const val KEY_LAST_BACKGROUND_AT_MS = "last_background_at_ms"
    private const val BACKGROUND_GRACE_MS = 4_000L

    fun markForeground(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_FOREGROUND, true)
            .putLong(KEY_LAST_FOREGROUND_AT_MS, System.currentTimeMillis())
            .apply()
    }

    fun markBackground(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_FOREGROUND, false)
            .putLong(KEY_LAST_BACKGROUND_AT_MS, System.currentTimeMillis())
            .apply()
    }

    fun setKeepOpenEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_KEEP_OPEN_ENABLED, enabled)
            .apply()
    }

    fun shouldRelaunch(context: Context): Boolean {
        if (!homeGuardEnabled(context)) return false

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_FOREGROUND, false)) return false

        val backgroundAt = prefs.getLong(KEY_LAST_BACKGROUND_AT_MS, 0L)
        return backgroundAt == 0L || System.currentTimeMillis() - backgroundAt >= BACKGROUND_GRACE_MS
    }

    fun homeGuardEnabled(context: Context): Boolean {
        return DevicePlatform.isFireTv() || keepOpenEnabled(context)
    }

    private fun keepOpenEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_KEEP_OPEN_ENABLED, false)
    }
}
