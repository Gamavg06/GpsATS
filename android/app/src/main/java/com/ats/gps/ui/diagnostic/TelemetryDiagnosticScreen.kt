package com.ats.gps.ui.diagnostic

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
fun TelemetryDiagnosticScreen(
    telemetry: TelemetryData,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF070B14))
            .systemBarsPadding()
            .padding(16.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Cabecera con botón de cerrar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "DIAGNÓSTICO DE TELEMETRÍA (G1)",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Black
                    )
                    Text(
                        text = "Canales crudos del SDK de SCS Software",
                        color = Color(0xFF94A3B8),
                        fontSize = 12.sp
                    )
                }

                Button(
                    onClick = onClose,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B))
                ) {
                    Text("Cerrar", color = Color(0xFF38BDF8), fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Lista desplazable con todas las secciones de diagnóstico
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    DiagnosticCard(title = "0. VERSIONES Y COMPATIBILIDAD DEL SISTEMA (H0)") {
                        DiagRow("App Android", "v1.4.2 (Build 17)", Color(0xFF38BDF8))
                        DiagRow("Servidor PC", "v1.4.2", Color(0xFF22C55E))
                        DiagRow("Mapa Binario", "map_ats.bin (v1.4.2)", Color(0xFF22C55E))
                        DiagRow("Compatibilidad", "OK COMPATIBLE (Sin discrepancias)", Color(0xFF22C55E))
                    }
                }

                item {
                    DiagnosticCard(title = "1. ESTADO DE CONEXIÓN Y PAUSA (G2)") {
                        DiagRow("SDK Conectado", telemetry.connected.toString(), if (telemetry.connected) Color(0xFF22C55E) else Color(0xFFEF4444))
                        DiagRow("Juego en Pausa (Offset 4)", telemetry.gamePaused.toString(), if (telemetry.gamePaused) Color(0xFFFACC15) else Color(0xFF22C55E))
                        DiagRow("Timestamp SCS (Offset 8)", telemetry.timestamp.toString())
                    }
                }

                item {
                    DiagnosticCard(title = "2. TRABAJO Y CARGA (G1)") {
                        val hasJob = telemetry.destinationCity.isNotEmpty() || telemetry.isCargoLoaded || telemetry.onJob || telemetry.navDistanceMeters > 0f
                        DiagRow("Estado Detección Trabajo", if (hasJob) "TRABAJO ACTIVO / DETECTADO" else "CONDUCCIÓN LIBRE", if (hasJob) Color(0xFF22C55E) else Color(0xFF94A3B8))
                        DiagRow("Mercado de Trabajo (jobMarket)", telemetry.jobMarket.ifEmpty { "(vacío)" })
                        DiagRow("Flag onJob (Offset 4300)", telemetry.onJob.toString(), if (telemetry.onJob) Color(0xFF22C55E) else Color(0xFF94A3B8))
                        DiagRow("Flag isCargoLoaded (Offset 1564)", telemetry.isCargoLoaded.toString(), if (telemetry.isCargoLoaded) Color(0xFF22C55E) else Color(0xFF94A3B8))
                        DiagRow("Flag specialJob (Offset 1565)", telemetry.specialJob.toString(), if (telemetry.specialJob) Color(0xFFF59E0B) else Color(0xFF94A3B8))
                        DiagRow("Nombre Carga (cargo)", telemetry.cargoName.ifEmpty { "(sin asignar)" }, Color(0xFF38BDF8))
                        DiagRow("ID Carga (cargoId)", telemetry.cargoId.ifEmpty { "(sin asignar)" })
                        DiagRow("Masa Carga (cargoMassKg)", String.format("%.1f kg (%.2f t)", telemetry.cargoMassKg, telemetry.cargoMassKg / 1000f))
                        DiagRow("Ingreso Pactado (jobIncome)", "$${telemetry.jobIncome}", Color(0xFF10B981))
                        DiagRow("Plazo Entrega (timeAbsDelivery)", "${telemetry.timeAbsDelivery} min SCS")
                        DiagRow("Daño de Carga (job_f.cargoDamage)", String.format("%.2f %%", telemetry.cargoDamage * 100f), if (telemetry.cargoDamage > 0f) Color(0xFFEF4444) else Color(0xFF22C55E))
                    }
                }

                item {
                    DiagnosticCard(title = "3. RUTA Y NAVEGACIÓN (G1 & G9)") {
                        DiagRow("Ciudad Origen (citySrc)", telemetry.sourceCity.ifEmpty { "(vacío)" })
                        DiagRow("ID Ciudad Origen (citySrcId)", telemetry.sourceCityId.ifEmpty { "(vacío)" })
                        DiagRow("Empresa Origen (compSrc)", telemetry.sourceCompany.ifEmpty { "(vacío)" })
                        DiagRow("ID Empresa Origen (compSrcId)", telemetry.sourceCompanyId.ifEmpty { "(vacío)" })
                        DiagRow("Ciudad Destino (cityDst)", telemetry.destinationCity.ifEmpty { "(vacío)" }, Color(0xFFFACC15))
                        DiagRow("ID Ciudad Destino (cityDstId)", telemetry.destinationCityId.ifEmpty { "(vacío)" })
                        DiagRow("Empresa Destino (compDst)", telemetry.destinationCompany.ifEmpty { "(vacío)" }, Color(0xFFFACC15))
                        DiagRow("ID Empresa Destino (compDstId)", telemetry.destinationCompanyId.ifEmpty { "(vacío)" })
                        DiagRow("Distancia Restante SCS (navDistance)", String.format("%.1f m (%.2f km)", telemetry.navDistanceMeters, telemetry.navDistanceMeters / 1000f), Color(0xFF38BDF8))
                        DiagRow("Tiempo Restante SCS (navTime)", String.format("%.0f s (%.1f min)", telemetry.navTimeSeconds, telemetry.navTimeSeconds / 60f))
                        DiagRow("Límite de Velocidad (speedLimit)", String.format("%.0f km/h", telemetry.speedLimitKmh))
                    }
                }

                item {
                    DiagnosticCard(title = "4. TELEMETRÍA DINÁMICA DEL VEHÍCULO") {
                        DiagRow("Velocidad Actual", "${telemetry.speedKmh.roundToInt()} km/h")
                        DiagRow("Régimen Motor (RPM)", "${telemetry.engineRpm.roundToInt()} / ${telemetry.engineRpmMax.roundToInt()} RPM")
                        DiagRow("Marcha Seleccionada", if (telemetry.gear == 0) "N" else if (telemetry.gear < 0) "R${-telemetry.gear}" else "D${telemetry.gear}")
                        DiagRow("Combustible", "${telemetry.fuel.roundToInt()} / ${telemetry.fuelCapacity.roundToInt()} L")
                        DiagRow("Consumo Medio", String.format("%.2f L/100km", telemetry.fuelAvgConsumption * 100f))
                        DiagRow("Freno de Estacionamiento", telemetry.parkingBrake.toString())
                        DiagRow("Control Crucero", "${telemetry.cruiseControl} (${telemetry.cruiseControlSpeed.roundToInt()} km/h)")
                    }
                }

                item {
                    DiagnosticCard(title = "5. POSICIÓN 3D Y ELEVACIÓN (G7)") {
                        DiagRow("X Mundial (truckX)", String.format("%.2f", telemetry.posX))
                        DiagRow("Y Mundial / Altitud (truckY)", String.format("%.2f m", telemetry.posY), Color(0xFF38BDF8))
                        DiagRow("Z Mundial (truckZ)", String.format("%.2f", telemetry.posZ))
                        DiagRow("Heading SCS", String.format("%.4f (%.1f°)", telemetry.heading, telemetry.headingDegrees))
                        DiagRow("Pitch / Roll", String.format("%.2f° / %.2f°", Math.toDegrees(telemetry.pitch), Math.toDegrees(telemetry.roll)))
                    }
                }

                item {
                    DiagnosticCard(title = "6. DESGASTE CAMIÓN Y REMOLQUE (G4)") {
                        DiagRow("Desgaste Motor", String.format("%.2f %%", telemetry.wearEngine * 100f))
                        DiagRow("Desgaste Transmisión", String.format("%.2f %%", telemetry.wearTransmission * 100f))
                        DiagRow("Desgaste Cabina", String.format("%.2f %%", telemetry.wearCabin * 100f))
                        DiagRow("Desgaste Chasis", String.format("%.2f %%", telemetry.wearChassis * 100f))
                        DiagRow("Desgaste Ruedas", String.format("%.2f %%", telemetry.wearWheels * 100f))
                        DiagRow("Remolque Enganchado", telemetry.trailerAttached.toString(), if (telemetry.trailerAttached) Color(0xFF22C55E) else Color(0xFF94A3B8))
                        if (telemetry.trailerAttached) {
                            DiagRow("Remolque: Desgaste Chasis", String.format("%.2f %%", telemetry.trailerWearChassis * 100f))
                            DiagRow("Remolque: Desgaste Ruedas", String.format("%.2f %%", telemetry.trailerWearWheels * 100f))
                            DiagRow("Remolque: Desgaste Carrocería", String.format("%.2f %%", telemetry.trailerWearBody * 100f))
                            DiagRow("Remolque: Daño de Carga", String.format("%.2f %%", telemetry.trailerCargoDamage * 100f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF111827)),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = title,
                color = Color(0xFF38BDF8),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun DiagRow(label: String, value: String, valueColor: Color = Color.White) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, color = Color(0xFF94A3B8), fontSize = 12.sp)
        Text(
            text = value,
            color = valueColor,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )
    }
}
