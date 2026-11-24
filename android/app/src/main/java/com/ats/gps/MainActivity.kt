package com.ats.gps

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ats.gps.data.map.AStarRouter
import com.ats.gps.data.map.RouteResult
import com.ats.gps.data.model.MapGraph
import com.ats.gps.data.model.TelemetryData
import com.ats.gps.data.network.AtsNetworkClient
import com.ats.gps.data.network.AuthTokenStore
import com.ats.gps.data.network.UnauthorizedException
import com.ats.gps.ui.about.AboutScreen
import com.ats.gps.ui.dashboard.DashboardScreen
import com.ats.gps.ui.diagnostic.TelemetryDiagnosticScreen
import com.ats.gps.ui.junction.JunctionView
import com.ats.gps.ui.map.GpsMapCanvas
import com.ats.gps.ui.map.MapViewMode
import com.ats.gps.ui.map.MapThemeStyle
import com.ats.gps.ui.navigation.ManeuverType
import com.ats.gps.ui.navigation.NavigationInstructionCache
import com.ats.gps.ui.navigation.TurnByTurnCard
import com.ats.gps.ui.navigation.calculateNavigationInstruction
import com.ats.gps.ui.tts.NavigationVoiceAssistant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import kotlin.math.sqrt

import androidx.compose.ui.platform.LocalContext
import com.ats.gps.data.triplog.TripLogManager
import com.ats.gps.data.triplog.TripRecord
import com.ats.gps.ui.triplog.TripLogScreen

enum class AppTab {
    MAPA,
    TELEMETRIA,
    BITACORA,
    ACERCA_DE
}

private data class RouteTrackingInput(
    val posX: Double,
    val posZ: Double,
    val headingDegrees: Double,
    val speedKmh: Float,
    val gear: Int,
    val navDistanceMeters: Float,
    val destinationCity: String,
    val destinationCityId: String,
    val destinationCompany: String,
    val destinationCompanyId: String,
    val telemetryTargetNode: Int?,
    val manualTargetNode: Int?,
    val manualDestName: String?
)

class MainActivity : ComponentActivity() {

    private val networkClient = AtsNetworkClient()
    private lateinit var voiceAssistant: NavigationVoiceAssistant

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Mantener la pantalla encendida mientras la app de navegación está activa
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        voiceAssistant = NavigationVoiceAssistant(this)

        setContent {
            AtsNavMainApp(networkClient, voiceAssistant)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        networkClient.shutdown()
        voiceAssistant.shutdown()
    }
}

