package com.ats.gps.ui.about

import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun AboutScreen(
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0C1013))
            .padding(top = 92.dp, bottom = 48.dp, start = 20.dp, end = 20.dp)
    ) {
        val isLandscape = maxWidth > 800.dp

        if (isLandscape) {
            // Diseño en dos columnas para pantallas apaisadas (1280x800)
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Columna Izquierda: Licencia del Producto y Proyecto (356px)
                ProjectLicenseCard(
                    modifier = Modifier
                        .weight(0.35f)
                        .fillMaxHeight()
                )

                // Columna Derecha: Avisos de Terceros y Componentes (860px)
                ThirdPartyNoticesCard(
                    modifier = Modifier
                        .weight(0.65f)
                        .fillMaxHeight()
                )
            }
        } else {
            // Diseño vertical adaptable para teléfonos o pantallas estrechas (scroll único en contenedor principal)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                ProjectLicenseCard(
                    modifier = Modifier.fillMaxWidth(),
                    scrollable = false
                )
                ThirdPartyNoticesCard(
                    modifier = Modifier.fillMaxWidth(),
                    scrollable = false
                )
            }
        }
    }
}

@Composable
private fun ProjectLicenseCard(
    modifier: Modifier = Modifier,
    scrollable: Boolean = true
) {
    val cardModifier = if (scrollable) {
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF151C21))
            .border(1.dp, Color(0xFF1E262C), RoundedCornerShape(18.dp))
            .padding(22.dp)
            .verticalScroll(rememberScrollState())
    } else {
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF151C21))
            .border(1.dp, Color(0xFF1E262C), RoundedCornerShape(18.dp))
            .padding(22.dp)
    }

    Column(
        modifier = cardModifier,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Tag superior
        Text(
            text = "PRODUCT AND PROJECT LICENSES",
            color = Color(0xFFC7F36B),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )

        // Nombre de la App y Versión
        val context = LocalContext.current
        val appVersion = remember(context) {
            runCatching {
                val pkgInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.packageManager.getPackageInfo(
                        context.packageName,
                        PackageManager.PackageInfoFlags.of(0)
                    )
                } else {
                    @Suppress("DEPRECATION")
                    context.packageManager.getPackageInfo(context.packageName, 0)
                }
                pkgInfo.versionName ?: "1.4.2"
            }.getOrDefault("1.4.2")
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "Telemetría GPS",
                color = Color(0xFFF3F6F7),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Versión $appVersion • Producción",
                color = Color(0xFF94A3B8),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
        }

        Spacer(modifier = Modifier.height(1.dp).fillMaxWidth().background(Color(0xFF1E262C)))

        // Resumen del Proyecto
        Text(
            text = "Sistema de navegación GPS de alta precisión y telemetría de cabina en tiempo real para American Truck Simulator y Euro Truck Simulator 2.",
            color = Color(0xFFCBD5E1),
            fontSize = 13.sp,
            lineHeight = 19.sp
        )

        // Licencia MIT
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF0C1013))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "Licencia MIT",
                color = Color(0xFFC7F36B),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Copyright (c) 2026 Gamaliel Velazquez Galicia (SGNIA). Todos los derechos reservados sobre el codigo fuente propio.\n\nSe concede permiso para utilizar, copiar, modificar y distribuir este software conforme a los términos de la licencia MIT estándar de código abierto.",
                color = Color(0xFF94A3B8),
                fontSize = 11.sp,
                lineHeight = 16.sp
            )
        }

        // Repositorio y Créditos
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "Repositorio Oficial",
                color = Color(0xFFF3F6F7),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "github.com/Gamavg06/GpsATS",
                color = Color(0xFF38BDF8),
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun ThirdPartyNoticesCard(
    modifier: Modifier = Modifier,
    scrollable: Boolean = true
) {
    val cardModifier = if (scrollable) {
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF151C21))
            .border(1.dp, Color(0xFF1E262C), RoundedCornerShape(18.dp))
            .padding(22.dp)
            .verticalScroll(rememberScrollState())
    } else {
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF151C21))
            .border(1.dp, Color(0xFF1E262C), RoundedCornerShape(18.dp))
            .padding(22.dp)
    }

    Column(
        modifier = cardModifier,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Tag superior
        Text(
            text = "THIRD-PARTY NOTICES & COMPONENT LICENSES",
            color = Color(0xFF94A3B8),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )

        Text(
            text = "Avisos de Software de Terceros",
            color = Color(0xFFF3F6F7),
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )

        Text(
            text = "Este proyecto utiliza librerías de código abierto y herramientas de la comunidad simuladora bajo sus respectivas licencias:",
            color = Color(0xFF94A3B8),
            fontSize = 12.sp
        )

        // Item 1: SCS SDK Plugin
        NoticeItem(
            title = "SCS SDK Plugin (RenCloud)",
            license = "Licencia MIT",
            description = "Plugin de telemetría nativo para extracción de memoria compartida en Euro Truck Simulator 2 y American Truck Simulator."
        )

        // Item 2: TruckLib & Map Binary Extractor
        NoticeItem(
            title = "TruckLib & Binary Map Extractor (sk-zk)",
            license = "Licencia GNU GPL-2.0 (solo servidor PC)",
            description = "Estructuras de datos y algoritmos para extracción del grafo vial, nodos, conexiones y sectores de mapa de SCS Software."
        )

        // Item 3: Jetpack Compose & AndroidX
        NoticeItem(
            title = "Jetpack Compose & AndroidX (Google)",
            license = "Licencia Apache 2.0",
            description = "Kit de herramientas moderno para el desarrollo de interfaces de usuario reactivas en Android con renderizado de hardware a 60-120 FPS."
        )

        // Item 4: OkHttp & WebSockets (Square)
        NoticeItem(
            title = "OkHttp & Okio (Square, Inc.)",
            license = "Licencia Apache 2.0",
            description = "Cliente HTTP y WebSocket de alto rendimiento para transmisión continua de paquetes de telemetría con latencia inferior a 10 ms."
        )

        // Item 5: Tipografía Inter
        NoticeItem(
            title = "Inter Font Family (Rasmus Andersson)",
            license = "SIL Open Font License 1.1",
            description = "Tipografía optimizada para pantallas digitales de alta densidad y legibilidad automotriz en interfaces de cabina."
        )
    }
}

@Composable
private fun NoticeItem(
    title: String,
    license: String,
    description: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF0C1013))
            .border(1.dp, Color(0xFF1E262C), RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                color = Color(0xFFF3F6F7),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = license,
                color = Color(0xFFC7F36B),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
        }
        Text(
            text = description,
            color = Color(0xFF94A3B8),
            fontSize = 12.sp,
            lineHeight = 17.sp
        )
    }
}