package com.ats.gps.ui.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun CircularGauge(
    value: Float,
    maxValue: Float,
    label: String,
    unit: String,
    gaugeSize: Dp = 190.dp,
    primaryColor: Color = Color(0xFF38BDF8),
    warningColor: Color = Color(0xFFEF4444),
    warningThreshold: Float = maxValue * 0.85f,
    modifier: Modifier = Modifier
) {
    val clampedValue = value.coerceIn(0f, maxValue)
    val fraction = clampedValue / maxValue

    // Arco de 240 grados: inicia en 150° y termina en 390° (30°)
    val startAngle = 150f
    val sweepAngle = 240f
    val currentSweep = sweepAngle * fraction
    val needleAngleDeg = startAngle + currentSweep

    Box(
        modifier = modifier.size(gaugeSize),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(gaugeSize)) {
            val strokeW = size.width * 0.065f
            val radius = (size.width - strokeW * 2f) / 2f
            val center = Offset(size.width / 2f, size.height / 2f)

            // 1. Arco base de fondo (pista apagada de alto contraste)
            drawArc(
                color = Color(0xFF1E293B),
                startAngle = startAngle,
                sweepAngle = sweepAngle,
                useCenter = false,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = Size(radius * 2f, radius * 2f),
                style = Stroke(width = strokeW, cap = StrokeCap.Round)
            )

            // 2. Arco activo con gradiente de color según rango
            val activeColor = if (clampedValue >= warningThreshold) warningColor else primaryColor
            drawArc(
                brush = Brush.sweepGradient(
                    0.0f to primaryColor,
                    0.7f to primaryColor,
                    0.9f to warningColor,
                    1.0f to warningColor,
                    center = center
                ),
                startAngle = startAngle,
                sweepAngle = currentSweep,
                useCenter = false,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = Size(radius * 2f, radius * 2f),
                style = Stroke(width = strokeW, cap = StrokeCap.Round)
            )

            // 3. Marcas de graduación radiales (Ticks calibrados cada 10%)
            val numTicks = 11
            for (i in 0 until numTicks) {
                val tickAngleDeg = startAngle + (sweepAngle * (i.toFloat() / (numTicks - 1)))
                val tickRad = Math.toRadians(tickAngleDeg.toDouble()).toFloat()
                val isMajor = i % 2 == 0

                val innerR = radius - strokeW * (if (isMajor) 1.2f else 0.8f)
                val outerR = radius - strokeW * 0.2f

                val p1 = Offset(center.x + innerR * cos(tickRad), center.y + innerR * sin(tickRad))
                val p2 = Offset(center.x + outerR * cos(tickRad), center.y + outerR * sin(tickRad))

                val tickColor = if (tickAngleDeg > startAngle + sweepAngle * 0.85f) warningColor else Color(0xFF64748B)
                drawLine(
                    color = tickColor,
                    start = p1,
                    end = p2,
                    strokeWidth = if (isMajor) 2.5f else 1.5f,
                    cap = StrokeCap.Round
                )
            }

            // 4. Aguja indicadora analógica de precisión
            val needleRad = Math.toRadians(needleAngleDeg.toDouble()).toFloat()
            val needleLen = radius * 0.78f
            val needleEnd = Offset(center.x + needleLen * cos(needleRad), center.y + needleLen * sin(needleRad))
            val needleTail = Offset(center.x - radius * 0.18f * cos(needleRad), center.y - radius * 0.18f * sin(needleRad))

            // Sombra de aguja
            drawLine(
                color = Color(0x66000000),
                start = needleTail + Offset(2f, 2f),
                end = needleEnd + Offset(2f, 2f),
                strokeWidth = 3.5f,
                cap = StrokeCap.Round
            )
            // Aguja principal
            drawLine(
                color = activeColor,
                start = needleTail,
                end = needleEnd,
                strokeWidth = 3.0f,
                cap = StrokeCap.Round
            )

            // Centro del buje (Pivote central de aguja)
            drawCircle(color = Color(0xFF0F172A), radius = radius * 0.16f, center = center)
            drawCircle(color = activeColor, radius = radius * 0.08f, center = center)
        }

        // 5. Lectura digital grande centrada con contraste >= 3:1
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.align(Alignment.Center)
        ) {
            Text(
                text = "${clampedValue.toInt()}",
                color = Color.White,
                fontSize = if (gaugeSize > 160.dp) 32.sp else 24.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = unit,
                color = Color(0xFF94A3B8),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = label,
                color = Color(0xFF64748B),
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
