package br.com.softextv.player

import android.content.Context

class ChannelListCache(context: Context) {
    data class CachedChannelList(
        val channels: List<TvChannel>,
        val savedAtMs: Long
    )

    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun save(rawBody: String, savedAtMs: Long = System.currentTimeMillis()) {
        // Keep website passwords out of the persistent channel-list cache.
        val safeBody = org.json.JSONArray(rawBody)
        for (index in 0 until safeBody.length()) {
            safeBody.optJSONObject(index)?.optJSONObject("official_app_browser_rotation")?.remove("login")
        }
        preferences.edit()
            .putString(KEY_RAW_BODY, safeBody.toString())
            .putLong(KEY_SAVED_AT, savedAtMs)
            .apply()
    }

    fun load(apiClient: TvApiClient): CachedChannelList? {
        val rawBody = preferences.getString(KEY_RAW_BODY, null)?.takeIf { it.isNotBlank() } ?: return null
        val channels = runCatching { apiClient.parseChannels(rawBody) }.getOrNull().orEmpty()
        if (channels.isEmpty()) return null

        return CachedChannelList(
            channels = channels,
            savedAtMs = preferences.getLong(KEY_SAVED_AT, 0L)
        )
    }

    private companion object {
        const val PREFS_NAME = "channel_list_cache"
        const val KEY_RAW_BODY = "raw_body"
        const val KEY_SAVED_AT = "saved_at_ms"
    }
}
