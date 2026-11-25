package com.ats.gps.ui.junction

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ats.gps.ui.navigation.ManeuverType
import kotlin.math.roundToInt

@Composable
fun JunctionView(
    maneuver: ManeuverType,
    totalLanes: Int,
    recommendedLaneIndex: Int,
    signedLateralMeters: Float,
    headingDeltaDeg: Float,
    speedKmh: Float,
    blinkerLeft: Boolean,
    blinkerRight: Boolean,
    exitName: String,
    destinationName: String,
    distanceMeters: Float,
    speedLimitKmh: Int,
    width: Dp = 280.dp,
    height: Dp = 140.dp,
    modifier: Modifier = Modifier
) {
    val laneCount = totalLanes.coerceIn(2, 4)
    val safeRecLane = recommendedLaneIndex.coerceIn(0, laneCount - 1)

    // Mapeo lateral real: ~[-5.5..+5.5] m para 3 carriles, escalado por numero de carriles
    val maxLateralRange = 5.5f * (laneCount / 3f)
    val rawLatNorm = (signedLateralMeters / maxLateralRange).coerceIn(-1f, 1f)
    val animatedLatNorm by animateFloatAsState(
        targetValue = rawLatNorm,
        animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing),
        label = "TruckLateralNorm"
    )
    val animatedTiltDeg by animateFloatAsState(
        targetValue = headingDeltaDeg.coerceIn(-18f, 18f),
        animationSpec = tween(durationMillis = 140, easing = FastOutSlowInEasing),
        label = "TruckHeadingTilt"
    )

    val truckRoadFraction = (0.5f + animatedLatNorm * 0.44f).coerceIn(0.04f, 0.96f)
    val currentTruckLane = (truckRoadFraction * laneCount).toInt().coerceIn(0, laneCount - 1)
    val isInRecommendedLane = currentTruckLane == safeRecLane

    // Desplazamiento continuo de lineas divisorias proporcional a la velocidad real (detenidas si < 2 km/h)
    val currentSpeed by rememberUpdatedState(speedKmh)
    var roadPhase by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var lastNanos = 0L
        while (true) {
            withFrameNanos { now ->
                val dt = if (lastNanos == 0L) 0.016f else ((now - lastNanos) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
                lastNanos = now
                if (kotlin.math.abs(currentSpeed) >= 2f) {
                    val speedMps = currentSpeed / 3.6f
                    roadPhase = (((roadPhase + speedMps * dt * 0.14f) % 1f) + 1f) % 1f
                }
            }
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "JunctionPulses")
    val guidePulse by infiniteTransition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(650, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "GuidePulse"
    )
    val blinkerBlink by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(360, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "BlinkerBlink"
    )
    val blinkerVisible = blinkerBlink > 0.42f

    val isCompactHeight = height < 125.dp
    Box(
        modifier = modifier
            .width(width)
            .height(height)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF0B1218))
            .border(1.dp, Color(0xFF22303C), RoundedCornerShape(12.dp))
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            val horizonY = h * 0.39f
            val roadTopLeft = w * 0.18f
            val roadTopRight = w * 0.82f
            val roadBottomLeft = w * 0.02f
            val roadBottomRight = w * 0.98f

            // 1. Cielo nocturno y horizonte
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color(0xFF0F172A), Color(0xFF1E3A5F)),
                    startY = 0f,
                    endY = horizonY
                ),
                topLeft = Offset.Zero,
                size = Size(w, horizonY)
            )
            drawRect(
                color = Color(0xFF475569),
                topLeft = Offset(0f, horizonY - 2f),
                size = Size(w, 4f * density)
            )

            // 2. Calzada en perspectiva
            val roadPath = Path().apply {
                moveTo(roadTopLeft, horizonY)
                lineTo(roadTopRight, horizonY)
                lineTo(roadBottomRight, h)
                lineTo(roadBottomLeft, h)
                close()
            }
            drawPath(roadPath, Color(0xFF1E293B))

            // Bordes exteriores de la via
            drawLine(
                color = Color(0xFFCBD5E1),
                start = Offset(roadTopLeft, horizonY),
                end = Offset(roadBottomLeft, h),
                strokeWidth = 2.0f * density
            )
            drawLine(
                color = Color(0xFFCBD5E1),
                start = Offset(roadTopRight, horizonY),
                end = Offset(roadBottomRight, h),
                strokeWidth = 2.0f * density
            )

            // 3. Iluminacion suave del carril recomendado real
            val laneLeftFrac = safeRecLane.toFloat() / laneCount.toFloat()
            val laneRightFrac = (safeRecLane + 1).toFloat() / laneCount.toFloat()
            val topL = roadTopLeft + (roadTopRight - roadTopLeft) * laneLeftFrac
            val topR = roadTopLeft + (roadTopRight - roadTopLeft) * laneRightFrac
            val botL = roadBottomLeft + (roadBottomRight - roadBottomLeft) * laneLeftFrac
            val botR = roadBottomLeft + (roadBottomRight - roadBottomLeft) * laneRightFrac

            val recLanePath = Path().apply {
                moveTo(topL, horizonY)
                lineTo(topR, horizonY)
                lineTo(botR, h)
                lineTo(botL, h)
                close()
            }
            val laneHighlightColor = if (isInRecommendedLane) Color(0xFFC7F36B) else Color(0xFF38BDF8)
            drawPath(
                path = recLanePath,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        laneHighlightColor.copy(alpha = 0.34f * guidePulse),
                        laneHighlightColor.copy(alpha = 0.14f)
                    ),
                    startY = horizonY,
                    endY = h
                )
            )

            // 4. Lineas divisorias discontinuas animadas proporcionalmente a speedKmh
            val dashCount = 5
            for (divIdx in 1 until laneCount) {
                val frac = divIdx.toFloat() / laneCount.toFloat()
                val topX = roadTopLeft + (roadTopRight - roadTopLeft) * frac
                val botX = roadBottomLeft + (roadBottomRight - roadBottomLeft) * frac

                for (d in 0 until dashCount) {
                    val tStart = ((d.toFloat() / dashCount.toFloat()) + roadPhase) % 1f
                    val tEnd = (tStart + 0.11f).coerceAtMost(1f)
                    if (tEnd <= tStart) continue

                    // Curva cuadratica para acelerar perspectiva al acercarse
                    val pStart = tStart * tStart
                    val pEnd = tEnd * tEnd
                    val sy1 = horizonY + (h - horizonY) * pStart
                    val sy2 = horizonY + (h - horizonY) * pEnd
                    val sx1 = topX + (botX - topX) * pStart
                    val sx2 = topX + (botX - topX) * pEnd
                    val strokeW = (1.2f + 1.8f * pEnd) * density

                    drawLine(
                        color = Color(0xFFE2E8F0).copy(alpha = 0.78f),
                        start = Offset(sx1, sy1),
                        end = Offset(sx2, sy2),
                        strokeWidth = strokeW,
                        cap = StrokeCap.Round
                    )
                }
            }

            // 5. Flecha dinamica de guia de maniobra en la parte superior del carril recomendado
            val markerScale = (h / (140f * density)).coerceIn(0.72f, 1.0f)
            val guideCenterFrac = (safeRecLane + 0.5f) / laneCount.toFloat()
            val guideYTop = horizonY + (h - horizonY) * 0.10f
            val guideYBot = horizonY + (h - horizonY) * 0.36f
            val guideYMid = (guideYTop + guideYBot) * 0.5f
            val guideT = (guideYMid - horizonY) / (h - horizonY)
            val roadLAtGuide = roadTopLeft + (roadBottomLeft - roadTopLeft) * guideT
            val roadRAtGuide = roadTopRight + (roadBottomRight - roadTopRight) * guideT
            val guideCx = roadLAtGuide + (roadRAtGuide - roadLAtGuide) * guideCenterFrac
            val guideSize = 15f * density * markerScale

            drawGuideManeuverArrow(
                cx = guideCx,
                cy = guideYMid,
                halfSize = guideSize,
                maneuver = maneuver,
                color = laneHighlightColor.copy(alpha = guidePulse)
            )

            // 6. Marcador dinamico del camion (posicion lateral real + rotacion + intermitentes reales)
            val truckY = h * 0.67f
            val truckT = (truckY - horizonY) / (h - horizonY)
            val roadLAtTruck = roadTopLeft + (roadBottomLeft - roadTopLeft) * truckT
            val roadRAtTruck = roadTopRight + (roadBottomRight - roadTopRight) * truckT
            val truckCx = roadLAtTruck + (roadRAtTruck - roadLAtTruck) * truckRoadFraction

            drawDynamicTruckMarker(
                cx = truckCx,
                cy = truckY,
                tiltDeg = animatedTiltDeg,
                isInRecommended = isInRecommendedLane,
                blinkerLeftActive = blinkerLeft && blinkerVisible,
                blinkerRightActive = blinkerRight && blinkerVisible,
                markerScale = markerScale
            )
        }

        // 7. Senal superior estilo autopista con limite de velocidad real
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(if (isCompactHeight) 5.dp else 8.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF14532D))
                .border(1.dp, Color(0xFF22C55E).copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                .padding(horizontal = if (isCompactHeight) 6.dp else 8.dp, vertical = if (isCompactHeight) 3.dp else 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = exitName,
                        color = Color(0xFFDCFCE7),
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = if (distanceMeters >= 1000f) {
                            String.format("%.1f km", distanceMeters / 1000f)
                        } else {
                            "${distanceMeters.roundToInt()} m"
                        },
                        color = Color(0xFFFEF08A),
                        fontWeight = FontWeight.Black,
                        fontSize = 12.sp
                    )
                }
                Text(
                    text = destinationName,
                    color = Color.White,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (speedLimitKmh > 0) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                        .border(2.5.dp, Color(0xFFDC2626), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "$speedLimitKmh",
                        color = Color(0xFF0F172A),
                        fontWeight = FontWeight.Black,
                        fontSize = 10.sp
                    )
                }
            }
        }

        // 8. Barra inferior dinamica segun carril real y maniobra
        val laneStatusText = when {
            isInRecommendedLane -> "En carril recomendado"
            currentTruckLane > safeRecLane -> "Cambia al carril izquierdo"
            else -> "Cambia al carril derecho"
        }
        val laneStatusColor = if (isInRecommendedLane) Color(0xFFC7F36B) else Color(0xFFFBBF24)
        val maneuverLabel = when (maneuver) {
            ManeuverType.LEFT, ManeuverType.SHARP_LEFT -> "Giro izquierda"
            ManeuverType.SLIGHT_LEFT -> "Salida izquierda"
            ManeuverType.RIGHT, ManeuverType.SHARP_RIGHT -> "Giro derecha"
            ManeuverType.SLIGHT_RIGHT -> "Salida derecha"
            ManeuverType.U_TURN -> "Retorno en U"
            ManeuverType.STRAIGHT -> "Carril ${safeRecLane + 1}/$laneCount"
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color(0xD9090E14))
                .padding(horizontal = if (isCompactHeight) 8.dp else 10.dp, vertical = if (isCompactHeight) 3.dp else 5.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = laneStatusText,
                color = laneStatusColor,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp
            )
            Text(
                text = maneuverLabel,
                color = Color(0xFF38BDF8),
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp
            )
        }
    }
}

