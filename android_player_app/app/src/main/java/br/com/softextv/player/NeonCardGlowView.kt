package br.com.softextv.player

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

class NeonCardGlowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val glowRect = RectF()
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val inset = 25f
        val radius = min(width, height) * 0.085f
        glowRect.set(inset, inset, width - inset, height - inset)

        drawGlow(canvas, 22f, 5.5f, Color.argb(160, 177, 76, 255))
        drawGlow(canvas, 11f, 2.8f, Color.argb(205, 203, 142, 255))
        drawGlow(canvas, 4f, 1.5f, Color.argb(220, 255, 255, 255))

        glowPaint.clearShadowLayer()
        glowPaint.strokeWidth = 1.05f
        glowPaint.color = Color.argb(230, 255, 255, 255)
        canvas.drawRoundRect(glowRect, radius, radius, glowPaint)

        glowPaint.strokeWidth = 0.85f
        glowPaint.color = Color.argb(145, 157, 72, 255)
        canvas.drawRoundRect(glowRect, radius + 1.5f, radius + 1.5f, glowPaint)
    }

    private fun drawGlow(canvas: Canvas, blur: Float, stroke: Float, color: Int) {
        val radius = min(width, height) * 0.085f
        glowPaint.strokeWidth = stroke
        glowPaint.color = color
        glowPaint.setShadowLayer(blur, 0f, 0f, color)
        canvas.drawRoundRect(glowRect, radius, radius, glowPaint)
    }
}
