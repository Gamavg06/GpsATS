package com.ats.gps.data.model

import com.ats.gps.data.map.RouteResult

data class Point3D(val x: Float, val y: Float, val z: Float)

data class RoadSnapResult(
    val snappedX: Float,
    val snappedZ: Float,
    val edge: MapEdge,
    val segmentIndex: Int,
    val distanceSq: Float,
    val alignmentDot: Float = 1f,
    val signedLateralMeters: Float = 0f,
    val headingDeltaDeg: Float = 0f
)

data class RouteTrackingState(
    val isOffRoute: Boolean,
    val isWrongWay: Boolean,
    val distanceToRouteMeters: Float,
    val routeAlignmentDot: Float,
    val currentSnap: RoadSnapResult?,
    val signedLateralMeters: Float = 0f,
    val headingDeltaDeg: Float = 0f
)

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
    val roadType: Byte, // 0=Highway, 1=MajorRoad, 2=LocalStreet, 3=PrefabTurn, 4=Service, 5=HiddenRoad
    val lanes: Byte,
    val flags: Byte,
    val polyline: List<Point3D>
) {
    val isHidden: Boolean get() = roadType.toInt() == 5 || (flags.toInt() and 8) != 0
}

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
    val poiType: Byte, // 1=GasStation (GAS), 2=RestStop (RST), 3=ServiceRepair (SRV), 4=Dealership (DLR), 5=Company (LOG), 6=WeighStation (WGH)
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

    private fun findBestSnapInEdges(
        edges: List<MapEdge>,
        x: Float,
        z: Float,
        hx: Float,
        hz: Float,
        headingDeg: Float,
        minAlignmentDot: Float = -2.0f
    ): RoadSnapResult? {
        var bestResult: RoadSnapResult? = null
        var bestEffectiveDistSq = Float.MAX_VALUE

        for (edge in edges) {
            val poly = edge.polyline
            if (poly.size < 2) continue

            for (i in 0 until poly.size - 1) {
                val p1 = poly[i]
                val p2 = poly[i + 1]
                val vx = p2.x - p1.x
                val vz = p2.z - p1.z
                val lenSq = vx * vx + vz * vz

                val t = if (lenSq > 0.0001f) {
                    (((x - p1.x) * vx + (z - p1.z) * vz) / lenSq).coerceIn(0f, 1f)
                } else {
                    0f
                }

                val projX = p1.x + t * vx
                val projZ = p1.z + t * vz
                val dx = x - projX
                val dz = z - projZ
                val distSq = dx * dx + dz * dz

                val segLen = kotlin.math.sqrt(lenSq)
                val hasHeading = headingDeg.isFinite()
                val dot = if (!hasHeading || lenSq <= 0.01f) 1f else (vx * hx + vz * hz) / segLen
                if (hasHeading && dot < minAlignmentDot) continue
                var effectiveDistSq = distSq
                if (hasHeading) {
                    if (dot < -0.35f) {
                        effectiveDistSq = distSq * 5.0f
                    } else if (dot < 0.0f) {
                        effectiveDistSq = distSq * 3.0f
                    }
                }

                if (effectiveDistSq < bestEffectiveDistSq) {
                    bestEffectiveDistSq = effectiveDistSq
                    val signedLat = if (segLen > 0.01f) {
                        ((vx * (z - p1.z)) - (vz * (x - p1.x))) / segLen
                    } else 0f
                    var hDelta = if (hasHeading && lenSq > 0.01f) {
                        val segBearingDeg = Math.toDegrees(
                            kotlin.math.atan2(vx.toDouble(), -vz.toDouble())
                        ).toFloat()
                        (headingDeg - segBearingDeg) % 360f
                    } else 0f
                    if (hDelta > 180f) hDelta -= 360f
                    if (hDelta < -180f) hDelta += 360f

                    bestResult = RoadSnapResult(
                        snappedX = projX,
                        snappedZ = projZ,
                        edge = edge,
                        segmentIndex = i,
                        distanceSq = distSq,
                        alignmentDot = dot,
                        signedLateralMeters = signedLat,
                        headingDeltaDeg = hDelta
                    )
                }
            }
        }
        return bestResult
    }

    fun snapToRoad(
        x: Float,
        z: Float,
        headingDeg: Float,
        preferredEdges: List<MapEdge>? = null,
        maxDistMeters: Float = 26f
    ): RoadSnapResult? {
        if (edges.isEmpty()) return null

        val hasHeading = headingDeg.isFinite()
        val rad = if (hasHeading) Math.toRadians(headingDeg.toDouble()).toFloat() else 0f
        val hx = if (hasHeading) kotlin.math.sin(rad) else 0f
        val hz = if (hasHeading) -kotlin.math.cos(rad) else 0f
        val maxDistSq = maxDistMeters * maxDistMeters

        if (!preferredEdges.isNullOrEmpty()) {
            val prefMaxDist = kotlin.math.min(28f, maxDistMeters)
            val prefMaxDistSq = prefMaxDist * prefMaxDist
            val prefHit = findBestSnapInEdges(
                edges = preferredEdges,
                x = x,
                z = z,
                hx = hx,
                hz = hz,
                headingDeg = headingDeg,
                minAlignmentDot = if (hasHeading) 0.0f else -2.0f
            )
            if (prefHit != null && prefHit.distanceSq <= prefMaxDistSq && (!hasHeading || prefHit.alignmentDot >= 0.0f)) {
                return prefHit
            }
        }

        val candidates = spatialGrid.query(x, z, maxDistMeters * 2f)
        if (candidates.isEmpty()) return null

        val minDot = if (!hasHeading) -2.0f else if (preferredEdges == null) -0.1f else -2.0f
        val bestHit = findBestSnapInEdges(candidates, x, z, hx, hz, headingDeg, minDot)
        return if (bestHit != null && bestHit.distanceSq <= maxDistSq && (!hasHeading || preferredEdges != null || bestHit.alignmentDot >= -0.1f)) {
            bestHit
        } else {
            null
        }
    }

    fun findBestRouteStartNode(x: Float, z: Float, headingDeg: Float): Int {
        val snap = snapToRoad(x, z, headingDeg, null, 45f)
        if (snap != null && adjacency.containsKey(snap.edge.toNodeIndex)) {
            return snap.edge.toNodeIndex
        }
        if (snap != null && adjacency.containsKey(snap.edge.fromNodeIndex)) {
            return snap.edge.fromNodeIndex
        }
        return findNearestNode(x, z, mustHaveEdges = true)
    }

    fun evaluateRouteTracking(
        x: Float,
        z: Float,
        headingDeg: Float,
        speedKmh: Float,
        gear: Int,
        activeRoute: RouteResult?
    ): RouteTrackingState {
        val hasHeading = headingDeg.isFinite()
        val rad = if (hasHeading) Math.toRadians(headingDeg.toDouble()).toFloat() else 0f
        val hx = if (hasHeading) kotlin.math.sin(rad) else 0f
        val hz = if (hasHeading) -kotlin.math.cos(rad) else 0f

        val currentSnap = snapToRoad(x, z, headingDeg, activeRoute?.edgePath, maxDistMeters = 28f)

        var isOffRoute = false
        var minDist = 0f
        var routeDot = 1f

        if (activeRoute != null && activeRoute.edgePath.isNotEmpty()) {
            var bestAnyDistSq = Float.MAX_VALUE
            var bestAnyDot = 1f
            var bestAlignedDistSq = Float.MAX_VALUE
            var bestAlignedDot = 1f

            for (edge in activeRoute.edgePath) {
                val poly = edge.polyline
                if (poly.size < 2) continue
                for (i in 0 until poly.size - 1) {
                    val p1 = poly[i]
                    val p2 = poly[i + 1]
                    val vx = p2.x - p1.x
                    val vz = p2.z - p1.z
                    val lenSq = vx * vx + vz * vz
                    val t = if (lenSq > 0.0001f) {
                        (((x - p1.x) * vx + (z - p1.z) * vz) / lenSq).coerceIn(0f, 1f)
                    } else 0f
                    val projX = p1.x + t * vx
                    val projZ = p1.z + t * vz
                    val dx = x - projX
                    val dz = z - projZ
                    val distSq = dx * dx + dz * dz

                    val dot = if (hasHeading && lenSq > 0.0001f) {
                        (vx * hx + vz * hz) / kotlin.math.sqrt(lenSq)
                    } else 1f

                    if (distSq < bestAnyDistSq) {
                        bestAnyDistSq = distSq
                        bestAnyDot = dot
                    }
                    if (dot >= -0.35f && distSq < bestAlignedDistSq) {
                        bestAlignedDistSq = distSq
                        bestAlignedDot = dot
                    }
                }
            }

            val effectiveDistSq = if (bestAlignedDistSq <= 676f) bestAlignedDistSq else bestAnyDistSq
            minDist = if (effectiveDistSq < Float.MAX_VALUE) kotlin.math.sqrt(effectiveDistSq) else 0f
            routeDot = if (bestAlignedDistSq <= 676f) bestAlignedDot else bestAnyDot
            isOffRoute = (minDist > 26f) || (hasHeading && speedKmh > 8f && gear > 0 && routeDot < -0.35f)
        }

        val isWrongWay = if (hasHeading && speedKmh > 18f && gear > 0) {
            val nearby = spatialGrid.query(x, z, 25f)
            var minSegDistSq = Float.MAX_VALUE
            var maxOneWayDotWithin12m = -2f
            var hasOneWayWithin12m = false
            var hasAlignedEdgeWithin18m = false

            for (edge in nearby) {
                val poly = edge.polyline
                if (poly.size < 2) continue
                val isOneWay = edge.roadType.toInt() == 3 || (edge.flags.toInt() and 1) != 0
                for (i in 0 until poly.size - 1) {
                    val p1 = poly[i]
                    val p2 = poly[i + 1]
                    val vx = p2.x - p1.x
                    val vz = p2.z - p1.z
                    val lenSq = vx * vx + vz * vz
                    val t = if (lenSq > 0.0001f) {
                        (((x - p1.x) * vx + (z - p1.z) * vz) / lenSq).coerceIn(0f, 1f)
                    } else 0f
                    val projX = p1.x + t * vx
                    val projZ = p1.z + t * vz
                    val dx = x - projX
                    val dz = z - projZ
                    val distSq = dx * dx + dz * dz

                    if (distSq < minSegDistSq) {
                        minSegDistSq = distSq
                    }
                    if (lenSq > 0.01f) {
                        val dot = (vx * hx + vz * hz) / kotlin.math.sqrt(lenSq)
                        if (distSq <= 324f && dot > 0.25f) {
                            hasAlignedEdgeWithin18m = true
                        }
                        if (isOneWay && distSq <= 144f) {
                            hasOneWayWithin12m = true
                            if (dot > maxOneWayDotWithin12m) {
                                maxOneWayDotWithin12m = dot
                            }
                        }
                    }
                }
            }

            minSegDistSq <= 64f &&
                hasOneWayWithin12m &&
                maxOneWayDotWithin12m < -0.75f &&
                !hasAlignedEdgeWithin18m
        } else {
            false
        }

        return RouteTrackingState(
            isOffRoute = isOffRoute,
            isWrongWay = isWrongWay,
            distanceToRouteMeters = minDist,
            routeAlignmentDot = routeDot,
            currentSnap = currentSnap,
            signedLateralMeters = currentSnap?.signedLateralMeters ?: 0f,
            headingDeltaDeg = currentSnap?.headingDeltaDeg ?: 0f
        )
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
