package com.fourthward.game.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.platform.Material
import java.util.Locale

const val ANGLE_INDICATOR_WIDTH_DP = 300
const val ANGLE_INDICATOR_HEIGHT_DP = 72

/**
 * Read-only view indicator of the game's normalized theta in [0,360).
 * Both the number and thin progress line repeat every turn. The caller owns its pose.
 */
@Composable
fun AngleIndicator(angleDegrees: Double) {
    val finiteAngle = if (angleDegrees.isFinite()) angleDegrees else 0.0
    val angle = ((finiteAngle % 360.0) + 360.0) % 360.0
    // Rounding near the seam must show 0.0 rather than an out-of-range 360.0.
    val formatted = String.format(Locale.ROOT, "%.1f°", if (angle >= 359.95) 0.0 else angle)
    val fraction = (angle / 360.0).toFloat()
    val ink = Color(0xFF191919)
    val ivory = Color(0xFFF2F2F0)
    val signal = Color(0xFFFFFA00)
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier.size(ANGLE_INDICATOR_WIDTH_DP.dp, ANGLE_INDICATOR_HEIGHT_DP.dp)
            .clip(shape)
            .backgroundMaterial(style = Material.Thin)
            .background(ink.copy(alpha = 0.72f))
            .border(1.dp, ivory.copy(alpha = 0.25f), shape)
            .semantics {
                contentDescription = "旋转角度 θ $formatted"
            }
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("θ", color = ivory, fontSize = 18.sp)
            Text(formatted, color = ivory, fontSize = 22.sp,
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, maxLines = 1)
        }
        Spacer(Modifier.height(4.dp))
        Canvas(Modifier.fillMaxWidth().height(4.dp)) {
            val thickness = 2.dp.toPx()
            val start = Offset(thickness / 2f, size.height / 2f)
            val end = Offset(size.width - thickness / 2f, start.y)
            drawLine(ivory.copy(alpha = 0.25f), start, end, thickness, cap = StrokeCap.Round)
            if (fraction > 0f) {
                drawLine(signal, start, Offset(start.x + (end.x - start.x) * fraction, start.y),
                    thickness, cap = StrokeCap.Round)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("0°", color = ivory.copy(alpha = 0.8f), fontSize = 9.sp)
            Text("360° 周期", color = ivory.copy(alpha = 0.8f), fontSize = 9.sp)
        }
    }
}
