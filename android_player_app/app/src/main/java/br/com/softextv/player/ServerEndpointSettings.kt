package br.com.softextv.player

import android.content.Context

class ServerEndpointSettings(context: Context) {
    enum class AccessMode {
        AUTO,
        LOCAL,
        EXTERNAL
    }

    enum class AppLayoutRotation {
        SYSTEM,
        LANDSCAPE,
        PORTRAIT,
        PORTRAIT_INVERTED
    }

    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun mode(): AccessMode {
        return runCatching {
            AccessMode.valueOf(preferences.getString(KEY_MODE, AccessMode.AUTO.name) ?: AccessMode.AUTO.name)
        }.getOrDefault(AccessMode.AUTO)
    }

    fun localUrl(): String {
        return preferences.getString(KEY_LOCAL_URL, DEFAULT_LOCAL_URL)?.normalizeBaseUrl()
            ?: DEFAULT_LOCAL_URL
    }

    fun externalUrl(): String {
        return preferences.getString(KEY_EXTERNAL_URL, DEFAULT_EXTERNAL_URL)?.normalizeBaseUrl()
            ?: DEFAULT_EXTERNAL_URL
    }

    fun appLayoutRotation(): AppLayoutRotation {
        return runCatching {
            AppLayoutRotation.valueOf(
                preferences.getString(KEY_APP_LAYOUT_ROTATION, AppLayoutRotation.SYSTEM.name)
                    ?: AppLayoutRotation.SYSTEM.name
            )
        }.getOrDefault(AppLayoutRotation.SYSTEM)
    }

    fun save(
        mode: AccessMode,
        localUrl: String,
        externalUrl: String,
        appLayoutRotation: AppLayoutRotation = appLayoutRotation()
    ) {
        preferences.edit()
            .putString(KEY_MODE, mode.name)
            .putString(KEY_LOCAL_URL, localUrl.normalizeBaseUrl() ?: DEFAULT_LOCAL_URL)
            .putString(KEY_EXTERNAL_URL, externalUrl.normalizeBaseUrl() ?: DEFAULT_EXTERNAL_URL)
            .putString(KEY_APP_LAYOUT_ROTATION, appLayoutRotation.name)
            .apply()
    }

    fun baseUrls(defaultBaseUrls: String): List<String> {
        val local = localUrl()
        val external = externalUrl()
        val configuredDefaults = defaultBaseUrls
            .split(';', ',', '\n')
            .mapNotNull { it.normalizeBaseUrl() }

        return when (mode()) {
            AccessMode.LOCAL -> listOf(local, external)
            AccessMode.EXTERNAL -> listOf(external, local)
            AccessMode.AUTO -> listOf(local, external) + configuredDefaults
        }.distinct()
    }

    fun modeLabel(): String {
        return when (mode()) {
            AccessMode.AUTO -> "automatico"
            AccessMode.LOCAL -> "local"
            AccessMode.EXTERNAL -> "externo"
        }
    }

    private fun String.normalizeBaseUrl(): String? {
        val raw = trim()
            .replace(" ", "")
            .trimEnd('/')
        if (raw.isBlank()) return null
        val withScheme = if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "http://$raw"
        return withScheme.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }

    companion object {
        const val DEFAULT_LOCAL_URL = "http://192.168.1.98:3000"
        const val DEFAULT_EXTERNAL_URL = "http://apontitv.com"
        private const val PREFS_NAME = "server_endpoint_settings"
        private const val KEY_MODE = "mode"
        private const val KEY_LOCAL_URL = "local_url"
        private const val KEY_EXTERNAL_URL = "external_url"
        private const val KEY_APP_LAYOUT_ROTATION = "app_layout_rotation"
    }
}
