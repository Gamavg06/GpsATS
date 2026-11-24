package com.ats.gps.ui.navigation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ats.gps.data.map.RouteResult
import com.ats.gps.data.model.MapEdge
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class ManeuverType {
    STRAIGHT,
    SLIGHT_RIGHT,
    RIGHT,
    SHARP_RIGHT,
    SLIGHT_LEFT,
    LEFT,
    SHARP_LEFT,
    U_TURN
}

data class NavigationInstruction(
    val maneuver: ManeuverType,
    val distanceMeters: Float,
    val title: String,
    val subtitle: String,
    val totalLanes: Int, // Ej: 1, 2, 3, 4
    val activeLaneIndex: Int, // 0-indexed de izquierda a derecha
    val isTwoPlusOne: Boolean = false, // Modo 2+1 carril
    val isHiddenRoad: Boolean = false
)

@Composable
fun TurnByTurnCard(
    truckX: Float,
    truckZ: Float,
    activeRoute: RouteResult?,
    destinationCity: String,
    instructionOverride: NavigationInstruction? = null,
    modifier: Modifier = Modifier
) {
    if (activeRoute == null || activeRoute.edgePath.isEmpty()) return

    val navCache = remember { NavigationInstructionCache() }
    val instruction = instructionOverride ?: calculateNavigationInstruction(
        truckX = truckX,
        truckZ = truckZ,
        route = activeRoute,
        destinationCity = destinationCity,
        cache = navCache
    ) ?: return

    Box(
        modifier = modifier
            .width(260.dp)
            .shadow(12.dp, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.verticalGradient(
                    colors = if (instruction.isHiddenRoad) {
                        listOf(
                            Color(0xFF5B3900), // Tono ámbar/dorado de camino oculto
                            Color(0xFF2E1C00)
                        )
                    } else {
                        listOf(
                            Color(0xFF3F2B66), // Púrpura oscuro idéntico a la referencia
                            Color(0xFF291C47)
                        )
                    }
                )
            )
            .border(
                1.2.dp,
                if (instruction.isHiddenRoad) Color(0xFFF59E0B) else Color(0x40FFFFFF),
                RoundedCornerShape(16.dp)
            )
            .padding(14.dp)
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Fila superior: Flecha de maniobra grande + Distancia + Badge Camino Oculto
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Icono de Maniobra
                ManeuverIcon(
                    maneuver = instruction.maneuver,
                    modifier = Modifier.size(36.dp)
                )

                if (instruction.isHiddenRoad) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFFF59E0B))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "CAMINO OCULTO",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF1E1100)
                        )
                    }
                }

                // Distancia restante a la maniobra
                val distText = if (instruction.distanceMeters >= 1000f) {
                    val km = instruction.distanceMeters / 1000f
                    String.format("%.1f km", km)
                } else {
                    "${instruction.distanceMeters.roundToInt()} m"
                }

                Text(
                    text = distText,
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Título de maniobra / instrucción
            Text(
                text = instruction.title,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )

            if (instruction.subtitle.isNotEmpty()) {
                Text(
                    text = instruction.subtitle,
                    color = Color(0xFFD1C4E9),
                    fontSize = 12.sp,
                    maxLines = 1
                )
            }

            // Separador sutil
            Spacer(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Color(0x33FFFFFF))
            )

            // Fila inferior: Guía de Carriles táctica (Lane Guidance)
            LaneGuidanceBar(
                totalLanes = instruction.totalLanes,
                activeLaneIndex = instruction.activeLaneIndex,
                maneuver = instruction.maneuver,
                isTwoPlusOne = instruction.isTwoPlusOne
            )
        }
    }
}

