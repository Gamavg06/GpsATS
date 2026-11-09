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

    // Coordenadas objetivo recibidas de la telemetría (30 Hz)
    val targetX = telemetry.posX.toFloat()
    val targetY = telemetry.posY.toFloat()
    val targetZ = telemetry.posZ.toFloat()
    val targetHeading = telemetry.headingDegrees.toFloat()

    // Estados suavizados para renderizado continuo e interpolado (60-120 Hz)
    var smoothTruckX by remember { mutableFloatStateOf(targetX) }
    var smoothTruckY by remember { mutableFloatStateOf(targetY) }
    var smoothTruckZ by remember { mutableFloatStateOf(targetZ) }
    var smoothHeading by remember { mutableFloatStateOf(targetHeading) }

    // Inicializar de inmediato si es la primera lectura
    LaunchedEffect(targetX, targetZ) {
        if (abs(smoothTruckX) < 0.001f && abs(smoothTruckZ) < 0.001f && (abs(targetX) > 0.001f || abs(targetZ) > 0.001f)) {
            smoothTruckX = targetX
            smoothTruckY = targetY
            smoothTruckZ = targetZ
            smoothHeading = targetHeading
        }
    }

    // Bucle de interpolación continua a la frecuencia nativa de pantalla (elimina micro-tirones)
    LaunchedEffect(Unit) {
        var lastTime = 0L
        while (true) {
            withFrameNanos { now ->
                val dt = if (lastTime == 0L) 0.016f else ((now - lastTime) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
                lastTime = now

                val lerpFactor = (15f * dt).coerceIn(0.05f, 0.45f)

                val dx = targetX - smoothTruckX
                val dy = targetY - smoothTruckY
                val dz = targetZ - smoothTruckZ
                val distSq = dx * dx + dz * dz

                if (distSq > 10000f) { // Salto brusco / reaparición
                    smoothTruckX = targetX
                    smoothTruckY = targetY
                    smoothTruckZ = targetZ
                    smoothHeading = targetHeading
                } else {
                    smoothTruckX += dx * lerpFactor
                    smoothTruckY += dy * lerpFactor
                    smoothTruckZ += dz * lerpFactor

                    val headingDiff = ((targetHeading - smoothHeading + 540f) % 360f) - 180f
                    smoothHeading += headingDiff * lerpFactor
                }
            }
        }
    }

    // Caché de aristas visibles para no re-consultar el SpatialGrid en cada fotograma
    var cached2DEdges by remember { mutableStateOf<List<MapEdge>>(emptyList()) }
    var last2DQueryX by remember { mutableFloatStateOf(Float.NaN) }
    var last2DQueryZ by remember { mutableFloatStateOf(Float.NaN) }
    var last2DScale by remember { mutableFloatStateOf(1.0f) }

    var cached3DEdges by remember { mutableStateOf<List<MapEdge>>(emptyList()) }
    var last3DQueryX by remember { mutableFloatStateOf(Float.NaN) }
    var last3DQueryZ by remember { mutableFloatStateOf(Float.NaN) }

    // Pinturas cacheadas para texto y POIs (cero asignaciones en el bucle de renderizado)
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
                val baseScale = (1.8f * zoomScale).coerceIn(0.08f, 10f)

                val viewCenterX = truckX - (if (!isFollowingTruck) panOffset.x / baseScale else 0f)
                val viewCenterZ = truckZ - (if (!isFollowingTruck) panOffset.y / baseScale else 0f)
                val viewRadius = (max(size.width, size.height) / baseScale) * 1.5f

                // Actualizar caché de aristas sólo si la cámara se desplazó más de 25m o cambió el zoom
                val distFromLast2D = if (last2DQueryX.isNaN()) Float.MAX_VALUE else {
                    val qx = viewCenterX - last2DQueryX
                    val qz = viewCenterZ - last2DQueryZ
                    qx * qx + qz * qz
                }
                val scaleRatio = abs(baseScale - last2DScale) / last2DScale

                if (distFromLast2D > 625f || scaleRatio > 0.08f || cached2DEdges.isEmpty()) {
                    cached2DEdges = graph.getEdgesNear(viewCenterX, viewCenterZ, viewRadius * 1.25f)
                    last2DQueryX = viewCenterX
                    last2DQueryZ = viewCenterZ
                    last2DScale = baseScale
                }

                drawMap2D(
                    graph = graph,
                    visibleEdges = cached2DEdges,
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
                val distFromLast3D = if (last3DQueryX.isNaN()) Float.MAX_VALUE else {
                    val qx = truckX - last3DQueryX
                    val qz = truckZ - last3DQueryZ
                    qx * qx + qz * qz
                }
                if (distFromLast3D > 400f || cached3DEdges.isEmpty()) {
                    cached3DEdges = graph.getEdgesNear(truckX, truckZ, 650f)
                    last3DQueryX = truckX
                    last3DQueryZ = truckZ
                }

                drawMap3D(
                    nearbyEdges = cached3DEdges,
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
        // Encontrar la carretera actual más cercana al camión (búsqueda optimizada por cercanía de extremos)
        var closestEdgeId = -1
        var minEdgeDistSq = 1600f // Máximo 40 metros
        if (isFollowingTruck) {
            for (edge in visibleEdges) {
                if (edge.polyline.isEmpty()) continue
                val p0 = edge.polyline[0]
                val pEnd = edge.polyline[edge.polyline.size - 1]
                val d0 = (p0.x - truckX) * (p0.x - truckX) + (p0.z - truckZ) * (p0.z - truckZ)
                val dEnd = (pEnd.x - truckX) * (pEnd.x - truckX) + (pEnd.z - truckZ) * (pEnd.z - truckZ)
                if (d0 < 3600f || dEnd < 3600f) { // Dentro de 60m
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

        // Clasificar aristas por jerarquía para construir caminos vectoriales continuos
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

        // Dibujar capas en orden: Rampas -> Locales -> Principales -> Autopistas -> Carretera Actual
        drawContinuousRoadCategory(rampEdges, viewCenterX, viewCenterZ, cx, cy, baseScale, 3, zoomScale)
        drawContinuousRoadCategory(localEdges, viewCenterX, viewCenterZ, cx, cy, baseScale, 2, zoomScale)
        drawContinuousRoadCategory(mainEdges, viewCenterX, viewCenterZ, cx, cy, baseScale, 1, zoomScale)
        drawContinuousRoadCategory(hwyEdges, viewCenterX, viewCenterZ, cx, cy, baseScale, 0, zoomScale)

        // Carretera actual resaltada en Cyan brillante
        if (currentRoadEdge != null) {
            val highlightPath = buildConnectedPath(listOf(currentRoadEdge), viewCenterX, viewCenterZ, cx, cy, baseScale)
            val strokeW = getRoadStrokeWidth(currentRoadEdge.roadType.toInt(), zoomScale)
            drawPath(highlightPath, Color(0xFF0F172A), style = Stroke(strokeW + 5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(highlightPath, Color(0xFF38BDF8), style = Stroke(strokeW + 1f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }

        // 2. Ruta activa GPS (Cinta continua fluida con resplandor exterior suave)
        if (activeRoute != null && activeRoute.edgePath.isNotEmpty()) {
            val routePath = Path()
            var isFirst = true
            var lastPx = Float.NaN
            var lastPz = Float.NaN

            for (edge in activeRoute.edgePath) {
                for (pt in edge.polyline) {
                    // Descartar vértices duplicados exactos para evitar quiebres
                    if (!lastPx.isNaN() && abs(pt.x - lastPx) < 0.05f && abs(pt.z - lastPz) < 0.05f) {
                        continue
                    }
                    lastPx = pt.x
                    lastPz = pt.z

                    val sx = cx + (pt.x - viewCenterX) * baseScale
                    val sy = cy + (pt.z - viewCenterZ) * baseScale
                    if (isFirst) {
                        routePath.moveTo(sx, sy)
                        isFirst = false
                    } else {
                        routePath.lineTo(sx, sy)
                    }
                }
            }

            val rw = 16f * zoomScale.coerceIn(0.4f, 2.2f)
            // Halo / Resplandor exterior
            drawPath(
                path = routePath,
                color = Color(0x6600E5FF),
                style = Stroke(width = rw * 1.55f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
            // Núcleo brillante continuo
            drawPath(
                path = routePath,
                color = Color(0xFF00E5FF),
                style = Stroke(width = rw, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }

        // 3. Ciudades (Icono y nombre destacados)
        for (city in graph.cities) {
            val dx = city.x - viewCenterX
            val dz = city.z - viewCenterZ
            if (dx * dx + dz * dz > (viewRadius * 1.5f) * (viewRadius * 1.5f)) continue

            val sx = cx + dx * baseScale
            val sy = cy + dz * baseScale

            drawCircle(Color(0xFF38BDF8), radius = 6f * zoomScale.coerceIn(0.6f, 1.5f), center = Offset(sx, sy))

            drawIntoCanvas { canvas ->
                cityPaint.textSize = (13f * zoomScale.coerceIn(0.8f, 1.4f)).coerceAtLeast(10f) * density
                canvas.nativeCanvas.drawText(city.name, sx + 8f, sy + 4f, cityPaint)
            }
        }

        // 4. POIs (Gasolineras, áreas de descanso, talleres, empresas)
        val poiDrawRadiusSq = (viewRadius * 1.15f) * (viewRadius * 1.15f)
        for (poi in graph.pois) {
            val dx = poi.x - viewCenterX
            val dz = poi.z - viewCenterZ
            if (dx * dx + dz * dz > poiDrawRadiusSq) continue

            val sx = cx + dx * baseScale
            val sy = cy + dz * baseScale

            drawPoiMarker2D(poi, sx, sy, baseScale, poiIconPaint, poiLabelPaint)
        }
    }

    // 5. Cursor táctico del camión
    if (isFollowingTruck) {
        drawTruckCursor(cx, cy, 0f)
    } else {
        val truckSx = cx + (truckX - viewCenterX) * baseScale
        val truckSy = cy + (truckZ - viewCenterZ) * baseScale
        drawTruckCursor(truckSx, truckSy, headingDeg)
    }
}

// Dibuja una categoría completa de carreteras en una sola pasada unificada con bordes y asfalto continuo
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
        0 -> Color(0xFF0F172A) // Autopista
        1 -> Color(0xFF1E293B) // Carretera estatal
        2 -> Color(0xFF0B1120) // Calle local
        else -> Color(0xFF1E293B) // Rampa o enlace
    }
    val surfaceColor = when (roadType) {
        0 -> Color(0xFF475569) // Autopista
        1 -> Color(0xFF334155) // Primaria
        2 -> Color(0xFF1E293B) // Urbana
        else -> Color(0xFF3B4A5E) // Rampa
    }

    val path = buildConnectedPath(edges, viewCenterX, viewCenterZ, cx, cy, scale)

    // Borde exterior
    drawPath(
        path = path,
        color = borderColor,
        style = Stroke(width = strokeW + 4f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    )
    // Superficie de asfalto
    drawPath(
        path = path,
        color = surfaceColor,
        style = Stroke(width = strokeW, cap = StrokeCap.Round, join = StrokeJoin.Round)
    )
}

// Construye un Path vectorial conectando vértices contiguos para eliminar nudos y cortes angulares
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

            if (i == 0) {
                // Si el inicio de esta arista conecta exactamente con la anterior (<2.5 px), continuamos la línea
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
        0 -> 15f * z // Autopista
        1 -> 11f * z // Carretera estatal
        2 -> 7f * z  // Calle local
        else -> 8f * z // Rampa
    }
}

// Marcador individual de POI en 2D con insignia redondeada e icono
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
// RENDERIZADOR 3D PERSPECTIVA REAL (CORRECCIÓN DE ELEVACIÓN Y RECORTE G7)
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
    val horizonY = h * 0.38f

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

    // 2. Terreno / Plano de asfalto
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(Color(0xFF080D18), Color(0xFF0C1322), Color(0xFF10192A)),
            startY = horizonY,
            endY = h
        ),
        topLeft = Offset(0f, horizonY),
        size = Size(w, h - horizonY)
    )

    // Cámara en 3.ª persona detrás del camión (referenciada a altitud real truckY)
    val camHeadingRad = Math.toRadians(headingDeg.toDouble()).toFloat()
    val cosH = cos(camHeadingRad)
    val sinH = sin(camHeadingRad)

    val camFollowDist = 12f
    val camHeight = 5.2f
    val camY = truckY + camHeight

    val focalLength = h * 0.72f * zoomScale.coerceIn(0.5f, 2.5f)
    val viewDistMax = 550f
    val zNear = 0.5f

    val camX = truckX + camFollowDist * sinH
    val camZ = truckZ + camFollowDist * cosH

    fun toCam(wx: Float, wz: Float, wy: Float): Triple<Float, Float, Float> {
        val dx = wx - camX
        val dz = wz - camZ
        val dy = wy - camY
        val xCam = dx * cosH - dz * sinH
        val zCam = -dx * sinH - dz * cosH
        return Triple(xCam, dy, zCam)
    }

    fun projectCam(xCam: Float, dy: Float, zCam: Float): Offset {
        val pX = (w * 0.5f) + (xCam * focalLength / zCam)
        val pY = horizonY + ((-dy) * focalLength / zCam)
        return Offset(pX, pY)
    }

    fun project3D(wx: Float, wz: Float, wy: Float = truckY): Offset? {
        val (xCam, dy, zCam) = toCam(wx, wz, wy)
        if (zCam < zNear || zCam > viewDistMax) return null
        val pY = horizonY + ((-dy) * focalLength / zCam)
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

        if (cz1 < zNear) {
            val t = (zNear - cz1) / (cz2 - cz1)
            cx1 += t * (cx2 - cx1)
            cy1 += t * (cy2 - cy1)
            cz1 = zNear
        } else if (cz2 < zNear) {
            val t = (zNear - cz2) / (cz1 - cz2)
            cx2 += t * (cx1 - cx2)
            cy2 += t * (cy1 - cy2)
            cz2 = zNear
        }

        val proj1 = projectCam(cx1, cy1, cz1)
        val proj2 = projectCam(cx2, cy2, cz2)

        val avgDist = (cz1 + cz2) * 0.5f
        val strokeW = (focalLength * widthMult / max(avgDist, 2.5f)).coerceIn(2.5f, 65f)

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

    // 6. Dibujar Ruta Activa GPS en 3D (solo segmentos dentro del rango de visión)
    if (activeRoute != null) {
        for (edge in activeRoute.edgePath) {
            // Culling rápido para no proyectar segmentos lejanos
            if (edge.polyline.isNotEmpty()) {
                val midP = edge.polyline[edge.polyline.size / 2]
                val dx = midP.x - truckX
                val dz = midP.z - truckZ
                if (dx * dx + dz * dz > 360000f) continue // > 600m
            }
            for (i in 0 until edge.polyline.size - 1) {
                drawSegmentClipped(edge.polyline[i], edge.polyline[i + 1], Color(0x6600E5FF), Color(0xFF00E5FF), 13f)
            }
        }
    }

    // 7. Cursor 3D del camión
    drawTruckCursor3D(truckProj.x, truckProj.y)
}

// Rejilla de perspectiva virtual terrestre
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

// Cursor 3D táctico del camión
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

// Cursor 2D táctico del camión
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
