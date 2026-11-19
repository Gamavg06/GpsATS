package com.ats.gps.data.triplog

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class TripRecord(
    val id: String = java.util.UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val originCity: String,
    val destinationCity: String,
    val cargoName: String,
    val cargoMassKg: Float,
    val incomeEarned: Long,
    val distanceKm: Float,
    val avgSpeedKmh: Float,
    val damagePercent: Float
)

class TripLogManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("ats_gps_trip_log", Context.MODE_PRIVATE)

    fun getTripHistory(): List<TripRecord> {
        val jsonStr = prefs.getString("trips_json", "[]") ?: "[]"
        val list = mutableListOf<TripRecord>()
        try {
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    TripRecord(
                        id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                        timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                        originCity = obj.optString("originCity", "Origen"),
                        destinationCity = obj.optString("destinationCity", "Destino"),
                        cargoName = obj.optString("cargoName", "Carga general"),
                        cargoMassKg = obj.optDouble("cargoMassKg", 0.0).toFloat(),
                        incomeEarned = obj.optLong("incomeEarned", 0L),
                        distanceKm = obj.optDouble("distanceKm", 0.0).toFloat(),
                        avgSpeedKmh = obj.optDouble("avgSpeedKmh", 0.0).toFloat(),
                        damagePercent = obj.optDouble("damagePercent", 0.0).toFloat()
                    )
                )
            }
        } catch (_: Exception) { }
        return list.reversed() // Más recientes primero
    }

    fun addTrip(record: TripRecord) {
        val current = getTripHistory().toMutableList()
        current.add(0, record)
        if (current.size > 50) {
            current.removeAt(current.size - 1)
        }
        val arr = JSONArray()
        for (item in current) {
            val obj = JSONObject().apply {
                put("id", item.id)
                put("timestamp", item.timestamp)
                put("originCity", item.originCity)
                put("destinationCity", item.destinationCity)
                put("cargoName", item.cargoName)
                put("cargoMassKg", item.cargoMassKg)
                put("incomeEarned", item.incomeEarned)
                put("distanceKm", item.distanceKm)
                put("avgSpeedKmh", item.avgSpeedKmh)
                put("damagePercent", item.damagePercent)
            }
            arr.put(obj)
        }
        prefs.edit().putString("trips_json", arr.toString()).apply()
    }

    fun clearHistory() {
        prefs.edit().remove("trips_json").apply()
    }

    fun getTotalIncome(): Long {
        return getTripHistory().sumOf { it.incomeEarned }
    }

    fun getTotalDistanceKm(): Float {
        return getTripHistory().map { it.distanceKm }.sum()
    }
}
