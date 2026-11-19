package com.ats.gps.ui.map

import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
    var lastX: Float = Float.NaN
    var lastZ: Float = Float.NaN
    var lastHeading: Float = Float.NaN
    var cachedSnap: RoadSnapResult? = null
}

private class Viewport2DCache {
    var edges: List<MapEdge> = emptyList()
    var lastX: Float = Float.NaN
    var lastZ: Float = Float.NaN
    var lastScale: Float = 1.0f

    // Cadenas pre-calculadas y suavizadas en coordenadas del mundo
    var rampChains: List<List<Point3D>> = emptyList()
    var localChains: List<List<Point3D>> = emptyList()
    var mainChains: List<List<Point3D>> = emptyList()
    var hwyChains: List<List<Point3D>> = emptyList()
}

private class RoadSeg3D(
    val isFreeway: Boolean,
    val lanes: Int,
    val laneWidthFactor: Float,
    val points: List<Point3D>
)

private class Viewport3DCache {
    var edges: List<MapEdge> = emptyList()
    var lastX: Float = Float.NaN
    var lastZ: Float = Float.NaN
    var roadSegs: List<RoadSeg3D> = emptyList()
}

private class RouteRenderCache {
    var cachedRoute: RouteResult? = null
    var fullSmoothedPoints: List<Point3D> = emptyList()
    var lastTruckX: Float = Float.NaN
    var lastTruckZ: Float = Float.NaN
    var trimmedPoints: List<Point3D> = emptyList()
    var lastTrimIdx: Int = 0
}

private class ReusableRoadSeg(
    var x1: Float = 0f,
    var y1: Float = 0f,
    var x2: Float = 0f,
    var y2: Float = 0f,
    var casingW: Float = 0f,
    var depthRatio: Float = 0f,
    var isFreeway: Boolean = false,
    var lanes: Int = 1
)

private class RoadSegPool3D(capacity: Int = 3000) {
    val pool = Array(capacity) { ReusableRoadSeg() }
    val indices = IntArray(capacity) { it }
    var count = 0

    fun reset() {
        count = 0
    }

