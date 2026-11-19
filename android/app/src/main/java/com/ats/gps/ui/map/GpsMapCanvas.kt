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

// Clases de caché internas normales (NO snapshot states) para evitar excepciones en Compose DrawScope
private class Viewport2DCache {
    var edges: List<MapEdge> = emptyList()
    var lastX: Float = Float.NaN
    var lastZ: Float = Float.NaN
    var lastScale: Float = 1.0f
}

private class Viewport3DCache {
    var edges: List<MapEdge> = emptyList()
    var lastX: Float = Float.NaN
    var lastZ: Float = Float.NaN
}

@Composable
fun GpsMapCanvas(
    graph: MapGraph?,
    telemetry: TelemetryData,
    activeRoute: RouteResult?,
    viewMode: MapViewMode = MapViewMode.MODE_3D_PERSPECTIVE,
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

    // Objetos de caché normales (sin Snapshot Mutation inside Canvas)
    val cache2D = remember { Viewport2DCache() }
    val cache3D = remember { Viewport3DCache() }

    // Pinturas cacheadas para texto y POIs
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

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0A0F1D))
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

        val snap = graph.snapToRoad(
            x = smoothTruckX,
            z = smoothTruckZ,
            headingDeg = smoothHeading,
            preferredEdges = activeRoute?.edgePath,
            maxDistMeters = 26f
        )
        val truckX = snap?.snappedX ?: smoothTruckX
        val truckY = smoothTruckY
        val truckZ = snap?.snappedZ ?: smoothTruckZ
        val headingDeg = smoothHeading

        when (viewMode) {
            MapViewMode.MODE_2D_TOP_DOWN -> {
                val cx = size.width / 2f
                val cy = size.height * 0.62f
                val baseScale = (0.75f * zoomScale).coerceIn(0.05f, 6.0f)

                val viewCenterX = truckX - (if (!isFollowingTruck) panOffset.x / baseScale else 0f)
                val viewCenterZ = truckZ - (if (!isFollowingTruck) panOffset.y / baseScale else 0f)
                val viewRadius = (max(size.width, size.height) / baseScale) * 1.5f

                // Comprobación de caché de carreteras 2D
                val distFromLast2D = if (cache2D.lastX.isNaN()) Float.MAX_VALUE else {
                    val qx = viewCenterX - cache2D.lastX
                    val qz = viewCenterZ - cache2D.lastZ
                    qx * qx + qz * qz
                }
                val scaleRatio = abs(baseScale - cache2D.lastScale) / cache2D.lastScale

                if (distFromLast2D > 625f || scaleRatio > 0.08f || cache2D.edges.isEmpty()) {
                    cache2D.edges = graph.getEdgesNear(viewCenterX, viewCenterZ, viewRadius * 1.25f)
                    cache2D.lastX = viewCenterX
                    cache2D.lastZ = viewCenterZ
                    cache2D.lastScale = baseScale
                }

                drawMap2D(
                    graph = graph,
                    visibleEdges = cache2D.edges,
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
                    snap = snap,
                    zoomScale = zoomScale,
                    isFollowingTruck = isFollowingTruck,
                    cityPaint = cityPaint,
                    poiIconPaint = poiIconPaint,
                    poiLabelPaint = poiLabelPaint
                )
            }
            MapViewMode.MODE_3D_PERSPECTIVE -> {
                val distFromLast3D = if (cache3D.lastX.isNaN()) Float.MAX_VALUE else {
                    val qx = truckX - cache3D.lastX
                    val qz = truckZ - cache3D.lastZ
                    qx * qx + qz * qz
                }
                if (distFromLast3D > 225f || cache3D.edges.isEmpty()) {
                    cache3D.edges = graph.getEdgesNear(truckX, truckZ, 1400f)
                    cache3D.lastX = truckX
                    cache3D.lastZ = truckZ
                }

                // Controlador de zoom dinamico en funcion de la velocidad:
                // A velocidad alta (autopista) la cámara se eleva y amplía el campo visual; a baja velocidad se acerca.
                val speedKmh = telemetry.speedKmh.coerceAtLeast(0f)
                val speedZoomRatio = (speedKmh / 90f).coerceIn(0f, 1f)
                val dynamicZoom = zoomScale * (0.85f + speedZoomRatio * 0.35f)

                drawMap3D(
                    graph = graph,
                    nearbyEdges = cache3D.edges,
                    truckX = truckX,
                    truckY = truckY,
                    truckZ = truckZ,
                    headingDeg = headingDeg,
                    activeRoute = activeRoute,
                    snap = snap,
                    zoomScale = dynamicZoom,
                    poiIconPaint = poiIconPaint,
                    poiLabelPaint = poiLabelPaint
                )
            }
        }
    }
}

