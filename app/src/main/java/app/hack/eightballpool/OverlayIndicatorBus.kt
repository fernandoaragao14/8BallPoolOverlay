package app.hack.eightballpool

import android.graphics.Color
import androidx.annotation.ColorInt
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Primitivas de desenho que a overlay entende. O detector traduz a jogada
 * analisada (mesa, bolas, taco, trajetórias) para uma lista destas primitivas.
 */
enum class VisualIndicatorShape {
    /** Círculo (bola detectada, bola principal, marcador de impacto). */
    CIRCLE,

    /** Retângulo da área jogável da mesa (limites/tabelas). */
    RECT,

    /** Segmento de reta (trajetória da branca, caminho da bola alvo, rebatida). */
    LINE,

    /** Ponto pequeno preenchido com rótulo (impacto, ghost ball). */
    MARKER,

    /** Painel de texto (HUD de diagnóstico) desenhado direto em [x],[y], multilinha com `\n`. */
    TEXT
}

/**
 * Descrição declarativa de um elemento visual sobre a mesa.
 *
 * Coordenadas em pixels da tela capturada (mesma escala da overlay em tela cheia).
 * - CIRCLE: usa [x],[y],[radius].
 * - RECT: usa [x],[y] (canto superior esquerdo) e [endX],[endY] (canto inferior direito).
 * - LINE: usa [x],[y] -> [endX],[endY]; [dashed] e [arrow] controlam o estilo.
 * - MARKER: usa [x],[y] com [radius] pequeno.
 */
data class VisualIndicator(
    val shape: VisualIndicatorShape,
    val x: Float = 0f,
    val y: Float = 0f,
    val radius: Float = 0f,
    val endX: Float = 0f,
    val endY: Float = 0f,
    @ColorInt val color: Int = Color.argb(220, 0, 180, 255),
    val strokeWidth: Float = 6f,
    val dashed: Boolean = false,
    val arrow: Boolean = false,
    val fill: Boolean = false,
    val crosshair: Boolean = false,
    val label: String? = null
)

/**
 * Canal único entre o detector (thread de captura) e a overlay (main thread).
 *
 * O [ScreenFrameProcessor] chama [setIndicators] após analisar cada frame; a
 * [IndicatorOverlayService] escuta e redesenha.
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
