package app.hack.eightballpool

import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.util.Log
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Analisador visual de jogada de sinuca.
 *
 * Pipeline por frame:
 * 1. reduz o Bitmap para uma matriz leve (RGB + HSV);
 * 2. segmenta o pano (HSV) e acha a maior região => área da mesa;
 * 3. acha bolas como blobs "não-pano" dentro da mesa (tamanho/circularidade);
 * 4. escolhe a bola branca/principal pela luminância/saturação;
 * 5. detecta o taco como blob longo e fino (PCA) perto da bola principal;
 * 6. projeta a trajetória: reta da branca -> 1ª colisão (bola ou tabela);
 * 7. desenha caminho da bola alvo e/ou rebatida na tabela;
 * 8. suaviza tudo entre frames e só desenha com confiança suficiente.
 *
 * Implementação pura em Kotlin (sem OpenCV) para manter o build simples e
 * compatível com Android 15/16. Ver [DetectorConfig] para calibração.
 */
object ScreenFrameProcessor {

    private const val TAG = "ScreenFrameProcessor"

    // Paleta da overlay (ARGB).
    private val tableColor = Color.argb(200, 0, 230, 190)
    private val ballColor = Color.argb(220, 120, 200, 255)
    private val mainBallColor = Color.argb(240, 255, 214, 40)
    private val aimColor = Color.argb(240, 90, 255, 130)
    private val impactColor = Color.argb(240, 255, 70, 95)
    private val targetPathColor = Color.argb(235, 255, 150, 30)
    private val railBounceColor = Color.argb(225, 190, 120, 255)
    // Ciano (não-branco) para não realimentar o detector da linha de mira branca.
    private val cueDeflectColor = Color.argb(160, 120, 235, 235)
    private val debugColor = Color.argb(200, 255, 0, 255)

    // Modo oportunidades.
    private val pocketColor = Color.argb(230, 255, 235, 120)
    private val bestShotColor = Color.argb(245, 60, 255, 120)
    private val bestTargetColor = Color.argb(245, 255, 205, 40)
    private val altShotColor = Color.argb(150, 120, 230, 255)

    @Volatile
    private var latestBitmap: Bitmap? = null

    /** Se falso, a overlay não é atualizada (útil para pausar). */
    @Volatile
    var autoDetectEnabled: Boolean = true

    // ---- Estado do tracker temporal (acessado só na thread de captura) --------
    private val trackedBalls = ArrayList<TrackedBall>()
    private var nextBallId = 1
    private var frameCounter = 0L
    private var pixelBuffer = IntArray(0)
    private var autoClothHue = 200f
    private var autoClothGray = false
    private var smoothedAimAngle: Float? = null
    private var smoothedAimConfidence: Float = 0f
    private var smoothedAimOriginX: Float = 0f
    private var smoothedAimOriginY: Float = 0f

    // Estatísticas do último cálculo vetorial (para o HUD).
    private var statMode: String = "-"
    private var statCutDeg: Float = -1f
    private var statCueBounces: Int = 0
    private var statTargetBounces: Int = 0

    fun submit(bitmap: Bitmap) {
        val previous = synchronized(this) {
            val old = latestBitmap
            latestBitmap = bitmap
            old
        }

        if (autoDetectEnabled) {
            runCatching { detectAndPublish(bitmap) }
                .onFailure { error ->
                    Log.e(TAG, "Failed to process screen frame", error)
                    OverlayIndicatorBus.clear()
                }
        }

        previous?.recycle()
    }

    fun clear() {
        val previous = synchronized(this) {
            val old = latestBitmap
            latestBitmap = null
            old
        }
        previous?.recycle()
        resetTracking()
        OverlayIndicatorBus.clear()
    }

    private fun resetTracking() {
        trackedBalls.clear()
        smoothedAimAngle = null
        smoothedAimConfidence = 0f
    }

    // =====================================================================
    // Pipeline principal
    // =====================================================================

    private fun detectAndPublish(bitmap: Bitmap) {
        frameCounter++
        statMode = "-"; statCutDeg = -1f; statCueBounces = 0; statTargetBounces = 0
        val matrix = buildAnalysisMatrix(bitmap)
        val table = segmentTable(matrix)
        val clothPct = clothFraction(matrix)

        val indicators = ArrayList<VisualIndicator>()
        var ballsCount = 0
        var cueFound = false
        var aimFound = false

        if (table != null) {
            val notCloth = BooleanArray(matrix.size) { !matrix.cloth[it] }
            val blobs = labelBlobs(notCloth, matrix)

            val rawBalls = extractBalls(blobs, matrix, table)
            val emittedBalls = updateBallTracker(rawBalls)

            val cue = detectCue(blobs, matrix, table, emittedBalls)
            val cueBall = emittedBalls.maxByOrNull { it.whiteness }
                ?.takeIf { it.whiteness >= DetectorConfig.cueBallMinValue * (1f - DetectorConfig.cueBallMaxSat) }

            // Bola principal + direção de mira (jogada que você está mirando).
            val play = resolveAim(emittedBalls, cue, matrix)

            indicators += tableIndicator(table)

            // Modo automático: destaca sozinho as melhores jogadas (bola -> caçapa).
            if (DetectorConfig.opportunityMode && cueBall != null) {
                indicators += pocketIndicators(table)
                indicators += opportunityIndicators(computeOpportunities(emittedBalls, cueBall, table))
            }

            indicators += ballIndicators(emittedBalls, play?.mainBall ?: cueBall)

            if (play != null && smoothedAimConfidence >= DetectorConfig.minAimConfidence) {
                indicators += trajectoryIndicators(play, emittedBalls, table)
                aimFound = true
            }

            if (DetectorConfig.debugOverlay) {
                indicators += debugIndicators(matrix, table, cue)
            }

            ballsCount = emittedBalls.size
            cueFound = cueBall != null
        } else {
            decayTracking()
        }

        // HUD de diagnóstico: sempre no topo quando o debug está ligado. Se este
        // painel aparece, então captura + overlay estão funcionando.
        if (DetectorConfig.debugOverlay) {
            indicators.add(0, hudIndicator(matrix, table != null, clothPct, ballsCount, cueFound, aimFound))
        }

        OverlayIndicatorBus.setIndicators(indicators)
    }

