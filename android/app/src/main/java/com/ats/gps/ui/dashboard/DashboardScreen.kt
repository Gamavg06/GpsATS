package com.ats.gps.ui.dashboard

import android.media.AudioManager
import android.media.ToneGenerator
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ats.gps.data.model.MapPoi
import com.ats.gps.data.model.TelemetryData
import kotlin.math.roundToInt

@Composable
fun DashboardScreen(
    telemetry: TelemetryData,
    onOpenDiagnostics: () -> Unit = {},
    nearestRestStop: Pair<MapPoi, Float>? = null,
    onRouteToRestStop: (MapPoi) -> Unit = {},
    modifier: Modifier = Modifier
) {
    // Alerta sonora configurable de exceso de velocidad
    var isAudioAlertEnabled by remember { mutableStateOf(true) }
    val isSpeeding = telemetry.speedLimitKmh > 5f && telemetry.speedKmh > (telemetry.speedLimitKmh + 1.5f)

    LaunchedEffect(isSpeeding, isAudioAlertEnabled) {
        if (isSpeeding && isAudioAlertEnabled && !telemetry.gamePaused) {
            try {
                val toneGen = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 65)
                toneGen.startTone(ToneGenerator.TONE_PROP_BEEP2, 180)
            } catch (_: Exception) {
            }
        }
    }

    // Atenuación visual elegante si el juego está pausado (HUD · Juego en Pausa)
    val contentAlpha = if (telemetry.gamePaused) 0.88f else 1.0f

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0C1013)) // Fondo ultra oscuro Figma
            .padding(top = 88.dp, bottom = 48.dp, start = 16.dp, end = 16.dp)
            .alpha(contentAlpha)
    ) {
        val isLandscape = maxWidth > 850.dp

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Tarjeta Resumen de Entrega y Trabajo (Delivery summary de Figma)
            DeliverySummaryCard(telemetry = telemetry)

            if (isLandscape) {
                // Disposición horizontal de 2 columnas para Tablets (1280x800)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Columna 1: Instrumentación digital y Relojes
                    InstrumentGaugesCard(
                        telemetry = telemetry,
                        modifier = Modifier.weight(1f)
                    )

                    // Columna 2: Desgaste Mecánico y Combustible
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        VehicleWearCard(telemetry = telemetry)
                        FuelAndRangeCard(
                            telemetry = telemetry,
                            nearestRestStop = nearestRestStop,
                            onRouteToRestStop = onRouteToRestStop
                        )
                    }
                }
            } else {
                // Disposición vertical para pantallas compactas
                InstrumentGaugesCard(telemetry = telemetry)
                VehicleWearCard(telemetry = telemetry)
                FuelAndRangeCard(
                    telemetry = telemetry,
                    nearestRestStop = nearestRestStop,
                    onRouteToRestStop = onRouteToRestStop
                )
            }
        }
    }
}

