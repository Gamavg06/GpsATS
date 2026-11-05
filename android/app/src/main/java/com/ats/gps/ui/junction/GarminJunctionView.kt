package com.ats.gps.ui.junction

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun GarminJunctionView(
    exitName: String,
    destinationName: String,
    distanceMeters: Float,
    speedLimitKmh: Float,
    modifier: Modifier = Modifier
) {
    // Animación de parpadeo suave para la flecha de salida a 60 FPS
    val infiniteTransition = rememberInfiniteTransition(label = "ArrowPulse")
    val arrowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ArrowPulseAlpha"
    )

    Box(
        modifier = modifier
            .width(280.dp)
            .height(170.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF0F172A))
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            // 1. Cielo crepuscular superior
            drawRect(
                color = Color(0xFF1E3A8A),
                topLeft = Offset.Zero,
                size = Size(w, h * 0.4f)
            )

            // 2. Puente superior / Estructura metálica del pórtico
            drawRect(
                color = Color(0xFF64748B),
                topLeft = Offset(0f, h * 0.35f),
                size = Size(w, 8f)
            )

            // 3. Autopista de 3 carriles en perspectiva
            val roadPath = Path().apply {
                moveTo(w * 0.15f, h * 0.36f)
                lineTo(w * 0.85f, h * 0.36f)
                lineTo(w, h)
                lineTo(0f, h)
                close()
            }
            drawPath(roadPath, Color(0xFF334155))

            // Líneas divisorias de carril discontinuas
            drawLine(Color.White, Offset(w * 0.38f, h * 0.36f), Offset(w * 0.33f, h), strokeWidth = 3f)
            drawLine(Color.White, Offset(w * 0.62f, h * 0.36f), Offset(w * 0.67f, h), strokeWidth = 3f)

            // 4. Flecha iluminada de carril recomendada (salida derecha en magenta/cian)
            val arrowPath = Path().apply {
                moveTo(w * 0.82f, h * 0.55f)
                lineTo(w * 0.90f, h * 0.75f)
                lineTo(w * 0.74f, h * 0.75f)
                close()
            }
            drawPath(arrowPath, Color(0xFFFF0055).copy(alpha = arrowAlpha))
            drawPath(arrowPath, Color.White, style = Stroke(width = 2f))
        }

        // 5. Señal de tráfico superior tipo Garmin (Verde con letras blancas)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFF15803D))
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            Text(
                text = "SALIDA: $exitName",
                fontSize = 11.sp,
                fontWeight = FontWeight.Black,
                color = Color(0xFFFEF08A)
            )
            Text(
                text = destinationName.uppercase(),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }

        // 6. Indicador inferior de distancia a la salida
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(8.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xCC000000))
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text(
                text = if (distanceMeters >= 1000f) String.format("%.1f km", distanceMeters / 1000f) else "${distanceMeters.toInt()} m",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF38BDF8)
            )
        }
    }
}
