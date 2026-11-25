package com.ats.gps.ui.map

import android.graphics.Typeface
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import com.ats.gps.data.map.RouteResult
import com.ats.gps.data.model.MapEdge
import com.ats.gps.data.model.MapGraph
import com.ats.gps.data.model.MapPoi
import com.ats.gps.data.model.Point3D
import com.ats.gps.data.model.RoadSnapResult
import com.ats.gps.data.model.TelemetryData
import kotlin.math.*

enum class MapViewMode {
    MODE_2D_TOP_DOWN,
    MODE_3D_PERSPECTIVE
}

enum class MapThemeStyle {
    DARK_PREMIUM,
    HIGH_CONTRAST,
    CYBER_NEON
}

// =====================================================================
// ESTRUCTURAS DE CACHÉ DE ALTO RENDIMIENTO (CERO ASIGNACIÓN POR FRAME)
// =====================================================================

private class SnapCache {
    var cachedGraph: MapGraph? = null
    var cachedRoute: RouteResult? = null
    var lastX: Float = Float.NaN
    var lastZ: Float = Float.NaN
    var lastHeading: Float = Float.NaN
    var cachedSnap: RoadSnapResult? = null
}

private class Viewport2DCache {
    var cachedGraph: MapGraph? = null
    var cachedRoute: RouteResult? = null
    var hasCache: Boolean = false
    var edges: List<MapEdge> = emptyList()
    var lastX: Float = Float.NaN
    var lastZ: Float = Float.NaN
    var lastScale: Float = 1.0f
    var lastWasLowZoom: Boolean = false

    // Cadenas pre-calculadas y suavizadas en coordenadas del mundo
    var rampChains: List<List<Point3D>> = emptyList()
    var localChains: List<List<Point3D>> = emptyList()
    var mainChains: List<List<Point3D>> = emptyList()
    var hwyChains: List<List<Point3D>> = emptyList()
    var hiddenChains: List<List<Point3D>> = emptyList()
}

private class RoadSeg3D(
    val isFreeway: Boolean,
    val lanes: Int,
    val laneWidthFactor: Float,
    val points: List<Point3D>,
    val isHidden: Boolean = false
)

private class Viewport3DCache {
    var cachedGraph: MapGraph? = null
    var cachedRoute: RouteResult? = null
    var hasCache: Boolean = false
    var edges: List<MapEdge> = emptyList()
    var lastX: Float = Float.NaN
    var lastZ: Float = Float.NaN
    var roadSegs: List<RoadSeg3D> = emptyList()
}

private class RouteRenderCache {
    var cachedRoute: RouteResult? = null
    var fullSmoothedPoints: List<Point3D> = emptyList()
    var cumDistances: FloatArray = FloatArray(0)
    var lastTruckX: Float = Float.NaN
    var lastTruckZ: Float = Float.NaN
    var lastTrimIdx: Int = 0
    var trimProjPoint: Point3D? = null
}

private class RenderScratchCache {
    var theme: MapThemeStyle? = null
    var height3D: Float = -1f
    var skyBrush: Brush? = null
    var horizonGlowBrush: Brush? = null
    var groundBrush: Brush? = null

    var headlightHeight: Float = -1f
    var headlightDensity: Float = -1f
    var headlightBrush: Brush? = null

    var lastDashZoom: Float = -1f
    var dashEffect: PathEffect? = null

    // Coordenadas de proyeccion reutilizables (cero boxing Pair/Offset)
    var projX1: Float = 0f
    var projY1: Float = 0f
    var projX2: Float = 0f
    var projY2: Float = 0f
}

private class ReusableRoadSeg(
    var x1: Float = 0f,
    var y1: Float = 0f,
    var x2: Float = 0f,
    var y2: Float = 0f,
    var casingW: Float = 0f,
    var depthRatio: Float = 0f,
    var isFreeway: Boolean = false,
    var lanes: Int = 1,
    var isHidden: Boolean = false
)

private class RoadSegPool3D(capacity: Int = 8192) {
    val pool = Array(capacity) { ReusableRoadSeg() }
    val indices = IntArray(capacity) { it }
    var count = 0

    fun reset() {
        count = 0
    }

    fun add(
        x1: Float, y1: Float, x2: Float, y2: Float,
        casingW: Float, depthRatio: Float,
        isFreeway: Boolean, lanes: Int,
        isHidden: Boolean = false
    ) {
        if (count >= pool.size) return
        val s = pool[count]
        s.x1 = x1
        s.y1 = y1
        s.x2 = x2
        s.y2 = y2
        s.casingW = casingW
        s.depthRatio = depthRatio
        s.isFreeway = isFreeway
        s.lanes = lanes
        s.isHidden = isHidden
        indices[count] = count
        count++
    }

    fun sortBackToFront() {
        if (count > 1) {
            quickSort(0, count - 1)
        }
    }

    private fun quickSort(low: Int, high: Int) {
        if (low >= high) return
        val pivotY = getMidY(indices[(low + high) ushr 1])
        var i = low
        var j = high
        while (i <= j) {
            while (getMidY(indices[i]) < pivotY) i++
            while (getMidY(indices[j]) > pivotY) j--
            if (i <= j) {
                val temp = indices[i]
                indices[i] = indices[j]
                indices[j] = temp
                i++
                j--
            }
        }
        if (low < j) quickSort(low, j)
        if (i < high) quickSort(i, high)
    }

    private fun getMidY(idx: Int): Float {
        val s = pool[idx]
        return s.y1 + s.y2
    }
}

private class ReusableRouteSeg(
    var x1: Float = 0f,
    var y1: Float = 0f,
    var x2: Float = 0f,
    var y2: Float = 0f,
    var rWidth: Float = 0f
)

private class RouteSegPool3D(capacity: Int = 4096) {
    val pool = Array(capacity) { ReusableRouteSeg() }
    var count = 0

    fun reset() {
        count = 0
    }

    fun add(x1: Float, y1: Float, x2: Float, y2: Float, rWidth: Float) {
        if (count >= pool.size) return
        val s = pool[count]
        s.x1 = x1
        s.y1 = y1
        s.x2 = x2
        s.y2 = y2
        s.rWidth = rWidth
        count++
    }
}

private class ReusableCanvasPaths {
    val rampPath = Path()
    val localPath = Path()
    val mainPath = Path()
    val hwyPath = Path()
    val hiddenPath = Path()
    val routePath = Path()
    val headlightPath = Path()
    val leftFacet = Path()
    val rightFacet = Path()
    val outlinePath = Path()
    val cursorPath = Path()
}

