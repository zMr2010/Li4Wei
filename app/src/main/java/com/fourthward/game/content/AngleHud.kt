package com.fourthward.game.content

import com.pico.spatial.core.ecs.AnchorComponent
import com.pico.spatial.core.ecs.AnchorEntity
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.anchor.AnchorTarget
import com.pico.spatial.core.math.Vector3
import java.util.Locale
import kotlin.math.round

/** Read-only angle HUD continuously follows the native camera, independently of the hands. */
class AngleHud {
    val root = AnchorEntity(AnchorTarget.createCameraTarget()).apply {
        setName("AngleHudCameraAnchor")
        components[AnchorComponent::class.java]?.positionOffset = Vector3(0.50f, 0.52f, -1f)
    }
    private val overlay = HudBitmapLayer(root, "AngleHudOverlay", 300, 72, 0.24f)
    val overlayReady: Boolean get() = overlay.ready
    private var panel: Entity? = null
    private var baseWidthMeters = 0f

    fun attach(entity: Entity) {
        check(panel == null) { "Angle HUD already attached" }
        panel = entity
        entity.setName("AngleHudPanel")
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
                Vector3(0.24f / baseWidthMeters)
        }
    }

    fun update(angleDegrees: Double) {
        val finite = angleDegrees.takeIf { it.isFinite() } ?: 0.0
        val normalized = ((finite % 360.0) + 360.0) % 360.0
        val angle = (round(normalized * 10.0) / 10.0) % 360.0
        overlay.render(angle) { canvas ->
            HudInk.card(canvas, 300f, 72f, 12f, 184)
            HudInk.text(canvas, "θ", 14f, 29f, HudInk.paint(18f))
            val label = String.format(Locale.ROOT, "%.1f°", angle)
            val numberPaint = HudInk.paint(22f, mono = true)
            HudInk.text(canvas, label, 286f - numberPaint.measureText(label), 29f, numberPaint)
            HudInk.line(canvas, 14f, 45f, 286f, HudInk.faded(HudInk.ivory, 0.25f))
            if (angle > 0.0) HudInk.line(canvas, 14f, 45f, 14f + 272f * (angle / 360.0).toFloat(), HudInk.signal)
            val tickPaint = HudInk.paint(9f, HudInk.faded(HudInk.ivory, 0.8f))
            HudInk.text(canvas, "0°", 14f, 62f, tickPaint)
            val endLabel = "360° 周期"
            HudInk.text(canvas, endLabel, 286f - tickPaint.measureText(endLabel), 62f, tickPaint)
        }
    }

    fun close() { overlay.close(); root.destroy() }
}