@Composable
fun AtsNavMainApp(
    networkClient: AtsNetworkClient,
    voiceAssistant: NavigationVoiceAssistant
) {
    var serverIp by remember { mutableStateOf("127.0.0.1") }
    var serverPort by remember { mutableStateOf("35500") }
    var serverPin by remember { mutableStateOf("") }
    var isConnected by remember { mutableStateOf(false) }
    var isPairing by remember { mutableStateOf(false) }
    var pairError by remember { mutableStateOf<String?>(null) }

    var telemetry by remember { mutableStateOf(TelemetryData()) }
    var mapGraph by remember { mutableStateOf<MapGraph?>(null) }
    var activeRoute by remember { mutableStateOf<RouteResult?>(null) }

    var selectedTab by remember { mutableStateOf(AppTab.MAPA) }
    var viewMode by remember { mutableStateOf(MapViewMode.MODE_3D_PERSPECTIVE) }
    var mapTheme by remember { mutableStateOf(MapThemeStyle.DARK_PREMIUM) }
    var showDiagnostics by remember { mutableStateOf(false) }

    // Destinos manuales / desvíos independientes de los frames WebSocket (30 Hz)
    var manualTargetNodeIndex by remember { mutableStateOf<Int?>(null) }
    var manualDestinationName by remember { mutableStateOf<String?>(null) }
    val effectiveDestinationName = manualDestinationName ?: telemetry.destinationCity

    // Control táctil de Zoom y Desplazamiento (Pan)
    var zoomScale by remember { mutableStateOf(1.0f) }
    var panOffset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    var isFollowingTruck by remember { mutableStateOf(true) }

    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val tripLogManager = remember { TripLogManager(context) }
    val authTokenStore = remember(context) { AuthTokenStore(context) }
    var loadedMapHost by remember { mutableStateOf<String?>(null) }
    var sessionRevision by remember { mutableIntStateOf(0) }

    // Alertas de Voz y Asistente Turn-by-Turn
    var lastSpeedWarningTime by remember { mutableLongStateOf(0L) }
    var lastFuelWarningTime by remember { mutableLongStateOf(0L) }
    var lastRestWarningTime by remember { mutableLongStateOf(0L) }
    var lastArrivalAnnouncedCity by remember { mutableStateOf("") }

    // Rastreo de trabajo para Bitácora automática (solo con conexión activa y trabajo real)
    var jobOriginSnapshot by remember { mutableStateOf("") }
    var jobDestSnapshot by remember { mutableStateOf("") }
    var jobCargoSnapshot by remember { mutableStateOf("") }
    var jobMassSnapshot by remember { mutableFloatStateOf(0f) }
    var jobIncomeSnapshot by remember { mutableLongStateOf(0L) }
    var jobInitialDistSnapshot by remember { mutableFloatStateOf(0f) }
    var jobDrivenMeters by remember { mutableFloatStateOf(0f) }
    var jobLastNavDistMeters by remember { mutableFloatStateOf(Float.MAX_VALUE) }
    var jobStartTimeMs by remember { mutableLongStateOf(0L) }
    var lastJobPosX by remember { mutableDoubleStateOf(Double.NaN) }
    var lastJobPosZ by remember { mutableDoubleStateOf(Double.NaN) }
    var wasOnJob by remember { mutableStateOf(false) }

    val navInstructionCache = remember { NavigationInstructionCache() }

    // 1. Asistente de Giro a Giro por Voz continuo
    val currentInstruction = remember(telemetry.posX, telemetry.posZ, activeRoute, effectiveDestinationName) {
        if (activeRoute != null && (telemetry.posX != 0.0 || telemetry.posZ != 0.0)) {
            calculateNavigationInstruction(
                truckX = telemetry.posX.toFloat(),
                truckZ = telemetry.posZ.toFloat(),
                route = activeRoute!!,
                destinationCity = effectiveDestinationName,
                cache = navInstructionCache
            )
        } else null
    }

    LaunchedEffect(currentInstruction?.distanceMeters, currentInstruction?.title) {
        if (currentInstruction != null && isFollowingTruck && !telemetry.gamePaused) {
            voiceAssistant.updateNavigation(currentInstruction.distanceMeters, currentInstruction.title)
        }
    }

    // 2. Anuncio de Llegada a Destino
    LaunchedEffect(telemetry.navDistanceMeters, activeRoute, telemetry.destinationCity) {
        if (activeRoute != null && telemetry.destinationCity.isNotEmpty() &&
            telemetry.navDistanceMeters in 1f..45f &&
            lastArrivalAnnouncedCity != telemetry.destinationCity
        ) {
            lastArrivalAnnouncedCity = telemetry.destinationCity
            voiceAssistant.announceArrival(telemetry.destinationCity)
        }
    }

    // 3. Alerta de Exceso de Velocidad
    val isSpeeding = telemetry.speedLimitKmh > 15f && telemetry.speedKmh > (telemetry.speedLimitKmh + 5f)
    LaunchedEffect(isSpeeding) {
        if (isSpeeding && !telemetry.gamePaused) {
            val now = System.currentTimeMillis()
            if (now - lastSpeedWarningTime > 25000L) {
                lastSpeedWarningTime = now
                voiceAssistant.speak("Atención, reduce tu velocidad, límite superado")
            }
        }
    }

    // 4. Alerta Temprana de Combustible Crítico
    val isFuelCritical = telemetry.fuelCapacity > 0f && (telemetry.fuel / telemetry.fuelCapacity) < 0.12f
    LaunchedEffect(isFuelCritical) {
        if (isFuelCritical && !telemetry.gamePaused) {
            val now = System.currentTimeMillis()
            if (now - lastFuelWarningTime > 150000L) {
                lastFuelWarningTime = now
                voiceAssistant.speak("Combustible bajo, busca una gasolinera próxima")
            }
        }
    }

    // 5. Alerta Temprana de Descanso y Fatiga
    val isRestCritical = telemetry.restStopMinutes in 1..35
    LaunchedEffect(isRestCritical) {
        if (isRestCritical && !telemetry.gamePaused) {
            val now = System.currentTimeMillis()
            if (now - lastRestWarningTime > 150000L) {
                lastRestWarningTime = now
                voiceAssistant.speak("Conductor fatigado, planifica una parada en la siguiente zona de descanso")
            }
        }
    }

    // 6. Registro Automático en Bitácora de Conducción al Entregar Carga real
    LaunchedEffect(
        isConnected,
        telemetry.connected,
        telemetry.onJob,
        telemetry.posX,
        telemetry.posZ,
        telemetry.destinationCity,
        telemetry.jobIncome,
        telemetry.navDistanceMeters
    ) {
        if (!isConnected || !telemetry.connected) {
            // Desconexión de red o simulador: no emitir registros falsos; reiniciar seguimiento temporal si cae red
            if (!isConnected) {
                wasOnJob = false
                lastJobPosX = Double.NaN
                lastJobPosZ = Double.NaN
            }
            return@LaunchedEffect
        }

        if (telemetry.onJob) {
            if (!wasOnJob) {
                // Nuevo trabajo iniciado en el simulador: limpiar desvío manual previo y reiniciar acumuladores
                wasOnJob = true
                manualTargetNodeIndex = null
                manualDestinationName = null
                jobDrivenMeters = 0f
                jobStartTimeMs = System.currentTimeMillis()
                jobInitialDistSnapshot = telemetry.navDistanceMeters
                jobLastNavDistMeters = if (telemetry.navDistanceMeters > 0f) telemetry.navDistanceMeters else Float.MAX_VALUE
                lastJobPosX = telemetry.posX
                lastJobPosZ = telemetry.posZ
            } else if (!lastJobPosX.isNaN() && !lastJobPosZ.isNaN()) {
                val dx = telemetry.posX - lastJobPosX
                val dz = telemetry.posZ - lastJobPosZ
                val stepMeters = sqrt(dx * dx + dz * dz).toFloat()
                if (stepMeters in 0.05f..250f) {
                    jobDrivenMeters += stepMeters
                }
                lastJobPosX = telemetry.posX
                lastJobPosZ = telemetry.posZ
            }

            if (telemetry.sourceCity.isNotEmpty()) jobOriginSnapshot = telemetry.sourceCity
            if (telemetry.destinationCity.isNotEmpty()) jobDestSnapshot = telemetry.destinationCity
            if (telemetry.cargoName.isNotEmpty()) jobCargoSnapshot = telemetry.cargoName
            if (telemetry.cargoMassKg > 0f) jobMassSnapshot = telemetry.cargoMassKg
            if (telemetry.jobIncome > 0L) jobIncomeSnapshot = telemetry.jobIncome
            if (telemetry.navDistanceMeters > jobInitialDistSnapshot) jobInitialDistSnapshot = telemetry.navDistanceMeters
            if (telemetry.navDistanceMeters > 0f) jobLastNavDistMeters = telemetry.navDistanceMeters
        } else if (wasOnJob) {
            wasOnJob = false
            val completedRealDelivery = jobDestSnapshot.isNotEmpty() &&
                (jobLastNavDistMeters <= 350f || jobDrivenMeters >= 500f)

            if (completedRealDelivery) {
                val drivenKm = when {
                    jobDrivenMeters >= 200f -> jobDrivenMeters / 1000f
                    jobInitialDistSnapshot > 0f -> jobInitialDistSnapshot / 1000f
                    else -> 0f
                }
                val elapsedHours = ((System.currentTimeMillis() - jobStartTimeMs).coerceAtLeast(1000L)) / 3_600_000f
                val avgSpeed = if (drivenKm > 0.2f && elapsedHours > 0.005f) {
                    (drivenKm / elapsedHours).coerceIn(10f, 160f)
                } else {
                    telemetry.speedKmh.coerceAtLeast(0f)
                }

                tripLogManager.addTrip(
                    TripRecord(
                        originCity = jobOriginSnapshot.ifEmpty { "Origen" },
                        destinationCity = jobDestSnapshot,
                        cargoName = jobCargoSnapshot.ifEmpty { "Carga general" },
                        cargoMassKg = jobMassSnapshot,
                        incomeEarned = jobIncomeSnapshot,
                        distanceKm = drivenKm,
                        avgSpeedKmh = avgSpeed,
                        damagePercent = telemetry.cargoDamage * 100f
                    )
                )
                voiceAssistant.speak("Felicidades, trabajo de entrega completado con éxito")
            }

            jobOriginSnapshot = ""
            jobDestSnapshot = ""
            jobCargoSnapshot = ""
            jobMassSnapshot = 0f
            jobIncomeSnapshot = 0L
            jobInitialDistSnapshot = 0f
            jobDrivenMeters = 0f
            jobLastNavDistMeters = Float.MAX_VALUE
            lastJobPosX = Double.NaN
            lastJobPosZ = Double.NaN
        }
    }

    // Control de cálculo de ruta: snapshotFlow + conflate() evita cancelaciones a 30 Hz
    var lastRoutedDestCity by remember { mutableStateOf("") }
    var lastRoutedDestComp by remember { mutableStateOf("") }
    var lastRouteCalcTime by remember { mutableLongStateOf(0L) }
    var isWrongWay by remember { mutableStateOf(false) }
    var lastTargetNodeIndex by remember { mutableIntStateOf(-1) }
    var lastWrongWayVoiceTime by remember { mutableLongStateOf(0L) }
    var offRouteCounter by remember { mutableIntStateOf(0) }
    var routeJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var lastTrackingCheckTime by remember { mutableLongStateOf(0L) }
    var lastTrackingX by remember { mutableFloatStateOf(0f) }
    var lastTrackingZ by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(mapGraph) {
        val graph = mapGraph ?: return@LaunchedEffect
        if (graph.nodes.isEmpty()) return@LaunchedEffect

        snapshotFlow {
            RouteTrackingInput(
                posX = telemetry.posX,
                posZ = telemetry.posZ,
                headingDegrees = telemetry.headingDegrees,
                speedKmh = telemetry.speedKmh,
                gear = telemetry.gear,
                navDistanceMeters = telemetry.navDistanceMeters,
                destinationCity = telemetry.destinationCity,
                destinationCityId = telemetry.destinationCityId,
                destinationCompany = telemetry.destinationCompany,
                destinationCompanyId = telemetry.destinationCompanyId,
                telemetryTargetNode = telemetry.targetNodeIndex?.toInt(),
                manualTargetNode = manualTargetNodeIndex,
                manualDestName = manualDestinationName
            )
        }
            .conflate()
            .collect { input ->
                val hasValidPos = input.posX != 0.0 || input.posZ != 0.0
                if (!hasValidPos) return@collect

                val curX = input.posX.toFloat()
                val curZ = input.posZ.toFloat()
                val curHeading = input.headingDegrees.toFloat()
                val now = System.currentTimeMillis()

                // Verificar llegada (< 80m) a destino manual / desvío
                var effectiveManualNode = input.manualTargetNode
                if (effectiveManualNode != null && effectiveManualNode in graph.nodes.indices) {
                    val mNode = graph.nodes[effectiveManualNode]
                    val dxM = curX - mNode.x
                    val dzM = curZ - mNode.z
                    if (dxM * dxM + dzM * dzM < 6400f) {
                        val arrivedName = input.manualDestName ?: "tu destino"
                        manualTargetNodeIndex = null
                        manualDestinationName = null
                        effectiveManualNode = null
                        voiceAssistant.announceArrival(arrivedName)
                    }
                }

                val movedDistSq = (curX - lastTrackingX) * (curX - lastTrackingX) + (curZ - lastTrackingZ) * (curZ - lastTrackingZ)
                val shouldEvaluateTracking = (now - lastTrackingCheckTime > 200L) || (movedDistSq > 4.0f)

                val tracking = if (shouldEvaluateTracking) {
                    val t = withContext(Dispatchers.Default) {
                        graph.evaluateRouteTracking(curX, curZ, curHeading, input.speedKmh, input.gear, activeRoute)
                    }
                    lastTrackingCheckTime = now
                    lastTrackingX = curX
                    lastTrackingZ = curZ
                    t
                } else null

                if (tracking != null) {
                    isWrongWay = tracking.isWrongWay
                    if (tracking.isWrongWay && now - lastWrongWayVoiceTime > 9000L) {
                        lastWrongWayVoiceTime = now
                        voiceAssistant.speak("Precaución, sentido contrario")
                    }
                    if (tracking.isOffRoute) offRouteCounter++ else offRouteCounter = 0
                }

                // Autodetección: 1) Destino manual independiente, 2) Nodo enviado por servidor, 3) Ciudad/empresa de trabajo
                val targetNode: Int? = if (effectiveManualNode != null) {
                    effectiveManualNode
                } else if (input.telemetryTargetNode != null) {
                    input.telemetryTargetNode
                } else if (input.destinationCity.isNotEmpty() || input.destinationCityId.isNotEmpty()) {
                    val destCity = input.destinationCity.trim()
                    val destCityId = input.destinationCityId.trim()
                    val destCompany = input.destinationCompany.trim()
                    val destCompanyId = input.destinationCompanyId.trim()

                    fun norm(s: String): String = s.lowercase().replace("_", "").replace(" ", "").replace("-", "")

                    val normCity = norm(destCity)
                    val normCityId = norm(destCityId)
                    val normComp = norm(destCompany)
                    val normCompId = norm(destCompanyId)

                    // 1. Coincidencia por empresa y ciudad (priorizar exacta sobre parcial)
                    val exactCompMatch = graph.companies.firstOrNull { c ->
                        val cCityNorm = norm(c.cityName)
                        val cNameNorm = norm(c.name)
                        val cityExact = (normCity.isNotEmpty() && cCityNorm == normCity) ||
                            (normCityId.isNotEmpty() && cCityNorm == normCityId)
                        val compExact = normComp.isEmpty() ||
                            cNameNorm == normComp ||
                            (normCompId.isNotEmpty() && cNameNorm == normCompId)
                        cityExact && compExact
                    }
                    val partialCompMatch = exactCompMatch ?: graph.companies.firstOrNull { c ->
                        val cCityNorm = norm(c.cityName)
                        val cNameNorm = norm(c.name)
                        val cityMatches = (normCity.isNotEmpty() && (cCityNorm == normCity || cCityNorm.contains(normCity) || normCity.contains(cCityNorm))) ||
                            (normCityId.isNotEmpty() && (cCityNorm == normCityId || cCityNorm.contains(normCityId) || normCityId.contains(cCityNorm)))
                        val compMatches = normComp.isEmpty() ||
                            cNameNorm == normComp || cNameNorm.contains(normComp) || normComp.contains(cNameNorm) ||
                            (normCompId.isNotEmpty() && (cNameNorm == normCompId || cNameNorm.contains(normCompId) || normCompId.contains(cNameNorm)))
                        cityMatches && compMatches
                    }

                    // 2. Coincidencia por ciudad (priorizar exacta sobre parcial)
                    partialCompMatch?.nearestNodeIndex
                        ?: graph.cities.firstOrNull { c ->
                            val cNorm = norm(c.name)
                            (normCity.isNotEmpty() && cNorm == normCity) ||
                                (normCityId.isNotEmpty() && cNorm == normCityId)
                        }?.nearestNodeIndex
                        ?: graph.cities.firstOrNull { c ->
                            val cNorm = norm(c.name)
                            (normCity.isNotEmpty() && (cNorm.contains(normCity) || normCity.contains(cNorm))) ||
                                (normCityId.isNotEmpty() && (cNorm.contains(normCityId) || normCityId.contains(cNorm)))
                        }?.nearestNodeIndex
                } else {
                    null
                }

                if (targetNode != null) {
                    val effectiveDestLabel = input.manualDestName ?: input.destinationCity
                    val isNewDestination = effectiveDestLabel != lastRoutedDestCity ||
                        input.destinationCompany != lastRoutedDestComp ||
                        targetNode != lastTargetNodeIndex
                    val isConfirmedOffRoute = tracking != null && (tracking.distanceToRouteMeters > 48f || offRouteCounter >= 4) &&
                        (now - lastRouteCalcTime > 1500L)
                    val shouldCompute = activeRoute == null || isNewDestination || isConfirmedOffRoute

                    if (shouldCompute) {
                        if (isNewDestination) {
                            routeJob?.cancel()
                        }
                        if (isNewDestination || routeJob == null || !routeJob!!.isActive) {
                            val snap = tracking?.currentSnap ?: withContext(Dispatchers.Default) {
                                graph.snapToRoad(curX, curZ, curHeading, null, 45f)
                            }
                            val startNode = snap?.edge?.fromNodeIndex?.takeIf { graph.adjacency.containsKey(it) }
                                ?: withContext(Dispatchers.Default) {
                                    graph.findBestRouteStartNode(curX, curZ, curHeading)
                                }
                            lastRoutedDestCity = effectiveDestLabel
                            lastRoutedDestComp = input.destinationCompany
                            lastTargetNodeIndex = targetNode

                            if (targetNode == startNode) {
                                activeRoute = RouteResult(listOf(startNode), emptyList(), 0f)
                                lastRouteCalcTime = System.currentTimeMillis()
                                offRouteCounter = 0
                            } else {
                                val navDistSnapshot = input.navDistanceMeters
                                routeJob = coroutineScope.launch {
                                    val route = withContext(Dispatchers.Default) {
                                        AStarRouter.findRoute(
                                            graph = graph,
                                            startNode = startNode,
                                            targetNode = targetNode,
                                            headingDeg = curHeading,
                                            initialEdge = snap?.edge
                                        )
                                    }
                                    lastRouteCalcTime = System.currentTimeMillis()
                                    offRouteCounter = 0
                                    if (route != null) {
                                        activeRoute = route
                                        if (navDistSnapshot > 0f) {
                                            val destText = if (effectiveDestLabel.isNotEmpty()) effectiveDestLabel else "tu destino"
                                            voiceAssistant.updateNavigation(navDistSnapshot, "hacia $destText")
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    routeJob?.cancel()
                    activeRoute = null
                    lastRoutedDestCity = ""
                    lastRoutedDestComp = ""
                    lastTargetNodeIndex = -1
                    offRouteCounter = 0
                }
            }
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Color(0xFF0F172A),
            surface = Color(0xFF1E293B),
            onBackground = Color.White,
            onSurface = Color.White,
            primary = Color(0xFF38BDF8)
        )
    ) {
        Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0F172A))) {
            if (!isConnected) {
                // ==========================================
                // PANTALLA DE EMPAREJAMIENTO CON LA PC
                // ==========================================
                PairingScreen(
                    networkClient = networkClient,
                    authTokenStore = authTokenStore,
                    sessionRevision = sessionRevision,
                    ip = serverIp,
                    onIpChange = { serverIp = it },
                    port = serverPort,
                    onPortChange = { serverPort = it },
                    pin = serverPin,
                    onPinChange = { serverPin = it },
                    isLoading = isPairing,
                    errorMessage = pairError,
                    onClearSavedSession = {
                        authTokenStore.clearToken()
                        loadedMapHost = null
                        serverPin = ""
                        pairError = null
                        sessionRevision++
                    },
                    onConnect = { targetHost ->
                        val cleanHost = targetHost.trim()
                        if (cleanHost.isEmpty()) {
                            pairError = "Ingresa la IP del servidor PC."
                            return@PairingScreen
                        }
                        isPairing = true
                        pairError = null
                        coroutineScope.launch {
                            val portNum = serverPort.toIntOrNull() ?: 35500
                            val enteredPin = serverPin.trim()
                            val savedToken = if (enteredPin.isEmpty()) authTokenStore.getValidToken(cleanHost) else null

                            val activeToken: String? = if (savedToken != null) {
                                savedToken
                            } else {
                                if (enteredPin.length != 6) {
                                    pairError = "Ingresa el PIN de 6 digitos del servidor PC."
                                    isPairing = false
                                    return@launch
                                }
                                val pairResult = networkClient.pairWithServer(cleanHost, portNum, enteredPin)
                                if (pairResult.isSuccess) {
                                    val paired = pairResult.getOrThrow()
                                    authTokenStore.saveToken(cleanHost, paired.token, paired.expiresInHours)
                                    sessionRevision++
                                    serverPin = ""
                                    paired.token
                                } else {
                                    pairError = pairResult.exceptionOrNull()?.message ?: "Error de conexion"
                                    isPairing = false
                                    return@launch
                                }
                            }

                            if (activeToken != null) {
                                val needsMapDownload = mapGraph == null || loadedMapHost != cleanHost
                                val mapOk = if (needsMapDownload) {
                                    val mapResult = networkClient.downloadMap(cleanHost, portNum, activeToken)
                                    if (mapResult.isSuccess) {
                                        mapGraph = mapResult.getOrThrow()
                                        loadedMapHost = cleanHost
                                        true
                                    } else {
                                        val err = mapResult.exceptionOrNull()
                                        if (err is UnauthorizedException) {
                                            authTokenStore.clearToken()
                                            loadedMapHost = null
                                            sessionRevision++
                                            pairError = "Sesion expirada o no autorizada. Ingresa el PIN actual del servidor PC."
                                        } else {
                                            pairError = "Error descargando mapa vial: " + (err?.message ?: "Codigo de error del servidor")
                                        }
                                        false
                                    }
                                } else {
                                    true
                                }

                                if (mapOk) {
                                    networkClient.connectWebSocket(
                                        host = cleanHost,
                                        port = portNum,
                                        token = activeToken,
                                        onTelemetry = { telemetry = it },
                                        onStatusChanged = { connected -> isConnected = connected },
                                        onAuthExpired = {
                                            authTokenStore.clearToken()
                                            loadedMapHost = null
                                            sessionRevision++
                                            isConnected = false
                                            pairError = "Sesion expirada o no autorizada. Ingresa el PIN actual del servidor PC."
                                        }
                                    )
                                    isConnected = true
                                }
                            }
                            isPairing = false
                        }
                    }
                )
            } else {
                // ==========================================
                // VISTA PRINCIPAL DE NAVEGACIÓN Y TABLERO
                // ==========================================
                when (selectedTab) {
                    AppTab.MAPA -> {
                        Box(modifier = Modifier.fillMaxSize()) {
                            // 1. Renderizador del Mapa en Canvas 2D/3D con Zoom y Pan interactivo
                            GpsMapCanvas(
                                graph = mapGraph,
                                telemetry = telemetry,
                                activeRoute = activeRoute,
                                viewMode = viewMode,
                                mapTheme = mapTheme,
                                zoomScale = zoomScale,
                                panOffset = panOffset,
                                onZoomChange = { zoomScale = it },
                                onPanChange = { 
                                    panOffset = it
                                    isFollowingTruck = false
                                },
                                onMapTapped = { tapX, tapZ ->
                                    val graph = mapGraph
                                    if (graph != null && graph.nodes.isNotEmpty()) {
                                        // Buscar si tocó un POI (gasolinera, descanso, taller) cercano a 120m
                                        val nearestPoi = graph.pois.minByOrNull { poi ->
                                            val dx = poi.x - tapX
                                            val dz = poi.z - tapZ
                                            dx * dx + dz * dz
                                        }
                                        val poiDistSq = if (nearestPoi != null) {
                                            val dx = nearestPoi.x - tapX
                                            val dz = nearestPoi.z - tapZ
                                            dx * dx + dz * dz
                                        } else Float.MAX_VALUE

                                        val targetNode = if (poiDistSq < 14400f && nearestPoi != null) {
                                            graph.findNearestNode(nearestPoi.x, nearestPoi.z)
                                        } else {
                                            // Buscar el tramo de carretera más cercano al toque
                                            graph.findNearestNode(tapX, tapZ, mustHaveEdges = true)
                                        }

                                        if (targetNode in graph.nodes.indices) {
                                            val destLabel = if (poiDistSq < 14400f && nearestPoi != null) {
                                                nearestPoi.name.ifEmpty { "Punto de Interés" }
                                            } else {
                                                val c = graph.findNearestCity(tapX, tapZ)
                                                c?.name ?: "Destino Seleccionado"
                                            }
                                            manualTargetNodeIndex = targetNode
                                            manualDestinationName = destLabel
                                        }
                                    }
                                },
                                isFollowingTruck = isFollowingTruck,
                                modifier = Modifier.fillMaxSize()
                            )

                            // 1.5. Tarjeta de Navegación Giro a Giro con Guía de Carriles táctica (Turn-By-Turn)
                            if (activeRoute != null && isFollowingTruck) {
                                Column(
                                    modifier = Modifier
                                        .align(Alignment.TopStart)
                                        .padding(top = 80.dp, start = 14.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    TurnByTurnCard(
                                        truckX = telemetry.posX.toFloat(),
                                        truckZ = telemetry.posZ.toFloat(),
                                        activeRoute = activeRoute,
                                        destinationCity = effectiveDestinationName,
                                        instructionOverride = currentInstruction
                                    )
                                    if (manualTargetNodeIndex != null) {
                                        Row(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(Color(0xEE1E293B))
                                                .border(1.dp, Color(0xFF38BDF8), RoundedCornerShape(10.dp))
                                                .clickable {
                                                    manualTargetNodeIndex = null
                                                    manualDestinationName = null
                                                }
                                                .padding(horizontal = 10.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Text(
                                                text = "Ruta manual: ${manualDestinationName ?: "Destino"}",
                                                color = Color.White,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            Text(
                                                text = "Cancelar",
                                                color = Color(0xFF38BDF8),
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                            }

                            // 1.6. Columna Superior Derecha: Entrega en Curso + JunctionView (apilados sin solaparse)
                            val hasJob = telemetry.destinationCity.isNotEmpty() || telemetry.isCargoLoaded || telemetry.jobIncome > 0
                            val junctionInstr = currentInstruction?.takeIf {
                                it.maneuver != ManeuverType.STRAIGHT && it.distanceMeters in 50f..800f
                            }
                            if (hasJob || junctionInstr != null) {
                                Column(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(top = 80.dp, end = 74.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                    horizontalAlignment = Alignment.End
                                ) {
                                    if (hasJob) {
                                        Card(
                                            modifier = Modifier.widthIn(max = 280.dp),
                                            colors = CardDefaults.cardColors(containerColor = Color(0xF2151C21)),
                                            shape = RoundedCornerShape(14.dp),
                                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF222D35))
                                        ) {
                                            Column(modifier = Modifier.padding(12.dp)) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text(
                                                        "ENTREGA EN CURSO",
                                                        color = Color(0xFFC7F36B),
                                                        fontWeight = FontWeight.Black,
                                                        fontSize = 10.sp,
                                                        letterSpacing = 0.5.sp
                                                    )
                                                    if (telemetry.speedLimitKmh > 5f) {
                                                        Box(
                                                            modifier = Modifier
                                                                .size(24.dp)
                                                                .clip(CircleShape)
                                                                .background(Color.White)
                                                                .border(2.dp, Color(0xFFDC2626), CircleShape),
                                                            contentAlignment = Alignment.Center
                                                        ) {
                                                            Text(
                                                                "${telemetry.speedLimitKmh.roundToInt()}",
                                                                color = Color.Black,
                                                                fontWeight = FontWeight.Black,
                                                                fontSize = 10.sp
                                                            )
                                                        }
                                                    }
                                                }

                                                Spacer(modifier = Modifier.height(4.dp))

                                                val dest = if (telemetry.destinationCity.isNotEmpty()) telemetry.destinationCity else "Destino"
                                                val comp = if (telemetry.destinationCompany.isNotEmpty()) " (${telemetry.destinationCompany})" else ""
                                                Text(
                                                    "$dest$comp",
                                                    color = Color(0xFFF3F6F7),
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 13.sp,
                                                    maxLines = 1
                                                )

                                                val cargo = if (telemetry.cargoName.isNotEmpty()) telemetry.cargoName else "Carga general"
                                                val tons = if (telemetry.cargoMassKg > 0f) String.format(" • %.1f t", telemetry.cargoMassKg / 1000f) else ""
                                                Text(
                                                    "$cargo$tons",
                                                    color = Color(0xFF94A3B8),
                                                    fontSize = 11.sp,
                                                    maxLines = 1
                                                )

                                                Spacer(modifier = Modifier.height(4.dp))

                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween
                                                ) {
                                                    val km = telemetry.navDistanceMeters / 1000f
                                                    val min = telemetry.navTimeSeconds / 60f
                                                    Text(
                                                        String.format("%.1f km", km),
                                                        color = Color(0xFFC7F36B),
                                                        fontWeight = FontWeight.Black,
                                                        fontSize = 12.sp
                                                    )
                                                    Text(
                                                        String.format("ETA: %.0f min", min),
                                                        color = Color(0xFF94A3B8),
                                                        fontWeight = FontWeight.SemiBold,
                                                        fontSize = 11.sp
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    if (junctionInstr != null) {
                                        JunctionView(
                                            exitName = junctionInstr.title,
                                            destinationName = effectiveDestinationName.ifEmpty { "DESTINO" },
                                            distanceMeters = junctionInstr.distanceMeters,
                                            speedLimitKmh = telemetry.speedLimitKmh
                                        )
                                    }
                                }
                            }

                            // 2. Alertas Superior Central (Sentido Contrario + Parada de Descanso apiladas sin colisión)
                            val isRestStopRelevant = telemetry.restStopMinutes in 1..45
                            val currentMapGraph = mapGraph
                            val mapRestStop = remember(telemetry.posX, telemetry.posZ, currentMapGraph, isRestStopRelevant) {
                                if (isRestStopRelevant && currentMapGraph != null) {
                                    currentMapGraph.findNearestRestStop(telemetry.posX.toFloat(), telemetry.posZ.toFloat())
                                } else null
                            }
                            if (isWrongWay || (isRestStopRelevant && mapRestStop != null)) {
                                Column(
                                    modifier = Modifier
                                        .align(Alignment.TopCenter)
                                        .padding(top = 80.dp, start = 16.dp, end = 16.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    if (isWrongWay) {
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(14.dp))
                                                .background(Color(0xEEB91C1C))
                                                .border(2.dp, Color(0xFFFCA5A5), RoundedCornerShape(14.dp))
                                                .padding(horizontal = 18.dp, vertical = 10.dp)
                                        ) {
                                            Text(
                                                text = "ALERTA: SENTIDO CONTRARIO - RECALCULANDO RUTA",
                                                color = Color.White,
                                                fontWeight = FontWeight.Black,
                                                fontSize = 14.sp
                                            )
                                        }
                                    }

                                    if (isRestStopRelevant && mapRestStop != null) {
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(Color(0xF0111827))
                                                .border(1.5.dp, Color(0xFFF59E0B), RoundedCornerShape(10.dp))
                                                .padding(horizontal = 12.dp, vertical = 6.dp)
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .clip(RoundedCornerShape(4.dp))
                                                        .background(Color(0x33F59E0B))
                                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                                ) {
                                                    Text("DESCANSO", color = Color(0xFFF59E0B), fontSize = 9.sp, fontWeight = FontWeight.Black)
                                                }
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = "Descanso en ${telemetry.restStopMinutes}m • ${mapRestStop.first.name.ifEmpty { "Área de Servicio" }} (~${mapRestStop.second.roundToInt()} km)",
                                                        color = Color(0xFFFDE68A),
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                }
                                                Button(
                                                    onClick = {
                                                        val node = mapGraph?.findNearestNode(mapRestStop.first.x, mapRestStop.first.z)
                                                        if (node != null && mapGraph?.nodes?.indices?.contains(node) == true) {
                                                            manualTargetNodeIndex = node
                                                            manualDestinationName = mapRestStop.first.name.ifEmpty { "Área de Descanso" }
                                                        }
                                                    },
                                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF59E0B)),
                                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                                    shape = RoundedCornerShape(6.dp),
                                                    modifier = Modifier.height(28.dp)
                                                ) {
                                                    Text("Desviar", color = Color(0xFF1E1B4B), fontSize = 11.sp, fontWeight = FontWeight.Black)
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            // 3. Botones Flotantes de Control (G8: muestra el modo al que CAMBIARÁ)
                            Column(
                                modifier = Modifier
                                    .align(Alignment.CenterEnd)
                                    .padding(end = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                // Selector explícito y visual de Modo de Cámara: 3D Perspectiva vs 2D Aéreo
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = Color(0xF0111827)),
                                    shape = RoundedCornerShape(26.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFF334155))
                                ) {
                                    Column(
                                        modifier = Modifier.padding(3.dp),
                                        verticalArrangement = Arrangement.spacedBy(3.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        val is3D = viewMode == MapViewMode.MODE_3D_PERSPECTIVE
                                        val is2D = viewMode == MapViewMode.MODE_2D_TOP_DOWN

                                        // Pastilla 3D
                                        Box(
                                            modifier = Modifier
                                                .size(width = 46.dp, height = 34.dp)
                                                .clip(RoundedCornerShape(20.dp))
                                                .background(if (is3D) Color(0xFF0284C7) else Color.Transparent)
                                                .clickable {
                                                    viewMode = MapViewMode.MODE_3D_PERSPECTIVE
                                                    panOffset = androidx.compose.ui.geometry.Offset.Zero
                                                    isFollowingTruck = true
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                "3D",
                                                fontWeight = FontWeight.Black,
                                                fontSize = 12.sp,
                                                color = if (is3D) Color.White else Color(0xFF94A3B8)
                                            )
                                        }

                                        // Pastilla 2D
                                        Box(
                                            modifier = Modifier
                                                .size(width = 46.dp, height = 34.dp)
                                                .clip(RoundedCornerShape(20.dp))
                                                .background(if (is2D) Color(0xFF0284C7) else Color.Transparent)
                                                .clickable {
                                                    viewMode = MapViewMode.MODE_2D_TOP_DOWN
                                                    panOffset = androidx.compose.ui.geometry.Offset.Zero
                                                    isFollowingTruck = true
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                "2D",
                                                fontWeight = FontWeight.Black,
                                                fontSize = 12.sp,
                                                color = if (is2D) Color.White else Color(0xFF94A3B8)
                                            )
                                        }
                                    }
                                }

                                // Botón Zoom +
                                FloatingActionButton(
                                    onClick = {
                                        zoomScale = (zoomScale * 1.35f).coerceAtMost(4.5f)
                                    },
                                    containerColor = Color(0xFF1E293B),
                                    contentColor = Color.White,
                                    shape = CircleShape,
                                    modifier = Modifier.size(50.dp)
                                ) {
                                    Text("+", fontWeight = FontWeight.Black, fontSize = 24.sp)
                                }

                                // Botón Zoom -
                                FloatingActionButton(
                                    onClick = {
                                        zoomScale = (zoomScale / 1.35f).coerceAtLeast(0.12f)
                                    },
                                    containerColor = Color(0xFF1E293B),
                                    contentColor = Color.White,
                                    shape = CircleShape,
                                    modifier = Modifier.size(50.dp)
                                ) {
                                    Text("−", fontWeight = FontWeight.Black, fontSize = 24.sp)
                                }

                                // Botón Re-centrar en el Camión
                                FloatingActionButton(
                                    onClick = {
                                        panOffset = androidx.compose.ui.geometry.Offset.Zero
                                        isFollowingTruck = true
                                        zoomScale = 1.0f
                                    },
                                    containerColor = if (!isFollowingTruck) Color(0xFF0284C7) else Color(0xFF1E293B),
                                    contentColor = if (!isFollowingTruck) Color.White else Color(0xFF38BDF8),
                                    shape = CircleShape,
                                    modifier = Modifier.size(50.dp)
                                ) {
                                    Text("CTR", fontSize = 12.sp, fontWeight = FontWeight.Black)
                                }

                                // Botón de Diagnóstico Rápido G1
                                FloatingActionButton(
                                    onClick = { showDiagnostics = true },
                                    containerColor = Color(0xFF1E293B),
                                    contentColor = Color(0xFF38BDF8),
                                    shape = CircleShape,
                                    modifier = Modifier.size(50.dp)
                                ) {
                                    Text("CFG", fontSize = 12.sp, fontWeight = FontWeight.Black)
                                }

                                // Botón de Estilo de Mapa
                                FloatingActionButton(
                                    onClick = {
                                        mapTheme = when (mapTheme) {
                                            MapThemeStyle.DARK_PREMIUM -> MapThemeStyle.HIGH_CONTRAST
                                            MapThemeStyle.HIGH_CONTRAST -> MapThemeStyle.CYBER_NEON
                                            MapThemeStyle.CYBER_NEON -> MapThemeStyle.DARK_PREMIUM
                                        }
                                    },
                                    containerColor = Color(0xFF1E293B),
                                    contentColor = Color(0xFFC7F36B),
                                    shape = CircleShape,
                                    modifier = Modifier.size(50.dp)
                                ) {
                                    Text("STY", fontSize = 12.sp, fontWeight = FontWeight.Black)
                                }
                            }

                            // 4. Pastilla flotante de aviso si se desplazó el mapa libremente
                            if (!isFollowingTruck) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .padding(bottom = 54.dp)
                                        .clip(RoundedCornerShape(20.dp))
                                        .background(Color(0xD90284C7))
                                        .clickable {
                                            panOffset = androidx.compose.ui.geometry.Offset.Zero
                                            isFollowingTruck = true
                                        }
                                        .padding(horizontal = 16.dp, vertical = 8.dp)
                                ) {
                                    Text(
                                        text = "EXPLORACION LIBRE - PULSA PARA CENTRAR",
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }
                    }
                    AppTab.TELEMETRIA -> {
                        DashboardScreen(
                            telemetry = telemetry,
                            nearestRestStop = mapGraph?.findNearestRestStop(telemetry.posX.toFloat(), telemetry.posZ.toFloat()),
                            onRouteToRestStop = { poi ->
                                val nearestNode = mapGraph?.findNearestNode(poi.x, poi.z)
                                if (nearestNode != null && mapGraph?.nodes?.indices?.contains(nearestNode) == true) {
                                    manualTargetNodeIndex = nearestNode
                                    manualDestinationName = poi.name.ifEmpty { "Área de Descanso" }
                                    selectedTab = AppTab.MAPA
                                }
                            }
                        )
                    }
                    AppTab.BITACORA -> {
                        TripLogScreen(
                            tripLogManager = tripLogManager
                        )
                    }
                    AppTab.ACERCA_DE -> {
                        AboutScreen()
                    }
                }

                // Barra Superior de Pestañas y Navegación con HUD de Carga
                TopNavigationBar(
                    selectedTab = selectedTab,
                    onTabSelected = { selectedTab = it },
                    telemetry = telemetry,
                    onOpenDiagnostics = { showDiagnostics = true }
                )

                // Barra Inferior de Estado
                BottomTelemetryBar(
                    telemetry = telemetry,
                    modifier = Modifier.align(Alignment.BottomCenter)
                )

                // Pantalla de Diagnóstico G1
                if (showDiagnostics) {
                    TelemetryDiagnosticScreen(
                        telemetry = telemetry,
                        onClose = { showDiagnostics = false }
                    )
                }
            }
        }
    }
}

// =====================================================================
// COMPONENTES UI: NAVEGACIÓN, PESTAÑAS Y DIÁLOGOS (SISTEMA VISUAL DARK)
// =====================================================================

@Composable
fun TopNavigationBar(
    selectedTab: AppTab,
    onTabSelected: (AppTab) -> Unit,
    telemetry: TelemetryData,
    onOpenDiagnostics: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xF50C1013))
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Branding + Status Badge
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Telemetría GPS",
                color = Color(0xFFF3F6F7),
                fontWeight = FontWeight.Black,
                fontSize = 16.sp
            )

            // Connection / Game status pill
            val isPaused = telemetry.gamePaused
            val isLive = telemetry.connected && !isPaused
            val badgeBg = when {
                isPaused -> Color(0x33F7C948)
                isLive -> Color(0x33C7F36B)
                else -> Color(0x33EF4444)
            }
            val badgeBorder = when {
                isPaused -> Color(0xFFF7C948)
                isLive -> Color(0xFFC7F36B)
                else -> Color(0xFFEF4444)
            }
            val badgeText = when {
                isPaused -> "JUEGO EN PAUSA"
                isLive -> "SIMULADOR CONECTADO"
                else -> "DESCONECTADO"
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(badgeBg)
                    .border(1.dp, badgeBorder, RoundedCornerShape(12.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(badgeBorder)
                    )
                    Text(
                        text = badgeText,
                        color = badgeBorder,
                        fontWeight = FontWeight.Bold,
                        fontSize = 9.sp,
                        letterSpacing = 0.5.sp
                    )
                }
            }
        }

        // Segmented Tabs: [ Mapa ] [ Telemetría ] [ Acerca de ]
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF151C21))
                .border(1.dp, Color(0xFF222D35), RoundedCornerShape(10.dp))
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            NavigationTabButton("Mapa", selectedTab == AppTab.MAPA) { onTabSelected(AppTab.MAPA) }
            NavigationTabButton("Telemetría", selectedTab == AppTab.TELEMETRIA) { onTabSelected(AppTab.TELEMETRIA) }
            NavigationTabButton("Bitácora", selectedTab == AppTab.BITACORA) { onTabSelected(AppTab.BITACORA) }
            NavigationTabButton("Acerca de", selectedTab == AppTab.ACERCA_DE) { onTabSelected(AppTab.ACERCA_DE) }
        }

        // Settings / Diagnostics button
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(Color(0xFF151C21))
                .border(1.dp, Color(0xFF222D35), CircleShape)
                .clickable(onClick = onOpenDiagnostics),
            contentAlignment = Alignment.Center
        ) {
            Text("CFG", fontSize = 10.sp, fontWeight = FontWeight.Black, color = Color(0xFF38BDF8))
        }
    }
}

