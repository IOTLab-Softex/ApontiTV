package br.com.softextv.player

import android.content.Context
import java.util.UUID

object DeviceSession {
    private const val PREFS_NAME = "softextv_device_session"
    private const val KEY_DEVICE_TOKEN = "device_token"

    fun deviceToken(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_DEVICE_TOKEN, null)
        if (!existing.isNullOrBlank()) return existing

        val created = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_DEVICE_TOKEN, created).apply()
        return created
    }
}
