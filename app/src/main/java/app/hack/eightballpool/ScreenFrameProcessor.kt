package app.hack.eightballpool

import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.util.Log
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

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
 * Processador matricial para detectar botões redondos e caixas de canto no ERP.
 *
 * Pipeline:
 * 1. Reduz o Bitmap para uma matriz leve de luminância.
 * 2. Calcula bordas por Sobel.
 * 3. Agrupa componentes conectados.
 * 4. Pontua componentes circulares e retangulares.
 * 5. Escolhe o círculo principal.
 * 6. Calcula vetores entre o círculo principal e os demais círculos.
 * 7. Prediz colisões em retas usando interseção raio-círculo.
 * 8. Envia círculos, caixas e linhas de colisão para a overlay.
 */
object ScreenFrameProcessor {

    private const val TAG = "ScreenFrameProcessor"
    private const val MAX_ANALYSIS_SIDE = 420
    private const val MIN_COMPONENT_PIXELS = 18
    private const val MAX_COMPONENTS = 220
    private const val MAX_CIRCLES = 14
    private const val MAX_CORNER_BOXES = 4
    private const val MAX_COLLISION_LINES = 8

    private val mainCircleColor = Color.argb(235, 255, 193, 7)
    private val secondaryCircleColor = Color.argb(220, 0, 188, 212)
    private val collisionLineColor = Color.argb(230, 255, 82, 82)
    private val impactColor = Color.argb(235, 255, 64, 129)
    private val cornerBoxColor = Color.argb(220, 156, 39, 176)

    @Volatile
    private var latestFrame: CapturedScreenFrame? = null

    /**
     * Quando true, roda a detecção matricial padrão e atualiza o OverlayIndicatorBus.
     */
    @Volatile
    var autoDetectEnabled: Boolean = true

    /**
     * Hook opcional para processamento adicional do app.
     */
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

        if (autoDetectEnabled) {
            runCatching { detectAndPublish(frame) }
                .onFailure { error ->
                    Log.e(TAG, "Failed to process screen frame", error)
                    OverlayIndicatorBus.clear()
                }
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
        OverlayIndicatorBus.clear()
    }

    private fun detectAndPublish(frame: CapturedScreenFrame) {
        val matrix = buildAnalysisMatrix(frame.bitmap)
        val components = detectComponents(matrix)
        val circles = detectCircles(matrix, components)
        val cornerBoxes = detectCornerBoxes(matrix, components)
        val mainCircle = selectMainCircle(circles, frame.width, frame.height)
        val collisions = predictStraightLineCollisions(mainCircle, circles)

        val indicators = buildOverlayIndicators(
            mainCircle = mainCircle,
            circles = circles,
            cornerBoxes = cornerBoxes,
            collisions = collisions
        )

        OverlayIndicatorBus.setIndicators(indicators)
    }

