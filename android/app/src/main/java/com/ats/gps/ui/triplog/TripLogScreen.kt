package com.ats.gps.ui.triplog

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ats.gps.data.triplog.TripLogManager
import com.ats.gps.data.triplog.TripRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TripLogScreen(
    tripLogManager: TripLogManager,
    modifier: Modifier = Modifier
) {
    var history by remember { mutableStateOf(tripLogManager.getTripHistory()) }
    val totalIncome = remember(history) { history.sumOf { it.incomeEarned } }
    val totalDistance = remember(history) { history.sumOf { it.distanceKm.toDouble() }.toFloat() }
    val totalTrips = history.size

    val bgDark = Color(0xFF0C1013)
    val cardDark = Color(0xFF151C21)
    val borderDark = Color(0xFF222D35)
    val cyanAccent = Color(0xFF38BDF8)
    val limeAccent = Color(0xFFC7F36B)
    val textWhite = Color(0xFFF1F5F9)
    val textGray = Color(0xFF94A3B8)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(bgDark)
            .padding(16.dp)
    ) {
        // Encabezado
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "BITACORA DE ENTREGAS",
                    color = limeAccent,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp
                )
                Text(
                    text = "Historial de viajes y rendimiento del conductor",
                    color = textGray,
                    fontSize = 12.sp
                )
            }

            if (history.isNotEmpty()) {
                TextButton(
                    onClick = {
                        tripLogManager.clearHistory()
                        history = emptyList()
                    }
                ) {
                    Text("Limpiar", color = Color(0xFFEF4444), fontSize = 12.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Tarjetas de Métricas Globales
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Ingresos Totales
            Card(
                modifier = Modifier.weight(1f),
                colors = CardDefaults.cardColors(containerColor = cardDark),
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, borderDark)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("GANANCIAS", color = textGray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        String.format("$%,d", totalIncome),
                        color = limeAccent,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }

            // Distancia Total
            Card(
                modifier = Modifier.weight(1f),
                colors = CardDefaults.cardColors(containerColor = cardDark),
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, borderDark)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("DISTANCIA TOTAL", color = textGray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        String.format("%.1f km", totalDistance),
                        color = cyanAccent,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }

            // Entregas Completadas
            Card(
                modifier = Modifier.weight(0.8f),
                colors = CardDefaults.cardColors(containerColor = cardDark),
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, borderDark)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("VIAJES", color = textGray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        "$totalTrips",
                        color = textWhite,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Lista de Viajes
        if (history.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(cardDark)
                    .border(1.dp, borderDark, RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "Sin entregas registradas aun",
                        color = textWhite,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Al completar un trabajo de carga en ATS, se guardara automaticamente en tu bitacora.",
                        color = textGray,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 32.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(history, key = { it.id }) { trip ->
                    TripRecordCard(trip, cardDark, borderDark, textWhite, textGray, limeAccent, cyanAccent)
                }
            }
        }
    }
}

@Composable
private fun TripRecordCard(
    trip: TripRecord,
    cardBg: Color,
    borderCol: Color,
    textWhite: Color,
    textGray: Color,
    limeAccent: Color,
    cyanAccent: Color
) {
    val dateStr = remember(trip.timestamp) {
        val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
        sdf.format(Date(trip.timestamp))
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = cardBg),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, borderCol)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Fila Superior: Fecha e Ingreso
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(dateStr, color = textGray, fontSize = 11.sp)
                if (trip.incomeEarned > 0) {
                    Text(
                        String.format("+$%,d", trip.incomeEarned),
                        color = limeAccent,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Ruta: Origen -> Destino
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    trip.originCity,
                    color = textWhite,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
                Text("->", color = cyanAccent, fontWeight = FontWeight.Black, fontSize = 12.sp)
                Text(
                    trip.destinationCity,
                    color = textWhite,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Detalles de Carga y Rendimiento
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val tons = if (trip.cargoMassKg > 0f) String.format(" • %.1f t", trip.cargoMassKg / 1000f) else ""
                Text(
                    "${trip.cargoName}$tons",
                    color = textGray,
                    fontSize = 11.sp
                )

                Text(
                    String.format("%.1f km", trip.distanceKm),
                    color = cyanAccent,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}