// =====================================================================
// 1. RESUMEN DE ENTREGA (Delivery Summary)
// =====================================================================
@Composable
private fun DeliverySummaryCard(
    telemetry: TelemetryData,
    modifier: Modifier = Modifier
) {
    val hasJob = telemetry.destinationCity.isNotEmpty() || telemetry.isCargoLoaded || telemetry.jobIncome > 0

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF151C21))
            .border(1.dp, Color(0xFF1E262C), RoundedCornerShape(18.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Badge superior verde neón de Figma
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0x22C7F36B))
                    .border(1.dp, Color(0x55C7F36B), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    text = if (hasJob) "ENTREGA EN CURSO" else "CONDUCCIÓN LIBRE",
                    color = Color(0xFFC7F36B),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
            }

            if (telemetry.jobIncome > 0) {
                Text(
                    text = "$${telemetry.jobIncome}",
                    color = Color(0xFFC7F36B),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            Column(modifier = Modifier.weight(1f)) {
                val destText = when {
                    telemetry.destinationCity.isNotEmpty() && telemetry.destinationCompany.isNotEmpty() ->
                        "${telemetry.destinationCity} • ${telemetry.destinationCompany}"
                    telemetry.destinationCity.isNotEmpty() -> telemetry.destinationCity
                    else -> "Sin destino asignado"
                }

                Text(
                    text = destText,
                    color = Color(0xFFF3F6F7),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )

                val cargoText = if (telemetry.cargoName.isNotEmpty() && telemetry.cargoMassKg > 0) {
                    val tonnes = telemetry.cargoMassKg / 1000f
                    "${telemetry.cargoName} • ${String.format("%.1f", tonnes)} t"
                } else if (telemetry.cargoName.isNotEmpty()) {
                    telemetry.cargoName
                } else {
                    "Sin carga en remolque"
                }

                Text(
                    text = cargoText,
                    color = Color(0xFF94A3B8),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            if (telemetry.navDistanceMeters > 0f) {
                Column(horizontalAlignment = Alignment.End) {
                    val distKm = (telemetry.navDistanceMeters / 1000f).roundToInt()
                    Text(
                        text = "$distKm km",
                        color = Color(0xFFF3F6F7),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )

                    val timeMin = (telemetry.navTimeSeconds / 60).roundToInt()
                    val etaH = timeMin / 60
                    val etaM = timeMin % 60
                    val etaText = if (etaH > 0) "${etaH}h ${etaM}m restantes" else "${etaM}m restantes"
                    Text(
                        text = etaText,
                        color = Color(0xFF94A3B8),
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

// =====================================================================
// 2. INSTRUMENTACIÓN DIGITAL DE CABINA
// =====================================================================
@Composable
private fun InstrumentGaugesCard(
    telemetry: TelemetryData,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF151C21))
            .border(1.dp, Color(0xFF1E262C), RoundedCornerShape(18.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "INSTRUMENTACIÓN DIGITAL",
            color = Color(0xFF94A3B8),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )

        val speed = telemetry.speedKmh.coerceAtLeast(0f)
        val rpm = telemetry.engineRpm.coerceAtLeast(0f)
        val fuel = telemetry.fuel.coerceAtLeast(0f)

        // Interpoladores animados continuos para 60-120Hz sin saltos
        val animatedSpeed by animateFloatAsState(
            targetValue = speed,
            animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
            label = "speedAnim"
        )
        val animatedRpm by animateFloatAsState(
            targetValue = rpm,
            animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium),
            label = "rpmAnim"
        )
        val animatedFuel by animateFloatAsState(
            targetValue = fuel,
            animationSpec = tween(durationMillis = 350, easing = LinearOutSlowInEasing),
            label = "fuelAnim"
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Velocímetro Digital con Límite de Velocidad
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "${animatedSpeed.roundToInt()}",
                    color = Color(0xFFF3F6F7),
                    fontSize = 54.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "KM / H",
                    color = Color(0xFF94A3B8),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )

                if (telemetry.speedLimitKmh > 5f) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                            .border(3.dp, Color(0xFFEF4444), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "${telemetry.speedLimitKmh.roundToInt()}",
                            color = Color.Black,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                }
            }

            // Marcha Actual (Caja de cambios)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val gearText = when {
                    telemetry.gear < 0 -> "R${-telemetry.gear}"
                    telemetry.gear == 0 -> "N"
                    else -> "D${telemetry.gear}"
                }
                Box(
                    modifier = Modifier
                        .size(width = 80.dp, height = 70.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF0C1013))
                        .border(1.5.dp, Color(0xFF38BDF8), RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = gearText,
                        color = Color(0xFF38BDF8),
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Black
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "MARCHA",
                    color = Color(0xFF94A3B8),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            // Tacómetro RPM
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "${animatedRpm.roundToInt()}",
                    color = Color(0xFFC7F36B),
                    fontSize = 42.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "RPM",
                    color = Color(0xFF94A3B8),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        // Fila de Métricas Secundarias (Presión, Consumo y Rango)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            val fuelPct = if (telemetry.fuelCapacity > 0f) {
                ((telemetry.fuel / telemetry.fuelCapacity) * 100f).roundToInt()
            } else 0
            val range = if (telemetry.fuelRangeKm > 0f) telemetry.fuelRangeKm.roundToInt() else (telemetry.fuel * 2.2f).roundToInt()

            MetricPill("Nivel Combustible", "$fuelPct %")
            MetricPill("Autonomía Est.", "~$range km")
            MetricPill("Marcha Tablero", if (telemetry.gear == 0) "N" else if (telemetry.gear < 0) "R" else "D${telemetry.gear}")
        }
    }
}

@Composable
private fun MetricPill(label: String, value: String) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF0C1013))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(text = label, color = Color(0xFF94A3B8), fontSize = 10.sp)
        Text(text = value, color = Color(0xFFF3F6F7), fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

// =====================================================================
// 3. DESGASTE MECÁNICO POR COMPONENTE (Vehicle wear de Figma)
// =====================================================================
@Composable
private fun VehicleWearCard(
    telemetry: TelemetryData,
    modifier: Modifier = Modifier
) {
    val maxWear = maxOf(
        telemetry.wearWheels,
        telemetry.wearTransmission,
        telemetry.wearEngine,
        telemetry.wearChassis,
        telemetry.wearCabin
    )
    val isAllGood = maxWear < 0.10f

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF151C21))
            .border(1.dp, Color(0xFF1E262C), RoundedCornerShape(18.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "DESGASTE POR COMPONENTE",
                color = Color(0xFF94A3B8),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )

            // Health Badge idéntico a Figma
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (isAllGood) Color(0x22C7F36B) else Color(0x22F7C948))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    text = if (isAllGood) "OK Todo en orden" else "AVISO Revision requerida",
                    color = if (isAllGood) Color(0xFFC7F36B) else Color(0xFFF7C948),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // 5 Barras de Desgaste (Neumáticos, Transmisión, Motor, Chasis, Cabina)
        WearRow("Neumáticos", telemetry.wearWheels)
        WearRow("Transmisión", telemetry.wearTransmission)
        WearRow("Motor", telemetry.wearEngine)
        WearRow("Chasis", telemetry.wearChassis)
        WearRow("Cabina", telemetry.wearCabin)
    }
}