    private fun hudIndicator(
        matrix: AnalysisMatrix,
        tableFound: Boolean,
        clothPct: Float,
        balls: Int,
        cue: Boolean,
        aim: Boolean
    ): VisualIndicator {
        val res = "${(matrix.width * matrix.scaleX).toInt()}x${(matrix.height * matrix.scaleY).toInt()}"
        val text = buildString {
            append("DEBUG 8BP  frame ${frameCounter % 100000}\n")
            append("captura: OK ($res)  giro: ${DetectorConfig.captureRotationDeg}°\n")
            val panoCor = if (DetectorConfig.clothProfile == DetectorConfig.ClothProfile.AUTO) {
                if (autoClothGray) "AUTO cinza" else "AUTO h=${autoClothHue.toInt()}°"
            } else DetectorConfig.clothProfile.toString()
            append("perfil: $panoCor  pano: ${(clothPct * 100).toInt()}%\n")
            append("mesa: ${if (tableFound) "SIM" else "NAO (ajustar HSV azul)"}\n")
            append("bolas: $balls   branca: ${if (cue) "SIM" else "NAO"}\n")
            append("mira: ${if (aim) "SIM (${(smoothedAimConfidence * 100).toInt()}%)" else "NAO"}\n")
            // Turno inferido pela presença da linha-guia (só aparece na sua vez).
            append("turno: ${if (aim) "SUA VEZ" else "aguardando (sem guia)"}\n")
            val cut = if (statCutDeg >= 0f) "${statCutDeg.roundToInt()}°" else "-"
            append("fisica: alvo=${statMode} corte=$cut ric(alvo=$statTargetBounces branca=$statCueBounces)\n")
            append("auto: ${if (DetectorConfig.opportunityMode) "ON" else "OFF"}")
        }
        return VisualIndicator(
            shape = VisualIndicatorShape.TEXT,
            x = 24f, y = 40f, color = Color.argb(255, 90, 255, 140), label = text
        )
    }

    /** Fração de pixels marcados como pano na máscara já calculada (para o HUD/calibração). */
    private fun clothFraction(matrix: AnalysisMatrix): Float {
        if (matrix.cloth.isEmpty()) return 0f
        var c = 0
        for (v in matrix.cloth) if (v) c++
        return c.toFloat() / matrix.size
    }

    // =====================================================================
    // 1. Matriz de análise (RGB + HSV + luminância)
    // =====================================================================

    private fun buildAnalysisMatrix(bitmap: Bitmap): AnalysisMatrix {
        val bw = bitmap.width
        val bh = bitmap.height

        // Carrega o frame inteiro num buffer reaproveitado (sem alocar por frame).
        val needed = bw * bh
        if (pixelBuffer.size < needed) pixelBuffer = IntArray(needed)
        bitmap.getPixels(pixelBuffer, 0, bw, 0, 0, bw, bh)

        // Dimensões orientadas: 90/270 trocam largura por altura (paisagem).
        val rot = ((DetectorConfig.captureRotationDeg % 360) + 360) % 360
        val ow = if (rot == 90 || rot == 270) bh else bw
        val oh = if (rot == 90 || rot == 270) bw else bh

        val step = max(1, ceil(max(ow, oh).toDouble() / DetectorConfig.maxAnalysisSide).toInt())
        val w = max(1, ow / step)
        val h = max(1, oh / step)
        val scaleX = ow.toFloat() / w
        val scaleY = oh.toFloat() / h

        val hue = FloatArray(w * h)
        val sat = FloatArray(w * h)
        val value = FloatArray(w * h)

        for (y in 0 until h) {
            val oy = min(oh - 1, y * step)
            val base = y * w
            for (x in 0 until w) {
                val ox = min(ow - 1, x * step)
                // Mapeia coordenada orientada (ox,oy) de volta para o pixel do buffer.
                val sx: Int
                val sy: Int
                when (rot) {
                    90 -> { sx = oy; sy = bh - 1 - ox }
                    180 -> { sx = bw - 1 - ox; sy = bh - 1 - oy }
                    270 -> { sx = bw - 1 - oy; sy = ox }
                    else -> { sx = ox; sy = oy }
                }
                val p = pixelBuffer[sy * bw + sx]
                rgbToHsv(Color.red(p), Color.green(p), Color.blue(p), base + x, hue, sat, value)
            }
        }

        return AnalysisMatrix(w, h, scaleX, scaleY, hue, sat, value)
    }

    private fun rgbToHsv(r: Int, g: Int, b: Int, index: Int, hue: FloatArray, sat: FloatArray, value: FloatArray) {
        val rf = r / 255f
        val gf = g / 255f
        val bf = b / 255f
        val cMax = max(rf, max(gf, bf))
        val cMin = min(rf, min(gf, bf))
        val delta = cMax - cMin

        val hDeg = when {
            delta < 1e-5f -> 0f
            cMax == rf -> 60f * (((gf - bf) / delta) % 6f)
            cMax == gf -> 60f * (((bf - rf) / delta) + 2f)
            else -> 60f * (((rf - gf) / delta) + 4f)
        }
        hue[index] = if (hDeg < 0f) hDeg + 360f else hDeg
        sat[index] = if (cMax <= 1e-5f) 0f else delta / cMax
        value[index] = cMax
    }

    // =====================================================================
    // 2. Segmentação da mesa (pano)
    // =====================================================================

    private fun segmentTable(matrix: AnalysisMatrix): TableRegion? {
        val useAuto = DetectorConfig.clothProfile == DetectorConfig.ClothProfile.AUTO
        if (useAuto) prepareAutoCloth(matrix)
        val profile = if (useAuto) DetectorConfig.ClothProfile.AUTO else DetectorConfig.clothProfile

        val cloth = BooleanArray(matrix.size)
        var clothCount = 0
        for (i in 0 until matrix.size) {
            val ok = if (useAuto) {
                clothMatchAuto(matrix.hue[i], matrix.sat[i], matrix.value[i])
            } else {
                matchesProfile(profile, matrix.hue[i], matrix.sat[i], matrix.value[i])
            }
            if (ok) { cloth[i] = true; clothCount++ }
        }
        matrix.cloth = cloth

        if (clothCount < matrix.size * DetectorConfig.minTableAreaFraction * 0.5f) return null

        // Maior componente de pano = mesa.
        val largest = largestComponent(cloth, matrix) ?: return null
        val areaFraction = largest.count.toFloat() / matrix.size
        if (areaFraction < DetectorConfig.minTableAreaFraction) return null

        val left = largest.minX * matrix.scaleX
        val top = largest.minY * matrix.scaleY
        val right = (largest.maxX + 1) * matrix.scaleX
        val bottom = (largest.maxY + 1) * matrix.scaleY
        val inset = (right - left) * DetectorConfig.railInsetFraction

        return TableRegion(
            sampledMinX = largest.minX,
            sampledMinY = largest.minY,
            sampledMaxX = largest.maxX,
            sampledMaxY = largest.maxY,
            left = left,
            top = top,
            right = right,
            bottom = bottom,
            playLeft = left + inset,
            playTop = top + inset,
            playRight = right - inset,
            playBottom = bottom - inset,
            profile = profile
        )
    }

