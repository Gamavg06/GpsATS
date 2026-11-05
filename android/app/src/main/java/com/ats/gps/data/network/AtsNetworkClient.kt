package com.ats.gps.data.network

import com.ats.gps.data.map.BinaryMapReader
import com.ats.gps.data.model.MapGraph
import com.ats.gps.data.model.TelemetryData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class AtsNetworkClient {

    companion object {
        fun isAllowedPrivateAddress(host: String): Boolean {
            val trimmed = host.trim()
            if (trimmed.equals("localhost", ignoreCase = true)) return true

            return try {
                val addr = java.net.InetAddress.getByName(trimmed)
                addr.isLoopbackAddress || addr.isSiteLocalAddress || addr.isLinkLocalAddress
            } catch (e: Exception) {
                false
            }
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private var activeWebSocket: WebSocket? = null

    suspend fun pairWithServer(host: String, port: Int, pin: String): Result<String> = withContext(Dispatchers.IO) {
        if (!isAllowedPrivateAddress(host)) {
            return@withContext Result.failure(
                SecurityException("Conexión rechazada: '$host' no es una IP privada (RFC 1918, loopback o link-local). Solo se admiten redes LAN privadas de confianza.")
            )
        }
        try {
            val url = "http://$host:$port/api/pair"
            val json = JSONObject().put("pin", pin).toString()
            val requestBody = json.toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (response.isSuccessful) {
                val jsonResp = JSONObject(responseBody)
                val token = jsonResp.getString("token")
                Result.success(token)
            } else {
                val err = if (responseBody.isNotEmpty()) JSONObject(responseBody).optString("error", "Error de autenticación") else "HTTP ${response.code}"
                Result.failure(IOException(err))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun downloadMap(host: String, port: Int): Result<MapGraph> = withContext(Dispatchers.IO) {
        if (!isAllowedPrivateAddress(host)) {
            return@withContext Result.failure(
                SecurityException("Conexión rechazada: '$host' no es una IP privada permitida.")
            )
        }
        try {
            val url = "http://$host:$port/api/map/download"
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()

            if (response.isSuccessful) {
                val stream = response.body?.byteStream() ?: throw IOException("Cuerpo de respuesta vacío.")
                val graph = BinaryMapReader.read(stream)
                Result.success(graph)
            } else {
                Result.failure(IOException("No se pudo descargar el mapa. Código: ${response.code}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun connectTelemetryWebSocket(
        host: String,
        port: Int,
        token: String,
        onTelemetry: (TelemetryData) -> Unit,
        onStatusChanged: (Boolean) -> Unit
    ) {
        disconnectWebSocket()

        if (!isAllowedPrivateAddress(host)) {
            onStatusChanged(false)
            return
        }

        val url = "ws://$host:$port/ws/telemetry?token=$token"
        val request = Request.Builder().url(url).build()

        activeWebSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                onStatusChanged(true)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    val telemetry = parseTelemetry(json)
                    onTelemetry(telemetry)
                } catch (e: Exception) {
                    // Ignorar frames con formato erróneo
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                onStatusChanged(false)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onStatusChanged(false)
            }
        })
    }

    fun disconnectWebSocket() {
        activeWebSocket?.close(1000, "Cierre voluntario de la app")
        activeWebSocket = null
    }

    private fun parseTelemetry(json: JSONObject): TelemetryData {
        return TelemetryData(
            connected = json.optBoolean("connected", false),
            gamePaused = json.optBoolean("gamePaused", false),
            timestamp = json.optLong("timestamp", 0L),
            posX = json.optDouble("posX", 0.0),
            posY = json.optDouble("posY", 0.0),
            posZ = json.optDouble("posZ", 0.0),
            heading = json.optDouble("heading", 0.0),
            headingDegrees = json.optDouble("headingDegrees", 0.0),
            pitch = json.optDouble("pitch", 0.0),
            roll = json.optDouble("roll", 0.0),
            speedKmh = json.optDouble("speedKmh", 0.0).toFloat(),
            engineRpm = json.optDouble("engineRpm", 0.0).toFloat(),
            engineRpmMax = json.optDouble("engineRpmMax", 2500.0).toFloat(),
            gear = json.optInt("gear", 0),
            gearDashboard = json.optInt("gearDashboard", 0),
            fuel = json.optDouble("fuel", 0.0).toFloat(),
            fuelCapacity = json.optDouble("fuelCapacity", 400.0).toFloat(),
            fuelAvgConsumption = json.optDouble("fuelAvgConsumption", 0.0).toFloat(),
            wearEngine = json.optDouble("wearEngine", 0.0).toFloat(),
            wearTransmission = json.optDouble("wearTransmission", 0.0).toFloat(),
            wearCabin = json.optDouble("wearCabin", 0.0).toFloat(),
            wearChassis = json.optDouble("wearChassis", 0.0).toFloat(),
            wearWheels = json.optDouble("wearWheels", 0.0).toFloat(),
            blinkerLeft = json.optBoolean("blinkerLeft", false),
            blinkerRight = json.optBoolean("blinkerRight", false),
            lightsBeamLow = json.optBoolean("lightsBeamLow", false),
            lightsBeamHigh = json.optBoolean("lightsBeamHigh", false),
            parkingBrake = json.optBoolean("parkingBrake", false),
            cruiseControl = json.optBoolean("cruiseControl", false),
            cruiseControlSpeed = json.optDouble("cruiseControlSpeed", 0.0).toFloat(),
            navDistanceMeters = json.optDouble("navDistanceMeters", 0.0).toFloat(),
            navTimeSeconds = json.optDouble("navTimeSeconds", 0.0).toFloat(),
            speedLimitKmh = json.optDouble("speedLimitKmh", 0.0).toFloat(),
            cargoName = json.optString("cargoName", ""),
            cargoId = json.optString("cargoId", ""),
            cargoMassKg = json.optDouble("cargoMassKg", 0.0).toFloat(),
            sourceCity = json.optString("sourceCity", ""),
            sourceCompany = json.optString("sourceCompany", ""),
            destinationCity = json.optString("destinationCity", ""),
            destinationCompany = json.optString("destinationCompany", ""),
            jobIncome = json.optLong("jobIncome", 0L),
            targetNodeIndex = if (json.has("targetNodeIndex") && !json.isNull("targetNodeIndex")) json.getLong("targetNodeIndex") else null,
            targetPosX = if (json.has("targetPosX") && !json.isNull("targetPosX")) json.getDouble("targetPosX").toFloat() else null,
            targetPosZ = if (json.has("targetPosZ") && !json.isNull("targetPosZ")) json.getDouble("targetPosZ").toFloat() else null
        )
    }
}
