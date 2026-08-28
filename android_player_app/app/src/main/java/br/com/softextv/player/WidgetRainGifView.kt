package br.com.softextv.player

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Movie
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import java.net.URL
import kotlin.concurrent.thread

class WidgetRainGifView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    @Volatile
    private var movie: Movie? = runCatching {
        resources.openRawResource(R.raw.sol).use { Movie.decodeStream(it) }
    }.getOrNull()
    @Volatile
    private var currentMovieUrl: String = ""
    private val startedAt = System.currentTimeMillis()
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun setMovieUrl(url: String?) {
        val normalizedUrl = url.orEmpty().trim()
        if (normalizedUrl == currentMovieUrl) return
        currentMovieUrl = normalizedUrl

        if (normalizedUrl.isBlank()) {
            movie = runCatching {
                resources.openRawResource(R.raw.sol).use { Movie.decodeStream(it) }
            }.getOrNull()
            postInvalidateOnAnimation()
            return
        }

        thread(name = "WidgetWeatherAssetLoader") {
            val loadedMovie = runCatching {
                URL(normalizedUrl).openStream().use { Movie.decodeStream(it) }
            }.getOrNull()

            if (currentMovieUrl == normalizedUrl && loadedMovie != null) {
                movie = loadedMovie
                postInvalidateOnAnimation()
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val gif = movie ?: return
        val duration = gif.duration().takeIf { it > 0 } ?: 1000
        val currentTime = ((System.currentTimeMillis() - startedAt) % duration).toInt()
        gif.setTime(currentTime)

        val scale = maxOf(width / gif.width().toFloat(), height / gif.height().toFloat()) * 1.02f
        val scaledWidth = gif.width() * scale
        val scaledHeight = gif.height() * scale
        val dx = (width - scaledWidth) * 0.5f
        val dy = (height - scaledHeight) / 2f
        val layer = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        canvas.save()
        canvas.translate(dx, dy)
        canvas.scale(scale, scale)
        val loopBlendMs = minOf(GIF_LOOP_BLEND_MS, duration / 3)
        val blendStart = (duration - loopBlendMs).coerceAtLeast(0)
        if (loopBlendMs > 0 && currentTime >= blendStart) {
            val progress = (currentTime - blendStart) / loopBlendMs.toFloat()
            val currentAlpha = ((1f - progress) * 255).toInt().coerceIn(0, 255)
            val nextAlpha = (progress * 255).toInt().coerceIn(0, 255)
            val blendPaint = Paint(Paint.ANTI_ALIAS_FLAG)

            blendPaint.alpha = currentAlpha
            gif.setTime(currentTime)
            gif.draw(canvas, 0f, 0f, blendPaint)

            blendPaint.alpha = nextAlpha
            gif.setTime((progress * loopBlendMs).toInt().coerceIn(0, duration - 1))
            gif.draw(canvas, 0f, 0f, blendPaint)
        } else {
            gif.draw(canvas, 0f, 0f)
        }
        canvas.restore()

        maskPaint.shader = LinearGradient(
            0f,
            0f,
            width.toFloat(),
            0f,
            intArrayOf(0xEE000000.toInt(), 0xEE000000.toInt(), 0xAA000000.toInt(), 0x00000000),
            floatArrayOf(0f, 0.55f, 0.78f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), maskPaint)
        canvas.restoreToCount(layer)
        postInvalidateOnAnimation()
    }

    companion object {
        private const val GIF_LOOP_BLEND_MS = 650
    }
}
