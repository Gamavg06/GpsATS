package com.ats.gps.ui.about

import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private const val PROJECT_GITHUB_URL = "https://github.com/Gamavg06/GpsATS"

private const val MIT_PERMISSION_TEXT =
    "Permission is hereby granted, free of charge, to any person obtaining a copy " +
    "of this software and associated documentation files (the \"Software\"), to deal " +
    "in the Software without restriction, including without limitation the rights " +
    "to use, copy, modify, merge, publish, distribute, sublicense, and/or sell " +
    "copies of the Software, and to permit persons to whom the Software is " +
    "furnished to do so, subject to the following conditions:\n\n" +
    "The above copyright notice and this permission notice shall be included in all " +
    "copies or substantial portions of the Software.\n\n" +
    "THE SOFTWARE IS PROVIDED \"AS IS\", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR " +
    "IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, " +
    "FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE " +
    "AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER " +
    "LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, " +
    "OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE " +
    "SOFTWARE."

private val RENCLOUD_MIT_NOTICE =
    "Copyright (c) 2018-2024 RenCloud / scs-sdk-plugin contributors\n\n$MIT_PERMISSION_TEXT"

private val SCS_SDK_MIT_NOTICE =
    "SCS SDK\nCopyright (C) 2016 SCS Software\n\n$MIT_PERMISSION_TEXT"

private val APP_MIT_NOTICE =
    "Copyright (c) 2026 Gamaliel Velazquez Galicia (SGNIA)\n\n$MIT_PERMISSION_TEXT"

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
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                ProjectLicenseCard(
                    modifier = Modifier
                        .weight(0.35f)
                        .fillMaxHeight()
                )

                ThirdPartyNoticesCard(
                    modifier = Modifier
                        .weight(0.65f)
                        .fillMaxHeight()
                )
            }
        } else {
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
    val uriHandler = LocalUriHandler.current
    val baseModifier = modifier
        .clip(RoundedCornerShape(18.dp))
        .background(Color(0xFF151C21))
        .border(1.dp, Color(0xFF1E262C), RoundedCornerShape(18.dp))
        .padding(22.dp)
    val cardModifier = if (scrollable) {
        baseModifier.verticalScroll(rememberScrollState())
    } else {
        baseModifier
    }

    Column(
        modifier = cardModifier,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = "PRODUCT AND PROJECT LICENSES",
            color = Color(0xFFC7F36B),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )

        val context = LocalContext.current
        val versionInfo = remember(context) {
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
                val name = pkgInfo.versionName ?: "1.4.2"
                val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    pkgInfo.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    pkgInfo.versionCode.toLong()
                }
                name to code
            }.getOrDefault("1.4.2" to 17L)
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "Telemetria GPS",
                color = Color(0xFFF3F6F7),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Version ${versionInfo.first} (Build ${versionInfo.second}) - Produccion",
                color = Color(0xFF94A3B8),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
        }

        Spacer(modifier = Modifier.height(1.dp).fillMaxWidth().background(Color(0xFF1E262C)))

        Text(
            text = "Sistema de navegacion GPS de alta precision y telemetria de cabina en tiempo real para American Truck Simulator y Euro Truck Simulator 2.",
            color = Color(0xFFCBD5E1),
            fontSize = 13.sp,
            lineHeight = 19.sp
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF0C1013))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "Licencia MIT (Cliente Android y Lanzador)",
                color = Color(0xFFC7F36B),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = APP_MIT_NOTICE,
                color = Color(0xFF94A3B8),
                fontSize = 11.sp,
                lineHeight = 16.sp
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "Codigo Fuente Oficial",
                color = Color(0xFFF3F6F7),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = PROJECT_GITHUB_URL,
                color = Color(0xFF38BDF8),
                fontSize = 12.sp,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier.clickable {
                    uriHandler.openUri(PROJECT_GITHUB_URL)
                }
            )
        }
    }
}

