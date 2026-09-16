package br.com.softextv.player

import android.content.Context
import android.os.Build
import android.os.PowerManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.OutputStreamWriter
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.net.URL

class TvApiClient(
    private val context: Context,
    configuredBaseUrls: String
) {
    private val endpointSettings = ServerEndpointSettings(context)
    private val defaultBaseUrls = configuredBaseUrls
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    data class ChannelListResponse(
        val channels: List<TvChannel>,
        val rawBody: String
    )

    data class ChannelStatus(
        val id: Long,
        val status: String?,
        val streamUrl: String?,
        val playbackUrl: String?,
        val keepAppForegroundEnabled: Boolean,
        val orientation: String?,
        val configVersion: String?,
        val playlistItemsJson: String?,
        val playlistSync: PlaylistSync?,
        val presentationControl: PresentationControl?,
        val playlistNotificationSound: PlaylistNotificationSound?,
        val officialAppBrowserRotation: OfficialAppBrowserRotation?,
        val officialAppWidgetBar: OfficialAppWidgetBar?,
        val powerSchedule: TvPowerSchedule?
    )

    fun fetchChannels(): List<TvChannel> {
        return fetchChannelList().channels
    }

    fun fetchChannelList(): ChannelListResponse {
        var lastError: IOException? = null

        for (candidateBaseUrl in orderedBaseUrls()) {
            val endpoint = URL("$candidateBaseUrl/broadcasts/mobile_index.json")
            val connection = buildConnection(endpoint)

            try {
                val body = connection.inputStream.bufferedReader().use(BufferedReader::readText)
                rememberBaseUrl(candidateBaseUrl)
                return ChannelListResponse(
                    channels = parseChannels(body),
                    rawBody = body
                )
            } catch (e: SocketTimeoutException) {
                lastError = IOException("Servidor nao respondeu (${CONNECT_TIMEOUT_MS / 1000}s) em $candidateBaseUrl.", e)
            } catch (e: ConnectException) {
                lastError = IOException("Nao foi possivel conectar ao servidor em $candidateBaseUrl.", e)
            } catch (e: IOException) {
                lastError = e
            } finally {
                connection.disconnect()
            }
        }

        throw IOException("Nao foi possivel conectar ao Aponti TV. Endpoints testados: ${baseUrls().joinToString(" | ")}", lastError)
    }

    fun fetchChannelStatus(channelId: Long): ChannelStatus {
        var lastError: IOException? = null

        for (candidateBaseUrl in orderedBaseUrls()) {
            val endpoint = URL("$candidateBaseUrl/broadcasts/${channelId}/mobile_status.json")
            val connection = buildConnection(endpoint)

            try {
                val body = connection.inputStream.bufferedReader().use(BufferedReader::readText)
                rememberBaseUrl(candidateBaseUrl)
                return parseChannelStatus(body)
            } catch (e: IOException) {
                lastError = e
            } finally {
                connection.disconnect()
            }
        }

        throw IOException("Nao foi possivel consultar o status da TV $channelId.", lastError)
    }

    fun fetchPresentationControl(channelId: Long): PresentationControl {
        var lastError: IOException? = null
        for (candidateBaseUrl in orderedBaseUrls()) {
            val connection = buildConnection(URL("$candidateBaseUrl/broadcasts/$channelId/presentation_status.json"))
            try {
                val body = connection.inputStream.bufferedReader().use(BufferedReader::readText)
                rememberBaseUrl(candidateBaseUrl)
                return JSONObject(body).getJSONObject("presentation_control").toPresentationControl()
            } catch (e: IOException) {
                lastError = e
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("Nao foi possivel consultar o controle da apresentacao.", lastError)
    }

    fun reportPlayerStatus(
        channelId: Long,
        playerStatus: String,
        playlistItemId: Long? = null,
        positionMs: Long? = null,
        message: String? = null
    ) {
        var lastError: IOException? = null
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val screenOnParam = "&screen_on=${if (powerManager.isInteractive) 1 else 0}"

        for (candidateBaseUrl in orderedBaseUrls()) {
            val endpoint = URL("$candidateBaseUrl/broadcasts/${channelId}/mobile_player_status.json")
            val connection = buildConnection(endpoint, requestMethod = "POST").apply {
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            }

            try {
                OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                    val encodedStatus = URLEncoder.encode(playerStatus, Charsets.UTF_8.name())
                    val playlistItemParam = playlistItemId?.let { "&playlist_item_id=$it" }.orEmpty()
                    val positionParam = positionMs?.let { "&position_ms=${it.coerceAtLeast(0L)}" }.orEmpty()
                    val messageParam = message?.takeIf { it.isNotBlank() }?.let {
                        "&message=${URLEncoder.encode(it.take(500), Charsets.UTF_8.name())}"
                    }.orEmpty()
                    val versionName = URLEncoder.encode(BuildConfig.VERSION_NAME, Charsets.UTF_8.name())
                    val versionParams = "&app_version_name=$versionName&app_version_code=${BuildConfig.VERSION_CODE}"
                    writer.write("player_status=$encodedStatus$screenOnParam$playlistItemParam$positionParam$messageParam$versionParams")
                }

                val responseStream =
                    if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
                responseStream?.close()
                if (connection.responseCode in 200..299) {
                    rememberBaseUrl(candidateBaseUrl)
                    return
                }
            } catch (e: IOException) {
                lastError = e
            } finally {
                connection.disconnect()
            }
        }

        if (lastError != null) throw lastError
    }

    fun sendPresentationCommand(channelId: Long, command: String): PresentationControl {
        var lastError: IOException? = null
        for (candidateBaseUrl in orderedBaseUrls()) {
            val endpoint = URL("$candidateBaseUrl/broadcasts/$channelId/presentation_command.json")
            val connection = buildConnection(endpoint, requestMethod = "POST").apply {
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            }
            try {
                OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                    writer.write("command=${URLEncoder.encode(command, Charsets.UTF_8.name())}")
                }
                val responseCode = connection.responseCode
                val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
                val body = stream.bufferedReader().use(BufferedReader::readText)
                if (responseCode !in 200..299) throw IOException(JSONObject(body).optString("error", "Falha no controle remoto"))
                rememberBaseUrl(candidateBaseUrl)
                return JSONObject(body).getJSONObject("presentation_control").toPresentationControl()
            } catch (e: IOException) {
                lastError = e
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("Não foi possível enviar o comando de apresentação.", lastError)
    }

    fun requestAdbAuthorization(channelId: Long) {
        var lastError: IOException? = null

        for (candidateBaseUrl in orderedBaseUrls()) {
            val endpoint = URL("$candidateBaseUrl/broadcasts/${channelId}/request_adb_authorization.json")
            val connection = buildConnection(endpoint, requestMethod = "POST").apply {
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            }

            try {
                OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                    writer.write("source=android_player_app")
                }

                val responseStream =
                    if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
                responseStream?.close()
                if (connection.responseCode in 200..299) {
                    rememberBaseUrl(candidateBaseUrl)
                    return
                }
            } catch (e: IOException) {
                lastError = e
            } finally {
                connection.disconnect()
            }
        }

        if (lastError != null) throw lastError
    }

    fun fetchTemperature(): String {
        return fetchWeather(null).temperatureLabel
    }

    fun fetchWeather(weatherApiUrl: String?): WeatherSnapshot {
        var lastError: IOException? = null

        val configuredEndpoint = weatherApiUrl
            ?.trim()
            ?.takeIf { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }

        val endpoints = listOfNotNull(configuredEndpoint) + orderedBaseUrls().map { "$it/temperature" }

        for (endpointUrl in endpoints) {
            val endpoint = URL(endpointUrl)
            val connection = buildConnection(endpoint)

            try {
                val body = connection.inputStream.bufferedReader().use(BufferedReader::readText)
                if (configuredEndpoint == null) {
                    endpointUrl.substringBeforeLast("/temperature").takeIf { it != endpointUrl }?.let(::rememberBaseUrl)
                }
                return JSONObject(body).toWeatherSnapshot()
            } catch (e: IOException) {
                lastError = e
            } finally {
                connection.disconnect()
            }
        }

        if (lastError != null) throw lastError
        return WeatherSnapshot("--", "", "", "clouds")
    }

    fun fetchForecast(latitude: Double, longitude: Double, timezone: String, days: Int): List<WeatherForecastDay> {
        val forecastDays = days.coerceIn(1, 7)
        val encodedTimezone = URLEncoder.encode(timezone.ifBlank { "America/Sao_Paulo" }, "UTF-8")
        val url = URL("https://api.open-meteo.com/v1/forecast?latitude=$latitude&longitude=$longitude&daily=weather_code,temperature_2m_max,temperature_2m_min&timezone=$encodedTimezone&forecast_days=${forecastDays + 1}")
        val connection = buildConnection(url)
        try {
            val daily = JSONObject(connection.inputStream.bufferedReader().use(BufferedReader::readText)).optJSONObject("daily") ?: return emptyList()
            val dates = daily.optJSONArray("time") ?: return emptyList()
            val maxes = daily.optJSONArray("temperature_2m_max") ?: return emptyList()
            val mins = daily.optJSONArray("temperature_2m_min") ?: return emptyList()
            val codes = daily.optJSONArray("weather_code") ?: return emptyList()
            return (1 until minOf(dates.length(), forecastDays + 1)).map { index ->
                WeatherForecastDay(dates.optString(index), maxes.optDouble(index), mins.optDouble(index), codes.optInt(index))
            }
        } finally { connection.disconnect() }
    }

    fun endpointLabel(): String = orderedBaseUrls().joinToString(" | ")

    private fun orderedBaseUrls(): List<String> {
        val baseUrls = baseUrls()
        if (endpointSettings.mode() != ServerEndpointSettings.AccessMode.AUTO) {
            return baseUrls
        }

        val preferred = preferences.getString(KEY_PREFERRED_BASE_URL, null)
            ?.takeIf { it in baseUrls }
        return if (preferred == null) {
            baseUrls
        } else {
            listOf(preferred) + baseUrls.filterNot { it == preferred }
        }
    }

    private fun rememberBaseUrl(baseUrl: String) {
        preferences.edit().putString(KEY_PREFERRED_BASE_URL, baseUrl).apply()
    }

    private fun baseUrls(): List<String> {
        return endpointSettings.baseUrls(defaultBaseUrls)
    }

    private fun buildConnection(endpoint: URL, requestMethod: String = "GET"): HttpURLConnection {
        val deviceIps = DeviceIdentityResolver.currentIpv4Addresses().joinToString(",")
        return (endpoint.openConnection() as HttpURLConnection).apply {
            this.requestMethod = requestMethod
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("X-TV-Device-Token", DeviceSession.deviceToken(context))
            if (deviceIps.isNotBlank()) {
                setRequestProperty("X-TV-Device-Ips", deviceIps)
            }
            setRequestProperty("X-TV-Device-Model", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 20_000
        private const val PREFS_NAME = "aponti_api_client"
        private const val KEY_PREFERRED_BASE_URL = "preferred_base_url"
    }

    fun parseChannels(body: String): List<TvChannel> {
        val jsonArray = JSONArray(body)
        val channels = mutableListOf<TvChannel>()

        for (index in 0 until jsonArray.length()) {
            val item = jsonArray.getJSONObject(index)
            val officialConfig = item.optJSONObject("official_app_browser_rotation")
            val notificationSound = item.optJSONObject("playlist_notification_sound")
            val playlistSync = item.optJSONObject("playlist_sync")
            val presentationControl = item.optJSONObject("presentation_control")
            val widgetBar = item.optJSONObject("official_app_widget_bar")
            val powerSchedule = item.optJSONObject("power_schedule")
            val playlistItems = item.optJSONArray("playlist_items")
            val channelId = item.optLong("id", -1L)
            if (channelId <= 0L) continue

            val directVideoUrl = officialConfig?.optCleanString("direct_video_url")
            val playbackUrl = item.optCleanString("playback_url").orEmpty().ifBlank {
                item.optCleanString("stream_url").orEmpty().ifBlank {
                    directVideoUrl.orEmpty()
                }
            }
            val streamUrl = item.optCleanString("stream_url").orEmpty().ifBlank {
                item.optCleanString("playback_url").orEmpty().ifBlank {
                    directVideoUrl.orEmpty()
                }
            }
            val hasPlaylist = playlistItems != null && playlistItems.length() > 0
            if (playbackUrl.isBlank() && streamUrl.isBlank() && !hasPlaylist) continue

            channels.add(
                TvChannel(
                    id = channelId,
                    name = item.optCleanString("name") ?: "TV $channelId",
                    streamUrl = streamUrl,
                    playbackUrl = playbackUrl.takeIf { it.isNotBlank() },
                    playbackMode = item.optCleanString("playback_mode"),
                    thumbnailUrl = item.optCleanString("thumbnail_url"),
                    tvIp = item.optCleanString("tv_ip"),
                    keepAppForegroundEnabled = item.optBoolean("keep_app_foreground_enabled", false),
                    deviceMatch = item.optBoolean("device_match"),
                    status = item.optCleanString("status") ?: "stopped",
                    tvPowerStatus = item.optCleanString("tv_power_status"),
                    powerSchedule = powerSchedule?.toTvPowerSchedule(),
                    orientation = item.optCleanString("orientation")?.takeIf { it == "portrait" || it == "portrait_inverted" || it == "landscape" } ?: "portrait",
                    playbackAppType = item.optCleanString("playback_app_type") ?: "official_app",
                    configVersion = item.optCleanString("config_version"),
                    playlistItemsJson = playlistItems?.toString(),
                    playlistSync = playlistSync?.toPlaylistSync(),
                    presentationControl = presentationControl?.toPresentationControl(),
                    playlistNotificationSound = notificationSound?.toPlaylistNotificationSound(),
                    officialAppWidgetBar = widgetBar?.toOfficialAppWidgetBar(),
                    officialAppBrowserRotation = officialConfig?.let { config ->
                        OfficialAppBrowserRotation(
                            enabled = config.optBoolean("enabled"),
                            webOnly = config.optBoolean("web_only"),
                            pageUrl = config.optCleanString("page_url"),
                            loginJson = config.optJSONObject("login")?.toString(),
                            rotationTrigger = config.optCleanString("rotation_trigger"),
                            switchIntervalSeconds = config.optInt("switch_interval_seconds", 300),
                            pageDurationSeconds = config.optInt("page_duration_seconds", 15),
                            transitionStyle = config.optCleanString("transition_style"),
                            transitionDurationMs = config.optInt("transition_duration_ms", 900),
                            directVideoUrl = directVideoUrl
                        )
                    }
                )
            )
        }

        return channels
    }

    private fun parseChannelStatus(body: String): ChannelStatus {
        val item = JSONObject(body)
        val officialConfig = item.optJSONObject("official_app_browser_rotation")
        val notificationSound = item.optJSONObject("playlist_notification_sound")
        val playlistSync = item.optJSONObject("playlist_sync")
        val presentationControl = item.optJSONObject("presentation_control")
        val widgetBar = item.optJSONObject("official_app_widget_bar")
        val powerSchedule = item.optJSONObject("power_schedule")
        val directVideoUrl = officialConfig?.optCleanString("direct_video_url")
        return ChannelStatus(
            id = item.optLong("id"),
            status = item.optCleanString("status"),
            streamUrl = item.optCleanString("stream_url"),
            playbackUrl = item.optCleanString("playback_url"),
            keepAppForegroundEnabled = item.optBoolean("keep_app_foreground_enabled", false),
            orientation = item.optCleanString("orientation"),
            configVersion = item.optCleanString("config_version"),
            playlistItemsJson = item.optJSONArray("playlist_items")?.toString(),
            playlistSync = playlistSync?.toPlaylistSync(),
            presentationControl = presentationControl?.toPresentationControl(),
            playlistNotificationSound = notificationSound?.toPlaylistNotificationSound(),
            officialAppWidgetBar = widgetBar?.toOfficialAppWidgetBar(),
            powerSchedule = powerSchedule?.toTvPowerSchedule(),
            officialAppBrowserRotation = officialConfig?.let { config ->
                OfficialAppBrowserRotation(
                    enabled = config.optBoolean("enabled"),
                    webOnly = config.optBoolean("web_only"),
                    pageUrl = config.optCleanString("page_url"),
                    loginJson = config.optJSONObject("login")?.toString(),
                    rotationTrigger = config.optCleanString("rotation_trigger"),
                    switchIntervalSeconds = config.optInt("switch_interval_seconds", 300),
                    pageDurationSeconds = config.optInt("page_duration_seconds", 15),
                    transitionStyle = config.optCleanString("transition_style"),
                    transitionDurationMs = config.optInt("transition_duration_ms", 900),
                    directVideoUrl = directVideoUrl
                )
            }
        )
    }

    private fun JSONObject.optCleanString(name: String): String? {
        if (isNull(name)) return null
        return optString(name)
            .trim()
            .takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
    }

    private fun JSONObject.toPlaylistNotificationSound(): PlaylistNotificationSound {
        return PlaylistNotificationSound(
            enabled = optBoolean("enabled", false),
            url = optCleanString("url"),
            name = optCleanString("name"),
            version = optCleanString("version")
        )
    }

    private fun JSONObject.toTvPowerSchedule(): TvPowerSchedule {
        val disabledDays = optJSONArray("disabled_weekdays")?.let { jsonArray ->
            (0 until jsonArray.length()).mapNotNull { index ->
                jsonArray.optString(index).toIntOrNull()?.takeIf { it in 0..6 }
            }
        }.orEmpty()

        return TvPowerSchedule(
            enabled = optBoolean("enabled", false),
            onTime = optCleanString("on_time"),
            offTime = optCleanString("off_time"),
            disabledWeekdays = disabledDays,
            serverTimeMs = optLong("server_time_ms", 0L),
            timezoneOffsetMinutes = optInt("timezone_offset_minutes", 0)
        )
    }

    private fun JSONObject.toPlaylistSync(): PlaylistSync {
        return PlaylistSync(
            enabled = optBoolean("enabled", false),
            startedAtMs = optLong("started_at_ms", 0L),
            serverTimeMs = optLong("server_time_ms", 0L),
            playlistId = optLong("playlist_id", 0L).takeIf { it > 0L },
            expectedItemId = optLong("expected_item_id", 0L).takeIf { it > 0L },
            expectedIndex = optInt("expected_index", -1).takeIf { it >= 0 },
            expectedElapsedMs = optLong("expected_elapsed_ms", 0L).coerceAtLeast(0L),
            resyncToken = optLong("resync_token", 0L).coerceAtLeast(0L),
            resyncReason = optCleanString("resync_reason")
        )
    }

    private fun JSONObject.toPresentationControl(): PresentationControl {
        return PresentationControl(
            enabled = optBoolean("enabled", false),
            paused = optBoolean("paused", false),
            command = optCleanString("command"),
            commandVersion = optInt("command_version", 0).coerceAtLeast(0),
            commandUrl = optCleanString("command_url"),
            playlistItemCount = optInt("playlist_item_count", 0).coerceAtLeast(0),
            currentItemId = optLong("current_item_id", 0L).takeIf { it > 0L }
        )
    }

    private fun JSONObject.toOfficialAppWidgetBar(): OfficialAppWidgetBar {
        return OfficialAppWidgetBar(
            enabled = optBoolean("enabled", false),
            style = optCleanString("style") ?: "transparent_blur",
            color = optCleanString("color") ?: "#0b1020",
            opacity = optInt("opacity", 72).coerceIn(0, 100),
            blurEnabled = optBoolean("blur_enabled", true),
            behavior = optCleanString("behavior") ?: "fixed",
            animation = optCleanString("animation") ?: "slide",
            layoutMode = optCleanString("layout_mode") ?: "overlay",
            edgeSpacing = optInt("edge_spacing", 0).coerceIn(0, 120),
            showSeconds = optInt("show_seconds", 8).coerceIn(1, 120),
            appearSeconds = optInt("appear_seconds", 0).coerceIn(0, 7200),
            hideSeconds = optInt("hide_seconds", 0).coerceIn(0, 7200),
            weatherApiUrl = optCleanString("weather_api_url"),
            weatherTestCondition = optCleanString("weather_test_condition") ?: "real",
            contentMode = optCleanString("content_mode") ?: "time_weather",
            forecastEnabled = optBoolean("forecast_enabled", true),
            forecastDays = optInt("forecast_days", 5).coerceIn(1, 7),
            forecastAnimationEnabled = optBoolean("forecast_animation_enabled", true),
            forecastTravelSeconds = optInt("forecast_travel_seconds", 12).coerceIn(4, 60),
            forecastPauseSeconds = optInt("forecast_pause_seconds", 3).coerceIn(0, 20),
            forecastCardAnimation = optCleanString("forecast_card_animation") ?: "stagger_up",
            forecastDisplayMode = optCleanString("forecast_display_mode") ?: "always",
            forecastDisplayMinutes = optInt("forecast_display_minutes", 5).coerceIn(1, 180),
            forecastLatitude = optDouble("forecast_latitude", -8.0476),
            forecastLongitude = optDouble("forecast_longitude", -34.8770),
            forecastTimezone = optCleanString("forecast_timezone") ?: "America/Sao_Paulo",
            weatherAssets = optJSONObject("weather_assets")?.toStringListMap().orEmpty()
        )
    }

    private fun JSONObject.toStringListMap(): Map<String, List<String>> {
        val result = mutableMapOf<String, List<String>>()
        keys().forEach { key ->
            val value = opt(key)
            val values = when (value) {
                is JSONArray -> (0 until value.length()).mapNotNull { index ->
                    value.optString(index).trim().takeIf { it.isNotBlank() }
                }
                else -> optCleanString(key)?.let { listOf(it) }.orEmpty()
            }
            if (values.isNotEmpty()) result[key] = values
        }
        return result
    }

    private fun JSONObject.toStringMap(): Map<String, String> {
        val result = mutableMapOf<String, String>()
        keys().forEach { key ->
            optCleanString(key)?.let { value ->
                result[key] = value
            }
        }
        return result
    }

    private fun JSONObject.toWeatherSnapshot(): WeatherSnapshot {
        val root = optJSONObject("data") ?: this
        val temperatureNode = root.optJSONObject("temperatura")
        val temperatureValue = temperatureNode?.let { node ->
            node.optDouble("value", Double.NaN).takeUnless { it.isNaN() }
                ?: node.optDouble("valor", Double.NaN).takeUnless { it.isNaN() }
        }
        val formattedTemperature = if (temperatureValue != null) {
            "${temperatureValue.toInt()}°C"
        } else {
            root.optCleanString("temperature") ?: "--"
        }
        val normalizedTemperature = formattedTemperature.replace("Â", "")

        val uvNode = root.optJSONObject("indice_uv") ?: root.optJSONObject("uv")
        val uvValue = uvNode?.let { node ->
            node.optDouble("value", Double.NaN).takeUnless { it.isNaN() }
                ?: node.optDouble("valor", Double.NaN).takeUnless { it.isNaN() }
        } ?: root.optDouble("uv_index", Double.NaN)
        val uvLabel = if (!uvValue.isNaN()) {
            "UV ${String.format(java.util.Locale.US, "%.1f", uvValue)}"
        } else {
            ""
        }

        return WeatherSnapshot(
            temperatureLabel = normalizedTemperature,
            statusLabel = root.optJSONObject("ceu")?.optCleanString("weather_text")
                ?: temperatureNode?.optCleanString("status")
                ?: root.optCleanString("status").orEmpty(),
            uvLabel = uvLabel,
            condition = root.optJSONObject("raw")?.optJSONObject("accuweather")?.optJSONObject("icon_info")?.optCleanString("cat")
                ?: root.optJSONObject("ceu")?.optCleanString("cloud_status")
                ?: root.optJSONObject("ceu")?.optCleanString("weather_text")
                ?: root.optJSONObject("weather_cond")?.optCleanString("value")
                ?: root.optCleanString("condition")
                ?: "clouds",
            iconCode = root.optJSONObject("ceu")?.optInt("weather_icon", -1)?.takeIf { it >= 0 }
                ?: root.optJSONObject("raw")?.optJSONObject("accuweather")?.optInt("weather_icon", -1)?.takeIf { it >= 0 },
            iconEmoji = root.optJSONObject("raw")?.optJSONObject("accuweather")?.optJSONObject("icon_info")?.optCleanString("emoji")
        )
    }
}

data class WeatherSnapshot(
    val temperatureLabel: String,
    val statusLabel: String,
    val uvLabel: String,
    val condition: String,
    val iconCode: Int? = null,
    val iconEmoji: String? = null
)

data class WeatherForecastDay(val date: String, val max: Double, val min: Double, val code: Int)
