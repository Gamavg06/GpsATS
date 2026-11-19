package com.ats.gps

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.lifecycleScope
import com.ats.gps.data.map.AStarRouter
import com.ats.gps.data.map.RouteResult
import com.ats.gps.data.model.MapGraph
import com.ats.gps.data.model.TelemetryData
import com.ats.gps.data.network.AtsNetworkClient
import com.ats.gps.ui.about.AboutScreen
import com.ats.gps.ui.dashboard.DashboardScreen
import com.ats.gps.ui.diagnostic.TelemetryDiagnosticScreen
import com.ats.gps.ui.junction.JunctionView
import com.ats.gps.ui.map.GpsMapCanvas
import com.ats.gps.ui.map.MapViewMode
import com.ats.gps.ui.map.MapThemeStyle
import com.ats.gps.ui.navigation.TurnByTurnCard
import com.ats.gps.ui.tts.NavigationVoiceAssistant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

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
        networkClient.disconnectWebSocket()
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
    var showDestSelector by remember { mutableStateOf(false) }
    var showDiagnostics by remember { mutableStateOf(false) }

    // Control táctil de Zoom y Desplazamiento (Pan)
    var zoomScale by remember { mutableStateOf(1.0f) }
    var panOffset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    var isFollowingTruck by remember { mutableStateOf(true) }

    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val tripLogManager = remember { TripLogManager(context) }

    // Alertas de Voz y Asistente Turn-by-Turn
    var lastSpeedWarningTime by remember { mutableLongStateOf(0L) }
    var lastFuelWarningTime by remember { mutableLongStateOf(0L) }
    var lastRestWarningTime by remember { mutableLongStateOf(0L) }
    var lastArrivalAnnouncedCity by remember { mutableStateOf("") }

    // Rastreo de trabajo para Bitácora automática
    var jobOriginSnapshot by remember { mutableStateOf("") }
    var jobDestSnapshot by remember { mutableStateOf("") }
    var jobCargoSnapshot by remember { mutableStateOf("") }
    var jobMassSnapshot by remember { mutableFloatStateOf(0f) }
    var jobIncomeSnapshot by remember { mutableLongStateOf(0L) }
    var jobInitialDistSnapshot by remember { mutableFloatStateOf(0f) }
    var wasOnJob by remember { mutableStateOf(false) }

    // 1. Asistente de Giro a Giro por Voz continuo
    val currentInstruction = remember(telemetry.posX, telemetry.posZ, activeRoute) {
        if (activeRoute != null && (telemetry.posX != 0.0 || telemetry.posZ != 0.0)) {
            com.ats.gps.ui.navigation.calculateNavigationInstruction(
                truckX = telemetry.posX.toFloat(),
                truckZ = telemetry.posZ.toFloat(),
                route = activeRoute!!,
                destinationCity = telemetry.destinationCity
            )
        } else null
    }

    LaunchedEffect(currentInstruction?.distanceMeters, currentInstruction?.title) {
        if (currentInstruction != null && isFollowingTruck && !telemetry.gamePaused) {
            voiceAssistant.updateNavigation(currentInstruction.distanceMeters, currentInstruction.title)
        }
    }

    // 2. Anuncio de Llegada a Destino
    LaunchedEffect(telemetry.navDistanceMeters, activeRoute) {
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

    // 6. Registro Automático en Bitácora de Conducción al Entregar Carga
    LaunchedEffect(telemetry.onJob, telemetry.destinationCity, telemetry.jobIncome) {
        if (telemetry.onJob || telemetry.destinationCity.isNotEmpty()) {
            wasOnJob = true
            if (telemetry.sourceCity.isNotEmpty()) jobOriginSnapshot = telemetry.sourceCity
            if (telemetry.destinationCity.isNotEmpty()) jobDestSnapshot = telemetry.destinationCity
            if (telemetry.cargoName.isNotEmpty()) jobCargoSnapshot = telemetry.cargoName
            if (telemetry.cargoMassKg > 0f) jobMassSnapshot = telemetry.cargoMassKg
            if (telemetry.jobIncome > 0L) jobIncomeSnapshot = telemetry.jobIncome
            if (telemetry.navDistanceMeters > jobInitialDistSnapshot) jobInitialDistSnapshot = telemetry.navDistanceMeters
        } else if (wasOnJob && jobDestSnapshot.isNotEmpty()) {
            wasOnJob = false
            val drivenKm = if (jobInitialDistSnapshot > 0f) jobInitialDistSnapshot / 1000f else 15f
            tripLogManager.addTrip(
                TripRecord(
                    originCity = jobOriginSnapshot.ifEmpty { "Origen" },
                    destinationCity = jobDestSnapshot,
                    cargoName = jobCargoSnapshot.ifEmpty { "Carga general" },
                    cargoMassKg = jobMassSnapshot,
                    incomeEarned = jobIncomeSnapshot,
                    distanceKm = drivenKm,
                    avgSpeedKmh = telemetry.speedKmh.coerceAtLeast(60f),
                    damagePercent = telemetry.cargoDamage * 100f
                )
            )
            voiceAssistant.speak("Felicidades, trabajo de entrega completado con éxito")
            jobOriginSnapshot = ""
            jobDestSnapshot = ""
            jobCargoSnapshot = ""
            jobMassSnapshot = 0f
            jobIncomeSnapshot = 0L
            jobInitialDistSnapshot = 0f
        }
    }

    // Control de cálculo de ruta: se activa cuando hay destino y origen válido
    val hasValidPos = telemetry.posX != 0.0 || telemetry.posZ != 0.0
    var lastRoutedDestCity by remember { mutableStateOf("") }
    var lastRoutedDestComp by remember { mutableStateOf("") }
    var lastOriginNodeIndex by remember { mutableIntStateOf(-1) }
    var lastRouteCalcTime by remember { mutableLongStateOf(0L) }
    var isWrongWay by remember { mutableStateOf(false) }
    var lastTargetNodeIndex by remember { mutableIntStateOf(-1) }
    var lastWrongWayVoiceTime by remember { mutableLongStateOf(0L) }
    var offRouteCounter by remember { mutableIntStateOf(0) }
    var routeJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var lastTrackingCheckTime by remember { mutableLongStateOf(0L) }
    var lastTrackingX by remember { mutableFloatStateOf(0f) }
    var lastTrackingZ by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(
        telemetry.posX,
        telemetry.posZ,
        telemetry.destinationCity,
        telemetry.destinationCompany,
        telemetry.targetNodeIndex,
        hasValidPos,
        mapGraph
    ) {
        val graph = mapGraph ?: return@LaunchedEffect
        if (graph.nodes.isEmpty() || !hasValidPos) return@LaunchedEffect

        val curX = telemetry.posX.toFloat()
        val curZ = telemetry.posZ.toFloat()
        val curHeading = telemetry.headingDegrees.toFloat()
        val curSpeed = telemetry.speedKmh
        val curGear = telemetry.gear
        val now = System.currentTimeMillis()

        val movedDistSq = (curX - lastTrackingX) * (curX - lastTrackingX) + (curZ - lastTrackingZ) * (curZ - lastTrackingZ)
        val shouldEvaluateTracking = (now - lastTrackingCheckTime > 200L) || (movedDistSq > 4.0f)

        val tracking = if (shouldEvaluateTracking) {
            val t = withContext(Dispatchers.Default) {
                graph.evaluateRouteTracking(curX, curZ, curHeading, curSpeed, curGear, activeRoute)
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

        // Autodetección: 1) Destino manual explícito, 2) Destino del trabajo en curso de ATS
        val targetNode: Int? = if (telemetry.targetNodeIndex != null) {
            telemetry.targetNodeIndex!!.toInt()
        } else if (telemetry.destinationCity.isNotEmpty()) {
            val destCity = telemetry.destinationCity.trim()
            val destCompany = telemetry.destinationCompany.trim()

            // 1. Coincidencia por empresa y ciudad
            val compMatch = graph.companies.firstOrNull { c ->
                c.cityName.equals(destCity, ignoreCase = true) &&
                (destCompany.isEmpty() || c.name.contains(destCompany, ignoreCase = true) || destCompany.contains(c.name, ignoreCase = true))
            }

            // 2. Coincidencia por ciudad
            compMatch?.nearestNodeIndex ?: graph.cities.firstOrNull { c ->
                c.name.equals(destCity, ignoreCase = true) ||
                c.name.contains(destCity, ignoreCase = true) ||
                destCity.contains(c.name, ignoreCase = true)
            }?.nearestNodeIndex
        } else {
            null
        }

        if (targetNode != null) {
            val isNewDestination = telemetry.destinationCity != lastRoutedDestCity ||
                telemetry.destinationCompany != lastRoutedDestComp ||
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
                    lastRoutedDestCity = telemetry.destinationCity
                    lastRoutedDestComp = telemetry.destinationCompany
                    lastTargetNodeIndex = targetNode

                    if (targetNode != startNode) {
                        val headingSnapshot = telemetry.headingDegrees.toFloat()
                        val navDistSnapshot = telemetry.navDistanceMeters
                        val destCitySnapshot = telemetry.destinationCity
                        routeJob = coroutineScope.launch {
                            val route = withContext(Dispatchers.Default) {
                                AStarRouter.findRoute(
                                    graph = graph,
                                    startNode = startNode,
                                    targetNode = targetNode,
                                    headingDeg = headingSnapshot,
                                    initialEdge = snap?.edge
                                )
                            }
                            if (route != null) {
                                activeRoute = route
                                lastOriginNodeIndex = startNode
                                lastRouteCalcTime = System.currentTimeMillis()
                                offRouteCounter = 0
                                if (navDistSnapshot > 0f) {
                                    val destText = if (destCitySnapshot.isNotEmpty()) destCitySnapshot else "tu destino"
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
                    ip = serverIp,
                    onIpChange = { serverIp = it },
                    port = serverPort,
                    onPortChange = { serverPort = it },
                    pin = serverPin,
                    onPinChange = { serverPin = it },
                    isLoading = isPairing,
                    errorMessage = pairError,
                    onConnect = {
                        isPairing = true
                        pairError = null
                        coroutineScope.launch {
                            val portNum = serverPort.toIntOrNull() ?: 35500
                            val effectivePin = if (serverIp == "127.0.0.1" || serverPin.isEmpty()) "USB_DIRECT" else serverPin
                            val pairResult = networkClient.pairWithServer(serverIp, portNum, effectivePin)
                            if (pairResult.isSuccess) {
                                val token = pairResult.getOrThrow()
                                // Descargar mapa binario en streaming
                                val mapResult = networkClient.downloadMap(serverIp, portNum)
                                if (mapResult.isSuccess) {
                                    mapGraph = mapResult.getOrThrow()
                                    // Conectar WebSocket de telemetría a 30 Hz
                                    networkClient.connectTelemetryWebSocket(
                                        host = serverIp,
                                        port = portNum,
                                        token = token,
                                        onTelemetry = { telemetry = it },
                                        onStatusChanged = { connected -> isConnected = connected }
                                    )
                                    isConnected = true
                                } else {
                                    pairError = "Error descargando mapa vial: " + (mapResult.exceptionOrNull()?.message ?: "Código de error del servidor")
                                }
                            } else {
                                pairError = pairResult.exceptionOrNull()?.message ?: "Error de conexión"
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
                                    if (graph != null) {
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

                                        if (targetNode != null) {
                                            val destLabel = if (poiDistSq < 14400f && nearestPoi != null) {
                                                nearestPoi.name.ifEmpty { "Punto de Interés" }
                                            } else {
                                                val c = graph.findNearestCity(tapX, tapZ)
                                                c?.name ?: "Destino Seleccionado"
                                            }
                                            telemetry = telemetry.copy(
                                                targetNodeIndex = targetNode.toLong(),
                                                destinationCity = destLabel
                                            )
                                        }
                                    }
                                },
                                isFollowingTruck = isFollowingTruck,
                                modifier = Modifier.fillMaxSize()
                            )

                            // 1.5. Tarjeta de Navegación Giro a Giro con Guía de Carriles táctica (Turn-By-Turn)
                            if (activeRoute != null && isFollowingTruck) {
                                TurnByTurnCard(
                                    truckX = telemetry.posX.toFloat(),
                                    truckZ = telemetry.posZ.toFloat(),
                                    activeRoute = activeRoute,
                                    destinationCity = telemetry.destinationCity,
                                    instructionOverride = currentInstruction,
                                    modifier = Modifier
                                        .align(Alignment.TopStart)
                                        .padding(top = 80.dp, start = 14.dp)
                                )
                            }

                            // 1.6. Floating Delivery Summary Card (Figma Screen 1: Top-Right)
                            val hasJob = telemetry.destinationCity.isNotEmpty() || telemetry.isCargoLoaded || telemetry.jobIncome > 0
                            if (hasJob) {
                                Card(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(top = 80.dp, end = 74.dp)
                                        .widthIn(max = 280.dp),
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

                            // 2. Alerta de Parada de Descanso Obligatorio (Automática en mapa cuando queda poco tiempo)
                            val isRestStopRelevant = telemetry.restStopMinutes in 1..45
                            val currentMapGraph = mapGraph
                            val mapRestStop = remember(telemetry.posX, telemetry.posZ, currentMapGraph, isRestStopRelevant) {
                                if (isRestStopRelevant && currentMapGraph != null) {
                                    currentMapGraph.findNearestRestStop(telemetry.posX.toFloat(), telemetry.posZ.toFloat())
                                } else null
                            }
                            if (telemetry.restStopMinutes in 1..45 && mapRestStop != null) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopCenter)
                                        .padding(top = 84.dp, start = 16.dp, end = 16.dp)
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
                                                if (node != null) {
                                                    telemetry = telemetry.copy(targetNodeIndex = node.toLong())
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

                            // 3. Junction View (Aparece en esquina superior derecha)
                            if (telemetry.navDistanceMeters in 50f..1200f) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(top = 90.dp, end = 16.dp)
                                ) {
                                    JunctionView(
                                        exitName = "Exit 45B",
                                        destinationName = if (telemetry.destinationCity.isNotEmpty()) telemetry.destinationCity else "DESTINO",
                                        distanceMeters = telemetry.navDistanceMeters,
                                        speedLimitKmh = telemetry.speedLimitKmh
                                    )
                                }
                            }

                            // 3.5. Alerta Visual de Sentido Contrario (Wrong-Way)
                            if (isWrongWay) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopCenter)
                                        .padding(top = 76.dp, start = 20.dp, end = 20.dp)
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
                            onOpenDiagnostics = { showDiagnostics = true },
                            nearestRestStop = mapGraph?.findNearestRestStop(telemetry.posX.toFloat(), telemetry.posZ.toFloat()),
                            onRouteToRestStop = { poi ->
                                val nearestNode = mapGraph?.findNearestNode(poi.x, poi.z)
                                if (nearestNode != null) {
                                    telemetry = telemetry.copy(targetNodeIndex = nearestNode.toLong())
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
                    graph = mapGraph,
                    onOpenDiagnostics = { showDiagnostics = true }
                )

                // Barra Inferior de Estado
                BottomTelemetryBar(
                    telemetry = telemetry,
                    modifier = Modifier.align(Alignment.BottomCenter)
                )

                // Diálogo Selector de Destino Manual
                if (showDestSelector && mapGraph != null) {
                    DestinationSelectorDialog(
                        graph = mapGraph!!,
                        onDismiss = { showDestSelector = false },
                        onDestinationSelected = { destNode ->
                            telemetry = telemetry.copy(targetNodeIndex = destNode.toLong())
                            showDestSelector = false
                        }
                    )
                }

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
// COMPONENTES UI: NAVEGACIÓN, PESTAÑAS Y DIÁLOGOS (FIGMA DESIGN SYSTEM)
// =====================================================================

@Composable
fun TopNavigationBar(
    selectedTab: AppTab,
    onTabSelected: (AppTab) -> Unit,
    telemetry: TelemetryData,
    graph: MapGraph?,
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
            FigmaTabButton("Mapa", selectedTab == AppTab.MAPA) { onTabSelected(AppTab.MAPA) }
            FigmaTabButton("Telemetría", selectedTab == AppTab.TELEMETRIA) { onTabSelected(AppTab.TELEMETRIA) }
            FigmaTabButton("Bitácora", selectedTab == AppTab.BITACORA) { onTabSelected(AppTab.BITACORA) }
            FigmaTabButton("Acerca de", selectedTab == AppTab.ACERCA_DE) { onTabSelected(AppTab.ACERCA_DE) }
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
fun FigmaTabButton(text: String, isSelected: Boolean, onClick: () -> Unit) {
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
    ip: String,
    onIpChange: (String) -> Unit,
    port: String,
    onPortChange: (String) -> Unit,
    pin: String,
    onPinChange: (String) -> Unit,
    isLoading: Boolean,
    errorMessage: String?,
    onConnect: () -> Unit
) {
    // Autodetección silenciosa: si el cable USB está conectado (127.0.0.1 o tethering), entrar directo al mapa
    var autoProbing by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        val portNum = port.toIntOrNull() ?: 35500
        val candidates = listOf("127.0.0.1", "192.168.42.129", "192.168.42.1", ip)
        for (host in candidates) {
            if (host.isNotEmpty()) {
                val isReady = networkClient.checkServerStatus(host, portNum)
                if (isReady) {
                    onIpChange(host)
                    autoProbing = false
                    onConnect()
                    return@LaunchedEffect
                }
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
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("ATS GPS", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            if (autoProbing || isLoading) "Buscando servidor..." else "Ingresa los datos para conectar",
            fontSize = 13.sp,
            color = Color(0xFF94A3B8)
        )
        Spacer(modifier = Modifier.height(28.dp))

        if (autoProbing) {
            CircularProgressIndicator(color = Color(0xFF38BDF8), modifier = Modifier.size(36.dp))
        } else {
            OutlinedTextField(
                value = ip,
                onValueChange = onIpChange,
                label = { Text("IP de la PC", color = Color(0xFF94A3B8)) },
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
                singleLine = true
            )
            Spacer(modifier = Modifier.height(14.dp))

            OutlinedTextField(
                value = pin,
                onValueChange = onPinChange,
                label = { Text("PIN", color = Color(0xFF94A3B8)) },
                modifier = Modifier.fillMaxWidth(),
                textStyle = inputStyle,
                colors = fieldColors,
                singleLine = true
            )
            Spacer(modifier = Modifier.height(24.dp))

            if (errorMessage != null) {
                Text(text = errorMessage, color = Color(0xFFEF4444), fontSize = 13.sp)
                Spacer(modifier = Modifier.height(14.dp))
            }

            Button(
                onClick = onConnect,
                enabled = !isLoading && (pin.length >= 6 || ip == "127.0.0.1"),
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7))
            ) {
                if (isLoading) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(22.dp))
                } else {
                    Text("Conectar", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                }
            }
        }
    }
}

@Composable
fun DestinationSelectorDialog(
    graph: MapGraph,
    onDismiss: () -> Unit,
    onDestinationSelected: (Int) -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.8f),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "SELECCIONAR DESTINO",
                    fontWeight = FontWeight.Black,
                    fontSize = 18.sp,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(12.dp))

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(graph.cities.sortedBy { it.name }) { city ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onDestinationSelected(city.nearestNodeIndex.toInt()) },
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF334155))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = city.name,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    fontSize = 15.sp
                                )
                                Text("Navegar ->", color = Color(0xFF38BDF8), fontSize = 13.sp)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF475569))
                ) {
                    Text("Cancelar", color = Color.White)
                }
            }
        }
    }
}
