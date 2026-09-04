package br.com.softextv.player

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class PresentationPointerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 168, 85, 247)
        maskFilter = BlurMaskFilter(22f, BlurMaskFilter.Blur.NORMAL)
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(168, 85, 247)
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    private var pointerX = .5f
    private var pointerY = .5f

    init { setLayerType(LAYER_TYPE_SOFTWARE, null) }

    fun moveTo(x: Float, y: Float) {
        pointerX = x.coerceIn(0f, 1f)
        pointerY = y.coerceIn(0f, 1f)
        visibility = VISIBLE
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = pointerX * width
        val cy = pointerY * height
        canvas.drawCircle(cx, cy, 25f, glowPaint)
        canvas.drawCircle(cx, cy, 13f, ringPaint)
        canvas.drawCircle(cx, cy, 5f, dotPaint)
    }
}