private fun DrawScope.drawGuideManeuverArrow(
    cx: Float,
    cy: Float,
    halfSize: Float,
    maneuver: ManeuverType,
    color: Color
) {
    val path = Path()
    val strokeW = 3.2f * density

    when (maneuver) {
        ManeuverType.LEFT, ManeuverType.SHARP_LEFT -> {
            path.moveTo(cx + halfSize * 0.25f, cy + halfSize * 0.85f)
            path.lineTo(cx + halfSize * 0.25f, cy + halfSize * 0.05f)
            path.quadraticTo(cx + halfSize * 0.25f, cy - halfSize * 0.45f, cx - halfSize * 0.25f, cy - halfSize * 0.45f)
            path.lineTo(cx - halfSize * 0.72f, cy - halfSize * 0.45f)
            path.moveTo(cx - halfSize * 0.42f, cy - halfSize * 0.78f)
            path.lineTo(cx - halfSize * 0.80f, cy - halfSize * 0.45f)
            path.lineTo(cx - halfSize * 0.42f, cy - halfSize * 0.12f)
        }
        ManeuverType.SLIGHT_LEFT -> {
            path.moveTo(cx + halfSize * 0.25f, cy + halfSize * 0.85f)
            path.quadraticTo(cx + halfSize * 0.15f, cy + halfSize * 0.10f, cx - halfSize * 0.60f, cy - halfSize * 0.65f)
            path.moveTo(cx - halfSize * 0.18f, cy - halfSize * 0.72f)
            path.lineTo(cx - halfSize * 0.68f, cy - halfSize * 0.72f)
            path.lineTo(cx - halfSize * 0.64f, cy - halfSize * 0.22f)
        }
        ManeuverType.RIGHT, ManeuverType.SHARP_RIGHT -> {
            path.moveTo(cx - halfSize * 0.25f, cy + halfSize * 0.85f)
            path.lineTo(cx - halfSize * 0.25f, cy + halfSize * 0.05f)
            path.quadraticTo(cx - halfSize * 0.25f, cy - halfSize * 0.45f, cx + halfSize * 0.25f, cy - halfSize * 0.45f)
            path.lineTo(cx + halfSize * 0.72f, cy - halfSize * 0.45f)
            path.moveTo(cx + halfSize * 0.42f, cy - halfSize * 0.78f)
            path.lineTo(cx + halfSize * 0.80f, cy - halfSize * 0.45f)
            path.lineTo(cx + halfSize * 0.42f, cy - halfSize * 0.12f)
        }
        ManeuverType.SLIGHT_RIGHT -> {
            path.moveTo(cx - halfSize * 0.25f, cy + halfSize * 0.85f)
            path.quadraticTo(cx - halfSize * 0.15f, cy + halfSize * 0.10f, cx + halfSize * 0.60f, cy - halfSize * 0.65f)
            path.moveTo(cx + halfSize * 0.18f, cy - halfSize * 0.72f)
            path.lineTo(cx + halfSize * 0.68f, cy - halfSize * 0.72f)
            path.lineTo(cx + halfSize * 0.64f, cy - halfSize * 0.22f)
        }
        ManeuverType.U_TURN -> {
            path.moveTo(cx + halfSize * 0.35f, cy + halfSize * 0.80f)
            path.lineTo(cx + halfSize * 0.35f, cy - halfSize * 0.15f)
            path.quadraticTo(cx + halfSize * 0.35f, cy - halfSize * 0.70f, cx, cy - halfSize * 0.70f)
            path.quadraticTo(cx - halfSize * 0.35f, cy - halfSize * 0.70f, cx - halfSize * 0.35f, cy - halfSize * 0.15f)
            path.lineTo(cx - halfSize * 0.35f, cy + halfSize * 0.48f)
            path.moveTo(cx - halfSize * 0.62f, cy + halfSize * 0.22f)
            path.lineTo(cx - halfSize * 0.35f, cy + halfSize * 0.56f)
            path.lineTo(cx - halfSize * 0.08f, cy + halfSize * 0.22f)
        }
        ManeuverType.STRAIGHT -> {
            path.moveTo(cx, cy + halfSize * 0.85f)
            path.lineTo(cx, cy - halfSize * 0.65f)
            path.moveTo(cx - halfSize * 0.42f, cy - halfSize * 0.25f)
            path.lineTo(cx, cy - halfSize * 0.75f)
            path.lineTo(cx + halfSize * 0.42f, cy - halfSize * 0.25f)
        }
    }

    drawPath(
        path = path,
        color = Color(0xFF0B1218),
        style = Stroke(width = strokeW + 2.5f * density, cap = StrokeCap.Round, join = StrokeJoin.Round)
    )
    drawPath(
        path = path,
        color = color,
        style = Stroke(width = strokeW, cap = StrokeCap.Round, join = StrokeJoin.Round)
    )
}