    /**
     * Detecta a cor do pano pela COR DOMINANTE do frame (histograma de matiz).
     * Assim funciona com qualquer feltro (azul, verde, marrom, vinho, cinza) sem
     * depender de faixas fixas. Guarda o resultado em [autoClothHue]/[autoClothGray].
     */
    private fun prepareAutoCloth(matrix: AnalysisMatrix) {
        val bins = IntArray(36)
        var grayCount = 0
        for (i in 0 until matrix.size) {
            val s = matrix.sat[i]
            val v = matrix.value[i]
            // Ignora pixels escuros (UI/fundo navy) e estourados: o feltro é de brilho médio.
            if (v < 0.30f || v > 0.95f) continue
            if (s >= 0.20f) {
                val b = ((matrix.hue[i] / 10f).toInt()).coerceIn(0, 35)
                bins[b]++
            } else if (v in 0.35f..0.72f) {
                grayCount++
            }
        }
        var maxBin = 0
        var maxCount = 0
        for (b in 0..35) if (bins[b] > maxCount) { maxCount = bins[b]; maxBin = b }

        // Feltro colorido dominante vs feltro cinza (ex.: mesa cinza/preta).
        if (maxCount >= grayCount && maxCount > matrix.size * 0.04f) {
            autoClothGray = false
            autoClothHue = maxBin * 10f + 5f
        } else {
            autoClothGray = true
        }
    }

    private fun clothMatchAuto(h: Float, s: Float, v: Float): Boolean {
        return if (autoClothGray) {
            s <= 0.22f && v in 0.35f..0.74f
        } else {
            var d = abs(h - autoClothHue)
            if (d > 180f) d = 360f - d
            d <= 24f && s >= 0.16f && v in 0.26f..0.97f
        }
    }

    private fun matchesProfile(profile: DetectorConfig.ClothProfile, h: Float, s: Float, v: Float): Boolean {
        return when (profile) {
            DetectorConfig.ClothProfile.GREEN ->
                h in DetectorConfig.greenHueRange && s >= DetectorConfig.greenMinSat &&
                    v in DetectorConfig.greenValRange
            DetectorConfig.ClothProfile.BLUE ->
                h in DetectorConfig.blueHueRange && s >= DetectorConfig.blueMinSat &&
                    v in DetectorConfig.blueValRange
            DetectorConfig.ClothProfile.GRAY ->
                s <= DetectorConfig.grayMaxSat && v in DetectorConfig.grayValRange
            DetectorConfig.ClothProfile.AUTO -> false
        }
    }

    // =====================================================================
    // 3. Blobs (componentes conectados) + momentos para PCA
    // =====================================================================

    private fun labelBlobs(mask: BooleanArray, matrix: AnalysisMatrix): List<Blob> {
        val w = matrix.width
        val h = matrix.height
        val visited = BooleanArray(mask.size)
        val stack = IntArray(mask.size)
        val blobs = ArrayList<Blob>()

        for (start in mask.indices) {
            if (visited[start] || !mask[start]) continue

            var sp = 0
            stack[sp++] = start
            visited[start] = true

            var count = 0L
            var sumX = 0.0
            var sumY = 0.0
            var sumXX = 0.0
            var sumYY = 0.0
            var sumXY = 0.0
            var sumSat = 0.0
            var sumVal = 0.0
            var minX = Int.MAX_VALUE
            var minY = Int.MAX_VALUE
            var maxX = Int.MIN_VALUE
            var maxY = Int.MIN_VALUE

            while (sp > 0) {
                val idx = stack[--sp]
                val x = idx % w
                val y = idx / w

                count++
                sumX += x
                sumY += y
                sumXX += (x * x).toDouble()
                sumYY += (y * y).toDouble()
                sumXY += (x * y).toDouble()
                sumSat += matrix.sat[idx]
                sumVal += matrix.value[idx]
                if (x < minX) minX = x
                if (y < minY) minY = y
                if (x > maxX) maxX = x
                if (y > maxY) maxY = y

                if (x > 0) { val n = idx - 1; if (!visited[n] && mask[n]) { visited[n] = true; stack[sp++] = n } }
                if (x < w - 1) { val n = idx + 1; if (!visited[n] && mask[n]) { visited[n] = true; stack[sp++] = n } }
                if (y > 0) { val n = idx - w; if (!visited[n] && mask[n]) { visited[n] = true; stack[sp++] = n } }
                if (y < h - 1) { val n = idx + w; if (!visited[n] && mask[n]) { visited[n] = true; stack[sp++] = n } }
            }

            blobs.add(
                Blob(
                    count = count,
                    centerX = (sumX / count).toFloat(),
                    centerY = (sumY / count).toFloat(),
                    covXX = (sumXX / count - (sumX / count) * (sumX / count)).toFloat(),
                    covYY = (sumYY / count - (sumY / count) * (sumY / count)).toFloat(),
                    covXY = (sumXY / count - (sumX / count) * (sumY / count)).toFloat(),
                    meanSat = (sumSat / count).toFloat(),
                    meanVal = (sumVal / count).toFloat(),
                    minX = minX,
                    minY = minY,
                    maxX = maxX,
                    maxY = maxY
                )
            )
        }

        return blobs
    }

    private fun largestComponent(mask: BooleanArray, matrix: AnalysisMatrix): Blob? {
        return labelBlobs(mask, matrix).maxByOrNull { it.count }
    }

    // =====================================================================
    // 4. Bolas
    // =====================================================================

    private fun extractBalls(blobs: List<Blob>, matrix: AnalysisMatrix, table: TableRegion): List<DetectedBall> {
        val avgScale = (matrix.scaleX + matrix.scaleY) / 2f
        val tableWidth = table.right - table.left
        val minR = DetectorConfig.minBallRadiusFraction * tableWidth
        val maxR = DetectorConfig.maxBallRadiusFraction * tableWidth

        val balls = ArrayList<DetectedBall>()
        for (blob in blobs) {
            // Deve estar estritamente dentro da mesa (exclui tabelas, caçapas e fundo).
            if (blob.minX <= table.sampledMinX + 1 || blob.maxX >= table.sampledMaxX - 1) continue
            if (blob.minY <= table.sampledMinY + 1 || blob.maxY >= table.sampledMaxY - 1) continue

            val bw = (blob.maxX - blob.minX + 1)
            val bh = (blob.maxY - blob.minY + 1)
            val aspect = max(bw, bh).toFloat() / max(1, min(bw, bh))
            if (aspect > DetectorConfig.ballMaxAspect) continue

            val circularity = blob.count.toFloat() / (bw * bh)
            if (circularity < DetectorConfig.ballMinCircularity) continue

            val radiusSampled = sqrt(blob.count / Math.PI).toFloat()
            val radius = radiusSampled * avgScale
            if (radius < minR || radius > maxR) continue

            balls.add(
                DetectedBall(
                    x = blob.centerX * matrix.scaleX,
                    y = blob.centerY * matrix.scaleY,
                    radius = radius,
                    whiteness = blob.meanVal * (1f - blob.meanSat)
                )
            )
        }

        return balls.sortedByDescending { it.radius }.take(DetectorConfig.maxBalls)
    }

    // =====================================================================
    // 5. Taco (blob longo e fino via PCA)
    // =====================================================================

