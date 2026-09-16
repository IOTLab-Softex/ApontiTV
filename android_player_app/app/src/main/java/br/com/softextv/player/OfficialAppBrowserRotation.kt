package br.com.softextv.player

data class OfficialAppBrowserRotation(
    val enabled: Boolean,
    val webOnly: Boolean,
    val pageUrl: String?,
    val rotationTrigger: String?,
    val switchIntervalSeconds: Int,
    val pageDurationSeconds: Int,
    val transitionStyle: String?,
    val transitionDurationMs: Int,
    val directVideoUrl: String?,
    val loginJson: String? = null
)
