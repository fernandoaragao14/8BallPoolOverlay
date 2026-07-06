package app.hack.eightballpool

import android.graphics.Color
import androidx.annotation.ColorInt
import java.util.concurrent.CopyOnWriteArrayList

enum class VisualIndicatorShape {
    CIRCLE,
    BOX,
    LINE
}

data class VisualIndicator(
    val x: Float,
    val y: Float,
    val radius: Float = 48f,
    val label: String? = null,
    @ColorInt val color: Int = Color.argb(220, 0, 180, 255),
    val shape: VisualIndicatorShape = VisualIndicatorShape.CIRCLE,
    val width: Float = radius * 2f,
    val height: Float = radius * 2f,
    val lineEndX: Float? = null,
    val lineEndY: Float? = null
)

/**
 * Canal simples para atualizar os indicadores desenhados pela overlay.
 *
 * O processamento interno chama setIndicators() depois de detectar os elementos
 * redondos e caixas do ERP mobile.
 */
object OverlayIndicatorBus {

    @Volatile
    private var currentIndicators: List<VisualIndicator> = emptyList()

    private val listeners = CopyOnWriteArrayList<(List<VisualIndicator>) -> Unit>()

    fun setIndicators(indicators: List<VisualIndicator>) {
        currentIndicators = indicators
        listeners.forEach { listener -> listener(indicators) }
    }

    fun clear() {
        setIndicators(emptyList())
    }

    fun current(): List<VisualIndicator> = currentIndicators

    fun addListener(listener: (List<VisualIndicator>) -> Unit): () -> Unit {
        listeners.add(listener)
        listener(currentIndicators)
        return { listeners.remove(listener) }
    }
}