    private fun buildAnalysisMatrix(bitmap: Bitmap): AnalysisMatrix {
        val sampleStep = max(
            1,
            ceil(max(bitmap.width, bitmap.height).toDouble() / MAX_ANALYSIS_SIDE).toInt()
        )
        val sampledWidth = max(1, ceil(bitmap.width.toDouble() / sampleStep).toInt())
        val sampledHeight = max(1, ceil(bitmap.height.toDouble() / sampleStep).toInt())
        val scaleX = bitmap.width.toFloat() / sampledWidth.toFloat()
        val scaleY = bitmap.height.toFloat() / sampledHeight.toFloat()

        val luminance = IntArray(sampledWidth * sampledHeight)
        val sourceRow = IntArray(bitmap.width)

        for (sampleY in 0 until sampledHeight) {
            val sourceY = min(bitmap.height - 1, sampleY * sampleStep)
            bitmap.getPixels(sourceRow, 0, bitmap.width, 0, sourceY, bitmap.width, 1)

            for (sampleX in 0 until sampledWidth) {
                val sourceX = min(bitmap.width - 1, sampleX * sampleStep)
                val pixel = sourceRow[sourceX]
                val red = Color.red(pixel)
                val green = Color.green(pixel)
                val blue = Color.blue(pixel)
                luminance[sampleY * sampledWidth + sampleX] = (red * 77 + green * 150 + blue * 29) shr 8
            }
        }

        val edge = FloatArray(luminance.size)
        var edgeSum = 0.0
        var edgeSquareSum = 0.0
        var measuredEdges = 0

        for (y in 1 until sampledHeight - 1) {
            for (x in 1 until sampledWidth - 1) {
                val top = (y - 1) * sampledWidth
                val mid = y * sampledWidth
                val bottom = (y + 1) * sampledWidth

                val gx =
                    -luminance[top + x - 1] - 2 * luminance[mid + x - 1] - luminance[bottom + x - 1] +
                        luminance[top + x + 1] + 2 * luminance[mid + x + 1] + luminance[bottom + x + 1]

                val gy =
                    -luminance[top + x - 1] - 2 * luminance[top + x] - luminance[top + x + 1] +
                        luminance[bottom + x - 1] + 2 * luminance[bottom + x] + luminance[bottom + x + 1]

                val strength = abs(gx) + abs(gy)
                val index = mid + x
                edge[index] = strength.toFloat()
                edgeSum += strength.toDouble()
                edgeSquareSum += strength.toDouble() * strength.toDouble()
                measuredEdges++
            }
        }

        val edgeMean = if (measuredEdges > 0) edgeSum / measuredEdges else 0.0
        val edgeVariance = if (measuredEdges > 0) {
            max(0.0, edgeSquareSum / measuredEdges - edgeMean * edgeMean)
        } else {
            0.0
        }
        val edgeStd = sqrt(edgeVariance)
        val edgeThreshold = max(48.0, edgeMean + edgeStd * 0.85)
        val edgeMask = BooleanArray(edge.size)

        for (index in edge.indices) {
            edgeMask[index] = edge[index] >= edgeThreshold
        }

        return AnalysisMatrix(
            width = sampledWidth,
            height = sampledHeight,
            scaleX = scaleX,
            scaleY = scaleY,
            luminance = luminance,
            edge = edge,
            edgeMask = edgeMask
        )
    }

    private fun detectComponents(matrix: AnalysisMatrix): List<Component> {
        val visited = BooleanArray(matrix.edgeMask.size)
        val components = mutableListOf<Component>()
        val queue = ArrayDeque<Int>()

        for (startIndex in matrix.edgeMask.indices) {
            if (visited[startIndex] || !matrix.edgeMask[startIndex]) continue

            val points = ArrayList<Int>()
            var minX = Int.MAX_VALUE
            var minY = Int.MAX_VALUE
            var maxX = Int.MIN_VALUE
            var maxY = Int.MIN_VALUE
            var sumX = 0L
            var sumY = 0L

            visited[startIndex] = true
            queue.add(startIndex)

            while (queue.isNotEmpty()) {
                val index = queue.removeFirst()
                points.add(index)

                val x = index % matrix.width
                val y = index / matrix.width

                minX = min(minX, x)
                minY = min(minY, y)
                maxX = max(maxX, x)
                maxY = max(maxY, y)
                sumX += x.toLong()
                sumY += y.toLong()

                val fromY = max(1, y - 1)
                val toY = min(matrix.height - 2, y + 1)
                val fromX = max(1, x - 1)
                val toX = min(matrix.width - 2, x + 1)

                for (ny in fromY..toY) {
                    for (nx in fromX..toX) {
                        if (nx == x && ny == y) continue
                        val neighborIndex = ny * matrix.width + nx
                        if (!visited[neighborIndex] && matrix.edgeMask[neighborIndex]) {
                            visited[neighborIndex] = true
                            queue.add(neighborIndex)
                        }
                    }
                }
            }

            if (points.size >= MIN_COMPONENT_PIXELS) {
                components.add(
                    Component(
                        points = points.toIntArray(),
                        minX = minX,
                        minY = minY,
                        maxX = maxX,
                        maxY = maxY,
                        sumX = sumX,
                        sumY = sumY
                    )
                )
            }

            if (components.size >= MAX_COMPONENTS) break
        }

        return components
    }