    fun add(
        x1: Float, y1: Float, x2: Float, y2: Float,
        casingW: Float, depthRatio: Float,
        isFreeway: Boolean, lanes: Int
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

    private inline fun getMidY(idx: Int): Float {
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

private class RouteSegPool3D(capacity: Int = 1000) {
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
    val currentOnZoom by rememberUpdatedState(onZoomChange)
    val currentOnPan by rememberUpdatedState(onPanChange)
    val currentOnMapTapped by rememberUpdatedState(onMapTapped)

    // Coordenadas objetivo en tiempo real (rememberUpdatedState para evitar capturas estáticas)
    val targetX by rememberUpdatedState(if (telemetry.posX.isNaN()) 0f else telemetry.posX.toFloat())
    val targetY by rememberUpdatedState(if (telemetry.posY.isNaN()) 0f else telemetry.posY.toFloat())
    val targetZ by rememberUpdatedState(if (telemetry.posZ.isNaN()) 0f else telemetry.posZ.toFloat())
    val targetHeading by rememberUpdatedState(if (telemetry.headingDegrees.isNaN()) 0f else telemetry.headingDegrees.toFloat())

    // Estados suavizados para renderizado continuo e interpolado (60-120 Hz)
    var smoothTruckX by remember { mutableFloatStateOf(targetX) }
    var smoothTruckY by remember { mutableFloatStateOf(targetY) }
    var smoothTruckZ by remember { mutableFloatStateOf(targetZ) }
    var smoothHeading by remember { mutableFloatStateOf(targetHeading) }

    LaunchedEffect(telemetry.posX, telemetry.posZ) {
        if (abs(smoothTruckX) < 0.001f && abs(smoothTruckZ) < 0.001f && (abs(targetX) > 0.001f || abs(targetZ) > 0.001f)) {
            smoothTruckX = targetX
            smoothTruckY = targetY
            smoothTruckZ = targetZ
            smoothHeading = targetHeading
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
                    smoothHeading = th
                } else {
                    smoothTruckX += dx * lerpFactor
                    smoothTruckY += dy * lerpFactor
                    smoothTruckZ += dz * lerpFactor

                    val headingDiff = ((th - smoothHeading + 540f) % 360f) - 180f
                    smoothHeading += headingDiff * lerpFactor
                }
            }
        }
    }

    // Estructuras de caché persistentes en memoria (reutilizadas sin recolector de basura)
    val snapCache = remember { SnapCache() }
    val cache2D = remember { Viewport2DCache() }
    val cache3D = remember { Viewport3DCache() }
    val routeCache = remember { RouteRenderCache() }
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

    val canvasBg = when (mapTheme) {
        MapThemeStyle.DARK_PREMIUM -> Color(0xFF0A0F1D)
        MapThemeStyle.HIGH_CONTRAST -> Color(0xFF000000)
        MapThemeStyle.CYBER_NEON -> Color(0xFF070B12)
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .background(canvasBg)
            .pointerInput(Unit) {
                detectTapGestures { tapOffset ->
                    val cx = size.width / 2f
                    val cy = size.height * 0.62f
                    val baseScale = (0.75f * currentZoom).coerceIn(0.05f, 6.0f)
                    val viewCenterX = smoothTruckX - (if (!isFollowingTruck) currentPan.x / baseScale else 0f)
                    val viewCenterZ = smoothTruckZ - (if (!isFollowingTruck) currentPan.y / baseScale else 0f)
                    
                    val worldX = viewCenterX + (tapOffset.x - cx) / baseScale
                    val worldZ = viewCenterZ + (tapOffset.y - cy) / baseScale
                    currentOnMapTapped(worldX, worldZ)
                }
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val newZoom = (currentZoom * zoom).coerceIn(0.10f, 4.5f)
                    currentOnZoom(newZoom)
                    currentOnPan(currentPan + pan)
                }
            }
    ) {
        if (graph == null || graph.nodes.isEmpty()) {
            drawEmptyState()
            return@Canvas
        }

        // Snapping inteligente throttled: sólo se ejecuta si el camión se movió > 1.8 metros
        val dxSnap = smoothTruckX - snapCache.lastX
        val dzSnap = smoothTruckZ - snapCache.lastZ
        val dHeadingSnap = abs(smoothHeading - snapCache.lastHeading)
        val snap = if (snapCache.lastX.isNaN() || (dxSnap * dxSnap + dzSnap * dzSnap) > 3.24f || dHeadingSnap > 8f) {
            val s = graph.snapToRoad(
                x = smoothTruckX,
                z = smoothTruckZ,
                headingDeg = smoothHeading,
                preferredEdges = activeRoute?.edgePath,
                maxDistMeters = 26f
            )
            snapCache.lastX = smoothTruckX
            snapCache.lastZ = smoothTruckZ
            snapCache.lastHeading = smoothHeading
            snapCache.cachedSnap = s
            s
        } else {
            snapCache.cachedSnap
        }

        val truckX = snap?.snappedX ?: smoothTruckX
        val truckY = smoothTruckY
        val truckZ = snap?.snappedZ ?: smoothTruckZ
        val headingDeg = smoothHeading

        // Trazado de ruta activa pre-suavizado con ventana deslizante O(1)
        val smoothRoutePoints = getOrUpdateSmoothedRoute(activeRoute, truckX, truckZ, routeCache)

        when (viewMode) {
            MapViewMode.MODE_2D_TOP_DOWN -> {
                val cx = size.width / 2f
                val cy = size.height * 0.62f
                val baseScale = (0.75f * zoomScale).coerceIn(0.05f, 6.0f)

                val viewCenterX = truckX - (if (!isFollowingTruck) panOffset.x / baseScale else 0f)
                val viewCenterZ = truckZ - (if (!isFollowingTruck) panOffset.y / baseScale else 0f)
                val viewRadius = (max(size.width, size.height) / baseScale) * 1.5f

                // Comprobación de caché de carreteras 2D: sólo reconstruir cadenas si se desplazó > 25 metros
                val distFromLast2D = if (cache2D.lastX.isNaN()) Float.MAX_VALUE else {
                    val qx = viewCenterX - cache2D.lastX
                    val qz = viewCenterZ - cache2D.lastZ
                    qx * qx + qz * qz
                }
                val scaleRatio = if (cache2D.lastScale > 0f) abs(baseScale - cache2D.lastScale) / cache2D.lastScale else 1f

                if (distFromLast2D > 625f || scaleRatio > 0.08f || cache2D.edges.isEmpty()) {
                    val visible = graph.getEdgesNear(viewCenterX, viewCenterZ, viewRadius * 1.25f)
                    cache2D.edges = visible
                    cache2D.lastX = viewCenterX
                    cache2D.lastZ = viewCenterZ
                    cache2D.lastScale = baseScale

                    val hwyEdges = ArrayList<MapEdge>()
                    val mainEdges = ArrayList<MapEdge>()
                    val localEdges = ArrayList<MapEdge>()
                    val rampEdges = ArrayList<MapEdge>()

                    val activeEdgeIds = activeRoute?.edgePath?.map { it.edgeId }?.toSet() ?: emptySet()
                    for (edge in visible) {
                        if (edge.polyline.size < 2) continue
                        val isRouteEdge = activeEdgeIds.contains(edge.edgeId)
                        if (!isRouteEdge && edge.roadType.toInt() == 3 && edge.length < 18f) continue

                        when (edge.roadType.toInt()) {
                            0 -> hwyEdges.add(edge)
                            1 -> mainEdges.add(edge)
                            2 -> localEdges.add(edge)
                            else -> rampEdges.add(edge)
                        }
                    }

                    // Se suavizan y encadenan UNA SOLA VEZ al mover de sector, nunca en cada frame
                    cache2D.rampChains = chainEdges(rampEdges).map { smoothPolyline(it, iterations = 1) }
                    cache2D.localChains = chainEdges(localEdges).map { smoothPolyline(it, iterations = 1) }
                    cache2D.mainChains = chainEdges(mainEdges).map { smoothPolyline(it, iterations = 1) }
                    cache2D.hwyChains = chainEdges(hwyEdges).map { smoothPolyline(it, iterations = 1) }
                }

                drawMap2D(
                    graph = graph,
                    cache2D = cache2D,
                    reusablePaths = reusablePaths,
                    truckX = truckX,
                    truckZ = truckZ,
                    viewCenterX = viewCenterX,
                    viewCenterZ = viewCenterZ,
                    viewRadius = viewRadius,
                    cx = cx,
                    cy = cy,
                    baseScale = baseScale,
                    headingDeg = headingDeg,
                    activeRoute = activeRoute,
                    routePoints = smoothRoutePoints,
                    zoomScale = zoomScale,
                    isFollowingTruck = isFollowingTruck,
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
                if (distFromLast3D > 225f || cache3D.edges.isEmpty()) {
                    val nearby = graph.getEdgesNear(truckX, truckZ, 1400f)
                    cache3D.edges = nearby
                    cache3D.lastX = truckX
                    cache3D.lastZ = truckZ

                    val segs = ArrayList<RoadSeg3D>(nearby.size)
                    val activeEdgeIds = activeRoute?.edgePath?.map { it.edgeId }?.toSet() ?: emptySet()
                    for (edge in nearby) {
                        if (edge.polyline.size < 2) continue
                        val isRouteEdge = activeEdgeIds.contains(edge.edgeId)
                        if (!isRouteEdge && edge.roadType.toInt() == 3 && edge.length < 18f) continue

                        val isFreeway = edge.roadType.toInt() == 0
                        val lanes = edge.lanes.toInt().coerceAtLeast(1)
                        val laneWidthFactor = 1.0f + (lanes - 1) * 0.38f
                        segs.add(RoadSeg3D(isFreeway, lanes, laneWidthFactor, edge.polyline))
                    }
                    cache3D.roadSegs = segs
                }

                // Controlador de zoom dinámico en función de la velocidad
                val speedKmh = telemetry.speedKmh.coerceAtLeast(0f)
                val speedZoomRatio = (speedKmh / 90f).coerceIn(0f, 1f)
                val dynamicZoom = zoomScale * (0.85f + speedZoomRatio * 0.35f)

                drawMap3D(
                    graph = graph,
                    cache3D = cache3D,
                    roadPool = roadSegPool,
                    routePool = routeSegPool,
                    reusablePaths = reusablePaths,
                    truckX = truckX,
                    truckY = truckY,
                    truckZ = truckZ,
                    headingDeg = headingDeg,
                    activeRoute = activeRoute,
                    routePoints = smoothRoutePoints,
                    zoomScale = dynamicZoom,
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
    if (points.size < 3 || iterations <= 0 || points.size > 2000) return points
    var current = points
    repeat(iterations) {
        if (current.size < 3 || current.size > 2000) return current
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

private fun subdivideForegroundSegments(
    points: List<Point3D>,
    truckX: Float,
    truckZ: Float,
    radiusSq: Float = 122500f,
    maxSegLenMeters: Float = 8f
): List<Point3D> {
    if (points.size < 2) return points
    val out = ArrayList<Point3D>(points.size)
    out.add(points[0])
    for (i in 0 until points.size - 1) {
        val p0 = points[i]
        val p1 = points[i + 1]
        val d0Sq = (p0.x - truckX) * (p0.x - truckX) + (p0.z - truckZ) * (p0.z - truckZ)
        val d1Sq = (p1.x - truckX) * (p1.x - truckX) + (p1.z - truckZ) * (p1.z - truckZ)
        if (d0Sq <= radiusSq || d1Sq <= radiusSq) {
            val dx = p1.x - p0.x
            val dy = p1.y - p0.y
            val dz = p1.z - p0.z
            val len = sqrt(dx * dx + dz * dz)
            if (len > maxSegLenMeters) {
                val steps = ceil(len / maxSegLenMeters).toInt().coerceAtMost(48)
                for (s in 1 until steps) {
                    val t = s.toFloat() / steps.toFloat()
                    out.add(Point3D(p0.x + dx * t, p0.y + dy * t, p0.z + dz * t))
                }
            }
        }
        out.add(p1)
    }
    return out
}

private fun getOrUpdateSmoothedRoute(
    activeRoute: RouteResult?,
    truckX: Float,
    truckZ: Float,
    routeCache: RouteRenderCache
): List<Point3D> {
    if (activeRoute == null || activeRoute.edgePath.isEmpty()) {
        routeCache.cachedRoute = null
        routeCache.fullSmoothedPoints = emptyList()
        routeCache.trimmedPoints = emptyList()
        return emptyList()
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
        routeCache.fullSmoothedPoints = smoothPolyline(sub, iterations = 2)
        routeCache.cachedRoute = activeRoute
        routeCache.lastTruckX = Float.NaN
        routeCache.lastTruckZ = Float.NaN
        routeCache.lastTrimIdx = 0
        routeCache.trimmedPoints = routeCache.fullSmoothedPoints
    }

    val fullPoints = routeCache.fullSmoothedPoints
    if (fullPoints.size < 2) return fullPoints

    // Solo recortar si el camión se movió más de 2.0 metros
    val dx = truckX - routeCache.lastTruckX
    val dz = truckZ - routeCache.lastTruckZ
    if (!routeCache.lastTruckX.isNaN() && (dx * dx + dz * dz) < 4.0f) {
        return routeCache.trimmedPoints
    }

    // Búsqueda en ventana cercana a la posición anterior para O(1)
    var bestIdx = -1
    var bestDistSq = Float.MAX_VALUE
    val startWindow = (routeCache.lastTrimIdx - 2).coerceAtLeast(0)
    val endWindow = (routeCache.lastTrimIdx + 25).coerceAtMost(fullPoints.size - 2)

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
        if (dSq < bestDistSq) {
            bestDistSq = dSq
            bestIdx = i
        }
    }

    // Si no está en la ventana cercana (< 45m), buscar en toda la lista
    if (bestDistSq > 2025f) {
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
            if (dSq < bestDistSq) {
                bestDistSq = dSq
                bestIdx = i
            }
        }
    }

    val trimStart = if (bestIdx >= 0) bestIdx.coerceAtMost(fullPoints.size - 1) else 0
    routeCache.lastTrimIdx = trimStart
    routeCache.lastTruckX = truckX
    routeCache.lastTruckZ = truckZ
    routeCache.trimmedPoints = if (trimStart > 0) fullPoints.subList(trimStart, fullPoints.size) else fullPoints
    return routeCache.trimmedPoints
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
    truckX: Float,
    truckZ: Float,
    viewCenterX: Float,
    viewCenterZ: Float,
    viewRadius: Float,
    cx: Float,
    cy: Float,
    baseScale: Float,
    headingDeg: Float,
    activeRoute: RouteResult?,
    routePoints: List<Point3D>,
    zoomScale: Float,
    isFollowingTruck: Boolean,
    cityPaint: android.graphics.Paint,
    poiIconPaint: android.graphics.Paint,
    poiLabelPaint: android.graphics.Paint,
    poiBgPaint: android.graphics.Paint
) {
    val hasRamp = populateChainsToPath(cache2D.rampChains, reusablePaths.rampPath, viewCenterX, viewCenterZ, cx, cy, baseScale)
    val hasLocal = populateChainsToPath(cache2D.localChains, reusablePaths.localPath, viewCenterX, viewCenterZ, cx, cy, baseScale)
    val hasMain = populateChainsToPath(cache2D.mainChains, reusablePaths.mainPath, viewCenterX, viewCenterZ, cx, cy, baseScale)
    val hasHwy = populateChainsToPath(cache2D.hwyChains, reusablePaths.hwyPath, viewCenterX, viewCenterZ, cx, cy, baseScale)

    val rampW = getRoadStrokeWidth(3, zoomScale)
    val localW = getRoadStrokeWidth(2, zoomScale)
    val mainW = getRoadStrokeWidth(1, zoomScale)
    val hwyW = getRoadStrokeWidth(0, zoomScale)

    // GLOBAL PASS 1: Todos los bordes exteriores
    val borderColor = Color(0xFF0B1017)
    if (hasRamp) drawPath(reusablePaths.rampPath, borderColor, style = Stroke(rampW + 4f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    if (hasLocal) drawPath(reusablePaths.localPath, borderColor, style = Stroke(localW + 4f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    if (hasMain) drawPath(reusablePaths.mainPath, borderColor, style = Stroke(mainW + 4f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    if (hasHwy) drawPath(reusablePaths.hwyPath, borderColor, style = Stroke(hwyW + 4f, cap = StrokeCap.Round, join = StrokeJoin.Round))

    // GLOBAL PASS 2: Todos los rellenos de asfalto unificados encima
    val streetFill = Color(0xFF2B3846)
    val hwyFill = Color(0xFF334354)
    if (hasRamp) drawPath(reusablePaths.rampPath, streetFill, style = Stroke(rampW, cap = StrokeCap.Round, join = StrokeJoin.Round))
    if (hasLocal) drawPath(reusablePaths.localPath, streetFill, style = Stroke(localW, cap = StrokeCap.Round, join = StrokeJoin.Round))
    if (hasMain) drawPath(reusablePaths.mainPath, streetFill, style = Stroke(mainW, cap = StrokeCap.Round, join = StrokeJoin.Round))
    if (hasHwy) drawPath(reusablePaths.hwyPath, hwyFill, style = Stroke(hwyW, cap = StrokeCap.Round, join = StrokeJoin.Round))

    // 2. Ruta activa GPS (Cinta continua con Path reutilizable)
    if (routePoints.size >= 2) {
        val rPath = reusablePaths.routePath
        rPath.reset()
        var hasRoute = false
        for (i in routePoints.indices) {
            val pt = routePoints[i]
            val sx = cx + (pt.x - viewCenterX) * baseScale
            val sy = cy + (pt.z - viewCenterZ) * baseScale
            if (sx.isNaN() || sy.isNaN()) continue
            if (i == 0) {
                rPath.moveTo(sx, sy)
            } else {
                rPath.lineTo(sx, sy)
                hasRoute = true
            }
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

    // 4. Cursor táctico del camión
    if (isFollowingTruck) {
        drawTruckCursor(cx, cy, headingDeg, reusablePaths)
    } else {
        val truckSx = cx + (truckX - viewCenterX) * baseScale
        val truckSy = cy + (truckZ - viewCenterZ) * baseScale
        if (!truckSx.isNaN() && !truckSy.isNaN()) {
            drawTruckCursor(truckSx, truckSy, headingDeg, reusablePaths)
        }
    }
}

private fun getRoadStrokeWidth(roadType: Int, zoomScale: Float): Float {
    val z = zoomScale.coerceIn(0.35f, 2.2f)
    return when (roadType) {
        0 -> 15f * z
        1 -> 11f * z
        2 -> 7f * z
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
        iconPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
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
    truckX: Float,
    truckY: Float,
    truckZ: Float,
    headingDeg: Float,
    activeRoute: RouteResult?,
    routePoints: List<Point3D>,
    zoomScale: Float,
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

    // 1. Cielo crepuscular
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(
                Color(0xFF030508),
                Color(0xFF080F1D),
                Color(0xFF112238),
                Color(0xFF1A3350)
            ),
            startY = 0f,
            endY = horizonY
        ),
        topLeft = Offset.Zero,
        size = Size(w, horizonY)
    )

    // Resplandor en el horizonte
    drawRect(
        brush = Brush.verticalGradient(
            listOf(Color(0xFF00E5FF).copy(alpha = 0.35f), Color.Transparent),
            startY = horizonY - 4f,
            endY = horizonY + 28f
        ),
        topLeft = Offset(0f, horizonY - 4f),
        size = Size(w, 32f)
    )

    // 2. Terreno oscuro
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(
                Color(0xFF080D14),
                Color(0xFF0C1013),
                Color(0xFF10151A),
                Color(0xFF151C21)
            ),
            startY = horizonY,
            endY = h
        ),
        topLeft = Offset(0f, horizonY),
        size = Size(w, h - horizonY)
    )

    // Rotación del mundo según Heading
    val camHeadingRad = Math.toRadians(headingDeg.toDouble()).toFloat()
    val cosH = cos(-camHeadingRad)
    val sinH = sin(-camHeadingRad)

    fun projectPoint3D(wx: Float, wz: Float): Offset? {
        val dx = wx - truckX
        val dz = wz - truckZ
        val rx = dx * cosH - dz * sinH
        val rz = -(dx * sinH + dz * cosH)

        val depth = rz + camDist3D
        if (depth < 8f) return null

        val factor = focal3D / depth
        val sx = (w * 0.5f) + rx * factor
        val sy = horizonY + (camHeight3D * factor)
        if (sy < horizonY - 2f || sy > h + 300f) return null
        return Offset(sx, sy)
    }

    fun projectSegment3D(x1: Float, z1: Float, x2: Float, z2: Float): Pair<Offset, Offset>? {
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

        if (depth1 < minDepth && depth2 < minDepth) return null

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
            return null
        }
        return Pair(Offset(sx1, sy1), Offset(sx2, sy2))
    }

    clipRect(0f, horizonY, w, h) {
        // 3. Rejilla virtual de perspectiva en el suelo
        drawGroundGrid3D(truckX, truckZ) { x1, z1, x2, z2 ->
            projectSegment3D(x1, z1, x2, z2)
        }

        // 4. Red Vial 3D proyectada usando pool de memoria estática (CERO asignaciones heap)
        roadPool.reset()
        for (road in cache3D.roadSegs) {
            val pts = road.points
            for (i in 0 until pts.size - 1) {
                val p0 = pts[i]
                val p1 = pts[i + 1]
                val seg = projectSegment3D(p0.x, p0.z, p1.x, p1.z) ?: continue
                val midY = (seg.first.y + seg.second.y) * 0.5f
                val depthRatio = ((midY - horizonY) / (targetTruckY - horizonY)).coerceIn(0.08f, 1.4f)
                val casingW = if (road.isFreeway) {
                    (24f * depthRatio * road.laneWidthFactor).coerceIn(3.5f, 38f)
                } else {
                    (14f * depthRatio * road.laneWidthFactor).coerceIn(2.0f, 22f)
                }
                roadPool.add(seg.first.x, seg.first.y, seg.second.x, seg.second.y, casingW, depthRatio, road.isFreeway, road.lanes)
            }
        }

        // Ordenamiento por profundidad in-place
        roadPool.sortBackToFront()

        // PASS 1: Bordes exteriores
        val roadBorderColor = Color(0xFF0A0F18)
        for (i in 0 until roadPool.count) {
            val seg = roadPool.pool[roadPool.indices[i]]
            drawLine(
                color = roadBorderColor,
                start = Offset(seg.x1, seg.y1),
                end = Offset(seg.x2, seg.y2),
                strokeWidth = seg.casingW + 3f,
                cap = StrokeCap.Round
            )
        }

        // PASS 2: Rellenos de asfalto
        val hwyAsphalt = Color(0xFF26323F)
        val otherAsphalt = Color(0xFF1F2A35)
        for (i in 0 until roadPool.count) {
            val seg = roadPool.pool[roadPool.indices[i]]
            drawLine(
                color = if (seg.isFreeway) hwyAsphalt else otherAsphalt,
                start = Offset(seg.x1, seg.y1),
                end = Offset(seg.x2, seg.y2),
                strokeWidth = seg.casingW,
                cap = StrokeCap.Round
            )
        }

        // PASS 3: Líneas divisorias de carriles
        val dividerColor = Color(0x5594A3B8)
        for (i in 0 until roadPool.count) {
            val seg = roadPool.pool[roadPool.indices[i]]
            if (seg.lanes >= 2 && seg.depthRatio > 0.35f) {
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

        // 5. Ruta de Navegación Activa GPS en 3D
        routePool.reset()
        if (routePoints.size >= 2) {
            for (i in 0 until routePoints.size - 1) {
                val p0 = routePoints[i]
                val p1 = routePoints[i + 1]
                val seg = projectSegment3D(p0.x, p0.z, p1.x, p1.z) ?: continue
                val midY = (seg.first.y + seg.second.y) * 0.5f
                val depthRatio = ((midY - horizonY) / (targetTruckY - horizonY)).coerceIn(0.10f, 1.35f)
                val rWidth = (28f * depthRatio).coerceIn(4.5f, 36f)
                routePool.add(seg.first.x, seg.first.y, seg.second.x, seg.second.y, rWidth)
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

        val sp = projectPoint3D(poi.x, poi.z) ?: continue
        if (sp.y in (horizonY + 12f)..(h + 60f) && sp.x in -60f..(w + 60f)) {
            val depthRatio = ((sp.y - horizonY) / (targetTruckY - horizonY)).coerceIn(0.35f, 1.25f)
            drawPoiMarker3D(poi, sp.x, sp.y - 10f * depthRatio, depthRatio, poiIconPaint, poiLabelPaint, poiBgPaint)
        }
    }

    // 7. Cursor 3D del camión con haz de luz
    drawTruckCursor3D(w * 0.5f, targetTruckY, reusablePaths)
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
        iconPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
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
    segmentProjector: (Float, Float, Float, Float) -> Pair<Offset, Offset>?
) {
    val gridSpacing = 20f
    val gridRange = 140f

    val startX = (floor((truckX - gridRange) / gridSpacing) * gridSpacing)
    val endX = (ceil((truckX + gridRange) / gridSpacing) * gridSpacing)
    val startZ = (floor((truckZ - gridRange) / gridSpacing) * gridSpacing)
    val endZ = (ceil((truckZ + gridRange) / gridSpacing) * gridSpacing)

    var curX = startX
    while (curX <= endX) {
        val seg = segmentProjector(curX, truckZ - gridRange, curX, truckZ + gridRange)
        if (seg != null) {
            drawLine(Color(0x1838BDF8), seg.first, seg.second, strokeWidth = 1.0f)
        }
        curX += gridSpacing
    }

    var curZ = startZ
    while (curZ <= endZ) {
        val seg = segmentProjector(truckX - gridRange, curZ, truckX + gridRange, curZ)
        if (seg != null) {
            drawLine(Color(0x1838BDF8), seg.first, seg.second, strokeWidth = 1.0f)
        }
        curZ += gridSpacing
    }
}

private fun DrawScope.drawTruckCursor3D(cx: Float, cy: Float, paths: ReusableCanvasPaths) {
    // 1. Sombra de contacto suave en el asfalto
    drawOval(
        color = Color(0x77000000),
        topLeft = Offset(cx - 20f, cy + 4f),
        size = Size(40f, 16f)
    )

    // 2. Haz de luz proyectado hacia adelante (Faros del camión sobre la carretera)
    val headlightPath = paths.headlightPath
    headlightPath.reset()
    headlightPath.moveTo(cx - 7f, cy - 8f)
    headlightPath.lineTo(cx - 32f, cy - 95f)
    headlightPath.lineTo(cx + 32f, cy - 95f)
    headlightPath.lineTo(cx + 7f, cy - 8f)
    headlightPath.close()

    drawPath(
        path = headlightPath,
        brush = Brush.verticalGradient(
            colors = listOf(Color(0x35E0F2FE), Color(0x00E0F2FE)),
            startY = cy - 8f,
            endY = cy - 95f
        )
    )

    // 3. Halo de energía exterior
    drawCircle(Color(0x3300E5FF), radius = 28f, center = Offset(cx, cy))
    drawCircle(Color(0x6600E5FF), radius = 22f, center = Offset(cx, cy), style = Stroke(width = 1.5f))

    // 4. Facetas 3D de la cabina del camión (con perspectiva e iluminación)
    val leftFacet = paths.leftFacet
    leftFacet.reset()
    leftFacet.moveTo(cx, cy - 22f)
    leftFacet.lineTo(cx - 14f, cy + 12f)
    leftFacet.lineTo(cx, cy + 5f)
    leftFacet.close()
    drawPath(leftFacet, color = Color(0xFF0284C7))

    val rightFacet = paths.rightFacet
    rightFacet.reset()
    rightFacet.moveTo(cx, cy - 22f)
    rightFacet.lineTo(cx + 14f, cy + 12f)
    rightFacet.lineTo(cx, cy + 5f)
    rightFacet.close()
    drawPath(rightFacet, color = Color(0xFF00E5FF))

    // Contorno exterior blanco nítido
    val outlinePath = paths.outlinePath
    outlinePath.reset()
    outlinePath.moveTo(cx, cy - 22f)
    outlinePath.lineTo(cx + 14f, cy + 12f)
    outlinePath.lineTo(cx, cy + 5f)
    outlinePath.lineTo(cx - 14f, cy + 12f)
    outlinePath.close()
    drawPath(outlinePath, color = Color.White, style = Stroke(width = 2.0f, cap = StrokeCap.Round, join = StrokeJoin.Round))

    // Faro frontal central
    drawCircle(Color.White, radius = 3.5f, center = Offset(cx, cy - 20f))
}

private fun DrawScope.drawTruckCursor(cx: Float, cy: Float, rotationDeg: Float, paths: ReusableCanvasPaths) {
    rotate(rotationDeg, pivot = Offset(cx, cy)) {
        val cursorPath = paths.cursorPath
        cursorPath.reset()
        cursorPath.moveTo(cx, cy - 16f)
        cursorPath.lineTo(cx + 10f, cy + 10f)
        cursorPath.lineTo(cx, cy + 5f)
        cursorPath.lineTo(cx - 10f, cy + 10f)
        cursorPath.close()

        drawCircle(Color(0x550284C7), radius = 20f, center = Offset(cx, cy))
        drawPath(cursorPath, color = Color(0xFF38BDF8))
        drawPath(cursorPath, color = Color.White, style = Stroke(width = 1.8f))
    }
}

private fun DrawScope.drawEmptyState() {
    drawIntoCanvas { canvas ->
        val paint = android.graphics.Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = 15f * density
            textAlign = android.graphics.Paint.Align.CENTER
            isAntiAlias = true
        }
        canvas.nativeCanvas.drawText("Descargando mapa vial desde la PC...", size.width / 2f, size.height / 2f, paint)
    }
}