    private fun detectCue(
        blobs: List<Blob>,
        matrix: AnalysisMatrix,
        table: TableRegion,
        balls: List<TrackedBall>
    ): CueLine? {
        val cueBall = balls.maxByOrNull { it.whiteness }
            ?.takeIf { it.whiteness >= DetectorConfig.cueBallMinValue * (1f - DetectorConfig.cueBallMaxSat) }
        // Referência de raio para a busca: bola branca, senão mediana das bolas.
        val refRadius = cueBall?.radius
            ?: balls.map { it.radius }.sortedOrNull()
            ?: return null
        val avgScale = (matrix.scaleX + matrix.scaleY) / 2f
        val searchRadius = DetectorConfig.cueSearchRadiusBallFactor * refRadius
        val maxOffset = DetectorConfig.cueMaxOffsetBallFactor * refRadius

        // Ponto de referência (em px originais) para achar o taco: bola branca ou centro das bolas.
        val refX = cueBall?.x ?: balls.map { it.x }.average().toFloat()
        val refY = cueBall?.y ?: balls.map { it.y }.average().toFloat()

        var best: CueLine? = null
        var bestScore = 0f

        for (blob in blobs) {
            if (blob.count < DetectorConfig.cueMinPixels) continue
            if (blob.count > matrix.size * 0.10f) continue // fundo/UI grande não é taco

            val eig = principalAxis(blob) ?: continue
            if (eig.elongation < DetectorConfig.cueMinElongation) continue

            val cx = blob.centerX * matrix.scaleX
            val cy = blob.centerY * matrix.scaleY
            // Vetor principal em px originais (renormalizado para lidar com scaleX!=scaleY).
            var vx = eig.dirX * matrix.scaleX
            var vy = eig.dirY * matrix.scaleY
            val vn = hypot(vx.toDouble(), vy.toDouble()).toFloat()
            if (vn < 1e-3f) continue
            vx /= vn
            vy /= vn

            // Distância perpendicular da referência ao eixo do taco.
            val rx = refX - cx
            val ry = refY - cy
            val proj = rx * vx + ry * vy
            val perp = abs(rx * (-vy) + ry * vx)
            if (perp > maxOffset) continue

            // A bola precisa estar perto de uma das pontas do taco.
            val halfLen = 2f * sqrt(max(0f, eig.lambda1)) * avgScale
            val endA = floatArrayOf(cx + vx * halfLen, cy + vy * halfLen)
            val endB = floatArrayOf(cx - vx * halfLen, cy - vy * halfLen)
            val nearEnd = min(dist(refX, refY, endA[0], endA[1]), dist(refX, refY, endB[0], endB[1]))
            if (nearEnd > searchRadius) continue

            // Orienta a mira: da ponta atrás da bola PARA a bola e além.
            val aimSign = if (proj >= 0f) 1f else -1f
            val aimX = vx * aimSign
            val aimY = vy * aimSign

            val elongScore = ((eig.elongation - DetectorConfig.cueMinElongation) / 6f).coerceIn(0f, 1f)
            val perpScore = (1f - perp / maxOffset).coerceIn(0f, 1f)
            val nearScore = (1f - nearEnd / searchRadius).coerceIn(0f, 1f)
            val score = 0.4f * elongScore + 0.35f * perpScore + 0.25f * nearScore

            if (score > bestScore) {
                bestScore = score
                best = CueLine(
                    axisX = cx,
                    axisY = cy,
                    aimX = aimX,
                    aimY = aimY,
                    confidence = score,
                    cueBall = cueBall
                )
            }
        }

        return best
    }

    private fun principalAxis(blob: Blob): EigenAxis? {
        val a = blob.covXX
        val b = blob.covXY
        val d = blob.covYY
        val trace = a + d
        val det = a * d - b * b
        val disc = trace * trace / 4f - det
        if (disc < 0f) return null
        val root = sqrt(disc)
        val l1 = trace / 2f + root
        val l2 = trace / 2f - root
        if (l1 <= 1e-4f || l2 <= 1e-5f) return null

        // Autovetor de l1.
        val ex: Float
        val ey: Float
        if (abs(b) > 1e-5f) {
            ex = l1 - d
            ey = b
        } else {
            if (a >= d) { ex = 1f; ey = 0f } else { ex = 0f; ey = 1f }
        }
        val n = hypot(ex.toDouble(), ey.toDouble()).toFloat()
        return EigenAxis(ex / n, ey / n, l1, l2, sqrt(l1 / l2))
    }

    // =====================================================================
    // 6-7. Mira, trajetória e geometria
    // =====================================================================

    private fun resolveAim(balls: List<TrackedBall>, cue: CueLine?, matrix: AnalysisMatrix): PlayResolution? {
        if (balls.isEmpty()) {
            decayTracking()
            return null
        }

        val white = balls.maxByOrNull { it.whiteness }
            ?.takeIf { it.whiteness >= DetectorConfig.cueBallMinValue * (1f - DetectorConfig.cueBallMaxSat) }

        // Caso 0 (jogo 8 Ball Pool): ler a linha de mira branca que o jogo desenha a partir da branca.
        if (DetectorConfig.readGameAimLine && white != null) {
            val aim = detectGameAimLine(white, balls, matrix)
            if (aim != null) {
                updateAim(white.x, white.y, aim.dirX, aim.dirY, aim.confidence)
                return currentPlay(white)
            }
        }

        // Caso 1: taco detectado -> bola principal é a melhor alinhada à frente do taco.
        if (cue != null) {
            val main = cue.cueBall ?: bestAlignedBall(balls, cue.axisX, cue.axisY, cue.aimX, cue.aimY)
            if (main != null) {
                updateAim(main.x, main.y, cue.aimX, cue.aimY, cue.confidence)
                return currentPlay(main)
            }
        }

        // Caso 2: sem taco, mas há bola branca -> fallback por alinhamento (confiança baixa).
        if (white != null) {
            val nearest = balls.filter { it !== white }.minByOrNull { dist(white.x, white.y, it.x, it.y) }
            if (nearest != null) {
                val dx = nearest.x - white.x
                val dy = nearest.y - white.y
                val n = hypot(dx.toDouble(), dy.toDouble()).toFloat()
                if (n > 1f) {
                    updateAim(white.x, white.y, dx / n, dy / n, 0.22f)
                    return currentPlay(white)
                }
            }
            updateAim(white.x, white.y, 0f, 0f, 0f)
            return currentPlay(white)
        }

        decayTracking()
        return null
    }

