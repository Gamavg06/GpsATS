package com.ats.gps.ui.dashboard

import android.media.AudioManager
import android.media.ToneGenerator
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ats.gps.data.model.TelemetryData
import kotlin.math.roundToInt

@Composable
fun DashboardScreen(
    telemetry: TelemetryData,
    onOpenDiagnostics: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    // Estado de alerta sonora configurable (G3)
    var isAudioAlertEnabled by remember { mutableStateOf(true) }
    val isSpeeding = telemetry.speedLimitKmh > 5f && telemetry.speedKmh > (telemetry.speedLimitKmh + 1.5f)

    // Animación de pulso para exceso de velocidad (G3)
    val infiniteTransition = rememberInfiniteTransition(label = "speeding_pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(450, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )

    // Tono audible configurable en exceso de velocidad (G3)
    LaunchedEffect(isSpeeding, isAudioAlertEnabled) {
        if (isSpeeding && isAudioAlertEnabled && !telemetry.gamePaused) {
            try {
                val toneGen = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 65)
                toneGen.startTone(ToneGenerator.TONE_PROP_BEEP2, 180)
            } catch (_: Exception) {
            }
        }
    }

    // Atenuación visual si el juego está pausado (G2)
    val contentAlpha = if (telemetry.gamePaused) 0.52f else 1.0f

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF070B14))
            .navigationBarsPadding()
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        val isLandscape = maxWidth > maxHeight

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 1. Barra de Luces e Indicadores de Estado (Contraste >= 3:1)
            TopIndicatorsBar(
                telemetry = telemetry,
                isAudioAlertEnabled = isAudioAlertEnabled,
                onToggleAudio = { isAudioAlertEnabled = !isAudioAlertEnabled },
                onOpenDiagnostics = onOpenDiagnostics
            )

            // 2. Banner de Juego en Pausa (G2)
            if (telemetry.gamePaused) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0x33FACC15))
                        .border(1.5.dp, Color(0xFFFACC15), RoundedCornerShape(8.dp))
                        .padding(vertical = 8.dp, horizontal = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("⏸", fontSize = 18.sp)
                        Text(
                            text = "JUEGO EN PAUSA — TELEMETRÍA CONGELADA",
                            color = Color(0xFFFACC15),
                            fontWeight = FontWeight.Black,
                            fontSize = 13.sp
                        )
                    }
                }
            }

            // 3. Banner de Exceso de Velocidad (G3)
            if (isSpeeding && !telemetry.gamePaused) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFFDC2626).copy(alpha = pulseAlpha * 0.35f))
                        .border(2.dp, Color(0xFFEF4444).copy(alpha = pulseAlpha), RoundedCornerShape(8.dp))
                        .padding(vertical = 8.dp, horizontal = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("⚠️", fontSize = 18.sp)
                        Text(
                            text = "¡EXCESO DE VELOCIDAD! LÍMITE: ${telemetry.speedLimitKmh.roundToInt()} KM/H",
                            color = Color(0xFFFCA5A5),
                            fontWeight = FontWeight.Black,
                            fontSize = 14.sp
                        )
                    }
                }
            }

            // 4. Tablero de Relojes Analógicos Propios (G4)
            if (isLandscape) {
                // Modo horizontal (Side by side)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .alpha(contentAlpha),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GaugesSection(telemetry, gaugeSize = 175.dp)
                    Column(
                        modifier = Modifier.weight(1f).padding(start = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        VehicleStateCard(telemetry)
                        FuelAndIncomeCard(telemetry)
                    }
                }
            } else {
                // Modo vertical (Portrait)
                Box(modifier = Modifier.fillMaxWidth().alpha(contentAlpha)) {
                    GaugesSection(telemetry, gaugeSize = 165.dp)
                }

                VehicleStateCard(telemetry = telemetry, modifier = Modifier.alpha(contentAlpha))
                FuelAndIncomeCard(telemetry = telemetry, modifier = Modifier.alpha(contentAlpha))
            }

            Spacer(modifier = Modifier.height(60.dp)) // Espacio seguro para la barra de navegación inferior
        }
    }
}

