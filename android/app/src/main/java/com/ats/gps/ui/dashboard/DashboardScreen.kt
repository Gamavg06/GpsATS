package com.ats.gps.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ats.gps.data.model.TelemetryData
import kotlin.math.roundToInt

@Composable
fun DashboardScreen(
    telemetry: TelemetryData,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0A0F1D))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // 1. Barra de Estado Superior e Indicadores de Luces
        TopIndicatorsBar(telemetry)

        // 2. Velocímetro y Tacómetro Central
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Velocímetro Digital
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "${telemetry.speedKmh.roundToInt()}",
                    fontSize = 72.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                    color = Color.White
                )
                Text("KM / H", fontSize = 14.sp, color = Color(0xFF94A3B8), fontWeight = FontWeight.Bold)

                if (telemetry.speedLimitKmh > 0f) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.White)
                            .padding(2.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "${telemetry.speedLimitKmh.roundToInt()}",
                            color = Color.Black,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                    }
                }
            }

            // Tacómetro y Marcha
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = if (telemetry.gear == 0) "N" else if (telemetry.gear < 0) "R${-telemetry.gear}" else "D${telemetry.gear}",
                    fontSize = 54.sp,
                    fontWeight = FontWeight.Black,
                    color = Color(0xFF38BDF8)
                )
                Text("MARCHA", fontSize = 13.sp, color = Color(0xFF94A3B8))

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "${telemetry.engineRpm.roundToInt()} RPM",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = if (telemetry.engineRpm > 2000f) Color(0xFFEF4444) else Color(0xFF22C55E)
                )
            }
        }

        // 3. Los 5 Canales Reales de Desgaste del SDK
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF131D31)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "DESGASTE REAL DEL VEHÍCULO (CANALES OFICIALES SDK)",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF64748B)
                )
                Spacer(modifier = Modifier.height(12.dp))

                WearItem("Motor", telemetry.wearEngine)
                WearItem("Transmisión", telemetry.wearTransmission)
                WearItem("Cabina", telemetry.wearCabin)
                WearItem("Chasis", telemetry.wearChassis)
                WearItem("Ruedas", telemetry.wearWheels)
            }
        }

        // 4. Combustible y Consumo
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF131D31))
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceAround
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("COMBUSTIBLE", fontSize = 11.sp, color = Color(0xFF64748B), fontWeight = FontWeight.Bold)
                Text("${telemetry.fuel.roundToInt()} L", fontSize = 18.sp, color = Color(0xFFFBBF24), fontWeight = FontWeight.Bold)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("CONSUMO MEDIO", fontSize = 11.sp, color = Color(0xFF64748B), fontWeight = FontWeight.Bold)
                Text(String.format("%.1f L/100km", telemetry.fuelAvgConsumption * 100f), fontSize = 18.sp, color = Color.White, fontWeight = FontWeight.Bold)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("GANANCIA", fontSize = 11.sp, color = Color(0xFF64748B), fontWeight = FontWeight.Bold)
                Text("$${telemetry.jobIncome}", fontSize = 18.sp, color = Color(0xFF10B981), fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun TopIndicatorsBar(telemetry: TelemetryData) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Direccional Izquierda
        Text(
            text = "◀",
            fontSize = 24.sp,
            color = if (telemetry.blinkerLeft) Color(0xFF22C55E) else Color(0xFF334155),
            fontWeight = FontWeight.Bold
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // Luces Bajas
            Text("D", fontWeight = FontWeight.Bold, color = if (telemetry.lightsBeamLow) Color(0xFF22C55E) else Color(0xFF334155))
            // Luces Altas
            Text("≡", fontWeight = FontWeight.Bold, color = if (telemetry.lightsBeamHigh) Color(0xFF38BDF8) else Color(0xFF334155))
            // Freno de mano
            Text("(P)", fontWeight = FontWeight.Bold, color = if (telemetry.parkingBrake) Color(0xFFEF4444) else Color(0xFF334155))
            // Control crucero
            Text("CRUISE", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = if (telemetry.cruiseControl) Color(0xFF10B981) else Color(0xFF334155))
        }

        // Direccional Derecha
        Text(
            text = "▶",
            fontSize = 24.sp,
            color = if (telemetry.blinkerRight) Color(0xFF22C55E) else Color(0xFF334155),
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun WearItem(label: String, wearValue: Float) {
    val pct = (wearValue * 100f).coerceIn(0f, 100f)
    val color = when {
        pct < 10f -> Color(0xFF22C55E) // Verde
        pct < 30f -> Color(0xFFFBBF24) // Amarillo
        pct < 60f -> Color(0xFFF97316) // Naranja
        else -> Color(0xFFEF4444)      // Rojo crítico
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, modifier = Modifier.width(90.dp), fontSize = 13.sp, color = Color(0xFFCBD5E1))
        LinearProgressIndicator(
            progress = { wearValue.coerceIn(0f, 1f) },
            modifier = Modifier
                .weight(1f)
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp)),
            color = color,
            trackColor = Color(0xFF1E293B)
        )
        Text(
            text = "${pct.roundToInt()}%",
            modifier = Modifier.width(45.dp),
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            color = color,
            fontWeight = FontWeight.Bold,
            textAlign = androidx.compose.ui.text.style.TextAlign.End
        )
    }
}
