package com.ats.gps.data.map

import com.ats.gps.data.model.MapEdge
import com.ats.gps.data.model.MapGraph
import java.util.PriorityQueue
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class RouteResult(
    val nodePath: List<Int>,
    val edgePath: List<MapEdge>,
    val totalDistanceMeters: Float
)

object AStarRouter {

    fun findRoute(
        graph: MapGraph,
        startNode: Int,
        targetNode: Int,
        headingDeg: Float = Float.NaN,
        initialEdge: MapEdge? = null,
        isCancelled: () -> Boolean = { false }
    ): RouteResult? {
        val nodeCount = graph.nodes.size
        if (nodeCount == 0 || startNode !in 0 until nodeCount || targetNode !in 0 until nodeCount) {
            return null
        }
        if (startNode == targetNode) {
            return RouteResult(listOf(startNode), emptyList(), 0f)
        }
        if (isCancelled()) return null

        val route = executeAStar(graph, startNode, targetNode, nodeCount, headingDeg, initialEdge, isCancelled)
        if (route != null) return route
        if (isCancelled()) return null

        // Si el nodo exacto esta desconectado o en sentido contrario, buscar un nodo adyacente conectado
        val candidateStarts = graph.adjacency[startNode]
            ?.filter { it.toNodeIndex != initialEdge?.fromNodeIndex }
            ?.map { it.toNodeIndex }
            ?.take(4) ?: emptyList()
        for (cand in candidateStarts) {
            if (isCancelled()) return null
            if (cand in 0 until nodeCount && cand != targetNode) {
                val altRoute = executeAStar(graph, cand, targetNode, nodeCount, headingDeg, initialEdge, isCancelled)
                if (altRoute != null) return altRoute
            }
        }
        if (isCancelled()) return null

        // Busqueda de respaldo conservando headingDeg e initialEdge (evitando giros atras)
        val startPt = graph.nodes[startNode]
        val nearbyStartEdges = graph.getEdgesNear(startPt.x, startPt.z, 350f)
            .sortedBy { edge ->
                val node = graph.nodes.getOrNull(edge.fromNodeIndex)
                if (node != null) {
                    val dx = node.x - startPt.x
                    val dz = node.z - startPt.z
                    dx * dx + dz * dz
                } else Float.MAX_VALUE
            }
        for (edge in nearbyStartEdges.take(6)) {
            if (isCancelled()) return null
            val cand = edge.fromNodeIndex
            if (cand in 0 until nodeCount && cand != startNode && cand != targetNode) {
                val altRoute = executeAStar(graph, cand, targetNode, nodeCount, headingDeg, initialEdge, isCancelled)
                if (altRoute != null) return altRoute
            }
        }
        if (isCancelled()) return null

        // Busqueda de respaldo: aristas cercanas a targetNode (< 350m)
        val targetPt = graph.nodes[targetNode]
        val nearbyTargetEdges = graph.getEdgesNear(targetPt.x, targetPt.z, 350f)
            .sortedBy { edge ->
                val node = graph.nodes.getOrNull(edge.toNodeIndex)
                if (node != null) {
                    val dx = node.x - targetPt.x
                    val dz = node.z - targetPt.z
                    dx * dx + dz * dz
                } else Float.MAX_VALUE
            }
        for (edge in nearbyTargetEdges.take(6)) {
            if (isCancelled()) return null
            val candTarget = edge.toNodeIndex
            if (candTarget in 0 until nodeCount && candTarget != targetNode && candTarget != startNode) {
                val altRoute = executeAStar(graph, startNode, candTarget, nodeCount, headingDeg, initialEdge, isCancelled)
                if (altRoute != null) return altRoute
            }
        }
        if (isCancelled()) return null

        // Ultimo recurso si no existe ruta hacia adelante (callejon sin salida): relajar headingDeg
        if (headingDeg.isFinite() || initialEdge != null) {
            val fallbackDirect = executeAStar(graph, startNode, targetNode, nodeCount, Float.NaN, null, isCancelled)
            if (fallbackDirect != null) return fallbackDirect

            for (edge in nearbyStartEdges.take(6)) {
                if (isCancelled()) return null
                val cand = edge.fromNodeIndex
                if (cand in 0 until nodeCount && cand != startNode && cand != targetNode) {
                    val altRoute = executeAStar(graph, cand, targetNode, nodeCount, Float.NaN, null, isCancelled)
                    if (altRoute != null) return altRoute
                }
            }
        }

        return null
    }

