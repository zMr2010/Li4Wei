package com.fourthward.game.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fourthward.game.core.PalmAction
import com.fourthward.game.core.PalmPanelLayout
import com.fourthward.game.core.PalmPointerPosition
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.foundation.layout.zOffset
import com.pico.spatial.ui.platform.Material
import java.util.Locale
import kotlin.math.abs

/** Feedback is supplied by the actual left-index contact detector, never simulated here. */
data class PanelState(
    val referenceVisible: Boolean = true,
    val hoveredAction: PalmAction? = null,
    val pressedAction: PalmAction? = null,
    val pointer: PalmPointerPosition? = null,
    val debugMode: Boolean = false,
    val contextLabel: String = "重试",
)

const val PALM_PANEL_SIZE_DP = PalmPanelLayout.WIDTH_DP
const val PALM_PANEL_HEIGHT_DP = PalmPanelLayout.HEIGHT_DP

private object PalmStyle {
    val Ink = Color(0xFF191919)
    val Ivory = Color(0xFFF2F2F0)
    val Signal = Color(0xFFFFFA00)
}

/**
 * Two auxiliary actions use the same normalized rectangles as physical plane contact.
 * The host consumes physical pointer events and delivers detected left-index actions;
 * onAction remains available for semantic/accessibility invocation through the host guard.
 */
@Composable
fun FourthwardPanel(state: PanelState, onAction: (PalmAction) -> Unit) {
    val shape = RoundedCornerShape(28.dp)
    Box(
        Modifier.size(PALM_PANEL_SIZE_DP.dp, PALM_PANEL_HEIGHT_DP.dp)
            .clip(shape)
            .backgroundMaterial(style = Material.Thin)
            .background(PalmStyle.Ink.copy(alpha = 0.24f))
            .border(1.dp, PalmStyle.Ivory.copy(alpha = 0.35f), shape),
    ) {
        PalmPanelLayout.buttons.forEach { button ->
            val active = button.action == PalmAction.TOGGLE_REFERENCE && state.referenceVisible
            val hovered = state.hoveredAction == button.action
            val pressed = state.pressedAction == button.action
            var focused by remember(button.action) { mutableStateOf(false) }
            val emphasized = active || hovered || pressed || focused
            // Genuine spatial layer translation; fixed shared x/y rectangles and contact plane.
            // At 18cm panel width, 4dp is only1.8mm and stays within the8mm contact tolerance.
            val lift by animateDpAsState(
                targetValue = when { pressed -> (-2).dp; hovered || focused -> 4.dp; else -> 0.dp },
                animationSpec = tween(120),
                label = "PalmButtonDepth",
            )
            Box(
                Modifier
                    .offset((button.left * PALM_PANEL_SIZE_DP).toFloat().dp, (button.top * PALM_PANEL_HEIGHT_DP).toFloat().dp)
                    .size((button.width * PALM_PANEL_SIZE_DP).toFloat().dp, (button.height * PALM_PANEL_HEIGHT_DP).toFloat().dp)
                    .zOffset { lift.toPx() }
                    .background(if (pressed) PalmStyle.Signal else PalmStyle.Ink.copy(alpha = if (hovered) 0.9f else 0.76f))
                    .border(if (emphasized) 2.dp else 1.dp,
                        if (emphasized) PalmStyle.Signal else PalmStyle.Ivory.copy(alpha = 0.55f))
                    .semantics {
                        if (button.action == PalmAction.TOGGLE_REFERENCE) {
                            stateDescription = if (state.referenceVisible) "已显示" else "已隐藏"
                        }
                    }
                    .onFocusChanged { focused = it.isFocused }
                    .clickable(role = Role.Button) { onAction(button.action) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    when (button.action) {
                        PalmAction.RESET_ANGLE -> "θ归零"
                        PalmAction.TOGGLE_REFERENCE -> "参考平面"
                        PalmAction.CONTEXT -> state.contextLabel
                        PalmAction.LEVEL_LIST -> "关卡列表"
                        PalmAction.BACK -> "返回"
                        PalmAction.LEVEL_1 -> "第一关"
                        PalmAction.LEVEL_2 -> "第二关"
                        PalmAction.LEVEL_3 -> "第三关"
                        PalmAction.LEVEL_4 -> "第四关"
                        PalmAction.LEVEL_5 -> "第五关"
                        PalmAction.LEVEL_6 -> "第六关"
                        PalmAction.PREVIOUS_GROUP -> "上一组"
                        PalmAction.NEXT_GROUP -> "下一组"
                    },
                    color = if (pressed) PalmStyle.Ink else PalmStyle.Ivory,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
            }
        }
        if (state.debugMode) {
            Box(Modifier.offset(20.dp, 2.dp).size(360.dp, 34.dp), contentAlignment = Alignment.Center) {
                Text("Tab 选择 · Enter 点击 · M 收起", color = PalmStyle.Ivory, fontSize = 20.sp)
            }
        } else FingerDepthFeedback(state.pointer)
    }
}

/** Visual position/depth only: proximity does not imply the contact detector fired. */
@Composable
private fun FingerDepthFeedback(pointer: PalmPointerPosition?) {
    val valid = pointer?.takeIf { it.x.isFinite() && it.y.isFinite() && it.depthMeters.isFinite() }
    val marker = valid?.takeIf { PalmPanelLayout.inPanel(it.x, it.y) && abs(it.depthMeters) <= 0.35 }
    val distance = valid?.let { abs(it.depthMeters) } ?: 0.0
    val close = marker != null && distance <= 0.008
    val color = if (close) PalmStyle.Signal else PalmStyle.Ivory
    val centimeters = if (distance > 9.99) ">999" else String.format(Locale.ROOT, "%.1f", distance * 100.0)
    val guidance = when {
        valid == null -> "左手食指靠近面板"
        !PalmPanelLayout.inPanel(valid.x, valid.y) -> "左指未对准"
        distance > 0.35 -> "左指距面板 $centimeters cm"
        else -> "左指 · ${if (valid.depthMeters >= 0.0) "前" else "后"} $centimeters cm"
    }
    // Keep read-only guidance visible over the raised button faces; it owns no hit target.
    if (marker != null) Canvas(Modifier.fillMaxSize().zOffset { 8.dp.toPx() }) {
        val radius = (7f + 11f * (distance / 0.08).coerceIn(0.0, 1.0).toFloat()).dp.toPx()
        val margin = radius + 3.dp.toPx()
        val center = Offset(
            (marker.x * size.width).toFloat().coerceIn(margin, size.width - margin),
            (marker.y * size.height).toFloat().coerceIn(margin, size.height - margin),
        )
        drawCircle(PalmStyle.Ink.copy(alpha = 0.85f), radius + 2.dp.toPx(), center, style = Stroke(5.dp.toPx()))
        drawCircle(color, radius, center, style = Stroke(2.dp.toPx()))
        drawCircle(color, if (close) 5.dp.toPx() else 2.5.dp.toPx(), center)
    }
    Box(
        Modifier.offset(40.dp, 2.dp).size((PALM_PANEL_SIZE_DP - 80).dp, 34.dp)
            .background(PalmStyle.Ink.copy(alpha = 0.88f), RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            guidance,
            color = color,
            fontSize = 24.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}
