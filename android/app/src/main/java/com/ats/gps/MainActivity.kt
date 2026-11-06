package com.ats.gps

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
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
import com.ats.gps.ui.dashboard.DashboardScreen
import com.ats.gps.ui.junction.GarminJunctionView
import com.ats.gps.ui.map.GpsMapCanvas
import com.ats.gps.ui.map.MapViewMode
import com.ats.gps.ui.tts.NavigationVoiceAssistant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

enum class AppTab {
    MAPA_GPS,
    TABLERO
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

    var selectedTab by remember { mutableStateOf(AppTab.MAPA_GPS) }
    var viewMode by remember { mutableStateOf(MapViewMode.MODE_3D_PERSPECTIVE) }
    var showDestSelector by remember { mutableStateOf(false) }

    // Control táctil de Zoom y Desplazamiento (Pan)
    var zoomScale by remember { mutableStateOf(1.0f) }
    var panOffset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    var isFollowingTruck by remember { mutableStateOf(true) }

    val coroutineScope = rememberCoroutineScope()

    // Recalcular ruta automáticamente: autodetecta trabajo de ATS o destino manual
    LaunchedEffect(
        telemetry.posX,
        telemetry.posZ,
        telemetry.destinationCity,
        telemetry.destinationCompany,
        telemetry.targetNodeIndex,
        mapGraph
    ) {
        val graph = mapGraph
        if (graph != null && graph.nodes.isNotEmpty() && (telemetry.posX != 0.0 || telemetry.posZ != 0.0)) {
            val startNode = graph.findNearestNode(telemetry.posX.toFloat(), telemetry.posZ.toFloat())

            // Autodetección: 1) Destino manual explícito, 2) Destino del trabajo en curso de ATS
            val targetNode: Int? = if (telemetry.targetNodeIndex != null) {
                telemetry.targetNodeIndex!!.toInt()
            } else if (telemetry.destinationCity.isNotEmpty()) {
                val destCity = telemetry.destinationCity
                val destCompany = telemetry.destinationCompany

                val compMatch = graph.companies.firstOrNull { c ->
                    c.cityName.equals(destCity, ignoreCase = true) &&
                    (destCompany.isEmpty() || c.name.contains(destCompany, ignoreCase = true))
                }

                compMatch?.nearestNodeIndex ?: graph.cities.firstOrNull { c ->
                    c.name.equals(destCity, ignoreCase = true)
                }?.nearestNodeIndex
            } else {
                null
            }

            if (targetNode != null && targetNode != startNode) {
                withContext(Dispatchers.Default) {
                    val route = AStarRouter.findRoute(graph, startNode, targetNode)
                    activeRoute = route
                }

                if (activeRoute != null && telemetry.navDistanceMeters > 0f) {
                    val destText = if (telemetry.destinationCity.isNotEmpty()) telemetry.destinationCity else "tu destino"
                    voiceAssistant.updateNavigation(telemetry.navDistanceMeters, "hacia $destText")
                }
            } else if (targetNode == null) {
                activeRoute = null
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
                            val pairResult = networkClient.pairWithServer(serverIp, portNum, serverPin)
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
                    AppTab.MAPA_GPS -> {
                        Box(modifier = Modifier.fillMaxSize()) {
                            // 1. Renderizador del Mapa en Canvas 2D/3D con Zoom y Pan interactivo
                            GpsMapCanvas(
                                graph = mapGraph,
                                telemetry = telemetry,
                                activeRoute = activeRoute,
                                viewMode = viewMode,
                                zoomScale = zoomScale,
                                panOffset = panOffset,
                                onZoomChange = { zoomScale = it },
                                onPanChange = { 
                                    panOffset = it
                                    isFollowingTruck = false
                                },
                                isFollowingTruck = isFollowingTruck,
                                modifier = Modifier.fillMaxSize()
                            )

                            // 2. Garmin Junction View (Aparece en esquina superior derecha)
                            if (telemetry.navDistanceMeters in 50f..1200f) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(top = 70.dp, end = 16.dp)
                                ) {
                                    GarminJunctionView(
                                        exitName = "Exit 45B",
                                        destinationName = if (telemetry.destinationCity.isNotEmpty()) telemetry.destinationCity else "ONTARIO",
                                        distanceMeters = telemetry.navDistanceMeters,
                                        speedLimitKmh = telemetry.speedLimitKmh
                                    )
                                }
                            }

                            // 3. Botones Flotantes de Control (Zoom +, Zoom -, Recentrar, 2D/3D, Destino)
                            Column(
                                modifier = Modifier
                                    .align(Alignment.CenterEnd)
                                    .padding(end = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                // Alternar 3D / 2D
                                FloatingActionButton(
                                    onClick = {
                                        viewMode = if (viewMode == MapViewMode.MODE_3D_PERSPECTIVE)
                                            MapViewMode.MODE_2D_TOP_DOWN
                                        else
                                            MapViewMode.MODE_3D_PERSPECTIVE
                                    },
                                    containerColor = Color(0xFF1E293B),
                                    contentColor = Color(0xFF38BDF8),
                                    shape = CircleShape,
                                    modifier = Modifier.size(50.dp)
                                ) {
                                    Text(
                                        text = if (viewMode == MapViewMode.MODE_3D_PERSPECTIVE) "3D" else "2D",
                                        fontWeight = FontWeight.Black,
                                        fontSize = 15.sp
                                    )
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
                                    Text("🎯", fontSize = 20.sp)
                                }

                                // Selector Manual de Destino (Opcional si no hay carga activa)
                                FloatingActionButton(
                                    onClick = { showDestSelector = true },
                                    containerColor = Color(0xFF1E293B),
                                    contentColor = Color(0xFFFACC15),
                                    shape = CircleShape,
                                    modifier = Modifier.size(50.dp)
                                ) {
                                    Text("📍", fontSize = 20.sp)
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
                                        text = "📍 Explorando mapa libremente • Pulsa para volver al camión",
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }
                    }
                    AppTab.TABLERO -> {
                        DashboardScreen(telemetry = telemetry)
                    }
                }

                // Barra Superior de Pestañas y Navegación con HUD de Carga
                TopNavigationBar(
                    selectedTab = selectedTab,
                    onTabSelected = { selectedTab = it },
                    telemetry = telemetry,
                    graph = mapGraph
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
            }
        }
    }
}

// =====================================================================
// COMPONENTES UI: NAVEGACIÓN, PESTAÑAS Y DIÁLOGOS
// =====================================================================

@Composable
fun TopNavigationBar(
    selectedTab: AppTab,
    onTabSelected: (AppTab) -> Unit,
    telemetry: TelemetryData,
    graph: MapGraph?
) {
    val nearestCity = remember(telemetry.posX, telemetry.posZ, graph) {
        if (graph != null && (telemetry.posX != 0.0 || telemetry.posZ != 0.0)) {
            graph.findNearestCity(telemetry.posX.toFloat(), telemetry.posZ.toFloat())
        } else null
    }

    val hasActiveJob = telemetry.destinationCity.isNotEmpty() || telemetry.targetNodeIndex != null

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xE60A0F1D))
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        // Fila 1: Pestañas de control y estado de velocidad
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Estado y Ubicación rápida
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (hasActiveJob) Color(0xFF166534) else Color(0xFF1E293B))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = if (hasActiveJob) "📦 RUTA ACTIVA" else "🚚 CONDUCCIÓN LIBRE",
                        color = if (hasActiveJob) Color(0xFF4ADE80) else Color(0xFF94A3B8),
                        fontWeight = FontWeight.Black,
                        fontSize = 11.sp
                    )
                }

                if (telemetry.speedLimitKmh > 5f) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.White)
                            .padding(horizontal = 6.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = "LÍMITE ${telemetry.speedLimitKmh.roundToInt()}",
                            color = Color.Black,
                            fontWeight = FontWeight.Black,
                            fontSize = 10.sp
                        )
                    }
                }
            }

            // Pestañas MAPA / TABLERO
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TabButton("MAPA GPS", selectedTab == AppTab.MAPA_GPS) { onTabSelected(AppTab.MAPA_GPS) }
                TabButton("TABLERO", selectedTab == AppTab.TABLERO) { onTabSelected(AppTab.TABLERO) }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Fila 2: Tarjeta informativa de la Carga y Ruta
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xCC1E293B)),
            shape = RoundedCornerShape(10.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                if (hasActiveJob) {
                    // Detalle de la Carga
                    val cargoTitle = if (telemetry.cargoName.isNotEmpty()) telemetry.cargoName else "Carga Asignada"
                    val massTons = if (telemetry.cargoMassKg > 0f) String.format(" • %.1f t", telemetry.cargoMassKg / 1000f) else ""
                    val incomeStr = if (telemetry.jobIncome > 0) String.format(" • \$%,d", telemetry.jobIncome) else ""

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "$cargoTitle$massTons$incomeStr",
                            color = Color(0xFF38BDF8),
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )

                        if (telemetry.navDistanceMeters > 0f) {
                            val kmRemaining = telemetry.navDistanceMeters / 1000f
                            val minRemaining = telemetry.navTimeSeconds / 60f
                            Text(
                                text = String.format("%.1f km  (%.0f min)", kmRemaining, minRemaining),
                                color = Color(0xFFFACC15),
                                fontWeight = FontWeight.Black,
                                fontSize = 13.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(2.dp))

                    // Origen y Destino
                    val srcStr = if (telemetry.sourceCity.isNotEmpty()) {
                        if (telemetry.sourceCompany.isNotEmpty()) "${telemetry.sourceCity} (${telemetry.sourceCompany})" else telemetry.sourceCity
                    } else (nearestCity?.name ?: "Origen")

                    val dstStr = if (telemetry.destinationCity.isNotEmpty()) {
                        if (telemetry.destinationCompany.isNotEmpty()) "${telemetry.destinationCity} (${telemetry.destinationCompany})" else telemetry.destinationCity
                    } else "Destino"

                    Text(
                        text = "🚩 $srcStr ➔ 🏁 $dstStr",
                        color = Color.White,
                        fontWeight = FontWeight.Medium,
                        fontSize = 12.sp
                    )
                } else {
                    // Modo Libre (Sin Carga Activa)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            val cityName = nearestCity?.name?.uppercase() ?: "ZONA RURAL / EN RUTA"
                            Text(
                                text = "📍 Cerca de: $cityName",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                            Text(
                                text = "Acepta un trabajo en ATS o pulsa 🎯 para navegar libremente",
                                color = Color(0xFF94A3B8),
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TabButton(text: String, isSelected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (isSelected) Color(0xFF0284C7) else Color(0xFF1E293B))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(text = text, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
    }
}

@Composable
fun BottomTelemetryBar(telemetry: TelemetryData, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xE60A0F1D))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Marcha y Velocidad (o Juego en Pausa)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (telemetry.gamePaused) {
                Text(
                    text = "⏸ JUEGO EN PAUSA",
                    color = Color(0xFFFACC15),
                    fontWeight = FontWeight.Black,
                    fontSize = 16.sp
                )
            } else {
                Text(
                    text = if (telemetry.gear == 0) "N" else if (telemetry.gear < 0) "R" else "D${telemetry.gear}",
                    color = Color(0xFF38BDF8),
                    fontWeight = FontWeight.Black,
                    fontSize = 18.sp
                )
                Text(
                    text = "${telemetry.speedKmh.roundToInt()} km/h",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            }
        }

        // Estado conexión PC y Pausa
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(
                        if (telemetry.gamePaused) Color(0xFFFACC15)
                        else if (telemetry.connected) Color(0xFF22C55E)
                        else Color(0xFFEF4444)
                    )
            )
            Text(
                text = if (telemetry.gamePaused) "Juego en Pausa"
                else if (telemetry.connected) "PC Conectada"
                else "Desconectado",
                color = Color(0xFF94A3B8),
                fontSize = 12.sp
            )
        }
    }
}

