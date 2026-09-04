package br.com.softextv.player

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.HapticFeedbackConstants
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import kotlin.concurrent.thread
import kotlin.math.abs
import org.json.JSONArray

class PresentationRemoteActivity : AppCompatActivity(), SensorEventListener {
    private val apiClient by lazy { TvApiClient(applicationContext, BuildConfig.API_BASE_URLS) }
    private val refreshHandler = Handler(Looper.getMainLooper())
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null
    private var channels: List<TvChannel> = emptyList()
    private var sending = false
    private var lastShakeAt = 0L
    private var gravityX = 0f
    private var gravityY = 0f
    private var gravityZ = 0f
    private var selectedSlideId: Long? = null
    private var pointerEnabled = false
    private var pointerX = .5f
    private var pointerY = .5f
    private var lastGyroscopeTimestamp = 0L
    private var lastPointerSentAt = 0L

    private lateinit var spinner: Spinner
    private lateinit var status: TextView
    private lateinit var playPause: MaterialButton
    private lateinit var previous: MaterialButton
    private lateinit var next: MaterialButton
    private lateinit var slideStrip: LinearLayout
    private lateinit var showSlide: MaterialButton
    private lateinit var pointerButton: MaterialButton

    private val refreshRunnable = object : Runnable {
        override fun run() {
            loadPresentationTvs(silent = true)
            refreshHandler.postDelayed(this, 5_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_presentation_remote)
        spinner = findViewById(R.id.presentationTvSpinner)
        status = findViewById(R.id.remoteStatus)
        playPause = findViewById(R.id.playPauseButton)
        previous = findViewById(R.id.previousSlideButton)
        next = findViewById(R.id.nextSlideButton)
        slideStrip = findViewById(R.id.presentationSlideStrip)
        showSlide = findViewById(R.id.showSelectedSlideButton)
        pointerButton = findViewById(R.id.pointerModeButton)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        findViewById<View>(R.id.shakeHint).visibility = if (accelerometer == null) View.GONE else View.VISIBLE

        previous.setOnClickListener { sendCommand("previous") }
        next.setOnClickListener { sendCommand("next") }
        playPause.setOnClickListener {
            val paused = selectedChannel()?.presentationControl?.paused == true
            sendCommand(if (paused) "play" else "pause")
        }
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedSlideId = selectedChannel()?.presentationControl?.currentItemId
                renderSlides()
                updatePlayPauseLabel()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        showSlide.setOnClickListener {
            selectedSlideId?.let { sendCommand("show:$it") }
        }
        pointerButton.setOnClickListener { togglePointerMode() }
        setControlsEnabled(false)
        loadPresentationTvs(silent = false)
    }

    override fun onResume() {
        super.onResume()
        accelerometer?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        gyroscope?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        refreshHandler.post(refreshRunnable)
    }

    override fun onPause() {
        sensorManager.unregisterListener(this)
        refreshHandler.removeCallbacks(refreshRunnable)
        super.onPause()
    }

    private fun loadPresentationTvs(silent: Boolean) {
        thread {
            runCatching { apiClient.fetchChannels().filter { it.presentationControl?.enabled == true } }
                .onSuccess { fresh ->
                    runOnUiThread {
                        val selectedId = selectedChannel()?.id
                        channels = fresh
                        spinner.adapter = ArrayAdapter(
                            this,
                            android.R.layout.simple_spinner_dropdown_item,
                            channels.map { it.name }
                        )
                        val restoredIndex = channels.indexOfFirst { it.id == selectedId }
                        if (restoredIndex >= 0) spinner.setSelection(restoredIndex)
                        setControlsEnabled(channels.isNotEmpty() && !sending)
                        status.text = if (channels.isEmpty()) {
                            "Nenhuma TV está com o modo apresentação ativado."
                        } else {
                            "${channels.size} TV(s) disponível(is)"
                        }
                        updatePlayPauseLabel()
                        renderSlides()
                    }
                }
                .onFailure { error ->
                    if (!silent) runOnUiThread { status.text = error.message ?: "Falha ao carregar TVs." }
                }
        }
    }

    private fun selectedChannel(): TvChannel? = channels.getOrNull(spinner.selectedItemPosition)

    private fun sendCommand(command: String, quiet: Boolean = false) {
        val channel = selectedChannel() ?: return
        if (sending) return
        sending = true
        if (!quiet) {
            setControlsEnabled(false)
            status.text = "Enviando comando para ${channel.name}..."
        }
        thread {
            runCatching { apiClient.sendPresentationCommand(channel.id, command) }
                .onSuccess { control ->
                    runOnUiThread {
                        val index = channels.indexOfFirst { it.id == channel.id }
                        if (index >= 0) channels = channels.toMutableList().also {
                            it[index] = it[index].copy(presentationControl = control)
                        }
                        if (!quiet) status.text = when (command) {
                            command.takeIf { it.startsWith("show:") } -> "Slide exibido na TV"
                            "next" -> "Próximo slide enviado"
                            "previous" -> "Slide anterior enviado"
                            "pause" -> "Apresentação pausada"
                            else -> "Apresentação retomada"
                        }
                        updatePlayPauseLabel()
                    }
                }
                .onFailure { error -> runOnUiThread {
                    Toast.makeText(this, error.message ?: "Falha no comando", Toast.LENGTH_SHORT).show()
                    status.text = "Não foi possível controlar ${channel.name}."
                } }
            runOnUiThread {
                sending = false
                setControlsEnabled(channels.isNotEmpty())
            }
        }
    }

    private fun updatePlayPauseLabel() {
        playPause.text = if (selectedChannel()?.presentationControl?.paused == true) "PLAY" else "PAUSE"
    }

    private fun setControlsEnabled(enabled: Boolean) {
        previous.isEnabled = enabled
        next.isEnabled = enabled
        playPause.isEnabled = enabled
        spinner.isEnabled = enabled
        showSlide.isEnabled = enabled && selectedSlideId != null
        pointerButton.isEnabled = enabled && gyroscope != null
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_GYROSCOPE) {
            handlePointerMotion(event)
            return
        }
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        // Remove gravity and react only to a dominant lateral impulse. This
        // prevents vertical movement from accidentally changing the slide.
        gravityX = SENSOR_FILTER * gravityX + (1f - SENSOR_FILTER) * event.values[0]
        gravityY = SENSOR_FILTER * gravityY + (1f - SENSOR_FILTER) * event.values[1]
        gravityZ = SENSOR_FILTER * gravityZ + (1f - SENSOR_FILTER) * event.values[2]
        val lateralX = event.values[0] - gravityX
        val verticalY = event.values[1] - gravityY
        val depthZ = event.values[2] - gravityZ
        val now = System.currentTimeMillis()
        val isLateralGesture = abs(lateralX) > LATERAL_SHAKE_THRESHOLD &&
            abs(lateralX) > abs(verticalY) * 1.25f &&
            abs(lateralX) > abs(depthZ) * 1.25f
        if (isLateralGesture && now - lastShakeAt > SHAKE_COOLDOWN_MS && !sending && channels.isNotEmpty()) {
            lastShakeAt = now
            findViewById<View>(R.id.shakeHint).performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            // The accelerometer reaction points opposite to the physical hand movement.
            sendCommand(if (lateralX < 0f) "next" else "previous")
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun playlistItems(channel: TvChannel?): List<PlaylistItem> {
        val json = channel?.playlistItemsJson ?: return emptyList()
        return runCatching {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val id = item.optLong("id", 0L)
                val url = item.optString("url")
                if (id <= 0L || url.isBlank()) return@mapNotNull null
                PlaylistItem(
                    id = id,
                    type = item.optString("type", "image"),
                    name = item.optString("name").takeIf { it.isNotBlank() },
                    url = url,
                    durationSeconds = item.optInt("duration_seconds", 10),
                    videoDurationMode = item.optString("video_duration_mode", "image_duration"),
                    transitionStyle = item.optString("transition_style", "fade"),
                    transitionDurationMs = item.optInt("transition_duration_ms", 600)
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun renderSlides() {
        slideStrip.removeAllViews()
        val items = playlistItems(selectedChannel())
        if (selectedSlideId == null || items.none { it.id == selectedSlideId }) {
            selectedSlideId = selectedChannel()?.presentationControl?.currentItemId ?: items.firstOrNull()?.id
        }
        items.forEachIndexed { index, item ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER
                setPadding(5.dp, 5.dp, 5.dp, 5.dp)
                layoutParams = LinearLayout.LayoutParams(112.dp, 104.dp).apply { marginEnd = 10.dp }
                setOnClickListener { selectedSlideId = item.id; renderSlides() }
            }
            val selected = item.id == selectedSlideId
            card.background = GradientDrawable().apply {
                cornerRadius = 14.dp.toFloat()
                setColor(if (selected) Color.rgb(76, 29, 149) else Color.rgb(24, 30, 48))
                setStroke(if (selected) 3.dp else 1.dp, if (selected) Color.rgb(168, 85, 247) else Color.rgb(55, 65, 81))
            }
            val preview = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 70.dp)
                setBackgroundColor(Color.rgb(10, 15, 28))
            }
            if (item.type == "image") RemoteImageLoader.loadInto(preview, item.url)
            else preview.setImageResource(android.R.drawable.ic_media_play)
            val label = TextView(this).apply {
                text = "${index + 1}. ${item.name ?: if (item.type == "video") "Vídeo" else "Imagem"}"
                setTextColor(Color.WHITE)
                textSize = 11f
                maxLines = 1
                gravity = android.view.Gravity.CENTER
            }
            card.addView(preview)
            card.addView(label, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 28.dp))
            slideStrip.addView(card)
        }
        showSlide.isEnabled = items.isNotEmpty() && !sending
    }

    private fun togglePointerMode() {
        pointerEnabled = !pointerEnabled
        pointerX = .5f
        pointerY = .5f
        lastGyroscopeTimestamp = 0L
        pointerButton.text = if (pointerEnabled) "DESATIVAR PONTEIRO" else "ATIVAR PONTEIRO"
        if (pointerEnabled) sendCommand("pointer:${pointerX}:${pointerY}", quiet = true)
        else sendCommand("pointer_hide", quiet = true)
    }

    private fun handlePointerMotion(event: SensorEvent) {
        if (!pointerEnabled || channels.isEmpty()) return
        if (lastGyroscopeTimestamp == 0L) {
            lastGyroscopeTimestamp = event.timestamp
            return
        }
        val dt = ((event.timestamp - lastGyroscopeTimestamp) / 1_000_000_000f).coerceAtMost(.08f)
        lastGyroscopeTimestamp = event.timestamp
        pointerX = (pointerX - event.values[1] * dt * POINTER_SENSITIVITY).coerceIn(0f, 1f)
        pointerY = (pointerY - event.values[0] * dt * POINTER_SENSITIVITY).coerceIn(0f, 1f)
        val now = System.currentTimeMillis()
        if (now - lastPointerSentAt >= POINTER_SEND_INTERVAL_MS && !sending) {
            lastPointerSentAt = now
            sendCommand("pointer:${"%.3f".format(java.util.Locale.US, pointerX)}:${"%.3f".format(java.util.Locale.US, pointerY)}", quiet = true)
        }
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    companion object {
        private const val SENSOR_FILTER = 0.82f
        private const val LATERAL_SHAKE_THRESHOLD = 8.5f
        private const val SHAKE_COOLDOWN_MS = 650L
        private const val POINTER_SENSITIVITY = .42f
        private const val POINTER_SEND_INTERVAL_MS = 100L
    }
}