@Composable
private fun WearRow(label: String, wearFraction: Float) {
    val pct = (wearFraction * 100f).roundToInt().coerceIn(0, 100)
    val barColor = when {
        pct < 6 -> Color(0xFFC7F36B) // Verde Neón Figma
        pct < 15 -> Color(0xFFF7C948) // Amarillo Ámbar
        else -> Color(0xFFEF4444)     // Rojo Alerta
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = label,
            color = Color(0xFFF3F6F7),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.width(90.dp)
        )

        // Barra de progreso estilizada
        Box(
            modifier = Modifier
                .weight(1f)
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xFF1E262C))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction = (pct / 100f).coerceIn(0.01f, 1f))
                    .clip(RoundedCornerShape(4.dp))
                    .background(barColor)
            )
        }

        Text(
            text = "$pct%",
            color = barColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(36.dp)
        )
    }
}

// =====================================================================
// 4. COMBUSTIBLE Y AUTONOMÍA (Fuel level de Figma)
// =====================================================================
@Composable
private fun FuelAndRangeCard(
    telemetry: TelemetryData,
    nearestRestStop: Pair<MapPoi, Float>?,
    onRouteToRestStop: (MapPoi) -> Unit,
    modifier: Modifier = Modifier
) {
    val fuelPct = if (telemetry.fuelCapacity > 0f) {
        ((telemetry.fuel / telemetry.fuelCapacity) * 100f).roundToInt()
    } else 0

    val avgL100 = telemetry.fuelAvgConsumption
    val rangeKm = if (telemetry.fuelRangeKm > 0f) {
        telemetry.fuelRangeKm.roundToInt()
    } else {
        (telemetry.fuel * 2.4f).roundToInt()
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF151C21))
            .border(1.dp, Color(0xFF1E262C), RoundedCornerShape(18.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "COMBUSTIBLE Y AUTONOMÍA",
                color = Color(0xFF94A3B8),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )

            // Recomendación de parada
            val restMin = telemetry.restStopMinutes
            val recText = when {
                restMin in 1..45 -> "Descanso pronto"
                fuelPct < 15 -> "Repostaje urgente"
                else -> "Sin paradas necesarias"
            }
            val recColor = if (fuelPct < 15 || restMin in 1..45) Color(0xFFF7C948) else Color(0xFFC7F36B)

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(recColor.copy(alpha = 0.15f))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    text = recText,
                    color = recColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Barra de combustible visual animada con estilo Figma
        val animatedFuelPct by animateFloatAsState(
            targetValue = fuelPct.toFloat(),
            animationSpec = tween(durationMillis = 400, easing = LinearOutSlowInEasing),
            label = "fuelPctAnim"
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "${telemetry.fuel.roundToInt()} L (${animatedFuelPct.roundToInt()}%)",
                    color = Color(0xFFF3F6F7),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (avgL100 > 0.05f) "Consumo medio: ${String.format("%.1f", avgL100)} L/100km" else "Consumo estimado normal",
                    color = Color(0xFF94A3B8),
                    fontSize = 12.sp
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "~$rangeKm km",
                    color = Color(0xFF38BDF8),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Autonomía estimada",
                    color = Color(0xFF94A3B8),
                    fontSize = 12.sp
                )
            }
        }

        // Temporizador de Descanso y Desvío Rápido
        if (nearestRestStop != null) {
            Spacer(modifier = Modifier.height(2.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF0C1013))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Parada cercana: ${nearestRestStop.first.name.ifEmpty { "Área de Servicio" }} (~${nearestRestStop.second.roundToInt()} km)",
                    color = Color(0xFFCBD5E1),
                    fontSize = 11.sp
                )
                Button(
                    onClick = { onRouteToRestStop(nearestRestStop.first) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF38BDF8)),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.height(26.dp)
                ) {
                    Text("Marcar", color = Color(0xFF0C1013), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}