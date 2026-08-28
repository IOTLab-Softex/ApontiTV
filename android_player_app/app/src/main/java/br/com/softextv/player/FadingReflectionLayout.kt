package br.com.softextv.player

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.util.AttributeSet
import android.widget.FrameLayout

class FadingReflectionLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }
    private var maskShader: LinearGradient? = null

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        clipChildren = false
        clipToPadding = false
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        maskShader = LinearGradient(
            0f,
            0f,
            0f,
            h.toFloat(),
            intArrayOf(0x9AFFFFFF.toInt(), 0x54FFFFFF, 0x20FFFFFF, 0x06FFFFFF, 0x00FFFFFF),
            floatArrayOf(0f, 0.24f, 0.52f, 0.82f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun dispatchDraw(canvas: Canvas) {
        val saveCount = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        val sourceHeight = if (childCount > 0) getChildAt(0).height.toFloat() else height.toFloat()
        val mirrorSaveCount = canvas.save()
        canvas.translate(0f, sourceHeight)
        canvas.scale(1f, -1f)
        super.dispatchDraw(canvas)
        canvas.restoreToCount(mirrorSaveCount)
        maskPaint.shader = maskShader
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), maskPaint)
        canvas.restoreToCount(saveCount)
    }
}