private fun DrawScope.drawDynamicTruckMarker(
    cx: Float,
    cy: Float,
    tiltDeg: Float,
    isInRecommended: Boolean,
    blinkerLeftActive: Boolean,
    blinkerRightActive: Boolean,
    markerScale: Float = 1.0f
) {
    val accent = if (isInRecommended) Color(0xFFC7F36B) else Color(0xFFFBBF24)
    val s = density * markerScale

    rotate(tiltDeg, pivot = Offset(cx, cy)) {
        // Sombra de contacto
        drawOval(
            color = Color(0x88000000),
            topLeft = Offset(cx - 17f * s, cy + 4f * s),
            size = Size(34f * s, 12f * s)
        )

        // Halo de posicion lateral
        drawCircle(
            color = accent.copy(alpha = 0.28f),
            radius = 19f * s,
            center = Offset(cx, cy)
        )

        // Cuerpo estilizado del camion
        val bodyW = 18f * s
        val bodyH = 24f * s
        drawRoundRect(
            color = Color(0xFF0B1218),
            topLeft = Offset(cx - bodyW * 0.5f, cy - bodyH * 0.5f),
            size = Size(bodyW, bodyH),
            cornerRadius = CornerRadius(5f * s, 5f * s)
        )
        drawRoundRect(
            color = Color.White,
            topLeft = Offset(cx - bodyW * 0.5f, cy - bodyH * 0.5f),
            size = Size(bodyW, bodyH),
            cornerRadius = CornerRadius(5f * s, 5f * s),
            style = Stroke(width = 1.8f * s)
        )

        // Chevron direccional interno
        val chevron = Path().apply {
            moveTo(cx, cy - 8f * s)
            lineTo(cx + 6f * s, cy + 6f * s)
            lineTo(cx, cy + 2.5f * s)
            lineTo(cx - 6f * s, cy + 6f * s)
            close()
        }
        drawPath(chevron, color = accent)

        // Destello real de intermitentes ambar izquierda / derecha
        val amber = Color(0xFFF59E0B)
        if (blinkerLeftActive) {
            drawCircle(amber.copy(alpha = 0.45f), radius = 8f * s, center = Offset(cx - bodyW * 0.55f, cy - 4f * s))
            drawCircle(amber, radius = 3.2f * s, center = Offset(cx - bodyW * 0.55f, cy - 4f * s))
        }
        if (blinkerRightActive) {
            drawCircle(amber.copy(alpha = 0.45f), radius = 8f * s, center = Offset(cx + bodyW * 0.55f, cy - 4f * s))
            drawCircle(amber, radius = 3.2f * s, center = Offset(cx + bodyW * 0.55f, cy - 4f * s))
        }
    }
}
