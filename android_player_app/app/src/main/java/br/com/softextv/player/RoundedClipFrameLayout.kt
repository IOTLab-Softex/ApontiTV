package br.com.softextv.player

import android.content.Context
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.widget.FrameLayout

class RoundedClipFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val clipPath = Path()
    private val clipRect = RectF()
    private val cornerRadius = 22f * resources.displayMetrics.density

    override fun dispatchDraw(canvas: Canvas) {
        val saveCount = canvas.save()
        clipRect.set(0f, 0f, width.toFloat(), height.toFloat())
        clipPath.reset()
        clipPath.addRoundRect(clipRect, cornerRadius, cornerRadius, Path.Direction.CW)
        canvas.clipPath(clipPath)
        super.dispatchDraw(canvas)
        canvas.restoreToCount(saveCount)
    }
}
