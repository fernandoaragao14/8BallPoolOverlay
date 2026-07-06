package app.hack.eightballpool

import android.graphics.Bitmap
import android.os.SystemClock

/**
 * Frame pronto para processamento interno.
 *
 * Observação: se algum processamento assíncrono precisar guardar o Bitmap,
 * faça uma cópia com bitmap.copy(Bitmap.Config.ARGB_8888, false), porque o
 * processador recicla o frame anterior para evitar crescimento de memória.
 */
data class CapturedScreenFrame(
    val bitmap: Bitmap,
    val timestampMs: Long,
    val width: Int,
    val height: Int
)

/**
 * Ponto central para receber os frames capturados pelo MediaProjection.
 *
 * A detecção dos elementos redondos do ERP pode ser plugada em [listener].
 * Depois da detecção, atualize a overlay chamando:
 *
 * OverlayIndicatorBus.setIndicators(indicadores)
 */
object ScreenFrameProcessor {

    @Volatile
    private var latestFrame: CapturedScreenFrame? = null

    @Volatile
    var listener: ((CapturedScreenFrame) -> Unit)? = null

    fun submit(bitmap: Bitmap) {
        val frame = CapturedScreenFrame(
            bitmap = bitmap,
            timestampMs = SystemClock.elapsedRealtime(),
            width = bitmap.width,
            height = bitmap.height
        )

        val previousFrame = synchronized(this) {
            val previous = latestFrame
            latestFrame = frame
            previous
        }

        listener?.invoke(frame)
        previousFrame?.bitmap?.recycle()
    }

    fun latestBitmapCopy(): Bitmap? = synchronized(this) {
        latestFrame?.bitmap?.copy(Bitmap.Config.ARGB_8888, false)
    }

    fun clear() {
        val previousFrame = synchronized(this) {
            val previous = latestFrame
            latestFrame = null
            previous
        }
        previousFrame?.bitmap?.recycle()
    }
}
