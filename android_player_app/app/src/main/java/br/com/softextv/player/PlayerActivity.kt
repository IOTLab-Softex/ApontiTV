package br.com.softextv.player

import android.animation.ValueAnimator
import android.content.Intent
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.RenderEffect
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.SurfaceTexture
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.TransitionDrawable
import android.media.MediaPlayer
import android.net.Uri
import android.view.KeyEvent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.format.DateFormat
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewTreeObserver
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import android.webkit.WebChromeClient
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import br.com.softextv.player.databinding.ActivityPlayerBinding
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.random.Random

class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private var player: ExoPlayer? = null
    private var preloadedPlaylistPlayer: ExoPlayer? = null
    private var preloadedPlaylistUrl: String = ""
    private var playlistNotificationPlayer: MediaPlayer? = null
    private val rotationHandler = Handler(Looper.getMainLooper())
    private val statusHandler = Handler(Looper.getMainLooper())
    private val retryHandler = Handler(Looper.getMainLooper())
    private val widgetHandler = Handler(Looper.getMainLooper())
    private var widgetAutoCycleRunnable: Runnable? = null
    private var widgetBarConfigSignature: String = ""
    private var localPowerShutdownPending = false
    private var widgetBarBaseTranslationX = 0f
    private var widgetBarBaseTranslationY = 0f
    private var widgetBackdropDrawable: Drawable? = null
    private var widgetForecastAnimator: ValueAnimator? = null
    private var forecastHideRunnable: Runnable? = null
    private var forecastShowRunnable: Runnable? = null
    private var playerSplashDismissed = false
    private var playerSplashPlayer: MediaPlayer? = null
    private var playerSplashSurface: Surface? = null
    private var playerSplashStartRunnable: Runnable? = null
    private var playerSplashVideoStartedAtMs: Long = 0L
    private val apiClient by lazy { TvApiClient(applicationContext, BuildConfig.API_BASE_URLS) }
    private val localPowerScheduleManager by lazy { LocalPowerScheduleManager(applicationContext) }
    private val directVideoCache by lazy { DirectVideoCache(applicationContext) }
    private val playlistMediaCache by lazy { PlaylistMediaCache(applicationContext) }
    private val playlistCacheExecutor = Executors.newSingleThreadExecutor()
    private var showingBrowser = false
    private var useVideoEndTrigger = false
    private var browserRotationEnabled = false
    private var browserPageLoaded = false
    private var browserPreparedForDisplay = false
    private var pendingBrowserSwitch = false
    private var browserPageUrl: String = ""
    private var awaitingSelectionReturn = false
    private var preparingDirectVideo = false
    private var playerReady = false
    private var rotationStarted = false
    private var pendingVideoSwitch = false
    private var isTransitioning = false
    private var directLocalRetryAttempted = false
    private var hudManuallyRequested = false
    private var currentRemotePlaybackUrl: String = ""
    private var currentConfigVersion: String = ""
    private var restartPlaybackPending = false
    private var playlistItems: List<PlaylistItem> = emptyList()
    private var currentPlaylistItemsJson: String = ""
    private var currentPlaylistNotificationUrl: String = ""
    private var currentPlaylistNotificationVersion: String = ""
    private var playlistNotificationEnabled = true
    private var playlistSyncEnabled = false
    private var playlistSyncStartedAtMs = 0L
    private var playlistServerClockOffsetMs = 0L
    private var playlistVideoReadyResyncedItemId: Long? = null
    private var playlistResyncToken = 0L
    private var currentPlaylistIndex = 0
    private var presentationModeEnabled = false
    private var presentationPaused = false
    private var presentationPausedRemainingMs: Long? = null
    private var presentationCommandVersion = 0
    @Volatile private var statusPollInFlight = false
    @Volatile private var presentationPollInFlight = false
    private val presentationHandler = Handler(Looper.getMainLooper())
    private var currentPlaylistItemStartedRealtimeMs = 0L
    private var playlistPlaybackActive = false
    private var retryCount = 0
    private val retryRunnable = Runnable { retryPlayback() }
    private val hideHudRunnable = Runnable { hideHud() }
    private val directVideoTimeoutRunnable = Runnable { handleDirectVideoTimeout() }
    private val playbackReadyTimeoutRunnable = Runnable { handlePlaybackReadyTimeout() }
    private val nextPlaylistItemRunnable = Runnable { playNextPlaylistItem() }
    private val hideWidgetBarRunnable = Runnable { hideWidgetBar() }
    private val clockWidgetRunnable = object : Runnable {
        override fun run() {
            updateWidgetClock()
            widgetHandler.postDelayed(this, CLOCK_WIDGET_INTERVAL_MS)
        }
    }
    private val weatherWidgetRunnable = object : Runnable {
        override fun run() {
            refreshWidgetWeatherFromConfiguredApi()
            widgetHandler.postDelayed(this, WEATHER_WIDGET_INTERVAL_MS)
        }
    }
    private val widgetBackdropRefreshRunnable = Runnable {
        if (isWidgetBarEnabled() && shouldUseWidgetBackdropBlur() && binding.widgetBar.visibility == View.VISIBLE) {
            refreshWidgetBackdropBlur()
        }
    }

    private val showBrowserRunnable = Runnable {
        showBrowserOverlay()
    }

    private val showVideoRunnable = Runnable {
        showVideoSurface()
    }

    private val checkBroadcastStatusRunnable = object : Runnable {
        override fun run() {
            pollBroadcastStatus()
            statusHandler.postDelayed(this, STATUS_POLL_INTERVAL_MS)
        }
    }

    private val checkPresentationStatusRunnable = object : Runnable {
        override fun run() {
            if (presentationModeEnabled) pollPresentationStatus()
            presentationHandler.postDelayed(this, PRESENTATION_POLL_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching {
            binding = ActivityPlayerBinding.inflate(layoutInflater)
            setContentView(binding.root)
            configurePlayerSplash()
            resetVideoPresentation()

            runCatching {
                WindowCompat.setDecorFitsSystemWindows(window, false)
                hideSystemUi()
            }

            binding.playerTitle.text = intent.getStringExtra(EXTRA_CHANNEL_NAME).orEmpty()
            currentRemotePlaybackUrl = initialRemotePlaybackUrl()
            currentConfigVersion = intent.getStringExtra(EXTRA_CONFIG_VERSION).orEmpty()
            ApontiForegroundState.setKeepOpenEnabled(
                applicationContext,
                intent.getBooleanExtra(EXTRA_KEEP_APP_FOREGROUND_ENABLED, false)
            )
            currentPlaylistItemsJson = intent.getStringExtra(EXTRA_PLAYLIST_ITEMS_JSON).orEmpty()
            playlistNotificationEnabled = intent.getBooleanExtra(EXTRA_PLAYLIST_NOTIFICATION_ENABLED, true)
            currentPlaylistNotificationUrl = intent.getStringExtra(EXTRA_PLAYLIST_NOTIFICATION_URL).orEmpty()
            currentPlaylistNotificationVersion = intent.getStringExtra(EXTRA_PLAYLIST_NOTIFICATION_VERSION).orEmpty()
            playlistSyncEnabled = intent.getBooleanExtra(EXTRA_PLAYLIST_SYNC_ENABLED, false)
            playlistSyncStartedAtMs = intent.getLongExtra(EXTRA_PLAYLIST_SYNC_STARTED_AT_MS, 0L)
            updatePlaylistServerClockOffset(intent.getLongExtra(EXTRA_PLAYLIST_SYNC_SERVER_TIME_MS, 0L))
            playlistItems = parsePlaylistItems(currentPlaylistItemsJson)
            synchronizePlaylistMediaCache(playlistItems)
            binding.closeButton.setOnClickListener { returnToSelection() }
            binding.playerHeader.visibility = View.GONE
            configureBrowserView()
            configureWidgetBar()
        }.onFailure { error ->
            handlePlayerStartupFailure(error)
        }
    }

    override fun onStart() {
        super.onStart()
        if (!::binding.isInitialized) return
        ApontiForegroundState.markForeground(applicationContext)
        ApontiForegroundWatchdogReceiver.schedule(applicationContext)
        runCatching {
            hideSystemUi()
            initializePlayer()
            startStatusPolling()
            reportPlayerPresence("online")
        }.onFailure { error ->
            handlePlayerStartupFailure(error)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemUi()
            if (!hudManuallyRequested) {
                hideHud()
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!awaitingSelectionReturn) {
            ApontiForegroundState.markBackground(applicationContext)
            ApontiForegroundWatchdogReceiver.scheduleSoon(applicationContext)
        }
    }

    override fun onStop() {
        stopPlayerSplashVideo()
        restartPlaybackPending = false
        retryHandler.removeCallbacksAndMessages(null)
        retryHandler.removeCallbacks(nextPlaylistItemRunnable)
        stopStatusPolling()
        stopOfficialAppRotation()
        stopWidgetBar()
        releasePlayer()
        releasePreloadedPlaylistPlayer()
        releasePlaylistNotificationPlayer()
        if (!awaitingSelectionReturn) reportPlayerPresence("offline")
        ApontiForegroundState.markBackground(applicationContext)
        ApontiForegroundWatchdogReceiver.scheduleSoon(applicationContext)
        super.onStop()
    }

    private fun configurePlayerSplash() {
        playerSplashDismissed = false
        binding.playerSplashOverlay.visibility = View.VISIBLE
        binding.playerSplashOverlay.alpha = 1f
        binding.playerSplashVideo.visibility = View.VISIBLE
        binding.playerSplashVideo.alpha = 1f
        binding.playerSplashVideo.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
                preparePlayerSplashVideo(surfaceTexture)
            }

            override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) = Unit

            override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
                releasePlayerSplashVideo()
                return true
            }

            override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) = Unit
        }
        binding.playerSplashVideo.surfaceTexture?.let { preparePlayerSplashVideo(it) }
    }

    private fun preparePlayerSplashVideo(surfaceTexture: SurfaceTexture) {
        releasePlayerSplashVideo()
        val surface = Surface(surfaceTexture)
        playerSplashSurface = surface
        playerSplashPlayer = MediaPlayer().apply {
            setDataSource(applicationContext, Uri.parse("android.resource://$packageName/${R.raw.abertura_splash}"))
            setSurface(surface)
            isLooping = false
            setVolume(0f, 0f)
            setOnPreparedListener {
                if (binding.playerSplashOverlay.visibility == View.VISIBLE && binding.playerSplashVideo.visibility == View.VISIBLE) {
                    schedulePlayerSplashVideoStart()
                }
            }
            setOnCompletionListener {
                dismissPlayerSplash()
            }
            setOnErrorListener { _, _, _ ->
                dismissPlayerSplash()
                true
            }
            prepareAsync()
        }
    }

    private fun dismissPlayerSplash() {
        if (playerSplashDismissed || !::binding.isInitialized) return

        val startedAt = playerSplashVideoStartedAtMs.takeIf { it > 0L }
        if (startedAt != null) {
            val elapsed = System.currentTimeMillis() - startedAt
            val remaining = (PLAYER_SPLASH_DURATION_MS - elapsed).coerceAtLeast(0L)
            if (remaining > 0L) {
                binding.playerSplashOverlay.postDelayed({ dismissPlayerSplash() }, remaining)
                return
            }
        }

        playerSplashDismissed = true
        binding.playerSplashOverlay.animate()
            .alpha(0f)
            .setDuration(260L)
            .withEndAction {
                binding.playerSplashVideo.visibility = View.INVISIBLE
                stopPlayerSplashVideo()
                binding.playerSplashOverlay.visibility = View.GONE
                binding.playerSplashOverlay.alpha = 1f
                binding.playerSplashVideo.alpha = 1f
            }
            .start()
    }

    private fun stopPlayerSplashVideo() {
        playerSplashStartRunnable?.let { retryHandler.removeCallbacks(it) }
        playerSplashStartRunnable = null
        playerSplashVideoStartedAtMs = 0L
        runCatching {
            playerSplashPlayer?.let { player ->
                if (player.isPlaying) player.pause()
                player.seekTo(0)
            }
        }
    }

    private fun schedulePlayerSplashVideoStart() {
        playerSplashStartRunnable?.let { retryHandler.removeCallbacks(it) }
        val runnable = Runnable {
            if (playerSplashDismissed || binding.playerSplashOverlay.visibility != View.VISIBLE || binding.playerSplashVideo.visibility != View.VISIBLE) return@Runnable
            runCatching {
                playerSplashPlayer?.let { player ->
                    player.seekTo(0)
                    if (!player.isPlaying) {
                        player.start()
                        playerSplashVideoStartedAtMs = System.currentTimeMillis()
                        binding.playerSplashOverlay.postDelayed({ dismissPlayerSplash() }, PLAYER_SPLASH_DURATION_MS)
                    }
                }
            }
        }
        playerSplashStartRunnable = runnable
        retryHandler.postDelayed(runnable, PLAYER_SPLASH_VIDEO_START_DELAY_MS)
    }

    private fun releasePlayerSplashVideo() {
        playerSplashStartRunnable?.let { retryHandler.removeCallbacks(it) }
        playerSplashStartRunnable = null
        runCatching { playerSplashPlayer?.release() }
        playerSplashPlayer = null
        runCatching { playerSplashSurface?.release() }
        playerSplashSurface = null
    }

    private fun initializePlayer(overrideUrl: String? = null) {
        if (isOfficialAppWebOnly() && isOfficialAppWebDisplayEnabled()) {
            playerReady = true
            startOfficialAppRotationIfNeeded()
            return
        }

        if (overrideUrl.isNullOrBlank() && playlistItems.isNotEmpty()) {
            playlistPlaybackActive = true
            maybePlayPlaylistNotification()
            val syncPosition = synchronizedPlaylistPosition()
            if (syncPosition != null) {
                startPlaylistItem(syncPosition.index, syncPosition.elapsedMs, syncPosition.remainingMs)
            } else {
                startPlaylistItem(0)
            }
            return
        }

        playlistPlaybackActive = false
        val streamUrl = intent.getStringExtra(EXTRA_STREAM_URL).orEmpty()
        val playbackUrl = intent.getStringExtra(EXTRA_PLAYBACK_URL).orEmpty()
        val directVideoUrl = intent.getStringExtra(EXTRA_DIRECT_VIDEO_URL).orEmpty()
        val shouldCacheDirectVideo = overrideUrl.isNullOrBlank() && directVideoUrl.isNotBlank()
        val mediaUrl = overrideUrl?.ifBlank { null } ?: if (shouldCacheDirectVideo) {
            directVideoUrl
        } else {
            playbackUrl.ifBlank { streamUrl }
        }

        if (mediaUrl.isBlank()) {
            showError(getString(R.string.invalid_stream_url))
            return
        }

        if (shouldCacheDirectVideo) {
            prepareDirectVideoPlayback(mediaUrl)
            return
        }

        startPlayer(mediaUrl)
    }

    private fun initialRemotePlaybackUrl(): String {
        val streamUrl = intent.getStringExtra(EXTRA_STREAM_URL).orEmpty()
        val playbackUrl = intent.getStringExtra(EXTRA_PLAYBACK_URL).orEmpty()
        val directVideoUrl = intent.getStringExtra(EXTRA_DIRECT_VIDEO_URL).orEmpty()
        return playbackUrl.ifBlank { streamUrl.ifBlank { directVideoUrl } }
    }

    private fun prepareDirectVideoPlayback(videoUrl: String) {
        if (preparingDirectVideo) return

        directVideoCache.cachedFileFor(videoUrl)?.let { cachedFile ->
            showDownloadOverlay(getString(R.string.player_opening_cached_video))
            startPlayer(Uri.fromFile(cachedFile).toString())
            return
        }

        preparingDirectVideo = true
        showDownloadOverlay(getString(R.string.player_downloading_video))
        retryHandler.postDelayed(directVideoTimeoutRunnable, DIRECT_VIDEO_TIMEOUT_MS)

        thread {
            runCatching {
                directVideoCache.download(videoUrl) {
                    runOnUiThread {
                        if (!preparingDirectVideo || awaitingSelectionReturn) return@runOnUiThread
                        updateDownloadProgress()
                    }
                }
            }
                .onSuccess { cachedFile ->
                    runOnUiThread {
                        preparingDirectVideo = false
                        retryHandler.removeCallbacks(directVideoTimeoutRunnable)
                        if (awaitingSelectionReturn) return@runOnUiThread
                        binding.errorText.visibility = View.GONE
                        startPlayer(Uri.fromFile(cachedFile).toString())
                    }
                }
                .onFailure { error ->
                    runOnUiThread {
                        preparingDirectVideo = false
                        retryHandler.removeCallbacks(directVideoTimeoutRunnable)
                        if (awaitingSelectionReturn) return@runOnUiThread
                        hideDownloadOverlay()
                        showError("${getString(R.string.player_download_failed)} ${error.message.orEmpty()}".trim())
                    }
                }
        }
    }

    private fun showDownloadOverlay() {
        showDownloadOverlay(getString(R.string.player_downloading_video))
    }

    private fun showDownloadOverlay(message: String) {
        binding.downloadOverlay.visibility = View.VISIBLE
        binding.downloadOverlay.alpha = 1f
        binding.downloadTitle.text = getString(R.string.player_downloading_video_title)
        binding.downloadMessage.text = message
        binding.errorText.visibility = View.GONE
        binding.playerView.visibility = View.INVISIBLE
        binding.browserView.visibility = View.INVISIBLE
    }

    private fun hideDownloadOverlay() {
        binding.downloadOverlay.animate().cancel()
        binding.downloadOverlay.visibility = View.GONE
        binding.downloadOverlay.alpha = 1f
        binding.playerView.visibility = View.VISIBLE
    }

    private fun updateDownloadProgress() {
        binding.downloadMessage.text = getString(R.string.player_downloading_video)
    }

    private fun startPlayer(
        mediaUrl: String,
        keepCurrentSurfaceUntilReady: Boolean = false,
        preparedPlayer: ExoPlayer? = null,
        startPositionMs: Long = 0L
    ) {
        runCatching {
            retryHandler.removeCallbacks(nextPlaylistItemRunnable)
            playerReady = false
            val keepCurrentSurface = keepCurrentSurfaceUntilReady && binding.playlistImageView.visibility == View.VISIBLE
            if (!keepCurrentSurface) {
                binding.playlistImageView.visibility = View.GONE
                binding.previousPlaylistImageView.visibility = View.GONE
            }
            if (!playlistPlaybackActive) {
                resetVideoPresentation()
            }
            binding.playerView.visibility = if (keepCurrentSurface) View.INVISIBLE else View.VISIBLE
            val exoPlayer = preparedPlayer ?: buildPlaylistExoPlayer()
            binding.playerView.player = exoPlayer
            exoPlayer.repeatMode = if (shouldRepeatCurrentVideo()) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            binding.playerView.setControllerShowTimeoutMs(2_000)
            binding.playerView.setControllerAutoShow(false)
            binding.playerView.setControllerHideOnTouch(true)
            binding.playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
                binding.playerHeader.visibility = if (hudManuallyRequested && !isTransitioning && visibility == View.VISIBLE) View.VISIBLE else View.GONE
            })
            binding.playerView.hideController()
            binding.playerHeader.visibility = View.GONE
            binding.errorText.visibility = View.GONE
            retryHandler.removeCallbacks(playbackReadyTimeoutRunnable)
            retryHandler.postDelayed(playbackReadyTimeoutRunnable, PLAYBACK_READY_TIMEOUT_MS)

            exoPlayer.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    retryHandler.removeCallbacks(playbackReadyTimeoutRunnable)
                    val cachedPlaylistItem = currentPlaylistItem()?.takeIf { playlistPlaybackActive && it.type == "video" }
                    if (mediaUrl.startsWith("file:") && cachedPlaylistItem != null) {
                        playlistMediaCache.delete(cachedPlaylistItem.url)
                        synchronizePlaylistMediaCache(playlistItems)
                        releasePlayer()
                        startPlayer(cachedPlaylistItem.url, keepCurrentSurfaceUntilReady = true)
                        return
                    }
                    val directVideoUrl = intent.getStringExtra(EXTRA_DIRECT_VIDEO_URL).orEmpty()
                    if (mediaUrl.startsWith("file:") && directVideoUrl.isNotBlank() && !directLocalRetryAttempted) {
                        directLocalRetryAttempted = true
                        directVideoCache.delete(directVideoUrl)
                        releasePlayer()
                        prepareDirectVideoPlayback(directVideoUrl)
                        return
                    }

                    retryCount++
                    val errorCode = error.errorCodeName.ifBlank { error.message ?: "unknown" }
                    if (retryCount <= MAX_RETRIES) {
                        val retryMessage = getString(R.string.player_error_retrying, retryCount, MAX_RETRIES, errorCode)
                        showError(retryMessage)
                        reportPlayerError(retryMessage)
                        retryHandler.postDelayed(retryRunnable, RETRY_DELAY_MS)
                    } else {
                        val giveUpMessage = getString(R.string.player_error_give_up)
                        showError(giveUpMessage)
                        reportPlayerError("$giveUpMessage $errorCode")
                        retryHandler.postDelayed({ returnToSelection() }, GIVE_UP_REDIRECT_DELAY_MS)
                    }
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) {
                        retryHandler.removeCallbacks(playbackReadyTimeoutRunnable)
                        playerReady = true
                        retryCount = 0
                        val playlistVideoItem = currentPlaylistItem()?.takeIf { playlistPlaybackActive && it.type == "video" }
                        if (playlistVideoItem != null) {
                            resyncReadyPlaylistVideo(exoPlayer, playlistVideoItem)
                            binding.playerView.visibility = View.VISIBLE
                            binding.playerView.bringToFront()
                            bringPlayerOverlaysToFront()
                            applyPlaylistExitTransition(binding.playlistImageView, playlistVideoItem)
                            applyPlaylistTransition(binding.playerView, playlistVideoItem)
                            retryHandler.postDelayed({
                                binding.playlistImageView.visibility = View.GONE
                            }, playlistVideoItem.transitionDurationMs.coerceIn(0, 10_000).toLong().coerceAtLeast(120L))
                            if (presentationModeEnabled && presentationPaused) exoPlayer.pause()
                        } else if (keepCurrentSurface) {
                            binding.playerView.visibility = View.VISIBLE
                            binding.playlistImageView.visibility = View.GONE
                        }
                        binding.errorText.visibility = View.GONE
                        hideDownloadOverlay()
                        hideHud()
                        reportPlayerPresence("playing", currentPlaylistItem()?.id?.takeIf { playlistPlaybackActive })
                        startOfficialAppRotationIfNeeded()
                    }
                    if (playlistPlaybackActive && playlistItems.size > 1 && playbackState == Player.STATE_ENDED && shouldAdvancePlaylistVideoOnEnded()) {
                        playNextPlaylistItem()
                    } else if (useVideoEndTrigger && browserRotationEnabled && !showingBrowser && playbackState == Player.STATE_ENDED) {
                        showBrowserOverlay()
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isPlaying && pendingVideoSwitch && showingBrowser) {
                        completeVideoSurfaceSwitch()
                    }
                }
            })

            if (preparedPlayer == null) {
                exoPlayer.setMediaItem(MediaItem.fromUri(mediaUrl))
                if (startPositionMs > 0L) {
                    exoPlayer.seekTo(startPositionMs)
                }
                exoPlayer.prepare()
            } else if (exoPlayer.playbackState == Player.STATE_READY) {
                if (startPositionMs > 0L) {
                    exoPlayer.seekTo(startPositionMs)
                }
                retryHandler.removeCallbacks(playbackReadyTimeoutRunnable)
                playerReady = true
                retryCount = 0
                binding.playerView.visibility = View.VISIBLE
                binding.playerView.bringToFront()
                bringPlayerOverlaysToFront()
                currentPlaylistItem()?.takeIf { playlistPlaybackActive && it.type == "video" }?.let { item ->
                    applyPlaylistExitTransition(binding.playlistImageView, item)
                    applyPlaylistTransition(binding.playerView, item)
                    retryHandler.postDelayed({
                        binding.playlistImageView.visibility = View.GONE
                    }, item.transitionDurationMs.coerceIn(0, 10_000).toLong().coerceAtLeast(120L))
                } ?: run {
                    binding.playlistImageView.visibility = View.GONE
                }
                binding.errorText.visibility = View.GONE
                hideDownloadOverlay()
                hideHud()
                reportPlayerPresence("playing", currentPlaylistItem()?.id?.takeIf { playlistPlaybackActive })
                startOfficialAppRotationIfNeeded()
            }
            exoPlayer.playWhenReady = true
            player = exoPlayer
        }.onFailure { error ->
            retryHandler.removeCallbacks(playbackReadyTimeoutRunnable)
            showError(error.message ?: getString(R.string.player_start_failed))
            reportPlayerError(error.message ?: getString(R.string.player_start_failed))
        }
    }

    private fun handleDirectVideoTimeout() {
        if (!preparingDirectVideo || awaitingSelectionReturn) return

        preparingDirectVideo = false
        hideDownloadOverlay()
        showError(getString(R.string.player_direct_video_timeout))
        startFallbackPlaybackOrReturn()
    }

    private fun handlePlaybackReadyTimeout() {
        if (playerReady || awaitingSelectionReturn) return

        releasePlayer()
        hideDownloadOverlay()
        showError(getString(R.string.player_ready_timeout))
        retryHandler.postDelayed({ returnToSelection() }, GIVE_UP_REDIRECT_DELAY_MS)
    }

    private fun startFallbackPlaybackOrReturn() {
        val streamUrl = intent.getStringExtra(EXTRA_STREAM_URL).orEmpty()
        val playbackUrl = intent.getStringExtra(EXTRA_PLAYBACK_URL).orEmpty()
        val fallbackUrl = playbackUrl.ifBlank { streamUrl }

        if (fallbackUrl.isNotBlank()) {
            retryHandler.postDelayed({ initializePlayer(overrideUrl = fallbackUrl) }, 1_000L)
        } else {
            retryHandler.postDelayed({ returnToSelection() }, GIVE_UP_REDIRECT_DELAY_MS)
        }
    }

    private fun startPlaylistItem(index: Int, elapsedMs: Long = 0L, remainingMs: Long? = null) {
        if (playlistItems.isEmpty() || awaitingSelectionReturn) return

        val normalizedIndex = ((index % playlistItems.size) + playlistItems.size) % playlistItems.size
        val item = playlistItems[normalizedIndex]
        val itemDurationMs = playlistItemDurationMs(item)
        val nextDelayMs = (remainingMs ?: itemDurationMs).coerceAtLeast(500L)
        currentPlaylistIndex = normalizedIndex
        currentPlaylistItemStartedRealtimeMs = SystemClock.elapsedRealtime() - elapsedMs.coerceAtLeast(0L)
        requestWidgetBackdropRefresh(delayMs = 260L)
        currentRemotePlaybackUrl = item.url
        playlistVideoReadyResyncedItemId = null
        playlistPlaybackActive = true

        retryHandler.removeCallbacks(nextPlaylistItemRunnable)
        retryHandler.removeCallbacks(playbackReadyTimeoutRunnable)
        releasePlayer()
        hideDownloadOverlay()
        binding.errorText.visibility = View.GONE

        if (item.type == "image") {
            playerReady = true
            preparePlaylistSurfaceLayout(binding.playlistImageView)
            prepareOutgoingPlaylistImage()
            binding.playerView.visibility = View.INVISIBLE
            binding.playlistImageView.visibility = View.VISIBLE
            binding.playlistImageView.bringToFront()
            bringPlayerOverlaysToFront()
            binding.playlistImageView.setImageDrawable(null)
            binding.playlistImageView.alpha = 0f
            RemoteImageLoader.loadInto(binding.playlistImageView, playlistMediaCache.localUri(item.url) ?: item.url) {
                if (!playlistPlaybackActive || currentPlaylistItem()?.id != item.id || awaitingSelectionReturn) return@loadInto
                binding.playlistImageView.visibility = View.VISIBLE
                binding.playlistImageView.bringToFront()
                bringPlayerOverlaysToFront()
                requestWidgetBackdropRefresh(delayMs = 160L)
                applyPlaylistExitTransition(binding.previousPlaylistImageView, item)
                applyPlaylistTransition(binding.playlistImageView, item)
            }
            reportPlayerPresence("playing", item.id)
            startOfficialAppRotationIfNeeded()
            if (playlistItems.size > 1) {
                retryHandler.postDelayed(nextPlaylistItemRunnable, nextDelayMs)
                preloadNextPlaylistVideo()
            }
            return
        }

        preparePlaylistSurfaceLayout(binding.playerView)
        binding.playerView.visibility = View.INVISIBLE
        val playableItemUrl = playlistMediaCache.localUri(item.url) ?: item.url
        startPlayer(
            playableItemUrl,
            keepCurrentSurfaceUntilReady = true,
            preparedPlayer = takePreloadedPlaylistPlayer(playableItemUrl),
            startPositionMs = elapsedMs.coerceIn(0L, (itemDurationMs - 500L).coerceAtLeast(0L))
        )
        if (playlistItems.size > 1 && (playlistSyncEnabled || item.videoDurationMode != "video_end")) {
            retryHandler.postDelayed(nextPlaylistItemRunnable, nextDelayMs)
        }
    }

    private fun playNextPlaylistItem() {
        if (!playlistPlaybackActive || playlistItems.size <= 1 || awaitingSelectionReturn) return

        if (shouldOpenBrowserAtPlaylistBoundary()) {
            showBrowserOverlay()
            return
        }

        val syncPosition = synchronizedPlaylistPosition()
        if (syncPosition != null) {
            startPlaylistItem(syncPosition.index, syncPosition.elapsedMs, syncPosition.remainingMs)
        } else {
            startPlaylistItem(currentPlaylistIndex + 1)
        }
    }

    private fun currentPlaylistItem(): PlaylistItem? {
        return playlistItems.getOrNull(currentPlaylistIndex)
    }

    private data class PlaylistSyncPosition(
        val index: Int,
        val elapsedMs: Long,
        val remainingMs: Long
    )

    private fun synchronizedPlaylistPosition(): PlaylistSyncPosition? {
        if (presentationModeEnabled || !playlistSyncEnabled || playlistSyncStartedAtMs <= 0L || playlistItems.isEmpty()) return null

        val durations = playlistItems.map(::playlistItemDurationMs)
        val totalDurationMs = durations.sum()
        if (totalDurationMs <= 0L) return null

        val serverNowMs = System.currentTimeMillis() + playlistServerClockOffsetMs
        val elapsedSinceStartMs = (serverNowMs - playlistSyncStartedAtMs).coerceAtLeast(0L)
        var cursorMs = elapsedSinceStartMs % totalDurationMs

        durations.forEachIndexed { index, durationMs ->
            if (cursorMs < durationMs) {
                return PlaylistSyncPosition(
                    index = index,
                    elapsedMs = cursorMs,
                    remainingMs = (durationMs - cursorMs).coerceAtLeast(500L)
                )
            }
            cursorMs -= durationMs
        }

        return PlaylistSyncPosition(0, 0L, durations.first().coerceAtLeast(500L))
    }

    private fun playlistItemDurationMs(item: PlaylistItem): Long {
        return item.durationSeconds.coerceAtLeast(3) * 1000L
    }

    private fun resyncReadyPlaylistVideo(exoPlayer: ExoPlayer, item: PlaylistItem, force: Boolean = false) {
        if (!playlistPlaybackActive || !playlistSyncEnabled || (!force && playlistVideoReadyResyncedItemId == item.id)) return

        val syncPosition = synchronizedPlaylistPosition() ?: return
        if (syncPosition.index != currentPlaylistIndex) {
            startPlaylistItem(syncPosition.index, syncPosition.elapsedMs, syncPosition.remainingMs)
            return
        }

        val durationMs = playlistItemDurationMs(item)
        val targetMs = syncPosition.elapsedMs.coerceIn(0L, (durationMs - 500L).coerceAtLeast(0L))
        if (kotlin.math.abs(exoPlayer.currentPosition - targetMs) >= PLAYLIST_SYNC_SEEK_THRESHOLD_MS) {
            exoPlayer.seekTo(targetMs)
        }
        if (!force) playlistVideoReadyResyncedItemId = item.id
    }

    private fun updatePlaylistServerClockOffset(serverTimeMs: Long) {
        playlistServerClockOffsetMs = if (serverTimeMs > 0L) {
            serverTimeMs - System.currentTimeMillis()
        } else {
            0L
        }
    }

    private fun prepareOutgoingPlaylistImage() {
        val currentDrawable = binding.playlistImageView.drawable ?: return
        if (binding.playlistImageView.visibility != View.VISIBLE) return

        val outgoingDrawable = currentDrawable.constantState?.newDrawable()?.mutate() ?: currentDrawable
        preparePlaylistSurfaceLayout(binding.previousPlaylistImageView)
        binding.previousPlaylistImageView.animate().cancel()
        binding.previousPlaylistImageView.setImageDrawable(outgoingDrawable)
        binding.previousPlaylistImageView.visibility = View.VISIBLE
        binding.previousPlaylistImageView.alpha = 1f
        binding.previousPlaylistImageView.translationX = binding.playlistImageView.translationX
        binding.previousPlaylistImageView.translationY = binding.playlistImageView.translationY
        binding.previousPlaylistImageView.rotation = binding.playlistImageView.rotation
        binding.previousPlaylistImageView.rotationY = 0f
        binding.previousPlaylistImageView.scaleX = binding.playlistImageView.scaleX
        binding.previousPlaylistImageView.scaleY = binding.playlistImageView.scaleY
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            binding.previousPlaylistImageView.setRenderEffect(null)
        }
        binding.previousPlaylistImageView.bringToFront()
    }

    private fun applyPlaylistExitTransition(view: View, item: PlaylistItem) {
        if (view.visibility != View.VISIBLE) return

        val rawDuration = item.transitionDurationMs.coerceIn(0, 10_000).toLong()
        val duration = if (item.transitionStyle == "none") 0L else rawDuration.coerceAtLeast(450L)
        val travelX = (binding.root.width.takeIf { it > 0 } ?: 720).toFloat()
        val travelY = (binding.root.height.takeIf { it > 0 } ?: 480).toFloat()
        view.animate().cancel()

        if (duration <= 0L || item.transitionStyle == "none") {
            view.visibility = View.GONE
            return
        }

        val animation = view.animate()
            .alpha(0f)
            .setDuration(duration)
            .setInterpolator(AccelerateInterpolator(1.3f))
            .withEndAction {
                view.visibility = View.GONE
                view.translationX = 0f
                view.translationY = 0f
                view.rotationY = 0f
                view.scaleX = 1f
                view.scaleY = 1f
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    view.setRenderEffect(null)
                }
            }

        when (item.transitionStyle) {
            "slide", "push" -> animation.translationX(-travelX * 0.10f)
            "wipe" -> animation.translationY(-travelY * 0.10f)
            "zoom", "cross_zoom", "aponti_smooth", "blur" -> animation.scaleX(0.965f).scaleY(0.965f)
            "flip" -> animation.rotationY(48f)
        }

        animation.start()
    }

    private fun buildPlaylistExoPlayer(): ExoPlayer {
        val renderersFactory = DefaultRenderersFactory(this)
            .setEnableDecoderFallback(true)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                VIDEO_MIN_BUFFER_MS,
                VIDEO_MAX_BUFFER_MS,
                VIDEO_PLAYBACK_BUFFER_MS,
                VIDEO_REBUFFER_BUFFER_MS
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        return ExoPlayer.Builder(this, renderersFactory)
            .setLoadControl(loadControl)
            .build()
    }

    private fun preloadNextPlaylistVideo() {
        if (!playlistPlaybackActive || playlistItems.size <= 1 || awaitingSelectionReturn) return

        val nextIndex = ((currentPlaylistIndex + 1) % playlistItems.size + playlistItems.size) % playlistItems.size
        val nextItem = playlistItems.getOrNull(nextIndex) ?: return
        if (nextItem.type != "video") {
            releasePreloadedPlaylistPlayer()
            return
        }
        val playableItemUrl = playlistMediaCache.localUri(nextItem.url) ?: nextItem.url
        if (preloadedPlaylistUrl == playableItemUrl && preloadedPlaylistPlayer != null) return

        releasePreloadedPlaylistPlayer()
        runCatching {
            buildPlaylistExoPlayer().also { exoPlayer ->
                exoPlayer.setMediaItem(MediaItem.fromUri(playableItemUrl))
                exoPlayer.prepare()
                exoPlayer.playWhenReady = false
                preloadedPlaylistPlayer = exoPlayer
                preloadedPlaylistUrl = playableItemUrl
            }
        }.onFailure {
            releasePreloadedPlaylistPlayer()
        }
    }

    private fun takePreloadedPlaylistPlayer(url: String): ExoPlayer? {
        if (preloadedPlaylistUrl != url) return null

        return preloadedPlaylistPlayer.also {
            preloadedPlaylistPlayer = null
            preloadedPlaylistUrl = ""
        }
    }

    private fun releasePreloadedPlaylistPlayer() {
        preloadedPlaylistPlayer?.release()
        preloadedPlaylistPlayer = null
        preloadedPlaylistUrl = ""
    }

    private fun applyPlaylistTransition(view: View, item: PlaylistItem) {
        val rawDuration = item.transitionDurationMs.coerceIn(0, 10_000).toLong()
        val duration = if (item.transitionStyle == "none") 0L else rawDuration.coerceAtLeast(450L)
        val travelX = (binding.root.width.takeIf { it > 0 } ?: 720).toFloat()
        val travelY = (binding.root.height.takeIf { it > 0 } ?: 480).toFloat()
        view.animate().cancel()
        view.alpha = 1f
        view.translationX = 0f
        view.translationY = 0f
        view.rotationY = 0f
        view.scaleX = 1f
        view.scaleY = 1f
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            view.setRenderEffect(null)
        }

        if (duration <= 0L || item.transitionStyle == "none") return

        when (item.transitionStyle) {
            "aponti_smooth" -> {
                view.alpha = 0f
                view.scaleX = 1.045f
                view.scaleY = 1.045f
                showApontiSmoothOverlay(duration) {
                    view.animate()
                        .alpha(1f)
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration((duration * 0.62f).toLong().coerceAtLeast(180L))
                        .setInterpolator(DecelerateInterpolator(1.8f))
                        .start()
                }
            }
            "slide" -> {
                val travel = travelX * 0.08f
                view.translationX = travel
                view.alpha = 0.88f
                view.animate()
                    .translationX(0f)
                    .alpha(1f)
                    .setDuration(duration)
                    .setInterpolator(DecelerateInterpolator(1.6f))
                    .start()
            }
            "push" -> {
                view.translationX = travelX * 0.18f
                view.alpha = 0.96f
                view.animate()
                    .translationX(0f)
                    .alpha(1f)
                    .setDuration(duration)
                    .setInterpolator(DecelerateInterpolator(1.8f))
                    .start()
            }
            "wipe" -> {
                view.translationY = travelY * 0.14f
                view.alpha = 0f
                view.animate()
                    .translationY(0f)
                    .alpha(1f)
                    .setDuration(duration)
                    .setInterpolator(DecelerateInterpolator(1.7f))
                    .start()
            }
            "zoom" -> {
                view.alpha = 0f
                view.scaleX = 1.08f
                view.scaleY = 1.08f
                view.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(duration)
                    .setInterpolator(DecelerateInterpolator(1.7f))
                    .start()
            }
            "cross_zoom" -> {
                view.alpha = 0f
                view.scaleX = 0.92f
                view.scaleY = 0.92f
                view.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(duration)
                    .setInterpolator(DecelerateInterpolator(1.9f))
                    .start()
            }
            "flip" -> {
                view.cameraDistance = resources.displayMetrics.density * 8000f
                view.rotationY = -58f
                view.alpha = 0f
                view.animate()
                    .rotationY(0f)
                    .alpha(1f)
                    .setDuration(duration)
                    .setInterpolator(DecelerateInterpolator(1.8f))
                    .start()
            }
            "blur" -> {
                view.alpha = 0f
                view.scaleX = 1.035f
                view.scaleY = 1.035f
                view.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(duration)
                    .setInterpolator(DecelerateInterpolator(1.5f))
                    .start()
            }
            else -> {
                view.alpha = 0f
                view.animate()
                    .alpha(1f)
                    .setDuration(duration)
                    .setInterpolator(DecelerateInterpolator(1.4f))
                    .start()
            }
        }
    }

    private fun orientationMode(): String {
        return intent.getStringExtra(EXTRA_ORIENTATION).orEmpty().ifBlank { "portrait" }
    }

    private fun normalizedOrientation(value: String?): String? {
        val orientation = value.orEmpty()
        return if (orientation == "landscape" || orientation == "portrait" || orientation == "portrait_inverted") {
            orientation
        } else {
            null
        }
    }

    private fun isPortraitOrientation(): Boolean {
        return orientationMode() != "landscape"
    }

    private fun isPortraitInvertedOrientation(): Boolean {
        return orientationMode() == "portrait_inverted"
    }

    private fun portraitContentRotation(): Float {
        return if (isPortraitInvertedOrientation()) -90f else 90f
    }

    private fun portraitBackdropRotation(): Float {
        return -portraitContentRotation()
    }

    private fun preparePlaylistSurfaceLayout(view: View, syncWidgetMode: Boolean = true) {
        val isPortrait = isPortraitOrientation()

        if (!isPortrait) {
            view.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            view.rotation = 0f
            view.scaleX = 1f
            view.scaleY = 1f
            view.translationX = 0f
            view.translationY = 0f
            view.requestLayout()
            if (syncWidgetMode) applyWidgetContentMode(visible = binding.widgetBar.visibility == View.VISIBLE)
            return
        }

        binding.root.post {
            val surfaceW = binding.playbackSurface.width
            val surfaceH = binding.playbackSurface.height
            if (surfaceW <= 0 || surfaceH <= 0) return@post

            view.layoutParams = FrameLayout.LayoutParams(surfaceH, surfaceW).apply {
                gravity = Gravity.CENTER
            }
            view.pivotX = surfaceH / 2f
            view.pivotY = surfaceW / 2f
            view.rotation = portraitContentRotation()
            view.scaleX = 1f
            view.scaleY = 1f
            view.translationX = 0f
            view.translationY = 0f
            view.requestLayout()
            if (syncWidgetMode) applyWidgetContentMode(visible = binding.widgetBar.visibility == View.VISIBLE)
        }
    }

    private fun retryPlayback() {
        val channelId = intent.getLongExtra(EXTRA_CHANNEL_ID, -1L)
        if (channelId <= 0L || awaitingSelectionReturn || statusPollInFlight) return
        statusPollInFlight = true

        thread {
            runCatching { apiClient.fetchChannelStatus(channelId) }
                .onSuccess { status ->
                    runOnUiThread {
                        if (awaitingSelectionReturn) return@runOnUiThread
                        val freshUrl = status.playbackUrl?.ifBlank { null } ?: status.streamUrl
                        val freshPlaylist = parsePlaylistItems(status.playlistItemsJson.orEmpty())
                        applyOfficialAppConfigExtras(status.officialAppBrowserRotation)
                        applyPlaylistNotificationExtras(status.playlistNotificationSound)
                        val hasWebOnly = status.officialAppBrowserRotation?.let {
                            it.enabled && it.webOnly && !it.pageUrl.isNullOrBlank()
                        } ?: false
                        if (status.status == "running" && (!freshUrl.isNullOrBlank() || freshPlaylist.isNotEmpty() || hasWebOnly)) {
                            playlistItems = freshPlaylist
                            synchronizePlaylistMediaCache(playlistItems)
                            stopOfficialAppRotation()
                            releasePlayer()
                            releasePreloadedPlaylistPlayer()
                            if (hasWebOnly) {
                                initializePlayer()
                            } else if (freshPlaylist.isNotEmpty()) {
                                playlistPlaybackActive = true
                                maybePlayPlaylistNotification()
                                startPlaylistItem(currentPlaylistIndex + 1)
                            } else {
                                initializePlayer(overrideUrl = freshUrl)
                            }
                        } else {
                            returnToSelection(suppressCurrentPlayback = false)
                        }
                    }
                }
                .onFailure {
                    runOnUiThread {
                        if (awaitingSelectionReturn) return@runOnUiThread
                        if (retryCount < MAX_RETRIES) {
                            retryHandler.postDelayed(retryRunnable, RETRY_DELAY_MS)
                        } else {
                            returnToSelection()
                        }
                    }
                }
            statusPollInFlight = false
        }
    }

    private fun releasePlayer() {
        playerReady = false
        binding.playerView.player = null
        player?.release()
        player = null
    }

    private val webPageLogin = WebPageLogin()

    private fun configureBrowserView() {
        binding.browserView.apply {
            webViewClient = object : WebViewClient() {
                override fun onReceivedHttpAuthRequest(view: WebView, handler: android.webkit.HttpAuthHandler, host: String, realm: String) {
                    webPageLogin.httpAuth(view, handler, host,
                        intent.getStringExtra(EXTRA_OFFICIAL_APP_PAGE_URL).orEmpty(),
                        intent.getStringExtra(EXTRA_OFFICIAL_APP_LOGIN))
                }

                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    browserPageLoaded = false
                    browserPreparedForDisplay = false
                }

                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    webPageLogin.pageFinished(view, url,
                        intent.getStringExtra(EXTRA_OFFICIAL_APP_PAGE_URL).orEmpty(),
                        intent.getStringExtra(EXTRA_OFFICIAL_APP_LOGIN))
                    val isPortraitBrowser = isPortraitOrientation()
                    val viewportContent = if (isPortraitBrowser) {
                        "width=390, initial-scale=1, maximum-scale=1, user-scalable=no, viewport-fit=cover"
                    } else {
                        "width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no, viewport-fit=cover"
                    }
                    val portraitFitJs = if (isPortraitBrowser) {
                        """
                            document.documentElement.style.transform = '';
                            document.documentElement.style.transformOrigin = '';
                            document.body.style.transform = '';
                            document.body.style.transformOrigin = '';
                        """.trimIndent()
                    } else {
                        ""
                    }
                    view.evaluateJavascript("""
                        (function() {
                            function ensureViewport() {
                                var meta = document.querySelector('meta[name="viewport"]');
                                if (!meta) {
                                    meta = document.createElement('meta');
                                    meta.name = 'viewport';
                                    document.head.appendChild(meta);
                                }
                                meta.content = '${viewportContent}';
                            }
                            function normalizeLayout() {
                                document.documentElement.style.margin = '0';
                                document.documentElement.style.padding = '0';
                                document.documentElement.style.width = '100%';
                                document.documentElement.style.height = '100%';
                                document.documentElement.style.overflow = 'hidden';
                                document.body.style.margin = '0';
                                document.body.style.padding = '0';
                                document.body.style.width = '100%';
                                document.body.style.minHeight = '100%';
                                document.body.style.overflow = 'hidden';
                            }
                            function lockAll() {
                                window.scrollTo(0, 0);
                                document.querySelectorAll('*').forEach(function(el) {
                                    if (el.scrollTop !== 0) el.scrollTop = 0;
                                    if (el.scrollLeft !== 0) el.scrollLeft = 0;
                                });
                                if (document.activeElement && document.activeElement !== document.body) {
                                    document.activeElement.blur();
                                }
                            }
                            ensureViewport();
                            normalizeLayout();
                            ${portraitFitJs}
                            lockAll();
                            setTimeout(lockAll, 300);
                            setTimeout(lockAll, 800);
                            setTimeout(lockAll, 1500);
                            if (!window.__softexFocusBlocked) {
                                window.__softexFocusBlocked = true;
                                Element.prototype.scrollIntoView = function() {};
                                document.addEventListener('focus', function(e) {
                                    if (e.target && e.target !== document.body) {
                                        e.target.blur();
                                        window.scrollTo(0, 0);
                                        document.querySelectorAll('*').forEach(function(el) {
                                            el.scrollTop = 0;
                                            el.scrollLeft = 0;
                                        });
                                    }
                                }, true);
                            }
                        })();
                    """.trimIndent(), null)

                    warmUpBrowserMedia()
                    settings.cacheMode = WebSettings.LOAD_DEFAULT
                    reloadBrowserMediaIfMissing()
                    postDelayed({ reloadBrowserMediaIfMissing() }, 800)
                    postDelayed({ resumeBrowserMedia() }, 1200)
                    if (isOfficialAppWebOnly() && showingBrowser) {
                        browserPageLoaded = true
                        browserPreparedForDisplay = true
                        prepareBrowserViewForDisplay {
                            binding.browserView.alpha = 1f
                            binding.browserView.bringToFront()
                            bringPlayerOverlaysToFront()
                        }
                    } else {
                        prepareBrowserViewInBackground {
                            browserPageLoaded = true
                            browserPreparedForDisplay = true
                            if (pendingBrowserSwitch && browserRotationEnabled && !showingBrowser) {
                                pendingBrowserSwitch = false
                                showBrowserOverlay()
                            }
                        }
                    }
                }
            }
            webChromeClient = WebChromeClient()
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.mediaPlaybackRequiresUserGesture = false
            settings.loadsImagesAutomatically = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.builtInZoomControls = false
            settings.displayZoomControls = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                settings.offscreenPreRaster = true
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true)
            }
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            setBackgroundColor(android.graphics.Color.BLACK)
            isFocusable = false
            isFocusableInTouchMode = false
            isClickable = false
            setOnKeyListener { _, _, _ -> true }
            visibility = View.VISIBLE
            alpha = 0f
        }

        resetVideoPresentation()
    }

    private fun startOfficialAppRotationIfNeeded() {
        if (!playerReady || rotationStarted) return
        rotationStarted = true
        rotationHandler.removeCallbacks(showBrowserRunnable)
        rotationHandler.removeCallbacks(showVideoRunnable)
        showingBrowser = false
        pendingBrowserSwitch = false

        val rotationEnabled = isOfficialAppWebDisplayEnabled()
        val pageUrl = intent.getStringExtra(EXTRA_OFFICIAL_APP_PAGE_URL).orEmpty()
        browserPageUrl = pageUrl
        useVideoEndTrigger = shouldUseDirectVideoPlayback()
        browserRotationEnabled = isOfficialAppPlayback() && rotationEnabled && pageUrl.isNotBlank()

        if (!browserRotationEnabled) {
            if (!playlistPlaybackActive) {
                showVideoSurface(immediate = true)
            }
            return
        }

        if (binding.browserView.url != pageUrl) {
            browserPageLoaded = false
            browserPreparedForDisplay = false
            pendingBrowserSwitch = false
            binding.browserView.settings.cacheMode = WebSettings.LOAD_DEFAULT
            binding.browserView.clearCache(true)
            prepareBrowserViewInBackground()
            binding.browserView.loadUrl(pageUrl)
        } else {
            warmUpBrowserMedia()
            prepareBrowserViewInBackground {
                browserPreparedForDisplay = true
            }
        }
        if (isOfficialAppWebOnly()) {
            showBrowserOverlay()
        } else if (!useVideoEndTrigger) {
            scheduleBrowserSwitch()
        }
    }

    private fun stopOfficialAppRotation() {
        rotationHandler.removeCallbacks(showBrowserRunnable)
        rotationHandler.removeCallbacks(showVideoRunnable)
        rotationStarted = false
        showingBrowser = false
        pendingBrowserSwitch = false
        pendingVideoSwitch = false
    }

    private fun startStatusPolling() {
        statusHandler.removeCallbacks(checkBroadcastStatusRunnable)
        statusHandler.postDelayed(checkBroadcastStatusRunnable, STATUS_POLL_INTERVAL_MS)
        presentationHandler.removeCallbacks(checkPresentationStatusRunnable)
        presentationHandler.post(checkPresentationStatusRunnable)
    }

    private fun stopStatusPolling() {
        statusHandler.removeCallbacks(checkBroadcastStatusRunnable)
        presentationHandler.removeCallbacks(checkPresentationStatusRunnable)
    }

    private fun pollPresentationStatus() {
        val channelId = intent.getLongExtra(EXTRA_CHANNEL_ID, -1L)
        if (channelId <= 0L || awaitingSelectionReturn || presentationPollInFlight) return
        presentationPollInFlight = true
        thread {
            runCatching { apiClient.fetchPresentationControl(channelId) }
                .onSuccess { control -> runOnUiThread { applyPresentationControl(control) } }
            presentationPollInFlight = false
        }
    }

    private fun pollBroadcastStatus() {
        val channelId = intent.getLongExtra(EXTRA_CHANNEL_ID, -1L)
        if (channelId <= 0L || awaitingSelectionReturn) return

        thread {
            runCatching { apiClient.fetchChannelStatus(channelId) }
                .onSuccess { status ->
                    ApontiForegroundState.setKeepOpenEnabled(
                        applicationContext,
                        status.keepAppForegroundEnabled
                    )
                    localPowerScheduleManager.remember(
                        channelId,
                        intent.getStringExtra(EXTRA_CHANNEL_NAME),
                        status.powerSchedule
                    )
                    if (status.status != "running") {
                        runOnUiThread {
                            if (!awaitingSelectionReturn) {
                                returnToSelection(suppressCurrentPlayback = false)
                            }
                        }
                        return@onSuccess
                    }

                    val playableUrl = status.playbackUrl?.ifBlank { null }
                        ?: status.streamUrl?.ifBlank { null }
                        ?: intent.getStringExtra(EXTRA_DIRECT_VIDEO_URL)?.ifBlank { null }
                    val freshPlaylist = parsePlaylistItems(status.playlistItemsJson.orEmpty())
                    val hasWebOnly = status.officialAppBrowserRotation?.let {
                        it.enabled && it.webOnly && !it.pageUrl.isNullOrBlank()
                    } ?: false
                    if (playableUrl.isNullOrBlank() && freshPlaylist.isEmpty() && !hasWebOnly) {
                        runOnUiThread {
                            if (!awaitingSelectionReturn) {
                            returnToSelection(suppressCurrentPlayback = false)
                            }
                        }
                        return@onSuccess
                    }

                    val configChanged = status.configVersion.orEmpty().isNotBlank() &&
                        status.configVersion.orEmpty() != currentConfigVersion
                    val urlChanged = !playlistPlaybackActive && playableUrl != currentRemotePlaybackUrl
                    val freshPlaylistJson = status.playlistItemsJson.orEmpty()
                    val playlistChanged = freshPlaylistJson.isNotBlank() &&
                        freshPlaylistJson != currentPlaylistItemsJson
                    val browserConfigChanged = status.officialAppBrowserRotation?.let { browser ->
                        val currentEnabled = intent.getBooleanExtra(EXTRA_OFFICIAL_APP_ROTATION_ENABLED, false)
                        browser.enabled != currentEnabled ||
                            (browser.enabled && (
                                browser.webOnly != intent.getBooleanExtra(EXTRA_OFFICIAL_APP_WEB_ONLY, false) ||
                                    browser.pageUrl.orEmpty() != intent.getStringExtra(EXTRA_OFFICIAL_APP_PAGE_URL).orEmpty() ||
                                    browser.loginJson.orEmpty() != intent.getStringExtra(EXTRA_OFFICIAL_APP_LOGIN).orEmpty() ||
                                    (browser.rotationTrigger ?: "time_interval") != intent.getStringExtra(EXTRA_OFFICIAL_APP_ROTATION_TRIGGER).orEmpty().ifBlank { "time_interval" } ||
                                    browser.switchIntervalSeconds != intent.getIntExtra(EXTRA_OFFICIAL_APP_SWITCH_INTERVAL_SECONDS, 300) ||
                                    browser.pageDurationSeconds != intent.getIntExtra(EXTRA_OFFICIAL_APP_PAGE_DURATION_SECONDS, 15)
                            ))
                    } ?: false

                    if (configChanged || urlChanged || playlistChanged || browserConfigChanged) {
                        runOnUiThread {
                            if (!awaitingSelectionReturn) {
                                restartPlaybackFromStatus(
                                    playableUrl.orEmpty(),
                                    status.configVersion.orEmpty(),
                                    status.orientation,
                                    freshPlaylist,
                                    freshPlaylistJson,
                                    status.officialAppBrowserRotation,
                                    status.playlistSync,
                                    status.playlistNotificationSound,
                                    status.officialAppWidgetBar
                                )
                            }
                        }
                    } else {
                        runOnUiThread {
                            // Presentation commands must be consumed even while a video is
                            // preparing. Apply them before sync so Previous/Next use the slide
                            // currently shown instead of the server clock position.
                            status.presentationControl?.let(::applyPresentationControl)
                            if (!playerReady) return@runOnUiThread
                            status.officialAppWidgetBar?.let { widgetBar ->
                                val signature = widgetBar.signature()
                                if (signature != widgetBarConfigSignature) {
                                    applyWidgetBarExtras(widgetBar)
                                    widgetBarConfigSignature = signature
                                    configureWidgetBar()
                                }
                            }
                            status.playlistSync?.let { sync ->
                                applyPlaylistSyncExtras(sync)
                                if (applyPlaylistResyncRequest(sync)) {
                                    return@runOnUiThread
                                }
                                if (playlistPlaybackActive && currentPlaylistItem()?.type == "video") {
                                    player?.let { resyncReadyPlaylistVideo(it, currentPlaylistItem()!!, force = true) }
                                }
                            }
                        }
                        if (playerReady) reportPlayerPresence("playing")
                    }
                }
                .onFailure {
                    prepareForLocalPowerSleep()
                    val result = localPowerScheduleManager.evaluate(serverAvailable = false)
                    if (result.shouldEnterStandby) {
                        if (result.success) {
                            Log.i(TAG, "Desligamento local em reproducao executado. Mantendo a tela sem novas atualizacoes.")
                            return@onFailure
                        }
                        runOnUiThread {
                            enterLocalPowerStandbyFromPlayer(result)
                        }
                    }
                }
        }
    }

    private fun prepareForLocalPowerSleep() {
        val latch = CountDownLatch(1)
        runOnUiThread {
            runCatching {
                window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                binding.root.keepScreenOn = false
                binding.playerView.keepScreenOn = false
                binding.playlistImageView.keepScreenOn = false
                binding.previousPlaylistImageView.keepScreenOn = false
                binding.browserView.keepScreenOn = false
            }
            latch.countDown()
        }
        latch.await(350L, TimeUnit.MILLISECONDS)
    }

    private fun enterLocalPowerStandbyFromPlayer(initialResult: LocalPowerScheduleManager.EvaluationResult) {
        if (localPowerShutdownPending) return

        localPowerShutdownPending = true
        awaitingSelectionReturn = true
        stopOfficialAppRotation()
        retryHandler.removeCallbacksAndMessages(null)
        statusHandler.removeCallbacksAndMessages(null)
        binding.root.animate().cancel()
        binding.playerView.animate().cancel()
        binding.playlistImageView.animate().cancel()
        binding.previousPlaylistImageView.animate().cancel()
        binding.browserView.animate().cancel()
        binding.browserView.stopLoading()
        binding.browserView.loadUrl("about:blank")
        releasePlayer()
        releasePreloadedPlaylistPlayer()
        releasePlaylistNotificationPlayer()
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding.root.keepScreenOn = false
        binding.playerView.keepScreenOn = false
        binding.errorText.visibility = View.GONE
        binding.downloadOverlay.visibility = View.VISIBLE
        binding.downloadTitle.text = getString(R.string.power_schedule_standby_title)
        binding.downloadMessage.text = getString(R.string.power_schedule_standby_retrying)

        thread {
            Thread.sleep(900L)
            val finalResult = if (initialResult.success) {
                initialResult
            } else {
                localPowerScheduleManager.evaluate(serverAvailable = false)
            }
            Log.i(TAG, "Desligamento local em reproducao: due=${finalResult.due} success=${finalResult.success}")
            if (finalResult.success) {
                return@thread
            }
            runOnUiThread {
                binding.downloadMessage.text = getString(R.string.power_schedule_standby_retrying)
                if (!DevicePlatform.isFireTv()) {
                    moveTaskToBack(true)
                }
            }
        }
    }

    private fun restartPlaybackFromStatus(
        playableUrl: String,
        configVersion: String,
        orientation: String? = null,
        freshPlaylist: List<PlaylistItem> = emptyList(),
        freshPlaylistJson: String = "",
        officialConfig: OfficialAppBrowserRotation? = null,
        playlistSync: PlaylistSync? = null,
        notificationSound: PlaylistNotificationSound? = null,
        widgetBar: OfficialAppWidgetBar? = null
    ) {
        if (restartPlaybackPending) return

        restartPlaybackPending = true
        if (freshPlaylistJson.isNotBlank()) {
            playlistItems = freshPlaylist
            synchronizePlaylistMediaCache(playlistItems)
            currentPlaylistIndex = 0
            currentPlaylistItemsJson = freshPlaylistJson.ifBlank { playlistItemsToJson(freshPlaylist) }
            intent.putExtra(EXTRA_PLAYLIST_ITEMS_JSON, currentPlaylistItemsJson)
        }
        currentRemotePlaybackUrl = playableUrl.ifBlank { freshPlaylist.firstOrNull()?.url.orEmpty() }
        currentConfigVersion = configVersion.ifBlank { currentConfigVersion }
        normalizedOrientation(orientation)?.let { intent.putExtra(EXTRA_ORIENTATION, it) }
        if (playableUrl.isNotBlank()) {
            intent.putExtra(EXTRA_PLAYBACK_URL, playableUrl)
            intent.putExtra(EXTRA_DIRECT_VIDEO_URL, playableUrl)
        }
        intent.putExtra(EXTRA_CONFIG_VERSION, currentConfigVersion)
        applyOfficialAppConfigExtras(officialConfig)
        applyPlaylistSyncExtras(playlistSync)
        applyPlaylistNotificationExtras(notificationSound)
        applyWidgetBarExtras(widgetBar)
        widgetBarConfigSignature = widgetBar?.signature().orEmpty()
        configureWidgetBar()

        stopOfficialAppRotation()
        releasePlayer()
        releasePreloadedPlaylistPlayer()
        retryHandler.removeCallbacks(playbackReadyTimeoutRunnable)
        directLocalRetryAttempted = false
        retryCount = 0
        playerReady = false
        if (isOfficialAppWebOnly()) {
            hideDownloadOverlay()
        } else {
            showDownloadOverlay(getString(R.string.player_downloading_video))
        }
        retryHandler.postDelayed({
            restartPlaybackPending = false
            if (!awaitingSelectionReturn) {
                if (isOfficialAppWebOnly()) {
                    initializePlayer()
                } else if (playlistItems.isNotEmpty()) {
                    playlistPlaybackActive = true
                    maybePlayPlaylistNotification()
                    val syncPosition = synchronizedPlaylistPosition()
                    if (syncPosition != null) {
                        startPlaylistItem(syncPosition.index, syncPosition.elapsedMs, syncPosition.remainingMs)
                    } else {
                        startPlaylistItem(0)
                    }
                } else {
                    initializePlayer(overrideUrl = playableUrl)
                }
            }
        }, PLAYBACK_RESTART_DELAY_MS)
    }

    private fun scheduleBrowserSwitch() {
        val intervalMs = intent.getIntExtra(EXTRA_OFFICIAL_APP_SWITCH_INTERVAL_SECONDS, 300).coerceAtLeast(1) * 1000L
        rotationHandler.postDelayed(showBrowserRunnable, intervalMs)
    }

    private fun scheduleVideoReturn() {
        val durationMs = intent.getIntExtra(EXTRA_OFFICIAL_APP_PAGE_DURATION_SECONDS, 15).coerceAtLeast(1) * 1000L
        rotationHandler.postDelayed(showVideoRunnable, durationMs)
    }

    private fun warmUpBrowserMedia() {
        binding.browserView.evaluateJavascript(
            """
                (function() {
                    document.querySelectorAll('video').forEach(function(video) {
                        try {
                            video.preload = 'auto';
                            video.setAttribute('preload', 'auto');
                            if (video.readyState < 3) video.load();
                        } catch (e) {}
                    });
                })();
            """.trimIndent(),
            null
        )
    }

    private fun resumeBrowserMedia() {
        binding.browserView.evaluateJavascript(
            """
                (function() {
                    function tryPlayVideos() {
                        document.querySelectorAll('video').forEach(function(video) {
                            try {
                                video.preload = 'auto';
                                video.setAttribute('preload', 'auto');
                                if (video.readyState < 2) video.load();
                                var playPromise = video.play();
                                if (playPromise && playPromise.catch) playPromise.catch(function(){});
                            } catch (e) {}
                        });
                    }
                    tryPlayVideos();
                    [350, 900, 1800, 3200].forEach(function(delay) {
                        setTimeout(tryPlayVideos, delay);
                    });
                })();
            """.trimIndent(),
            null
        )
    }

    private fun reloadBrowserMediaIfMissing() {
        binding.browserView.evaluateJavascript(
            """
                (function() {
                    document.querySelectorAll('video').forEach(function(video) {
                        try {
                            if (!video.currentSrc && !video.getAttribute('src') && video.querySelectorAll('source').length === 0 && typeof window.checkVideoVersion === 'function') {
                                window.checkVideoVersion();
                            }
                        } catch(e) {}
                    });
                })();
            """.trimIndent(),
            null
        )
    }

    private fun pauseBrowserMedia() {
        binding.browserView.evaluateJavascript(
            """
                (function() {
                    document.querySelectorAll('video').forEach(function(video) {
                        try { video.pause(); } catch (e) {}
                    });
                })();
            """.trimIndent(),
            null
        )
    }

    private fun showBrowserOverlay() {
        if (showingBrowser || !browserRotationEnabled) return
        if (!playerReady) return
        if (!isOfficialAppWebOnly() && (!browserPageLoaded || !browserPreparedForDisplay)) {
            pendingBrowserSwitch = true
            if (browserPageUrl.isNotBlank() && binding.browserView.url.isNullOrBlank()) {
                prepareBrowserViewInBackground()
                binding.browserView.loadUrl(browserPageUrl)
            } else {
                prepareBrowserViewInBackground {
                    browserPreparedForDisplay = true
                    if (pendingBrowserSwitch && browserPageLoaded && !showingBrowser) {
                        pendingBrowserSwitch = false
                        showBrowserOverlay()
                    }
                }
            }
            return
        }

        rotationHandler.removeCallbacks(showVideoRunnable)
        hideHud()
        showingBrowser = true
        if (isOfficialAppWebOnly()) {
            // The page may contain video. Free the hardware decoder held by the
            // playlist so Android WebView can render that media reliably.
            hideDownloadOverlay()
            retryHandler.removeCallbacks(nextPlaylistItemRunnable)
            retryHandler.removeCallbacks(playbackReadyTimeoutRunnable)
            retryHandler.removeCallbacks(directVideoTimeoutRunnable)
            releasePreloadedPlaylistPlayer()
            releasePlayer()
            binding.playerView.visibility = View.INVISIBLE
            binding.playlistImageView.visibility = View.INVISIBLE
            binding.previousPlaylistImageView.visibility = View.INVISIBLE
            playerSplashDismissed = true
            releasePlayerSplashVideo()
            binding.playerSplashVideo.visibility = View.INVISIBLE
            binding.playerSplashOverlay.visibility = View.GONE
        }
        val resetJs = "window.scrollTo(0,0); document.querySelectorAll('*').forEach(function(el){el.scrollTop=0;el.scrollLeft=0;}); if(document.activeElement && document.activeElement !== document.body) document.activeElement.blur();"
        binding.browserView.evaluateJavascript(resetJs, null)
        reloadBrowserMediaIfMissing()
        resumeBrowserMedia()
        binding.browserView.postDelayed({ binding.browserView.evaluateJavascript(resetJs, null) }, 400)
        binding.browserView.postDelayed({ resumeBrowserMedia() }, 450)
        prepareBrowserViewForDisplay {
            if (showingBrowser) {
                if (isOfficialAppWebOnly()) {
                    // There is no outgoing media surface after Stop -> Play.
                    // Showing directly avoids a transition ending on a black surface.
                    binding.playerView.visibility = View.INVISIBLE
                    binding.playlistImageView.visibility = View.INVISIBLE
                    binding.previousPlaylistImageView.visibility = View.INVISIBLE
                    binding.browserView.visibility = View.VISIBLE
                    binding.browserView.alpha = 1f
                    binding.browserView.bringToFront()
                    bringPlayerOverlaysToFront()
                    finishTransition(showBrowser = true)
                } else {
                    applyTransition(showBrowser = true)
                    scheduleVideoReturn()
                }
            }
        }
    }

    private fun showVideoSurface(immediate: Boolean = false) {
        rotationHandler.removeCallbacks(showBrowserRunnable)
        rotationHandler.removeCallbacks(showVideoRunnable)
        hideHud()
        pauseBrowserMedia()

        if (playlistPlaybackActive && useVideoEndTrigger && !immediate) {
            pendingVideoSwitch = false
            showingBrowser = false
            binding.browserView.clearFocus()
            startPlaylistItem(0)
            mediaSurfaceView().requestFocus()
            applyTransition(showBrowser = false)
            return
        }

        val currentPlayer = player
        if (currentPlayer != null && !immediate) {
            pendingVideoSwitch = true
            if (useVideoEndTrigger) {
                currentPlayer.seekTo(0)
            }
            currentPlayer.playWhenReady = true
            currentPlayer.play()

            if (!currentPlayer.isPlaying) {
                return
            }
        }

        completeVideoSurfaceSwitch(immediate = immediate)
    }

    private fun completeVideoSurfaceSwitch(immediate: Boolean = false) {
        pendingVideoSwitch = false
        showingBrowser = false
        binding.browserView.clearFocus()
        mediaSurfaceView().requestFocus()
        applyTransition(showBrowser = false, immediate = immediate)

        if (!useVideoEndTrigger && !immediate) {
            scheduleBrowserSwitch()
        }
    }

    private fun shouldUseDirectVideoPlayback(): Boolean {
        return isOfficialAppPlayback() &&
            isOfficialAppWebDisplayEnabled() &&
            !isOfficialAppWebOnly() &&
            intent.getStringExtra(EXTRA_OFFICIAL_APP_ROTATION_TRIGGER).orEmpty() == "video_end"
    }

    private fun shouldLoopVideoPlayback(): Boolean {
        return isOfficialAppPlayback() &&
            isOfficialAppWebDisplayEnabled() &&
            intent.getStringExtra(EXTRA_OFFICIAL_APP_ROTATION_TRIGGER).orEmpty() == "time_interval"
    }

    private fun shouldRepeatCurrentVideo(): Boolean {
        if (!playlistPlaybackActive) return shouldLoopVideoPlayback()
        if (playlistItems.size > 1) return currentPlaylistItem()?.videoDurationMode != "video_end"
        if (playlistItems.size != 1) return false
        return intent.getStringExtra(EXTRA_OFFICIAL_APP_ROTATION_TRIGGER).orEmpty() != "video_end"
    }

    private fun shouldAdvancePlaylistVideoOnEnded(): Boolean {
        val item = currentPlaylistItem() ?: return true
        return item.type != "video" || item.videoDurationMode == "video_end"
    }

    private fun shouldOpenBrowserAtPlaylistBoundary(): Boolean {
        return browserRotationEnabled &&
            useVideoEndTrigger &&
            playlistPlaybackActive &&
            playlistItems.size > 1 &&
            currentPlaylistIndex >= playlistItems.lastIndex
    }

    private fun applyOfficialAppConfigExtras(config: OfficialAppBrowserRotation?) {
        if (config == null) return

        intent.putExtra(EXTRA_OFFICIAL_APP_PAGE_URL, config.pageUrl)
        val loginChanged = intent.getStringExtra(EXTRA_OFFICIAL_APP_LOGIN) != config.loginJson
        intent.putExtra(EXTRA_OFFICIAL_APP_LOGIN, config.loginJson)
        if (loginChanged) binding.browserView.reload()
        intent.putExtra(EXTRA_OFFICIAL_APP_ROTATION_ENABLED, config.enabled)
        intent.putExtra(EXTRA_OFFICIAL_APP_WEB_ONLY, config.webOnly)
        intent.putExtra(EXTRA_OFFICIAL_APP_ROTATION_TRIGGER, config.rotationTrigger ?: "time_interval")
        intent.putExtra(EXTRA_OFFICIAL_APP_SWITCH_INTERVAL_SECONDS, config.switchIntervalSeconds)
        intent.putExtra(EXTRA_OFFICIAL_APP_PAGE_DURATION_SECONDS, config.pageDurationSeconds)
        intent.putExtra(EXTRA_OFFICIAL_APP_TRANSITION_STYLE, config.transitionStyle ?: "blur")
        intent.putExtra(EXTRA_OFFICIAL_APP_TRANSITION_DURATION_MS, config.transitionDurationMs)
        intent.putExtra(EXTRA_DIRECT_VIDEO_URL, config.directVideoUrl)
    }

    private fun applyPlaylistSyncExtras(sync: PlaylistSync?) {
        if (sync == null) return

        playlistSyncEnabled = sync.enabled
        playlistSyncStartedAtMs = sync.startedAtMs
        updatePlaylistServerClockOffset(sync.serverTimeMs)
        intent.putExtra(EXTRA_PLAYLIST_SYNC_ENABLED, playlistSyncEnabled)
        intent.putExtra(EXTRA_PLAYLIST_SYNC_STARTED_AT_MS, playlistSyncStartedAtMs)
        intent.putExtra(EXTRA_PLAYLIST_SYNC_SERVER_TIME_MS, sync.serverTimeMs)
    }

    private fun applyPresentationControl(control: PresentationControl) {
        presentationModeEnabled = control.enabled
        if (!control.enabled) {
            presentationPaused = false
            presentationCommandVersion = control.commandVersion
            return
        }
        if (!playlistPlaybackActive || control.commandVersion <= presentationCommandVersion) return

        presentationCommandVersion = control.commandVersion
        when (control.command) {
            "pause" -> pausePresentation()
            "play" -> resumePresentation()
            "next" -> {
                presentationPaused = control.paused
                startPlaylistItem(currentPlaylistIndex + 1)
                if (presentationPaused) pausePresentation()
            }
            "previous" -> {
                presentationPaused = control.paused
                startPlaylistItem(currentPlaylistIndex - 1)
                if (presentationPaused) pausePresentation()
            }
            "pointer_hide" -> binding.presentationPointer.visibility = View.GONE
            else -> applyExtendedPresentationCommand(control.command, control.paused)
        }
    }

    private fun applyExtendedPresentationCommand(command: String?, paused: Boolean) {
        if (command.isNullOrBlank()) return
        if (command.startsWith("show:")) {
            val itemId = command.substringAfter(':').toLongOrNull() ?: return
            val targetIndex = playlistItems.indexOfFirst { it.id == itemId }
            if (targetIndex >= 0) {
                presentationPaused = paused
                startPlaylistItem(targetIndex)
                if (presentationPaused) pausePresentation()
            }
            return
        }
        if (command.startsWith("pointer:")) {
            val values = command.split(':')
            val x = values.getOrNull(1)?.toFloatOrNull() ?: return
            val y = values.getOrNull(2)?.toFloatOrNull() ?: return
            binding.presentationPointer.moveTo(x, y)
            binding.presentationPointer.bringToFront()
        }
    }

    private fun pausePresentation() {
        presentationPaused = true
        retryHandler.removeCallbacks(nextPlaylistItemRunnable)
        currentPlaylistItem()?.let { item ->
            val durationMs = playlistItemDurationMs(item)
            val elapsedMs = (SystemClock.elapsedRealtime() - currentPlaylistItemStartedRealtimeMs).coerceIn(0L, durationMs)
            presentationPausedRemainingMs = (durationMs - elapsedMs).coerceAtLeast(500L)
        }
        player?.pause()
    }

    private fun resumePresentation() {
        if (!playlistPlaybackActive || playlistItems.isEmpty()) return
        presentationPaused = false
        val item = currentPlaylistItem() ?: return
        val durationMs = playlistItemDurationMs(item)
        val elapsedMs = (SystemClock.elapsedRealtime() - currentPlaylistItemStartedRealtimeMs).coerceIn(0L, durationMs)
        val remainingMs = presentationPausedRemainingMs ?: (durationMs - elapsedMs).coerceAtLeast(500L)
        presentationPausedRemainingMs = null
        if (item.type == "video") player?.play()
        if (playlistItems.size > 1) {
            retryHandler.removeCallbacks(nextPlaylistItemRunnable)
            retryHandler.postDelayed(nextPlaylistItemRunnable, remainingMs)
        }
    }

    private fun applyPlaylistResyncRequest(sync: PlaylistSync): Boolean {
        if (!playlistPlaybackActive || !sync.enabled || sync.resyncToken <= 0L || sync.resyncToken == playlistResyncToken) {
            return false
        }

        val syncPosition = synchronizedPlaylistPosition() ?: return false
        val targetIndex = sync.expectedIndex?.coerceIn(0, (playlistItems.size - 1).coerceAtLeast(0)) ?: syncPosition.index
        val targetElapsedMs = sync.expectedElapsedMs.takeIf { it > 0L } ?: syncPosition.elapsedMs
        playlistResyncToken = sync.resyncToken
        startPlaylistItem(targetIndex, targetElapsedMs, syncPosition.remainingMs)
        return true
    }

    private fun applyPlaylistNotificationExtras(sound: PlaylistNotificationSound?) {
        if (sound == null) return

        playlistNotificationEnabled = sound.enabled
        currentPlaylistNotificationUrl = sound.url.orEmpty()
        currentPlaylistNotificationVersion = sound.version.orEmpty()
        intent.putExtra(EXTRA_PLAYLIST_NOTIFICATION_ENABLED, playlistNotificationEnabled)
        intent.putExtra(EXTRA_PLAYLIST_NOTIFICATION_URL, currentPlaylistNotificationUrl)
        intent.putExtra(EXTRA_PLAYLIST_NOTIFICATION_VERSION, currentPlaylistNotificationVersion)
    }

    private fun applyWidgetBarExtras(widgetBar: OfficialAppWidgetBar?) {
        if (widgetBar == null) return

        intent.putExtra(EXTRA_WIDGET_BAR_ENABLED, widgetBar.enabled)
        intent.putExtra(EXTRA_WIDGET_BAR_STYLE, widgetBar.style)
        intent.putExtra(EXTRA_WIDGET_BAR_COLOR, widgetBar.color)
        intent.putExtra(EXTRA_WIDGET_BAR_OPACITY, widgetBar.opacity)
        intent.putExtra(EXTRA_WIDGET_BAR_BLUR_ENABLED, widgetBar.blurEnabled)
        intent.putExtra(EXTRA_WIDGET_BAR_BEHAVIOR, widgetBar.behavior)
        intent.putExtra(EXTRA_WIDGET_BAR_ANIMATION, widgetBar.animation)
        intent.putExtra(EXTRA_WIDGET_BAR_LAYOUT_MODE, widgetBar.layoutMode)
        intent.putExtra(EXTRA_WIDGET_BAR_EDGE_SPACING, widgetBar.edgeSpacing)
        intent.putExtra(EXTRA_WIDGET_BAR_SHOW_SECONDS, widgetBar.showSeconds)
        intent.putExtra(EXTRA_WIDGET_BAR_APPEAR_SECONDS, widgetBar.appearSeconds)
        intent.putExtra(EXTRA_WIDGET_BAR_HIDE_SECONDS, widgetBar.hideSeconds)
        intent.putExtra(EXTRA_WIDGET_BAR_WEATHER_API_URL, widgetBar.weatherApiUrl)
        intent.putExtra(EXTRA_WIDGET_BAR_WEATHER_TEST_CONDITION, widgetBar.weatherTestCondition)
        intent.putExtra(EXTRA_WIDGET_BAR_CONTENT_MODE, widgetBar.contentMode)
        intent.putExtra(EXTRA_WIDGET_FORECAST_ENABLED, widgetBar.forecastEnabled)
        intent.putExtra(EXTRA_WIDGET_FORECAST_DAYS, widgetBar.forecastDays)
        intent.putExtra(EXTRA_WIDGET_FORECAST_ANIMATION_ENABLED, widgetBar.forecastAnimationEnabled)
        intent.putExtra(EXTRA_WIDGET_FORECAST_TRAVEL_SECONDS, widgetBar.forecastTravelSeconds)
        intent.putExtra(EXTRA_WIDGET_FORECAST_PAUSE_SECONDS, widgetBar.forecastPauseSeconds)
        intent.putExtra(EXTRA_WIDGET_FORECAST_CARD_ANIMATION, widgetBar.forecastCardAnimation)
        intent.putExtra(EXTRA_WIDGET_FORECAST_DISPLAY_MODE, widgetBar.forecastDisplayMode)
        intent.putExtra(EXTRA_WIDGET_FORECAST_DISPLAY_MINUTES, widgetBar.forecastDisplayMinutes)
        intent.putExtra(EXTRA_WIDGET_FORECAST_LATITUDE, widgetBar.forecastLatitude)
        intent.putExtra(EXTRA_WIDGET_FORECAST_LONGITUDE, widgetBar.forecastLongitude)
        intent.putExtra(EXTRA_WIDGET_FORECAST_TIMEZONE, widgetBar.forecastTimezone)
        intent.putExtra(EXTRA_WIDGET_BAR_WEATHER_ASSETS_JSON, JSONObject(widgetBar.weatherAssets).toString())
    }

    private fun OfficialAppWidgetBar.signature(): String {
        return listOf(
            enabled,
            style,
            color,
            opacity,
            blurEnabled,
            behavior,
            animation,
            layoutMode,
            edgeSpacing,
            showSeconds,
            appearSeconds,
            hideSeconds,
            weatherApiUrl.orEmpty(),
            weatherTestCondition,
            contentMode,
            forecastEnabled,
            forecastDays,
            forecastAnimationEnabled,
            forecastTravelSeconds,
            forecastPauseSeconds,
            forecastCardAnimation,
            forecastDisplayMode,
            forecastDisplayMinutes,
            forecastLatitude,
            forecastLongitude,
            forecastTimezone,
            weatherAssets.toSortedMap().entries.joinToString(",") { "${it.key}:${it.value}" }
        ).joinToString("|")
    }

    private fun maybePlayPlaylistNotification() {
        val channelId = intent.getLongExtra(EXTRA_CHANNEL_ID, -1L)
        val playlistVersion = currentConfigVersion
        val soundVersion = currentPlaylistNotificationVersion.ifBlank { "bundled-notification" }
        val version = listOf(playlistVersion, soundVersion).filter { it.isNotBlank() }.joinToString(":")
        if (!playlistNotificationEnabled || channelId <= 0L || version.isBlank()) return

        val prefs = getSharedPreferences(PREFS_PLAYLIST_NOTIFICATION, Context.MODE_PRIVATE)
        val key = "played_${channelId}"
        if (prefs.getString(key, null) == version) return

        prefs.edit().putString(key, version).apply()
        playPlaylistNotificationSound()
    }

    private fun playPlaylistNotificationSound() {
        releasePlaylistNotificationPlayer()

        runCatching {
            playlistNotificationPlayer = MediaPlayer.create(this, R.raw.universfield_new_notification_023_494260).apply {
                setOnCompletionListener { releasePlaylistNotificationPlayer() }
                setOnErrorListener { _, _, _ ->
                    releasePlaylistNotificationPlayer()
                    true
                }
                start()
            }
        }.onFailure { error ->
            Log.w(TAG, "Nao foi possivel tocar notificacao da playlist: ${error.message}")
            releasePlaylistNotificationPlayer()
        }
    }

    private fun releasePlaylistNotificationPlayer() {
        playlistNotificationPlayer?.setOnCompletionListener(null)
        playlistNotificationPlayer?.setOnErrorListener(null)
        playlistNotificationPlayer?.release()
        playlistNotificationPlayer = null
    }

    private fun configureWidgetBar() {
        widgetHandler.removeCallbacks(hideWidgetBarRunnable)
        widgetHandler.removeCallbacks(widgetBackdropRefreshRunnable)
        widgetAutoCycleRunnable?.let { widgetHandler.removeCallbacks(it) }
        widgetAutoCycleRunnable = null

        if (!isWidgetBarEnabled()) {
            binding.widgetBar.visibility = View.GONE
            applyWidgetContentMode(visible = false)
            return
        }

        applyWidgetBarStyle()
        applyWidgetBarContentMode()
        prepareWidgetBarLayout()
        updateWidgetClock()
        refreshWidgetWeatherFromConfiguredApi()
        binding.widgetBar.bringToFront()

        widgetHandler.removeCallbacks(clockWidgetRunnable)
        widgetHandler.removeCallbacks(weatherWidgetRunnable)
        widgetHandler.post(clockWidgetRunnable)
        widgetHandler.postDelayed(weatherWidgetRunnable, WEATHER_WIDGET_INTERVAL_MS)

        val behavior = currentWidgetBarBehavior()
        val legacyShowSeconds = intent.getIntExtra(EXTRA_WIDGET_BAR_SHOW_SECONDS, 8).coerceIn(1, 120)
        val appearSeconds = intent.getIntExtra(EXTRA_WIDGET_BAR_APPEAR_SECONDS, 0).coerceIn(0, 7200)
        val hideSeconds = intent.getIntExtra(EXTRA_WIDGET_BAR_HIDE_SECONDS, 0).coerceIn(0, 7200)

        when (behavior) {
            "slide" -> {
                binding.widgetBar.visibility = View.INVISIBLE
                widgetHandler.postDelayed({ showWidgetBar(animate = true) }, appearSeconds * 1000L)
            }
            "auto_hide" -> {
                binding.widgetBar.visibility = View.INVISIBLE
                scheduleAutoHideWidgetBar(
                    appearSeconds = appearSeconds,
                    visibleSeconds = if (hideSeconds > 0) hideSeconds else legacyShowSeconds
                )
            }
            else -> {
                showWidgetBar(animate = false)
            }
        }
    }

    private fun stopWidgetBar() {
        widgetHandler.removeCallbacks(clockWidgetRunnable)
        widgetHandler.removeCallbacks(weatherWidgetRunnable)
        widgetHandler.removeCallbacks(hideWidgetBarRunnable)
        widgetHandler.removeCallbacks(widgetBackdropRefreshRunnable)
        widgetAutoCycleRunnable?.let { widgetHandler.removeCallbacks(it) }
        widgetAutoCycleRunnable = null
        applyWidgetContentMode(visible = false)
        binding.widgetBar.animate().cancel()
    }

    private fun isWidgetBarEnabled(): Boolean {
        return isOfficialAppPlayback() && intent.getBooleanExtra(EXTRA_WIDGET_BAR_ENABLED, false)
    }

    private fun currentWidgetBarBehavior(): String {
        return intent.getStringExtra(EXTRA_WIDGET_BAR_BEHAVIOR)
            .orEmpty()
            .trim()
            .lowercase(Locale.ROOT)
            .ifBlank { "fixed" }
    }

    private fun isWidgetBarFixed(): Boolean {
        return currentWidgetBarBehavior() in setOf("fixed", "fixo", "always", "always_visible")
    }

    private fun showWidgetBar(animate: Boolean) {
        if (!isWidgetBarEnabled()) return
        binding.widgetBar.animate().cancel()
        bringPlayerOverlaysToFront()
        binding.widgetBar.visibility = View.VISIBLE
        applyWidgetContentMode(visible = true)
        binding.root.post { applyWidgetContentMode(visible = true) }
        binding.root.postDelayed({ applyWidgetContentMode(visible = true) }, 180L)
        requestWidgetBackdropRefresh(delayMs = 120L)
        val animation = intent.getStringExtra(EXTRA_WIDGET_BAR_ANIMATION).orEmpty().ifBlank { "slide" }
        if (!animate) {
            setWidgetBarShownPosition()
            binding.widgetBar.alpha = 1f
            binding.widgetBar.scaleX = 1f
            binding.widgetBar.scaleY = 1f
            return
        }

        when (animation) {
            "fade" -> {
                setWidgetBarShownPosition()
                binding.widgetBar.alpha = 0f
                binding.widgetBar.animate()
                    .alpha(1f)
                    .setDuration(360L)
                    .setInterpolator(DecelerateInterpolator(1.4f))
                    .start()
            }
            "zoom" -> {
                setWidgetBarShownPosition()
                binding.widgetBar.alpha = 0f
                binding.widgetBar.scaleX = 0.96f
                binding.widgetBar.scaleY = 0.86f
                binding.widgetBar.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(430L)
                    .setInterpolator(DecelerateInterpolator(1.8f))
                    .start()
            }
            "smooth" -> {
                setWidgetBarHiddenPosition()
                binding.widgetBar.alpha = 0f
                binding.widgetBar.scaleY = 0.92f
                binding.widgetBar.animate()
                    .translationX(widgetBarBaseTranslationX)
                    .translationY(widgetBarBaseTranslationY)
                    .alpha(1f)
                    .scaleY(1f)
                    .setDuration(560L)
                    .setInterpolator(DecelerateInterpolator(2.0f))
                    .start()
            }
            else -> {
                setWidgetBarHiddenPosition()
                binding.widgetBar.alpha = 0f
                binding.widgetBar.animate()
                    .translationX(widgetBarBaseTranslationX)
                    .translationY(widgetBarBaseTranslationY)
                    .alpha(1f)
                    .setDuration(420L)
                    .setInterpolator(DecelerateInterpolator(1.7f))
                    .start()
            }
        }
    }

    private fun hideWidgetBar() {
        if (isWidgetBarFixed()) {
            widgetHandler.removeCallbacks(hideWidgetBarRunnable)
            widgetAutoCycleRunnable?.let { widgetHandler.removeCallbacks(it) }
            widgetAutoCycleRunnable = null
            showWidgetBar(animate = false)
            return
        }
        binding.widgetBar.animate().cancel()
        applyWidgetContentMode(visible = false)
        widgetHandler.removeCallbacks(widgetBackdropRefreshRunnable)
        val isPortrait = isPortraitOrientation()
        val hiddenTranslationX = if (isPortrait) widgetBarBaseTranslationX - widgetBarThicknessPx() else widgetBarBaseTranslationX
        val hiddenTranslationY = if (isPortrait) widgetBarBaseTranslationY else widgetBarBaseTranslationY + widgetBarThicknessPx()
        val animation = intent.getStringExtra(EXTRA_WIDGET_BAR_ANIMATION).orEmpty().ifBlank { "slide" }
        val animator = binding.widgetBar.animate()
            .alpha(0f)
            .setDuration(
                when (animation) {
                    "smooth" -> 460L
                    "zoom" -> 360L
                    "fade" -> 320L
                    else -> 340L
                }
            )
            .setInterpolator(DecelerateInterpolator(1.4f))
            .withEndAction {
                binding.widgetBar.visibility = View.GONE
                widgetHandler.removeCallbacks(widgetBackdropRefreshRunnable)
                binding.widgetBar.scaleX = 1f
                binding.widgetBar.scaleY = 1f
            }

        when (animation) {
            "fade" -> {
                setWidgetBarShownPosition()
            }
            "zoom" -> {
                setWidgetBarShownPosition()
                animator.scaleX(0.96f).scaleY(0.86f)
            }
            "smooth" -> {
                animator
                    .translationX(hiddenTranslationX)
                    .translationY(hiddenTranslationY)
                    .scaleY(0.92f)
            }
            else -> {
                animator
                    .translationX(hiddenTranslationX)
                    .translationY(hiddenTranslationY)
            }
        }
        animator.start()
    }

    private fun scheduleAutoHideWidgetBar(appearSeconds: Int, visibleSeconds: Int) {
        widgetAutoCycleRunnable?.let { widgetHandler.removeCallbacks(it) }
        val cycleRunnable = object : Runnable {
            override fun run() {
                if (!isWidgetBarEnabled()) return
                showWidgetBar(animate = true)
                widgetHandler.postDelayed({
                    if (isWidgetBarEnabled()) {
                        hideWidgetBar()
                        widgetHandler.postDelayed(this, appearSeconds.coerceAtLeast(1) * 1000L)
                    }
                }, visibleSeconds.coerceAtLeast(1) * 1000L)
            }
        }
        widgetAutoCycleRunnable = cycleRunnable
        widgetHandler.postDelayed(cycleRunnable, appearSeconds * 1000L)
    }

    private fun requestWidgetBackdropRefresh(delayMs: Long = 120L) {
        widgetHandler.removeCallbacks(widgetBackdropRefreshRunnable)
        if (isWidgetBarEnabled() && shouldUseWidgetBackdropBlur() && binding.widgetBar.visibility == View.VISIBLE) {
            widgetHandler.postDelayed(widgetBackdropRefreshRunnable, delayMs.coerceAtLeast(0L))
        }
    }

    private fun bringPlayerOverlaysToFront() {
        if (isWidgetBarEnabled()) binding.widgetBar.bringToFront()
        binding.playerHeader.bringToFront()
        binding.errorText.bringToFront()
        binding.downloadOverlay.bringToFront()
    }

    private fun currentWidgetContentInset(): Int {
        val pushContent = intent.getStringExtra(EXTRA_WIDGET_BAR_LAYOUT_MODE).orEmpty() == "push_content"
        return if (binding.widgetBar.visibility == View.VISIBLE && pushContent) widgetBarThicknessPx() + widgetBarEdgeSpacingPx() else 0
    }

    private fun applyWidgetContentMode(visible: Boolean) {
        val pushContent = intent.getStringExtra(EXTRA_WIDGET_BAR_LAYOUT_MODE).orEmpty() == "push_content"
        val inset = if (visible && pushContent) widgetBarThicknessPx() + widgetBarEdgeSpacingPx() else 0
        val isPortrait = isPortraitOrientation()
        val imageScaleType = if (pushContent) {
            android.widget.ImageView.ScaleType.FIT_XY
        } else {
            android.widget.ImageView.ScaleType.FIT_CENTER
        }
        binding.playlistImageView.scaleType = imageScaleType
        binding.previousPlaylistImageView.scaleType = imageScaleType
        binding.playbackSurface.clipChildren = visible && pushContent
        binding.playbackSurface.clipToPadding = visible && pushContent
        val params = binding.playbackSurface.layoutParams as? FrameLayout.LayoutParams ?: return
        if (isPortrait && binding.root.width > 0) {
            params.width = (binding.root.width - inset).coerceAtLeast(1)
            params.height = FrameLayout.LayoutParams.MATCH_PARENT
            params.gravity = Gravity.START
            params.leftMargin = if (isPortraitInvertedOrientation()) 0 else inset
            params.rightMargin = if (isPortraitInvertedOrientation()) inset else 0
        } else {
            params.width = FrameLayout.LayoutParams.MATCH_PARENT
            params.height = FrameLayout.LayoutParams.MATCH_PARENT
            params.gravity = Gravity.NO_GRAVITY
            params.leftMargin = 0
            params.rightMargin = 0
        }
        params.topMargin = 0
        params.bottomMargin = if (isPortrait) 0 else inset
        binding.playbackSurface.layoutParams = params
        binding.playbackSurface.translationX = 0f
        binding.playbackSurface.translationY = 0f
        binding.playbackSurface.requestLayout()
        binding.playbackSurface.post { relayoutVisiblePlaybackSurface() }

        if (!isPortrait) {
            listOf(binding.playerView, binding.playlistImageView, binding.previousPlaylistImageView, binding.browserView).forEach { view ->
                val childParams = view.layoutParams as? FrameLayout.LayoutParams ?: return@forEach
                childParams.width = FrameLayout.LayoutParams.MATCH_PARENT
                childParams.height = FrameLayout.LayoutParams.MATCH_PARENT
                childParams.gravity = Gravity.NO_GRAVITY
                view.layoutParams = childParams
                view.translationX = 0f
                view.translationY = 0f
            }
        }
    }

    private fun relayoutVisiblePlaybackSurface() {
        if (!isPortraitOrientation()) return

        when {
            binding.browserView.visibility == View.VISIBLE -> prepareBrowserViewLayout({}, syncWidgetMode = false)
            binding.playlistImageView.visibility == View.VISIBLE -> preparePlaylistSurfaceLayout(binding.playlistImageView, syncWidgetMode = false)
            binding.playerView.visibility == View.VISIBLE || binding.playerView.visibility == View.INVISIBLE -> preparePlaylistSurfaceLayout(binding.playerView, syncWidgetMode = false)
        }
    }

    private fun prepareWidgetBarLayout() {
        val isPortrait = isPortraitOrientation()
        if (!isPortrait) {
            binding.widgetBar.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                widgetBarThicknessPx()
            ).apply {
                gravity = Gravity.BOTTOM
                bottomMargin = widgetBarEdgeSpacingPx()
            }
            binding.widgetBar.rotation = 0f
            binding.widgetBar.translationX = 0f
            binding.widgetBar.translationY = 0f
            widgetBarBaseTranslationX = 0f
            widgetBarBaseTranslationY = 0f
            binding.widgetBar.requestLayout()
            return
        }

        binding.root.post {
            val screenW = binding.root.width
            val screenH = binding.root.height
            if (screenW <= 0 || screenH <= 0) return@post

            binding.widgetBar.layoutParams = FrameLayout.LayoutParams(screenH, widgetBarThicknessPx()).apply {
                gravity = Gravity.CENTER
            }
            binding.widgetBar.pivotX = screenH / 2f
            binding.widgetBar.pivotY = widgetBarThicknessPx() / 2f
            val sideOffset = screenW / 2f - widgetBarThicknessPx() / 2f
            val edgeSpacing = widgetBarEdgeSpacingPx().toFloat()
            binding.widgetBar.rotation = portraitContentRotation()
            binding.widgetBar.translationX = if (isPortraitInvertedOrientation()) sideOffset - edgeSpacing else -sideOffset + edgeSpacing
            binding.widgetBar.translationY = 0f
            widgetBarBaseTranslationX = binding.widgetBar.translationX
            widgetBarBaseTranslationY = binding.widgetBar.translationY
            binding.widgetBar.requestLayout()
        }
    }

    private fun setWidgetBarShownPosition() {
        binding.widgetBar.translationX = widgetBarBaseTranslationX
        binding.widgetBar.translationY = widgetBarBaseTranslationY
    }

    private fun setWidgetBarHiddenPosition() {
        val isPortrait = isPortraitOrientation()
        binding.widgetBar.translationX = if (isPortrait) {
            widgetBarBaseTranslationX + if (isPortraitInvertedOrientation()) widgetBarThicknessPx() else -widgetBarThicknessPx()
        } else {
            widgetBarBaseTranslationX
        }
        binding.widgetBar.translationY = if (isPortrait) widgetBarBaseTranslationY else widgetBarBaseTranslationY + widgetBarThicknessPx()
    }

    private fun applyWidgetBarStyle() {
        val style = intent.getStringExtra(EXTRA_WIDGET_BAR_STYLE).orEmpty().ifBlank { "transparent_blur" }
        val opacity = intent.getIntExtra(EXTRA_WIDGET_BAR_OPACITY, 72).coerceIn(0, 100)
        val rawColor = intent.getStringExtra(EXTRA_WIDGET_BAR_COLOR).orEmpty().ifBlank { "#0b1020" }
        val color = runCatching { Color.parseColor(rawColor) }.getOrDefault(Color.parseColor("#0b1020"))
        val blurRequested = shouldUseWidgetBackdropBlur()
        val alpha = if (style == "solid") {
            255
        } else if (blurRequested) {
            ((opacity / 100f) * 145).toInt().coerceIn(24, 170)
        } else {
            ((opacity / 100f) * 95).toInt().coerceIn(0, 120)
        }
        val background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor((color and 0x00FFFFFF) or (alpha shl 24))
        }
        widgetBackdropDrawable = null
        binding.widgetBar.background = null
        binding.widgetBarBackdrop.background = background
        binding.widgetBar.elevation = 0f
        binding.widgetRainFade.visibility = View.GONE
        binding.widgetRainFade.background = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(
                Color.TRANSPARENT,
                (color and 0x00FFFFFF) or (46 shl 24),
                (color and 0x00FFFFFF) or (150 shl 24),
                color
            )
        )
        binding.widgetForecastFadeStart.background = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(color, (color and 0x00FFFFFF) or (90 shl 24), Color.TRANSPARENT)
        )
        binding.widgetForecastFadeEnd.background = GradientDrawable(
            GradientDrawable.Orientation.RIGHT_LEFT,
            intArrayOf(color, (color and 0x00FFFFFF) or (90 shl 24), Color.TRANSPARENT)
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            binding.widgetBarBackdrop.setRenderEffect(null)
        }
    }

    private fun refreshWidgetBackdropBlur() {
        if (!shouldUseWidgetBackdropBlur()) return

        val rootWidth = binding.root.width
        val rootHeight = binding.root.height
        if (rootWidth <= 0 || rootHeight <= 0) return

        val barThickness = widgetBarThicknessPx()
        val isPortrait = isPortraitOrientation()
        val sourceRect = widgetBackdropBlurSourceRect(
            rootWidth = rootWidth,
            rootHeight = rootHeight,
            barThickness = barThickness,
            isPortrait = isPortrait
        )
        val bitmap = Bitmap.createBitmap(sourceRect.width().coerceAtLeast(1), sourceRect.height().coerceAtLeast(1), Bitmap.Config.ARGB_8888)

        val previousBackdropAlpha = binding.widgetBarBackdrop.alpha
        binding.widgetBarBackdrop.alpha = 0f
        binding.widgetBarBackdrop.invalidate()
        binding.root.invalidate()

        val canvas = Canvas(bitmap)
        canvas.translate(-sourceRect.left.toFloat(), -sourceRect.top.toFloat())
        binding.root.draw(canvas)
        binding.widgetBarBackdrop.alpha = previousBackdropAlpha
        applyBlurredWidgetBackdrop(if (isPortrait) rotateBitmap(bitmap, portraitBackdropRotation()) else bitmap)
    }

    private fun widgetBackdropBlurSourceRect(rootWidth: Int, rootHeight: Int, barThickness: Int, isPortrait: Boolean): Rect {
        val pushContent = intent.getStringExtra(EXTRA_WIDGET_BAR_LAYOUT_MODE).orEmpty() == "push_content"
        if (!pushContent) {
            return if (isPortrait) {
                Rect(0, 0, barThickness.coerceAtMost(rootWidth), rootHeight)
            } else {
                Rect(0, (rootHeight - barThickness).coerceAtLeast(0), rootWidth, rootHeight)
            }
        }

        return if (isPortrait) {
            val left = barThickness.coerceAtMost((rootWidth - 1).coerceAtLeast(0))
            val right = (left + barThickness).coerceAtMost(rootWidth)
            Rect(left, 0, right.coerceAtLeast(left + 1), rootHeight)
        } else {
            val bottom = (rootHeight - barThickness).coerceAtLeast(1)
            val top = (bottom - barThickness).coerceAtLeast(0)
            Rect(0, top, rootWidth, bottom.coerceAtLeast(top + 1))
        }
    }

    private fun shouldUseWidgetBackdropBlur(): Boolean {
        return intent.getBooleanExtra(EXTRA_WIDGET_BAR_BLUR_ENABLED, true)
    }

    private fun applyFallbackWidgetBackdropBlur(isPortrait: Boolean, sourceRect: Rect) {
        val bitmap = Bitmap.createBitmap(sourceRect.width().coerceAtLeast(1), sourceRect.height().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.translate(-sourceRect.left.toFloat(), -sourceRect.top.toFloat())
        val previousBackdropAlpha = binding.widgetBarBackdrop.alpha
        binding.widgetBarBackdrop.alpha = 0f
        binding.root.draw(canvas)
        binding.widgetBarBackdrop.alpha = previousBackdropAlpha
        applyBlurredWidgetBackdrop(if (isPortrait) rotateBitmap(bitmap, portraitBackdropRotation()) else bitmap)
    }

    private fun applyBlurredWidgetBackdrop(bitmap: Bitmap) {
        if (!isWidgetBarEnabled() || !shouldUseWidgetBackdropBlur()) return

        val scaled = Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width / 10).coerceAtLeast(1),
            (bitmap.height / 10).coerceAtLeast(1),
            true
        )
        val blurred = stackBlur(scaled, 18)
        val blurDrawable = BitmapDrawable(resources, blurred).apply {
            setBounds(0, 0, binding.widgetBarBackdrop.width, binding.widgetBarBackdrop.height)
        }

        val color = runCatching {
            Color.parseColor(intent.getStringExtra(EXTRA_WIDGET_BAR_COLOR).orEmpty().ifBlank { "#0b1020" })
        }.getOrDefault(Color.parseColor("#0b1020"))
        val opacity = intent.getIntExtra(EXTRA_WIDGET_BAR_OPACITY, 72).coerceIn(0, 100)
        val tintAlpha = ((opacity / 100f) * 115).toInt().coerceIn(18, 145)
        val tint = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor((color and 0x00FFFFFF) or (tintAlpha shl 24))
        }

        val nextDrawable = LayerDrawable(arrayOf(blurDrawable, tint))
        val previousDrawable = widgetBackdropDrawable
        widgetBackdropDrawable = nextDrawable
        if (previousDrawable == null) {
            binding.widgetBarBackdrop.background = nextDrawable
            return
        }

        val transition = TransitionDrawable(arrayOf(previousDrawable, nextDrawable)).apply {
            isCrossFadeEnabled = true
        }
        binding.widgetBarBackdrop.background = transition
        transition.startTransition(520)
    }

    private fun rotateBitmap(bitmap: Bitmap, degrees: Float): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun stackBlur(source: Bitmap, radius: Int): Bitmap {
        val bitmap = source.copy(Bitmap.Config.ARGB_8888, true)
        if (radius < 1) return bitmap

        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val tmp = IntArray(width * height)
        val div = radius * 2 + 1

        for (y in 0 until height) {
            var red = 0
            var green = 0
            var blue = 0
            for (i in -radius..radius) {
                val color = pixels[y * width + i.coerceIn(0, width - 1)]
                red += Color.red(color)
                green += Color.green(color)
                blue += Color.blue(color)
            }
            for (x in 0 until width) {
                tmp[y * width + x] = Color.rgb(red / div, green / div, blue / div)
                val remove = pixels[y * width + (x - radius).coerceIn(0, width - 1)]
                val add = pixels[y * width + (x + radius + 1).coerceIn(0, width - 1)]
                red += Color.red(add) - Color.red(remove)
                green += Color.green(add) - Color.green(remove)
                blue += Color.blue(add) - Color.blue(remove)
            }
        }

        for (x in 0 until width) {
            var red = 0
            var green = 0
            var blue = 0
            for (i in -radius..radius) {
                val color = tmp[i.coerceIn(0, height - 1) * width + x]
                red += Color.red(color)
                green += Color.green(color)
                blue += Color.blue(color)
            }
            for (y in 0 until height) {
                pixels[y * width + x] = Color.rgb(red / div, green / div, blue / div)
                val remove = tmp[(y - radius).coerceIn(0, height - 1) * width + x]
                val add = tmp[(y + radius + 1).coerceIn(0, height - 1) * width + x]
                red += Color.red(add) - Color.red(remove)
                green += Color.green(add) - Color.green(remove)
                blue += Color.blue(add) - Color.blue(remove)
            }
        }

        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return Bitmap.createScaledBitmap(bitmap, source.width * 10, source.height * 10, true)
    }

    private fun applyWidgetBarContentMode() {
        forecastHideRunnable?.let { widgetHandler.removeCallbacks(it) }
        forecastShowRunnable?.let { widgetHandler.removeCallbacks(it) }
        val forecastEnabled = intent.getBooleanExtra(EXTRA_WIDGET_FORECAST_ENABLED, true)
        when (intent.getStringExtra(EXTRA_WIDGET_BAR_CONTENT_MODE).orEmpty().ifBlank { "time_weather" }) {
            "weather" -> {
                binding.widgetWeatherGroup.visibility = View.VISIBLE
                binding.widgetForecastViewport.visibility = if (forecastEnabled) View.VISIBLE else View.INVISIBLE
                binding.widgetBarDivider.visibility = View.INVISIBLE
                binding.widgetClockDivider.visibility = View.INVISIBLE
                binding.widgetClockGroup.visibility = View.INVISIBLE
            }
            "time" -> {
                binding.widgetWeatherGroup.visibility = View.INVISIBLE
                binding.widgetForecastViewport.visibility = View.INVISIBLE
                binding.widgetBarDivider.visibility = View.INVISIBLE
                binding.widgetClockDivider.visibility = View.INVISIBLE
                binding.widgetClockGroup.visibility = View.VISIBLE
            }
            else -> {
                binding.widgetWeatherGroup.visibility = View.VISIBLE
                binding.widgetForecastViewport.visibility = if (forecastEnabled) View.VISIBLE else View.INVISIBLE
                binding.widgetBarDivider.visibility = View.VISIBLE
                binding.widgetClockDivider.visibility = View.VISIBLE
                binding.widgetClockGroup.visibility = View.VISIBLE
            }
        }
        if (forecastEnabled && binding.widgetForecastViewport.visibility == View.VISIBLE) {
            animateForecastCardsIn()
            if (intent.getStringExtra(EXTRA_WIDGET_FORECAST_DISPLAY_MODE).orEmpty() == "minutes") {
                scheduleForecastTimedCycle()
            }
        }
    }

    private fun scheduleForecastTimedCycle() {
        forecastHideRunnable?.let { widgetHandler.removeCallbacks(it) }
        forecastShowRunnable?.let { widgetHandler.removeCallbacks(it) }
        val minutes = intent.getIntExtra(EXTRA_WIDGET_FORECAST_DISPLAY_MINUTES, 5).coerceIn(1, 180)
        forecastHideRunnable = Runnable {
            animateForecastCardsOut()
            val pauseMs = intent.getIntExtra(EXTRA_WIDGET_FORECAST_PAUSE_SECONDS, 3).coerceIn(0, 20).coerceAtLeast(2) * 1_000L
            forecastShowRunnable = Runnable {
                if (intent.getBooleanExtra(EXTRA_WIDGET_FORECAST_ENABLED, true)) {
                    binding.widgetForecastViewport.visibility = View.VISIBLE
                    animateForecastCardsIn()
                    scheduleForecastTimedCycle()
                }
            }.also { widgetHandler.postDelayed(it, pauseMs + 900L) }
        }.also { widgetHandler.postDelayed(it, minutes * 60_000L) }
    }

    private fun animateForecastCardsIn() {
        val view = binding.widgetForecastViewport
        view.animate().cancel()
        val style = intent.getStringExtra(EXTRA_WIDGET_FORECAST_CARD_ANIMATION).orEmpty().ifBlank { "stagger_up" }
        view.visibility = View.VISIBLE
        if (style == "stagger_up") {
            view.alpha = 1f
            view.translationX = 0f
            view.scaleX = 1f
            view.scaleY = 1f
            val group = binding.widgetForecastGroup
            (0 until group.childCount).forEach { index ->
                group.getChildAt(index).apply {
                    animate().cancel()
                    alpha = 0f
                    translationY = dpToPx(52).toFloat()
                    animate().alpha(1f).translationY(0f).setStartDelay(index * 120L).setDuration(520L)
                        .setInterpolator(DecelerateInterpolator()).start()
                }
            }
            return
        }
        view.alpha = if (style == "none") 1f else 0f
        view.translationX = if (style == "slide" || style == "smooth") dpToPx(36).toFloat() else 0f
        view.scaleX = if (style == "zoom" || style == "smooth") 0.94f else 1f
        view.scaleY = view.scaleX
        if (style != "none") view.animate().alpha(1f).translationX(0f).scaleX(1f).scaleY(1f).setDuration(if (style == "smooth") 900L else 550L).start()
    }

    private fun animateForecastCardsOut() {
        val view = binding.widgetForecastViewport
        val style = intent.getStringExtra(EXTRA_WIDGET_FORECAST_CARD_ANIMATION).orEmpty().ifBlank { "stagger_up" }
        widgetForecastAnimator?.cancel()
        if (style == "stagger_up") {
            val group = binding.widgetForecastGroup
            if (group.childCount == 0) {
                view.visibility = View.INVISIBLE
                return
            }
            (group.childCount - 1 downTo 0).forEachIndexed { sequence, index ->
                group.getChildAt(index).apply {
                    animate().cancel()
                    animate().alpha(0f).translationY(dpToPx(52).toFloat()).setStartDelay(sequence * 120L).setDuration(420L)
                        .setInterpolator(AccelerateInterpolator())
                        .withEndAction { if (index == 0) view.visibility = View.INVISIBLE }
                        .start()
                }
            }
            return
        }
        if (style == "none") {
            view.visibility = View.INVISIBLE
        } else {
            view.animate().cancel()
            view.animate().alpha(0f).translationX(if (style == "slide" || style == "smooth") -dpToPx(36).toFloat() else 0f)
                .scaleX(if (style == "zoom" || style == "smooth") 0.94f else 1f)
                .scaleY(if (style == "zoom" || style == "smooth") 0.94f else 1f)
                .setDuration(if (style == "smooth") 800L else 450L)
                .withEndAction { view.visibility = View.INVISIBLE }
                .start()
        }
    }

    private fun updateWidgetClock() {
        if (!isWidgetBarEnabled()) return
        val now = Date()
        binding.widgetClockText.text = DateFormat.getTimeFormat(this).format(now)
        binding.widgetDateText.text = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(now)
    }

    private fun refreshWidgetWeatherFromConfiguredApi() {
        if (!isWidgetBarEnabled()) return
        thread {
            val forcedCondition = currentWeatherTestCondition()
            val weather = if (forcedCondition == "real") {
                val weatherApiUrl = intent.getStringExtra(EXTRA_WIDGET_BAR_WEATHER_API_URL)
                runCatching { apiClient.fetchWeather(weatherApiUrl) }
                    .getOrDefault(WeatherSnapshot("--", "", "", "clouds"))
            } else {
                forcedWeatherSnapshot(forcedCondition)
            }
            val forecastEnabled = intent.getBooleanExtra(EXTRA_WIDGET_FORECAST_ENABLED, true)
            val forecastDays = intent.getIntExtra(EXTRA_WIDGET_FORECAST_DAYS, 5).coerceIn(1, 7)
            val forecast = if (forecastEnabled) {
                runCatching {
                    apiClient.fetchForecast(
                        intent.getDoubleExtra(EXTRA_WIDGET_FORECAST_LATITUDE, -8.0476),
                        intent.getDoubleExtra(EXTRA_WIDGET_FORECAST_LONGITUDE, -34.8770),
                        intent.getStringExtra(EXTRA_WIDGET_FORECAST_TIMEZONE).orEmpty().ifBlank { "America/Sao_Paulo" },
                        forecastDays
                    )
                }.getOrDefault(emptyList())
            } else {
                emptyList()
            }
            runOnUiThread {
                if (!isWidgetBarEnabled()) return@runOnUiThread
                binding.widgetWeatherText.text = weather.temperatureLabel.trim().ifBlank { "--" }
                binding.widgetWeatherStatusText.text = weather.statusLabel.trim()
                binding.widgetWeatherUvText.text = weather.uvLabel.trim()
                binding.widgetWeatherUvGroup.visibility = if (weather.uvLabel.isBlank()) View.GONE else View.VISIBLE
                binding.widgetWeatherIcon.setImageResource(weatherIconFor(weather.condition, weather.iconCode))
                binding.widgetRainGif.setMovieUrl(weatherAssetUrlFor(weather.condition, weather.iconCode))
                if (forecastEnabled) {
                    renderWidgetForecast(forecast)
                } else {
                    widgetForecastAnimator?.cancel()
                    binding.widgetForecastGroup.removeAllViews()
                }
            }
        }
    }

    private fun renderWidgetForecast(days: List<WeatherForecastDay>) {
        binding.widgetForecastGroup.removeAllViews()
        val configuredDays = intent.getIntExtra(EXTRA_WIDGET_FORECAST_DAYS, 5).coerceIn(1, 7)
        val forecastDays = if (days.isNotEmpty()) days.take(configuredDays) else fallbackWidgetForecast().take(configuredDays)
        forecastDays.forEach { day ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dpToPx(4), dpToPx(2), dpToPx(4), dpToPx(2))
                elevation = 0f
                background = null
            }
            val dayName = TextView(this).apply {
                text = SimpleDateFormat("EEE", Locale("pt", "BR")).format(SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(day.date) ?: Date())
                setTextColor(Color.rgb(222, 226, 240)); textSize = 10f; gravity = Gravity.CENTER
                includeFontPadding = false
            }
            val icon = ImageView(this).apply {
                setImageResource(forecastIconFor(day.code)); adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER
            }
            val temperature = TextView(this).apply {
                val maximum = if (day.max.isNaN()) "--\u00B0" else "${day.max.toInt()}\u00B0"
                val minimum = if (day.min.isNaN()) "--\u00B0" else "${day.min.toInt()}\u00B0"
                text = android.text.SpannableString("$maximum  $minimum").apply {
                    setSpan(
                        android.text.style.ForegroundColorSpan(Color.rgb(145, 153, 174)),
                        maximum.length,
                        length,
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
                setTextColor(Color.WHITE); textSize = 10.5f; gravity = Gravity.CENTER; setTypeface(typeface, android.graphics.Typeface.BOLD)
                includeFontPadding = false
            }
            card.addView(dayName, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(13)))
            card.addView(icon, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(25)))
            card.addView(temperature, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(16)))
            binding.widgetForecastGroup.addView(card, LinearLayout.LayoutParams(dpToPx(56), dpToPx(58)).apply { setMargins(dpToPx(3), 0, dpToPx(3), 0) })
        }
        startWidgetForecastAnimation()
        if (binding.widgetForecastViewport.visibility == View.VISIBLE) animateForecastCardsIn()
    }

    private fun blendWidgetColor(base: Int, tint: Int, amount: Float): Int {
        val ratio = amount.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(base) + (Color.red(tint) - Color.red(base)) * ratio).toInt(),
            (Color.green(base) + (Color.green(tint) - Color.green(base)) * ratio).toInt(),
            (Color.blue(base) + (Color.blue(tint) - Color.blue(base)) * ratio).toInt()
        )
    }

    private fun forecastGlassBackground(base: Int, selected: Boolean): Drawable {
        // Blur only the glass surface; foreground text and weather icons stay crisp.
        val texture = Bitmap.createBitmap(56, 58, Bitmap.Config.ARGB_8888)
        val surfaceCanvas = Canvas(texture)
        surfaceCanvas.drawColor(blendWidgetColor(base, Color.rgb(39, 45, 63), 0.70f))
        val light = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        light.color = if (selected) Color.argb(135, 141, 115, 215) else Color.argb(65, 155, 170, 202)
        surfaceCanvas.drawOval(-18f, -16f, 47f, 17f, light)
        light.color = if (selected) Color.argb(95, 119, 91, 200) else Color.argb(36, 111, 125, 159)
        surfaceCanvas.drawOval(12f, 39f, 70f, 71f, light)
        val blurred = stackBlur(texture, 10)
        val glass = android.graphics.drawable.ShapeDrawable(
            android.graphics.drawable.shapes.RoundRectShape(FloatArray(8) { dpToPx(9).toFloat() }, null, null)
        ).apply {
            shaderFactory = object : android.graphics.drawable.ShapeDrawable.ShaderFactory() {
                override fun resize(width: Int, height: Int): android.graphics.Shader {
                    return android.graphics.BitmapShader(blurred, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP).apply {
                        setLocalMatrix(Matrix().apply { setScale(width / 56f, height / 58f) })
                    }
                }
            }
            paint.alpha = 225
        }
        val outline = GradientDrawable().apply {
            cornerRadius = dpToPx(9).toFloat()
            setColor(Color.TRANSPARENT)
            setStroke(dpToPx(1), if (selected) Color.argb(210, 165, 139, 255) else Color.argb(65, 155, 169, 198))
        }
        return LayerDrawable(arrayOf(glass, outline))
    }

    private fun startWidgetForecastAnimation() {
        widgetForecastAnimator?.cancel()
        binding.widgetForecastScroll.scrollTo(0, 0)
        if (!intent.getBooleanExtra(EXTRA_WIDGET_FORECAST_ANIMATION_ENABLED, true)) return
        binding.widgetForecastGroup.post {
            val visibleWidth = binding.widgetForecastScroll.width
            val contentWidth = (0 until binding.widgetForecastGroup.childCount).sumOf { index ->
                val child = binding.widgetForecastGroup.getChildAt(index)
                child.width + ((child.layoutParams as? LinearLayout.LayoutParams)?.let { it.leftMargin + it.rightMargin } ?: 0)
            }
            val overflow = (contentWidth - visibleWidth).coerceAtLeast(0)
            if (overflow == 0 || visibleWidth == 0) return@post

            val travelMs = intent.getIntExtra(EXTRA_WIDGET_FORECAST_TRAVEL_SECONDS, 12).coerceIn(4, 60) * 1_000L
            val pauseMs = intent.getIntExtra(EXTRA_WIDGET_FORECAST_PAUSE_SECONDS, 3).coerceIn(0, 20) * 1_000L
            val cycleMs = (travelMs * 2L) + (pauseMs * 2L)
            widgetForecastAnimator = ValueAnimator.ofFloat(0f, cycleMs.toFloat()).apply {
                duration = cycleMs
                repeatCount = ValueAnimator.INFINITE
                interpolator = android.view.animation.LinearInterpolator()
                addUpdateListener { animator ->
                    val elapsed = animator.animatedValue as Float
                    val rawProgress = when {
                        elapsed < pauseMs -> 0f
                        elapsed < pauseMs + travelMs -> (elapsed - pauseMs) / travelMs
                        elapsed < (pauseMs * 2L) + travelMs -> 1f
                        else -> 1f - ((elapsed - ((pauseMs * 2L) + travelMs)) / travelMs)
                    }.coerceIn(0f, 1f)
                    val smoothProgress = rawProgress * rawProgress * (3f - 2f * rawProgress)
                    binding.widgetForecastScroll.scrollTo((overflow * smoothProgress).toInt(), 0)
                }
                start()
            }
        }
    }

    private fun forecastIconFor(code: Int): Int = when (code) {
        0 -> R.drawable.google_weather_sunny
        1 -> R.drawable.google_weather_mostly_sunny
        2 -> R.drawable.google_weather_partly_cloudy
        3, 45, 48 -> R.drawable.google_weather_cloudy
        51, 53, 55, 56, 57, 61 -> R.drawable.google_weather_drizzle
        63, 65, 66, 67, 80, 81, 82 -> R.drawable.google_weather_rain
        71, 73, 75, 77, 85, 86 -> R.drawable.google_weather_flurries
        95, 96, 99 -> R.drawable.google_weather_storm
        else -> R.drawable.google_weather_cloudy
    }

    private fun fallbackWidgetForecast(): List<WeatherForecastDay> {
        val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val calendar = java.util.Calendar.getInstance()
        return (1..5).map { offset ->
            calendar.add(java.util.Calendar.DAY_OF_YEAR, if (offset == 1) 1 else 1)
            WeatherForecastDay(formatter.format(calendar.time), Double.NaN, Double.NaN, 2)
        }
    }

    private fun currentWeatherTestCondition(): String {
        return intent.getStringExtra(EXTRA_WIDGET_BAR_WEATHER_TEST_CONDITION)
            .orEmpty()
            .lowercase(Locale.ROOT)
            .takeIf { it in setOf("real", "default", "sun", "rain", "cloud", "storm") }
            ?: "real"
    }

    private fun forcedWeatherSnapshot(condition: String): WeatherSnapshot {
        return when (condition) {
            "sun" -> WeatherSnapshot("29°C", "Ensolarado", "UV 8.0", "sun")
            "rain" -> WeatherSnapshot("24°C", "Chuva", "UV 2.0", "rain")
            "cloud" -> WeatherSnapshot("26°C", "Nublado", "UV 4.0", "cloud")
            "storm" -> WeatherSnapshot("22°C", "Tempestade", "UV 1.0", "storm")
            else -> WeatherSnapshot("--", "Clima teste", "", "default")
        }
    }

    private fun weatherAssetUrlFor(condition: String, iconCode: Int? = null): String? {
        val assets = widgetWeatherAssets()
        val normalized = condition.lowercase(Locale.ROOT)
        val key = weatherAssetKeyFor(normalized, iconCode)
        val options = assets[key].orEmpty().ifEmpty { assets["default"].orEmpty() }
        if (options.isEmpty()) return null
        return options[Random.nextInt(options.size)]
    }

    private fun weatherAssetKeyFor(normalizedCondition: String, iconCode: Int? = null): String {
        iconCode?.let { code ->
            return when (code) {
                1, 2, 3, 4, 5, 30, 33, 34 -> "sun"
                12, 13, 14, 18, 39, 40 -> "rain"
                15, 16, 17, 41, 42 -> "storm"
                else -> "cloud"
            }
        }

        return when {
            "thunder" in normalizedCondition || "storm" in normalizedCondition || "tempest" in normalizedCondition -> "storm"
            "rain" in normalizedCondition || "drizzle" in normalizedCondition || "chuva" in normalizedCondition -> "rain"
            "clear" in normalizedCondition || "sun" in normalizedCondition || "sol" in normalizedCondition -> "sun"
            "cloud" in normalizedCondition || "nublado" in normalizedCondition || "overcast" in normalizedCondition -> "cloud"
            else -> "default"
        }
    }

    private fun widgetWeatherAssets(): Map<String, List<String>> {
        val json = intent.getStringExtra(EXTRA_WIDGET_BAR_WEATHER_ASSETS_JSON).orEmpty()
        if (json.isBlank()) return emptyMap()
        return runCatching {
            val payload = JSONObject(json)
            val result = mutableMapOf<String, List<String>>()
            payload.keys().forEach { key ->
                val value = payload.opt(key)
                val urls = when (value) {
                    is JSONArray -> (0 until value.length()).mapNotNull { index ->
                        value.optString(index).trim().takeIf { it.isNotBlank() }
                    }
                    else -> payload.optString(key).trim().takeIf { it.isNotBlank() }?.let { listOf(it) }.orEmpty()
                }
                if (urls.isNotEmpty()) result[key] = urls
            }
            result.toMap()
        }.getOrDefault(emptyMap())
    }

    private fun weatherIconFor(condition: String, iconCode: Int? = null): Int {
        iconCode?.let { code ->
            return when (code) {
                1, 2, 3, 4, 5, 30, 33, 34 -> R.drawable.ic_weather_sun
                12, 13, 14, 18, 39, 40 -> R.drawable.ic_weather_rain
                15, 16, 17, 41, 42 -> R.drawable.ic_weather_storm
                else -> R.drawable.ic_weather_cloud
            }
        }

        val normalized = condition.lowercase()
        return when {
            "thunder" in normalized || "storm" in normalized -> R.drawable.ic_weather_storm
            "rain" in normalized || "drizzle" in normalized -> R.drawable.ic_weather_rain
            "clear" in normalized || "sun" in normalized -> R.drawable.ic_weather_sun
            else -> R.drawable.ic_weather_cloud
        }
    }

    private fun dpToPx(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun widgetBarThicknessPx(): Int {
        return dpToPx(68)
    }

    private fun widgetBarEdgeSpacingPx(): Int {
        return dpToPx(intent.getIntExtra(EXTRA_WIDGET_BAR_EDGE_SPACING, 0).coerceIn(0, 120))
    }

    private fun refreshWidgetWeather() {
        if (!isWidgetBarEnabled()) return
        thread {
            val temperature = runCatching { apiClient.fetchTemperature() }.getOrDefault("--")
            runOnUiThread {
                if (!isWidgetBarEnabled()) return@runOnUiThread
                val label = temperature.trim().ifBlank { "--" }
                binding.widgetWeatherText.text = if (label.contains("°")) {
                    label
                } else {
                    "Clima $label"
                }
            }
        }
    }

    private fun isOfficialAppPlayback(): Boolean {
        return intent.getStringExtra(EXTRA_PLAYBACK_APP_TYPE).orEmpty() == "official_app"
    }

    private fun isOfficialAppWebDisplayEnabled(): Boolean {
        return intent.getBooleanExtra(EXTRA_OFFICIAL_APP_ROTATION_ENABLED, false) &&
            !intent.getStringExtra(EXTRA_OFFICIAL_APP_PAGE_URL).isNullOrBlank()
    }

    private fun isOfficialAppWebOnly(): Boolean {
        return intent.getBooleanExtra(EXTRA_OFFICIAL_APP_WEB_ONLY, false)
    }

    private fun configureBrowserViewport(isPortrait: Boolean) {
        binding.browserView.settings.apply {
            useWideViewPort = !isPortrait
            loadWithOverviewMode = !isPortrait
        }
        binding.browserView.setInitialScale(if (isPortrait) 100 else 0)
    }

    private fun resetVideoPresentation() {
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        binding.root.rotation = 0f
        binding.root.scaleX = 1f
        binding.root.scaleY = 1f
        binding.playbackSurface.rotation = 0f
        binding.playbackSurface.scaleX = 1f
        binding.playbackSurface.scaleY = 1f
        binding.playbackSurface.translationX = 0f
        binding.playbackSurface.translationY = 0f
        binding.playerView.rotation = 0f
        binding.playerView.scaleX = 1f
        binding.playerView.scaleY = 1f
        binding.playerView.translationX = 0f
        binding.playerView.translationY = 0f
        binding.playlistImageView.rotation = 0f
        binding.playlistImageView.scaleX = 1f
        binding.playlistImageView.scaleY = 1f
        binding.playlistImageView.translationX = 0f
        binding.playlistImageView.translationY = 0f
        binding.previousPlaylistImageView.rotation = 0f
        binding.previousPlaylistImageView.scaleX = 1f
        binding.previousPlaylistImageView.scaleY = 1f
        binding.previousPlaylistImageView.translationX = 0f
        binding.previousPlaylistImageView.translationY = 0f
        binding.previousPlaylistImageView.visibility = View.GONE
        binding.playerView.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        binding.playlistImageView.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        binding.previousPlaylistImageView.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        binding.playbackSurface.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
    }

    private fun applyTransition(showBrowser: Boolean, immediate: Boolean = false) {
        val style = intent.getStringExtra(EXTRA_OFFICIAL_APP_TRANSITION_STYLE).orEmpty().ifBlank { "blur" }
        val duration = if (immediate || style == "none") {
            0L
        } else {
            intent.getIntExtra(EXTRA_OFFICIAL_APP_TRANSITION_DURATION_MS, 900).toLong().coerceIn(250L, 1_800L)
        }

        val mediaView = mediaSurfaceView()
        val enteringView = if (showBrowser) binding.browserView else mediaView
        val leavingView = if (showBrowser) mediaView else binding.browserView

        hideHud()
        isTransitioning = !immediate && duration > 0L
        prepareTransitionView(enteringView)
        prepareTransitionView(leavingView)
        enteringView.visibility = View.VISIBLE
        enteringView.bringToFront()
        if (showBrowser) {
            bringPlayerOverlaysToFront()
        }
        enteringView.invalidate()
        leavingView.invalidate()

        if (immediate || duration == 0L) {
            enteringView.alpha = 1f
            enteringView.translationX = 0f
            enteringView.scaleX = 1f
            enteringView.scaleY = 1f
            leavingView.visibility = View.INVISIBLE
            resetTransitionView(enteringView)
            resetTransitionView(leavingView)
            finishTransition(showBrowser = showBrowser)
            return
        }

        val enterInterpolator = DecelerateInterpolator(1.8f)
        val leaveInterpolator = AccelerateInterpolator(1.35f)

        when (style) {
            "aponti_smooth" -> {
                enteringView.alpha = 0f
                enteringView.scaleX = 1.045f
                enteringView.scaleY = 1.045f
                leavingView.alpha = 1f
                leavingView.scaleX = 1f
                leavingView.scaleY = 1f
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    animateBlurOut(leavingView, duration)
                }

                leavingView.animate()
                    .alpha(0.28f)
                    .scaleX(0.985f)
                    .scaleY(0.985f)
                    .setInterpolator(AccelerateInterpolator(1.25f))
                    .setDuration((duration * 0.42f).toLong().coerceAtLeast(150L))
                    .start()

                showApontiSmoothOverlay(duration) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        animateBlurIn(enteringView, (duration * 0.58f).toLong().coerceAtLeast(180L))
                    }
                    enteringView.animate()
                        .alpha(1f)
                        .scaleX(1f)
                        .scaleY(1f)
                        .setInterpolator(DecelerateInterpolator(1.85f))
                        .setDuration((duration * 0.62f).toLong().coerceAtLeast(180L))
                        .withEndAction {
                            completeTransition(showBrowser, enteringView, leavingView)
                        }
                        .start()
                }
            }
            "slide" -> {
                val travel = ((enteringView.width.takeIf { it > 0 } ?: binding.root.width).takeIf { it > 0 } ?: 960) * 0.12f
                enteringView.alpha = 0.92f
                enteringView.translationX = if (showBrowser) travel else -travel
                enteringView.scaleX = 1.015f
                enteringView.scaleY = 1.015f
                leavingView.translationX = 0f
                leavingView.alpha = 1f
                leavingView.scaleX = 1f
                leavingView.scaleY = 1f

                enteringView.animate()
                    .translationX(0f)
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setInterpolator(enterInterpolator)
                    .setDuration(duration)
                    .start()
                leavingView.animate()
                    .translationX(if (showBrowser) -travel * 0.45f else travel * 0.45f)
                    .alpha(0f)
                    .scaleX(0.992f)
                    .scaleY(0.992f)
                    .setInterpolator(leaveInterpolator)
                    .setDuration(duration)
                    .withEndAction {
                        completeTransition(showBrowser, enteringView, leavingView)
                    }
                    .start()
            }

            "blur" -> {
                enteringView.alpha = 0f
                enteringView.scaleX = 1.025f
                enteringView.scaleY = 1.025f
                leavingView.alpha = 1f
                leavingView.scaleX = 1f
                leavingView.scaleY = 1f
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    animateBlurOut(leavingView, duration)
                }

                enteringView.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setInterpolator(enterInterpolator)
                    .setDuration(duration)
                    .start()
                leavingView.animate()
                    .alpha(0f)
                    .scaleX(0.985f)
                    .scaleY(0.985f)
                    .setInterpolator(leaveInterpolator)
                    .setDuration(duration)
                    .withEndAction {
                        completeTransition(showBrowser, enteringView, leavingView)
                    }
                    .start()
            }
            "zoom" -> {
                enteringView.alpha = 0f
                enteringView.scaleX = 1.08f
                enteringView.scaleY = 1.08f
                leavingView.alpha = 1f
                leavingView.scaleX = 1f
                leavingView.scaleY = 1f

                enteringView.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setInterpolator(enterInterpolator)
                    .setDuration(duration)
                    .start()
                leavingView.animate()
                    .alpha(0f)
                    .scaleX(0.965f)
                    .scaleY(0.965f)
                    .setInterpolator(leaveInterpolator)
                    .setDuration(duration)
                    .withEndAction {
                        completeTransition(showBrowser, enteringView, leavingView)
                    }
                    .start()
            }
            "cross_zoom" -> {
                enteringView.alpha = 0f
                enteringView.scaleX = 0.92f
                enteringView.scaleY = 0.92f
                leavingView.alpha = 1f
                leavingView.scaleX = 1f
                leavingView.scaleY = 1f

                enteringView.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setInterpolator(DecelerateInterpolator(2f))
                    .setDuration(duration)
                    .start()
                leavingView.animate()
                    .alpha(0f)
                    .scaleX(1.08f)
                    .scaleY(1.08f)
                    .setInterpolator(leaveInterpolator)
                    .setDuration(duration)
                    .withEndAction {
                        completeTransition(showBrowser, enteringView, leavingView)
                    }
                    .start()
            }
            "wipe" -> {
                val travel = ((enteringView.height.takeIf { it > 0 } ?: binding.root.height).takeIf { it > 0 } ?: 540) * 0.18f
                enteringView.alpha = 0f
                enteringView.translationY = travel
                leavingView.alpha = 1f
                leavingView.translationY = 0f

                enteringView.animate()
                    .translationY(0f)
                    .alpha(1f)
                    .setInterpolator(enterInterpolator)
                    .setDuration(duration)
                    .start()
                leavingView.animate()
                    .translationY(-travel * 0.55f)
                    .alpha(0f)
                    .setInterpolator(leaveInterpolator)
                    .setDuration(duration)
                    .withEndAction {
                        completeTransition(showBrowser, enteringView, leavingView)
                    }
                    .start()
            }
            "push" -> {
                val travel = ((enteringView.width.takeIf { it > 0 } ?: binding.root.width).takeIf { it > 0 } ?: 960) * 0.22f
                enteringView.alpha = 0.98f
                enteringView.translationX = if (showBrowser) travel else -travel
                leavingView.alpha = 1f
                leavingView.translationX = 0f

                enteringView.animate()
                    .translationX(0f)
                    .alpha(1f)
                    .setInterpolator(enterInterpolator)
                    .setDuration(duration)
                    .start()
                leavingView.animate()
                    .translationX(if (showBrowser) -travel else travel)
                    .alpha(0f)
                    .setInterpolator(leaveInterpolator)
                    .setDuration(duration)
                    .withEndAction {
                        completeTransition(showBrowser, enteringView, leavingView)
                    }
                    .start()
            }
            "flip" -> {
                enteringView.cameraDistance = resources.displayMetrics.density * 8000f
                leavingView.cameraDistance = resources.displayMetrics.density * 8000f
                enteringView.alpha = 0f
                enteringView.rotationY = if (showBrowser) -70f else 70f
                leavingView.alpha = 1f
                leavingView.rotationY = 0f

                enteringView.animate()
                    .rotationY(0f)
                    .alpha(1f)
                    .setInterpolator(enterInterpolator)
                    .setDuration(duration)
                    .start()
                leavingView.animate()
                    .rotationY(if (showBrowser) 42f else -42f)
                    .alpha(0f)
                    .setInterpolator(leaveInterpolator)
                    .setDuration(duration)
                    .withEndAction {
                        completeTransition(showBrowser, enteringView, leavingView)
                    }
                    .start()
            }

            else -> {
                enteringView.alpha = 0f
                enteringView.scaleX = 1.012f
                enteringView.scaleY = 1.012f
                leavingView.alpha = 1f
                leavingView.scaleX = 1f
                leavingView.scaleY = 1f

                enteringView.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setInterpolator(enterInterpolator)
                    .setDuration(duration)
                    .start()
                leavingView.animate()
                    .alpha(0f)
                    .scaleX(0.988f)
                    .scaleY(0.988f)
                    .setInterpolator(leaveInterpolator)
                    .setDuration(duration)
                    .withEndAction {
                        completeTransition(showBrowser, enteringView, leavingView)
                    }
                    .start()
            }
        }
    }

    private fun completeTransition(showBrowser: Boolean, enteringView: View, leavingView: View) {
        hideApontiSmoothOverlay()
        leavingView.visibility = View.INVISIBLE
        resetTransitionView(leavingView)
        resetTransitionView(enteringView)
        finishTransition(showBrowser = showBrowser)
        if (!showBrowser && browserRotationEnabled) {
            prepareBrowserViewInBackground {
                browserPreparedForDisplay = browserPageLoaded
            }
        }
    }

    private fun finishTransition(showBrowser: Boolean = false) {
        hideApontiSmoothOverlay()
        if (showBrowser) {
            player?.pause()
        }
        isTransitioning = false
        hideHud()
        hideSystemUi()
    }

    private fun showApontiSmoothOverlay(duration: Long, onLogoPeak: () -> Unit = {}) {
        val overlay = binding.apontiSmoothOverlay
        val logo = binding.apontiSmoothLogo
        val fadeInDuration = (duration * 0.32f).toLong().coerceIn(120L, 480L)
        val holdDelay = (duration * 0.38f).toLong().coerceIn(120L, 620L)
        val fadeOutDuration = (duration * 0.46f).toLong().coerceIn(180L, 780L)

        overlay.animate().cancel()
        logo.animate().cancel()
        overlay.visibility = View.VISIBLE
        overlay.bringToFront()
        bringPlayerOverlaysToFront()
        overlay.alpha = 0f
        logo.alpha = 0f
        logo.scaleX = 0.92f
        logo.scaleY = 0.92f

        overlay.animate()
            .alpha(1f)
            .setDuration(fadeInDuration)
            .setInterpolator(DecelerateInterpolator(1.5f))
            .start()

        logo.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(fadeInDuration)
            .setInterpolator(DecelerateInterpolator(1.7f))
            .withEndAction {
                onLogoPeak()
                overlay.postDelayed({
                    logo.animate()
                        .alpha(0f)
                        .scaleX(1.035f)
                        .scaleY(1.035f)
                        .setDuration(fadeOutDuration)
                        .setInterpolator(AccelerateInterpolator(1.2f))
                        .start()
                    overlay.animate()
                        .alpha(0f)
                        .setDuration(fadeOutDuration)
                        .setInterpolator(AccelerateInterpolator(1.15f))
                        .withEndAction { hideApontiSmoothOverlay() }
                        .start()
                }, holdDelay)
            }
            .start()
    }

    private fun hideApontiSmoothOverlay() {
        binding.apontiSmoothOverlay.animate().cancel()
        binding.apontiSmoothLogo.animate().cancel()
        binding.apontiSmoothOverlay.alpha = 0f
        binding.apontiSmoothOverlay.visibility = View.GONE
        binding.apontiSmoothLogo.alpha = 0f
        binding.apontiSmoothLogo.scaleX = 1f
        binding.apontiSmoothLogo.scaleY = 1f
    }

    private fun mediaSurfaceView(): View {
        return if (playlistPlaybackActive && playlistItems.getOrNull(currentPlaylistIndex)?.type == "image") {
            binding.playlistImageView
        } else {
            binding.playerView
        }
    }

    private fun prepareTransitionView(view: View) {
        view.animate().cancel()
        view.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            view.setRenderEffect(null)
        }
    }

    private fun animateBlurOut(view: View, duration: Long) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return

        ValueAnimator.ofFloat(0f, 14f).apply {
            this.duration = (duration * 0.82f).toLong()
            interpolator = DecelerateInterpolator(1.4f)
            addUpdateListener { animator ->
                val radius = animator.animatedValue as Float
                view.setRenderEffect(
                    RenderEffect.createBlurEffect(radius.coerceAtLeast(0.1f), radius.coerceAtLeast(0.1f), Shader.TileMode.CLAMP)
                )
            }
            start()
        }
    }

    private fun animateBlurIn(view: View, duration: Long) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return

        ValueAnimator.ofFloat(14f, 0f).apply {
            this.duration = (duration * 0.82f).toLong()
            interpolator = DecelerateInterpolator(1.4f)
            addUpdateListener { animator ->
                val radius = animator.animatedValue as Float
                if (radius <= 0.1f) {
                    view.setRenderEffect(null)
                } else {
                    view.setRenderEffect(
                        RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP)
                    )
                }
            }
            start()
        }
    }

    private fun resetTransitionView(view: View) {
        view.alpha = 1f
        view.translationX = 0f
        view.translationY = 0f
        view.rotationY = 0f
        view.scaleX = 1f
        view.scaleY = 1f
        view.setLayerType(View.LAYER_TYPE_NONE, null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            view.setRenderEffect(null)
        }
    }

    private fun hideHud() {
        hudManuallyRequested = false
        retryHandler.removeCallbacks(hideHudRunnable)
        binding.playerHeader.visibility = View.GONE
        binding.playerView.hideController()
        hideSystemUi()
    }

    private fun prepareBrowserViewInBackground(onReady: () -> Unit = {}) {
        prepareBrowserViewLayout(onReady = {
            binding.browserView.visibility = View.VISIBLE
            binding.browserView.alpha = 0f
            mediaSurfaceView().bringToFront()
            bringPlayerOverlaysToFront()
            onReady()
        })
    }

    private fun prepareBrowserViewForDisplay(onReady: () -> Unit) {
        prepareBrowserViewLayout(onReady = {
            binding.browserView.visibility = View.VISIBLE
            binding.browserView.bringToFront()
            bringPlayerOverlaysToFront()
            binding.browserView.alpha = 1f
            binding.browserView.scrollTo(0, 0)
            binding.browserView.postDelayed({ binding.browserView.scrollTo(0, 0) }, 250)
            onReady()
        })
    }

    private fun prepareBrowserViewLayout(onReady: () -> Unit, syncWidgetMode: Boolean = true) {
        val isPortrait = isPortraitOrientation()
        configureBrowserViewport(isPortrait)

        if (!isPortrait) {
            binding.browserView.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            binding.browserView.rotation = 0f
            binding.browserView.translationX = 0f
            binding.browserView.translationY = 0f
            binding.browserView.scaleX = 1f
            binding.browserView.scaleY = 1f
            binding.browserView.requestLayout()
            runAfterBrowserLayout(onReady)
            if (syncWidgetMode) applyWidgetContentMode(visible = binding.widgetBar.visibility == View.VISIBLE)
            return
        }

        binding.root.post {
            val surfaceW = binding.playbackSurface.width
            val surfaceH = binding.playbackSurface.height
            if (surfaceW <= 0 || surfaceH <= 0) {
                onReady()
                return@post
            }

            binding.browserView.layoutParams = FrameLayout.LayoutParams(surfaceH, surfaceW).apply {
                gravity = Gravity.CENTER
            }
            binding.browserView.pivotX = surfaceH / 2f
            binding.browserView.pivotY = surfaceW / 2f
            binding.browserView.rotation = portraitContentRotation()
            binding.browserView.scaleX = 1f
            binding.browserView.scaleY = 1f
            binding.browserView.translationX = 0f
            binding.browserView.translationY = 0f
            binding.browserView.requestLayout()
            if (syncWidgetMode) applyWidgetContentMode(visible = binding.widgetBar.visibility == View.VISIBLE)
            runAfterBrowserLayout {
                fitPortraitBrowserToScreen(surfaceW, surfaceH)
                onReady()
            }
        }
    }

    private fun fitPortraitBrowserToScreen(screenW: Int, screenH: Int) {
        binding.browserView.scaleX = 1f
        binding.browserView.scaleY = 1f
        binding.browserView.translationX = 0f
        binding.browserView.translationY = 0f
        binding.browserView.scrollTo(0, 0)
    }

    private fun runAfterBrowserLayout(onReady: () -> Unit) {
        binding.browserView.viewTreeObserver.addOnPreDrawListener(
            object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    if (binding.browserView.viewTreeObserver.isAlive) {
                        binding.browserView.viewTreeObserver.removeOnPreDrawListener(this)
                    }
                    binding.browserView.post(onReady)
                    return true
                }
            }
        )
    }

    private fun resetBrowserViewOrientation() {
        configureBrowserViewport(false)
        binding.browserView.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        binding.browserView.rotation = 0f
        binding.browserView.translationX = 0f
        binding.browserView.translationY = 0f
        binding.browserView.scaleX = 1f
        binding.browserView.scaleY = 1f
    }

    private fun hideSystemUi() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let { controller ->
                controller.systemBarsBehavior =
                    android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(
                    android.view.WindowInsets.Type.statusBars() or
                        android.view.WindowInsets.Type.navigationBars()
                )
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }

    private fun showError(message: String) {
        binding.downloadOverlay.animate().cancel()
        binding.downloadOverlay.visibility = View.GONE
        binding.playerView.visibility = View.VISIBLE
        binding.errorText.text = message
        binding.errorText.visibility = View.VISIBLE
    }

    private fun handlePlayerStartupFailure(error: Throwable) {
        Log.e(TAG, "Player startup failed", error)
        runCatching {
            if (::binding.isInitialized) {
                val startupMessage = error.message ?: getString(R.string.player_start_failed)
                showError(startupMessage)
                reportPlayerError(startupMessage)
                retryHandler.postDelayed({ returnToSelection() }, GIVE_UP_REDIRECT_DELAY_MS)
            } else {
                Toast.makeText(this, getString(R.string.player_start_failed), Toast.LENGTH_LONG).show()
                finish()
            }
        }.onFailure {
            Toast.makeText(this, getString(R.string.player_start_failed), Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun reportPlayerPresence(playerStatus: String, playlistItemId: Long? = null) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread { reportPlayerPresence(playerStatus, playlistItemId) }
            return
        }

        val channelId = intent.getLongExtra(EXTRA_CHANNEL_ID, -1L)
        if (channelId <= 0L) return
        val effectivePlaylistItemId = playlistItemId ?: currentPlaylistItem()?.id?.takeIf { playlistPlaybackActive && playerStatus == "playing" }
        val positionMs = currentPlaybackPositionForReport().takeIf { playerStatus == "playing" }

        thread {
            runCatching { apiClient.reportPlayerStatus(channelId, playerStatus, effectivePlaylistItemId, positionMs) }
        }
    }

    private fun currentPlaybackPositionForReport(): Long? {
        if (!playlistPlaybackActive) return null

        val item = currentPlaylistItem() ?: return null
        return if (item.type == "video") {
            player?.currentPosition?.coerceAtLeast(0L)
        } else {
            (SystemClock.elapsedRealtime() - currentPlaylistItemStartedRealtimeMs).coerceAtLeast(0L)
        }
    }

    private fun reportPlayerError(message: String) {
        val channelId = intent.getLongExtra(EXTRA_CHANNEL_ID, -1L)
        if (channelId <= 0L) return

        thread {
            runCatching { apiClient.reportPlayerStatus(channelId, "error", message = message) }
        }
    }

    private fun returnToSelection(suppressCurrentPlayback: Boolean = true) {
        if (awaitingSelectionReturn) return

        awaitingSelectionReturn = true
        stopOfficialAppRotation()
        retryHandler.removeCallbacksAndMessages(null)
        statusHandler.removeCallbacksAndMessages(null)
        binding.root.animate().cancel()
        binding.playerView.animate().cancel()
        binding.playlistImageView.animate().cancel()
        binding.previousPlaylistImageView.animate().cancel()
        binding.browserView.animate().cancel()
        releasePlayer()
        releasePreloadedPlaylistPlayer()
        releasePlaylistNotificationPlayer()
        val channelId = intent.getLongExtra(EXTRA_CHANNEL_ID, -1L)

        thread {
            if (channelId > 0L) {
                runCatching {
                    apiClient.reportPlayerStatus(
                        channelId,
                        "offline",
                        message = "Retorno manual para a lista de TVs"
                    )
                }
            }
            runOnUiThread {
                startActivity(
                    Intent(this, MainActivity::class.java).apply {
                        putExtra(MainActivity.EXTRA_FORCE_SELECTION_MODE, true)
                        if (suppressCurrentPlayback) {
                            putExtra(MainActivity.EXTRA_SUPPRESS_CHANNEL_ID, channelId)
                            putExtra(MainActivity.EXTRA_SUPPRESS_CONFIG_VERSION, currentConfigVersion)
                        }
                        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    }
                )
                finish()
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        hideSystemUi()
        if (event.action == KeyEvent.ACTION_DOWN && !isTransitioning && !showingBrowser && event.keyCode != KeyEvent.KEYCODE_BACK) {
            showHudFromUserInteraction()
        }
        if (showingBrowser && event.keyCode != KeyEvent.KEYCODE_BACK) return true
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        hideSystemUi()
        if (ev.action == MotionEvent.ACTION_DOWN && !isTransitioning && !showingBrowser) {
            showHudFromUserInteraction()
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun showHudFromUserInteraction() {
        hudManuallyRequested = true
        retryHandler.removeCallbacks(hideHudRunnable)
        binding.playerView.showController()
        binding.playerHeader.visibility = View.VISIBLE
        retryHandler.postDelayed(hideHudRunnable, 2_500L)
    }

    override fun onBackPressed() {
        returnToSelection()
    }

    private fun parsePlaylistItems(rawJson: String): List<PlaylistItem> {
        if (rawJson.isBlank() || rawJson == "[]") return emptyList()

        return runCatching {
            val jsonArray = JSONArray(rawJson)
            buildList {
                for (index in 0 until jsonArray.length()) {
                    val item = jsonArray.optJSONObject(index) ?: continue
                    val url = item.optString("url").trim()
                    if (url.isBlank()) continue

                    add(
                        PlaylistItem(
                            id = item.optLong("id", index.toLong()),
                            type = item.optString("type", "video").trim().ifBlank { "video" },
                            name = item.optString("name").trim().takeIf { it.isNotBlank() },
                            url = url,
                            durationSeconds = item.optInt("duration_seconds", 10).coerceAtLeast(3),
                            videoDurationMode = item.optString("video_duration_mode", "image_duration").trim().ifBlank { "image_duration" },
                            transitionStyle = item.optString("transition_style", "fade").trim().ifBlank { "fade" },
                            transitionDurationMs = item.optInt("transition_duration_ms", 600).coerceIn(0, 10_000)
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun synchronizePlaylistMediaCache(items: List<PlaylistItem>) {
        val urls = items.map { it.url }
        playlistCacheExecutor.execute { playlistMediaCache.synchronize(urls) }
    }

    private fun playlistItemsToJson(items: List<PlaylistItem>): String {
        val jsonArray = JSONArray()
        items.forEach { item ->
            jsonArray.put(
                JSONObject()
                    .put("id", item.id)
                    .put("type", item.type)
                    .put("name", item.name)
                    .put("url", item.url)
                    .put("duration_seconds", item.durationSeconds)
                    .put("video_duration_mode", item.videoDurationMode)
                    .put("transition_style", item.transitionStyle)
                    .put("transition_duration_ms", item.transitionDurationMs)
            )
        }
        return jsonArray.toString()
    }

    companion object {
        private const val TAG = "PlayerActivity"
        const val EXTRA_CHANNEL_ID = "channel_id"
        const val EXTRA_CHANNEL_NAME = "channel_name"
        const val EXTRA_STREAM_URL = "stream_url"
        const val EXTRA_PLAYBACK_URL = "playback_url"
        const val EXTRA_PLAYBACK_MODE = "playback_mode"
        const val EXTRA_ORIENTATION = "orientation"
        const val EXTRA_PLAYBACK_APP_TYPE = "playback_app_type"
        const val EXTRA_CONFIG_VERSION = "config_version"
        const val EXTRA_OFFICIAL_APP_ROTATION_ENABLED = "official_app_rotation_enabled"
        const val EXTRA_OFFICIAL_APP_WEB_ONLY = "official_app_web_only"
        const val EXTRA_OFFICIAL_APP_PAGE_URL = "official_app_page_url"
        const val EXTRA_OFFICIAL_APP_LOGIN = "official_app_login"
        const val EXTRA_OFFICIAL_APP_ROTATION_TRIGGER = "official_app_rotation_trigger"
        const val EXTRA_OFFICIAL_APP_SWITCH_INTERVAL_SECONDS = "official_app_switch_interval_seconds"
        const val EXTRA_OFFICIAL_APP_PAGE_DURATION_SECONDS = "official_app_page_duration_seconds"
        const val EXTRA_OFFICIAL_APP_TRANSITION_STYLE = "official_app_transition_style"
        const val EXTRA_OFFICIAL_APP_TRANSITION_DURATION_MS = "official_app_transition_duration_ms"
        const val EXTRA_DIRECT_VIDEO_URL = "direct_video_url"
        const val EXTRA_PLAYLIST_ITEMS_JSON = "playlist_items_json"
        const val EXTRA_PLAYLIST_SYNC_ENABLED = "playlist_sync_enabled"
        const val EXTRA_PLAYLIST_SYNC_STARTED_AT_MS = "playlist_sync_started_at_ms"
        const val EXTRA_PLAYLIST_SYNC_SERVER_TIME_MS = "playlist_sync_server_time_ms"
        const val EXTRA_PLAYLIST_NOTIFICATION_ENABLED = "playlist_notification_enabled"
        const val EXTRA_PLAYLIST_NOTIFICATION_URL = "playlist_notification_url"
        const val EXTRA_PLAYLIST_NOTIFICATION_VERSION = "playlist_notification_version"
        const val EXTRA_WIDGET_BAR_ENABLED = "widget_bar_enabled"
        const val EXTRA_WIDGET_BAR_STYLE = "widget_bar_style"
        const val EXTRA_WIDGET_BAR_COLOR = "widget_bar_color"
        const val EXTRA_WIDGET_BAR_OPACITY = "widget_bar_opacity"
        const val EXTRA_WIDGET_BAR_BLUR_ENABLED = "widget_bar_blur_enabled"
        const val EXTRA_WIDGET_BAR_BEHAVIOR = "widget_bar_behavior"
        const val EXTRA_WIDGET_BAR_ANIMATION = "widget_bar_animation"
        const val EXTRA_WIDGET_BAR_LAYOUT_MODE = "widget_bar_layout_mode"
        const val EXTRA_WIDGET_BAR_EDGE_SPACING = "widget_bar_edge_spacing"
        const val EXTRA_WIDGET_BAR_SHOW_SECONDS = "widget_bar_show_seconds"
        const val EXTRA_WIDGET_BAR_APPEAR_SECONDS = "widget_bar_appear_seconds"
        const val EXTRA_WIDGET_BAR_HIDE_SECONDS = "widget_bar_hide_seconds"
        const val EXTRA_WIDGET_BAR_WEATHER_API_URL = "widget_bar_weather_api_url"
        const val EXTRA_WIDGET_BAR_WEATHER_TEST_CONDITION = "widget_bar_weather_test_condition"
        const val EXTRA_WIDGET_BAR_CONTENT_MODE = "widget_bar_content_mode"
        const val EXTRA_WIDGET_FORECAST_ENABLED = "widget_forecast_enabled"
        const val EXTRA_WIDGET_FORECAST_DAYS = "widget_forecast_days"
        const val EXTRA_WIDGET_FORECAST_ANIMATION_ENABLED = "widget_forecast_animation_enabled"
        const val EXTRA_WIDGET_FORECAST_TRAVEL_SECONDS = "widget_forecast_travel_seconds"
        const val EXTRA_WIDGET_FORECAST_PAUSE_SECONDS = "widget_forecast_pause_seconds"
        const val EXTRA_WIDGET_FORECAST_CARD_ANIMATION = "widget_forecast_card_animation"
        const val EXTRA_WIDGET_FORECAST_DISPLAY_MODE = "widget_forecast_display_mode"
        const val EXTRA_WIDGET_FORECAST_DISPLAY_MINUTES = "widget_forecast_display_minutes"
        const val EXTRA_WIDGET_FORECAST_LATITUDE = "widget_forecast_latitude"
        const val EXTRA_WIDGET_FORECAST_LONGITUDE = "widget_forecast_longitude"
        const val EXTRA_WIDGET_FORECAST_TIMEZONE = "widget_forecast_timezone"
        const val EXTRA_WIDGET_BAR_WEATHER_ASSETS_JSON = "widget_bar_weather_assets_json"
        const val EXTRA_KEEP_APP_FOREGROUND_ENABLED = "keep_app_foreground_enabled"
        private const val STATUS_POLL_INTERVAL_MS = 2_000L
        private const val PRESENTATION_POLL_INTERVAL_MS = 250L
        private const val CLOCK_WIDGET_INTERVAL_MS = 30_000L
        private const val WEATHER_WIDGET_INTERVAL_MS = 10 * 60 * 1000L
        private const val WEATHER_CONTRAST_INTERVAL_MS = 350L
        private const val PREFS_PLAYLIST_NOTIFICATION = "playlist_notification"
        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY_MS = 3_000L
        private const val GIVE_UP_REDIRECT_DELAY_MS = 3_000L
        private const val DIRECT_VIDEO_TIMEOUT_MS = 45_000L
        private const val PLAYBACK_READY_TIMEOUT_MS = 30_000L
        private const val PLAYBACK_RESTART_DELAY_MS = 1_500L
        private const val PLAYLIST_SYNC_SEEK_THRESHOLD_MS = 350L
        private const val PLAYER_SPLASH_DURATION_MS = 4_300L
        private const val PLAYER_SPLASH_VIDEO_START_DELAY_MS = 450L
        private const val VIDEO_MIN_BUFFER_MS = 12_000
        private const val VIDEO_MAX_BUFFER_MS = 75_000
        private const val VIDEO_PLAYBACK_BUFFER_MS = 2_500
        private const val VIDEO_REBUFFER_BUFFER_MS = 5_000
    }
}