@Composable
fun GpsMapCanvas(
    graph: MapGraph?,
    telemetry: TelemetryData,
    activeRoute: RouteResult?,
    viewMode: MapViewMode = MapViewMode.MODE_3D_PERSPECTIVE,
    mapTheme: MapThemeStyle = MapThemeStyle.DARK_PREMIUM,
    zoomScale: Float = 1.0f,
    panOffset: Offset = Offset.Zero,
    onZoomChange: (Float) -> Unit = {},
    onPanChange: (Offset) -> Unit = {},
    onMapTapped: (Float, Float) -> Unit = { _, _ -> },
    isFollowingTruck: Boolean = true,
    modifier: Modifier = Modifier
) {
    val currentZoom by rememberUpdatedState(zoomScale)
    val currentPan by rememberUpdatedState(panOffset)
    val currentViewMode by rememberUpdatedState(viewMode)
    val currentFollowing by rememberUpdatedState(isFollowingTruck)
    val currentSpeedKmh by rememberUpdatedState(if (telemetry.speedKmh.isFinite()) telemetry.speedKmh else 0f)
    val currentOnZoom by rememberUpdatedState(onZoomChange)
    val currentOnPan by rememberUpdatedState(onPanChange)
    val currentOnMapTapped by rememberUpdatedState(onMapTapped)

    // Coordenadas objetivo en tiempo real (rememberUpdatedState para evitar capturas estáticas)
    val targetX by rememberUpdatedState(if (telemetry.posX.isFinite()) telemetry.posX.toFloat() else 0f)
    val targetY by rememberUpdatedState(if (telemetry.posY.isFinite()) telemetry.posY.toFloat() else 0f)
    val targetZ by rememberUpdatedState(if (telemetry.posZ.isFinite()) telemetry.posZ.toFloat() else 0f)
    val targetHeading by rememberUpdatedState(if (telemetry.headingDegrees.isFinite()) telemetry.headingDegrees.toFloat() else 0f)

    // Estados suavizados para renderizado continuo e interpolado (60-120 Hz)
    var smoothTruckX by remember { mutableFloatStateOf(targetX) }
    var smoothTruckY by remember { mutableFloatStateOf(targetY) }
    var smoothTruckZ by remember { mutableFloatStateOf(targetZ) }
    var smoothHeading by remember { mutableFloatStateOf(((targetHeading % 360f) + 360f) % 360f) }

    LaunchedEffect(telemetry.posX, telemetry.posZ) {
        if (abs(smoothTruckX) < 0.001f && abs(smoothTruckZ) < 0.001f && (abs(targetX) > 0.001f || abs(targetZ) > 0.001f)) {
            smoothTruckX = targetX
            smoothTruckY = targetY
            smoothTruckZ = targetZ
            smoothHeading = ((targetHeading % 360f) + 360f) % 360f
        }
    }

    // Bucle de interpolación continua a la tasa de refresco del display
    LaunchedEffect(Unit) {
        var lastTime = 0L
        while (true) {
            withFrameNanos { now ->
                val dt = if (lastTime == 0L) 0.016f else ((now - lastTime) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
                lastTime = now

                val lerpFactor = (15f * dt).coerceIn(0.05f, 0.45f)

                val tx = targetX
                val ty = targetY
                val tz = targetZ
                val th = targetHeading

                val dx = tx - smoothTruckX
                val dy = ty - smoothTruckY
                val dz = tz - smoothTruckZ
                val distSq = dx * dx + dz * dz

                if (distSq > 10000f) {
                    smoothTruckX = tx
                    smoothTruckY = ty
                    smoothTruckZ = tz
                    smoothHeading = ((th % 360f) + 360f) % 360f
                } else {
                    smoothTruckX += dx * lerpFactor
                    smoothTruckY += dy * lerpFactor
                    smoothTruckZ += dz * lerpFactor

                    val headingDiff = ((th - smoothHeading) % 360f + 540f) % 360f - 180f
                    smoothHeading = (((smoothHeading + headingDiff * lerpFactor) % 360f) + 360f) % 360f
                }
            }
        }
    }

    // Estructuras de caché persistentes en memoria (reutilizadas sin recolector de basura)
    val snapCache = remember { SnapCache() }
    val cache2D = remember { Viewport2DCache() }
    val cache3D = remember { Viewport3DCache() }
    val routeCache = remember { RouteRenderCache() }
    val scratchCache = remember { RenderScratchCache() }
    val roadSegPool = remember { RoadSegPool3D() }
    val routeSegPool = remember { RouteSegPool3D() }
    val reusablePaths = remember { ReusableCanvasPaths() }

    // Pinturas cacheadas para texto y marcadores POI
    val cityPaint = remember {
        android.graphics.Paint().apply {
            color = android.graphics.Color.WHITE
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
            setShadowLayer(4f, 0f, 0f, android.graphics.Color.BLACK)
        }
    }
    val poiIconPaint = remember {
        android.graphics.Paint().apply {
            color = android.graphics.Color.WHITE
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = android.graphics.Paint.Align.CENTER
            isAntiAlias = true
        }
    }
    val poiLabelPaint = remember {
        android.graphics.Paint().apply {
            color = android.graphics.Color.WHITE
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = android.graphics.Paint.Align.CENTER
            isAntiAlias = true
            setShadowLayer(3f, 0f, 0f, android.graphics.Color.BLACK)
        }
    }
    val poiBgPaint = remember {
        android.graphics.Paint().apply {
            color = android.graphics.Color.argb(220, 15, 23, 42)
            isAntiAlias = true
        }
    }
    val emptyStatePaint = remember {
        android.graphics.Paint().apply {
            color = android.graphics.Color.WHITE
            textAlign = android.graphics.Paint.Align.CENTER
            isAntiAlias = true
        }
    }

    val canvasBg = when (mapTheme) {
        MapThemeStyle.DARK_PREMIUM -> Color(0xFF0A0F1D)
        MapThemeStyle.HIGH_CONTRAST -> Color(0xFF000000)
        MapThemeStyle.CYBER_NEON -> Color(0xFF070B12)
    }

    val cursorInfinite = rememberInfiniteTransition(label = "TruckCursorPulse")
    val cursorPulseAlpha by cursorInfinite.animateFloat(
        initialValue = 0.30f,
        targetValue = 0.68f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "TruckCursorPulseAlpha"
    )

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .background(canvasBg)
            .pointerInput(Unit) {
                detectTapGestures { tapOffset ->
                    if (currentViewMode == MapViewMode.MODE_2D_TOP_DOWN) {
                        val cx = size.width / 2f
                        val cy = size.height * 0.62f
                        val baseScale = (0.75f * currentZoom).coerceIn(0.05f, 6.0f)
                        val viewCenterX = smoothTruckX - (if (!currentFollowing) currentPan.x / baseScale else 0f)
                        val viewCenterZ = smoothTruckZ - (if (!currentFollowing) currentPan.y / baseScale else 0f)

                        val worldX = viewCenterX + (tapOffset.x - cx) / baseScale
                        val worldZ = viewCenterZ + (tapOffset.y - cy) / baseScale
                        currentOnMapTapped(worldX, worldZ)
                    } else {
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        val horizonY = h * 0.26f
                        if (tapOffset.y <= horizonY + 8f) return@detectTapGestures

                        val targetTruckY = h * 0.78f
                        val camDist3D = 65f
                        val speedRatio = (currentSpeedKmh.coerceAtLeast(0f) / 90f).coerceIn(0f, 1f)
                        val dynamicZoom = currentZoom * (0.85f + speedRatio * 0.35f)
                        val focal3D = 440f * dynamicZoom.coerceIn(0.6f, 2.0f)
                        val camHeight3D = (targetTruckY - horizonY) / (focal3D / camDist3D)

                        val factor = (tapOffset.y - horizonY) / camHeight3D
                        if (factor <= 0.01f) return@detectTapGestures
                        val depth = focal3D / factor
                        if (depth > 2500f) return@detectTapGestures
                        val rz = depth - camDist3D
                        val rx = (tapOffset.x - w * 0.5f) / factor

                        val camHeadingRad = Math.toRadians(smoothHeading.toDouble()).toFloat()
                        val cosH = cos(-camHeadingRad)
                        val sinH = sin(-camHeadingRad)
                        val dx = rx * cosH - rz * sinH
                        val dz = -(rx * sinH + rz * cosH)
                        currentOnMapTapped(smoothTruckX + dx, smoothTruckZ + dz)
                    }
                }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    var zoomAcc = 1f
                    var panAcc = Offset.Zero
                    var pastTouchSlop = false
                    val touchSlop = viewConfiguration.touchSlop
                    var pendingPan = Offset.Zero

                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val canceled = event.changes.any { it.isConsumed }
                        if (!canceled) {
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()
                            if (!pastTouchSlop) {
                                zoomAcc *= zoomChange
                                panAcc += panChange
                                val centroidSize = event.calculateCentroidSize(useCurrent = false)
                                val zoomMotion = abs(1f - zoomAcc) * centroidSize
                                val panMotion = panAcc.getDistance()
                                if (zoomMotion > touchSlop || panMotion > touchSlop) {
                                    pastTouchSlop = true
                                }
                            }
                            if (pastTouchSlop) {
                                val newZoom = if (currentViewMode == MapViewMode.MODE_3D_PERSPECTIVE) {
                                    (currentZoom * zoomChange).coerceIn(0.55f, 2.1f)
                                } else {
                                    (currentZoom * zoomChange).coerceIn(0.10f, 4.5f)
                                }
                                if (zoomChange != 1f) {
                                    currentOnZoom(newZoom)
                                }
                                if (currentViewMode == MapViewMode.MODE_2D_TOP_DOWN) {
                                    val isPinchZoom = abs(zoomChange - 1f) >= 0.03f
                                    if (isPinchZoom) {
                                        pendingPan = Offset.Zero
                                    } else if (panChange.x != 0f || panChange.y != 0f) {
                                        if (!currentFollowing) {
                                            currentOnPan(currentPan + panChange)
                                        } else {
                                            pendingPan += panChange
                                            if (pendingPan.getDistance() > 8f) {
                                                currentOnPan(currentPan + pendingPan)
                                                pendingPan = Offset.Zero
                                            }
                                        }
                                    }
                                }
                                event.changes.forEach {
                                    if (it.positionChanged()) it.consume()
                                }
                            }
                        }
                    } while (!canceled && event.changes.any { it.pressed })
                }
            }
    ) {
        if (graph == null || graph.nodes.isEmpty()) {
            drawEmptyState(emptyStatePaint)
            return@Canvas
        }

        // Snapping inteligente: re-consulta el MapEdge cada > 1.8m o > 8 deg, pero proyecta cada frame a 60-120 FPS
        val dxSnap = smoothTruckX - snapCache.lastX
        val dzSnap = smoothTruckZ - snapCache.lastZ
        val dHeadingSnap = if (snapCache.lastHeading.isNaN()) 999f else {
            abs(((smoothHeading - snapCache.lastHeading) % 360f + 540f) % 360f - 180f)
        }
        val snap = if (snapCache.cachedGraph !== graph || snapCache.cachedRoute !== activeRoute ||
            snapCache.lastX.isNaN() || (dxSnap * dxSnap + dzSnap * dzSnap) > 3.24f || dHeadingSnap > 8f
        ) {
            val s = graph.snapToRoad(
                x = smoothTruckX,
                z = smoothTruckZ,
                headingDeg = smoothHeading,
                preferredEdges = activeRoute?.edgePath,
                maxDistMeters = 26f
            )
            snapCache.cachedGraph = graph
            snapCache.cachedRoute = activeRoute
            snapCache.lastX = smoothTruckX
            snapCache.lastZ = smoothTruckZ
            snapCache.lastHeading = smoothHeading
            snapCache.cachedSnap = s
            s
        } else {
            snapCache.cachedSnap
        }

        var truckX = smoothTruckX
        var truckZ = smoothTruckZ
        if (snap != null) {
            val poly = snap.edge.polyline
            if (poly.size >= 2) {
                val startSeg = (snap.segmentIndex - 1).coerceAtLeast(0)
                val endSeg = (snap.segmentIndex + 1).coerceAtMost(poly.size - 2)
                var bestSq = Float.MAX_VALUE
                var bestPx = snap.snappedX
                var bestPz = snap.snappedZ
                for (i in startSeg..endSeg) {
                    val p1 = poly[i]
                    val p2 = poly[i + 1]
                    val vx = p2.x - p1.x
                    val vz = p2.z - p1.z
                    val lenSq = vx * vx + vz * vz
                    val t = if (lenSq > 0.0001f) {
                        (((smoothTruckX - p1.x) * vx + (smoothTruckZ - p1.z) * vz) / lenSq).coerceIn(0f, 1f)
                    } else 0f
                    val px = p1.x + t * vx
                    val pz = p1.z + t * vz
                    val dSq = (smoothTruckX - px) * (smoothTruckX - px) + (smoothTruckZ - pz) * (smoothTruckZ - pz)
                    if (dSq < bestSq) {
                        bestSq = dSq
                        bestPx = px
                        bestPz = pz
                    }
                }
                truckX = bestPx
                truckZ = bestPz
            } else {
                truckX = snap.snappedX
                truckZ = snap.snappedZ
            }
        }
        val headingDeg = smoothHeading

        // Trazado de ruta activa pre-suavizado con ventana deslizante O(1) sin copias de lista
        updateSmoothedRouteTrim(activeRoute, truckX, truckZ, headingDeg, routeCache)

        when (viewMode) {
            MapViewMode.MODE_2D_TOP_DOWN -> {
                val cx = size.width / 2f
                val cy = size.height * 0.62f
                val baseScale = (0.75f * zoomScale).coerceIn(0.05f, 6.0f)

                val viewCenterX = truckX - (if (!isFollowingTruck) panOffset.x / baseScale else 0f)
                val viewCenterZ = truckZ - (if (!isFollowingTruck) panOffset.y / baseScale else 0f)
                val viewRadius = (max(size.width, size.height) / baseScale) * 1.5f

                // Comprobación de caché de carreteras 2D: umbral escalado por zoomScale para fluidez en vista alejada
                val distFromLast2D = if (cache2D.lastX.isNaN()) Float.MAX_VALUE else {
                    val qx = viewCenterX - cache2D.lastX
                    val qz = viewCenterZ - cache2D.lastZ
                    qx * qx + qz * qz
                }
                val scaleRatio = if (cache2D.lastScale > 0f) abs(baseScale - cache2D.lastScale) / cache2D.lastScale else 1f
                val worldThreshold = (25f / zoomScale.coerceAtLeast(0.10f)).coerceIn(25f, 350f)
                val worldThresholdSq = worldThreshold * worldThreshold
                val isLowZoom = zoomScale < 0.25f

                if (cache2D.cachedGraph !== graph || cache2D.cachedRoute !== activeRoute ||
                    distFromLast2D > worldThresholdSq || scaleRatio > 0.08f ||
                    cache2D.lastWasLowZoom != isLowZoom || !cache2D.hasCache
                ) {
                    val visible = graph.getEdgesNear(viewCenterX, viewCenterZ, viewRadius * 1.25f)
                    cache2D.cachedGraph = graph
                    cache2D.cachedRoute = activeRoute
                    cache2D.hasCache = true
                    cache2D.edges = visible
                    cache2D.lastX = viewCenterX
                    cache2D.lastZ = viewCenterZ
                    cache2D.lastScale = baseScale
                    cache2D.lastWasLowZoom = isLowZoom

                    val hwyEdges = ArrayList<MapEdge>()
                    val mainEdges = ArrayList<MapEdge>()
                    val localEdges = ArrayList<MapEdge>()
                    val rampEdges = ArrayList<MapEdge>()
                    val hiddenEdges = ArrayList<MapEdge>()

                    val activeEdgeIds = activeRoute?.edgePath?.map { it.edgeId }?.toSet() ?: emptySet()
                    for (edge in visible) {
                        if (edge.polyline.size < 2) continue
                        val isRouteEdge = activeEdgeIds.contains(edge.edgeId)
                        if (isLowZoom) {
                            if (edge.roadType.toInt() == 0 && !edge.isHidden) {
                                hwyEdges.add(edge)
                            }
                            continue
                        }
                        if (!isRouteEdge && edge.roadType.toInt() == 3 && edge.length < 18f) continue
                        if (!isRouteEdge && edge.roadType.toInt() == 5 && edge.length < 16f) continue

                        if (edge.isHidden) {
                            hiddenEdges.add(edge)
                        } else {
                            when (edge.roadType.toInt()) {
                                0 -> hwyEdges.add(edge)
                                1 -> mainEdges.add(edge)
                                2 -> localEdges.add(edge)
                                else -> rampEdges.add(edge)
                            }
                        }
                    }

                    // Se suavizan y encadenan UNA SOLA VEZ al mover de sector, nunca en cada frame
                    cache2D.rampChains = if (isLowZoom) emptyList() else chainEdges(rampEdges).map { smoothPolyline(it, iterations = 1) }
                    cache2D.localChains = if (isLowZoom) emptyList() else chainEdges(localEdges).map { smoothPolyline(it, iterations = 1) }
                    cache2D.mainChains = if (isLowZoom) emptyList() else chainEdges(mainEdges).map { smoothPolyline(it, iterations = 1) }
                    cache2D.hwyChains = chainEdges(hwyEdges).map { smoothPolyline(it, iterations = 1) }
                    cache2D.hiddenChains = if (isLowZoom) emptyList() else chainEdges(hiddenEdges).map { smoothPolyline(it, iterations = 1) }
                }

                drawMap2D(
                    graph = graph,
                    cache2D = cache2D,
                    reusablePaths = reusablePaths,
                    scratchCache = scratchCache,
                    truckX = truckX,
                    truckZ = truckZ,
                    viewCenterX = viewCenterX,
                    viewCenterZ = viewCenterZ,
                    viewRadius = viewRadius,
                    cx = cx,
                    cy = cy,
                    baseScale = baseScale,
                    headingDeg = headingDeg,
                    routeCache = routeCache,
                    zoomScale = zoomScale,
                    isFollowingTruck = isFollowingTruck,
                    cursorPulseAlpha = cursorPulseAlpha,
                    mapTheme = mapTheme,
                    cityPaint = cityPaint,
                    poiIconPaint = poiIconPaint,
                    poiLabelPaint = poiLabelPaint,
                    poiBgPaint = poiBgPaint
                )
            }
            MapViewMode.MODE_3D_PERSPECTIVE -> {
                val distFromLast3D = if (cache3D.lastX.isNaN()) Float.MAX_VALUE else {
                    val qx = truckX - cache3D.lastX
                    val qz = truckZ - cache3D.lastZ
                    qx * qx + qz * qz
                }
                if (cache3D.cachedGraph !== graph || cache3D.cachedRoute !== activeRoute ||
                    distFromLast3D > 225f || !cache3D.hasCache
                ) {
                    val nearby = graph.getEdgesNear(truckX, truckZ, 1400f)
                    cache3D.cachedGraph = graph
                    cache3D.cachedRoute = activeRoute
                    cache3D.hasCache = true
                    cache3D.edges = nearby
                    cache3D.lastX = truckX
                    cache3D.lastZ = truckZ

                    val segs = ArrayList<RoadSeg3D>(nearby.size)
                    val activeEdgeIds = activeRoute?.edgePath?.map { it.edgeId }?.toSet() ?: emptySet()
                    for (edge in nearby) {
                        if (edge.polyline.size < 2) continue
                        val isRouteEdge = activeEdgeIds.contains(edge.edgeId)
                        if (!isRouteEdge && edge.roadType.toInt() == 3 && edge.length < 18f) continue
                        if (!isRouteEdge && edge.roadType.toInt() == 5 && edge.length < 16f) continue

                        val isFreeway = edge.roadType.toInt() == 0
                        val isHidden = edge.isHidden
                        val lanes = edge.lanes.toInt().coerceAtLeast(1)
                        val laneWidthFactor = 1.0f + (lanes - 1) * 0.38f
                        segs.add(RoadSeg3D(isFreeway, lanes, laneWidthFactor, edge.polyline, isHidden))
                    }
                    cache3D.roadSegs = segs
                }

                // Controlador de zoom dinámico en función de la velocidad
                val speedKmh = (if (telemetry.speedKmh.isFinite()) telemetry.speedKmh else 0f).coerceAtLeast(0f)
                val speedZoomRatio = (speedKmh / 90f).coerceIn(0f, 1f)
                val dynamicZoom = zoomScale * (0.85f + speedZoomRatio * 0.35f)

                drawMap3D(
                    graph = graph,
                    cache3D = cache3D,
                    roadPool = roadSegPool,
                    routePool = routeSegPool,
                    reusablePaths = reusablePaths,
                    scratchCache = scratchCache,
                    truckX = truckX,
                    truckZ = truckZ,
                    headingDeg = headingDeg,
                    routeCache = routeCache,
                    zoomScale = dynamicZoom,
                    cursorPulseAlpha = cursorPulseAlpha,
                    mapTheme = mapTheme,
                    poiIconPaint = poiIconPaint,
                    poiLabelPaint = poiLabelPaint,
                    poiBgPaint = poiBgPaint
                )
            }
        }
    }
}