@Composable
fun PairingScreen(
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
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = Color.White,
        unfocusedTextColor = Color.White,
        focusedBorderColor = Color(0xFF38BDF8),
        unfocusedBorderColor = Color(0xFF475569),
        focusedLabelColor = Color(0xFF38BDF8),
        unfocusedLabelColor = Color(0xFF94A3B8),
        cursorColor = Color(0xFF38BDF8),
        focusedContainerColor = Color(0xFF1E293B),
        unfocusedContainerColor = Color(0xFF1E293B)
    )
    val inputStyle = TextStyle(color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("ATS TELEMETRÍA GPS", fontSize = 26.sp, fontWeight = FontWeight.Black, color = Color.White)
        Text("Conexión con el servidor PC", fontSize = 14.sp, color = Color(0xFF94A3B8))
        Spacer(modifier = Modifier.height(24.dp))

        OutlinedTextField(
            value = ip,
            onValueChange = onIpChange,
            label = { Text("IP de la PC (ej. 192.168.1.X)", color = Color(0xFF94A3B8)) },
            modifier = Modifier.fillMaxWidth(),
            textStyle = inputStyle,
            colors = fieldColors,
            singleLine = true
        )
        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = port,
            onValueChange = onPortChange,
            label = { Text("Puerto (35500)", color = Color(0xFF94A3B8)) },
            modifier = Modifier.fillMaxWidth(),
            textStyle = inputStyle,
            colors = fieldColors,
            singleLine = true
        )
        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = pin,
            onValueChange = onPinChange,
            label = { Text("PIN de 6 dígitos mostrado en la PC", color = Color(0xFF94A3B8)) },
            modifier = Modifier.fillMaxWidth(),
            textStyle = inputStyle,
            colors = fieldColors,
            singleLine = true
        )
        Spacer(modifier = Modifier.height(20.dp))

        if (errorMessage != null) {
            Text(text = errorMessage, color = Color(0xFFEF4444), fontSize = 13.sp)
            Spacer(modifier = Modifier.height(12.dp))
        }

        Button(
            onClick = onConnect,
            enabled = !isLoading && pin.length >= 6,
            modifier = Modifier.fillMaxWidth().height(50.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7))
        ) {
            if (isLoading) {
                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
            } else {
                Text("EMPAREJAR Y CONECTAR", fontWeight = FontWeight.Bold, fontSize = 15.sp)
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
                .height(450.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "SELECCIONAR DESTINO",
                    fontWeight = FontWeight.Black,
                    fontSize = 16.sp,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(12.dp))

                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(graph.cities) { city ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onDestinationSelected(city.nearestNodeIndex) }
                                .padding(vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("🏙 ${city.name.uppercase()}", color = Color(0xFF38BDF8), fontWeight = FontWeight.Bold)
                            Text("Ciudad", color = Color(0xFF64748B), fontSize = 12.sp)
                        }
                        HorizontalDivider(color = Color(0xFF334155))
                    }

                    items(graph.companies) { comp ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onDestinationSelected(comp.nearestNodeIndex) }
                                .padding(vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("🏢 ${comp.name} (${comp.cityName})", color = Color(0xFF10B981))
                            Text("Empresa", color = Color(0xFF64748B), fontSize = 12.sp)
                        }
                        HorizontalDivider(color = Color(0xFF334155))
                    }
                }
            }
        }
    }
}
