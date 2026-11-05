package com.ats.gps.data.map

import com.ats.gps.data.model.*
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object BinaryMapReader {

    fun read(inputStream: InputStream): MapGraph {
        val bytes = inputStream.readBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        // 1. Header (ATSMAP03 o ATSMAP02)
        val magic = ByteArray(8)
        buffer.get(magic)
        val magicStr = String(magic, Charsets.US_ASCII)
        if (magicStr != "ATSMAP03" && magicStr != "ATSMAP02") {
            throw IllegalArgumentException("Formato de mapa inválido: $magicStr")
        }

        val version = buffer.int
        val nodeCount = buffer.int
        val edgeCount = buffer.int
        val cityCount = buffer.int
        val companyCount = buffer.int
        val poiCount = if (magicStr == "ATSMAP03") buffer.int else 0

        val minX = buffer.float
        val maxX = buffer.float
        val minZ = buffer.float
        val maxZ = buffer.float

        // 2. Nodos
        val nodes = ArrayList<MapNode>(nodeCount)
        for (i in 0 until nodeCount) {
            val uid = buffer.long
            val x = buffer.float
            val y = buffer.float
            val z = buffer.float
            nodes.add(MapNode(i, uid, x, y, z))
        }

        // 3. Aristas
        val edges = ArrayList<MapEdge>(edgeCount)
        for (i in 0 until edgeCount) {
            val fromNode = buffer.int
            val toNode = buffer.int
            val length = buffer.float
            val speedLimit = buffer.float
            val roadType = buffer.get()
            val lanes = buffer.get()
            val flags = buffer.get()

            val ptCount = buffer.short.toInt() and 0xFFFF
            val polyline = ArrayList<Point3D>(ptCount)
            for (p in 0 until ptCount) {
                polyline.add(Point3D(buffer.float, buffer.float, buffer.float))
            }

            edges.add(MapEdge(i, fromNode, toNode, length, speedLimit, roadType, lanes, flags, polyline))
        }

        // 4. Ciudades
        val cities = ArrayList<MapCity>(cityCount)
        for (i in 0 until cityCount) {
            val cityToken = buffer.long
            val name = readString(buffer)
            val x = buffer.float
            val z = buffer.float
            val nearest = buffer.int
            cities.add(MapCity(cityToken, name, x, z, nearest))
        }

        // 5. Empresas
        val companies = ArrayList<MapCompany>(companyCount)
        for (i in 0 until companyCount) {
            val companyToken = buffer.long
            val cityToken = buffer.long
            val name = readString(buffer)
            val cityName = readString(buffer)
            val x = buffer.float
            val z = buffer.float
            val nearest = buffer.int
            companies.add(MapCompany(companyToken, cityToken, name, cityName, x, z, nearest))
        }

        // 6. POIs (Gasolineras, Descanso, Talleres, Concesionarios, Empresas)
        val pois = ArrayList<MapPoi>(poiCount)
        for (i in 0 until poiCount) {
            val poiId = buffer.int
            val poiType = buffer.get()
            val name = readString(buffer)
            val x = buffer.float
            val y = buffer.float
            val z = buffer.float
            pois.add(MapPoi(poiId, poiType, name, x, y, z))
        }

        return MapGraph(nodes, edges, cities, companies, pois, minX, maxX, minZ, maxZ)
    }

    private fun readString(buffer: ByteBuffer): String {
        // En .NET BinaryWriter.Write(string), se escribe longitud 7-bit encoded int
        var count = 0
        var shift = 0
        while (shift != 35) {
            val b = buffer.get().toInt()
            count = count or ((b and 0x7F) shl shift)
            shift += 7
            if ((b and 0x80) == 0) break
        }
        val strBytes = ByteArray(count)
        buffer.get(strBytes)
        return String(strBytes, Charsets.UTF_8)
    }
}