    /**
     * Lê a linha de mira branca que o jogo 8 Ball Pool desenha, por SCAN RADIAL:
     * dispara raios da bola branca em todas as direções e mede o comprimento da
     * sequência branca em cada uma; a mais longa é a linha-guia do jogo.
     *
     * Robusto e em tempo real: acompanha a mira enquanto você aponta, e a direção
     * é exatamente a que o jogo mostra (sem recalcular física).
     */
    private fun detectGameAimLine(white: TrackedBall, balls: List<TrackedBall>, matrix: AnalysisMatrix): GameAim? {
        val w = matrix.width
        val h = matrix.height
        val avgScale = (matrix.scaleX + matrix.scaleY) / 2f
        val cxS = white.x / matrix.scaleX
        val cyS = white.y / matrix.scaleY
        val rS = max(1.5f, white.radius / avgScale)

        val startR = 1.3f * rS
        val maxR = hypot(w.toDouble(), h.toDouble()).toFloat()
        val minRun = DetectorConfig.guidelineMinRunBallFactor * rS
        val maxGap = DetectorConfig.guidelineMaxGap

        var bestRun = 0f
        var bestCos = 0f
        var bestSin = 0f

        val stepDeg = DetectorConfig.guidelineAngleStepDeg.coerceAtLeast(0.5f)
        var angle = 0f
        while (angle < 360f) {
            val rad = Math.toRadians(angle.toDouble())
            val dx = cos(rad).toFloat()
            val dy = sin(rad).toFloat()

            var gap = 0
            var lastWhite = 0f
            var r = startR
            while (r < maxR) {
                val xi = (cxS + dx * r).toInt()
                val yi = (cyS + dy * r).toInt()
                if (xi < 0 || xi >= w || yi < 0 || yi >= h) break
                if (isWhiteNear(matrix, xi, yi)) {
                    lastWhite = r
                    gap = 0
                } else {
                    gap++
                    if (gap > maxGap) break
                }
                r += 1f
            }
            val run = lastWhite - startR
            if (run > bestRun) {
                bestRun = run
                bestCos = dx
                bestSin = dy
            }
            angle += stepDeg
        }

        if (bestRun < minRun) return null

        var aimX = bestCos * matrix.scaleX
        var aimY = bestSin * matrix.scaleY
        val an = hypot(aimX.toDouble(), aimY.toDouble()).toFloat()
        if (an < 1e-4f) return null
        aimX /= an; aimY /= an

        val confidence = (0.5f + 0.45f * (bestRun / (12f * rS))).coerceIn(0.5f, 0.95f)
        return GameAim(aimX, aimY, confidence)
    }

    /** Branco = valor alto e saturação baixa; checa vizinhança 3x3 para pegar linhas finas. */
    private fun isWhiteNear(matrix: AnalysisMatrix, x: Int, y: Int): Boolean {
        val w = matrix.width
        val h = matrix.height
        val minVal = DetectorConfig.aimLineMinValue
        val maxSat = DetectorConfig.aimLineMaxSat
        var dy = -1
        while (dy <= 1) {
            val yy = y + dy
            if (yy in 0 until h) {
                var dx = -1
                while (dx <= 1) {
                    val xx = x + dx
                    if (xx in 0 until w) {
                        val idx = yy * w + xx
                        if (matrix.value[idx] >= minVal && matrix.sat[idx] <= maxSat) return true
                    }
                    dx++
                }
            }
            dy++
        }
        return false
    }

    private fun bestAlignedBall(
        balls: List<TrackedBall>,
        axisX: Float,
        axisY: Float,
        aimX: Float,
        aimY: Float
    ): TrackedBall? {
        return balls.filter { ball ->
            (ball.x - axisX) * aimX + (ball.y - axisY) * aimY > 0f // à frente do taco
        }.minByOrNull { ball ->
            abs((ball.x - axisX) * (-aimY) + (ball.y - axisY) * aimX) // menor distância perpendicular
        }
    }

    /** Atualiza a mira suavizada (EMA no ângulo) e a confiança. */
    private fun updateAim(originX: Float, originY: Float, dirX: Float, dirY: Float, confidence: Float) {
        smoothedAimOriginX = originX
        smoothedAimOriginY = originY
        if (confidence <= 0f || (dirX == 0f && dirY == 0f)) {
            smoothedAimConfidence *= 0.6f
            return
        }
        val angle = atan2(dirY.toDouble(), dirX.toDouble()).toFloat()
        val current = smoothedAimAngle
        smoothedAimAngle = if (current == null) {
            angle
        } else {
            val d = angleDelta(angle, current)
            val snap = Math.toRadians(DetectorConfig.aimSnapDeg.toDouble()).toFloat()
            // Rotação grande do taco: acompanha na hora (reativo). Pequena: suaviza (estável).
            if (abs(d) > snap) angle else current + DetectorConfig.aimSmoothing * d
        }
        smoothedAimConfidence = smoothedAimConfidence + 0.5f * (confidence - smoothedAimConfidence)
        if (smoothedAimConfidence < confidence) smoothedAimConfidence = confidence
    }

    private fun decayTracking() {
        smoothedAimConfidence *= 0.6f
    }

    private fun currentPlay(main: TrackedBall): PlayResolution {
        val angle = smoothedAimAngle ?: 0f
        return PlayResolution(main, cos(angle.toDouble()).toFloat(), sin(angle.toDouble()).toFloat())
    }

    private fun trajectoryIndicators(
        play: PlayResolution,
        balls: List<TrackedBall>,
        table: TableRegion
    ): List<VisualIndicator> {
        val out = ArrayList<VisualIndicator>()
        val main = play.mainBall
        val ox = main.x
        val oy = main.y
        val dx = play.aimX
        val dy = play.aimY
        if (dx == 0f && dy == 0f) return out

        val diagonal = hypot((table.right - table.left).toDouble(), (table.bottom - table.top).toDouble()).toFloat()
        val energy = diagonal * DetectorConfig.pathEnergyDiagonals
        val others = balls.filter { it !== main }

        // Linha-guia de alinhamento total: eixo do taco estendido de ponta a ponta.
        if (DetectorConfig.drawAlignmentGuide) {
            val axisEnd = rayRect(ox, oy, dx, dy, table, 0f)
            val ax = if (axisEnd != null) ox + dx * axisEnd.t else ox + dx * diagonal
            val ay = if (axisEnd != null) oy + dy * axisEnd.t else oy + dy * diagonal
            out += lineIndicator(ox, oy, ax, ay, withAlpha(aimColor, 70), 3f, dashed = true)
        }

        // 1ª colisão: bola x tabela.
        var ballHitT = Float.MAX_VALUE
        var hitBall: TrackedBall? = null
        for (b in others) {
            val t = rayCircle(ox, oy, dx, dy, b.x, b.y, main.radius + b.radius)
            if (t != null && t < ballHitT) { ballHitT = t; hitBall = b }
        }
        val railHit = rayRect(ox, oy, dx, dy, table, main.radius)
        val ballFirst = hitBall != null && ballHitT <= (railHit?.t ?: Float.MAX_VALUE)

        if (ballFirst && hitBall != null) {
            val contactX = ox + dx * ballHitT
            val contactY = oy + dy * ballHitT
            out += lineIndicator(ox, oy, contactX, contactY, aimColor, 9f, label = "Mira")

            // Linha de centros (bola-alvo sai por aqui — colisão elástica de esferas rígidas).
            var ux = hitBall.x - contactX
            var uy = hitBall.y - contactY
            val un = hypot(ux.toDouble(), uy.toDouble()).toFloat()
            if (un > 1e-3f) { ux /= un; uy /= un }

            // Ângulo de corte entre a mira e a linha de centros.
            val cutCos = (dx * ux + dy * uy).coerceIn(-1f, 1f)
            val cutDeg = Math.toDegrees(acos(cutCos.toDouble())).toFloat()
            statCutDeg = cutDeg
            statMode = "bola"

            val touchX = contactX + ux * main.radius
            val touchY = contactY + uy * main.radius
            out += VisualIndicator(
                shape = VisualIndicatorShape.MARKER,
                x = touchX, y = touchY, radius = 12f, color = impactColor,
                label = "Impacto • corte ${cutDeg.roundToInt()}°"
            )
            out += VisualIndicator(
                shape = VisualIndicatorShape.CIRCLE,
                x = contactX, y = contactY, radius = main.radius,
                color = withAlpha(aimColor, 130), strokeWidth = 4f
            )

            // Reparte a energia pela física elástica: alvo ∝ cos(θ), branca ∝ sen(θ).
            val targetEnergy = energy * cutCos.coerceIn(0f, 1f)
            val cueEnergy = energy * sqrt((1f - cutCos * cutCos).coerceIn(0f, 1f))

            // Bola-alvo: linha de centros, com múltiplos ricochetes e perda de energia.
            statTargetBounces = tracePath(out, hitBall.x, hitBall.y, ux, uy, table, hitBall.radius, targetEnergy, targetPathColor, 8f)
            // Caçapa mais provável para a bola-alvo.
            highlightProbablePocket(out, hitBall.x, hitBall.y, ux, uy, table)?.let { out += it }

            // Branca: tangente 90° (deflexão elástica), também com ricochetes.
            if (DetectorConfig.drawCueDeflection && cueEnergy > main.radius) {
                var tvx = dx - cutCos * ux
                var tvy = dy - cutCos * uy
                val tvn = hypot(tvx.toDouble(), tvy.toDouble()).toFloat()
                if (tvn > 1e-3f) {
                    tvx /= tvn; tvy /= tvn
                    statCueBounces = tracePath(out, contactX, contactY, tvx, tvy, table, main.radius, cueEnergy, cueDeflectColor, 5f)
                }
            } else {
                statCueBounces = 0
            }
        } else if (railHit != null) {
            val ix = ox + dx * railHit.t
            val iy = oy + dy * railHit.t
            statMode = "tabela"
            statCutDeg = -1f
            statCueBounces = 0
            out += lineIndicator(ox, oy, ix, iy, aimColor, 9f, label = "Mira")
            out += VisualIndicator(
                shape = VisualIndicatorShape.MARKER,
                x = ix, y = iy, radius = 12f, color = impactColor, label = "Tabela"
            )
            val reflected = reflect(dx, dy, railHit.normalX, railHit.normalY)
            val remaining = (energy - railHit.t).coerceAtLeast(0f) * DetectorConfig.railRestitution
            statTargetBounces = 1 + tracePath(out, ix, iy, reflected.first, reflected.second, table, main.radius, remaining, railBounceColor, 7f)
        }

        return out
    }