    private fun detectCircles(matrix: AnalysisMatrix, components: List<Component>): List<CircleCandidate> {
        val candidates = mutableListOf<CircleCandidate>()
        val maxCircleSide = min(matrix.width, matrix.height) * 0.45f

        components.forEach { component ->
            val boxWidth = component.width
            val boxHeight = component.height
            val minSide = min(boxWidth, boxHeight)
            val maxSide = max(boxWidth, boxHeight)

            if (minSide < 6 || maxSide > maxCircleSide) return@forEach

            val aspectScore = minSide.toFloat() / maxSide.toFloat()
            if (aspectScore < 0.66f) return@forEach

            val centerX = component.sumX.toFloat() / component.points.size.toFloat()
            val centerY = component.sumY.toFloat() / component.points.size.toFloat()
            val radius = (boxWidth + boxHeight) / 4f
            if (radius <= 0f) return@forEach

            var radialError = 0f
            component.points.forEach { point ->
                val x = point % matrix.width
                val y = point / matrix.width
                radialError += abs(hypot((x - centerX).toDouble(), (y - centerY).toDouble()).toFloat() - radius)
            }

            val normalizedRadialError = radialError / (component.points.size.toFloat() * max(1f, radius))
            if (normalizedRadialError > 0.42f) return@forEach

            val perimeter = (2.0 * Math.PI * radius).toFloat()
            val edgeDensity = component.points.size.toFloat() / max(1f, perimeter)
            val radialScore = (1f - normalizedRadialError * 1.65f).coerceIn(0f, 1f)
            val densityScore = (edgeDensity / 1.65f).coerceIn(0.25f, 1f)
            val score = aspectScore * radialScore * densityScore

            val originalRadius = radius * ((matrix.scaleX + matrix.scaleY) / 2f)
            if (score < 0.28f || originalRadius < 18f || originalRadius > 260f) return@forEach

            candidates.add(
                CircleCandidate(
                    x = centerX * matrix.scaleX,
                    y = centerY * matrix.scaleY,
                    radius = originalRadius,
                    score = score
                )
            )
        }

        return suppressOverlappingCircles(candidates)
            .sortedWith(compareByDescending<CircleCandidate> { it.score * it.radius })
            .take(MAX_CIRCLES)
    }

    private fun suppressOverlappingCircles(circles: List<CircleCandidate>): List<CircleCandidate> {
        val selected = mutableListOf<CircleCandidate>()
        circles.sortedWith(compareByDescending<CircleCandidate> { it.score * it.radius }).forEach { circle ->
            val overlaps = selected.any { existing ->
                distance(circle.x, circle.y, existing.x, existing.y) < max(circle.radius, existing.radius) * 0.65f
            }
            if (!overlaps) selected.add(circle)
        }
        return selected
    }

