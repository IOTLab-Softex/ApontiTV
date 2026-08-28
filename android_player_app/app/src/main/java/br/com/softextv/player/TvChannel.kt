package br.com.softextv.player

data class TvChannel(
    val id: Long,
    val name: String,
    val streamUrl: String,
    val playbackUrl: String?,
    val playbackMode: String?,
    val thumbnailUrl: String?,
    val tvIp: String?,
    val keepAppForegroundEnabled: Boolean,
    val deviceMatch: Boolean,
    val status: String?,
    val tvPowerStatus: String?,
    val powerSchedule: TvPowerSchedule?,
    val orientation: String?,
    val playbackAppType: String?,
    val configVersion: String?,
    val playlistItemsJson: String?,
    val playlistSync: PlaylistSync?,
    val playlistNotificationSound: PlaylistNotificationSound?,
    val officialAppBrowserRotation: OfficialAppBrowserRotation?,
    val officialAppWidgetBar: OfficialAppWidgetBar?
)

data class TvPowerSchedule(
    val enabled: Boolean,
    val onTime: String?,
    val offTime: String?,
    val disabledWeekdays: List<Int>,
    val serverTimeMs: Long,
    val timezoneOffsetMinutes: Int
)

data class PlaylistSync(
    val enabled: Boolean,
    val startedAtMs: Long,
    val serverTimeMs: Long,
    val playlistId: Long?,
    val expectedItemId: Long?,
    val expectedIndex: Int?,
    val expectedElapsedMs: Long,
    val resyncToken: Long,
    val resyncReason: String?
)

data class PlaylistNotificationSound(
    val enabled: Boolean,
    val url: String?,
    val name: String?,
    val version: String?
)

data class OfficialAppWidgetBar(
    val enabled: Boolean,
    val style: String,
    val color: String,
    val opacity: Int,
    val blurEnabled: Boolean,
    val behavior: String,
    val animation: String,
    val layoutMode: String,
    val edgeSpacing: Int,
    val showSeconds: Int,
    val appearSeconds: Int,
    val hideSeconds: Int,
    val weatherApiUrl: String?,
    val weatherTestCondition: String,
    val contentMode: String,
    val weatherAssets: Map<String, List<String>>
)

data class PlaylistItem(
    val id: Long,
    val type: String,
    val name: String?,
    val url: String,
    val durationSeconds: Int,
    val videoDurationMode: String,
    val transitionStyle: String,
    val transitionDurationMs: Int
)