    /**
     * Traça um caminho com múltiplos ricochetes nas tabelas, gastando um orçamento de
     * energia ([budget] em px). A cada batida a energia é multiplicada por [railRestitution]
     * (perda cinética nominal) e a linha fica mais fraca. Retorna o nº de ricochetes desenhados.
     */
    private fun tracePath(
        out: ArrayList<VisualIndicator>,
        startX: Float, startY: Float, dirXin: Float, dirYin: Float,
        table: TableRegion, radius: Float, budget: Float, color: Int, width: Float
    ): Int {
        var px = startX
        var py = startY
        var dirX = dirXin
        var dirY = dirYin
        var remaining = budget
        var bounce = 0
        val minLen = radius * 0.8f

        while (remaining > minLen && bounce <= DetectorConfig.maxRailReflections) {
            val hit = rayRect(px, py, dirX, dirY, table, radius)
            val alpha = (235f * pow(DetectorConfig.railRestitution, bounce)).toInt().coerceIn(60, 235)
            val segColor = withAlpha(color, alpha)

            if (hit == null || hit.t >= remaining) {
                // A bola perde a energia antes de alcançar a próxima tabela: para aqui.
                out += lineIndicator(px, py, px + dirX * remaining, py + dirY * remaining, segColor, width, arrow = true)
                return bounce
            }
            val nx = px + dirX * hit.t
            val ny = py + dirY * hit.t
            out += lineIndicator(px, py, nx, ny, segColor, width)

            val reflected = reflect(dirX, dirY, hit.normalX, hit.normalY)
            px = nx; py = ny
            dirX = reflected.first; dirY = reflected.second
            remaining = (remaining - hit.t) * DetectorConfig.railRestitution
            bounce++
        }
        return bounce
    }

    /** Destaca a caçapa cujo eixo mais se alinha com a direção da bola-alvo (à frente dela). */
    private fun highlightProbablePocket(
        out: ArrayList<VisualIndicator>,
        bx: Float, by: Float, ux: Float, uy: Float, table: TableRegion
    ): VisualIndicator? {
        var best: FloatArray? = null
        var bestPerp = Float.MAX_VALUE
        for (p in tablePockets(table)) {
            val rx = p[0] - bx
            val ry = p[1] - by
            val proj = rx * ux + ry * uy
            if (proj <= 0f) continue
            val perp = abs(rx * (-uy) + ry * ux)
            if (perp < bestPerp) { bestPerp = perp; best = p }
        }
        val pocket = best ?: return null
        // Só marca se estiver razoavelmente alinhada (dentro de ~1.5 diâmetro de bola).
        return VisualIndicator(
            shape = VisualIndicatorShape.CIRCLE,
            x = pocket[0], y = pocket[1], radius = (table.right - table.left) * DetectorConfig.pocketRadiusFraction,
            color = bestTargetColor, strokeWidth = 6f, label = "Caçapa"
        )
    }

    private fun pow(base: Float, exp: Int): Float {
        var r = 1f
        repeat(exp) { r *= base }
        return r
    }

    // ---- Interseções -----------------------------------------------------------

    /** Menor t>0 em que um círculo de raio [combinedRadius] centrado em (cx,cy) é atingido pelo raio (ox,oy)+t*(dx,dy). */
    private fun rayCircle(
        ox: Float, oy: Float, dx: Float, dy: Float,
        cx: Float, cy: Float, combinedRadius: Float
    ): Float? {
        val relX = cx - ox
        val relY = cy - oy
        val proj = relX * dx + relY * dy
        if (proj <= 0f) return null
        val perp2 = (relX * relX + relY * relY) - proj * proj
        val r2 = combinedRadius * combinedRadius
        if (perp2 > r2) return null
        val t = proj - sqrt(r2 - perp2)
        return if (t > 0f) t else null
    }

