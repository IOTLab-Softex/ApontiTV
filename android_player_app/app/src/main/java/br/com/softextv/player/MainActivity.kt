package br.com.softextv.player

import android.content.Intent
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.view.KeyEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewParent
import android.view.animation.DecelerateInterpolator
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import br.com.softextv.player.databinding.ActivityMainBinding
import org.json.JSONObject
import java.util.Date
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val refreshHandler = Handler(Looper.getMainLooper())
    private val snapHelper = PagerSnapHelper()
    private var launchingPlayer = false
    private var openingServerSettings = false
    private var splashShownAtMs = 0L
    private var focusedChannelId: Long? = null
    private var configButtonArmed = false
    private val adapter = TvChannelAdapter(
        onFocusChanged = { channelId ->
            focusedChannelId = channelId
            configButtonArmed = false
            binding.channelList.post { applyCarouselEffect() }
        },
        onMoveUpFromCard = {
            binding.serverConfigButton.requestFocus()
            configButtonArmed = true
        },
        onClick = { channel ->
            forceSelectionMode = false
            safeOpenPlayer(channel)
        }
    )

    private val apiClient by lazy { TvApiClient(applicationContext, BuildConfig.API_BASE_URLS) }
    private val endpointSettings by lazy { ServerEndpointSettings(applicationContext) }
    private val channelListCache by lazy { ChannelListCache(applicationContext) }
    private val localPowerScheduleManager by lazy { LocalPowerScheduleManager(applicationContext) }
    private var splashDismissed = false
    private var splashPlayer: MediaPlayer? = null
    private var splashSurface: Surface? = null
    private var splashStartRunnable: Runnable? = null
    private var splashVideoStartedAtMs: Long = 0L
    private var forceSelectionMode = false
    private var suppressAutoLaunchUntilMs: Long = 0L
    private var suppressedChannelId: Long? = null
    private var suppressedConfigVersion: String? = null
    private var forcedLaunchChannelId: Long? = null
    private var currentPresenceChannelId: Long? = null
    private var lastLoadedChannels: List<TvChannel> = emptyList()
    private var lastChannelVisualSignature: String = ""
    private var carouselDisplayIndex = 0
    private var loadingChannels = false
    private var localPowerStandbyMode = false
    private var lastAdbAuthorizationRequestAtMs = 0L
    private val channelRefreshRunnable = object : Runnable {
        override fun run() {
            if (!launchingPlayer && !localPowerStandbyMode) {
                loadChannels(backgroundRefresh = true)
            }
            refreshHandler.postDelayed(this, CHANNEL_REFRESH_INTERVAL_MS)
        }
    }
    private val localPowerStandbyRunnable = object : Runnable {
        override fun run() {
            thread {
                val result = localPowerScheduleManager.evaluate(serverAvailable = false)
                runOnUiThread {
                    if (result.shouldEnterStandby) {
                        if (result.success) {
                            refreshHandler.postDelayed(this, LOCAL_POWER_STANDBY_RETRY_MS)
                            return@runOnUiThread
                        }
                        showLocalPowerStandby(result)
                        refreshHandler.postDelayed(this, LOCAL_POWER_STANDBY_RETRY_MS)
                    } else {
                        localPowerStandbyMode = false
                        startChannelRefresh()
                        loadChannels(backgroundRefresh = true)
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLayoutRotationApplier.apply(this, endpointSettings)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ApontiForegroundWatchdogReceiver.schedule(applicationContext)
        splashShownAtMs = System.currentTimeMillis()
        configureSplashVideo()
        binding.serverSettingsOverlay.visibility = View.GONE
        forceSelectionMode = intent.getBooleanExtra(EXTRA_FORCE_SELECTION_MODE, true)
        forcedLaunchChannelId = intent.getLongExtra(EXTRA_BROADCAST_ID, -1L).takeIf { it > 0L }
        suppressedChannelId = intent.getLongExtra(EXTRA_SUPPRESS_CHANNEL_ID, -1L).takeIf { it > 0L }
        suppressedConfigVersion = intent.getStringExtra(EXTRA_SUPPRESS_CONFIG_VERSION)

        binding.channelList.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.channelList.adapter = adapter
        binding.channelList.itemAnimator = null
        binding.channelList.setHasFixedSize(true)
        binding.channelList.elevation = 80f
        binding.channelList.translationZ = 80f
        snapHelper.attachToRecyclerView(binding.channelList)
        binding.channelList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                applyCarouselEffect()
            }
        })
        binding.channelList.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft) updateCarouselPadding()
        }
        binding.channelList.post {
            updateCarouselPadding()
            applyCarouselEffect()
        }

        binding.swipeRefresh.setOnRefreshListener { loadChannels(backgroundRefresh = true) }
        binding.retryButton.setOnClickListener { loadChannels() }
        binding.serverConfigButton.setOnFocusChangeListener { _, hasFocus ->
            configButtonArmed = hasFocus
        }
        binding.serverConfigButton.setOnClickListener {
            openServerSettings()
        }
        binding.serverConfigButton.setOnKeyListener { _, keyCode, event ->
            if (
                event.action == KeyEvent.ACTION_DOWN &&
                (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
            ) {
                openServerSettings()
                true
            } else {
                false
            }
        }

        loadChannels()
    }

    override fun onPause() {
        // Release the splash decoder while PlayerActivity is in front. Some
        // MediaTek TVs only provide one reliable H.264 decoder to WebView.
        releaseSplashPlayer()
        super.onPause()
    }

    override fun onStart() {
        super.onStart()
        ApontiForegroundState.markForeground(applicationContext)
        startChannelRefresh()
    }

    override fun onResume() {
        super.onResume()
        AppLayoutRotationApplier.apply(this, endpointSettings)
        openingServerSettings = false
        if (binding.splashOverlay.visibility == View.VISIBLE) {
            startSplashVideo()
        }
        if (localPowerStandbyMode) {
            refreshHandler.post(localPowerStandbyRunnable)
            return
        }
        if (launchingPlayer) {
            forceSelectionMode = true
            suppressAutoLaunchUntilMs = System.currentTimeMillis() + AUTO_LAUNCH_COOLDOWN_MS
        }
        launchingPlayer = false
        loadChannels(backgroundRefresh = true)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!launchingPlayer && !openingServerSettings) {
            ApontiForegroundState.markBackground(applicationContext)
            ApontiForegroundWatchdogReceiver.scheduleSoon(applicationContext)
        }
    }

    override fun onStop() {
        if (!launchingPlayer && !openingServerSettings) {
            reportCurrentPresence("offline")
            ApontiForegroundState.markBackground(applicationContext)
            ApontiForegroundWatchdogReceiver.scheduleSoon(applicationContext)
        }
        stopChannelRefresh()
        super.onStop()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (
                        binding.channelList.visibility == View.VISIBLE &&
                        currentFocus != binding.serverConfigButton &&
                        binding.serverSettingsOverlay.visibility != View.VISIBLE
                    ) {
                        binding.serverConfigButton.requestFocus()
                        configButtonArmed = true
                        return true
                    }
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (binding.channelList.visibility == View.VISIBLE && currentFocus != binding.serverConfigButton) {
                        moveCarouselSelection(-1)
                        return true
                    }
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (binding.channelList.visibility == View.VISIBLE && currentFocus != binding.serverConfigButton) {
                        moveCarouselSelection(1)
                        return true
                    }
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (currentFocus == binding.serverConfigButton) {
                        configButtonArmed = false
                        requestFocusedChannelCard()
                        return true
                    }
                }
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER -> {
                    if (configButtonArmed || currentFocus == binding.serverConfigButton) {
                        openServerSettings()
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.hasExtra(EXTRA_FORCE_SELECTION_MODE)) {
            forceSelectionMode = intent.getBooleanExtra(EXTRA_FORCE_SELECTION_MODE, true)
            forcedLaunchChannelId = intent.getLongExtra(EXTRA_BROADCAST_ID, -1L).takeIf { it > 0L }
            if (!forceSelectionMode) {
                suppressAutoLaunchUntilMs = 0L
                suppressedChannelId = null
                suppressedConfigVersion = null
                launchingPlayer = false
                loadChannels(backgroundRefresh = true)
                return
            }

            suppressAutoLaunchUntilMs = System.currentTimeMillis() + AUTO_LAUNCH_COOLDOWN_MS
            suppressedChannelId = intent.getLongExtra(EXTRA_SUPPRESS_CHANNEL_ID, -1L).takeIf { it > 0L } ?: suppressedChannelId
            suppressedConfigVersion = intent.getStringExtra(EXTRA_SUPPRESS_CONFIG_VERSION) ?: suppressedConfigVersion
            rememberSuppressedChannelVersion()
            launchingPlayer = false
            if (lastLoadedChannels.isNotEmpty()) {
                binding.splashOverlay.animate().cancel()
                binding.splashOverlay.visibility = View.VISIBLE
                binding.splashOverlay.alpha = 1f
                binding.splashMessage.text = getString(R.string.splash_loading)
                splashDismissed = false
                splashShownAtMs = System.currentTimeMillis()
                startSplashVideo()
                prepareInitialSelection(lastLoadedChannels)
            }
        }
    }

    private fun loadChannels(backgroundRefresh: Boolean = false) {
        if (loadingChannels) return
        loadingChannels = true

        if (!backgroundRefresh) {
            showLoading()
        }

        thread {
            runCatching { apiClient.fetchChannelList() }
                .onSuccess { response ->
                    runOnUiThread {
                        loadingChannels = false
                        binding.swipeRefresh.isRefreshing = false
                        if (response.channels.isEmpty()) {
                            showError(getString(R.string.no_channels_found))
                        } else {
                            val savedAtMs = System.currentTimeMillis()
                            channelListCache.save(response.rawBody, savedAtMs)
                            rememberLocalPowerSchedules(response.channels)
                            showListFreshness(isCached = false, savedAtMs = savedAtMs)
                            renderChannels(response.channels, backgroundRefresh = backgroundRefresh, fromCache = false)
                        }
                    }
                }
                .onFailure { error ->
                    val localPowerFallback = localPowerScheduleManager.evaluate(serverAvailable = false)
                    runOnUiThread {
                        loadingChannels = false
                        binding.swipeRefresh.isRefreshing = false
                        if (localPowerFallback.shouldEnterStandby) {
                            if (localPowerFallback.success) {
                                return@runOnUiThread
                            }
                            enterLocalPowerStandby(localPowerFallback)
                            return@runOnUiThread
                        }
                        val cachedList = channelListCache.load(apiClient)
                        if (cachedList != null) {
                            showListFreshness(isCached = true, savedAtMs = cachedList.savedAtMs)
                            renderChannels(cachedList.channels, backgroundRefresh = backgroundRefresh, fromCache = true)
                        } else {
                            showError(error.message ?: getString(R.string.generic_error))
                        }
                    }
                }
        }
    }

    private fun rememberLocalPowerSchedules(channels: List<TvChannel>) {
        val matchedChannel = channels.firstOrNull { it.deviceMatch } ?: channels.firstOrNull(::matchesCurrentDevice)
        matchedChannel?.let {
            localPowerScheduleManager.remember(it)
            prepareLocalAdbIfNeeded(it)
            requestAdbAuthorizationIfNeeded(it)
        }
    }

    private fun prepareLocalAdbIfNeeded(channel: TvChannel) {
        if (channel.powerSchedule?.enabled != true) return
        thread {
            runCatching { LocalAdbPowerController(applicationContext).prepareAuthorization() }
        }
    }

    private fun requestAdbAuthorizationIfNeeded(channel: TvChannel) {
        if (channel.id <= 0L) return
        val now = System.currentTimeMillis()
        if (now - lastAdbAuthorizationRequestAtMs < ADB_AUTHORIZATION_REQUEST_INTERVAL_MS) return

        lastAdbAuthorizationRequestAtMs = now
        thread {
            runCatching { apiClient.requestAdbAuthorization(channel.id) }
        }
    }

    private fun renderChannels(channels: List<TvChannel>, backgroundRefresh: Boolean, fromCache: Boolean) {
        val visualSignature = channels.toCarouselVisualSignature()
        val hasVisualChanges = visualSignature != lastChannelVisualSignature

        lastLoadedChannels = channels
        updateKeepOpenPreference(channels)
        updateHomeStats(channels)
        reportMatchedPresence(channels, "online")

        if (!hasVisualChanges && backgroundRefresh) {
            if (!fromCache) attemptMatchedChannelAutoLaunch(channels)
            return
        }

        lastChannelVisualSignature = visualSignature
        adapter.submitList(channels.toCarouselDisplayList())
        binding.channelList.post {
            applyCarouselEffect()
            if (backgroundRefresh) {
                val openedPlayer = if (!fromCache) {
                    attemptMatchedChannelAutoLaunch(channels)
                } else {
                    false
                }
                if (forceSelectionMode && !openedPlayer) {
                    revealSelectionList()
                    return@post
                }
            } else {
                binding.channelList.visibility = View.GONE
                binding.statusGroup.visibility = View.GONE
                prepareInitialSelection(channels)
            }
        }
    }

    private fun List<TvChannel>.toCarouselVisualSignature(): String =
        joinToString(separator = "|") { channel ->
            listOf(
                channel.id,
                channel.name,
                channel.streamUrl,
                channel.thumbnailUrl.orEmpty(),
                channel.status.orEmpty(),
                channel.deviceMatch,
                channel.configVersion.orEmpty()
            ).joinToString(separator = "~")
        }

    private fun List<TvChannel>.toCarouselDisplayList(): List<TvChannel> {
        if (size <= 1) return this
        return buildList(size * CAROUSEL_REPEAT_COUNT) {
            repeat(CAROUSEL_REPEAT_COUNT) {
                addAll(this@toCarouselDisplayList)
            }
        }
    }

    private fun showLoading() {
        binding.channelList.visibility = View.GONE
        binding.statusGroup.visibility = View.VISIBLE
        binding.listFreshnessStatus.visibility = View.VISIBLE
        binding.listFreshnessStatus.setBackgroundResource(R.drawable.list_status_fresh)
        binding.listFreshnessStatus.text = getString(R.string.list_status_updating)
        binding.statusTitle.text = getString(R.string.loading_channels)
        binding.statusMessage.text = getString(R.string.loading_channels_hint, apiClient.endpointLabel())
        binding.retryButton.visibility = View.GONE
        binding.splashOverlay.animate().cancel()
        binding.splashOverlay.visibility = View.VISIBLE
        binding.splashOverlay.alpha = 1f
        binding.splashMessage.text = getString(R.string.splash_loading)
        splashDismissed = false
        splashShownAtMs = System.currentTimeMillis()
        startSplashVideo()
    }

    private fun revealSelectionList() {
        binding.splashOverlay.animate().cancel()
        binding.serverSettingsOverlay.visibility = View.GONE
        hideSplash()
        binding.channelList.visibility = View.VISIBLE
        binding.statusGroup.visibility = View.GONE
        updateCarouselPadding()
        applyCarouselEffect()
        if (binding.channelList.focusedChild == null) {
            binding.channelList.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
                ?: binding.serverConfigButton.requestFocus()
        }
    }

    private fun openServerSettings() {
        configButtonArmed = false
        openingServerSettings = true
        forceSelectionMode = true
        suppressAutoLaunchUntilMs = System.currentTimeMillis() + AUTO_LAUNCH_COOLDOWN_MS
        revealSelectionList()
        startActivity(Intent(this, ServerSettingsActivity::class.java))
    }

    private fun showError(message: String) {
        binding.channelList.visibility = View.GONE
        binding.statusGroup.visibility = View.VISIBLE
        binding.listFreshnessStatus.visibility = View.GONE
        binding.statusTitle.text = getString(R.string.error_loading_channels)
        binding.statusMessage.text = message
        binding.retryButton.visibility = View.VISIBLE
        hideSplash()
    }

    private fun handleLocalPowerScheduleFallback(): Boolean {
        val result = localPowerScheduleManager.evaluate(serverAvailable = false)
        if (!result.shouldEnterStandby) return false

        enterLocalPowerStandby(result)
        return true
    }

    private fun enterLocalPowerStandby(result: LocalPowerScheduleManager.EvaluationResult) {
        if (result.success) {
            return
        }
        localPowerStandbyMode = true
        launchingPlayer = false
        loadingChannels = false
        stopChannelRefresh()
        showLocalPowerStandby(result)
        refreshHandler.removeCallbacks(localPowerStandbyRunnable)
        refreshHandler.postDelayed(localPowerStandbyRunnable, LOCAL_POWER_STANDBY_RETRY_MS)
    }

    private fun showLocalPowerStandby(result: LocalPowerScheduleManager.EvaluationResult) {
        binding.swipeRefresh.isRefreshing = false
        binding.channelList.visibility = View.GONE
        binding.listFreshnessStatus.visibility = View.GONE
        binding.statusGroup.visibility = View.VISIBLE
        binding.statusTitle.text = getString(R.string.power_schedule_standby_title)
        binding.statusMessage.text = if (result.success) {
            getString(R.string.power_schedule_standby_success)
        } else {
            getString(R.string.power_schedule_standby_retrying)
        }
        binding.retryButton.visibility = View.GONE
        binding.splashOverlay.animate().cancel()
        hideSplash()
        if (!DevicePlatform.isFireTv()) {
            moveTaskToBack(true)
        }
    }

    private fun showListFreshness(isCached: Boolean, savedAtMs: Long) {
        val savedAtLabel = formatSavedAt(savedAtMs)
        binding.listFreshnessStatus.visibility = View.VISIBLE
        if (isCached) {
            binding.listFreshnessStatus.setBackgroundResource(R.drawable.list_status_cached)
            binding.listFreshnessStatus.text = getString(R.string.list_status_cached, savedAtLabel)
            return
        }

        binding.listFreshnessStatus.setBackgroundResource(R.drawable.list_status_fresh)
        binding.listFreshnessStatus.text = getString(R.string.list_status_fresh, savedAtLabel)
    }

    private fun formatSavedAt(savedAtMs: Long): String {
        if (savedAtMs <= 0L) return "--:--"
        return DateFormat.getTimeFormat(this).format(Date(savedAtMs))
    }

    private fun prepareInitialSelection(channels: List<TvChannel>) {
        if (forceSelectionMode) {
            focusChannelAt(0, revealList = true) {
                dismissSplash()
            }
            return
        }

        val matchedIndex = channels.indexOfFirst { it.deviceMatch || matchesCurrentDevice(it) }
        if (matchedIndex >= 0) {
            binding.splashMessage.text = getString(R.string.splash_matching_device)
            focusChannelAt(matchedIndex, revealList = false) {
                attemptMatchedChannelAutoLaunch(channels)
            }
            return
        }

        focusChannelAt(0, revealList = true) {
            dismissSplash()
        }
    }

    private fun attemptMatchedChannelAutoLaunch(channels: List<TvChannel>): Boolean {
        if (launchingPlayer) return false
        if (System.currentTimeMillis() < suppressAutoLaunchUntilMs) return false

        val forcedChannel = forcedLaunchChannelId?.let { id -> channels.firstOrNull { it.id == id } }
        val matchedChannel = forcedChannel
            ?: channels.firstOrNull { it.deviceMatch }
            ?: channels.firstOrNull(::matchesCurrentDevice)
            ?: return false
        val playableUrl = matchedChannel.playbackUrl ?: matchedChannel.streamUrl
        val hasPlaylist = !matchedChannel.playlistItemsJson.isNullOrBlank() && matchedChannel.playlistItemsJson != "[]"
        val hasWebOnly = matchedChannel.officialAppBrowserRotation?.let {
            it.enabled && it.webOnly && !it.pageUrl.isNullOrBlank()
        } ?: false
        reportPresence(matchedChannel.id, "online")
        if (matchedChannel.status != "running") {
            if (suppressedChannelId == matchedChannel.id) {
                suppressedChannelId = null
                suppressedConfigVersion = null
            }
            return false
        }
        if (playableUrl.isBlank() && !hasPlaylist && !hasWebOnly) return false

        val isSameSuppressedPlayback =
            forceSelectionMode &&
                suppressedChannelId == matchedChannel.id &&
                (suppressedConfigVersion.isNullOrBlank() ||
                    suppressedConfigVersion.orEmpty() == matchedChannel.configVersion.orEmpty())

        if (isSameSuppressedPlayback) return false

        if (suppressedChannelId == matchedChannel.id) {
            suppressedChannelId = null
            suppressedConfigVersion = null
        }
        if (forcedLaunchChannelId == matchedChannel.id) forcedLaunchChannelId = null

        forceSelectionMode = false
        launchingPlayer = true
        safeOpenPlayer(matchedChannel)
        return true
    }

    private fun matchesCurrentDevice(channel: TvChannel): Boolean {
        val channelIp = channel.tvIp?.trim()?.takeIf { it.isNotBlank() } ?: return false
        return DeviceIdentityResolver.currentIpv4Addresses().any { it == channelIp }
    }

    private fun updateKeepOpenPreference(channels: List<TvChannel>) {
        val matchedChannel = channels.firstOrNull { it.deviceMatch } ?: channels.firstOrNull(::matchesCurrentDevice)
        ApontiForegroundState.setKeepOpenEnabled(
            applicationContext,
            matchedChannel?.keepAppForegroundEnabled == true
        )
    }

    private fun rememberSuppressedChannelVersion() {
        val channelId = suppressedChannelId ?: return
        if (!suppressedConfigVersion.isNullOrBlank()) return

        suppressedConfigVersion = lastLoadedChannels
            .firstOrNull { it.id == channelId }
            ?.configVersion
            ?.takeIf { it.isNotBlank() }
    }

    private fun startChannelRefresh() {
        if (localPowerStandbyMode) return
        refreshHandler.removeCallbacks(channelRefreshRunnable)
        refreshHandler.post(channelRefreshRunnable)
    }

    private fun stopChannelRefresh() {
        refreshHandler.removeCallbacks(channelRefreshRunnable)
    }

    private fun reportMatchedPresence(channels: List<TvChannel>, playerStatus: String) {
        val matchedChannel = channels.firstOrNull { it.deviceMatch } ?: channels.firstOrNull(::matchesCurrentDevice)
        if (matchedChannel != null) {
            reportPresence(matchedChannel.id, playerStatus)
        }
    }

    private fun reportCurrentPresence(playerStatus: String) {
        val channelId = currentPresenceChannelId ?: return
        reportPresence(channelId, playerStatus)
    }

    private fun reportPresence(channelId: Long, playerStatus: String) {
        if (channelId <= 0L) return
        currentPresenceChannelId = channelId

        thread {
            runCatching { apiClient.reportPlayerStatus(channelId, playerStatus) }
        }
    }

    private fun updateCarouselPadding() {
        binding.channelList.setPadding(
            0,
            binding.channelList.paddingTop,
            0,
            binding.channelList.paddingBottom
        )
    }

    private fun carouselCenterOffset(): Int {
        val recyclerWidth = binding.channelList.width
        if (recyclerWidth == 0) return 0
        val density = resources.displayMetrics.density
        val cardTotalWidthPx = ((292 + 10 + 10) * density + 0.5f).toInt()
        return ((recyclerWidth - cardTotalWidthPx) / 2).coerceAtLeast(0)
    }

    private fun updateHomeStats(channels: List<TvChannel>) {
        val liveCount = channels.count { it.status.equals("running", ignoreCase = true) }
        val onlineCount = channels.count { it.deviceMatch || matchesCurrentDevice(it) || it.status.equals("running", ignoreCase = true) }
        binding.homeLiveCount.text = "$liveCount NO AR"
        binding.homeOnlineCount.text = "$onlineCount ONLINE"
    }

    private fun focusChannelAt(index: Int, revealList: Boolean, onReady: () -> Unit) {
        val layoutManager = binding.channelList.layoutManager as? LinearLayoutManager
        val displayIndex = carouselDisplayIndexFor(index)
        carouselDisplayIndex = displayIndex
        layoutManager?.scrollToPositionWithOffset(displayIndex, carouselCenterOffset())
        binding.channelList.postDelayed({
            if (revealList) {
                binding.channelList.visibility = View.VISIBLE
                binding.statusGroup.visibility = View.GONE
            }
            applyCarouselEffect()
            binding.channelList.findViewHolderForAdapterPosition(displayIndex)?.itemView?.requestFocus()
            runAfterSplashMinimum(onReady)
        }, 260)
    }

    private fun requestFocusedChannelCard() {
        focusedChannelId
            ?.let(::findChildForChannel)
            ?.requestFocus()
            ?: binding.channelList.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
    }

    private fun moveCarouselSelection(direction: Int) {
        val sourceSize = lastLoadedChannels.size
        if (sourceSize == 0 || direction == 0) return

        if (carouselDisplayIndex <= 0) {
            val currentIndex = focusedChannelId
                ?.let { id -> lastLoadedChannels.indexOfFirst { it.id == id } }
                ?.takeIf { it >= 0 }
                ?: 0
            carouselDisplayIndex = carouselDisplayIndexFor(currentIndex)
        }

        val targetDisplayIndex = (carouselDisplayIndex + direction)
            .coerceIn(0, adapter.itemCount - 1)
        val targetIndex = Math.floorMod(targetDisplayIndex, sourceSize)
        focusedChannelId = lastLoadedChannels[targetIndex].id
        carouselDisplayIndex = targetDisplayIndex
        scrollToCarouselDisplayIndex(targetDisplayIndex, smooth = true)
    }

    private fun scrollToCarouselDisplayIndex(displayIndex: Int, smooth: Boolean, afterScroll: (() -> Unit)? = null) {
        val layoutManager = binding.channelList.layoutManager as? LinearLayoutManager ?: return
        if (smooth) {
            binding.channelList.stopScroll()
            val targetChild = findChildForAdapterPosition(displayIndex)
            if (targetChild != null) {
                val viewportCenterX = binding.channelList.width / 2
                val childCenterX = (targetChild.left + targetChild.right) / 2
                val dx = childCenterX - viewportCenterX
                binding.channelList.smoothScrollBy(
                    dx,
                    0,
                    DecelerateInterpolator(1.6f),
                    CAROUSEL_SCROLL_DURATION_MS
                )
            } else {
                binding.channelList.smoothScrollToPosition(displayIndex)
            }
        } else {
            layoutManager.scrollToPositionWithOffset(displayIndex, carouselCenterOffset())
        }
        binding.channelList.postDelayed({
            applyCarouselEffect()
            binding.channelList.findViewHolderForAdapterPosition(displayIndex)?.itemView?.requestFocus()
            afterScroll?.invoke()
        }, if (smooth) CAROUSEL_SCROLL_DURATION_MS + 40L else 40L)
    }

    private fun isFocusInsideChannelList(): Boolean {
        if (currentFocus == binding.channelList) return true
        var parent: ViewParent? = currentFocus?.parent
        while (parent != null) {
            if (parent == binding.channelList) return true
            parent = parent.parent
        }
        return false
    }

    private fun carouselDisplayIndexFor(originalIndex: Int): Int {
        val sourceSize = lastLoadedChannels.size
        if (sourceSize <= 1) return originalIndex.coerceAtLeast(0)
        val safeIndex = originalIndex.coerceIn(0, sourceSize - 1)
        return ((CAROUSEL_REPEAT_COUNT / 2) * sourceSize) + safeIndex
    }

    private fun dismissSplash(onComplete: (() -> Unit)? = null) {
        if (splashDismissed || binding.splashOverlay.visibility != View.VISIBLE) {
            onComplete?.invoke()
            return
        }

        splashDismissed = true
        binding.splashOverlay.animate()
            .alpha(0f)
            .setDuration(260)
            .withEndAction {
                binding.splashVideo.visibility = View.INVISIBLE
                stopSplashVideo()
                binding.splashOverlay.visibility = View.GONE
                binding.splashOverlay.alpha = 1f
                binding.splashVideo.alpha = 1f
                onComplete?.invoke()
            }
            .start()
    }

    private fun runAfterSplashMinimum(action: () -> Unit) {
        if (binding.splashOverlay.visibility != View.VISIBLE) {
            action()
            return
        }

        val startedAt = splashVideoStartedAtMs.takeIf { it > 0L } ?: splashShownAtMs
        val elapsed = System.currentTimeMillis() - startedAt
        val remaining = (MIN_SPLASH_DURATION_MS - elapsed).coerceAtLeast(0L)
        if (remaining == 0L) {
            action()
        } else {
            binding.splashOverlay.postDelayed(action, remaining)
        }
    }

    private fun hideSplash() {
        splashDismissed = true
        binding.splashOverlay.animate().cancel()
        binding.splashVideo.visibility = View.INVISIBLE
        stopSplashVideo()
        binding.splashOverlay.visibility = View.GONE
        binding.splashOverlay.alpha = 1f
        binding.splashVideo.alpha = 1f
    }

    private fun configureSplashVideo() {
        binding.splashVideo.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
                prepareSplashPlayer(surfaceTexture)
            }

            override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) = Unit

            override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
                releaseSplashPlayer()
                return true
            }

            override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) = Unit
        }
        binding.splashVideo.surfaceTexture?.let { prepareSplashPlayer(it) }
    }

    private fun prepareSplashPlayer(surfaceTexture: SurfaceTexture) {
        releaseSplashPlayer()
        val surface = Surface(surfaceTexture)
        splashSurface = surface
        splashPlayer = MediaPlayer().apply {
            setDataSource(applicationContext, Uri.parse("android.resource://$packageName/${R.raw.abertura_splash}"))
            setSurface(surface)
            isLooping = false
            setVolume(0f, 0f)
            setOnPreparedListener {
                if (binding.splashOverlay.visibility == View.VISIBLE && binding.splashVideo.visibility == View.VISIBLE) {
                    scheduleSplashVideoStart()
                }
            }
            setOnErrorListener { _, _, _ -> true }
            prepareAsync()
        }
    }

    private fun startSplashVideo() {
        runCatching {
            binding.splashVideo.visibility = View.VISIBLE
            binding.splashVideo.alpha = 1f
            val player = splashPlayer
            if (player != null) {
                player.seekTo(0)
                scheduleSplashVideoStart()
            } else {
                binding.splashVideo.surfaceTexture?.let { prepareSplashPlayer(it) }
            }
            Unit
        }
    }

    private fun stopSplashVideo() {
        splashStartRunnable?.let { refreshHandler.removeCallbacks(it) }
        splashStartRunnable = null
        splashVideoStartedAtMs = 0L
        runCatching {
            splashPlayer?.let { player ->
                if (player.isPlaying) player.pause()
                player.seekTo(0)
            }
        }
    }

    private fun scheduleSplashVideoStart() {
        splashStartRunnable?.let { refreshHandler.removeCallbacks(it) }
        val runnable = Runnable {
            if (splashDismissed || binding.splashOverlay.visibility != View.VISIBLE || binding.splashVideo.visibility != View.VISIBLE) return@Runnable
            runCatching {
                splashPlayer?.let { player ->
                    player.seekTo(0)
                    if (!player.isPlaying) {
                        player.start()
                        splashVideoStartedAtMs = System.currentTimeMillis()
                    }
                }
            }
        }
        splashStartRunnable = runnable
        refreshHandler.postDelayed(runnable, SPLASH_VIDEO_START_DELAY_MS)
    }

    private fun releaseSplashPlayer() {
        splashStartRunnable?.let { refreshHandler.removeCallbacks(it) }
        splashStartRunnable = null
        runCatching { splashPlayer?.release() }
        splashPlayer = null
        runCatching { splashSurface?.release() }
        splashSurface = null
    }

    private fun safeOpenPlayer(channel: TvChannel) {
        launchingPlayer = true
        runCatching { openPlayer(channel) }
            .onFailure { error ->
                launchingPlayer = false
                forceSelectionMode = true
                dismissSplash()
                binding.channelList.visibility = View.VISIBLE
                binding.statusGroup.visibility = View.GONE
                Toast.makeText(
                    this,
                    "Nao foi possivel abrir esta TV: ${error.message.orEmpty()}".trim(),
                    Toast.LENGTH_LONG
                ).show()
            }
    }

    private fun openPlayer(channel: TvChannel) {
        val playableUrl = channel.playbackUrl?.takeIf { it.isNotBlank() } ?: channel.streamUrl
        val hasPlaylist = !channel.playlistItemsJson.isNullOrBlank() && channel.playlistItemsJson != "[]"
        val hasWebOnly = channel.officialAppBrowserRotation?.let {
            it.enabled && it.webOnly && !it.pageUrl.isNullOrBlank()
        } ?: false
        require(channel.id > 0L) { "ID da TV invalido" }
        require(playableUrl.isNotBlank() || hasPlaylist || hasWebOnly) { "URL de reproducao vazia" }

        val orientation = channel.orientation?.takeIf { it == "landscape" || it == "portrait" || it == "portrait_inverted" } ?: "portrait"
        val playbackAppType = channel.playbackAppType?.takeIf { it.isNotBlank() } ?: "official_app"

        val intent = Intent(this, PlayerActivity::class.java)
            .putExtra(PlayerActivity.EXTRA_CHANNEL_ID, channel.id)
            .putExtra(PlayerActivity.EXTRA_CHANNEL_NAME, channel.name.ifBlank { "TV ${channel.id}" })
            .putExtra(PlayerActivity.EXTRA_STREAM_URL, channel.streamUrl)
            .putExtra(PlayerActivity.EXTRA_PLAYBACK_URL, playableUrl)
            .putExtra(PlayerActivity.EXTRA_PLAYBACK_MODE, channel.playbackMode)
            .putExtra(PlayerActivity.EXTRA_ORIENTATION, orientation)
            .putExtra(PlayerActivity.EXTRA_PLAYBACK_APP_TYPE, playbackAppType)
            .putExtra(PlayerActivity.EXTRA_CONFIG_VERSION, channel.configVersion)
            .putExtra(PlayerActivity.EXTRA_PLAYLIST_ITEMS_JSON, channel.playlistItemsJson)
            .putExtra(PlayerActivity.EXTRA_PLAYLIST_SYNC_ENABLED, channel.playlistSync?.enabled ?: false)
            .putExtra(PlayerActivity.EXTRA_PLAYLIST_SYNC_STARTED_AT_MS, channel.playlistSync?.startedAtMs ?: 0L)
            .putExtra(PlayerActivity.EXTRA_PLAYLIST_SYNC_SERVER_TIME_MS, channel.playlistSync?.serverTimeMs ?: 0L)
            .putExtra(PlayerActivity.EXTRA_PLAYLIST_NOTIFICATION_ENABLED, channel.playlistNotificationSound?.enabled ?: true)
            .putExtra(PlayerActivity.EXTRA_PLAYLIST_NOTIFICATION_URL, channel.playlistNotificationSound?.url)
            .putExtra(PlayerActivity.EXTRA_PLAYLIST_NOTIFICATION_VERSION, channel.playlistNotificationSound?.version)
            .putExtra(PlayerActivity.EXTRA_OFFICIAL_APP_PAGE_URL, channel.officialAppBrowserRotation?.pageUrl)
            .putExtra(PlayerActivity.EXTRA_OFFICIAL_APP_ROTATION_ENABLED, channel.officialAppBrowserRotation?.enabled ?: false)
            .putExtra(PlayerActivity.EXTRA_OFFICIAL_APP_WEB_ONLY, channel.officialAppBrowserRotation?.webOnly ?: false)
            .putExtra(PlayerActivity.EXTRA_OFFICIAL_APP_ROTATION_TRIGGER, channel.officialAppBrowserRotation?.rotationTrigger ?: "time_interval")
            .putExtra(PlayerActivity.EXTRA_OFFICIAL_APP_SWITCH_INTERVAL_SECONDS, channel.officialAppBrowserRotation?.switchIntervalSeconds ?: 300)
            .putExtra(PlayerActivity.EXTRA_OFFICIAL_APP_PAGE_DURATION_SECONDS, channel.officialAppBrowserRotation?.pageDurationSeconds ?: 15)
            .putExtra(PlayerActivity.EXTRA_DIRECT_VIDEO_URL, channel.officialAppBrowserRotation?.directVideoUrl)
            .putExtra(PlayerActivity.EXTRA_OFFICIAL_APP_TRANSITION_STYLE, channel.officialAppBrowserRotation?.transitionStyle ?: "blur")
            .putExtra(PlayerActivity.EXTRA_OFFICIAL_APP_TRANSITION_DURATION_MS, channel.officialAppBrowserRotation?.transitionDurationMs ?: 900)
            .putExtra(PlayerActivity.EXTRA_WIDGET_BAR_ENABLED, channel.officialAppWidgetBar?.enabled ?: false)
            .putExtra(PlayerActivity.EXTRA_WIDGET_BAR_STYLE, channel.officialAppWidgetBar?.style ?: "transparent_blur")
            .putExtra(PlayerActivity.EXTRA_WIDGET_BAR_COLOR, channel.officialAppWidgetBar?.color ?: "#0b1020")
            .putExtra(PlayerActivity.EXTRA_WIDGET_BAR_OPACITY, channel.officialAppWidgetBar?.opacity ?: 72)
            .putExtra(PlayerActivity.EXTRA_WIDGET_BAR_BLUR_ENABLED, channel.officialAppWidgetBar?.blurEnabled ?: true)
            .putExtra(PlayerActivity.EXTRA_WIDGET_BAR_BEHAVIOR, channel.officialAppWidgetBar?.behavior ?: "fixed")
            .putExtra(PlayerActivity.EXTRA_WIDGET_BAR_ANIMATION, channel.officialAppWidgetBar?.animation ?: "slide")
            .putExtra(PlayerActivity.EXTRA_WIDGET_BAR_LAYOUT_MODE, channel.officialAppWidgetBar?.layoutMode ?: "overlay")
            .putExtra(PlayerActivity.EXTRA_WIDGET_BAR_EDGE_SPACING, channel.officialAppWidgetBar?.edgeSpacing ?: 0)
            .putExtra(PlayerActivity.EXTRA_WIDGET_BAR_SHOW_SECONDS, channel.officialAppWidgetBar?.showSeconds ?: 8)
            .putExtra(PlayerActivity.EXTRA_WIDGET_BAR_APPEAR_SECONDS, channel.officialAppWidgetBar?.appearSeconds ?: 0)
            .putExtra(PlayerActivity.EXTRA_WIDGET_BAR_HIDE_SECONDS, channel.officialAppWidgetBar?.hideSeconds ?: 0)
            .putExtra(PlayerActivity.EXTRA_WIDGET_BAR_WEATHER_API_URL, channel.officialAppWidgetBar?.weatherApiUrl)
            .putExtra(PlayerActivity.EXTRA_WIDGET_BAR_WEATHER_TEST_CONDITION, channel.officialAppWidgetBar?.weatherTestCondition ?: "real")
            .putExtra(PlayerActivity.EXTRA_WIDGET_BAR_CONTENT_MODE, channel.officialAppWidgetBar?.contentMode ?: "time_weather")
            .putExtra(PlayerActivity.EXTRA_WIDGET_BAR_WEATHER_ASSETS_JSON, JSONObject(channel.officialAppWidgetBar?.weatherAssets ?: emptyMap<String, List<String>>()).toString())
            .putExtra(PlayerActivity.EXTRA_KEEP_APP_FOREGROUND_ENABLED, channel.keepAppForegroundEnabled)

        startActivity(intent)
    }

    private fun applyCarouselEffect() {
        val viewportCenterX =
            binding.channelList.width / 2f
        var activeChildId: Long? = null
        var smallestDistance = Float.MAX_VALUE
        val halfWidth = (binding.channelList.width / 2f).coerceAtLeast(1f)

        for (index in 0 until binding.channelList.childCount) {
            val child = binding.channelList.getChildAt(index)
            val childCenterX = (child.left + child.right) / 2f
            val signedDistance = childCenterX - viewportCenterX
            val distanceToCenter = kotlin.math.abs(signedDistance)
            if (distanceToCenter < smallestDistance) {
                smallestDistance = distanceToCenter
                val adapterPosition = binding.channelList.getChildAdapterPosition(child)
                activeChildId = adapter.channelIdAt(adapterPosition)
                if (adapterPosition >= 0) {
                    carouselDisplayIndex = adapterPosition
                }
            }
            val normalized = (distanceToCenter / halfWidth).coerceIn(0f, 1f)
            val direction = if (signedDistance < 0f) -1f else 1f

            val sideCurve = normalized * normalized
            val scale = 1.03f - (0.24f * normalized)
            val alpha = 1f - (0.42f * normalized)
            val rotationY = direction * (62f * sideCurve)
            val translationX = direction * (32f * sideCurve)
            val translationY = 16f * normalized
            val depth = 260f * (1f - normalized)

            child.cameraDistance = 2_400f
            child.pivotX = if (direction < 0f) child.width.toFloat() else 0f
            child.pivotY = child.height * 0.54f
            child.scaleX = scale
            child.scaleY = scale
            child.alpha = alpha
            child.rotationY = rotationY
            child.translationX = translationX
            child.translationY = translationY
            child.translationZ = depth
            child.elevation = depth
            child.z = depth
            (binding.channelList.getChildViewHolder(child) as? TvChannelAdapter.ChannelViewHolder)
                ?.setActive(distanceToCenter < (child.width * 0.48f))
        }

        adapter.setActiveChannel(activeChildId)
    }

    private fun findChildForChannel(channelId: Long): View? {
        for (index in 0 until binding.channelList.childCount) {
            val child = binding.channelList.getChildAt(index)
            val adapterPosition = binding.channelList.getChildAdapterPosition(child)
            if (adapter.channelIdAt(adapterPosition) == channelId) return child
        }
        return null
    }

    private fun findChildForAdapterPosition(adapterPosition: Int): View? {
        for (index in 0 until binding.channelList.childCount) {
            val child = binding.channelList.getChildAt(index)
            if (binding.channelList.getChildAdapterPosition(child) == adapterPosition) return child
        }
        return null
    }

    companion object {
        const val EXTRA_FORCE_SELECTION_MODE = "force_selection_mode"
        const val EXTRA_BROADCAST_ID = "broadcast_id"
        const val EXTRA_SUPPRESS_CHANNEL_ID = "suppress_channel_id"
        const val EXTRA_SUPPRESS_CONFIG_VERSION = "suppress_config_version"
        private const val CHANNEL_REFRESH_INTERVAL_MS = 5_000L
        private const val AUTO_LAUNCH_COOLDOWN_MS = 2_000L
        private const val LOCAL_POWER_STANDBY_RETRY_MS = 30_000L
        private const val ADB_AUTHORIZATION_REQUEST_INTERVAL_MS = 5 * 60 * 1000L
        private const val MIN_SPLASH_DURATION_MS = 4_300L
        private const val SPLASH_VIDEO_START_DELAY_MS = 450L
        private const val CAROUSEL_REPEAT_COUNT = 401
        private const val CAROUSEL_SCROLL_DURATION_MS = 360
    }
}