// =====================================================================
// SUAVIZADO DE CURVAS Y ENCADENADO DE VÍAS
// =====================================================================
private fun smoothPolyline(points: List<Point3D>, iterations: Int = 1): List<Point3D> {
    if (points.size < 3 || iterations <= 0 || points.size > 60000) return points
    val effectiveIter = if (points.size > 12000) 1 else iterations
    var current = points
    repeat(effectiveIter) {
        if (current.size < 3 || current.size > 60000) return current
        val next = ArrayList<Point3D>(current.size * 2)
        next.add(current.first())
        for (i in 0 until current.size - 1) {
            val p0 = current[i]
            val p1 = current[i + 1]
            next.add(
                Point3D(
                    0.75f * p0.x + 0.25f * p1.x,
                    0.75f * p0.y + 0.25f * p1.y,
                    0.75f * p0.z + 0.25f * p1.z
                )
            )
            next.add(
                Point3D(
                    0.25f * p0.x + 0.75f * p1.x,
                    0.25f * p0.y + 0.75f * p1.y,
                    0.25f * p0.z + 0.75f * p1.z
                )
            )
        }
        next.add(current.last())
        current = next
    }
    return current
}

private fun subdivideLongSegments(points: List<Point3D>, maxSegLenMeters: Float): List<Point3D> {
    if (points.size < 2 || maxSegLenMeters <= 0.1f) return points
    val out = ArrayList<Point3D>(points.size)
    out.add(points[0])
    for (i in 0 until points.size - 1) {
        val p0 = points[i]
        val p1 = points[i + 1]
        val dx = p1.x - p0.x
        val dy = p1.y - p0.y
        val dz = p1.z - p0.z
        val len = sqrt(dx * dx + dz * dz)
        if (len > maxSegLenMeters) {
            val steps = ceil(len / maxSegLenMeters).toInt().coerceAtMost(64)
            for (s in 1 until steps) {
                val t = s.toFloat() / steps.toFloat()
                out.add(Point3D(p0.x + dx * t, p0.y + dy * t, p0.z + dz * t))
            }
        }
        out.add(p1)
    }
    return out
}