    private fun executeAStar(
        graph: MapGraph,
        startNode: Int,
        targetNode: Int,
        nodeCount: Int,
        headingDeg: Float,
        initialEdge: MapEdge?,
        isCancelled: () -> Boolean
    ): RouteResult? {
        val adjacency = graph.adjacency
        val nodes = graph.nodes

        val hasHeading = headingDeg.isFinite()
        val hx: Float
        val hz: Float
        if (hasHeading) {
            val rad = Math.toRadians(headingDeg.toDouble()).toFloat()
            hx = sin(rad)
            hz = -cos(rad)
        } else {
            hx = 0f
            hz = 0f
        }

        // Arreglos primitivos planos: 0 boxing, rendimiento nativo a nivel de caché
        val gScore = FloatArray(nodeCount) { Float.MAX_VALUE }
        val cameFromNode = IntArray(nodeCount) { -1 }
        val cameFromEdge = arrayOfNulls<MapEdge>(nodeCount)

        val openSet = PriorityQueue<NodeScore>(compareBy { it.fScore })

        gScore[startNode] = 0f
        openSet.add(NodeScore(startNode, 0f, heuristic(graph, startNode, targetNode)))

        val maxSteps = 500000 // Límite de seguridad para evitar bloqueos
        var steps = 0

        while (!openSet.isEmpty() && steps++ < maxSteps) {
            if ((steps and 511) == 0 && isCancelled()) return null
            val currentScore = openSet.poll() ?: break
            val current = currentScore.nodeIndex

            if (currentScore.gScore > gScore[current]) {
                continue
            }

            if (current == targetNode) {
                // Reconstruir camino
                val nodePath = ArrayList<Int>()
                val edgePath = ArrayList<MapEdge>()
                var curr = current
                nodePath.add(curr)

                while (cameFromNode[curr] != -1) {
                    val prev = cameFromNode[curr]
                    val edge = cameFromEdge[curr] ?: break
                    edgePath.add(edge)
                    nodePath.add(prev)
                    curr = prev
                }

                nodePath.reverse()
                edgePath.reverse()
                val totalDist = edgePath.sumOf { it.length.toDouble() }.toFloat()
                return RouteResult(nodePath, edgePath, totalDist)
            }

            val edges = adjacency[current] ?: continue
            val currentG = gScore[current]
            val prevNode = cameFromNode[current]
            val prevEdge = cameFromEdge[current]

            for (edge in edges) {
                val neighbor = edge.toNodeIndex
                if (neighbor !in 0 until nodeCount) continue

                // Prohibir retroceso inmediato A -> B -> A cuando hay alternativas
                if (prevNode != -1 && neighbor == prevNode && edges.size > 1) continue

                // Prohibir vuelta atras inmediata hacia el nodo origen de initialEdge
                if (current == startNode && initialEdge != null && neighbor == initialEdge.fromNodeIndex) {
                    continue
                }

                var initialReversePenalty = 0f
                if (current == startNode && hasHeading) {
                    val dx = nodes[neighbor].x - nodes[startNode].x
                    val dz = nodes[neighbor].z - nodes[startNode].z
                    val len = sqrt((dx * dx + dz * dz).toDouble()).toFloat()
                    if (initialEdge != null && edge.edgeId == initialEdge.edgeId) {
                        initialReversePenalty = 0f
                    } else if (len > 0.01f) {
                        val dot = (dx * hx + dz * hz) / len
                        if (dot < -0.35f) {
                            initialReversePenalty = 45000f
                        } else if (dot < -0.20f) {
                            initialReversePenalty = 4500f
                        }
                    }
                }

                var turnPenalty = 0f
                if (prevNode != -1) {
                    val inDx = nodes[current].x - nodes[prevNode].x
                    val inDz = nodes[current].z - nodes[prevNode].z
                    val outDx = nodes[neighbor].x - nodes[current].x
                    val outDz = nodes[neighbor].z - nodes[current].z
                    val inLen = sqrt((inDx * inDx + inDz * inDz).toDouble()).toFloat()
                    val outLen = sqrt((outDx * outDx + outDz * outDz).toDouble()).toFloat()

                    val hasValidVecs = inLen > 0.1f && outLen > 0.1f
                    val cosTurn = if (hasValidVecs) (inDx * outDx + inDz * outDz) / (inLen * outLen) else 1f
                    if (hasValidVecs) {
                        if (cosTurn < -0.60f) {
                            turnPenalty += 3200f // Giro en U ilegal o > 127°
                        } else if (cosTurn < -0.15f) {
                            turnPenalty += 140f  // Giro cerrado > 98°
                        }
                    }

                    // Penalizar cruces sintéticos de mediana en autopistas
                    if (edge.roadType.toInt() == 3 && edge.length <= 15.5f && edge.flags.toInt() == 0 &&
                        (prevEdge?.roadType?.toInt() == 0 || (hasValidVecs && cosTurn < 0.3f))
                    ) {
                        turnPenalty += 2500f
                    }
                }

                // Costo por velocidad y jerarquía vial (estilo ATS Route Advisor)
                val speed = edge.speedLimitKmh.coerceIn(35f, 115f)
                val speedFactor = 105f / speed
                val hierarchyFactor = when (edge.roadType.toInt()) {
                    0 -> 1.0f
                    1 -> 1.15f
                    2 -> 1.38f
                    3 -> 1.30f
                    5 -> 1.22f
                    else -> 1.35f
                }
                val semaphorePenalty = if ((edge.flags.toInt() and 2) != 0) 65f else 0f
                val edgeCost = edge.length * speedFactor * hierarchyFactor +
                    semaphorePenalty + turnPenalty + initialReversePenalty

                val tentativeG = currentG + edgeCost

                if (tentativeG < gScore[neighbor]) {
                    cameFromNode[neighbor] = current
                    cameFromEdge[neighbor] = edge
                    gScore[neighbor] = tentativeG
                    val fScore = tentativeG + heuristic(graph, neighbor, targetNode)
                    openSet.add(NodeScore(neighbor, tentativeG, fScore))
                }
            }
        }

        return null
    }

    private fun heuristic(graph: MapGraph, a: Int, b: Int): Float {
        val na = graph.nodes[a]
        val nb = graph.nodes[b]
        val dx = na.x - nb.x
        val dz = na.z - nb.z
        return sqrt((dx * dx + dz * dz).toDouble()).toFloat()
    }

    private data class NodeScore(val nodeIndex: Int, val gScore: Float, val fScore: Float)
}