@Composable
private fun GaugesSection(telemetry: TelemetryData, gaugeSize: androidx.compose.ui.unit.Dp) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Velocímetro Circular Propio
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularGauge(
                value = telemetry.speedKmh,
                maxValue = 160f,
                label = "VELOCIDAD",
                unit = "KM/H",
                gaugeSize = gaugeSize,
                primaryColor = Color(0xFF38BDF8),
                warningColor = Color(0xFFEF4444),
                warningThreshold = if (telemetry.speedLimitKmh > 5f) telemetry.speedLimitKmh else 130f
            )

            if (telemetry.speedLimitKmh > 0f) {
                Spacer(modifier = Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                        .border(2.5.dp, Color(0xFFEF4444), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "${telemetry.speedLimitKmh.roundToInt()}",
                        color = Color.Black,
                        fontWeight = FontWeight.Black,
                        fontSize = 14.sp
                    )
                }
            }
        }

        // Indicador de Marcha Central
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF1E293B))
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (telemetry.gear == 0) "N" else if (telemetry.gear < 0) "R${-telemetry.gear}" else "D${telemetry.gear}",
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Black,
                    color = Color(0xFF38BDF8)
                )
            }
            Text("MARCHA", fontSize = 10.sp, color = Color(0xFF94A3B8), fontWeight = FontWeight.Bold)
        }

        // Tacómetro Circular Propio
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val maxRpm = if (telemetry.engineRpmMax > 1000f) telemetry.engineRpmMax else 2500f
            CircularGauge(
                value = telemetry.engineRpm,
                maxValue = maxRpm,
                label = "MOTOR",
                unit = "RPM",
                gaugeSize = gaugeSize,
                primaryColor = Color(0xFF10B981),
                warningColor = Color(0xFFEF4444),
                warningThreshold = maxRpm * 0.82f
            )
        }
    }
}

@Composable
private fun TopIndicatorsBar(
    telemetry: TelemetryData,
    isAudioAlertEnabled: Boolean,
    onToggleAudio: () -> Unit,
    onOpenDiagnostics: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF111827))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Direccional Izquierda
        Text(
            text = "◀",
            fontSize = 22.sp,
            color = if (telemetry.blinkerLeft) Color(0xFF22C55E) else Color(0xFF334155),
            fontWeight = FontWeight.Black
        )

        // Fila Central de Luces (Contraste >= 3:1)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            // Luces Bajas
            IndicatorBadge(label = "D", active = telemetry.lightsBeamLow, activeColor = Color(0xFF22C55E))
            // Luces Altas
            IndicatorBadge(label = "≡", active = telemetry.lightsBeamHigh, activeColor = Color(0xFF38BDF8))
            // Freno de mano
            IndicatorBadge(label = "(P)", active = telemetry.parkingBrake, activeColor = Color(0xFFEF4444))
            // Control crucero
            IndicatorBadge(label = "CRUISE", active = telemetry.cruiseControl, activeColor = Color(0xFF10B981))
        }

        // Botones de Diagnóstico y Audio
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            // Toggle de Audio Alerta
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (isAudioAlertEnabled) Color(0xFF1E3A5F) else Color(0xFF1F2937))
                    .clickable(onClick = onToggleAudio)
                    .padding(horizontal = 7.dp, vertical = 4.dp)
            ) {
                Text(
                    text = if (isAudioAlertEnabled) "🔔" else "🔕",
                    fontSize = 12.sp
                )
            }

            // Botón Diagnóstico G1
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF0284C7))
                    .clickable(onClick = onOpenDiagnostics)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text("DIAG", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Black)
            }

            // Direccional Derecha
            Text(
                text = "▶",
                fontSize = 22.sp,
                color = if (telemetry.blinkerRight) Color(0xFF22C55E) else Color(0xFF334155),
                fontWeight = FontWeight.Black
            )
        }
    }
}

