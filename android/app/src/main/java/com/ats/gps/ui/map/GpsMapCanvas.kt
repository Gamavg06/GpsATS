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

        val truckX = telemetry.posX.toFloat()
        val truckY = telemetry.posY.toFloat()
        val truckZ = telemetry.posZ.toFloat()
        val headingDeg = telemetry.headingDegrees.toFloat()

        when (viewMode) {
            MapViewMode.MODE_2D_TOP_DOWN -> {
                drawMap2D(
                    graph = graph,
                    truckX = truckX,
                    truckZ = truckZ,
                    headingDeg = headingDeg,
                    activeRoute = activeRoute,
                    zoomScale = zoomScale,
                    panOffset = panOffset,
                    isFollowingTruck = isFollowingTruck
                )
            }
            MapViewMode.MODE_3D_PERSPECTIVE -> {
                drawMap3D(
                    graph = graph,
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
// RENDERIZADOR 2D (VISTA AÉREA TÁCTICA CON JERARQUÍA VIAL G6)
// =====================================================================
private fun DrawScope.drawMap2D(
    graph: MapGraph,
    truckX: Float,
    truckZ: Float,
    headingDeg: Float,
    activeRoute: RouteResult?,
    zoomScale: Float,
    panOffset: Offset,
    isFollowingTruck: Boolean
) {
    val cx = size.width / 2f
    val cy = size.height * 0.62f
    val baseScale = (1.8f * zoomScale).coerceIn(0.08f, 10f)

    // Coordenadas mundiales del centro de vista actual
    val viewCenterX = truckX - (if (!isFollowingTruck) panOffset.x / baseScale else 0f)
    val viewCenterZ = truckZ - (if (!isFollowingTruck) panOffset.y / baseScale else 0f)

    val rotationAngle = if (isFollowingTruck) -headingDeg else 0f

    rotate(rotationAngle, pivot = Offset(cx, cy)) {
        val viewRadius = (max(size.width, size.height) / baseScale) * 1.5f

        // 1. Obtener aristas visibles con Spatial Grid
        val visibleEdges = graph.getEdgesNear(viewCenterX, viewCenterZ, viewRadius)

        // Encontrar la carretera actual más cercana al camión (G6)
        var closestEdgeId = -1
        var minEdgeDistSq = Float.MAX_VALUE
        if (isFollowingTruck) {
            for (edge in visibleEdges) {
                for (p in edge.polyline) {
                    val dSq = (p.x - truckX) * (p.x - truckX) + (p.z - truckZ) * (p.z - truckZ)
                    if (dSq < minEdgeDistSq) {
                        minEdgeDistSq = dSq
                        closestEdgeId = edge.edgeId
                    }
                }
            }
        }

        // Paso A: Dibujar capas inferiores y contornos oscuros
        for (edge in visibleEdges) {
            if (edge.polyline.size < 2) continue

            val strokeW = getRoadStrokeWidth(edge.roadType.toInt(), zoomScale)
            val borderColor = when (edge.roadType.toInt()) {
                0 -> Color(0xFF0F172A) // Autopista
                1 -> Color(0xFF1E293B) // Primaria
                2 -> Color(0xFF0B1120) // Local
                else -> Color(0xFF1E293B) // Rampa
            }
            drawPolyline2D(edge.polyline, viewCenterX, viewCenterZ, cx, cy, baseScale, borderColor, strokeW + 4f)
        }

        // Paso B: Dibujar superficies de asfalto con colores de jerarquía (G6)
        for (edge in visibleEdges) {
            if (edge.polyline.size < 2) continue

            val strokeW = getRoadStrokeWidth(edge.roadType.toInt(), zoomScale)
            val isCurrentRoad = (edge.edgeId == closestEdgeId && minEdgeDistSq < 1600f) // <40 metros

            val surfaceColor = if (isCurrentRoad) {
                Color(0xFF38BDF8) // Carretera actual resaltada en Cyan de navegación
            } else {
                when (edge.roadType.toInt()) {
                    0 -> Color(0xFF475569) // Autopista Interestatal (Gris pizarra destacado)
                    1 -> Color(0xFF334155) // Carretera estatal principal
                    2 -> Color(0xFF1E293B) // Vía urbana / local
                    else -> Color(0xFF3B4A5E) // Rampa o enlace
                }
            }

            drawPolyline2D(edge.polyline, viewCenterX, viewCenterZ, cx, cy, baseScale, surfaceColor, strokeW)
        }

        // 2. Ruta activa GPS (Glow cyan brillante)
        if (activeRoute != null) {
            for (edge in activeRoute.edgePath) {
                val rw = 16f * zoomScale.coerceIn(0.4f, 2.2f)
                drawPolyline2D(edge.polyline, viewCenterX, viewCenterZ, cx, cy, baseScale, Color(0x6600E5FF), rw * 1.6f)
                drawPolyline2D(edge.polyline, viewCenterX, viewCenterZ, cx, cy, baseScale, Color(0xFF00E5FF), rw)
            }
        }

        // 3. Dibujar Ciudades (Nombres grandes destacados)
        for (city in graph.cities) {
            val dist = sqrt((city.x - viewCenterX).pow(2) + (city.z - viewCenterZ).pow(2))
            if (dist > viewRadius * 1.5f) continue

            val sx = cx + (city.x - viewCenterX) * baseScale
            val sy = cy + (city.z - viewCenterZ) * baseScale

            drawCircle(Color(0xFF38BDF8), radius = 6f * zoomScale.coerceIn(0.6f, 1.5f), center = Offset(sx, sy))

            drawIntoCanvas { canvas ->
                val paint = android.graphics.Paint().apply {
                    color = android.graphics.Color.WHITE
                    textSize = (13f * zoomScale.coerceIn(0.8f, 1.4f)).coerceAtLeast(10f) * density
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    isAntiAlias = true
                    setShadowLayer(4f, 0f, 0f, android.graphics.Color.BLACK)
                }
                canvas.nativeCanvas.drawText(city.name, sx + 8f, sy + 4f, paint)
            }
        }

        // 4. Dibujar POIs
        val poiDrawRadius = viewRadius * 1.1f
        for (poi in graph.pois) {
            val dist = sqrt((poi.x - viewCenterX).pow(2) + (poi.z - viewCenterZ).pow(2))
            if (dist > poiDrawRadius) continue

            val sx = cx + (poi.x - viewCenterX) * baseScale
            val sy = cy + (poi.z - viewCenterZ) * baseScale

            drawPoiMarker2D(poi, sx, sy, baseScale)
        }
    }

    // 5. Cursor del camión
    if (isFollowingTruck) {
        drawTruckCursor(cx, cy, 0f)
    } else {
        val truckSx = cx + (truckX - viewCenterX) * baseScale
        val truckSy = cy + (truckZ - viewCenterZ) * baseScale
        drawTruckCursor(truckSx, truckSy, headingDeg)
    }
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
private fun DrawScope.drawPoiMarker2D(poi: MapPoi, sx: Float, sy: Float, scale: Float) {
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
        val iconPaint = android.graphics.Paint().apply {
            textSize = (radius * 1.25f).coerceAtLeast(8f)
            textAlign = android.graphics.Paint.Align.CENTER
            isAntiAlias = true
        }
        val yOffset = radius * 0.45f
        canvas.nativeCanvas.drawText(iconText, sx, sy + yOffset, iconPaint)

        if (scale > 0.7f && poi.name.isNotEmpty()) {
            val labelPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.WHITE
                textSize = (9f * density).coerceAtLeast(8f)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = android.graphics.Paint.Align.CENTER
                isAntiAlias = true
                setShadowLayer(3f, 0f, 0f, android.graphics.Color.BLACK)
            }
            canvas.nativeCanvas.drawText(poi.name, sx, sy + radius + 11f * density, labelPaint)
        }
    }
}

// =====================================================================
// RENDERIZADOR 3D PERSPECTIVA REAL (CORRECCIÓN DE ELEVACIÓN Y RECORTE G7)
// =====================================================================
private fun DrawScope.drawMap3D(
    graph: MapGraph,
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

    // Cámara en 3.ª persona detrás del camión (G7: referenciada a la altitud real truckY)
    val camHeadingRad = Math.toRadians(headingDeg.toDouble()).toFloat()
    val cosH = cos(camHeadingRad)
    val sinH = sin(camHeadingRad)

    val camFollowDist = 12f
    val camHeight = 5.2f // 5.2 metros sobre la altura del camión
    val camY = truckY + camHeight // Elevación absoluta en el mundo de SCS

    val focalLength = h * 0.72f * zoomScale.coerceIn(0.5f, 2.5f)
    val viewDistMax = 550f
    val zNear = 0.5f // Plano cercano para no cortar carreteras bajo el camión

    // La cámara se sitúa DETRÁS del camión:
    val camX = truckX + camFollowDist * sinH
    val camZ = truckZ + camFollowDist * cosH

    fun toCam(wx: Float, wz: Float, wy: Float): Triple<Float, Float, Float> {
        val dx = wx - camX
        val dz = wz - camZ
        val dy = wy - camY // Altura relativa a la lente de la cámara (G7)
        val xCam = dx * cosH - dz * sinH
        val zCam = -dx * sinH - dz * cosH
        return Triple(xCam, dy, zCam)
    }

    fun projectCam(xCam: Float, dy: Float, zCam: Float): Offset {
        val pX = (w * 0.5f) + (xCam * focalLength / zCam)
        // dy es negativo cuando el objeto está bajo la cámara, por lo que -dy es positivo (hacia abajo en pantalla)
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
            cx1 = cx1 + t * (cx2 - cx1)
            cy1 = cy1 + t * (cy2 - cy1)
            cz1 = zNear
        } else if (cz2 < zNear) {
            val t = (zNear - cz2) / (cz1 - cz2)
            cx2 = cx2 + t * (cx1 - cx2)
            cy2 = cy2 + t * (cy1 - cy2)
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

    // 4. Cono sutil de faros delanteros
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

    // 5. Dibujar Carreteras en 3D con Spatial Grid y elevación precisa (G7)
    val nearbyEdges = graph.getEdgesNear(truckX, truckZ, 700f)

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

private fun DrawScope.drawPolyline2D(
    points: List<Point3D>,
    viewCenterX: Float,
    viewCenterZ: Float,
    cx: Float,
    cy: Float,
    scale: Float,
    color: Color,
    strokeWidth: Float
) {
    if (points.size < 2) return

    val path = Path()
    var first = true

    for (p in points) {
        val sx = cx + (p.x - viewCenterX) * scale
        val sy = cy + (p.z - viewCenterZ) * scale
        if (first) {
            path.moveTo(sx, sy)
            first = false
        } else {
            path.lineTo(sx, sy)
        }
    }

    drawPath(
        path = path,
        color = color,
        style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
    )
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
