package com.ats.gps.data.map

import com.ats.gps.data.model.*
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object BinaryMapReader {

    fun read(file: File): MapGraph {
        BufferedInputStream(FileInputStream(file), 65536).use { bis ->
            return read(bis)
        }
    }

    fun read(inputStream: InputStream): MapGraph {
        val stream = if (inputStream is BufferedInputStream) inputStream else BufferedInputStream(inputStream, 65536)
        val scratch = ByteArray(8)
        val scratchBuf = ByteBuffer.wrap(scratch).order(ByteOrder.LITTLE_ENDIAN)

        fun readFully(dst: ByteArray, len: Int) {
            var offset = 0
            while (offset < len) {
                val n = stream.read(dst, offset, len - offset)
                if (n < 0) throw EOFException("Fin inesperado leyendo mapa binario")
                offset += n
            }
        }

        fun readByte(): Byte {
            val v = stream.read()
            if (v < 0) throw EOFException("Fin inesperado leyendo mapa binario")
            return v.toByte()
        }

        fun readShortUnsigned(): Int {
            readFully(scratch, 2)
            scratchBuf.position(0)
            return scratchBuf.short.toInt() and 0xFFFF
        }

        fun readInt(): Int {
            readFully(scratch, 4)
            scratchBuf.position(0)
            return scratchBuf.int
        }

        fun readFloat(): Float {
            readFully(scratch, 4)
            scratchBuf.position(0)
            return scratchBuf.float
        }

        fun readLong(): Long {
            readFully(scratch, 8)
            scratchBuf.position(0)
            return scratchBuf.long
        }

        fun readString(): String {
            var count = 0
            var shift = 0
            while (shift != 35) {
                val b = readByte().toInt() and 0xFF
                count = count or ((b and 0x7F) shl shift)
                shift += 7
                if ((b and 0x80) == 0) break
            }
            if (count < 0 || count > 1_048_576) {
                throw IllegalArgumentException("Longitud de cadena invalida en mapa: $count")
            }
            if (count == 0) return ""
            val strBytes = ByteArray(count)
            readFully(strBytes, count)
            return String(strBytes, Charsets.UTF_8)
        }

        // 1. Header (ATSMAP03 o ATSMAP02)
        val magic = ByteArray(8)
        readFully(magic, 8)
        val magicStr = String(magic, Charsets.US_ASCII)
        if (magicStr != "ATSMAP03" && magicStr != "ATSMAP02") {
            throw IllegalArgumentException("Formato de mapa invalido: $magicStr")
        }

        readInt() // version
        val nodeCount = readInt()
        val edgeCount = readInt()
        val cityCount = readInt()
        val companyCount = readInt()
        val poiCount = if (magicStr == "ATSMAP03") readInt() else 0

        val minX = readFloat()
        val maxX = readFloat()
        val minZ = readFloat()
        val maxZ = readFloat()

        // 2. Nodos
        val nodes = ArrayList<MapNode>(nodeCount)
        for (i in 0 until nodeCount) {
            val uid = readLong()
            val x = readFloat()
            val y = readFloat()
            val z = readFloat()
            nodes.add(MapNode(i, uid, x, y, z))
        }

        // 3. Aristas
        val edges = ArrayList<MapEdge>(edgeCount)
        for (i in 0 until edgeCount) {
            val fromNode = readInt()
            val toNode = readInt()
            val length = readFloat()
            val speedLimit = readFloat()
            val roadType = readByte()
            val lanes = readByte()
            val flags = readByte()

            val ptCount = readShortUnsigned()
            val polyline = ArrayList<Point3D>(ptCount)
            for (p in 0 until ptCount) {
                polyline.add(Point3D(readFloat(), readFloat(), readFloat()))
            }

            edges.add(MapEdge(i, fromNode, toNode, length, speedLimit, roadType, lanes, flags, polyline))
        }

        // 4. Ciudades
        val cities = ArrayList<MapCity>(cityCount)
        for (i in 0 until cityCount) {
            val cityToken = readLong()
            val name = readString()
            val x = readFloat()
            val z = readFloat()
            val nearest = readInt()
            cities.add(MapCity(cityToken, name, x, z, nearest))
        }

        // 5. Empresas
        val companies = ArrayList<MapCompany>(companyCount)
        for (i in 0 until companyCount) {
            val companyToken = readLong()
            val cityToken = readLong()
            val name = readString()
            val cityName = readString()
            val x = readFloat()
            val z = readFloat()
            val nearest = readInt()
            companies.add(MapCompany(companyToken, cityToken, name, cityName, x, z, nearest))
        }

        // 6. POIs (Gasolineras, Descanso, Talleres, Concesionarios, Empresas)
        val pois = ArrayList<MapPoi>(poiCount)
        for (i in 0 until poiCount) {
            val poiId = readInt()
            val poiType = readByte()
            val name = readString()
            val x = readFloat()
            val y = readFloat()
            val z = readFloat()
            pois.add(MapPoi(poiId, poiType, name, x, y, z))
        }

        return MapGraph(nodes, edges, cities, companies, pois, minX, maxX, minZ, maxZ)
    }
}