@Composable
fun NavigationTabButton(text: String, isSelected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(if (isSelected) Color(0xFF1E262C) else Color.Transparent)
            .border(
                1.dp,
                if (isSelected) Color(0xFFC7F36B).copy(alpha = 0.5f) else Color.Transparent,
                RoundedCornerShape(7.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (isSelected) Color(0xFFC7F36B) else Color(0xFF94A3B8),
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            fontSize = 12.sp
        )
    }
}

@Composable
fun BottomTelemetryBar(telemetry: TelemetryData, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xF50C1013))
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Vehicle quick glanceable telemetry
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Gear badge
            val gearText = when {
                telemetry.gear == 0 -> "N"
                telemetry.gear < 0 -> "R"
                else -> "D${telemetry.gear}"
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF151C21))
                    .border(1.dp, Color(0xFF222D35), RoundedCornerShape(6.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = gearText,
                    color = Color(0xFFC7F36B),
                    fontWeight = FontWeight.Black,
                    fontSize = 14.sp
                )
            }

            // Speed
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "${telemetry.speedKmh.roundToInt()}",
                    color = Color(0xFFF3F6F7),
                    fontWeight = FontWeight.Black,
                    fontSize = 20.sp
                )
                Text(
                    text = "km/h",
                    color = Color(0xFF94A3B8),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(bottom = 2.dp)
                )
            }

            // RPM
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "${telemetry.engineRpm.roundToInt()}",
                    color = Color(0xFFF3F6F7),
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
                Text(
                    text = "rpm",
                    color = Color(0xFF94A3B8),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(bottom = 2.dp)
                )
            }

            // Fuel
            val fuelPct = if (telemetry.fuelCapacity > 0f) {
                ((telemetry.fuel / telemetry.fuelCapacity) * 100f).roundToInt()
            } else 0
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("FUEL", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF94A3B8))
                Text(
                    text = "${telemetry.fuel.roundToInt()} L ($fuelPct%)",
                    color = if (fuelPct < 15) Color(0xFFEF4444) else Color(0xFF94A3B8),
                    fontWeight = FontWeight.Medium,
                    fontSize = 12.sp
                )
            }
        }

        // Simulator connectivity text
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val statusColor = when {
                telemetry.gamePaused -> Color(0xFFF7C948)
                telemetry.connected -> Color(0xFFC7F36B)
                else -> Color(0xFFEF4444)
            }
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(statusColor)
            )
            Text(
                text = when {
                    telemetry.gamePaused -> "Pausa"
                    telemetry.connected -> "Simulador Activo"
                    else -> "Sin Conexión"
                },
                color = Color(0xFF94A3B8),
                fontSize = 11.sp
            )
        }
    }
}