private fun updateSmoothedRouteTrim(
    activeRoute: RouteResult?,
    truckX: Float,
    truckZ: Float,
    headingDeg: Float,
    routeCache: RouteRenderCache
) {
    if (activeRoute == null || activeRoute.edgePath.isEmpty()) {
        routeCache.cachedRoute = null
        routeCache.fullSmoothedPoints = emptyList()
        routeCache.cumDistances = FloatArray(0)
        routeCache.lastTrimIdx = 0
        routeCache.trimProjPoint = null
        return
    }

    // Si la ruta cambió, construir el trazado suavizado completo de la ruta UNA SOLA VEZ
    if (routeCache.cachedRoute !== activeRoute) {
        val raw = ArrayList<Point3D>(activeRoute.edgePath.size * 4)
        for (edge in activeRoute.edgePath) {
            for (pt in edge.polyline) {
                if (raw.isNotEmpty()) {
                    val prev = raw.last()
                    val dx = pt.x - prev.x
                    val dz = pt.z - prev.z
                    if (dx * dx + dz * dz < 0.04f) continue
                }
                raw.add(pt)
            }
        }
        val sub = subdivideLongSegments(raw, 6f)
        val smoothed = smoothPolyline(sub, iterations = 2)
        val cum = FloatArray(smoothed.size)
        var accDist = 0f
        for (i in 1 until smoothed.size) {
            val a = smoothed[i - 1]
            val b = smoothed[i]
            val dx = b.x - a.x
            val dz = b.z - a.z
            accDist += sqrt(dx * dx + dz * dz)
            cum[i] = accDist
        }
        routeCache.fullSmoothedPoints = smoothed
        routeCache.cumDistances = cum
        routeCache.cachedRoute = activeRoute
        routeCache.lastTruckX = Float.NaN
        routeCache.lastTruckZ = Float.NaN
        routeCache.lastTrimIdx = 0
        routeCache.trimProjPoint = smoothed.firstOrNull()
    }

    val fullPoints = routeCache.fullSmoothedPoints
    if (fullPoints.size < 2) return

    // Solo recortar si el camión se movió más de 0.8 metros (sin copiar la lista)
    val dx = truckX - routeCache.lastTruckX
    val dz = truckZ - routeCache.lastTruckZ
    if (!routeCache.lastTruckX.isNaN() && (dx * dx + dz * dz) < 0.64f) {
        return
    }

    val hasHeading = headingDeg.isFinite()
    val rad = if (hasHeading) Math.toRadians(headingDeg.toDouble()).toFloat() else 0f
    val hx = if (hasHeading) sin(rad) else 0f
    val hz = if (hasHeading) -cos(rad) else 0f

    // Búsqueda en ventana cercana a la posición anterior para O(1) con preferencia direccional
    var bestIdx = -1
    var bestEffectiveSq = Float.MAX_VALUE
    var bestRawDistSq = Float.MAX_VALUE
    var bestProjX = truckX
    var bestProjY = 0f
    var bestProjZ = truckZ
    val startWindow = (routeCache.lastTrimIdx - 4).coerceAtLeast(0)
    val endWindow = (routeCache.lastTrimIdx + 30).coerceAtMost(fullPoints.size - 2)

    for (i in startWindow..endWindow) {
        val p1 = fullPoints[i]
        val p2 = fullPoints[i + 1]
        val vx = p2.x - p1.x
        val vz = p2.z - p1.z
        val lenSq = vx * vx + vz * vz
        val t = if (lenSq > 0.0001f) {
            (((truckX - p1.x) * vx + (truckZ - p1.z) * vz) / lenSq).coerceIn(0f, 1f)
        } else 0f
        val px = p1.x + t * vx
        val pz = p1.z + t * vz
        val dSq = (truckX - px) * (truckX - px) + (truckZ - pz) * (truckZ - pz)
        var effectiveSq = dSq
        if (hasHeading && lenSq > 0.0001f) {
            val dot = (vx * hx + vz * hz) / sqrt(lenSq)
            if (dot < -0.35f) effectiveSq *= 5f
        }
        if (effectiveSq < bestEffectiveSq) {
            bestEffectiveSq = effectiveSq
            bestRawDistSq = dSq
            bestIdx = i
            bestProjX = px
            bestProjY = p1.y + t * (p2.y - p1.y)
            bestProjZ = pz
        }
    }

    // Si no está en la ventana cercana (< 45m), buscar en toda la lista exigiendo alineacion y progreso monotono
    if (bestRawDistSq > 2025f) {
        bestEffectiveSq = Float.MAX_VALUE
        for (i in 0 until fullPoints.size - 1) {
            val p1 = fullPoints[i]
            val p2 = fullPoints[i + 1]
            val vx = p2.x - p1.x
            val vz = p2.z - p1.z
            val lenSq = vx * vx + vz * vz
            val t = if (lenSq > 0.0001f) {
                (((truckX - p1.x) * vx + (truckZ - p1.z) * vz) / lenSq).coerceIn(0f, 1f)
            } else 0f
            val px = p1.x + t * vx
            val pz = p1.z + t * vz
            val dSq = (truckX - px) * (truckX - px) + (truckZ - pz) * (truckZ - pz)
            var effectiveSq = dSq
            if (hasHeading && lenSq > 0.0001f) {
                val dot = (vx * hx + vz * hz) / sqrt(lenSq)
                if (dot < -0.35f) effectiveSq *= 5f
            }
            if (i < routeCache.lastTrimIdx) {
                effectiveSq += 100f // Penalizar retroceso en lazos al buscar globalmente
            }
            if (effectiveSq < bestEffectiveSq) {
                bestEffectiveSq = effectiveSq
                bestIdx = i
                bestProjX = px
                bestProjY = p1.y + t * (p2.y - p1.y)
                bestProjZ = pz
            }
        }
    }

    val trimStart = if (bestIdx >= 0) bestIdx.coerceAtMost(fullPoints.size - 2) else 0
    routeCache.lastTrimIdx = trimStart
    routeCache.lastTruckX = truckX
    routeCache.lastTruckZ = truckZ
    routeCache.trimProjPoint = if (bestIdx >= 0) Point3D(bestProjX, bestProjY, bestProjZ) else fullPoints[0]
}

private fun chainEdges(edges: List<MapEdge>): List<List<Point3D>> {
    if (edges.isEmpty()) return emptyList()
    val n = edges.size
    val visited = BooleanArray(n)
    val fromMap = HashMap<Int, MutableList<Int>>(n)
    val inDegree = HashMap<Int, Int>(n)
    val startBuckets = HashMap<Long, MutableList<Int>>(n)

    fun bucketKey(x: Float, z: Float): Long {
        val bx = floor(x / 5f).toInt()
        val bz = floor(z / 5f).toInt()
        return (bx.toLong() shl 32) or (bz.toLong() and 0xFFFFFFFFL)
    }

    for (i in 0 until n) {
        val e = edges[i]
        if (e.polyline.size < 2) {
            visited[i] = true
            continue
        }
        fromMap.getOrPut(e.fromNodeIndex) { ArrayList(2) }.add(i)
        inDegree[e.toNodeIndex] = (inDegree[e.toNodeIndex] ?: 0) + 1
        val p0 = e.polyline.first()
        startBuckets.getOrPut(bucketKey(p0.x, p0.z)) { ArrayList(2) }.add(i)
    }

    val chains = ArrayList<List<Point3D>>()

    fun traceChain(startIdx: Int) {
        val chain = ArrayList<Point3D>(16)
        var currIdx = startIdx
        while (currIdx >= 0 && !visited[currIdx]) {
            visited[currIdx] = true
            val currEdge = edges[currIdx]
            for (pt in currEdge.polyline) {
                if (chain.isNotEmpty()) {
                    val prev = chain.last()
                    val dx = pt.x - prev.x
                    val dz = pt.z - prev.z
                    if (dx * dx + dz * dz < 0.04f) continue
                }
                chain.add(pt)
            }
            if (chain.size > 1500) break

            var nextIdx = -1
            val ceEnd = currEdge.polyline.last()
            val cePrev = currEdge.polyline[currEdge.polyline.size - 2]
            val ceDx = ceEnd.x - cePrev.x
            val ceDz = ceEnd.z - cePrev.z
            val ceLen = sqrt(ceDx * ceDx + ceDz * ceDz)

            fun isValidContinuation(cand: Int): Boolean {
                if (visited[cand]) return false
                val candEdge = edges[cand]
                if (candEdge.toNodeIndex == currEdge.fromNodeIndex) return false
                if (ceLen > 0.1f && candEdge.polyline.size >= 2) {
                    val n0 = candEdge.polyline[0]
                    val n1 = candEdge.polyline[1]
                    val ndx = n1.x - n0.x
                    val ndz = n1.z - n0.z
                    val nlen = sqrt(ndx * ndx + ndz * ndz)
                    if (nlen > 0.1f && (ceDx * ndx + ceDz * ndz) / (ceLen * nlen) < -0.35f) {
                        return false
                    }
                }
                return true
            }

            val candidates = fromMap[currEdge.toNodeIndex]
            if (candidates != null) {
                for (cand in candidates) {
                    if (isValidContinuation(cand)) {
                        nextIdx = cand
                        break
                    }
                }
            }

            if (nextIdx == -1 && chain.isNotEmpty()) {
                val endPt = chain.last()
                val baseBx = floor(endPt.x / 5f).toInt()
                val baseBz = floor(endPt.z / 5f).toInt()
                var bestDistSq = 6.25f // 2.5m^2
                for (bx in (baseBx - 1)..(baseBx + 1)) {
                    for (bz in (baseBz - 1)..(baseBz + 1)) {
                        val key = (bx.toLong() shl 32) or (bz.toLong() and 0xFFFFFFFFL)
                        val bucket = startBuckets[key] ?: continue
                        for (cand in bucket) {
                            if (!isValidContinuation(cand)) continue
                            val startPt = edges[cand].polyline.first()
                            val dx = startPt.x - endPt.x
                            val dz = startPt.z - endPt.z
                            val dSq = dx * dx + dz * dz
                            if (dSq <= bestDistSq) {
                                bestDistSq = dSq
                                nextIdx = cand
                            }
                        }
                    }
                }
            }
            currIdx = nextIdx
        }
        if (chain.size >= 2) {
            chains.add(chain)
        }
    }

    // Primera pasada: iniciar desde cabeceras de cadena (inDegree == 0)
    for (i in 0 until n) {
        if (!visited[i] && (inDegree[edges[i].fromNodeIndex] ?: 0) == 0) {
            traceChain(i)
        }
    }
    // Segunda pasada: ciclos o bifurcaciones restantes
    for (i in 0 until n) {
        if (!visited[i]) {
            traceChain(i)
        }
    }

    return chains
}

private fun populateChainsToPath(
    chains: List<List<Point3D>>,
    path: Path,
    viewCenterX: Float,
    viewCenterZ: Float,
    cx: Float,
    cy: Float,
    scale: Float
): Boolean {
    path.reset()
    if (chains.isEmpty()) return false
    var hasPoints = false
    var lastWx = Float.NaN
    var lastWz = Float.NaN

    for (chain in chains) {
        if (chain.size < 2) continue
        for (i in chain.indices) {
            val p = chain[i]
            val sx = cx + (p.x - viewCenterX) * scale
            val sy = cy + (p.z - viewCenterZ) * scale
            if (sx.isNaN() || sy.isNaN()) continue

            if (i == 0) {
                val isContiguous = !lastWx.isNaN() &&
                    ((p.x - lastWx) * (p.x - lastWx) + (p.z - lastWz) * (p.z - lastWz) < 1.0f)
                if (isContiguous) {
                    path.lineTo(sx, sy)
                } else {
                    path.moveTo(sx, sy)
                }
            } else {
                path.lineTo(sx, sy)
                hasPoints = true
            }
            lastWx = p.x
            lastWz = p.z
        }
    }
    return hasPoints
}