// =====================================================================
// SUAVIZADO DE CURVAS, ENCADENADO DE VÍAS Y RECORTE DE RUTA ACTIVA
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

private fun buildSmoothActiveRoutePoints(
    activeRoute: RouteResult,
    truckX: Float,
    truckZ: Float,
    snap: RoadSnapResult?
): List<Point3D> {
    if (activeRoute.edgePath.isEmpty()) return emptyList()

    val rawPoints = ArrayList<Point3D>(activeRoute.edgePath.size * 4)
    var snapStartIdx = -1
    var snapEndIdx = -1
    val snapEdgeId = snap?.edge?.edgeId

    for (edge in activeRoute.edgePath) {
        val poly = edge.polyline
        if (poly.isEmpty()) continue
        val edgeBeginIdx = (rawPoints.size - 1).coerceAtLeast(0)
        for (pt in poly) {
            if (rawPoints.isNotEmpty()) {
                val prev = rawPoints.last()
                val dx = pt.x - prev.x
                val dz = pt.z - prev.z
                if (dx * dx + dz * dz < 0.04f) continue
            }
            rawPoints.add(pt)
        }
        if (snapEdgeId != null && edge.edgeId == snapEdgeId && snapStartIdx == -1 && rawPoints.size > edgeBeginIdx) {
            snapStartIdx = edgeBeginIdx
            snapEndIdx = rawPoints.size - 1
        }
    }

    if (rawPoints.size < 2) return rawPoints

    fun findClosestInRange(startIdx: Int, endIdx: Int): Triple<Int, Float, Pair<Float, Float>> {
        var bestIdx = -1
        var bestDistSq = Float.MAX_VALUE
        var bestProjX = 0f
        var bestProjZ = 0f
        val lastSeg = (endIdx - 1).coerceAtMost(rawPoints.size - 2)
        for (i in startIdx..lastSeg) {
            val p1 = rawPoints[i]
            val p2 = rawPoints[i + 1]
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
                bestProjX = px
                bestProjZ = pz
            }
        }
        return Triple(bestIdx, bestDistSq, Pair(bestProjX, bestProjZ))
    }

    var bestMatch = if (snapStartIdx >= 0 && snapEndIdx > snapStartIdx) {
        findClosestInRange(snapStartIdx, snapEndIdx)
    } else {
        Triple(-1, Float.MAX_VALUE, Pair(0f, 0f))
    }

    if (bestMatch.first < 0 || bestMatch.second > 3600f) {
        bestMatch = findClosestInRange(0, rawPoints.size - 1)
    }

    val trimmed = if (bestMatch.first >= 0 && bestMatch.second <= 3600f) {
        val segIdx = bestMatch.first
        val projX = bestMatch.third.first
        val projZ = bestMatch.third.second
        ArrayList<Point3D>(rawPoints.size - segIdx).apply {
            add(Point3D(projX, 0f, projZ))
            for (i in (segIdx + 1) until rawPoints.size) {
                val pt = rawPoints[i]
                if (size == 1) {
                    val dx = pt.x - projX
                    val dz = pt.z - projZ
                    if (dx * dx + dz * dz < 0.04f) continue
                }
                add(pt)
            }
        }
    } else {
        rawPoints
    }

    if (trimmed.size < 2) return trimmed

    var fgDist = 0f
    var fgEndIdx = 1
    while (fgEndIdx < trimmed.size && fgEndIdx < 120 && fgDist < 1200f) {
        val a = trimmed[fgEndIdx - 1]
        val b = trimmed[fgEndIdx]
        val dx = b.x - a.x
        val dz = b.z - a.z
        fgDist += sqrt(dx * dx + dz * dz)
        fgEndIdx++
    }

    val fgRaw = trimmed.subList(0, fgEndIdx)
    val fgSub = subdivideLongSegments(fgRaw, 6f)
    val fgSmooth = smoothPolyline(fgSub, iterations = 2)

    if (fgEndIdx >= trimmed.size) {
        return fgSmooth
    }

    val result = ArrayList<Point3D>(fgSmooth.size + (trimmed.size - fgEndIdx))
    result.addAll(fgSmooth)
    for (i in fgEndIdx until trimmed.size) {
        result.add(trimmed[i])
    }
    return result
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