@Composable
private fun IndicatorBadge(label: String, active: Boolean, activeColor: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (active) activeColor.copy(alpha = 0.22f) else Color(0xFF1F2937))
            .border(1.dp, if (active) activeColor else Color(0xFF374151), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = label,
            fontWeight = FontWeight.Black,
            fontSize = 11.sp,
            color = if (active) activeColor else Color(0xFF6B7280)
        )
    }
}

@Composable
private fun VehicleStateCard(telemetry: TelemetryData, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF111827)),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "ESTADO DEL VEHÍCULO Y REMOLQUE",
                fontSize = 12.sp,
                fontWeight = FontWeight.Black,
                color = Color(0xFF38BDF8)
            )
            Spacer(modifier = Modifier.height(8.dp))

            // 5 Canales Reales del Camión
            WearItem("Motor", telemetry.wearEngine)
            WearItem("Transmisión", telemetry.wearTransmission)
            WearItem("Cabina", telemetry.wearCabin)
            WearItem("Chasis", telemetry.wearChassis)
            WearItem("Ruedas", telemetry.wearWheels)

            // Canales de Remolque y Daño de Carga (G4)
            if (telemetry.trailerAttached || telemetry.cargoDamage > 0f) {
                Spacer(modifier = Modifier.height(6.dp))
                HorizontalDivider(color = Color(0xFF1F2937), thickness = 1.dp)
                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "REMOLQUE Y CARGA ASIGNADA",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFF59E0B)
                )
                Spacer(modifier = Modifier.height(4.dp))

                if (telemetry.cargoDamage > 0f) {
                    WearItem("Daño de Carga", telemetry.cargoDamage)
                }
                if (telemetry.trailerAttached) {
                    WearItem("Remolque: Chasis", telemetry.trailerWearChassis)
                    WearItem("Remolque: Ruedas", telemetry.trailerWearWheels)
                    WearItem("Remolque: Carrocería", telemetry.trailerWearBody)
                }
            }
        }
    }
}

@Composable
private fun FuelAndIncomeCard(telemetry: TelemetryData, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF111827))
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceAround
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("COMBUSTIBLE", fontSize = 10.sp, color = Color(0xFF94A3B8), fontWeight = FontWeight.Bold)
            Text("${telemetry.fuel.roundToInt()} L", fontSize = 16.sp, color = Color(0xFFFBBF24), fontWeight = FontWeight.Black)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("CONSUMO MEDIO", fontSize = 10.sp, color = Color(0xFF94A3B8), fontWeight = FontWeight.Bold)
            Text(String.format("%.1f L/100", telemetry.fuelAvgConsumption * 100f), fontSize = 16.sp, color = Color.White, fontWeight = FontWeight.Black)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("GANANCIA", fontSize = 10.sp, color = Color(0xFF94A3B8), fontWeight = FontWeight.Bold)
            Text("$${telemetry.jobIncome}", fontSize = 16.sp, color = Color(0xFF10B981), fontWeight = FontWeight.Black)
        }
    }
}

@Composable
private fun WearItem(label: String, wearValue: Float) {
    val pct = (wearValue * 100f).coerceIn(0f, 100f)
    val color = when {
        pct < 10f -> Color(0xFF22C55E) // Verde
        pct < 25f -> Color(0xFFFBBF24) // Amarillo
        pct < 50f -> Color(0xFFF97316) // Naranja
        else -> Color(0xFFEF4444)      // Rojo
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, modifier = Modifier.width(130.dp), fontSize = 12.sp, color = Color(0xFFCBD5E1))
        LinearProgressIndicator(
            progress = { wearValue.coerceIn(0f, 1f) },
            modifier = Modifier
                .weight(1f)
                .height(7.dp)
                .clip(RoundedCornerShape(4.dp)),
            color = color,
            trackColor = Color(0xFF1E293B)
        )
        Text(
            text = "${pct.roundToInt()}%",
            modifier = Modifier.width(42.dp),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = color,
            fontWeight = FontWeight.Bold,
            textAlign = androidx.compose.ui.text.style.TextAlign.End
        )
    }
}
