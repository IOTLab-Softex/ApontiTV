package br.com.softextv.player

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.util.AttributeSet
import android.widget.HorizontalScrollView

/** Fades the moving cards into the actual bar background. */
class ForecastScrollView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : HorizontalScrollView(context, attrs) {
    private val mask = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }

    override fun dispatchDraw(canvas: Canvas) {
        val left = scrollX.toFloat()
        val right = left + width
        val layer = canvas.saveLayer(left, 0f, right, height.toFloat(), null)
        super.dispatchDraw(canvas)
        val edge = (14f * resources.displayMetrics.density / width.coerceAtLeast(1)).coerceAtMost(0.45f)
        mask.shader = LinearGradient(left, 0f, right, 0f,
            intArrayOf(0x00FFFFFF, -1, -1, 0x00FFFFFF),
            floatArrayOf(0f, edge, 1f - edge, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(left, 0f, right, height.toFloat(), mask)
        canvas.restoreToCount(layer)
    }
}