    /** Interseção do centro da bola (offset [radius] das bordas) com o retângulo jogável. */
    private fun rayRect(
        ox: Float, oy: Float, dx: Float, dy: Float,
        table: TableRegion, radius: Float
    ): RailHit? {
        val left = table.playLeft + radius
        val right = table.playRight - radius
        val top = table.playTop + radius
        val bottom = table.playBottom - radius

        var bestT = Float.MAX_VALUE
        var nx = 0f
        var ny = 0f

        if (dx < 0f) { // parede esquerda
            val t = (left - ox) / dx
            if (t > 1e-3f) {
                val y = oy + dy * t
                if (y in top..bottom && t < bestT) { bestT = t; nx = 1f; ny = 0f }
            }
        } else if (dx > 0f) { // parede direita
            val t = (right - ox) / dx
            if (t > 1e-3f) {
                val y = oy + dy * t
                if (y in top..bottom && t < bestT) { bestT = t; nx = -1f; ny = 0f }
            }
        }
        if (dy < 0f) { // parede topo
            val t = (top - oy) / dy
            if (t > 1e-3f) {
                val x = ox + dx * t
                if (x in left..right && t < bestT) { bestT = t; nx = 0f; ny = 1f }
            }
        } else if (dy > 0f) { // parede base
            val t = (bottom - oy) / dy
            if (t > 1e-3f) {
                val x = ox + dx * t
                if (x in left..right && t < bestT) { bestT = t; nx = 0f; ny = -1f }
            }
        }

        return if (bestT != Float.MAX_VALUE) RailHit(bestT, nx, ny) else null
    }

    private fun reflect(dx: Float, dy: Float, nx: Float, ny: Float): Pair<Float, Float> {
        val dot = dx * nx + dy * ny
        return Pair(dx - 2f * dot * nx, dy - 2f * dot * ny)
    }

    // =====================================================================
    // Modo automático de jogadas (oportunidades)
    // =====================================================================

    /** 6 caçapas a partir do retângulo do pano (4 cantos + 2 meio das tabelas longas). */
    private fun tablePockets(table: TableRegion): Array<FloatArray> {
        val cx = (table.left + table.right) / 2f
        return arrayOf(
            floatArrayOf(table.left, table.top),
            floatArrayOf(table.right, table.top),
            floatArrayOf(table.left, table.bottom),
            floatArrayOf(table.right, table.bottom),
            floatArrayOf(cx, table.top),
            floatArrayOf(cx, table.bottom)
        )
    }

    private fun computeOpportunities(
        balls: List<TrackedBall>,
        cueBall: TrackedBall,
        table: TableRegion
    ): List<Shot> {
        val pockets = tablePockets(table)
        val targets = balls.filter { it !== cueBall }
        if (targets.isEmpty()) return emptyList()

        val cueR = cueBall.radius
        val tableWidth = table.right - table.left
        val minCutCos = cos(Math.toRadians(DetectorConfig.maxCutAngleDeg.toDouble())).toFloat()

        val shots = ArrayList<Shot>()
        for (ball in targets) {
            var bestForBall: Shot? = null
            for (pocket in pockets) {
                var bpx = pocket[0] - ball.x
                var bpy = pocket[1] - ball.y
                val bpn = hypot(bpx.toDouble(), bpy.toDouble()).toFloat()
                if (bpn < 1f) continue
                bpx /= bpn; bpy /= bpn

                // Ponto fantasma: centro da branca no contato para mandar a bola à caçapa.
                val ghostX = ball.x - bpx * (cueR + ball.radius)
                val ghostY = ball.y - bpy * (cueR + ball.radius)
                if (ghostX < table.playLeft + cueR || ghostX > table.playRight - cueR) continue
                if (ghostY < table.playTop + cueR || ghostY > table.playBottom - cueR) continue

                var cgx = ghostX - cueBall.x
                var cgy = ghostY - cueBall.y
                val cgn = hypot(cgx.toDouble(), cgy.toDouble()).toFloat()
                if (cgn < 1f) continue
                cgx /= cgn; cgy /= cgn

                val cutCos = cgx * bpx + cgy * bpy
                if (cutCos < minCutCos) continue

                // Caminhos livres: branca -> fantasma e bola -> caçapa.
                if (segmentBlocked(cueBall.x, cueBall.y, ghostX, ghostY, cueR, balls, cueBall, ball)) continue
                if (segmentBlocked(ball.x, ball.y, pocket[0], pocket[1], ball.radius, balls, ball, null)) continue

                val distScore = 1f - (cgn + bpn) / (2f * tableWidth).coerceAtLeast(1f)
                val score = cutCos * 0.7f + distScore.coerceIn(0f, 1f) * 0.3f

                if (bestForBall == null || score > bestForBall.score) {
                    bestForBall = Shot(ball, pocket[0], pocket[1], ghostX, ghostY, cueBall.x, cueBall.y, cutCos, score)
                }
            }
            if (bestForBall != null) shots.add(bestForBall)
        }

        return shots.sortedByDescending { it.score }.take(DetectorConfig.maxOpportunities)
    }

    /** True se algum obstáculo cruza o segmento a->b (raio do móvel + raio do obstáculo + folga). */
    private fun segmentBlocked(
        ax: Float, ay: Float, bx: Float, by: Float, radius: Float,
        balls: List<TrackedBall>, exclude1: TrackedBall?, exclude2: TrackedBall?
    ): Boolean {
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        if (len2 < 1f) return false
        for (c in balls) {
            if (c === exclude1 || c === exclude2) continue
            var t = ((c.x - ax) * dx + (c.y - ay) * dy) / len2
            t = t.coerceIn(0f, 1f)
            val px = ax + dx * t
            val py = ay + dy * t
            val dd = dist(c.x, c.y, px, py)
            if (dd < radius + c.radius * (1f + DetectorConfig.pathClearanceBallFactor)) return true
        }
        return false
    }

    private fun pocketIndicators(table: TableRegion): List<VisualIndicator> {
        val r = (table.right - table.left) * DetectorConfig.pocketRadiusFraction
        return tablePockets(table).map { p ->
            VisualIndicator(
                shape = VisualIndicatorShape.CIRCLE,
                x = p[0], y = p[1], radius = r, color = pocketColor, strokeWidth = 4f
            )
        }
    }

    private fun opportunityIndicators(shots: List<Shot>): List<VisualIndicator> {
        val out = ArrayList<VisualIndicator>()
        shots.forEachIndexed { index, shot ->
            val best = index == 0
            val shotColor = if (best) bestShotColor else altShotColor
            val targetColor = if (best) bestTargetColor else altShotColor
            val width = if (best) 8f else 5f

            // Branca -> ponto fantasma (como mirar).
            out += lineIndicator(shot.cueX, shot.cueY, shot.ghostX, shot.ghostY, shotColor, width, arrow = true)
            // Fantasma (posição da branca no contato).
            out += VisualIndicator(
                shape = VisualIndicatorShape.MARKER,
                x = shot.ghostX, y = shot.ghostY, radius = if (best) 11f else 8f, color = shotColor
            )
            // Bola -> caçapa.
            val label = if (best) "Melhor jogada • ${difficultyLabel(shot.cutCos)}" else null
            out += lineIndicator(shot.ball.x, shot.ball.y, shot.pocketX, shot.pocketY, targetColor, width, arrow = true, dashed = true, label = label)
            // Destaque da caçapa alvo.
            out += VisualIndicator(
                shape = VisualIndicatorShape.CIRCLE,
                x = shot.pocketX, y = shot.pocketY, radius = if (best) 26f else 18f,
                color = targetColor, strokeWidth = if (best) 6f else 3f
            )
        }
        return out
    }

