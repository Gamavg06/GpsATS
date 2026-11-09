package com.ats.gps.data.model

data class Point3D(val x: Float, val y: Float, val z: Float)

data class MapNode(
    val index: Int,
    val uid: Long,
    val x: Float,
    val y: Float,
    val z: Float
)

data class MapEdge(
    val edgeId: Int,
    val fromNodeIndex: Int,
    val toNodeIndex: Int,
    val length: Float,
    val speedLimitKmh: Float,
    val roadType: Byte, // 0=Highway, 1=MajorRoad, 2=LocalStreet, 3=PrefabTurn, 4=Service
    val lanes: Byte,
    val flags: Byte,
    val polyline: List<Point3D>
)

data class MapCity(
    val cityToken: Long,
    val name: String,
    val x: Float,
    val z: Float,
    val nearestNodeIndex: Int
)

data class MapCompany(
    val companyToken: Long,
    val cityToken: Long,
    val name: String,
    val cityName: String,
    val x: Float,
    val z: Float,
    val nearestNodeIndex: Int
)

data class MapPoi(
    val poiId: Int,
    val poiType: Byte, // 1=GasStation (⛽), 2=RestStop (🛏️), 3=ServiceRepair (🔧), 4=Dealership (🏬), 5=Company (🏢), 6=WeighStation (⚖️)
    val name: String,
    val x: Float,
    val y: Float,
    val z: Float
)

data class MapGraph(
    val nodes: List<MapNode>,
    val edges: List<MapEdge>,
    val cities: List<MapCity>,
    val companies: List<MapCompany>,
    val pois: List<MapPoi> = emptyList(),
    val minX: Float,
    val maxX: Float,
    val minZ: Float,
    val maxZ: Float
) {
    val spatialGrid: SpatialGrid by lazy {
        SpatialGrid(1000f).apply { populate(edges) }
    }

    val adjacency: Map<Int, List<MapEdge>> by lazy {
        val map = HashMap<Int, MutableList<MapEdge>>(nodes.size)
        for (edge in edges) {
            map.getOrPut(edge.fromNodeIndex) { ArrayList() }.add(edge)
        }
        map
    }

    fun getEdgesNear(centerX: Float, centerZ: Float, radius: Float): List<MapEdge> {
        return spatialGrid.query(centerX, centerZ, radius)
    }

    fun findNearestNode(x: Float, z: Float, mustHaveEdges: Boolean = true): Int {
        if (nodes.isEmpty()) return 0
        var bestIndex = 0
        var bestDistSq = Double.MAX_VALUE
        val adj = if (mustHaveEdges) adjacency else null

        // 1. Aceleración espacial: consultar aristas a menos de 400m de la posición
        val nearbyEdges = spatialGrid.query(x, z, 400f)
        if (nearbyEdges.isNotEmpty()) {
            for (edge in nearbyEdges) {
                val n1 = edge.fromNodeIndex
                val n2 = edge.toNodeIndex
                if (n1 in nodes.indices && (adj == null || adj.containsKey(n1))) {
                    val dx = (nodes[n1].x - x).toDouble()
                    val dz = (nodes[n1].z - z).toDouble()
                    val dSq = dx * dx + dz * dz
                    if (dSq < bestDistSq) {
                        bestDistSq = dSq
                        bestIndex = n1
                    }
                }
                if (n2 in nodes.indices && (adj == null || adj.containsKey(n2))) {
                    val dx = (nodes[n2].x - x).toDouble()
                    val dz = (nodes[n2].z - z).toDouble()
                    val dSq = dx * dx + dz * dz
                    if (dSq < bestDistSq) {
                        bestDistSq = dSq
                        bestIndex = n2
                    }
                }
            }
            if (bestDistSq < 160000.0) { // Encontrado a menos de 400 metros
                return bestIndex
            }
        }

        // 2. Respaldo lineal completo
        for (i in nodes.indices) {
            if (adj != null && !adj.containsKey(i)) continue
            val dx = (nodes[i].x - x).toDouble()
            val dz = (nodes[i].z - z).toDouble()
            val dSq = dx * dx + dz * dz
            if (dSq < bestDistSq) {
                bestDistSq = dSq
                bestIndex = i
            }
        }
        return bestIndex
    }

    fun findNearestCity(x: Float, z: Float): MapCity? {
        if (cities.isEmpty()) return null
        var bestCity: MapCity? = null
        var bestDistSq = Double.MAX_VALUE
        for (c in cities) {
            val dx = (c.x - x).toDouble()
            val dz = (c.z - z).toDouble()
            val dSq = dx * dx + dz * dz
            if (dSq < bestDistSq) {
                bestDistSq = dSq
                bestCity = c
            }
        }
        return bestCity
    }

    fun findNearestRestStop(x: Float, z: Float): Pair<MapPoi, Float>? {
        if (pois.isEmpty()) return null
        var bestPoi: MapPoi? = null
        var bestDistSq = Double.MAX_VALUE
        for (poi in pois) {
            // poiType: 1=GasStation (con descanso), 2=RestStop (área de descanso dedicada)
            if (poi.poiType == 1.toByte() || poi.poiType == 2.toByte()) {
                val dx = (poi.x - x).toDouble()
                val dz = (poi.z - z).toDouble()
                val dSq = dx * dx + dz * dz
                if (dSq < bestDistSq) {
                    bestDistSq = dSq
                    bestPoi = poi
                }
            }
        }
        return if (bestPoi != null) {
            val distKm = (kotlin.math.sqrt(bestDistSq) / 1000f).toFloat()
            Pair(bestPoi, distKm)
        } else null
    }
}

class SpatialGrid(val cellSize: Float = 1000f) {
    private val cellMap = HashMap<Long, MutableList<MapEdge>>()

    fun populate(edges: List<MapEdge>) {
        cellMap.clear()
        for (edge in edges) {
            if (edge.polyline.isEmpty()) continue
            var minX = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE
            var minZ = Float.MAX_VALUE
            var maxZ = -Float.MAX_VALUE
            for (pt in edge.polyline) {
                if (pt.x < minX) minX = pt.x
                if (pt.x > maxX) maxX = pt.x
                if (pt.z < minZ) minZ = pt.z
                if (pt.z > maxZ) maxZ = pt.z
            }
            val minCx = kotlin.math.floor(minX / cellSize).toInt()
            val maxCx = kotlin.math.floor(maxX / cellSize).toInt()
            val minCz = kotlin.math.floor(minZ / cellSize).toInt()
            val maxCz = kotlin.math.floor(maxZ / cellSize).toInt()

            for (cx in minCx..maxCx) {
                for (cz in minCz..maxCz) {
                    val key = (cx.toLong() shl 32) or (cz.toLong() and 0xFFFFFFFFL)
                    cellMap.getOrPut(key) { ArrayList() }.add(edge)
                }
            }
        }
    }

    fun query(centerX: Float, centerZ: Float, radius: Float): List<MapEdge> {
        val minCx = kotlin.math.floor((centerX - radius) / cellSize).toInt()
        val maxCx = kotlin.math.floor((centerX + radius) / cellSize).toInt()
        val minCz = kotlin.math.floor((centerZ - radius) / cellSize).toInt()
        val maxCz = kotlin.math.floor((centerZ + radius) / cellSize).toInt()

        val resultSet = HashSet<MapEdge>()
        for (cx in minCx..maxCx) {
            for (cz in minCz..maxCz) {
                val key = (cx.toLong() shl 32) or (cz.toLong() and 0xFFFFFFFFL)
                cellMap[key]?.let { resultSet.addAll(it) }
            }
        }
        return resultSet.toList()
    }
}