@Composable
private fun LaneGuidanceBar(
    totalLanes: Int,
    activeLaneIndex: Int,
    maneuver: ManeuverType,
    isTwoPlusOne: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val laneCount = totalLanes.coerceIn(1, 4)

        for (i in 0 until laneCount) {
            val isActive = (i == activeLaneIndex)
            val laneManeuver = when {
                i == 0 && (maneuver == ManeuverType.LEFT || maneuver == ManeuverType.SLIGHT_LEFT) -> maneuver
                i == laneCount - 1 && (maneuver == ManeuverType.RIGHT || maneuver == ManeuverType.SLIGHT_RIGHT) -> maneuver
                else -> ManeuverType.STRAIGHT
            }

            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (isActive) Color(0x33FFFFFF) else Color.Transparent),
                contentAlignment = Alignment.Center
            ) {
                LaneArrow(
                    maneuver = if (isActive) maneuver else laneManeuver,
                    isActive = isActive,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        if (isTwoPlusOne) {
            Text(
                text = "2+1",
                color = Color(0xFFA5B4FC),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 4.dp)
            )
        }
    }
}

@Composable
private fun LaneArrow(
    maneuver: ManeuverType,
    isActive: Boolean,
    modifier: Modifier = Modifier
) {
    val color = if (isActive) Color.White else Color(0x40FFFFFF)
    val strokeWidth = if (isActive) 3.2f else 2.2f

    Canvas(modifier = modifier) {
        drawManeuverPath(maneuver, color, strokeWidth)
    }
}

@Composable
private fun ManeuverIcon(
    maneuver: ManeuverType,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        drawManeuverPath(maneuver, Color.White, strokeWidth = 4.0f)
    }
}

private fun DrawScope.drawManeuverPath(
    maneuver: ManeuverType,
    color: Color,
    strokeWidth: Float
) {
    val w = size.width
    val h = size.height
    val path = Path()

    when (maneuver) {
        ManeuverType.STRAIGHT -> {
            // Flecha recta vertical hacia arriba
            path.moveTo(w * 0.5f, h * 0.9f)
            path.lineTo(w * 0.5f, h * 0.2f)
            path.moveTo(w * 0.25f, h * 0.42f)
            path.lineTo(w * 0.5f, h * 0.15f)
            path.lineTo(w * 0.75f, h * 0.42f)
        }
        ManeuverType.RIGHT, ManeuverType.SHARP_RIGHT -> {
            // Flecha curva en ángulo de 90° hacia la derecha
            path.moveTo(w * 0.25f, h * 0.88f)
            path.lineTo(w * 0.25f, h * 0.50f)
            path.quadraticTo(w * 0.25f, h * 0.25f, w * 0.55f, h * 0.25f)
            path.lineTo(w * 0.82f, h * 0.25f)
            // Punta de la flecha
            path.moveTo(w * 0.62f, h * 0.08f)
            path.lineTo(w * 0.88f, h * 0.25f)
            path.lineTo(w * 0.62f, h * 0.42f)
        }
        ManeuverType.SLIGHT_RIGHT -> {
            // Bifurcación suave a la derecha
            path.moveTo(w * 0.35f, h * 0.88f)
            path.quadraticTo(w * 0.4f, h * 0.5f, w * 0.75f, h * 0.25f)
            path.moveTo(w * 0.52f, h * 0.22f)
            path.lineTo(w * 0.80f, h * 0.22f)
            path.lineTo(w * 0.76f, h * 0.45f)
        }
        ManeuverType.LEFT, ManeuverType.SHARP_LEFT -> {
            // Flecha curva en ángulo de 90° hacia la izquierda
            path.moveTo(w * 0.75f, h * 0.88f)
            path.lineTo(w * 0.75f, h * 0.50f)
            path.quadraticTo(w * 0.75f, h * 0.25f, w * 0.45f, h * 0.25f)
            path.lineTo(w * 0.18f, h * 0.25f)
            // Punta
            path.moveTo(w * 0.38f, h * 0.08f)
            path.lineTo(w * 0.12f, h * 0.25f)
            path.lineTo(w * 0.38f, h * 0.42f)
        }
        ManeuverType.SLIGHT_LEFT -> {
            path.moveTo(w * 0.65f, h * 0.88f)
            path.quadraticTo(w * 0.6f, h * 0.5f, w * 0.25f, h * 0.25f)
            path.moveTo(w * 0.48f, h * 0.22f)
            path.lineTo(w * 0.20f, h * 0.22f)
            path.lineTo(w * 0.24f, h * 0.45f)
        }
        ManeuverType.U_TURN -> {
            path.moveTo(w * 0.70f, h * 0.88f)
            path.lineTo(w * 0.70f, h * 0.45f)
            path.quadraticTo(w * 0.70f, h * 0.15f, w * 0.45f, h * 0.15f)
            path.quadraticTo(w * 0.20f, h * 0.15f, w * 0.20f, h * 0.45f)
            path.lineTo(w * 0.20f, h * 0.75f)
            path.moveTo(w * 0.06f, h * 0.58f)
            path.lineTo(w * 0.20f, h * 0.82f)
            path.lineTo(w * 0.34f, h * 0.58f)
        }
    }

    drawPath(
        path = path,
        color = color,
        style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
    )
}

