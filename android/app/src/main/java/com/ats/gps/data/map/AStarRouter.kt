package com.ats.gps.data.map

import com.ats.gps.data.model.MapEdge
import com.ats.gps.data.model.MapGraph
import java.util.PriorityQueue
import kotlin.math.sqrt

data class RouteResult(
    val nodePath: List<Int>,
    val edgePath: List<MapEdge>,
    val totalDistanceMeters: Float
)

object AStarRouter {

    fun findRoute(graph: MapGraph, startNode: Int, targetNode: Int): RouteResult? {
        val nodeCount = graph.nodes.size
        if (nodeCount == 0 || startNode !in 0 until nodeCount || targetNode !in 0 until nodeCount) {
            return null
        }
        if (startNode == targetNode) {
            return RouteResult(listOf(startNode), emptyList(), 0f)
        }

        val route = executeAStar(graph, startNode, targetNode, nodeCount)
        if (route != null) return route

        // Si el nodo exacto está desconectado o en sentido contrario, buscar un nodo adyacente conectado
        val candidateStarts = graph.adjacency[startNode]?.map { it.toNodeIndex }?.take(4) ?: emptyList()
        for (cand in candidateStarts) {
            if (cand in 0 until nodeCount && cand != targetNode) {
                val altRoute = executeAStar(graph, cand, targetNode, nodeCount)
                if (altRoute != null) return altRoute
            }
        }

        return null
    }

    private fun executeAStar(graph: MapGraph, startNode: Int, targetNode: Int, nodeCount: Int): RouteResult? {
        val adjacency = graph.adjacency

        // Arreglos primitivos planos: 0 boxing, rendimiento nativo a nivel de caché
        val gScore = FloatArray(nodeCount) { Float.MAX_VALUE }
        val cameFromNode = IntArray(nodeCount) { -1 }
        val cameFromEdge = arrayOfNulls<MapEdge>(nodeCount)

        val openSet = PriorityQueue<NodeScore>(compareBy { it.fScore })

        gScore[startNode] = 0f
        openSet.add(NodeScore(startNode, 0f, heuristic(graph, startNode, targetNode)))

        var maxSteps = 150000 // Límite de seguridad para evitar bloqueos
        var steps = 0

        while (!openSet.isEmpty() && steps++ < maxSteps) {
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

            for (edge in edges) {
                val neighbor = edge.toNodeIndex
                if (neighbor !in 0 until nodeCount) continue

                // Factor de preferencia de carretera
                val weight = when (edge.roadType.toInt()) {
                    0 -> 1.0f  // Autopistas interestatales
                    1 -> 1.12f // Carreteras principales
                    3 -> 1.35f // Curvas/rampas de intercambiador
                    else -> 1.25f
                }
                val tentativeG = currentG + edge.length * weight

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