@Composable
fun PairingScreen(
    networkClient: AtsNetworkClient,
    authTokenStore: AuthTokenStore,
    sessionRevision: Int,
    ip: String,
    onIpChange: (String) -> Unit,
    port: String,
    onPortChange: (String) -> Unit,
    pin: String,
    onPinChange: (String) -> Unit,
    isLoading: Boolean,
    errorMessage: String?,
    onClearSavedSession: () -> Unit,
    onConnect: (String) -> Unit
) {
    // Autodeteccion silenciosa de servidor activo; solo reconecta directo si ya hay sesion valida (< 48 h)
    var autoProbing by remember { mutableStateOf(errorMessage == null) }
    val hasSavedSession = remember(ip, sessionRevision) {
        authTokenStore.getValidToken(ip.trim()) != null
    }

    LaunchedEffect(Unit) {
        if (errorMessage != null) {
            autoProbing = false
            return@LaunchedEffect
        }
        val portNum = port.toIntOrNull() ?: 35500
        val candidates = listOf("127.0.0.1", "192.168.42.129", "192.168.42.1", ip)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        for (host in candidates) {
            val isReady = networkClient.checkServerStatus(host, portNum)
            if (isReady) {
                onIpChange(host)
                autoProbing = false
                if (authTokenStore.getValidToken(host) != null) {
                    onConnect(host)
                }
                return@LaunchedEffect
            }
        }
        autoProbing = false
    }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = Color.White,
        unfocusedTextColor = Color.White,
        focusedBorderColor = Color(0xFF38BDF8),
        unfocusedBorderColor = Color(0xFF334155),
        focusedLabelColor = Color(0xFF38BDF8),
        unfocusedLabelColor = Color(0xFF94A3B8),
        cursorColor = Color(0xFF38BDF8),
        focusedContainerColor = Color(0xFF1E293B),
        unfocusedContainerColor = Color(0xFF1E293B)
    )
    val inputStyle = TextStyle(color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Column(
            modifier = Modifier.widthIn(max = 460.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("ATS GPS", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                if (autoProbing || isLoading) {
                    "Buscando servidor..."
                } else {
                    "El PIN de 6 digitos del servidor PC se requiere en modo Wi-Fi y USB. La sesion se guarda durante 48 horas."
                },
                fontSize = 13.sp,
                color = Color(0xFF94A3B8)
            )
            Spacer(modifier = Modifier.height(20.dp))

            if (autoProbing) {
                CircularProgressIndicator(color = Color(0xFF38BDF8), modifier = Modifier.size(36.dp))
            } else {
                if (hasSavedSession) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0x1AC7F36B))
                            .border(1.dp, Color(0xFF84CC16), RoundedCornerShape(10.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Sesion guardada activa (48 h)",
                            color = Color(0xFFC7F36B),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Cambiar PIN",
                            color = Color(0xFF38BDF8),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clickable(enabled = !isLoading) {
                                onClearSavedSession()
                            }
                        )
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                }

                OutlinedTextField(
                    value = ip,
                    onValueChange = onIpChange,
                    label = { Text("IP de la PC (Wi-Fi o 127.0.0.1 USB)", color = Color(0xFF94A3B8)) },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = inputStyle,
                    colors = fieldColors,
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(14.dp))

                OutlinedTextField(
                    value = port,
                    onValueChange = onPortChange,
                    label = { Text("Puerto", color = Color(0xFF94A3B8)) },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = inputStyle,
                    colors = fieldColors,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(14.dp))

                OutlinedTextField(
                    value = pin,
                    onValueChange = { raw ->
                        onPinChange(raw.filter { it.isDigit() }.take(6))
                    },
                    label = {
                        Text(
                            if (hasSavedSession) "Nuevo PIN de 6 digitos (opcional)" else "PIN de 6 digitos (Wi-Fi y USB)",
                            color = Color(0xFF94A3B8)
                        )
                    },
                    placeholder = {
                        Text(
                            if (hasSavedSession) "Deja vacio para reconectar directo" else "Ingresa los 6 digitos del servidor PC",
                            color = Color(0xFF64748B),
                            fontSize = 13.sp
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = inputStyle,
                    colors = fieldColors,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(22.dp))

                if (errorMessage != null) {
                    Text(text = errorMessage, color = Color(0xFFEF4444), fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(14.dp))
                }

                val canReconnectSaved = hasSavedSession && pin.isBlank()
                val canPairWithPin = pin.trim().length == 6
                Button(
                    onClick = { onConnect(ip) },
                    enabled = !isLoading && ip.trim().isNotEmpty() && (canReconnectSaved || canPairWithPin),
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7))
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(22.dp))
                    } else {
                        Text(
                            text = if (canReconnectSaved) "Reconectar directo" else "Conectar",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp
                        )
                    }
                }
            }
        }
    }
}