class NavigationInstructionCache {
    var lastRoute: RouteResult? = null
    var lastDestinationCity: String = ""
    var lastTruckX: Float = Float.NaN
    var lastTruckZ: Float = Float.NaN
    var lastEdgeIdx: Int = 0
    var lastInstruction: NavigationInstruction? = null
}

/**
 * Analiza la ruta activa y posición del camión para deducir la próxima maniobra,
 * distancia y configuración de carriles de la vía.
 */
fun calculateNavigationInstruction(
    truckX: Float,
    truckZ: Float,
    route: RouteResult,
    destinationCity: String,
    cache: NavigationInstructionCache? = null
): NavigationInstruction? {
    val edges = route.edgePath
    if (edges.isEmpty()) return null

    // Si el camión se movió menos de 1.5 metros y la ruta/destino son iguales, devolver la instrucción en caché
    if (cache != null && cache.lastRoute === route && cache.lastDestinationCity == destinationCity && !cache.lastTruckX.isNaN()) {
        val dx = truckX - cache.lastTruckX
        val dz = truckZ - cache.lastTruckZ
        if (dx * dx + dz * dz < 2.25f && cache.lastInstruction != null) {
            return cache.lastInstruction
        }
    }

    // 1. Encontrar el tramo actual más cercano proyectando ortogonalmente sobre los segmentos
    var currentEdgeIdx = 0
    var minDistSq = Float.MAX_VALUE
    var remainingOnCurrentEdge = edges[0].length

    fun projectOntoEdge(edge: MapEdge): Pair<Float, Float> {
        val poly = edge.polyline
        if (poly.isEmpty()) return Pair(Float.MAX_VALUE, edge.length)
        if (poly.size == 1) {
            val pt = poly[0]
            val dSq = (pt.x - truckX) * (pt.x - truckX) + (pt.z - truckZ) * (pt.z - truckZ)
            return Pair(dSq, edge.length)
        }
        var bestSq = Float.MAX_VALUE
        var bestTraveled = 0f
        var accLen = 0f
        for (pIdx in 0 until poly.size - 1) {
            val p1 = poly[pIdx]
            val p2 = poly[pIdx + 1]
            val vx = p2.x - p1.x
            val vz = p2.z - p1.z
            val lenSq = vx * vx + vz * vz
            val segLen = sqrt(lenSq)
            val t = if (lenSq > 0.0001f) {
                (((truckX - p1.x) * vx + (truckZ - p1.z) * vz) / lenSq).coerceIn(0f, 1f)
            } else 0f
            val px = p1.x + t * vx
            val pz = p1.z + t * vz
            val dSq = (truckX - px) * (truckX - px) + (truckZ - pz) * (truckZ - pz)
            if (dSq < bestSq) {
                bestSq = dSq
                bestTraveled = accLen + t * segLen
            }
            accLen += segLen
        }
        val remaining = if (accLen > 0.1f) {
            val fracRemaining = (1f - bestTraveled / accLen).coerceIn(0f, 1f)
            edge.length * fracRemaining
        } else {
            edge.length
        }
        return Pair(bestSq, remaining)
    }

    val isSameRoute = (cache != null && cache.lastRoute === route)
    val startSearch = if (isSameRoute) (cache!!.lastEdgeIdx - 2).coerceAtLeast(0) else 0
    val endSearch = if (isSameRoute) (cache!!.lastEdgeIdx + 8).coerceAtMost(edges.size - 1) else edges.size - 1

    for (i in startSearch..endSearch) {
        val (dSq, rem) = projectOntoEdge(edges[i])
        if (dSq < minDistSq) {
            minDistSq = dSq
            currentEdgeIdx = i
            remainingOnCurrentEdge = rem
        }
    }

    // Si la búsqueda local no encontró ningún tramo cercano (< 60m), hacer búsqueda global
    if (minDistSq > 3600f && (startSearch > 0 || endSearch < edges.size - 1)) {
        for (i in edges.indices) {
            val (dSq, rem) = projectOntoEdge(edges[i])
            if (dSq < minDistSq) {
                minDistSq = dSq
                currentEdgeIdx = i
                remainingOnCurrentEdge = rem
            }
        }
    }

    val currentEdge = edges[currentEdgeIdx]

    // Determinar carriles directamente desde los metadatos de MapEdge y el tipo de vía
    val edgeLanes = currentEdge.lanes.toInt()
    val (totalLanes, isTwoPlusOne) = when {
        edgeLanes in 1..4 -> Pair(edgeLanes, edgeLanes == 3)
        currentEdge.roadType.toInt() == 0 -> Pair(3, false) // Autopista: 3 carriles
        currentEdge.roadType.toInt() == 1 -> Pair(2, true)  // Carretera principal: 2 o 2+1
        currentEdge.roadType.toInt() == 3 -> Pair(1, false) // Rampa: 1 carril
        else -> Pair(2, false)
    }

    // 2. Buscar la próxima maniobra de cambio de dirección significativo (> 20 grados) o rampa
    var distAcc = 0f
    var nextManeuver = ManeuverType.STRAIGHT
    var maneuverTitle = "Continúa en el trayecto"
    var maneuverSubtitle = if (destinationCity.isNotEmpty()) "Hacia $destinationCity" else "Ruta activa"
    var targetLane = (totalLanes - 1).coerceAtLeast(0) // Carril predeterminado derecho para camión

    for (i in currentEdgeIdx until edges.size - 1) {
        val eCurr = edges[i]
        val eNext = edges[i + 1]
        distAcc += if (i == currentEdgeIdx) remainingOnCurrentEdge else eCurr.length

        if (eCurr.polyline.size >= 2 && eNext.polyline.size >= 2) {
            val p1 = eCurr.polyline[eCurr.polyline.size - 2]
            val p2 = eCurr.polyline[eCurr.polyline.size - 1]
            val nextP0 = eNext.polyline[0]
            val nextP1 = eNext.polyline[1]

            val dx1 = (p2.x - p1.x).toDouble()
            val dz1 = (p2.z - p1.z).toDouble()
            val dx2 = (nextP1.x - nextP0.x).toDouble()
            val dz2 = (nextP1.z - nextP0.z).toDouble()

            if (dx1 * dx1 + dz1 * dz1 < 1e-4 || dx2 * dx2 + dz2 * dz2 < 1e-4) continue

            val angle1 = atan2(dz1, dx1)
            val angle2 = atan2(dz2, dx2)

            var diffDeg = Math.toDegrees(angle2 - angle1)
            while (diffDeg > 180.0) diffDeg -= 360.0
            while (diffDeg < -180.0) diffDeg += 360.0

            val isRamp = eNext.roadType.toInt() == 3
            val isShortPrefab = (eCurr.roadType.toInt() == 3 && eCurr.length <= 38f) ||
                (eNext.roadType.toInt() == 3 && eNext.length <= 38f)
            if (abs(diffDeg) >= 20.0 || isRamp) {
                var matched = true
                when {
                    (diffDeg > 120.0 || diffDeg < -120.0) && !isShortPrefab -> {
                        nextManeuver = ManeuverType.U_TURN
                        maneuverTitle = "Cambio de sentido en U"
                        targetLane = 0
                    }
                    isShortPrefab && diffDeg > 120.0 -> {
                        nextManeuver = ManeuverType.SHARP_RIGHT
                        maneuverTitle = "Gira a la derecha"
                        targetLane = totalLanes - 1
                    }
                    isShortPrefab && diffDeg < -120.0 -> {
                        nextManeuver = ManeuverType.SHARP_LEFT
                        maneuverTitle = "Gira a la izquierda"
                        targetLane = 0
                    }
                    diffDeg > 55.0 -> {
                        nextManeuver = ManeuverType.RIGHT
                        maneuverTitle = "Gira a la derecha"
                        targetLane = totalLanes - 1
                    }
                    diffDeg in 20.0..55.0 -> {
                        nextManeuver = ManeuverType.SLIGHT_RIGHT
                        maneuverTitle = if (isRamp) "Toma la salida a la derecha" else "Mantén a la derecha"
                        targetLane = totalLanes - 1
                    }
                    diffDeg < -55.0 -> {
                        nextManeuver = ManeuverType.LEFT
                        maneuverTitle = "Gira a la izquierda"
                        targetLane = 0
                    }
                    diffDeg in -55.0..-20.0 -> {
                        nextManeuver = ManeuverType.SLIGHT_LEFT
                        maneuverTitle = if (isRamp) "Toma la salida a la izquierda" else "Mantén a la izquierda"
                        targetLane = 0
                    }
                    isRamp -> {
                        if (diffDeg >= 6.0) {
                            nextManeuver = ManeuverType.SLIGHT_RIGHT
                            maneuverTitle = "Toma la salida a la derecha"
                            targetLane = totalLanes - 1
                        } else if (diffDeg <= -6.0) {
                            nextManeuver = ManeuverType.SLIGHT_LEFT
                            maneuverTitle = "Toma la salida a la izquierda"
                            targetLane = 0
                        } else {
                            matched = false
                        }
                    }
                    else -> matched = false
                }
                if (matched) break
            }
        }
    }

    val isCurrentHidden = currentEdge.isHidden
    if (nextManeuver == ManeuverType.STRAIGHT) {
        distAcc += if (currentEdgeIdx == edges.size - 1) {
            remainingOnCurrentEdge
        } else {
            edges.last().length
        }
    }
    if (isCurrentHidden && nextManeuver == ManeuverType.STRAIGHT) {
        maneuverTitle = "Camino Oculto / Atajo"
        maneuverSubtitle = "Tramo secreto no cartografiado"
    } else if (nextManeuver == ManeuverType.STRAIGHT) {
        maneuverTitle = "Continúa recto"
        targetLane = if (totalLanes > 2) totalLanes - 2 else totalLanes - 1 // Carril central/derecho
    }

    val result = NavigationInstruction(
        maneuver = nextManeuver,
        distanceMeters = distAcc,
        title = maneuverTitle,
        subtitle = maneuverSubtitle,
        totalLanes = totalLanes,
        activeLaneIndex = targetLane.coerceIn(0, totalLanes - 1),
        isTwoPlusOne = isTwoPlusOne,
        isHiddenRoad = isCurrentHidden
    )

    if (cache != null) {
        cache.lastRoute = route
        cache.lastDestinationCity = destinationCity
        cache.lastTruckX = truckX
        cache.lastTruckZ = truckZ
        cache.lastEdgeIdx = currentEdgeIdx
        cache.lastInstruction = result
    }

    return result
}