    private fun difficultyLabel(cutCos: Float): String {
        val deg = Math.toDegrees(acos(cutCos.coerceIn(-1f, 1f).toDouble()))
        return when {
            deg < 20 -> "Fácil"
            deg < 45 -> "Média"
            else -> "Difícil"
        }
    }

    // =====================================================================
    // Tracker temporal das bolas
    // =====================================================================

    private fun updateBallTracker(detections: List<DetectedBall>): List<TrackedBall> {
        val used = BooleanArray(detections.size)

        for (tracked in trackedBalls) {
            var bestIdx = -1
            var bestDist = Float.MAX_VALUE
            for (i in detections.indices) {
                if (used[i]) continue
                val d = dist(tracked.x, tracked.y, detections[i].x, detections[i].y)
                if (d < bestDist && d < tracked.radius * 1.8f) {
                    bestDist = d
                    bestIdx = i
                }
            }
            if (bestIdx >= 0) {
                val det = detections[bestIdx]
                used[bestIdx] = true
                val a = DetectorConfig.positionSmoothing
                tracked.x += a * (det.x - tracked.x)
                tracked.y += a * (det.y - tracked.y)
                tracked.radius += a * (det.radius - tracked.radius)
                tracked.whiteness += a * (det.whiteness - tracked.whiteness)
                tracked.hits = min(tracked.hits + 1, 30)
                tracked.miss = 0
            } else {
                tracked.miss++
            }
        }

        for (i in detections.indices) {
            if (used[i]) continue
            val det = detections[i]
            trackedBalls.add(
                TrackedBall(nextBallId++, det.x, det.y, det.radius, det.whiteness, hits = 1, miss = 0)
            )
        }

        trackedBalls.removeAll { it.miss > DetectorConfig.ballMaxMissFrames }

        return trackedBalls.filter { it.hits >= DetectorConfig.ballPersistenceFrames && it.miss == 0 }
    }

    // =====================================================================
    // Construção de indicadores
    // =====================================================================

    private fun tableIndicator(table: TableRegion): VisualIndicator =
        VisualIndicator(
            shape = VisualIndicatorShape.RECT,
            x = table.playLeft, y = table.playTop, endX = table.playRight, endY = table.playBottom,
            color = tableColor, strokeWidth = 6f, label = "Mesa"
        )

    private fun ballIndicators(balls: List<TrackedBall>, main: TrackedBall?): List<VisualIndicator> {
        val out = ArrayList<VisualIndicator>(balls.size)
        for (ball in balls) {
            val isMain = ball === main
            out += VisualIndicator(
                shape = VisualIndicatorShape.CIRCLE,
                x = ball.x, y = ball.y, radius = ball.radius,
                color = if (isMain) mainBallColor else ballColor,
                strokeWidth = if (isMain) 7f else 5f,
                fill = isMain,
                crosshair = isMain,
                label = if (isMain) "Principal" else null
            )
        }
        return out
    }

    private fun lineIndicator(
        x: Float, y: Float, ex: Float, ey: Float, color: Int, width: Float,
        arrow: Boolean = false, dashed: Boolean = false, label: String? = null
    ): VisualIndicator = VisualIndicator(
        shape = VisualIndicatorShape.LINE,
        x = x, y = y, endX = ex, endY = ey, color = color,
        strokeWidth = width, arrow = arrow, dashed = dashed, label = label
    )

    private fun debugIndicators(matrix: AnalysisMatrix, table: TableRegion, cue: CueLine?): List<VisualIndicator> {
        val out = ArrayList<VisualIndicator>()
        out += VisualIndicator(
            shape = VisualIndicatorShape.RECT,
            x = table.left, y = table.top, endX = table.right, endY = table.bottom,
            color = debugColor, strokeWidth = 3f, dashed = true,
            label = "pano ${table.profile}"
        )
        if (cue != null) {
            out += lineIndicator(
                cue.axisX - cue.aimX * 300f, cue.axisY - cue.aimY * 300f,
                cue.axisX + cue.aimX * 300f, cue.axisY + cue.aimY * 300f,
                debugColor, 3f, arrow = true, label = "taco ${(cue.confidence * 100).roundToInt()}%"
            )
        }
        return out
    }

    // ---- utils -----------------------------------------------------------------

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    private fun dist(x1: Float, y1: Float, x2: Float, y2: Float): Float =
        hypot((x1 - x2).toDouble(), (y1 - y2).toDouble()).toFloat()

    private fun angleDelta(target: Float, current: Float): Float {
        var d = target - current
        while (d > Math.PI) d -= (2.0 * Math.PI).toFloat()
        while (d < -Math.PI) d += (2.0 * Math.PI).toFloat()
        return d
    }

    private fun List<Float>.sortedOrNull(): Float? =
        if (isEmpty()) null else sorted()[size / 2]

    // =====================================================================
    // Modelos internos
    // =====================================================================

    private class AnalysisMatrix(
        val width: Int,
        val height: Int,
        val scaleX: Float,
        val scaleY: Float,
        val hue: FloatArray,
        val sat: FloatArray,
        val value: FloatArray
    ) {
        val size: Int get() = width * height
        var cloth: BooleanArray = BooleanArray(0)
    }

    private data class Blob(
        val count: Long,
        val centerX: Float,
        val centerY: Float,
        val covXX: Float,
        val covYY: Float,
        val covXY: Float,
        val meanSat: Float,
        val meanVal: Float,
        val minX: Int,
        val minY: Int,
        val maxX: Int,
        val maxY: Int
    )

    private data class EigenAxis(
        val dirX: Float,
        val dirY: Float,
        val lambda1: Float,
        val lambda2: Float,
        val elongation: Float
    )

    private class TableRegion(
        val sampledMinX: Int,
        val sampledMinY: Int,
        val sampledMaxX: Int,
        val sampledMaxY: Int,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val playLeft: Float,
        val playTop: Float,
        val playRight: Float,
        val playBottom: Float,
        val profile: DetectorConfig.ClothProfile
    )

    private data class DetectedBall(
        val x: Float,
        val y: Float,
        val radius: Float,
        val whiteness: Float
    )

    private class TrackedBall(
        val id: Int,
        var x: Float,
        var y: Float,
        var radius: Float,
        var whiteness: Float,
        var hits: Int,
        var miss: Int
    )

    private class CueLine(
        val axisX: Float,
        val axisY: Float,
        val aimX: Float,
        val aimY: Float,
        val confidence: Float,
        val cueBall: TrackedBall?
    )

    private class GameAim(
        val dirX: Float,
        val dirY: Float,
        val confidence: Float
    )

    private class Shot(
        val ball: TrackedBall,
        val pocketX: Float,
        val pocketY: Float,
        val ghostX: Float,
        val ghostY: Float,
        val cueX: Float,
        val cueY: Float,
        val cutCos: Float,
        val score: Float
    )

    private class RailHit(
        val t: Float,
        val normalX: Float,
        val normalY: Float
    )

    private class PlayResolution(
        val mainBall: TrackedBall,
        val aimX: Float,
        val aimY: Float
    )
}
