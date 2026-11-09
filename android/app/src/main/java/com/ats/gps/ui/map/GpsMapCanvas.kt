package com.ats.gps.ui.map

import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import com.ats.gps.data.map.RouteResult
import com.ats.gps.data.model.MapEdge
import com.ats.gps.data.model.MapGraph
import com.ats.gps.data.model.MapPoi
import com.ats.gps.data.model.Point3D
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
    isFollowingTruck: Boolean = true,
    modifier: Modifier = Modifier
) {
    val currentZoom by rememberUpdatedState(zoomScale)
    val currentPan by rememberUpdatedState(panOffset)
    val currentOnZoom by rememberUpdatedState(onZoomChange)
    val currentOnPan by rememberUpdatedState(onPanChange)

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

        val truckX = smoothTruckX
        val truckY = smoothTruckY
        val truckZ = smoothTruckZ
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
                    cache3D.edges = graph.getEdgesNear(truckX, truckZ, 900f)
                    cache3D.lastX = truckX
                    cache3D.lastZ = truckZ
                }

                drawMap3D(
                    nearbyEdges = cache3D.edges,
                    truckX = truckX,
                    truckY = truckY,
                    truckZ = truckZ,
                    headingDeg = headingDeg,
                    activeRoute = activeRoute,
                    zoomScale = zoomScale
                )
            }
        }
    }
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
    zoomScale: Float,
    isFollowingTruck: Boolean,
    cityPaint: android.graphics.Paint,
    poiIconPaint: android.graphics.Paint,
    poiLabelPaint: android.graphics.Paint
) {
    val rotationAngle = if (isFollowingTruck) -headingDeg else 0f

    rotate(rotationAngle, pivot = Offset(cx, cy)) {
        // Encontrar la carretera actual más cercana al camión
        var closestEdgeId = -1
        var minEdgeDistSq = 1600f // Máximo 40 metros
        if (isFollowingTruck) {
            for (edge in visibleEdges) {
                if (edge.polyline.isEmpty()) continue
                val p0 = edge.polyline[0]
                val pEnd = edge.polyline[edge.polyline.size - 1]
                val d0 = (p0.x - truckX) * (p0.x - truckX) + (p0.z - truckZ) * (p0.z - truckZ)
                val dEnd = (pEnd.x - truckX) * (pEnd.x - truckX) + (pEnd.z - truckZ) * (pEnd.z - truckZ)
                if (d0 < 3600f || dEnd < 3600f) {
                    for (p in edge.polyline) {
                        val dSq = (p.x - truckX) * (p.x - truckX) + (p.z - truckZ) * (p.z - truckZ)
                        if (dSq < minEdgeDistSq) {
                            minEdgeDistSq = dSq
                            closestEdgeId = edge.edgeId
                        }
                    }
                }
            }
        }

        // Clasificar aristas por tipo de vía
        val hwyEdges = ArrayList<MapEdge>()
        val mainEdges = ArrayList<MapEdge>()
        val localEdges = ArrayList<MapEdge>()
        val rampEdges = ArrayList<MapEdge>()
        var currentRoadEdge: MapEdge? = null

        for (edge in visibleEdges) {
            if (edge.polyline.size < 2) continue
            if (edge.edgeId == closestEdgeId && minEdgeDistSq < 1600f) {
                currentRoadEdge = edge
            } else {
                when (edge.roadType.toInt()) {
                    0 -> hwyEdges.add(edge)
                    1 -> mainEdges.add(edge)
                    2 -> localEdges.add(edge)
                    else -> rampEdges.add(edge)
                }
            }
        }

        // Dibujar capas en orden: Rampas -> Locales -> Principales -> Autopistas
        drawContinuousRoadCategory(rampEdges, viewCenterX, viewCenterZ, cx, cy, baseScale, 3, zoomScale)
        drawContinuousRoadCategory(localEdges, viewCenterX, viewCenterZ, cx, cy, baseScale, 2, zoomScale)
        drawContinuousRoadCategory(mainEdges, viewCenterX, viewCenterZ, cx, cy, baseScale, 1, zoomScale)
        drawContinuousRoadCategory(hwyEdges, viewCenterX, viewCenterZ, cx, cy, baseScale, 0, zoomScale)

        // Carretera actual resaltada en Cyan de navegación
        if (currentRoadEdge != null) {
            val highlightPath = buildConnectedPath(listOf(currentRoadEdge), viewCenterX, viewCenterZ, cx, cy, baseScale)
            val strokeW = getRoadStrokeWidth(currentRoadEdge.roadType.toInt(), zoomScale)
            drawPath(highlightPath, Color(0xFF0F172A), style = Stroke(strokeW + 5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(highlightPath, Color(0xFF38BDF8), style = Stroke(strokeW + 1f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }

        // 2. Ruta activa GPS (Cinta continua suave con resplandor)
        if (activeRoute != null && activeRoute.edgePath.isNotEmpty()) {
            val routePath = Path()
            var hasPoints = false
            var lastPx = Float.NaN
            var lastPz = Float.NaN

            for (edge in activeRoute.edgePath) {
                for (pt in edge.polyline) {
                    if (!lastPx.isNaN() && abs(pt.x - lastPx) < 0.05f && abs(pt.z - lastPz) < 0.05f) {
                        continue
                    }
                    lastPx = pt.x
                    lastPz = pt.z

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
                    color = Color(0xFF00E5FF),
                    style = Stroke(width = rw, cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
            }
        }

        // 3. Ciudades
        for (city in graph.cities) {
            val dx = city.x - viewCenterX
            val dz = city.z - viewCenterZ
            if (dx * dx + dz * dz > (viewRadius * 1.5f) * (viewRadius * 1.5f)) continue

            val sx = cx + dx * baseScale
            val sy = cy + dz * baseScale
            if (sx.isNaN() || sy.isNaN()) continue

            drawCircle(Color(0xFF38BDF8), radius = 6f * zoomScale.coerceIn(0.6f, 1.5f), center = Offset(sx, sy))

            drawIntoCanvas { canvas ->
                cityPaint.textSize = (13f * zoomScale.coerceIn(0.8f, 1.4f)).coerceAtLeast(10f) * density
                canvas.nativeCanvas.drawText(city.name, sx + 8f, sy + 4f, cityPaint)
            }
        }

        // 4. POIs
        val poiDrawRadiusSq = (viewRadius * 1.15f) * (viewRadius * 1.15f)
        for (poi in graph.pois) {
            val dx = poi.x - viewCenterX
            val dz = poi.z - viewCenterZ
            if (dx * dx + dz * dz > poiDrawRadiusSq) continue

            val sx = cx + dx * baseScale
            val sy = cy + dz * baseScale
            if (sx.isNaN() || sy.isNaN()) continue

            drawPoiMarker2D(poi, sx, sy, baseScale, poiIconPaint, poiLabelPaint)
        }
    }

    // 5. Cursor táctico del camión
    if (isFollowingTruck) {
        drawTruckCursor(cx, cy, 0f)
    } else {
        val truckSx = cx + (truckX - viewCenterX) * baseScale
        val truckSy = cy + (truckZ - viewCenterZ) * baseScale
        if (!truckSx.isNaN() && !truckSy.isNaN()) {
            drawTruckCursor(truckSx, truckSy, headingDeg)
        }
    }
}

private fun DrawScope.drawContinuousRoadCategory(
    edges: List<MapEdge>,
    viewCenterX: Float,
    viewCenterZ: Float,
    cx: Float,
    cy: Float,
    scale: Float,
    roadType: Int,
    zoomScale: Float
) {
    if (edges.isEmpty()) return

    val strokeW = getRoadStrokeWidth(roadType, zoomScale)
    val borderColor = when (roadType) {
        0 -> Color(0xFF0F172A)
        1 -> Color(0xFF1E293B)
        2 -> Color(0xFF0B1120)
        else -> Color(0xFF1E293B)
    }
    val surfaceColor = when (roadType) {
        0 -> Color(0xFF475569)
        1 -> Color(0xFF334155)
        2 -> Color(0xFF1E293B)
        else -> Color(0xFF3B4A5E)
    }

    val path = buildConnectedPath(edges, viewCenterX, viewCenterZ, cx, cy, scale)

    drawPath(
        path = path,
        color = borderColor,
        style = Stroke(width = strokeW + 4f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    )
    drawPath(
        path = path,
        color = surfaceColor,
        style = Stroke(width = strokeW, cap = StrokeCap.Round, join = StrokeJoin.Round)
    )
}

private fun buildConnectedPath(
    edges: List<MapEdge>,
    viewCenterX: Float,
    viewCenterZ: Float,
    cx: Float,
    cy: Float,
    scale: Float
): Path {
    val path = Path()
    var lastSx = Float.NaN
    var lastSy = Float.NaN

    for (edge in edges) {
        val poly = edge.polyline
        if (poly.size < 2) continue

        for (i in poly.indices) {
            val p = poly[i]
            val sx = cx + (p.x - viewCenterX) * scale
            val sy = cy + (p.z - viewCenterZ) * scale
            if (sx.isNaN() || sy.isNaN()) continue

            if (i == 0) {
                val isContiguous = !lastSx.isNaN() && (abs(sx - lastSx) < 2.5f && abs(sy - lastSy) < 2.5f)
                if (isContiguous) {
                    path.lineTo(sx, sy)
                } else {
                    path.moveTo(sx, sy)
                }
            } else {
                path.lineTo(sx, sy)
            }
            lastSx = sx
            lastSy = sy
        }
    }
    return path
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

private fun DrawScope.drawPoiMarker2D(
    poi: MapPoi,
    sx: Float,
    sy: Float,
    scale: Float,
    iconPaint: android.graphics.Paint,
    labelPaint: android.graphics.Paint
) {
    val (bgColor, iconText) = when (poi.poiType.toInt()) {
        1 -> Pair(Color(0xFF10B981), "⛽")
        2 -> Pair(Color(0xFF6366F1), "🛏️")
        3 -> Pair(Color(0xFFF59E0B), "🔧")
        4 -> Pair(Color(0xFF8B5CF6), "🏬")
        5 -> Pair(Color(0xFF0EA5E9), "🏢")
        6 -> Pair(Color(0xFF64748B), "⚖️")
        else -> Pair(Color(0xFF475569), "📍")
    }

    val radius = (10f * scale.coerceIn(0.7f, 1.3f)).coerceAtLeast(6f)

    drawCircle(Color(0x55000000), radius = radius + 3f, center = Offset(sx, sy))
    drawCircle(bgColor, radius = radius, center = Offset(sx, sy))
    drawCircle(Color.White, radius = radius, center = Offset(sx, sy), style = Stroke(width = 1.8f))

    drawIntoCanvas { canvas ->
        iconPaint.textSize = (radius * 1.25f).coerceAtLeast(8f)
        val yOffset = radius * 0.45f
        canvas.nativeCanvas.drawText(iconText, sx, sy + yOffset, iconPaint)

        if (scale > 0.7f && poi.name.isNotEmpty()) {
            labelPaint.textSize = (9f * density).coerceAtLeast(8f)
            canvas.nativeCanvas.drawText(poi.name, sx, sy + radius + 11f * density, labelPaint)
        }
    }
}

// =====================================================================
// RENDERIZADOR 3D PERSPECTIVA REAL (GEOMETRÍA Y ORIENTACIÓN MATEMÁTICAMENTE CORREGIDAS)
// =====================================================================
private fun DrawScope.drawMap3D(
    nearbyEdges: List<MapEdge>,
    truckX: Float,
    truckY: Float,
    truckZ: Float,
    headingDeg: Float,
    activeRoute: RouteResult?,
    zoomScale: Float
) {
    val w = size.width
    val h = size.height
    val horizonY = h * 0.28f

    // 1. Cielo crepuscular
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(Color(0xFF050811), Color(0xFF0C1424), Color(0xFF132238)),
            startY = 0f,
            endY = horizonY
        ),
        topLeft = Offset.Zero,
        size = Size(w, horizonY)
    )

    drawLine(Color(0xFF1E3A5F), Offset(0f, horizonY), Offset(w, horizonY), strokeWidth = 2f)

    // 2. Terreno
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(Color(0xFF080D18), Color(0xFF0C1322), Color(0xFF10192A)),
            startY = horizonY,
            endY = h
        ),
        topLeft = Offset(0f, horizonY),
        size = Size(w, h - horizonY)
    )

    // Cámara 3D en tercera persona con inclinación hacia la calzada (Pitch)
    val camHeadingRad = Math.toRadians(headingDeg.toDouble()).toFloat()
    val sinH = sin(camHeadingRad)
    val cosH = cos(camHeadingRad)

    val camFollowDist = 18f
    val camHeight = 10.5f
    val camY = truckY + camHeight

    // Ángulo de inclinación hacia abajo (Pitch ~16°) para desplegar la carretera en perspectiva
    val pitchRad = Math.toRadians(16.0).toFloat()
    val sinP = sin(pitchRad)
    val cosP = cos(pitchRad)

    val focalLength = h * 0.65f * zoomScale.coerceIn(0.5f, 2.5f)
    val viewDistMax = 600f
    val zNear = 1.0f

    // Cámara situada detrás del camión según rumbo
    val camX = truckX - camFollowDist * sinH
    val camZ = truckZ + camFollowDist * cosH

    fun toCam(wx: Float, wz: Float, wy: Float): Triple<Float, Float, Float> {
        val dx = wx - camX
        val dz = wz - camZ
        // Normalizar elevación para evitar que puntos con Y errónea se disparen fuera de la vista
        val clampedY = if (abs(wy - truckY) > 45f) truckY else wy
        val dy = clampedY - camY
        
        // 1. Rumbo (Heading)
        val xH = dx * cosH + dz * sinH
        val zH = dx * sinH - dz * cosH

        // 2. Inclinación hacia abajo (Pitch)
        val xCam = xH
        val yCam = dy * cosP + zH * sinP
        val zCam = -dy * sinP + zH * cosP
        return Triple(xCam, yCam, zCam)
    }

    fun projectCam(xCam: Float, yCam: Float, zCam: Float): Offset {
        val pX = (w * 0.5f) + (xCam * focalLength / zCam)
        val pY = (h * 0.52f) - (yCam * focalLength / zCam)
        return Offset(pX, pY)
    }

    fun project3D(wx: Float, wz: Float, wy: Float = truckY): Offset? {
        val (xCam, yCam, zCam) = toCam(wx, wz, wy)
        if (zCam < zNear || zCam > viewDistMax) return null
        val pY = (h * 0.52f) - (yCam * focalLength / zCam)
        if (pY < horizonY - 40f || pY > h + 300f) return null
        return Offset((w * 0.5f) + (xCam * focalLength / zCam), pY)
    }

    fun drawSegmentClipped(
        pt1: Point3D,
        pt2: Point3D,
        edgeColor: Color,
        roadColor: Color,
        widthMult: Float
    ) {
        val (x1, y1, z1) = toCam(pt1.x, pt1.z, pt1.y)
        val (x2, y2, z2) = toCam(pt2.x, pt2.z, pt2.y)

        if ((z1 < zNear && z2 < zNear) || (z1 > viewDistMax && z2 > viewDistMax)) return

        var cx1 = x1; var cy1 = y1; var cz1 = z1
        var cx2 = x2; var cy2 = y2; var cz2 = z2

        // Recorte plano cercano (Near Plane Clipping)
        if (cz1 < zNear && cz2 > zNear) {
            val t = (zNear - cz1) / (cz2 - cz1)
            cx1 += t * (cx2 - cx1)
            cy1 += t * (cy2 - cy1)
            cz1 = zNear
        } else if (cz2 < zNear && cz1 > zNear) {
            val t = (zNear - cz2) / (cz1 - cz2)
            cx2 += t * (cx1 - cx2)
            cy2 += t * (cy1 - cy2)
            cz2 = zNear
        }

        // Recorte plano lejano (Far Plane Clipping)
        if (cz1 > viewDistMax && cz2 < viewDistMax) {
            val t = (viewDistMax - cz2) / (cz1 - cz2)
            cx1 = cx2 + t * (cx1 - cx2)
            cy1 = cy2 + t * (cy1 - cy2)
            cz1 = viewDistMax
        } else if (cz2 > viewDistMax && cz1 < viewDistMax) {
            val t = (viewDistMax - cz1) / (cz2 - cz1)
            cx2 = cx1 + t * (cx2 - cx1)
            cy2 = cy1 + t * (cy2 - cy1)
            cz2 = viewDistMax
        }

        if (cz1 < zNear || cz2 < zNear) return

        val proj1 = projectCam(cx1, cy1, cz1)
        val proj2 = projectCam(cx2, cy2, cz2)

        val avgDist = (cz1 + cz2) * 0.5f
        val strokeW = (focalLength * widthMult / max(avgDist, 8f)).coerceIn(3f, 85f)

        drawLine(edgeColor, proj1, proj2, strokeWidth = strokeW + 4f, cap = StrokeCap.Round)
        drawLine(roadColor, proj1, proj2, strokeWidth = strokeW, cap = StrokeCap.Round)
    }

    // 3. Rejilla virtual de perspectiva en el suelo
    drawGroundGrid3D(truckX, truckZ, truckY, headingDeg, w, h, horizonY, ::project3D)

    // 4. Cono de faros delanteros
    val truckProj = project3D(truckX, truckZ, truckY) ?: Offset(w * 0.5f, h * 0.72f)
    val headlightPath = Path().apply {
        moveTo(truckProj.x, truckProj.y)
        lineTo(w * 0.22f, horizonY + 25f)
        lineTo(w * 0.78f, horizonY + 25f)
        close()
    }
    drawPath(
        path = headlightPath,
        brush = Brush.verticalGradient(
            colors = listOf(Color(0x00FFFFFF), Color(0x12FFF8E1), Color(0x28FFE082)),
            startY = horizonY + 25f,
            endY = truckProj.y
        )
    )

    // 5. Dibujar Carreteras en 3D
    for (edge in nearbyEdges) {
        val isHwy = edge.roadType.toInt() == 0
        val isRamp = edge.roadType.toInt() == 3
        val roadColor = when {
            isHwy -> Color(0xFF475569)
            isRamp -> Color(0xFF334155)
            else -> Color(0xFF384758)
        }
        val edgeColor = Color(0xFF0F172A)
        val widthMult = when {
            isHwy -> 14f
            isRamp -> 8f
            else -> 10f
        }

        for (i in 0 until edge.polyline.size - 1) {
            drawSegmentClipped(edge.polyline[i], edge.polyline[i + 1], edgeColor, roadColor, widthMult)
        }
    }

    // 6. Dibujar Ruta Activa GPS en 3D
    if (activeRoute != null) {
        for (edge in activeRoute.edgePath) {
            if (edge.polyline.isNotEmpty()) {
                val midP = edge.polyline[edge.polyline.size / 2]
                val dx = midP.x - truckX
                val dz = midP.z - truckZ
                if (dx * dx + dz * dz > 360000f) continue
            }
            for (i in 0 until edge.polyline.size - 1) {
                drawSegmentClipped(edge.polyline[i], edge.polyline[i + 1], Color(0x6600E5FF), Color(0xFF00E5FF), 13f)
            }
        }
    }

    // 7. Cursor 3D del camión
    drawTruckCursor3D(truckProj.x, truckProj.y)
}

private fun DrawScope.drawGroundGrid3D(
    truckX: Float,
    truckZ: Float,
    truckY: Float,
    headingDeg: Float,
    w: Float,
    h: Float,
    horizonY: Float,
    projector: (Float, Float, Float) -> Offset?
) {
    val gridSpacing = 20f
    val gridRange = 140f

    val startX = (floor((truckX - gridRange) / gridSpacing) * gridSpacing)
    val endX = (ceil((truckX + gridRange) / gridSpacing) * gridSpacing)
    val startZ = (floor((truckZ - gridRange) / gridSpacing) * gridSpacing)
    val endZ = (ceil((truckZ + gridRange) / gridSpacing) * gridSpacing)

    var curX = startX
    while (curX <= endX) {
        val p1 = projector(curX, truckZ - gridRange, truckY)
        val p2 = projector(curX, truckZ + gridRange, truckY)
        if (p1 != null && p2 != null) {
            drawLine(Color(0x1838BDF8), p1, p2, strokeWidth = 1.0f)
        }
        curX += gridSpacing
    }

    var curZ = startZ
    while (curZ <= endZ) {
        val p1 = projector(truckX - gridRange, curZ, truckY)
        val p2 = projector(truckX + gridRange, curZ, truckY)
        if (p1 != null && p2 != null) {
            drawLine(Color(0x1838BDF8), p1, p2, strokeWidth = 1.0f)
        }
        curZ += gridSpacing
    }
}

private fun DrawScope.drawTruckCursor3D(cx: Float, cy: Float) {
    val cursorPath = Path().apply {
        moveTo(cx, cy - 20f)
        lineTo(cx + 12f, cy + 12f)
        lineTo(cx, cy + 6f)
        lineTo(cx - 12f, cy + 12f)
        close()
    }
    drawCircle(Color(0x4400E5FF), radius = 24f, center = Offset(cx, cy))
    drawPath(cursorPath, color = Color(0xFF00E5FF))
    drawPath(cursorPath, color = Color.White, style = Stroke(width = 1.8f))
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