    private fun detectCornerBoxes(matrix: AnalysisMatrix, components: List<Component>): List<CornerBoxCandidate> {
        val cornerZoneX = matrix.width * 0.30f
        val cornerZoneY = matrix.height * 0.30f
        val candidates = mutableListOf<CornerBoxCandidate>()

        components.forEach { component ->
            val boxWidth = component.width
            val boxHeight = component.height
            val minSide = min(boxWidth, boxHeight)
            val maxSide = max(boxWidth, boxHeight)

            if (minSide < 8 || maxSide < 16) return@forEach
            if (maxSide > max(matrix.width, matrix.height) * 0.45f) return@forEach

            val centerX = (component.minX + component.maxX) / 2f
            val centerY = (component.minY + component.maxY) / 2f
            val nearCorner =
                (centerX <= cornerZoneX && centerY <= cornerZoneY) ||
                    (centerX >= matrix.width - cornerZoneX && centerY <= cornerZoneY) ||
                    (centerX <= cornerZoneX && centerY >= matrix.height - cornerZoneY) ||
                    (centerX >= matrix.width - cornerZoneX && centerY >= matrix.height - cornerZoneY)

            if (!nearCorner) return@forEach

            val perimeter = 2f * (boxWidth + boxHeight)
            val edgeDensity = component.points.size.toFloat() / max(1f, perimeter)
            val aspect = minSide.toFloat() / maxSide.toFloat()
            val score = (edgeDensity / 1.4f).coerceIn(0f, 1f) * (0.45f + aspect * 0.55f)

            if (score < 0.22f) return@forEach

            candidates.add(
                CornerBoxCandidate(
                    x = centerX * matrix.scaleX,
                    y = centerY * matrix.scaleY,
                    width = boxWidth * matrix.scaleX,
                    height = boxHeight * matrix.scaleY,
                    score = score
                )
            )
        }

        return candidates
            .sortedByDescending { it.score * it.width * it.height }
            .take(MAX_CORNER_BOXES)
    }

    private fun selectMainCircle(
        circles: List<CircleCandidate>,
        frameWidth: Int,
        frameHeight: Int
    ): CircleCandidate? {
        if (circles.isEmpty()) return null

        val screenCenterX = frameWidth / 2f
        val screenCenterY = frameHeight / 2f
        val maxCenterDistance = hypot(screenCenterX.toDouble(), screenCenterY.toDouble()).toFloat()

        return circles.maxByOrNull { circle ->
            val centerDistance = distance(circle.x, circle.y, screenCenterX, screenCenterY)
            val centrality = 1f - (centerDistance / max(1f, maxCenterDistance)).coerceIn(0f, 1f)
            circle.radius * 1.35f + circle.score * 120f + centrality * 45f
        }
    }

    private fun predictStraightLineCollisions(
        mainCircle: CircleCandidate?,
        circles: List<CircleCandidate>
    ): List<CollisionCandidate> {
        val main = mainCircle ?: return emptyList()
        val targets = circles.filterNot { it === main }
        val collisions = mutableListOf<CollisionCandidate>()

        targets.forEach { target ->
            val directionX = target.x - main.x
            val directionY = target.y - main.y
            val totalDistance = hypot(directionX.toDouble(), directionY.toDouble()).toFloat()
            if (totalDistance <= 1f) return@forEach

            val unitX = directionX / totalDistance
            val unitY = directionY / totalDistance

            val firstHit = targets.mapNotNull { obstacle ->
                intersectMovingCircleWithObstacle(
                    main = main,
                    obstacle = obstacle,
                    unitX = unitX,
                    unitY = unitY,
                    maxDistance = totalDistance
                )
            }.minByOrNull { it.travelDistance }

            firstHit?.let { collisions.add(it) }
        }

        return collisions
            .sortedBy { it.travelDistance }
            .distinctBy { collision ->
                "${(collision.target.x / 24f).roundToInt()}:${(collision.target.y / 24f).roundToInt()}"
            }
            .take(MAX_COLLISION_LINES)
    }

    private fun intersectMovingCircleWithObstacle(
        main: CircleCandidate,
        obstacle: CircleCandidate,
        unitX: Float,
        unitY: Float,
        maxDistance: Float
    ): CollisionCandidate? {
        val relativeX = obstacle.x - main.x
        val relativeY = obstacle.y - main.y
        val projection = relativeX * unitX + relativeY * unitY

        if (projection <= main.radius || projection > maxDistance + obstacle.radius) return null

        val relativeDistanceSquared = relativeX * relativeX + relativeY * relativeY
        val closestDistanceSquared = relativeDistanceSquared - projection * projection
        val expandedRadius = main.radius + obstacle.radius
        val expandedRadiusSquared = expandedRadius * expandedRadius

        if (closestDistanceSquared > expandedRadiusSquared) return null

        val travelDistance = projection - sqrt(max(0f, expandedRadiusSquared - closestDistanceSquared))
        if (travelDistance < main.radius || travelDistance > maxDistance) return null

        val startX = main.x + unitX * main.radius
        val startY = main.y + unitY * main.radius
        val impactX = main.x + unitX * travelDistance
        val impactY = main.y + unitY * travelDistance
        val clearance = projection - expandedRadius

        return CollisionCandidate(
            startX = startX,
            startY = startY,
            impactX = impactX,
            impactY = impactY,
            travelDistance = travelDistance,
            clearance = clearance,
            target = obstacle
        )
    }

