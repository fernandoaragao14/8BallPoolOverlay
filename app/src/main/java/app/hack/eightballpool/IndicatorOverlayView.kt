package app.hack.eightballpool

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Desenha a jogada analisada por cima da mesa. Focada em legibilidade:
 * linhas grossas com brilho, setas de direção, caminhos tracejados para a
 * bola alvo e rótulos com fundo para contraste sobre qualquer pano.
 */
class IndicatorOverlayView(context: Context) : View(context) {

    private var indicators: List<VisualIndicator> = emptyList()

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 32f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(190, 0, 0, 0)
    }

    private val dashEffect = DashPathEffect(floatArrayOf(26f, 18f), 0f)
    private val rect = RectF()

    fun setIndicators(value: List<VisualIndicator>) {
        indicators = value
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // Linhas primeiro, formas por cima, rótulos por último (ficam sempre legíveis).
        indicators.forEach { if (it.shape == VisualIndicatorShape.LINE) drawLine(canvas, it) }
        indicators.forEach {
            when (it.shape) {
                VisualIndicatorShape.RECT -> drawRect(canvas, it)
                VisualIndicatorShape.CIRCLE -> drawCircle(canvas, it)
                VisualIndicatorShape.MARKER -> drawMarker(canvas, it)
                VisualIndicatorShape.LINE -> Unit
            }
        }
        indicators.forEach { it.label?.let { _ -> drawLabel(canvas, it) } }
    }

    private fun drawLine(canvas: Canvas, indicator: VisualIndicator) {
        val width = indicator.strokeWidth

        // Brilho suave por baixo para destacar sobre o pano.
        glowPaint.color = withAlpha(indicator.color, 70)
        glowPaint.strokeWidth = width * 2.4f
        glowPaint.pathEffect = null
        canvas.drawLine(indicator.x, indicator.y, indicator.endX, indicator.endY, glowPaint)

        strokePaint.color = indicator.color
        strokePaint.strokeWidth = width
        strokePaint.pathEffect = if (indicator.dashed) dashEffect else null
        canvas.drawLine(indicator.x, indicator.y, indicator.endX, indicator.endY, strokePaint)
        strokePaint.pathEffect = null

        if (indicator.arrow) drawArrowHead(canvas, indicator)
    }

    private fun drawArrowHead(canvas: Canvas, indicator: VisualIndicator) {
        val angle = atan2((indicator.endY - indicator.y).toDouble(), (indicator.endX - indicator.x).toDouble())
        val headLength = max(26f, indicator.strokeWidth * 4f)
        val spread = Math.toRadians(26.0)

        strokePaint.color = indicator.color
        strokePaint.strokeWidth = indicator.strokeWidth
        for (side in intArrayOf(-1, 1)) {
            val a = angle + side * spread + Math.PI
            val hx = indicator.endX + (cos(a) * headLength).toFloat()
            val hy = indicator.endY + (sin(a) * headLength).toFloat()
            canvas.drawLine(indicator.endX, indicator.endY, hx, hy, strokePaint)
        }
    }

    private fun drawRect(canvas: Canvas, indicator: VisualIndicator) {
        rect.set(indicator.x, indicator.y, indicator.endX, indicator.endY)
        strokePaint.color = indicator.color
        strokePaint.strokeWidth = indicator.strokeWidth
        strokePaint.pathEffect = if (indicator.dashed) dashEffect else null
        canvas.drawRoundRect(rect, 22f, 22f, strokePaint)
        strokePaint.pathEffect = null
    }

    private fun drawCircle(canvas: Canvas, indicator: VisualIndicator) {
        val radius = max(10f, indicator.radius)

        if (indicator.fill) {
            fillPaint.color = withAlpha(indicator.color, 55)
            canvas.drawCircle(indicator.x, indicator.y, radius, fillPaint)
        }

        strokePaint.color = indicator.color
        strokePaint.strokeWidth = indicator.strokeWidth
        canvas.drawCircle(indicator.x, indicator.y, radius, strokePaint)

        if (indicator.crosshair) {
            val c = radius * 0.5f
            canvas.drawLine(indicator.x - c, indicator.y, indicator.x + c, indicator.y, strokePaint)
            canvas.drawLine(indicator.x, indicator.y - c, indicator.x, indicator.y + c, strokePaint)
        }
    }

    private fun drawMarker(canvas: Canvas, indicator: VisualIndicator) {
        val radius = max(7f, indicator.radius)
        fillPaint.color = indicator.color
        canvas.drawCircle(indicator.x, indicator.y, radius, fillPaint)
        strokePaint.color = Color.WHITE
        strokePaint.strokeWidth = 3f
        canvas.drawCircle(indicator.x, indicator.y, radius, strokePaint)
    }

    private fun drawLabel(canvas: Canvas, indicator: VisualIndicator) {
        val label = indicator.label?.takeIf { it.isNotBlank() } ?: return
        val padding = 12f
        val textWidth = textPaint.measureText(label)
        val textHeight = textPaint.textSize

        val anchorRadius = when (indicator.shape) {
            VisualIndicatorShape.CIRCLE -> max(10f, indicator.radius)
            else -> 10f
        }
        val left = indicator.x + anchorRadius + padding
        val baseline = indicator.y - anchorRadius - padding

        canvas.drawRoundRect(
            left - padding,
            baseline - textHeight - padding,
            left + textWidth + padding,
            baseline + padding,
            14f,
            14f,
            labelBgPaint
        )
        canvas.drawText(label, left, baseline, textPaint)
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
}
