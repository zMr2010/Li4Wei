package com.fourthward.game.content

import com.pico.spatial.core.ecs.AnchorComponent
import com.pico.spatial.core.ecs.AnchorEntity
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.anchor.AnchorTarget
import com.pico.spatial.core.math.Vector3
import com.fourthward.game.ui.TutorialStatus
import java.util.Locale
import kotlin.math.round

/** Compact read-only tutorial card below the scene centre, independent of palm tracking. */
class TutorialHud {
    val root = AnchorEntity(AnchorTarget.createCameraTarget()).apply {
        setName("TutorialHudCameraAnchor")
        components[AnchorComponent::class.java]?.positionOffset = Vector3(0f, -0.43f, -1.2f)
    }
    private val overlay = HudBitmapLayer(root, "TutorialHudOverlay", 640, 184, 0.58f)
    val overlayReady: Boolean get() = overlay.ready
    private var panel: Entity? = null
    private var baseWidthMeters = 0f

    fun attach(entity: Entity) {
        check(panel == null) { "Tutorial HUD already attached" }
        panel = entity
        entity.setName("TutorialHudPanel")
        entity.enabled = true
        root.addChild(entity)
        applySize()
    }

    fun setBaseWidth(width: Float) {
        if (width.isFinite() && width > 0f) {
            baseWidthMeters = width
            applySize()
        }
    }

    private fun applySize() {
        if (baseWidthMeters > 0f) {
            panel?.components?.get(TransformComponent::class.java)?.scaleVector =
                Vector3(0.58f / baseWidthMeters)
        }
    }

    fun update(status: TutorialStatus) {
        val visible = status.copy(distanceMeters = status.distanceMeters
            ?.takeIf { it.isFinite() && it >= 0.0 }?.let { round(it * 10.0) / 10.0 })
        overlay.render(visible) { canvas ->
            val signal = if (visible.completed) HudInk.success else HudInk.signal
            val count = visible.totalLevels.coerceAtLeast(1)
            val index = visible.levelIndex.coerceIn(0, count - 1)
            val progress = (index + if (visible.completed) 1f else 0f) / count
            val label = when {
                visible.completed -> "已完成"
                visible.blocked -> "前方阻挡"
                visible.distanceMeters != null -> String.format(Locale.ROOT, "距目标 %.1f m", visible.distanceMeters)
                else -> "观察 · 探索"
            }
            HudInk.card(canvas, 640f, 184f, 10f, 199)
            val countText = "${index + 1} / $count"
            val countPaint = HudInk.paint(23f, signal, bold = true)
            HudInk.text(canvas, countText, 18f, 34f, countPaint)
            val labelPaint = HudInk.paint(17f, signal)
            val labelX = 622f - labelPaint.measureText(label)
            HudInk.text(canvas, label, labelX, 32f, labelPaint)
            val titleX = 30f + countPaint.measureText(countText)
            HudInk.text(canvas, visible.title, titleX, 34f, HudInk.paint(25f, bold = true), labelX - titleX - 12f)
            HudInk.line(canvas, 18f, 45f, 622f, HudInk.faded(HudInk.ivory, 0.2f))
            if (progress > 0f) HudInk.line(canvas, 18f, 45f, 18f + 604f * progress, signal)
            HudInk.lines(canvas, visible.instruction, 18f, 53f, 604, HudInk.paint(23f), 2)
            HudInk.text(canvas, visible.hint, 18f, 131f, HudInk.paint(18f, HudInk.faded(HudInk.ivory, 0.78f)), 604f)
            val shortcut = if (visible.keyboardMode) "WASD 移动 · Q/E 旋转 · M 面板 · T 重试 · N 下一关"
                else "张手背向自己横移：旋转 · 右掌朝自己：辅助面板"
            HudInk.text(canvas, shortcut, 18f, 165f, HudInk.paint(16f, HudInk.faded(HudInk.ivory, 0.7f)), 604f)
        }
    }

    fun close() { overlay.close(); root.destroy() }
}