    private fun buildOverlayIndicators(
        mainCircle: CircleCandidate?,
        circles: List<CircleCandidate>,
        cornerBoxes: List<CornerBoxCandidate>,
        collisions: List<CollisionCandidate>
    ): List<VisualIndicator> {
        val indicators = mutableListOf<VisualIndicator>()

        cornerBoxes.forEachIndexed { index, box ->
            indicators.add(
                VisualIndicator(
                    x = box.x,
                    y = box.y,
                    radius = 14f,
                    label = "Caixa ${index + 1}",
                    color = cornerBoxColor,
                    shape = VisualIndicatorShape.BOX,
                    width = box.width,
                    height = box.height
                )
            )
        }

        mainCircle?.let { main ->
            indicators.add(
                VisualIndicator(
                    x = main.x,
                    y = main.y,
                    radius = main.radius,
                    label = "Principal",
                    color = mainCircleColor,
                    shape = VisualIndicatorShape.CIRCLE
                )
            )
        }

        circles.filterNot { it === mainCircle }
            .take(8)
            .forEachIndexed { index, circle ->
                val label = mainCircle?.let { main ->
                    val distance = distance(main.x, main.y, circle.x, circle.y).roundToInt()
                    "C${index + 1} ${distance}px"
                } ?: "C${index + 1}"

                indicators.add(
                    VisualIndicator(
                        x = circle.x,
                        y = circle.y,
                        radius = circle.radius,
                        label = label,
                        color = secondaryCircleColor,
                        shape = VisualIndicatorShape.CIRCLE
                    )
                )
            }

        collisions.forEachIndexed { index, collision ->
            indicators.add(
                VisualIndicator(
                    x = collision.startX,
                    y = collision.startY,
                    radius = 8f,
                    label = "Reta ${index + 1}: ${collision.travelDistance.roundToInt()}px",
                    color = collisionLineColor,
                    shape = VisualIndicatorShape.LINE,
                    lineEndX = collision.impactX,
                    lineEndY = collision.impactY
                )
            )
            indicators.add(
                VisualIndicator(
                    x = collision.impactX,
                    y = collision.impactY,
                    radius = 18f,
                    label = "Impacto",
                    color = impactColor,
                    shape = VisualIndicatorShape.CIRCLE
                )
            )
        }

        return indicators
    }

    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        return hypot((x1 - x2).toDouble(), (y1 - y2).toDouble()).toFloat()
    }

    private data class AnalysisMatrix(
        val width: Int,
        val height: Int,
        val scaleX: Float,
        val scaleY: Float,
        val luminance: IntArray,
        val edge: FloatArray,
        val edgeMask: BooleanArray
    )

    private data class Component(
        val points: IntArray,
        val minX: Int,
        val minY: Int,
        val maxX: Int,
        val maxY: Int,
        val sumX: Long,
        val sumY: Long
    ) {
        val width: Int get() = maxX - minX + 1
        val height: Int get() = maxY - minY + 1
    }

    private data class CircleCandidate(
        val x: Float,
        val y: Float,
        val radius: Float,
        val score: Float
    )

    private data class CornerBoxCandidate(
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float,
        val score: Float
    )

    private data class CollisionCandidate(
        val startX: Float,
        val startY: Float,
        val impactX: Float,
        val impactY: Float,
        val travelDistance: Float,
        val clearance: Float,
        val target: CircleCandidate
    )
}