// =====================================================================
// RENDERIZADOR 2D (VISTA AÉREA TÁCTICA CON CINTAS CONTINUAS Y JERARQUÍA VIAL)
// =====================================================================
private fun DrawScope.drawMap2D(
    graph: MapGraph,
    visibleEdges: List<MapEdge>,
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
    snap: RoadSnapResult?,
    zoomScale: Float,
    isFollowingTruck: Boolean,
    cityPaint: android.graphics.Paint,
    poiIconPaint: android.graphics.Paint,
    poiLabelPaint: android.graphics.Paint
) {
    val activeRouteEdgeIds = if (activeRoute != null && activeRoute.edgePath.isNotEmpty()) {
        HashSet<Int>(activeRoute.edgePath.size * 2).apply {
            for (e in activeRoute.edgePath) add(e.edgeId)
        }
    } else null

    // Clasificar aristas por tipo de vía filtrando micro-conectores internos de prefab
    val hwyEdges = ArrayList<MapEdge>()
    val mainEdges = ArrayList<MapEdge>()
    val localEdges = ArrayList<MapEdge>()
    val rampEdges = ArrayList<MapEdge>()

    for (edge in visibleEdges) {
        if (edge.polyline.size < 2) continue
        val isRouteEdge = activeRouteEdgeIds?.contains(edge.edgeId) == true
        if (!isRouteEdge && edge.roadType.toInt() == 3 && edge.length < 18f) continue

        when (edge.roadType.toInt()) {
            0 -> hwyEdges.add(edge)
            1 -> mainEdges.add(edge)
            2 -> localEdges.add(edge)
            else -> rampEdges.add(edge)
        }
    }

    val rampPath = chainEdgesToPaths(rampEdges, viewCenterX, viewCenterZ, cx, cy, baseScale)
    val localPath = chainEdgesToPaths(localEdges, viewCenterX, viewCenterZ, cx, cy, baseScale)
    val mainPath = chainEdgesToPaths(mainEdges, viewCenterX, viewCenterZ, cx, cy, baseScale)
    val hwyPath = chainEdgesToPaths(hwyEdges, viewCenterX, viewCenterZ, cx, cy, baseScale)

    val rampW = getRoadStrokeWidth(3, zoomScale)
    val localW = getRoadStrokeWidth(2, zoomScale)
    val mainW = getRoadStrokeWidth(1, zoomScale)
    val hwyW = getRoadStrokeWidth(0, zoomScale)

    // GLOBAL PASS 1: Todos los bordes exteriores (evita cruces negros internos en intersecciones)
    val borderColor = Color(0xFF0B1017)
    if (rampPath != null) drawPath(rampPath, borderColor, style = Stroke(rampW + 4f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    if (localPath != null) drawPath(localPath, borderColor, style = Stroke(localW + 4f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    if (mainPath != null) drawPath(mainPath, borderColor, style = Stroke(mainW + 4f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    if (hwyPath != null) drawPath(hwyPath, borderColor, style = Stroke(hwyW + 4f, cap = StrokeCap.Round, join = StrokeJoin.Round))

    // GLOBAL PASS 2: Todos los rellenos de asfalto unificados encima
    val streetFill = Color(0xFF2B3846)
    val hwyFill = Color(0xFF334354)
    if (rampPath != null) drawPath(rampPath, streetFill, style = Stroke(rampW, cap = StrokeCap.Round, join = StrokeJoin.Round))
    if (localPath != null) drawPath(localPath, streetFill, style = Stroke(localW, cap = StrokeCap.Round, join = StrokeJoin.Round))
    if (mainPath != null) drawPath(mainPath, streetFill, style = Stroke(mainW, cap = StrokeCap.Round, join = StrokeJoin.Round))
    if (hwyPath != null) drawPath(hwyPath, hwyFill, style = Stroke(hwyW, cap = StrokeCap.Round, join = StrokeJoin.Round))

    // 2. Ruta activa GPS (Cinta continua suave con resplandor y contorno)
    if (activeRoute != null && activeRoute.edgePath.isNotEmpty()) {
        val routePoints = buildSmoothActiveRoutePoints(activeRoute, truckX, truckZ, snap)
        if (routePoints.size >= 2) {
            val routePath = Path()
            var hasPoints = false
            for (pt in routePoints) {
                val sx = cx + (pt.x - viewCenterX) * baseScale
                val sy = cy + (pt.z - viewCenterZ) * baseScale
                if (sx.isNaN() || sy.isNaN()) continue
                if (!hasPoints) {
                    routePath.moveTo(sx, sy)
                    hasPoints = true
                } else {
                    routePath.lineTo(sx, sy)
                }
            }

            if (hasPoints) {
                val rw = 16f * zoomScale.coerceIn(0.4f, 2.2f)
                drawPath(
                    path = routePath,
                    color = Color(0x6600E5FF),
                    style = Stroke(width = rw * 1.55f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
                drawPath(
                    path = routePath,
                    color = Color(0xFF0369A1),
                    style = Stroke(width = rw + 2.5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
                drawPath(
                    path = routePath,
                    color = Color(0xFF00E5FF),
                    style = Stroke(width = rw, cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
            }
        }
    }

    // 3. Ciudades y POIs dibujados ERGUIDOS (Sin rotación invertida) para máxima legibilidad táctica
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

        drawPoiMarker2D(poi, sx, sy, zoomScale, poiIconPaint, poiLabelPaint)
    }

    // 4. Cursor táctico del camión: rota en tiempo real siguiendo el rumbo del vehículo sobre la vía
    if (isFollowingTruck) {
        drawTruckCursor(cx, cy, headingDeg)
    } else {
        val truckSx = cx + (truckX - viewCenterX) * baseScale
        val truckSy = cy + (truckZ - viewCenterZ) * baseScale
        if (!truckSx.isNaN() && !truckSy.isNaN()) {
            drawTruckCursor(truckSx, truckSy, headingDeg)
        }
    }
}

private fun chainEdgesToPaths(
    edges: List<MapEdge>,
    viewCenterX: Float,
    viewCenterZ: Float,
    cx: Float,
    cy: Float,
    scale: Float
): Path? {
    if (edges.isEmpty()) return null
    val chains = chainEdges(edges)
    if (chains.isEmpty()) return null

    val path = Path()
    var hasSegments = false
    var lastWx = Float.NaN
    var lastWz = Float.NaN

    for (chain in chains) {
        val smoothed = smoothPolyline(chain, iterations = 1)
        if (smoothed.size < 2) continue

        for (i in smoothed.indices) {
            val p = smoothed[i]
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
                hasSegments = true
            }
            lastWx = p.x
            lastWz = p.z
        }
    }
    return if (hasSegments) path else null
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
    labelPaint: android.graphics.Paint
) {
    val (bgColor, iconText, defaultLabel) = poiMarkerStyle(poi.poiType.toInt())

    val radius = 13f * density // 36-40 píxeles táctiles de alto contraste

    // Sombra del marcador
    drawCircle(Color(0x88000000), radius = radius + 3f, center = Offset(sx, sy + 2f))
    // Círculo coloreado según categoría
    drawCircle(bgColor, radius = radius, center = Offset(sx, sy))
    // Borde blanco nítido
    drawCircle(Color.White, radius = radius, center = Offset(sx, sy), style = Stroke(width = 2.2f))

    val labelText = if (poi.name.isNotEmpty()) poi.name else defaultLabel

    drawIntoCanvas { canvas ->
        iconPaint.color = android.graphics.Color.WHITE
        iconPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        iconPaint.textSize = radius * 0.72f
        canvas.nativeCanvas.drawText(iconText, sx, sy + radius * 0.25f, iconPaint)

        // Etiqueta con pastilla oscura debajo del icono (siempre erguida y legible)
        if (zoomScale >= 0.45f) {
            labelPaint.textSize = 9.5f * density
            val textWidth = labelPaint.measureText(labelText)
            val pillPad = 4f * density
            val pTop = sy + radius + 3f
            val pBottom = pTop + 14f * density
            val pLeft = sx - textWidth / 2f - pillPad
            val pRight = sx + textWidth / 2f + pillPad

            val bgPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.argb(220, 15, 23, 42)
                isAntiAlias = true
            }
            canvas.nativeCanvas.drawRoundRect(pLeft, pTop, pRight, pBottom, 6f, 6f, bgPaint)
            canvas.nativeCanvas.drawText(labelText, sx, pBottom - 3.5f * density, labelPaint)
        }
    }
}

private data class ProjectedRoadSeg3D(
    val p1: Offset,
    val p2: Offset,
    val casingW: Float,
    val depthRatio: Float,
    val isFreeway: Boolean,
    val lanes: Int
)

private data class ProjectedRouteSeg3D(
    val p1: Offset,
    val p2: Offset,
    val rWidth: Float
)

// =====================================================================
// RENDERIZADOR 3D EN PERSPECTIVA
// =====================================================================
private fun DrawScope.drawMap3D(
    graph: MapGraph,
    nearbyEdges: List<MapEdge>,
    truckX: Float,
    truckY: Float,
    truckZ: Float,
    headingDeg: Float,
    activeRoute: RouteResult?,
    snap: RoadSnapResult?,
    zoomScale: Float,
    poiIconPaint: android.graphics.Paint,
    poiLabelPaint: android.graphics.Paint
) {
    val w = size.width
    val h = size.height

    // Parametros opticos de proyeccion de camara 3D:
    // horizonH: 26% de la altura, camDist: 65m, focal: 440f
    val horizonY = h * 0.26f
    val targetTruckY = h * 0.78f
    val camDist3D = 65f
    val focal3D = 440f * zoomScale.coerceIn(0.6f, 2.0f)
    val camHeight3D = (targetTruckY - horizonY) / (focal3D / camDist3D)

    // 1. Cielo crepuscular con degradado atmosférico Figma
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(
                Color(0xFF030508), // Espacio profundo superior
                Color(0xFF080F1D), // Azul noche
                Color(0xFF112238), // Resplandor crepuscular en el horizonte
                Color(0xFF1A3350)
            ),
            startY = 0f,
            endY = horizonY
        ),
        topLeft = Offset.Zero,
        size = Size(w, horizonY)
    )

    // Resplandor atmosferico en la linea de horizonte
    drawRect(
        brush = Brush.verticalGradient(
            listOf(Color(0xFF00E5FF).copy(alpha = 0.35f), Color.Transparent),
            startY = horizonY - 4f,
            endY = horizonY + 28f
        ),
        topLeft = Offset(0f, horizonY - 4f),
        size = Size(w, 32f)
    )

    // 2. Terreno oscuro de conducción (#0C1013 Figma)
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

    // Rotación del mundo según rumbo real (Heading)
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

    val activeRouteEdgeIds = if (activeRoute != null && activeRoute.edgePath.isNotEmpty()) {
        HashSet<Int>(activeRoute.edgePath.size * 2).apply {
            for (e in activeRoute.edgePath) add(e.edgeId)
        }
    } else null

    clipRect(0f, horizonY, w, h) {
        // 3. Rejilla virtual de perspectiva en el suelo
        drawGroundGrid3D(truckX, truckZ) { x1, z1, x2, z2 ->
            projectSegment3D(x1, z1, x2, z2)
        }

        // 4. Red Vial Real de ATS suavizada y renderizada en pases globales por capa
        val groupedEdges = HashMap<Int, MutableList<MapEdge>>()
        for (edge in nearbyEdges) {
            if (edge.polyline.size < 2) continue
            val isRouteEdge = activeRouteEdgeIds?.contains(edge.edgeId) == true
            if (!isRouteEdge && edge.roadType.toInt() == 3 && edge.length < 18f) continue

            val isFreeway = edge.roadType.toInt() == 0
            val lanes = edge.lanes.toInt().coerceAtLeast(1)
            val key = (if (isFreeway) 100 else 0) + lanes
            groupedEdges.getOrPut(key) { ArrayList() }.add(edge)
        }

        val projectedRoads = ArrayList<ProjectedRoadSeg3D>(nearbyEdges.size * 3)
        for ((key, group) in groupedEdges) {
            val isFreeway = key >= 100
            val lanes = if (isFreeway) key - 100 else key
            val laneWidthFactor = 1.0f + (lanes - 1) * 0.38f

            val chains = chainEdges(group)
            for (chain in chains) {
                val sub = subdivideForegroundSegments(chain, truckX, truckZ, radiusSq = 122500f, maxSegLenMeters = 8f)
                val smoothed = smoothPolyline(sub, iterations = 1)
                if (smoothed.size < 2) continue

                for (i in 0 until smoothed.size - 1) {
                    val p0 = smoothed[i]
                    val p1 = smoothed[i + 1]
                    val seg = projectSegment3D(p0.x, p0.z, p1.x, p1.z) ?: continue
                    val midY = (seg.first.y + seg.second.y) * 0.5f
                    val depthRatio = ((midY - horizonY) / (targetTruckY - horizonY)).coerceIn(0.08f, 1.4f)
                    val casingW = if (isFreeway) {
                        (24f * depthRatio * laneWidthFactor).coerceIn(3.5f, 38f)
                    } else {
                        (14f * depthRatio * laneWidthFactor).coerceIn(2.0f, 22f)
                    }
                    projectedRoads.add(ProjectedRoadSeg3D(seg.first, seg.second, casingW, depthRatio, isFreeway, lanes))
                }
            }
        }

        // Ordenar de atrás hacia adelante para composición limpia
        projectedRoads.sortBy { it.p1.y + it.p2.y }

        // PASS 1: Todos los bordes exteriores
        val roadBorderColor = Color(0xFF0A0F18)
        for (seg in projectedRoads) {
            drawLine(roadBorderColor, seg.p1, seg.p2, strokeWidth = seg.casingW + 3f, cap = StrokeCap.Round)
        }

        // PASS 2: Todos los rellenos de asfalto
        val hwyAsphalt = Color(0xFF26323F)
        val otherAsphalt = Color(0xFF1F2A35)
        for (seg in projectedRoads) {
            drawLine(
                color = if (seg.isFreeway) hwyAsphalt else otherAsphalt,
                start = seg.p1,
                end = seg.p2,
                strokeWidth = seg.casingW,
                cap = StrokeCap.Round
            )
        }

        // PASS 3: Líneas divisorias de carriles
        val dividerColor = Color(0x5594A3B8)
        for (seg in projectedRoads) {
            if (seg.lanes >= 2 && seg.depthRatio > 0.35f) {
                val dividerW = (1.5f * seg.depthRatio).coerceIn(0.8f, 2.5f)
                drawLine(dividerColor, seg.p1, seg.p2, strokeWidth = dividerW, cap = StrokeCap.Round)
            }
        }

        // 5. Ruta de Navegación Activa GPS en 4 pases globales separados
        if (activeRoute != null && activeRoute.edgePath.isNotEmpty()) {
            val smoothRoute = buildSmoothActiveRoutePoints(activeRoute, truckX, truckZ, snap)
            if (smoothRoute.size >= 2) {
                val projectedRoute = ArrayList<ProjectedRouteSeg3D>(smoothRoute.size)
                for (i in 0 until smoothRoute.size - 1) {
                    val p0 = smoothRoute[i]
                    val p1 = smoothRoute[i + 1]
                    val seg = projectSegment3D(p0.x, p0.z, p1.x, p1.z) ?: continue
                    val midY = (seg.first.y + seg.second.y) * 0.5f
                    val depthRatio = ((midY - horizonY) / (targetTruckY - horizonY)).coerceIn(0.10f, 1.35f)
                    val rWidth = (28f * depthRatio).coerceIn(4.5f, 36f)
                    projectedRoute.add(ProjectedRouteSeg3D(seg.first, seg.second, rWidth))
                }

                // Capa 1: Halo difuso
                val haloColor = Color(0xFF00E5FF).copy(alpha = 0.28f)
                for (seg in projectedRoute) {
                    drawLine(haloColor, seg.p1, seg.p2, strokeWidth = seg.rWidth * 1.55f, cap = StrokeCap.Round)
                }
                // Capa 2: Contorno azul oscuro
                val routeCasingColor = Color(0xFF0284C7)
                for (seg in projectedRoute) {
                    drawLine(routeCasingColor, seg.p1, seg.p2, strokeWidth = seg.rWidth + 2.5f, cap = StrokeCap.Round)
                }
                // Capa 3: Haz principal neón cyan brillante
                val routeCyanColor = Color(0xFF00E5FF)
                for (seg in projectedRoute) {
                    drawLine(routeCyanColor, seg.p1, seg.p2, strokeWidth = seg.rWidth, cap = StrokeCap.Round)
                }
                // Capa 4: Línea blanca central reflectante
                val routeCoreColor = Color.White.copy(alpha = 0.85f)
                for (seg in projectedRoute) {
                    drawLine(routeCoreColor, seg.p1, seg.p2, strokeWidth = (seg.rWidth * 0.24f).coerceAtLeast(1.2f), cap = StrokeCap.Round)
                }
            }
        }
    }

    // 6. POIs en Perspectiva 3D (Gasolineras, Zonas de Descanso, Talleres)
    for (poi in graph.pois) {
        val dx = poi.x - truckX
        val dz = poi.z - truckZ
        if (dx * dx + dz * dz > 1000000f) continue // 1000m

        val sp = projectPoint3D(poi.x, poi.z) ?: continue
        if (sp.y in (horizonY + 12f)..(h + 60f) && sp.x in -60f..(w + 60f)) {
            val depthRatio = ((sp.y - horizonY) / (targetTruckY - horizonY)).coerceIn(0.35f, 1.25f)
            drawPoiMarker3D(poi, sp.x, sp.y - 10f * depthRatio, depthRatio, poiIconPaint, poiLabelPaint)
        }
    }

    // 7. Cursor 3D del camión con haz de luz de faros
    drawTruckCursor3D(w * 0.5f, targetTruckY)
}

private fun DrawScope.drawPoiMarker3D(
    poi: MapPoi,
    sx: Float,
    sy: Float,
    depthRatio: Float,
    iconPaint: android.graphics.Paint,
    labelPaint: android.graphics.Paint
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

            val bgPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.argb(210, 15, 23, 42)
                isAntiAlias = true
            }
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

private fun DrawScope.drawTruckCursor3D(cx: Float, cy: Float) {
    // 1. Sombra de contacto suave en el asfalto
    drawOval(
        color = Color(0x77000000),
        topLeft = Offset(cx - 20f, cy + 4f),
        size = Size(40f, 16f)
    )

    // 2. Haz de luz proyectado hacia adelante (Faros del camión sobre la carretera)
    val headlightPath = Path().apply {
        moveTo(cx - 7f, cy - 8f)
        lineTo(cx - 32f, cy - 95f)
        lineTo(cx + 32f, cy - 95f)
        lineTo(cx + 7f, cy - 8f)
        close()
    }
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
    // Faceta izquierda (Sombra azulada)
    val leftFacet = Path().apply {
        moveTo(cx, cy - 22f)
        lineTo(cx - 14f, cy + 12f)
        lineTo(cx, cy + 5f)
        close()
    }
    drawPath(leftFacet, color = Color(0xFF0284C7))

    // Faceta derecha (Iluminada cian)
    val rightFacet = Path().apply {
        moveTo(cx, cy - 22f)
        lineTo(cx + 14f, cy + 12f)
        lineTo(cx, cy + 5f)
        close()
    }
    drawPath(rightFacet, color = Color(0xFF00E5FF))

    // Contorno exterior blanco nítido
    val outlinePath = Path().apply {
        moveTo(cx, cy - 22f)
        lineTo(cx + 14f, cy + 12f)
        lineTo(cx, cy + 5f)
        lineTo(cx - 14f, cy + 12f)
        close()
    }
    drawPath(outlinePath, color = Color.White, style = Stroke(width = 2.0f, cap = StrokeCap.Round, join = StrokeJoin.Round))

    // Faro frontal central
    drawCircle(Color.White, radius = 3.5f, center = Offset(cx, cy - 20f))
}

private fun DrawScope.drawTruckCursor(cx: Float, cy: Float, rotationDeg: Float) {
    rotate(rotationDeg, pivot = Offset(cx, cy)) {
        val cursorPath = Path().apply {
            moveTo(cx, cy - 16f)
            lineTo(cx + 10f, cy + 10f)
            lineTo(cx, cy + 5f)
            lineTo(cx - 10f, cy + 10f)
            close()
        }
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
