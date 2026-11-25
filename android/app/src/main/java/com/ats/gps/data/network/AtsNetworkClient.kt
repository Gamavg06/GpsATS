package com.ats.gps.data.network

import android.content.Context
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
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.BufferUnderflowException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class UnauthorizedException(message: String) : IOException(message)

class RateLimitedException(message: String) : IOException(message)

class MapBuildingException(message: String) : IOException(message)

data class MapInfoResult(
    val exists: Boolean,
    val fileSizeBytes: Long
)

data class PairResult(
    val token: String,
    val expiresInHours: Int
)

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

    suspend fun pairWithServer(host: String, port: Int, pin: String): Result<PairResult> = withContext(Dispatchers.IO) {
        val cleanPin = pin.trim()
        if (cleanPin.isEmpty()) {
            return@withContext Result.failure(
                IllegalArgumentException("Ingresa el PIN de 6 digitos del servidor PC.")
            )
        }
        if (!isAllowedPrivateAddress(host)) {
            return@withContext Result.failure(
                SecurityException("Conexion rechazada: '$host' no es una IP privada (RFC 1918, loopback o link-local). Solo se admiten redes LAN privadas de confianza.")
            )
        }
        try {
            val formattedHost = formatHostForUrl(host)
            val url = "http://$formattedHost:$port/api/pair"
            val json = JSONObject().put("pin", cleanPin).toString()
            val requestBody = json.toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string() ?: ""

                when {
                    response.isSuccessful -> {
                        val jsonResp = JSONObject(responseBody)
                        val token = jsonResp.getString("token")
                        val expiresInHours = jsonResp.optInt("expiresInHours", 48).coerceAtLeast(1)
                        Result.success(PairResult(token = token, expiresInHours = expiresInHours))
                    }
                    response.code == 401 -> {
                        Result.failure(UnauthorizedException("PIN incorrecto"))
                    }
                    response.code == 429 -> {
                        Result.failure(RateLimitedException("Demasiados intentos. Espera 2 minutos."))
                    }
                    else -> {
                        val err = if (responseBody.isNotEmpty()) {
                            runCatching { JSONObject(responseBody).optString("error", "") }.getOrDefault("")
                                .ifEmpty { "HTTP ${response.code}" }
                        } else {
                            "HTTP ${response.code}"
                        }
                        Result.failure(IOException(err))
                    }
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun sendControlKey(host: String, port: Int, token: String, action: String): Result<Boolean> = withContext(Dispatchers.IO) {
        if (!isAllowedPrivateAddress(host)) {
            return@withContext Result.failure(SecurityException("Direccion de servidor no permitida"))
        }
        if (token.isBlank()) {
            return@withContext Result.failure(UnauthorizedException("Sesion expirada o no autorizada"))
        }
        try {
            val formattedHost = formatHostForUrl(host)
            val url = "http://$formattedHost:$port/api/control/key"
            val json = JSONObject().put("action", action).toString()
            val requestBody = json.toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> Result.success(true)
                    response.code == 401 -> Result.failure(UnauthorizedException("Sesion expirada o no autorizada"))
                    else -> Result.failure(IOException("HTTP ${response.code}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchMapInfo(host: String, port: Int, token: String): Result<MapInfoResult> = withContext(Dispatchers.IO) {
        if (!isAllowedPrivateAddress(host)) {
            return@withContext Result.failure(
                SecurityException("Conexion rechazada: '$host' no es una IP privada permitida.")
            )
        }
        if (token.isBlank()) {
            return@withContext Result.failure(UnauthorizedException("Sesion expirada o no autorizada"))
        }
        try {
            val formattedHost = formatHostForUrl(host)
            val url = "http://$formattedHost:$port/api/map/info"
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> {
                        val bodyStr = response.body?.string() ?: "{}"
                        val json = JSONObject(bodyStr)
                        Result.success(
                            MapInfoResult(
                                exists = json.optBoolean("exists", false),
                                fileSizeBytes = json.optLong("fileSizeBytes", 0L)
                            )
                        )
                    }
                    response.code == 401 -> Result.failure(UnauthorizedException("Sesion expirada o no autorizada"))
                    response.code == 503 -> Result.failure(MapBuildingException("El servidor esta generando el mapa local"))
                    else -> Result.failure(IOException("HTTP ${response.code}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun downloadMap(context: Context, host: String, port: Int, token: String): Result<MapGraph> = withContext(Dispatchers.IO) {
        if (!isAllowedPrivateAddress(host)) {
            return@withContext Result.failure(
                SecurityException("Conexion rechazada: '$host' no es una IP privada permitida.")
            )
        }
        if (token.isBlank()) {
            return@withContext Result.failure(UnauthorizedException("Sesion expirada o no autorizada"))
        }

        val mapFile = File(context.filesDir, "map_ats.bin")
        val tmpFile = File(context.filesDir, "map_ats.bin.tmp")

        // Consultar /api/map/info para aprovechar cache local atomica si el tamano coincide
        val infoRes = fetchMapInfo(host, port, token)
        if (infoRes.isFailure) {
            val err = infoRes.exceptionOrNull()
            if (err is UnauthorizedException || err is MapBuildingException) {
                return@withContext Result.failure(err)
            }
        } else {
            val info = infoRes.getOrNull()
            if (info != null && info.exists && info.fileSizeBytes > 0L &&
                mapFile.exists() && mapFile.length() == info.fileSizeBytes
            ) {
                val cachedGraph = runCatching { BinaryMapReader.read(mapFile) }
                if (cachedGraph.isSuccess) {
                    return@withContext cachedGraph
                } else {
                    mapFile.delete()
                }
            }
        }

        try {
            val formattedHost = formatHostForUrl(host)
            val url = "http://$formattedHost:$port/api/map/download"
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .build()
            client.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> {
                        val stream = response.body?.byteStream() ?: throw IOException("Cuerpo de respuesta vacio.")
                        tmpFile.delete()
                        try {
                            stream.use { input ->
                                FileOutputStream(tmpFile).use { output ->
                                    val buffer = ByteArray(65536) // 64 KB
                                    while (true) {
                                        val read = input.read(buffer)
                                        if (read == -1) break
                                        output.write(buffer, 0, read)
                                    }
                                    output.flush()
                                }
                            }
                            if (mapFile.exists()) mapFile.delete()
                            if (!tmpFile.renameTo(mapFile)) {
                                tmpFile.copyTo(mapFile, overwrite = true)
                                tmpFile.delete()
                            }
                            val graph = try {
                                BinaryMapReader.read(mapFile)
                            } catch (e: Exception) {
                                mapFile.delete()
                                val detail = when (e) {
                                    is BufferUnderflowException, is EOFException ->
                                        "Mapa incompleto o corrupto en descarga. Reintenta."
                                    else -> e.message ?: "Error leyendo mapa binario"
                                }
                                throw IOException(detail, e)
                            }
                            Result.success(graph)
                        } catch (e: Exception) {
                            tmpFile.delete()
                            throw e
                        }
                    }
                    response.code == 401 -> {
                        Result.failure(UnauthorizedException("Sesion expirada o no autorizada"))
                    }
                    response.code == 503 -> {
                        Result.failure(MapBuildingException("El servidor esta generando el mapa local"))
                    }
                    response.code == 404 -> {
                        Result.failure(IOException("El mapa aun no ha sido generado en la PC. Ejecuta --build-map o POST /api/map/build."))
                    }
                    else -> {
                        Result.failure(IOException("No se pudo descargar el mapa. Codigo: ${response.code}"))
                    }
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun connectWebSocket(
        host: String,
        port: Int,
        token: String,
        onTelemetry: (TelemetryData) -> Unit,
        onStatusChanged: (Boolean) -> Unit,
        onAuthExpired: () -> Unit = {}
    ) {
        synchronized(wsLock) {
            isUserDisconnected.set(false)
        }
        retryAttempt.set(0)
        openWebSocketInternal(host, port, token, onTelemetry, onStatusChanged, onAuthExpired, fromReconnect = false)
    }

    private fun openWebSocketInternal(
        host: String,
        port: Int,
        token: String,
        onTelemetry: (TelemetryData) -> Unit,
        onStatusChanged: (Boolean) -> Unit,
        onAuthExpired: () -> Unit,
        fromReconnect: Boolean = false
    ) {
        if (!isAllowedPrivateAddress(host)) {
            synchronized(wsLock) {
                if (fromReconnect && isUserDisconnected.get()) return
                reconnectJob?.cancel()
                reconnectJob = null
                disconnectWebSocketInternal()
            }
            postToMain { onStatusChanged(false) }
            return
        }

        if (token.isBlank()) {
            synchronized(wsLock) {
                if (fromReconnect && isUserDisconnected.get()) return
                reconnectJob?.cancel()
                reconnectJob = null
                disconnectWebSocketInternal()
            }
            postToMain {
                onStatusChanged(false)
                onAuthExpired()
            }
            return
        }

        val formattedHost = formatHostForUrl(host)
        val url = "ws://$formattedHost:$port/ws/telemetry"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .build()

        synchronized(wsLock) {
            if (fromReconnect && isUserDisconnected.get()) return
            reconnectJob?.cancel()
            reconnectJob = null
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
                        // Ignorar frames con formato erroneo
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    if (webSocket !== activeWebSocket) return
                    if (code == 1008) {
                        handleWebSocketAuthExpired(webSocket, onStatusChanged, onAuthExpired)
                        webSocket.close(1008, "No autorizado")
                        return
                    }
                    postToMain { onStatusChanged(false) }
                    scheduleReconnect(host, port, token, onTelemetry, onStatusChanged, onAuthExpired)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (webSocket !== activeWebSocket) return
                    if (code == 1008) {
                        handleWebSocketAuthExpired(webSocket, onStatusChanged, onAuthExpired)
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (webSocket !== activeWebSocket) return
                    if (response?.code == 401) {
                        handleWebSocketAuthExpired(webSocket, onStatusChanged, onAuthExpired)
                        return
                    }
                    postToMain { onStatusChanged(false) }
                    scheduleReconnect(host, port, token, onTelemetry, onStatusChanged, onAuthExpired)
                }
            })
        }
    }

    private fun handleWebSocketAuthExpired(
        webSocket: WebSocket,
        onStatusChanged: (Boolean) -> Unit,
        onAuthExpired: () -> Unit
    ) {
        synchronized(wsLock) {
            if (webSocket !== activeWebSocket) return
            isUserDisconnected.set(true)
            activeWebSocket = null
            reconnectJob?.cancel()
            reconnectJob = null
        }
        postToMain {
            onStatusChanged(false)
            onAuthExpired()
        }
    }

    private fun scheduleReconnect(
        host: String,
        port: Int,
        token: String,
        onTelemetry: (TelemetryData) -> Unit,
        onStatusChanged: (Boolean) -> Unit,
        onAuthExpired: () -> Unit
    ) {
        if (isUserDisconnected.get()) return

        synchronized(wsLock) {
            if (isUserDisconnected.get()) return
            reconnectJob?.cancel()
            reconnectJob = clientScope.launch {
                val attempt = retryAttempt.incrementAndGet()
                // Backoff exponencial: 1s, 2s, 4s, 8s, hasta max 15s
                val delayMs = (1000L * (1L shl kotlin.math.min(attempt, 4))).coerceAtMost(15000L)
                delay(delayMs)

                if (!isUserDisconnected.get()) {
                    openWebSocketInternal(
                        host,
                        port,
                        token,
                        onTelemetry,
                        onStatusChanged,
                        onAuthExpired,
                        fromReconnect = true
                    )
                }
            }
        }
    }

    fun disconnectWebSocket(preserveSession: Boolean = false) {
        synchronized(wsLock) {
            isUserDisconnected.set(true)
            reconnectJob?.cancel()
            reconnectJob = null
            disconnectWebSocketInternal(
                reason = if (preserveSession) "App en segundo plano" else "Cierre voluntario de la app"
            )
        }
    }

    fun shutdown() {
        disconnectWebSocket()
        clientScope.cancel()
        mainHandler.removeCallbacksAndMessages(null)
    }

    private fun disconnectWebSocketInternal(reason: String = "Cierre voluntario de la app") {
        val old = activeWebSocket
        activeWebSocket = null
        old?.close(1000, reason)
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