@Composable
private fun ThirdPartyNoticesCard(
    modifier: Modifier = Modifier,
    scrollable: Boolean = true
) {
    val baseModifier = modifier
        .clip(RoundedCornerShape(18.dp))
        .background(Color(0xFF151C21))
        .border(1.dp, Color(0xFF1E262C), RoundedCornerShape(18.dp))
        .padding(22.dp)
    val cardModifier = if (scrollable) {
        baseModifier.verticalScroll(rememberScrollState())
    } else {
        baseModifier
    }

    Column(
        modifier = cardModifier,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
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
            text = "Este proyecto utiliza bibliotecas de codigo abierto y herramientas de la comunidad simuladora bajo sus respectivas licencias:",
            color = Color(0xFF94A3B8),
            fontSize = 12.sp
        )

        NoticeItem(
            title = "RenCloud scs-sdk-plugin (scs-telemetry.dll)",
            license = "Licencia MIT",
            description = "Copyright (c) 2018-2024 RenCloud / scs-sdk-plugin contributors. Plugin nativo instalado en el simulador para publicar telemetria en memoria compartida.",
            fullLicenseText = RENCLOUD_MIT_NOTICE,
            initiallyExpanded = true
        )

        NoticeItem(
            title = "SCS Software Telemetry SDK",
            license = "Licencia MIT",
            description = "Copyright (C) 2016 SCS Software. SDK y encabezados oficiales de referencia de memoria compartida para American Truck Simulator y ETS2.",
            fullLicenseText = SCS_SDK_MIT_NOTICE
        )

        NoticeItem(
            title = "TruckLib (sk-zk)",
            license = "GNU GPL-2.0 (solo servidor PC)",
            description = "Biblioteca de lectura de archivos .scs y extraccion cartografica utilizada exclusivamente en el servidor PC (AtsGpsServer). No se incluye en la app Android. Codigo fuente completo disponible en GitHub.",
            linkLabel = "Ver codigo fuente del proyecto en GitHub ($PROJECT_GITHUB_URL)",
            linkUrl = PROJECT_GITHUB_URL
        )

        NoticeItem(
            title = "RBush / Sprache / GisDeflate / System.IO.Hashing",
            license = "Licencia MIT (servidor PC)",
            description = "Bibliotecas auxiliares de indice espacial, analisis sintactico, descompresion y hash utilizadas por el servidor PC AtsGpsServer y TruckLib bajo licencia MIT."
        )

        NoticeItem(
            title = "AndroidX & Jetpack Compose (Google / AOSP)",
            license = "Apache 2.0",
            description = "Copyright (C) The Android Open Source Project. Kit de interfaz declarativa, componentes Material 3 y ciclo de vida para Android."
        )

        NoticeItem(
            title = "OkHttp & Okio (Square, Inc.)",
            license = "Apache 2.0",
            description = "Copyright 2013-2019 Square, Inc. Cliente HTTP/HTTPS, WebSocket y manejo de flujos I/O para telemetria en tiempo real."
        )

        NoticeItem(
            title = "Kotlin & Coroutines (JetBrains)",
            license = "Apache 2.0",
            description = "Copyright 2010-2024 JetBrains s.r.o. Biblioteca estandar de Kotlin y concurrencia asincrona estructurada."
        )

        NoticeItem(
            title = "Android SDK Platform-Tools (adb)",
            license = "Apache 2.0 (opcional)",
            description = "Copyright (C) Google LLC. Componente externo opcional en PC para tunel USB mediante adb reverse sin red Wi-Fi."
        )
    }
}

@Composable
private fun NoticeItem(
    title: String,
    license: String,
    description: String,
    fullLicenseText: String? = null,
    initiallyExpanded: Boolean = false,
    linkLabel: String? = null,
    linkUrl: String? = null
) {
    val uriHandler = LocalUriHandler.current
    var expanded by remember { mutableStateOf(initiallyExpanded) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF0C1013))
            .border(1.dp, Color(0xFF1E262C), RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
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
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .padding(end = 8.dp)
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

        if (!linkUrl.isNullOrBlank() && !linkLabel.isNullOrBlank()) {
            Text(
                text = linkLabel,
                color = Color(0xFF38BDF8),
                fontSize = 11.sp,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier.clickable {
                    uriHandler.openUri(linkUrl)
                }
            )
        }

        if (!fullLicenseText.isNullOrBlank()) {
            Text(
                text = if (expanded) "Ocultar texto completo de licencia" else "Ver texto completo de licencia",
                color = Color(0xFF38BDF8),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable { expanded = !expanded }
            )
            AnimatedVisibility(visible = expanded) {
                Text(
                    text = fullLicenseText,
                    color = Color(0xFF94A3B8),
                    fontSize = 10.sp,
                    lineHeight = 15.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF151C21))
                        .padding(10.dp)
                )
            }
        }
    }
}
