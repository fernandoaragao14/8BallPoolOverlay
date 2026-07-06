package app.hack.eightballpool

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
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

    private val rect = RectF()

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
        val color = indicator.color
        strokePaint.color = color
        fillPaint.color = Color.argb(
            40,
            Color.red(color),
            Color.green(color),
            Color.blue(color)
        )

        drawLineIfNeeded(canvas, indicator)

        when (indicator.shape) {
            VisualIndicatorShape.CIRCLE -> drawCircle(canvas, indicator)
            VisualIndicatorShape.BOX -> drawBox(canvas, indicator)
            VisualIndicatorShape.LINE -> drawLineEndpoint(canvas, indicator)
        }

        drawLabel(canvas, indicator)
    }

    private fun drawLineIfNeeded(canvas: Canvas, indicator: VisualIndicator) {
        val endX = indicator.lineEndX ?: return
        val endY = indicator.lineEndY ?: return

        strokePaint.strokeWidth = 4f
        canvas.drawLine(indicator.x, indicator.y, endX, endY, strokePaint)
        strokePaint.strokeWidth = 5f
    }

    private fun drawCircle(canvas: Canvas, indicator: VisualIndicator) {
        val radius = max(12f, indicator.radius)

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
    }

    private fun drawBox(canvas: Canvas, indicator: VisualIndicator) {
        rect.set(
            indicator.x - indicator.width / 2f,
            indicator.y - indicator.height / 2f,
            indicator.x + indicator.width / 2f,
            indicator.y + indicator.height / 2f
        )
        canvas.drawRoundRect(rect, 18f, 18f, fillPaint)
        canvas.drawRoundRect(rect, 18f, 18f, strokePaint)
    }

    private fun drawLineEndpoint(canvas: Canvas, indicator: VisualIndicator) {
        val radius = max(8f, indicator.radius)
        canvas.drawCircle(indicator.x, indicator.y, radius, fillPaint)
        canvas.drawCircle(indicator.x, indicator.y, radius, strokePaint)
    }

    private fun drawLabel(canvas: Canvas, indicator: VisualIndicator) {
        val label = indicator.label?.takeIf { it.isNotBlank() } ?: return
        val radius = max(12f, indicator.radius)
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
