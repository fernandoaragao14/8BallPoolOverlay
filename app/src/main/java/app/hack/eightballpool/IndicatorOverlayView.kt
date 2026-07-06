package app.hack.eightballpool

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import kotlin.math.max

class IndicatorOverlayView(context: Context) : View(context) {

    private var indicators: List<VisualIndicator> = emptyList()

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 30f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    fun setIndicators(value: List<VisualIndicator>) {
        indicators = value
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        indicators.forEach { indicator ->
            drawIndicator(canvas, indicator)
        }
    }

    private fun drawIndicator(canvas: Canvas, indicator: VisualIndicator) {
        val radius = max(12f, indicator.radius)
        val color = indicator.color

        fillPaint.color = Color.argb(
            40,
            Color.red(color),
            Color.green(color),
            Color.blue(color)
        )
        strokePaint.color = color

        canvas.drawCircle(indicator.x, indicator.y, radius, fillPaint)
        canvas.drawCircle(indicator.x, indicator.y, radius, strokePaint)

        val crossSize = radius * 0.45f
        canvas.drawLine(
            indicator.x - crossSize,
            indicator.y,
            indicator.x + crossSize,
            indicator.y,
            strokePaint
        )
        canvas.drawLine(
            indicator.x,
            indicator.y - crossSize,
            indicator.x,
            indicator.y + crossSize,
            strokePaint
        )

        indicator.label?.takeIf { it.isNotBlank() }?.let { label ->
            val padding = 12f
            val textWidth = textPaint.measureText(label)
            val textHeight = textPaint.textSize
            val left = indicator.x + radius + padding
            val baseline = indicator.y - radius - padding

            fillPaint.color = Color.argb(180, 0, 0, 0)
            canvas.drawRoundRect(
                left - padding,
                baseline - textHeight - padding,
                left + textWidth + padding,
                baseline + padding,
                14f,
                14f,
                fillPaint
            )
            canvas.drawText(label, left, baseline, textPaint)
        }
    }
}
