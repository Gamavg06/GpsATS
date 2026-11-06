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
        if (startNode < 0 || startNode >= graph.nodes.size || targetNode < 0 || targetNode >= graph.nodes.size) {
            return null
        }
        if (startNode == targetNode) {
            return RouteResult(listOf(startNode), emptyList(), 0f)
        }

        // Construir mapa de adyacencia
        val adjacency = HashMap<Int, MutableList<MapEdge>>()
        for (edge in graph.edges) {
            adjacency.getOrPut(edge.fromNodeIndex) { ArrayList() }.add(edge)
        }

        val openSet = PriorityQueue<NodeScore>(compareBy { it.fScore })
        val cameFromEdge = HashMap<Int, MapEdge>()
        val cameFromNode = HashMap<Int, Int>()
        val gScore = HashMap<Int, Float>()

        gScore[startNode] = 0f
        openSet.add(NodeScore(startNode, 0f, heuristic(graph, startNode, targetNode)))

        while (!openSet.isEmpty()) {
            val currentScore = openSet.poll() ?: break
            val current = currentScore.nodeIndex

            // Poda de entrada obsoleta al extraer de la cola de prioridad
            val bestKnownG = gScore[current] ?: Float.MAX_VALUE
            if (currentScore.gScore > bestKnownG) {
                continue
            }

            if (current == targetNode) {
                // Reconstruir camino
                val nodePath = ArrayList<Int>()
                val edgePath = ArrayList<MapEdge>()
                var curr = current
                nodePath.add(curr)

                while (cameFromNode.containsKey(curr)) {
                    val prev = cameFromNode[curr]!!
                    val edge = cameFromEdge[curr]!!
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
            val currentG = gScore[current] ?: Float.MAX_VALUE

            for (edge in edges) {
                val neighbor = edge.toNodeIndex
                // Penalizaciones por tipo de vía: Preferir autopista (peso 1.0) sobre calles lentas o giros (peso 1.3)
                val weight = when (edge.roadType.toInt()) {
                    0 -> 1.0f  // Autopista interestatal
                    1 -> 1.15f // Vía primaria
                    3 -> 1.4f  // Giro de prefab / rampa
                    else -> 1.25f
                }
                val tentativeG = currentG + edge.length * weight

                if (tentativeG < (gScore[neighbor] ?: Float.MAX_VALUE)) {
                    cameFromNode[neighbor] = current
                    cameFromEdge[neighbor] = edge
                    gScore[neighbor] = tentativeG
                    val fScore = tentativeG + heuristic(graph, neighbor, targetNode)
                    openSet.add(NodeScore(neighbor, tentativeG, fScore))
                }
            }
        }

        return null // No hay ruta alcanzable
    }

    private fun heuristic(graph: MapGraph, a: Int, b: Int): Float {
        val na = graph.NodesSafe(a)
        val nb = graph.NodesSafe(b)
        val dx = na.x - nb.x
        val dz = na.z - nb.z
        return sqrt((dx * dx + dz * dz).toDouble()).toFloat()
    }

    private fun MapGraph.NodesSafe(index: Int) = nodes.getOrElse(index) { nodes[0] }

    private data class NodeScore(val nodeIndex: Int, val gScore: Float, val fScore: Float)
}
