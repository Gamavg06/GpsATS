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
    Canvas(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0A0F1D))
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val newZoom = (zoomScale * zoom).coerceIn(0.10f, 4.5f)
                    onZoomChange(newZoom)
                    onPanChange(panOffset + pan)
                }
            }
    ) {
        if (graph == null || graph.nodes.isEmpty()) {
            drawEmptyState()
            return@Canvas
        }

        val truckX = telemetry.posX.toFloat()
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
// RENDERIZADOR 2D (VISTA AÉREA TÁCTICA)
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
    val cx = size.width / 2f + if (!isFollowingTruck) panOffset.x else 0f
    val cy = size.height * 0.62f + if (!isFollowingTruck) panOffset.y else 0f
    val baseScale = (1.8f * zoomScale).coerceIn(0.08f, 10f)

    val rotationAngle = if (isFollowingTruck) -headingDeg else 0f

    rotate(rotationAngle, pivot = Offset(cx, cy)) {
        val viewRadius = (max(size.width, size.height) / baseScale) * 1.2f

        // 1. Dibujar red vial (capa arcén y asfalto)
        for (edge in graph.edges) {
            if (edge.polyline.size < 2) continue

            // Comprobación de visibilidad segura: si algún punto cae dentro del radio de vista
            val inView = edge.polyline.any { pt ->
                abs(pt.x - truckX) <= viewRadius && abs(pt.z - truckZ) <= viewRadius
            }
            if (!inView) continue

            val isHighway = edge.roadType.toInt() == 0
            val isRamp = edge.roadType.toInt() == 3

            val strokeW = when {
                isHighway -> 14f * zoomScale.coerceIn(0.35f, 2.2f)
                isRamp -> 8f * zoomScale.coerceIn(0.35f, 2.2f)
                else -> 10f * zoomScale.coerceIn(0.35f, 2.2f)
            }

            val edgeColor = if (isHighway) Color(0xFF1E293B) else Color(0xFF0F172A)
            val roadColor = when {
                isHighway -> Color(0xFF475569)
                isRamp -> Color(0xFF334155)
                else -> Color(0xFF3B4A5E)
            }

            drawPolyline2D(edge.polyline, truckX, truckZ, cx, cy, baseScale, edgeColor, strokeW + 3f)
            drawPolyline2D(edge.polyline, truckX, truckZ, cx, cy, baseScale, roadColor, strokeW)
        }

        // 2. Ruta activa GPS (Glow cyan brillante)
        if (activeRoute != null) {
            for (edge in activeRoute.edgePath) {
                val rw = 16f * zoomScale.coerceIn(0.4f, 2.2f)
                drawPolyline2D(edge.polyline, truckX, truckZ, cx, cy, baseScale, Color(0x6600E5FF), rw * 1.6f)
                drawPolyline2D(edge.polyline, truckX, truckZ, cx, cy, baseScale, Color(0xFF00E5FF), rw)
            }
        }

        // 3. Dibujar Ciudades (Nombres grandes destacados)
        for (city in graph.cities) {
            val dist = sqrt((city.x - truckX).pow(2) + (city.z - truckZ).pow(2))
            if (dist > viewRadius * 1.5f) continue

            val sx = cx + (city.x - truckX) * baseScale
            val sy = cy + (city.z - truckZ) * baseScale

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

        // 4. Dibujar POIs (Gasolineras, Descanso, Talleres, Concesionarios, Empresas)
        val poiDrawRadius = viewRadius * 1.1f
        for (poi in graph.pois) {
            val dist = sqrt((poi.x - truckX).pow(2) + (poi.z - truckZ).pow(2))
            if (dist > poiDrawRadius) continue

            val sx = cx + (poi.x - truckX) * baseScale
            val sy = cy + (poi.z - truckZ) * baseScale

            drawPoiMarker2D(poi, sx, sy, baseScale)
        }
    }

    // 5. Cursor del camión
    drawTruckCursor(cx, cy, 0f)
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
// RENDERIZADOR 3D PERSPECTIVA REAL (CHASE CAMERA CON REJILLA VIRTUAL)
// =====================================================================
private fun DrawScope.drawMap3D(
    graph: MapGraph,
    truckX: Float,
    truckZ: Float,
    headingDeg: Float,
    activeRoute: RouteResult?,
    zoomScale: Float
) {
    val w = size.width
    val h = size.height
    val horizonY = h * 0.35f

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

    // Cámara en 3.ª persona detrás del camión
    val camHeadingRad = Math.toRadians(headingDeg.toDouble()).toFloat()
    val cosH = cos(camHeadingRad)
    val sinH = sin(camHeadingRad)

    val camFollowDist = 14f
    val camHeight = 6.0f
    val focalLength = h * 0.70f * zoomScale.coerceIn(0.5f, 2.5f)
    val viewDistMax = 500f

    // El camión se desplaza hacia (-sinH, -cosH). La cámara se sitúa DETRÁS del camión:
    val camX = truckX + camFollowDist * sinH
    val camZ = truckZ + camFollowDist * cosH

    fun project3D(wx: Float, wz: Float, wy: Float = 0f): Offset? {
        val dx = wx - camX
        val dz = wz - camZ
        val dy = wy

        val xCam = dx * cosH - dz * sinH
        val zCam = -dx * sinH - dz * cosH

        if (zCam < 1.0f || zCam > viewDistMax) return null

        val pX = (w * 0.5f) + (xCam * focalLength / zCam)
        val pY = horizonY + ((camHeight - dy) * focalLength / zCam)

        if (pY < horizonY - 30f || pY > h + 200f) return null
        return Offset(pX, pY)
    }

    // 3. Rejilla virtual de perspectiva en el suelo
    drawGroundGrid3D(truckX, truckZ, headingDeg, w, h, horizonY, ::project3D)

    // 4. Cono sutil de faros delanteros
    val truckProj = project3D(truckX, truckZ) ?: Offset(w * 0.5f, h * 0.70f)
    val headlightPath = Path().apply {
        moveTo(truckProj.x, truckProj.y)
        lineTo(w * 0.22f, horizonY + 30f)
        lineTo(w * 0.78f, horizonY + 30f)
        close()
    }
    drawPath(
        path = headlightPath,
        brush = Brush.verticalGradient(
            colors = listOf(Color(0x00FFFFFF), Color(0x12FFF8E1), Color(0x28FFE082)),
            startY = horizonY + 30f,
            endY = truckProj.y
        )
    )

    // 5. Dibujar Carreteras en 3D
    val nearbyEdges = graph.edges.filter { edge ->
        edge.polyline.any { abs(it.x - truckX) < 550f && abs(it.z - truckZ) < 550f }
    }

    for (edge in nearbyEdges) {
        var prevProj: Offset? = null
        for (pt in edge.polyline) {
            val proj = project3D(pt.x, pt.z)
            if (prevProj != null && proj != null) {
                val dist = sqrt((pt.x - camX).pow(2) + (pt.z - camZ).pow(2))
                val strokeW = (focalLength * 11f / max(dist, 4f)).coerceIn(2.5f, 54f)

                val isHwy = edge.roadType.toInt() == 0
                val roadColor = if (isHwy) Color(0xFF475569) else Color(0xFF334155)
                val edgeColor = Color(0xFF1E293B)

                drawLine(edgeColor, prevProj, proj, strokeWidth = strokeW + 4f, cap = StrokeCap.Round)
                drawLine(roadColor, prevProj, proj, strokeWidth = strokeW, cap = StrokeCap.Round)
            }
            prevProj = proj
        }
    }

    // 6. Dibujar Ruta Activa GPS en 3D
    if (activeRoute != null) {
        for (edge in activeRoute.edgePath) {
            var prevProj: Offset? = null
            for (pt in edge.polyline) {
                val proj = project3D(pt.x, pt.z)
                if (prevProj != null && proj != null) {
                    val dist = sqrt((pt.x - camX).pow(2) + (pt.z - camZ).pow(2))
                    val strokeW = (focalLength * 9f / max(dist, 4f)).coerceIn(3.5f, 40f)

                    drawLine(Color(0x5500E5FF), prevProj, proj, strokeWidth = strokeW * 1.5f, cap = StrokeCap.Round)
                    drawLine(Color(0xFF00E5FF), prevProj, proj, strokeWidth = strokeW, cap = StrokeCap.Round)
                }
                prevProj = proj
            }
        }
    }

    // 7. Dibujar POIs en 3D
    val nearbyPois = graph.pois.filter { poi ->
        abs(poi.x - truckX) < 450f && abs(poi.z - truckZ) < 450f
    }.sortedByDescending { poi ->
        (poi.x - camX).pow(2) + (poi.z - camZ).pow(2)
    }

    for (poi in nearbyPois) {
        val groundProj = project3D(poi.x, poi.z, wy = 0f) ?: continue
        val floatProj = project3D(poi.x, poi.z, wy = 4.5f) ?: continue

        val dist = sqrt((poi.x - camX).pow(2) + (poi.z - camZ).pow(2))
        val badgeScale = (focalLength / max(dist, 10f)).coerceIn(0.5f, 1.8f)

        drawPoiBillboard3D(poi, groundProj, floatProj, badgeScale)
    }

    // 8. Camión en perspectiva 3D
    drawTruck3D(truckProj.x, truckProj.y)
}

private fun DrawScope.drawGroundGrid3D(
    truckX: Float,
    truckZ: Float,
    headingDeg: Float,
    w: Float,
    h: Float,
    horizonY: Float,
    project3D: (Float, Float, Float) -> Offset?
) {
    val rad = Math.toRadians(headingDeg.toDouble()).toFloat()
    val fwdX = -sin(rad)
    val fwdZ = -cos(rad)
    val rightX = cos(rad)
    val rightZ = -sin(rad)

    val gridColor = Color(0x1838BDF8)

    for (d in 10..180 step 20) {
        val centerDistX = truckX + fwdX * d
        val centerDistZ = truckZ + fwdZ * d
        val pLeft = project3D(centerDistX - rightX * 70f, centerDistZ - rightZ * 70f, 0f)
        val pRight = project3D(centerDistX + rightX * 70f, centerDistZ + rightZ * 70f, 0f)
        if (pLeft != null && pRight != null) {
            drawLine(gridColor, pLeft, pRight, strokeWidth = 1.2f)
        }
    }
}

private fun DrawScope.drawPoiBillboard3D(
    poi: MapPoi,
    groundProj: Offset,
    floatProj: Offset,
    badgeScale: Float
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

    drawLine(Color(0x88FFFFFF), groundProj, floatProj, strokeWidth = 1.5f)

    val bRadius = (13f * badgeScale).coerceAtLeast(6f)

    drawCircle(Color(0x66000000), radius = bRadius + 2f, center = floatProj)
    drawCircle(bgColor, radius = bRadius, center = floatProj)
    drawCircle(Color.White, radius = bRadius, center = floatProj, style = Stroke(width = 1.6f))

    drawIntoCanvas { canvas ->
        val iconPaint = android.graphics.Paint().apply {
            textSize = (bRadius * 1.25f).coerceAtLeast(8f)
            textAlign = android.graphics.Paint.Align.CENTER
            isAntiAlias = true
        }
        canvas.nativeCanvas.drawText(iconText, floatProj.x, floatProj.y + bRadius * 0.45f, iconPaint)

        if (poi.name.isNotEmpty() && badgeScale > 0.7f) {
            val labelPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.WHITE
                textSize = (9.5f * badgeScale).coerceAtLeast(8f) * density
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = android.graphics.Paint.Align.CENTER
                isAntiAlias = true
                setShadowLayer(4f, 0f, 0f, android.graphics.Color.BLACK)
            }
            canvas.nativeCanvas.drawText(poi.name, floatProj.x, floatProj.y - bRadius - 4f, labelPaint)
        }
    }
}

private fun DrawScope.drawPolyline2D(
    polyline: List<Point3D>,
    truckX: Float,
    truckZ: Float,
    cx: Float,
    cy: Float,
    scale: Float,
    color: Color,
    strokeWidth: Float
) {
    if (polyline.size < 2) return
    val path = Path()
    val p0 = polyline[0]
    path.moveTo(cx + (p0.x - truckX) * scale, cy + (p0.z - truckZ) * scale)

    for (i in 1 until polyline.size) {
        val pt = polyline[i]
        path.lineTo(cx + (pt.x - truckX) * scale, cy + (pt.z - truckZ) * scale)
    }

    drawPath(path, color, style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

private fun DrawScope.drawTruckCursor(x: Float, y: Float, rotationDeg: Float) {
    rotate(rotationDeg, pivot = Offset(x, y)) {
        val path = Path().apply {
            moveTo(x, y - 22f)
            lineTo(x + 14f, y + 16f)
            lineTo(x, y + 10f)
            lineTo(x - 14f, y + 16f)
            close()
        }
        drawCircle(Color(0x5500E5FF), radius = 26f, center = Offset(x, y))
        drawPath(path, Color(0xFF00E5FF))
        drawPath(path, Color.White, style = Stroke(width = 2.5f))
    }
}

private fun DrawScope.drawTruck3D(x: Float, y: Float) {
    val wCab = 56f
    val hCab = 40f
    val topLeft = Offset(x - wCab / 2f, y - hCab / 2f)

    drawOval(Color(0x77000000), topLeft = Offset(x - wCab * 0.7f, y + hCab * 0.15f), size = Size(wCab * 1.4f, 18f))

    drawRoundRect(
        color = Color(0xFF1E3A8A),
        topLeft = topLeft,
        size = Size(wCab, hCab),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(6f, 6f)
    )
    drawRoundRect(
        color = Color(0xFF38BDF8),
        topLeft = topLeft,
        size = Size(wCab, hCab),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(6f, 6f),
        style = Stroke(width = 2f)
    )

    drawRect(
        color = Color(0xFF0F172A),
        topLeft = Offset(x - wCab * 0.35f, y - hCab * 0.35f),
        size = Size(wCab * 0.7f, hCab * 0.35f)
    )

    drawCircle(Color(0xFFFF1744), radius = 4.5f, center = Offset(x - wCab * 0.38f, y + hCab * 0.28f))
    drawCircle(Color(0xFFFF1744), radius = 4.5f, center = Offset(x + wCab * 0.38f, y + hCab * 0.28f))
}

private fun DrawScope.drawEmptyState() {
    drawCircle(Color(0xFF334155), radius = 30f, center = Offset(size.width / 2f, size.height / 2f))
}
