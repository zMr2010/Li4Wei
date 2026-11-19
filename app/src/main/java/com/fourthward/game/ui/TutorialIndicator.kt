package com.fourthward.game.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.platform.Material
import java.util.Locale

const val TUTORIAL_INDICATOR_WIDTH_DP = 640
const val TUTORIAL_INDICATOR_HEIGHT_DP = 184

/** All progress and instructions come from the game; no local tutorial progression. */
data class TutorialStatus(
    val levelIndex: Int,
    val totalLevels: Int,
    val title: String,
    val instruction: String,
    val hint: String,
    val completed: Boolean,
    val distanceMeters: Double? = null,
    val blocked: Boolean = false,
    val keyboardMode: Boolean = false,
)

/** ak-ui accent interpretation: clear hierarchy, neutral glass, purposeful action signals. */
@Composable
fun TutorialIndicator(status: TutorialStatus) {
    val ink = Color(0xFF191919)
    val ivory = Color(0xFFF2F2F0)
    val signal = if (status.completed) Color(0xFF9AE3B1) else Color(0xFFFFFA00)
    val shape = RoundedCornerShape(10.dp)
    val count = status.totalLevels.coerceAtLeast(1)
    val index = status.levelIndex.coerceIn(0, count - 1)
    val progress = (index + if (status.completed) 1f else 0f) / count
    val distance = status.distanceMeters?.takeIf { it.isFinite() && it >= 0.0 }
    val label = when {
        status.completed -> "已完成"
        status.blocked -> "前方阻挡"
        distance != null -> String.format(Locale.ROOT, "距目标 %.1f m", distance)
        else -> "观察 · 探索"
    }
    Column(
        Modifier.size(TUTORIAL_INDICATOR_WIDTH_DP.dp, TUTORIAL_INDICATOR_HEIGHT_DP.dp)
            .clip(shape)
            .backgroundMaterial(style = Material.Thin)
            .background(ink.copy(alpha = 0.78f))
            .border(1.dp, ivory.copy(alpha = 0.25f), shape)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("${index + 1} / $count", color = signal, fontSize = 23.sp, fontWeight = FontWeight.Bold)
            Text(status.title, modifier = Modifier.weight(1f), color = ivory,
                fontSize = 25.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(label, color = signal, fontSize = 17.sp, maxLines = 1)
        }
        Canvas(Modifier.fillMaxWidth().height(3.dp)) {
            drawLine(ivory.copy(alpha = 0.2f), Offset.Zero, Offset(size.width, 0f), 2.dp.toPx())
            if (progress > 0f) drawLine(signal, Offset.Zero, Offset(size.width * progress, 0f), 2.dp.toPx())
        }
        Text(status.instruction, color = ivory, fontSize = 23.sp,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (status.hint.isNotBlank()) {
            Text(status.hint, color = ivory.copy(alpha = 0.78f), fontSize = 18.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(
            if (status.keyboardMode) "WASD 移动 · Q/E 旋转 · M 面板 · T 重试 · N 下一关"
            else "右掌向前横移旋转 · 掌心朝自己或朝上 3 秒开面板",
            color = ivory.copy(alpha = 0.7f), fontSize = 16.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}