// =====================================================================
// RENDERIZADOR 2D (VISTA AÉREA CON CINTAS SUAVES Y REUTILIZACIÓN DE PATH)
// =====================================================================
private fun DrawScope.drawMap2D(
    graph: MapGraph,
    cache2D: Viewport2DCache,
    reusablePaths: ReusableCanvasPaths,
    scratchCache: RenderScratchCache,
    truckX: Float,
    truckZ: Float,
    viewCenterX: Float,
    viewCenterZ: Float,
    viewRadius: Float,
    cx: Float,
    cy: Float,
    baseScale: Float,
    headingDeg: Float,
    routeCache: RouteRenderCache,
    zoomScale: Float,
    isFollowingTruck: Boolean,
    cursorPulseAlpha: Float,
    mapTheme: MapThemeStyle,
    cityPaint: android.graphics.Paint,
    poiIconPaint: android.graphics.Paint,
    poiLabelPaint: android.graphics.Paint,
    poiBgPaint: android.graphics.Paint
) {
    val hasRamp = populateChainsToPath(cache2D.rampChains, reusablePaths.rampPath, viewCenterX, viewCenterZ, cx, cy, baseScale)
    val hasLocal = populateChainsToPath(cache2D.localChains, reusablePaths.localPath, viewCenterX, viewCenterZ, cx, cy, baseScale)
    val hasMain = populateChainsToPath(cache2D.mainChains, reusablePaths.mainPath, viewCenterX, viewCenterZ, cx, cy, baseScale)
    val hasHwy = populateChainsToPath(cache2D.hwyChains, reusablePaths.hwyPath, viewCenterX, viewCenterZ, cx, cy, baseScale)
    val hasHidden = populateChainsToPath(cache2D.hiddenChains, reusablePaths.hiddenPath, viewCenterX, viewCenterZ, cx, cy, baseScale)

    val rampW = getRoadStrokeWidth(3, zoomScale)
    val localW = getRoadStrokeWidth(2, zoomScale)
    val mainW = getRoadStrokeWidth(1, zoomScale)
    val hwyW = getRoadStrokeWidth(0, zoomScale)
    val hiddenW = (localW * 0.95f).coerceAtLeast(4.0f)

    // GLOBAL PASS 1: Todos los bordes exteriores
    val borderColor = when (mapTheme) {
        MapThemeStyle.DARK_PREMIUM -> Color(0xFF0B1017)
        MapThemeStyle.HIGH_CONTRAST -> Color(0xFF000000)
        MapThemeStyle.CYBER_NEON -> Color(0xFF06121E)
    }
    if (hasRamp) drawPath(reusablePaths.rampPath, borderColor, style = Stroke(rampW + 4f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    if (hasLocal) drawPath(reusablePaths.localPath, borderColor, style = Stroke(localW + 4f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    if (hasMain) drawPath(reusablePaths.mainPath, borderColor, style = Stroke(mainW + 4f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    if (hasHwy) drawPath(reusablePaths.hwyPath, borderColor, style = Stroke(hwyW + 4f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    if (hasHidden) drawPath(reusablePaths.hiddenPath, Color(0xFF0C0A09), style = Stroke(hiddenW + 4f, cap = StrokeCap.Round, join = StrokeJoin.Round))

    // GLOBAL PASS 2: Todos los rellenos de asfalto unificados encima
    val streetFill = when (mapTheme) {
        MapThemeStyle.DARK_PREMIUM -> Color(0xFF2B3846)
        MapThemeStyle.HIGH_CONTRAST -> Color(0xFF475569)
        MapThemeStyle.CYBER_NEON -> Color(0xFF1E293B)
    }
    val hwyFill = when (mapTheme) {
        MapThemeStyle.DARK_PREMIUM -> Color(0xFF334354)
        MapThemeStyle.HIGH_CONTRAST -> Color(0xFF64748B)
        MapThemeStyle.CYBER_NEON -> Color(0xFF1D4ED8).copy(alpha = 0.65f)
    }
    val hiddenBaseFill = Color(0xFF292524)
    if (hasRamp) drawPath(reusablePaths.rampPath, streetFill, style = Stroke(rampW, cap = StrokeCap.Round, join = StrokeJoin.Round))
    if (hasLocal) drawPath(reusablePaths.localPath, streetFill, style = Stroke(localW, cap = StrokeCap.Round, join = StrokeJoin.Round))
    if (hasMain) drawPath(reusablePaths.mainPath, streetFill, style = Stroke(mainW, cap = StrokeCap.Round, join = StrokeJoin.Round))
    if (hasHwy) drawPath(reusablePaths.hwyPath, hwyFill, style = Stroke(hwyW, cap = StrokeCap.Round, join = StrokeJoin.Round))
    if (hasHidden) drawPath(reusablePaths.hiddenPath, hiddenBaseFill, style = Stroke(hiddenW, cap = StrokeCap.Round, join = StrokeJoin.Round))

    // GLOBAL PASS 3: Brillo y trazo discontinuo neón para caminos ocultos (Secret Roads)
    if (hasHidden) {
        val clampedDashZoom = zoomScale.coerceIn(0.6f, 2.0f)
        if (scratchCache.dashEffect == null || abs(scratchCache.lastDashZoom - clampedDashZoom) > 0.01f) {
            scratchCache.lastDashZoom = clampedDashZoom
            scratchCache.dashEffect = PathEffect.dashPathEffect(
                floatArrayOf(14f * clampedDashZoom, 8f * clampedDashZoom),
                0f
            )
        }
        drawPath(
            reusablePaths.hiddenPath,
            Color(0x40F59E0B),
            style = Stroke(hiddenW + 4f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
        drawPath(
            reusablePaths.hiddenPath,
            Color(0xFFF59E0B),
            style = Stroke(
                width = (hiddenW * 0.75f).coerceAtLeast(3.2f),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
                pathEffect = scratchCache.dashEffect
            )
        )
    }

    // 2. Ruta activa GPS (Cinta continua desde trimIdx + trimProjPoint sin copiar lista)
    val fullRoutePts = routeCache.fullSmoothedPoints
    val trimIdx = routeCache.lastTrimIdx
    val trimProj = routeCache.trimProjPoint
    if (fullRoutePts.size >= 2 && trimIdx < fullRoutePts.size - 1) {
        val rPath = reusablePaths.routePath
        rPath.reset()
        var hasRoute = false
        val firstPt = trimProj ?: fullRoutePts[trimIdx]
        val firstSx = cx + (firstPt.x - viewCenterX) * baseScale
        val firstSy = cy + (firstPt.z - viewCenterZ) * baseScale
        var started = false
        if (firstSx.isFinite() && firstSy.isFinite()) {
            rPath.moveTo(firstSx, firstSy)
            started = true
        }
        val stride = if (zoomScale < 0.35f) 3 else 1
        var i = trimIdx + 1
        while (i < fullRoutePts.size) {
            val pt = fullRoutePts[i]
            val sx = cx + (pt.x - viewCenterX) * baseScale
            val sy = cy + (pt.z - viewCenterZ) * baseScale
            if (sx.isFinite() && sy.isFinite()) {
                if (!started) {
                    rPath.moveTo(sx, sy)
                    started = true
                } else {
                    rPath.lineTo(sx, sy)
                    hasRoute = true
                }
            }
            if (i == fullRoutePts.size - 1) break
            i = (i + stride).coerceAtMost(fullRoutePts.size - 1)
        }

        if (hasRoute) {
            val rw = 16f * zoomScale.coerceIn(0.4f, 2.2f)
            drawPath(
                path = rPath,
                color = Color(0x6600E5FF),
                style = Stroke(width = rw * 1.55f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
            drawPath(
                path = rPath,
                color = Color(0xFF0369A1),
                style = Stroke(width = rw + 2.5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
            drawPath(
                path = rPath,
                color = Color(0xFF00E5FF),
                style = Stroke(width = rw, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }
    }

    // 3. Ciudades y POIs dibujados erguidos
    for (city in graph.cities) {
        val sx = cx + (city.x - viewCenterX) * baseScale
        val sy = cy + (city.z - viewCenterZ) * baseScale
        if (sx !in -120f..(size.width + 120f) || sy !in -120f..(size.height + 120f)) continue

        drawCircle(Color(0xFF38BDF8), radius = 6f * zoomScale.coerceIn(0.6f, 1.5f), center = Offset(sx, sy))

        drawIntoCanvas { canvas ->
            cityPaint.textSize = (13f * zoomScale.coerceIn(0.8f, 1.4f)).coerceAtLeast(10f) * density
            canvas.nativeCanvas.drawText(city.name, sx + 9f, sy + 4f, cityPaint)
        }
    }

    // 3.2 POIs (Gasolineras, Zonas de Descanso, Talleres, Básculas)
    if (zoomScale >= 0.25f) {
        val poiDrawRadiusSq = (viewRadius * 1.15f) * (viewRadius * 1.15f)
        for (poi in graph.pois) {
            val realDx = poi.x - viewCenterX
            val realDz = poi.z - viewCenterZ
            if (realDx * realDx + realDz * realDz > poiDrawRadiusSq) continue

            val sx = cx + realDx * baseScale
            val sy = cy + realDz * baseScale
            if (sx !in -80f..(size.width + 80f) || sy !in -80f..(size.height + 80f)) continue

            drawPoiMarker2D(poi, sx, sy, zoomScale, poiIconPaint, poiLabelPaint, poiBgPaint)
        }
    }

    // 4. Cursor táctico del camión (siempre dibujado AL FINAL)
    if (isFollowingTruck) {
        drawTruckCursor(cx, cy, headingDeg, cursorPulseAlpha, reusablePaths)
    } else {
        val truckSx = cx + (truckX - viewCenterX) * baseScale
        val truckSy = cy + (truckZ - viewCenterZ) * baseScale
        if (!truckSx.isNaN() && !truckSy.isNaN()) {
            drawTruckCursor(truckSx, truckSy, headingDeg, cursorPulseAlpha, reusablePaths)
        }
    }
}

private fun getRoadStrokeWidth(roadType: Int, zoomScale: Float): Float {
    val z = zoomScale.coerceIn(0.35f, 2.2f)
    return when (roadType) {
        0 -> 15f * z
        1 -> 11f * z
        2 -> 7f * z
        5 -> 8.5f * z
        else -> 8f * z
    }
}

private fun poiMarkerStyle(poiType: Int): Triple<Color, String, String> {
    return when (poiType) {
        1 -> Triple(Color(0xFF10B981), "GAS", "Gasolinera")
        2 -> Triple(Color(0xFF6366F1), "RST", "Descanso")
        3 -> Triple(Color(0xFFF59E0B), "SRV", "Taller")
        4 -> Triple(Color(0xFF8B5CF6), "DLR", "Concesionario")
        5 -> Triple(Color(0xFF0EA5E9), "LOG", "Empresa")
        6 -> Triple(Color(0xFF64748B), "WGH", "Báscula")
        else -> Triple(Color(0xFF475569), "POI", "Punto")
    }
}

private fun DrawScope.drawPoiMarker2D(
    poi: MapPoi,
    sx: Float,
    sy: Float,
    zoomScale: Float,
    iconPaint: android.graphics.Paint,
    labelPaint: android.graphics.Paint,
    bgPaint: android.graphics.Paint
) {
    val (bgColor, iconText, defaultLabel) = poiMarkerStyle(poi.poiType.toInt())
    val radius = 13f * density

    drawCircle(Color(0x88000000), radius = radius + 3f, center = Offset(sx, sy + 2f))
    drawCircle(bgColor, radius = radius, center = Offset(sx, sy))
    drawCircle(Color.White, radius = radius, center = Offset(sx, sy), style = Stroke(width = 2.2f))

    val labelText = if (poi.name.isNotEmpty()) poi.name else defaultLabel

    drawIntoCanvas { canvas ->
        iconPaint.color = android.graphics.Color.WHITE
        iconPaint.textSize = radius * 0.72f
        canvas.nativeCanvas.drawText(iconText, sx, sy + radius * 0.25f, iconPaint)

        if (zoomScale >= 0.45f) {
            labelPaint.textSize = 9.5f * density
            val textWidth = labelPaint.measureText(labelText)
            val pillPad = 4f * density
            val pTop = sy + radius + 3f
            val pBottom = pTop + 14f * density
            val pLeft = sx - textWidth / 2f - pillPad
            val pRight = sx + textWidth / 2f + pillPad

            canvas.nativeCanvas.drawRoundRect(pLeft, pTop, pRight, pBottom, 6f, 6f, bgPaint)
            canvas.nativeCanvas.drawText(labelText, sx, pBottom - 3.5f * density, labelPaint)
        }
    }
}

// =====================================================================
// RENDERIZADOR 3D EN PERSPECTIVA (SIN GC, CON POOL REUTILIZABLE)
// =====================================================================
private fun DrawScope.drawMap3D(
    graph: MapGraph,
    cache3D: Viewport3DCache,
    roadPool: RoadSegPool3D,
    routePool: RouteSegPool3D,
    reusablePaths: ReusableCanvasPaths,
    scratchCache: RenderScratchCache,
    truckX: Float,
    truckZ: Float,
    headingDeg: Float,
    routeCache: RouteRenderCache,
    zoomScale: Float,
    cursorPulseAlpha: Float,
    mapTheme: MapThemeStyle,
    poiIconPaint: android.graphics.Paint,
    poiLabelPaint: android.graphics.Paint,
    poiBgPaint: android.graphics.Paint
) {
    val w = size.width
    val h = size.height

    val horizonY = h * 0.26f
    val targetTruckY = h * 0.78f
    val camDist3D = 65f
    val focal3D = 440f * zoomScale.coerceIn(0.6f, 2.0f)
    val camHeight3D = (targetTruckY - horizonY) / (focal3D / camDist3D)

    // 1. Cielo, resplandor y terreno cacheados por (mapTheme, h)
    if (scratchCache.theme != mapTheme || scratchCache.height3D != h ||
        scratchCache.skyBrush == null || scratchCache.horizonGlowBrush == null || scratchCache.groundBrush == null
    ) {
        val skyColors = when (mapTheme) {
            MapThemeStyle.DARK_PREMIUM -> listOf(
                Color(0xFF030508),
                Color(0xFF080F1D),
                Color(0xFF112238),
                Color(0xFF1A3350)
            )
            MapThemeStyle.HIGH_CONTRAST -> listOf(
                Color(0xFF000000),
                Color(0xFF05070B),
                Color(0xFF0F172A),
                Color(0xFF1E293B)
            )
            MapThemeStyle.CYBER_NEON -> listOf(
                Color(0xFF04010A),
                Color(0xFF0F0726),
                Color(0xFF1E1042),
                Color(0xFF0B2948)
            )
        }
        val horizonGlow = if (mapTheme == MapThemeStyle.CYBER_NEON) Color(0xFFC7F36B) else Color(0xFF00E5FF)
        val groundColors = when (mapTheme) {
            MapThemeStyle.DARK_PREMIUM -> listOf(
                Color(0xFF080D14),
                Color(0xFF0C1013),
                Color(0xFF10151A),
                Color(0xFF151C21)
            )
            MapThemeStyle.HIGH_CONTRAST -> listOf(
                Color(0xFF000000),
                Color(0xFF030508),
                Color(0xFF070A0E),
                Color(0xFF0B0F14)
            )
            MapThemeStyle.CYBER_NEON -> listOf(
                Color(0xFF050711),
                Color(0xFF070B18),
                Color(0xFF0A1022),
                Color(0xFF0D152C)
            )
        }
        scratchCache.theme = mapTheme
        scratchCache.height3D = h
        scratchCache.skyBrush = Brush.verticalGradient(colors = skyColors, startY = 0f, endY = horizonY)
        scratchCache.horizonGlowBrush = Brush.verticalGradient(
            colors = listOf(horizonGlow.copy(alpha = 0.35f), Color.Transparent),
            startY = horizonY - 4f,
            endY = horizonY + 28f
        )
        scratchCache.groundBrush = Brush.verticalGradient(colors = groundColors, startY = horizonY, endY = h)
    }

    drawRect(
        brush = scratchCache.skyBrush!!,
        topLeft = Offset.Zero,
        size = Size(w, horizonY)
    )
    drawRect(
        brush = scratchCache.horizonGlowBrush!!,
        topLeft = Offset(0f, horizonY - 4f),
        size = Size(w, 32f)
    )
    drawRect(
        brush = scratchCache.groundBrush!!,
        topLeft = Offset(0f, horizonY),
        size = Size(w, h - horizonY)
    )

    // Rotación del mundo según Heading
    val camHeadingRad = Math.toRadians(headingDeg.toDouble()).toFloat()
    val cosH = cos(-camHeadingRad)
    val sinH = sin(-camHeadingRad)

    fun projectPoint3D(wx: Float, wz: Float): Boolean {
        val dx = wx - truckX
        val dz = wz - truckZ
        val rx = dx * cosH - dz * sinH
        val rz = -(dx * sinH + dz * cosH)

        val depth = rz + camDist3D
        if (depth < 8f || depth > 1600f) return false

        val factor = focal3D / depth
        val sx = (w * 0.5f) + rx * factor
        val sy = horizonY + (camHeight3D * factor)
        if (sy < horizonY - 2f || sy > h + 300f) return false
        scratchCache.projX1 = sx
        scratchCache.projY1 = sy
        return true
    }

    fun projectSegment3D(x1: Float, z1: Float, x2: Float, z2: Float): Boolean {
        val dx1 = x1 - truckX
        val dz1 = z1 - truckZ
        var rx1 = dx1 * cosH - dz1 * sinH
        val rz1 = -(dx1 * sinH + dz1 * cosH)

        val dx2 = x2 - truckX
        val dz2 = z2 - truckZ
        var rx2 = dx2 * cosH - dz2 * sinH
        val rz2 = -(dx2 * sinH + dz2 * cosH)

        val minDepth = 8f
        var depth1 = rz1 + camDist3D
        var depth2 = rz2 + camDist3D

        if (depth1 < minDepth && depth2 < minDepth) return false
        if (depth1 > 1600f && depth2 > 1600f) return false

        if (depth1 < minDepth) {
            val t = (minDepth - depth1) / (depth2 - depth1)
            rx1 += (rx2 - rx1) * t
            depth1 = minDepth
        } else if (depth2 < minDepth) {
            val t = (minDepth - depth2) / (depth1 - depth2)
            rx2 += (rx1 - rx2) * t
            depth2 = minDepth
        }

        val factor1 = focal3D / depth1
        val sx1 = (w * 0.5f) + rx1 * factor1
        val sy1 = horizonY + (camHeight3D * factor1)

        val factor2 = focal3D / depth2
        val sx2 = (w * 0.5f) + rx2 * factor2
        val sy2 = horizonY + (camHeight3D * factor2)

        if ((sy1 < horizonY - 2f && sy2 < horizonY - 2f) || (sy1 > h + 300f && sy2 > h + 300f)) {
            return false
        }
        if ((sx1 < -w * 1.5f && sx2 < -w * 1.5f) || (sx1 > w * 2.5f && sx2 > w * 2.5f)) {
            return false
        }
        scratchCache.projX1 = sx1
        scratchCache.projY1 = sy1
        scratchCache.projX2 = sx2
        scratchCache.projY2 = sy2
        return true
    }

    fun projectRouteSegment3D(x1: Float, z1: Float, x2: Float, z2: Float): Boolean {
        val dx1 = x1 - truckX
        val dz1 = z1 - truckZ
        var rx1 = dx1 * cosH - dz1 * sinH
        var rz1 = -(dx1 * sinH + dz1 * cosH)

        val dx2 = x2 - truckX
        val dz2 = z2 - truckZ
        var rx2 = dx2 * cosH - dz2 * sinH
        var rz2 = -(dx2 * sinH + dz2 * cosH)

        // Recortar estrictamente contra el plano del camion (rz >= 0f) y horizonte visible (<= 1550f)
        if (rz1 < 0f && rz2 < 0f) return false
        if (rz1 > 1550f && rz2 > 1550f) return false

        if (rz1 < 0f) {
            val denom = rz2 - rz1
            if (abs(denom) < 1e-5f) return false
            val t = (0f - rz1) / denom
            rx1 += (rx2 - rx1) * t
            rz1 = 0f
        } else if (rz2 < 0f) {
            val denom = rz1 - rz2
            if (abs(denom) < 1e-5f) return false
            val t = (0f - rz2) / denom
            rx2 += (rx1 - rx2) * t
            rz2 = 0f
        }

        val depth1 = rz1 + camDist3D
        val depth2 = rz2 + camDist3D
        if (depth1 > 1600f && depth2 > 1600f) return false

        val factor1 = focal3D / depth1
        val sx1 = (w * 0.5f) + rx1 * factor1
        val sy1 = horizonY + (camHeight3D * factor1)

        val factor2 = focal3D / depth2
        val sx2 = (w * 0.5f) + rx2 * factor2
        val sy2 = horizonY + (camHeight3D * factor2)

        if ((sy1 < horizonY - 2f && sy2 < horizonY - 2f) || (sy1 > h + 300f && sy2 > h + 300f)) {
            return false
        }
        if ((sx1 < -w * 1.5f && sx2 < -w * 1.5f) || (sx1 > w * 2.5f && sx2 > w * 2.5f)) {
            return false
        }
        scratchCache.projX1 = sx1
        scratchCache.projY1 = sy1
        scratchCache.projX2 = sx2
        scratchCache.projY2 = sy2
        return true
    }

    clipRect(0f, horizonY, w, h) {
        // 3. Rejilla virtual de perspectiva en el suelo
        val gridColor = when (mapTheme) {
            MapThemeStyle.DARK_PREMIUM -> Color(0x1838BDF8)
            MapThemeStyle.HIGH_CONTRAST -> Color(0x24FFFFFF)
            MapThemeStyle.CYBER_NEON -> Color(0x28C7F36B)
        }
        drawGroundGrid3D(truckX, truckZ, gridColor, scratchCache) { x1, z1, x2, z2 ->
            projectSegment3D(x1, z1, x2, z2)
        }

        // 4. Red Vial 3D proyectada usando pool de memoria estática (CERO asignaciones heap)
        roadPool.reset()
        for (road in cache3D.roadSegs) {
            val pts = road.points
            for (i in 0 until pts.size - 1) {
                val p0 = pts[i]
                val p1 = pts[i + 1]
                if (!projectSegment3D(p0.x, p0.z, p1.x, p1.z)) continue
                val sx1 = scratchCache.projX1
                val sy1 = scratchCache.projY1
                val sx2 = scratchCache.projX2
                val sy2 = scratchCache.projY2
                val midY = (sy1 + sy2) * 0.5f
                val depthRatio = ((midY - horizonY) / (targetTruckY - horizonY)).coerceIn(0.08f, 1.4f)
                val casingW = if (road.isFreeway) {
                    (24f * depthRatio * road.laneWidthFactor).coerceIn(3.5f, 38f)
                } else {
                    (14f * depthRatio * road.laneWidthFactor).coerceIn(2.0f, 22f)
                }
                roadPool.add(sx1, sy1, sx2, sy2, casingW, depthRatio, road.isFreeway, road.lanes, road.isHidden)
            }
        }

        // Ordenamiento por profundidad in-place
        roadPool.sortBackToFront()

        // PASS 1: Bordes exteriores (respetando tema STY)
        val roadBorderColor = when (mapTheme) {
            MapThemeStyle.DARK_PREMIUM -> Color(0xFF0A0F18)
            MapThemeStyle.HIGH_CONTRAST -> Color(0xFF000000)
            MapThemeStyle.CYBER_NEON -> Color(0xFF06121E)
        }
        for (i in 0 until roadPool.count) {
            val seg = roadPool.pool[roadPool.indices[i]]
            drawLine(
                color = if (seg.isHidden) Color(0xFF0C0A09) else roadBorderColor,
                start = Offset(seg.x1, seg.y1),
                end = Offset(seg.x2, seg.y2),
                strokeWidth = seg.casingW + 3f,
                cap = StrokeCap.Round
            )
        }

        // PASS 2: Rellenos de asfalto (respetando tema STY)
        val hwyAsphalt = when (mapTheme) {
            MapThemeStyle.DARK_PREMIUM -> Color(0xFF26323F)
            MapThemeStyle.HIGH_CONTRAST -> Color(0xFF64748B)
            MapThemeStyle.CYBER_NEON -> Color(0xFF1D4ED8).copy(alpha = 0.72f)
        }
        val otherAsphalt = when (mapTheme) {
            MapThemeStyle.DARK_PREMIUM -> Color(0xFF1F2A35)
            MapThemeStyle.HIGH_CONTRAST -> Color(0xFF475569)
            MapThemeStyle.CYBER_NEON -> Color(0xFF1E293B)
        }
        val hiddenAsphalt = Color(0xFF292524)
        for (i in 0 until roadPool.count) {
            val seg = roadPool.pool[roadPool.indices[i]]
            drawLine(
                color = if (seg.isHidden) hiddenAsphalt else if (seg.isFreeway) hwyAsphalt else otherAsphalt,
                start = Offset(seg.x1, seg.y1),
                end = Offset(seg.x2, seg.y2),
                strokeWidth = seg.casingW,
                cap = StrokeCap.Round
            )
        }

        // PASS 3: Líneas divisorias de carriles (respetando tema STY)
        val dividerColor = when (mapTheme) {
            MapThemeStyle.DARK_PREMIUM -> Color(0x5594A3B8)
            MapThemeStyle.HIGH_CONTRAST -> Color(0xAAFFFFFF)
            MapThemeStyle.CYBER_NEON -> Color(0x6638BDF8)
        }
        for (i in 0 until roadPool.count) {
            val seg = roadPool.pool[roadPool.indices[i]]
            if (!seg.isHidden && seg.lanes >= 2 && seg.depthRatio > 0.35f) {
                val dividerW = (1.5f * seg.depthRatio).coerceIn(0.8f, 2.5f)
                drawLine(
                    color = dividerColor,
                    start = Offset(seg.x1, seg.y1),
                    end = Offset(seg.x2, seg.y2),
                    strokeWidth = dividerW,
                    cap = StrokeCap.Round
                )
            }
        }

        // PASS 4: Resaltado neón de caminos ocultos en 3D
        val hiddenNeonAmber = Color(0xFFF59E0B)
        for (i in 0 until roadPool.count) {
            val seg = roadPool.pool[roadPool.indices[i]]
            if (seg.isHidden) {
                drawLine(
                    color = hiddenNeonAmber,
                    start = Offset(seg.x1, seg.y1),
                    end = Offset(seg.x2, seg.y2),
                    strokeWidth = (seg.casingW * 0.45f).coerceIn(2.5f, 6.0f),
                    cap = StrokeCap.Round
                )
            }
        }

        // 5. Ruta de Navegación Activa GPS en 3D (estrictamente hacia adelante rz >= 0f, desde trimIdx hasta horizonte)
        routePool.reset()
        val fullRoutePts = routeCache.fullSmoothedPoints
        val trimIdx = routeCache.lastTrimIdx
        val cumDists = routeCache.cumDistances
        if (fullRoutePts.size >= 2 && trimIdx < fullRoutePts.size - 1) {
            val baseArc = if (trimIdx < cumDists.size) cumDists[trimIdx] else 0f
            var prevPt = routeCache.trimProjPoint ?: fullRoutePts[trimIdx]
            for (i in (trimIdx + 1) until fullRoutePts.size) {
                val nextPt = fullRoutePts[i]
                if (projectRouteSegment3D(prevPt.x, prevPt.z, nextPt.x, nextPt.z)) {
                    val sx1 = scratchCache.projX1
                    val sy1 = scratchCache.projY1
                    val sx2 = scratchCache.projX2
                    val sy2 = scratchCache.projY2
                    val midY = (sy1 + sy2) * 0.5f
                    val depthRatio = ((midY - horizonY) / (targetTruckY - horizonY)).coerceIn(0.10f, 1.35f)
                    val rWidth = (28f * depthRatio).coerceIn(4.5f, 36f)
                    routePool.add(sx1, sy1, sx2, sy2, rWidth)
                }
                prevPt = nextPt
                if (i < cumDists.size && (cumDists[i] - baseArc) > 2600f) break
            }

            // Capa 1: Halo difuso
            val haloColor = Color(0xFF00E5FF).copy(alpha = 0.28f)
            for (i in 0 until routePool.count) {
                val seg = routePool.pool[i]
                drawLine(haloColor, Offset(seg.x1, seg.y1), Offset(seg.x2, seg.y2), strokeWidth = seg.rWidth * 1.55f, cap = StrokeCap.Round)
            }
            // Capa 2: Contorno
            val routeCasingColor = Color(0xFF0284C7)
            for (i in 0 until routePool.count) {
                val seg = routePool.pool[i]
                drawLine(routeCasingColor, Offset(seg.x1, seg.y1), Offset(seg.x2, seg.y2), strokeWidth = seg.rWidth + 2.5f, cap = StrokeCap.Round)
            }
            // Capa 3: Haz principal neón
            val routeCyanColor = Color(0xFF00E5FF)
            for (i in 0 until routePool.count) {
                val seg = routePool.pool[i]
                drawLine(routeCyanColor, Offset(seg.x1, seg.y1), Offset(seg.x2, seg.y2), strokeWidth = seg.rWidth, cap = StrokeCap.Round)
            }
            // Capa 4: Núcleo reflectante
            val routeCoreColor = Color.White.copy(alpha = 0.85f)
            for (i in 0 until routePool.count) {
                val seg = routePool.pool[i]
                drawLine(routeCoreColor, Offset(seg.x1, seg.y1), Offset(seg.x2, seg.y2), strokeWidth = (seg.rWidth * 0.24f).coerceAtLeast(1.2f), cap = StrokeCap.Round)
            }
        }
    }

    // 6. POIs en Perspectiva 3D
    for (poi in graph.pois) {
        val dx = poi.x - truckX
        val dz = poi.z - truckZ
        if (dx * dx + dz * dz > 1000000f) continue // 1000m

        if (!projectPoint3D(poi.x, poi.z)) continue
        val sx = scratchCache.projX1
        val sy = scratchCache.projY1
        if (sy in (horizonY + 12f)..(h + 60f) && sx in -60f..(w + 60f)) {
            val depthRatio = ((sy - horizonY) / (targetTruckY - horizonY)).coerceIn(0.35f, 1.25f)
            drawPoiMarker3D(poi, sx, sy - 10f * depthRatio, depthRatio, poiIconPaint, poiLabelPaint, poiBgPaint)
        }
    }

    // 7. Cursor 3D del camión de alto contraste (siempre dibujado AL FINAL)
    drawTruckCursor3D(w * 0.5f, targetTruckY, h, cursorPulseAlpha, reusablePaths, scratchCache)
}

private fun DrawScope.drawPoiMarker3D(
    poi: MapPoi,
    sx: Float,
    sy: Float,
    depthRatio: Float,
    iconPaint: android.graphics.Paint,
    labelPaint: android.graphics.Paint,
    bgPaint: android.graphics.Paint
) {
    val (bgColor, iconText, defaultLabel) = poiMarkerStyle(poi.poiType.toInt())
    val radius = 12f * density * depthRatio

    // Línea de anclaje vertical hacia el suelo
    drawLine(Color(0x99FFFFFF), Offset(sx, sy), Offset(sx, sy + radius * 1.3f), strokeWidth = 1.8f)

    // Círculo con resplandor
    drawCircle(Color(0x77000000), radius = radius + 3f, center = Offset(sx, sy + 2f))
    drawCircle(bgColor, radius = radius, center = Offset(sx, sy))
    drawCircle(Color.White, radius = radius, center = Offset(sx, sy), style = Stroke(width = 1.8f))

    val labelText = if (poi.name.isNotEmpty()) poi.name else defaultLabel

    drawIntoCanvas { canvas ->
        iconPaint.color = android.graphics.Color.WHITE
        iconPaint.textSize = radius * 0.72f
        canvas.nativeCanvas.drawText(iconText, sx, sy + radius * 0.25f, iconPaint)

        if (depthRatio > 0.5f) {
            labelPaint.textSize = (9f * density * depthRatio).coerceAtLeast(8f * density)
            val textWidth = labelPaint.measureText(labelText)
            val pillPad = 3f * density
            val pTop = sy + radius + 2f
            val pBottom = pTop + 13f * density
            val pLeft = sx - textWidth / 2f - pillPad
            val pRight = sx + textWidth / 2f + pillPad

            canvas.nativeCanvas.drawRoundRect(pLeft, pTop, pRight, pBottom, 5f, 5f, bgPaint)
            canvas.nativeCanvas.drawText(labelText, sx, pBottom - 3f * density, labelPaint)
        }
    }
}

private fun DrawScope.drawGroundGrid3D(
    truckX: Float,
    truckZ: Float,
    gridColor: Color,
    scratchCache: RenderScratchCache,
    segmentProjector: (Float, Float, Float, Float) -> Boolean
) {
    val gridSpacing = 20f
    val gridRange = 140f

    val startX = (floor((truckX - gridRange) / gridSpacing) * gridSpacing)
    val endX = (ceil((truckX + gridRange) / gridSpacing) * gridSpacing)
    val startZ = (floor((truckZ - gridRange) / gridSpacing) * gridSpacing)
    val endZ = (ceil((truckZ + gridRange) / gridSpacing) * gridSpacing)

    var curX = startX
    while (curX <= endX) {
        if (segmentProjector(curX, truckZ - gridRange, curX, truckZ + gridRange)) {
            drawLine(
                gridColor,
                Offset(scratchCache.projX1, scratchCache.projY1),
                Offset(scratchCache.projX2, scratchCache.projY2),
                strokeWidth = 1.0f
            )
        }
        curX += gridSpacing
    }

    var curZ = startZ
    while (curZ <= endZ) {
        if (segmentProjector(truckX - gridRange, curZ, truckX + gridRange, curZ)) {
            drawLine(
                gridColor,
                Offset(scratchCache.projX1, scratchCache.projY1),
                Offset(scratchCache.projX2, scratchCache.projY2),
                strokeWidth = 1.0f
            )
        }
        curZ += gridSpacing
    }
}

private fun DrawScope.drawTruckCursor3D(
    cx: Float,
    cy: Float,
    h: Float,
    pulseAlpha: Float,
    paths: ReusableCanvasPaths,
    scratchCache: RenderScratchCache
) {
    val r = 26f * density

    // 1. Sombra de contacto suave en el asfalto
    drawOval(
        color = Color(0x88000000),
        topLeft = Offset(cx - r * 0.90f, cy + r * 0.18f),
        size = Size(r * 1.80f, r * 0.62f)
    )

    // 2. Haz de luz proyectado hacia adelante (Faros del camion sobre la carretera)
    val headlightPath = paths.headlightPath
    headlightPath.reset()
    headlightPath.moveTo(cx - r * 0.28f, cy - r * 0.35f)
    headlightPath.lineTo(cx - r * 1.35f, cy - r * 3.30f)
    headlightPath.lineTo(cx + r * 1.35f, cy - r * 3.30f)
    headlightPath.lineTo(cx + r * 0.28f, cy - r * 0.35f)
    headlightPath.close()

    if (scratchCache.headlightBrush == null || scratchCache.headlightHeight != h || scratchCache.headlightDensity != density) {
        scratchCache.headlightHeight = h
        scratchCache.headlightDensity = density
        scratchCache.headlightBrush = Brush.verticalGradient(
            colors = listOf(Color(0x2CF8FAFC), Color(0x00F8FAFC)),
            startY = cy - r * 0.35f,
            endY = cy - r * 3.30f
        )
    }
    drawPath(
        path = headlightPath,
        brush = scratchCache.headlightBrush!!
    )

    // 3. Anillo exterior suave/pulsante en verde lima electrico (#C7F36B)
    drawCircle(
        color = Color(0xFFC7F36B).copy(alpha = pulseAlpha * 0.38f),
        radius = r * 1.38f,
        center = Offset(cx, cy)
    )
    drawCircle(
        color = Color(0xFFC7F36B).copy(alpha = (pulseAlpha + 0.25f).coerceAtMost(0.92f)),
        radius = r * 1.22f,
        center = Offset(cx, cy),
        style = Stroke(width = 2.0f * density)
    )

    // 4. Escudo/disco oscuro de alto contraste (#0B1218) con borde blanco nitido
    drawCircle(
        color = Color(0xFF0B1218),
        radius = r * 0.94f,
        center = Offset(cx, cy)
    )
    drawCircle(
        color = Color.White,
        radius = r * 0.94f,
        center = Offset(cx, cy),
        style = Stroke(width = 2.2f * density)
    )

    // 5. Flecha chevron blanca pura (#FFFFFF) con borde oscuro fuerte y acento central lima (#C7F36B)
    val chevronPath = paths.outlinePath
    chevronPath.reset()
    chevronPath.moveTo(cx, cy - r * 0.68f)
    chevronPath.lineTo(cx + r * 0.54f, cy + r * 0.44f)
    chevronPath.lineTo(cx, cy + r * 0.18f)
    chevronPath.lineTo(cx - r * 0.54f, cy + r * 0.44f)
    chevronPath.close()

    drawPath(
        path = chevronPath,
        color = Color(0xFF0B1218),
        style = Stroke(width = 3.4f * density, cap = StrokeCap.Round, join = StrokeJoin.Round)
    )
    drawPath(path = chevronPath, color = Color.White)

    val centerAccent = paths.rightFacet
    centerAccent.reset()
    centerAccent.moveTo(cx, cy - r * 0.44f)
    centerAccent.lineTo(cx + r * 0.22f, cy + r * 0.24f)
    centerAccent.lineTo(cx, cy + r * 0.12f)
    centerAccent.lineTo(cx - r * 0.22f, cy + r * 0.24f)
    centerAccent.close()
    drawPath(path = centerAccent, color = Color(0xFFC7F36B))
}

private fun DrawScope.drawTruckCursor(
    cx: Float,
    cy: Float,
    rotationDeg: Float,
    pulseAlpha: Float,
    paths: ReusableCanvasPaths
) {
    val r = 20f * density

    // 1. Anillo exterior suave/pulsante en verde lima electrico (#C7F36B)
    drawCircle(
        color = Color(0xFFC7F36B).copy(alpha = pulseAlpha * 0.38f),
        radius = r * 1.40f,
        center = Offset(cx, cy)
    )
    drawCircle(
        color = Color(0xFFC7F36B).copy(alpha = (pulseAlpha + 0.25f).coerceAtMost(0.92f)),
        radius = r * 1.24f,
        center = Offset(cx, cy),
        style = Stroke(width = 1.8f * density)
    )

    // 2. Disco oscuro de alto contraste (#0B1218) con borde blanco nitido de 2.2f * density
    drawCircle(
        color = Color(0xFF0B1218),
        radius = r,
        center = Offset(cx, cy)
    )
    drawCircle(
        color = Color.White,
        radius = r,
        center = Offset(cx, cy),
        style = Stroke(width = 2.2f * density)
    )

    // 3. Flecha chevron blanca pura (#FFFFFF) rotada con borde oscuro fuerte y acento central lima (#C7F36B)
    rotate(rotationDeg, pivot = Offset(cx, cy)) {
        val cursorPath = paths.cursorPath
        cursorPath.reset()
        cursorPath.moveTo(cx, cy - r * 0.70f)
        cursorPath.lineTo(cx + r * 0.54f, cy + r * 0.48f)
        cursorPath.lineTo(cx, cy + r * 0.20f)
        cursorPath.lineTo(cx - r * 0.54f, cy + r * 0.48f)
        cursorPath.close()

        drawPath(
            path = cursorPath,
            color = Color(0xFF0B1218),
            style = Stroke(width = 3.0f * density, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
        drawPath(cursorPath, color = Color.White)

        val accentPath = paths.leftFacet
        accentPath.reset()
        accentPath.moveTo(cx, cy - r * 0.45f)
        accentPath.lineTo(cx + r * 0.22f, cy + r * 0.26f)
        accentPath.lineTo(cx, cy + r * 0.14f)
        accentPath.lineTo(cx - r * 0.22f, cy + r * 0.26f)
        accentPath.close()
        drawPath(accentPath, color = Color(0xFFC7F36B))
    }
}

private fun DrawScope.drawEmptyState(paint: android.graphics.Paint) {
    drawIntoCanvas { canvas ->
        paint.textSize = 15f * density
        canvas.nativeCanvas.drawText("Descargando mapa vial desde la PC...", size.width / 2f, size.height / 2f, paint)
    }
}
