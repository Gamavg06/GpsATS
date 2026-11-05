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
    fun findNearestNode(x: Float, z: Float): Int {
        if (nodes.isEmpty()) return 0
        var bestIndex = 0
        var bestDistSq = Double.MAX_VALUE
        for (i in nodes.indices) {
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
}
