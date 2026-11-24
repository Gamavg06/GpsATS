package com.ats.gps.data.network

import android.os.Handler
import android.os.Looper
import com.ats.gps.data.map.BinaryMapReader
import com.ats.gps.data.model.MapGraph
import com.ats.gps.data.model.TelemetryData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class AtsNetworkClient {

    companion object {
        fun isAllowedPrivateAddress(host: String): Boolean {
            val trimmed = host.trim().removePrefix("[").removeSuffix("]")
            if (trimmed.isEmpty()) return false
            if (trimmed.equals("localhost", ignoreCase = true)) return true

            return try {
                val addr = java.net.InetAddress.getByName(trimmed)
                addr.isLoopbackAddress || addr.isSiteLocalAddress || addr.isLinkLocalAddress
            } catch (_: Exception) {
                false
            }
        }

        private fun formatHostForUrl(host: String): String {
            val trimmed = host.trim().removePrefix("[").removeSuffix("]")
            return if (trimmed.contains(':')) "[$trimmed]" else trimmed
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val wsLock = Any()

    @Volatile
    private var activeWebSocket: WebSocket? = null

    @Volatile
    private var reconnectJob: Job? = null

    private val clientScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val isUserDisconnected = AtomicBoolean(false)
    private val retryAttempt = AtomicInteger(0)

    private fun postToMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    suspend fun checkServerStatus(host: String, port: Int): Boolean = withContext(Dispatchers.IO) {
        if (!isAllowedPrivateAddress(host)) return@withContext false
        try {
            val formattedHost = formatHostForUrl(host)
            val url = "http://$formattedHost:$port/api/status"
            val request = Request.Builder().url(url).get().build()
            val probeClient = client.newBuilder()
                .connectTimeout(java.time.Duration.ofMillis(700))
                .readTimeout(java.time.Duration.ofMillis(700))
                .build()
            probeClient.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (_: Exception) {
            false
        }
    }

    suspend fun pairWithServer(host: String, port: Int, pin: String): Result<String> = withContext(Dispatchers.IO) {
        if (!isAllowedPrivateAddress(host)) {
            return@withContext Result.failure(
                SecurityException("Conexión rechazada: '$host' no es una IP privada (RFC 1918, loopback o link-local). Solo se admiten redes LAN privadas de confianza.")
            )
        }
        try {
            val formattedHost = formatHostForUrl(host)
            val url = "http://$formattedHost:$port/api/pair"
            val json = JSONObject().put("pin", pin).toString()
            val requestBody = json.toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string() ?: ""

                if (response.isSuccessful) {
                    val jsonResp = JSONObject(responseBody)
                    val token = jsonResp.getString("token")
                    Result.success(token)
                } else {
                    val err = if (responseBody.isNotEmpty()) JSONObject(responseBody).optString("error", "Error de autenticación") else "HTTP ${response.code}"
                    Result.failure(IOException(err))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun sendControlKey(host: String, port: Int, action: String): Result<Boolean> = withContext(Dispatchers.IO) {
        if (!isAllowedPrivateAddress(host)) {
            return@withContext Result.failure(SecurityException("Dirección de servidor no permitida"))
        }
        try {
            val formattedHost = formatHostForUrl(host)
            val url = "http://$formattedHost:$port/api/control/key"
            val json = JSONObject().put("action", action).toString()
            val requestBody = json.toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Result.success(true)
                } else {
                    Result.failure(IOException("HTTP ${response.code}"))
                }
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
            val formattedHost = formatHostForUrl(host)
            val url = "http://$formattedHost:$port/api/map/download"
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val stream = response.body?.byteStream() ?: throw IOException("Cuerpo de respuesta vacío.")
                    val graph = BinaryMapReader.read(stream)
                    Result.success(graph)
                } else {
                    Result.failure(IOException("No se pudo descargar el mapa. Código: ${response.code}"))
                }
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
        isUserDisconnected.set(false)
        reconnectJob?.cancel()

        if (!isAllowedPrivateAddress(host)) {
            disconnectWebSocketInternal()
            postToMain { onStatusChanged(false) }
            return
        }

        val formattedHost = formatHostForUrl(host)
        val url = "ws://$formattedHost:$port/ws/telemetry?token=$token"
        val request = Request.Builder().url(url).build()

        synchronized(wsLock) {
            disconnectWebSocketInternal()
            activeWebSocket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (webSocket !== activeWebSocket) return
                    retryAttempt.set(0)
                    postToMain { onStatusChanged(true) }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (webSocket !== activeWebSocket) return
                    try {
                        val json = JSONObject(text)
                        val telemetry = parseTelemetry(json)
                        postToMain { onTelemetry(telemetry) }
                    } catch (_: Exception) {
                        // Ignorar frames con formato erróneo
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    if (webSocket !== activeWebSocket) return
                    postToMain { onStatusChanged(false) }
                    scheduleReconnect(host, port, token, onTelemetry, onStatusChanged)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (webSocket !== activeWebSocket) return
                    postToMain { onStatusChanged(false) }
                    scheduleReconnect(host, port, token, onTelemetry, onStatusChanged)
                }
            })
        }
    }

    private fun scheduleReconnect(
        host: String,
        port: Int,
        token: String,
        onTelemetry: (TelemetryData) -> Unit,
        onStatusChanged: (Boolean) -> Unit
    ) {
        if (isUserDisconnected.get()) return

        reconnectJob?.cancel()
        reconnectJob = clientScope.launch {
            val attempt = retryAttempt.incrementAndGet()
            // Backoff exponencial: 1s, 2s, 4s, 8s, hasta máx 15s
            val delayMs = (1000L * (1L shl kotlin.math.min(attempt, 4))).coerceAtMost(15000L)
            delay(delayMs)

            if (!isUserDisconnected.get()) {
                connectTelemetryWebSocket(host, port, token, onTelemetry, onStatusChanged)
            }
        }
    }

    fun disconnectWebSocket() {
        isUserDisconnected.set(true)
        reconnectJob?.cancel()
        synchronized(wsLock) {
            disconnectWebSocketInternal()
        }
    }

    fun shutdown() {
        disconnectWebSocket()
        clientScope.cancel()
        mainHandler.removeCallbacksAndMessages(null)
    }

    private fun disconnectWebSocketInternal() {
        val old = activeWebSocket
        activeWebSocket = null
        old?.close(1000, "Cierre voluntario de la app")
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
            fuelRangeKm = json.optDouble("fuelRangeKm", 0.0).toFloat(),
            restStopMinutes = json.optInt("restStopMinutes", 0),
            wearEngine = json.optDouble("wearEngine", 0.0).toFloat(),
            wearTransmission = json.optDouble("wearTransmission", 0.0).toFloat(),
            wearCabin = json.optDouble("wearCabin", 0.0).toFloat(),
            wearChassis = json.optDouble("wearChassis", 0.0).toFloat(),
            wearWheels = json.optDouble("wearWheels", 0.0).toFloat(),
            trailerAttached = json.optBoolean("trailerAttached", false),
            trailerWearChassis = json.optDouble("trailerWearChassis", 0.0).toFloat(),
            trailerWearWheels = json.optDouble("trailerWearWheels", 0.0).toFloat(),
            trailerWearBody = json.optDouble("trailerWearBody", 0.0).toFloat(),
            trailerCargoDamage = json.optDouble("trailerCargoDamage", 0.0).toFloat(),
            cargoDamage = json.optDouble("cargoDamage", 0.0).toFloat(),
            blinkerLeft = json.optBoolean("blinkerLeft", false),
            blinkerRight = json.optBoolean("blinkerRight", false),
            lightsBeamLow = json.optBoolean("lightsBeamLow", false),
            lightsBeamHigh = json.optBoolean("lightsBeamHigh", false),
            lightsParking = json.optBoolean("lightsParking", false),
            lightsBeacon = json.optBoolean("lightsBeacon", false),
            lightsHazard = json.optBoolean("lightsHazard", false),
            wipers = json.optBoolean("wipers", false),
            differentialLock = json.optBoolean("differentialLock", false),
            liftAxle = json.optBoolean("liftAxle", false),
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
            sourceCityId = json.optString("sourceCityId", ""),
            sourceCompany = json.optString("sourceCompany", ""),
            sourceCompanyId = json.optString("sourceCompanyId", ""),
            destinationCity = json.optString("destinationCity", ""),
            destinationCityId = json.optString("destinationCityId", ""),
            destinationCompany = json.optString("destinationCompany", ""),
            destinationCompanyId = json.optString("destinationCompanyId", ""),
            jobIncome = json.optLong("jobIncome", 0L),
            timeAbsDelivery = json.optLong("timeAbsDelivery", 0L),
            isCargoLoaded = json.optBoolean("isCargoLoaded", false),
            specialJob = json.optBoolean("specialJob", false),
            onJob = json.optBoolean("onJob", false),
            jobMarket = json.optString("jobMarket", ""),
            targetNodeIndex = if (json.has("targetNodeIndex") && !json.isNull("targetNodeIndex")) json.getLong("targetNodeIndex") else null,
            targetPosX = if (json.has("targetPosX") && !json.isNull("targetPosX")) json.getDouble("targetPosX").toFloat() else null,
            targetPosZ = if (json.has("targetPosZ") && !json.isNull("targetPosZ")) json.getDouble("targetPosZ").toFloat() else null
        )
    }
}
