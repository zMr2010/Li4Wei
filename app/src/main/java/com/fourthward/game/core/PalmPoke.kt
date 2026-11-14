package com.fourthward.game.core

enum class PalmAction {
    RESET_ANGLE, TOGGLE_REFERENCE, CONTEXT, LEVEL_LIST, BACK,
    /** Visible row slots; the current group maps them to absolute level indices. */
    LEVEL_1, LEVEL_2, LEVEL_3, LEVEL_4, LEVEL_5, LEVEL_6,
    PREVIOUS_GROUP, NEXT_GROUP,
}

/** Actual left index tip: normalized panel coordinates, positive depth in front. */
data class PalmPointerPosition(val x: Double, val y: Double, val depthMeters: Double)

/** The UI and physical contact detector consume these same panel-local rectangles. */
data class NormalizedButtonRect(
    val action: PalmAction,
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
) {
    init {
        require(left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite())
        require(left >= 0.0 && top >= 0.0 && right <= 1.0 && bottom <= 1.0)
        require(right > left && bottom > top)
    }

    val width: Double get() = right - left
    val height: Double get() = bottom - top
    fun contains(x: Double, y: Double) = x.isFinite() && y.isFinite() && x in left..right && y in top..bottom
}

/** One layout drives both the rendered surface and physical poke coordinates. */
data class PalmPanelSpec(
    val widthDp: Int,
    val heightDp: Int,
    val widthMeters: Double,
    val buttons: List<NormalizedButtonRect>,
) {
    init {
        require(widthDp > 0 && heightDp > 0)
        require(widthMeters.isFinite() && widthMeters > 0.0)
        require(buttons.map { it.action }.distinct().size == buttons.size)
    }
    val heightMeters: Double get() = widthMeters * heightDp / widthDp
    fun hitTest(x: Double, y: Double): PalmAction? = buttons.firstOrNull { it.contains(x, y) }?.action
}

object PalmPanelLayout {
    const val WIDTH_DP = 400
    const val HEIGHT_DP = 220
    const val WIDTH_METERS = 0.18
    const val HEIGHT_METERS = WIDTH_METERS * HEIGHT_DP / WIDTH_DP

    val buttons: List<NormalizedButtonRect> = listOf(
        NormalizedButtonRect(PalmAction.RESET_ANGLE, 0.04, 0.20, 0.32, 0.86),
        NormalizedButtonRect(PalmAction.TOGGLE_REFERENCE, 0.36, 0.20, 0.64, 0.86),
        NormalizedButtonRect(PalmAction.CONTEXT, 0.68, 0.20, 0.96, 0.86),
    )

    val lesson = PalmPanelSpec(WIDTH_DP, HEIGHT_DP, WIDTH_METERS, buttons)
    val lessonMenu = PalmPanelSpec(400, 280, 0.18, listOf(
        NormalizedButtonRect(PalmAction.RESET_ANGLE, .04, .18, .32, .70),
        NormalizedButtonRect(PalmAction.TOGGLE_REFERENCE, .36, .18, .64, .70),
        NormalizedButtonRect(PalmAction.CONTEXT, .68, .18, .96, .70),
        NormalizedButtonRect(PalmAction.LEVEL_LIST, .04, .76, .96, .94),
    ))
    val game = PalmPanelSpec(400, 300, 0.18, listOf(
        NormalizedButtonRect(PalmAction.RESET_ANGLE, .04, .20, .48, .53),
        NormalizedButtonRect(PalmAction.TOGGLE_REFERENCE, .52, .20, .96, .53),
        NormalizedButtonRect(PalmAction.CONTEXT, .04, .57, .48, .90),
        NormalizedButtonRect(PalmAction.LEVEL_LIST, .52, .57, .96, .90),
    ))
    val levelActions = listOf(PalmAction.LEVEL_1, PalmAction.LEVEL_2, PalmAction.LEVEL_3,
        PalmAction.LEVEL_4, PalmAction.LEVEL_5, PalmAction.LEVEL_6)
    val chapters = PalmPanelSpec(600, 460, .30, listOf(
        NormalizedButtonRect(PalmAction.LEVEL_1, .28, .19, .72, .40),
        NormalizedButtonRect(PalmAction.LEVEL_2, .04, .57, .48, .78),
        NormalizedButtonRect(PalmAction.LEVEL_3, .52, .57, .96, .78),
        NormalizedButtonRect(PalmAction.BACK, .30, .85, .70, .95),
    ))
    private val chapterLayouts = (1..6).associateWith { count ->
        PalmPanelSpec(600, 460, .30, (0 until count).map { slot ->
            val column = if (count <= 3) 0 else slot % 2
            val row = if (count <= 3) slot else slot / 2
            val left = if (count <= 3) .04 else .04 + column * .48
            val right = if (count <= 3) .96 else left + .44
            val top = .20 + row * .21
            NormalizedButtonRect(levelActions[slot], left, top, right, top + .17)
        } + NormalizedButtonRect(PalmAction.BACK, .30, .85, .70, .95))
    }
    val levels: PalmPanelSpec = chapterLayouts.getValue(6)
    fun chapterLevels(count: Int): PalmPanelSpec = chapterLayouts.getValue(count)

    fun inPanel(x: Double, y: Double) = x.isFinite() && y.isFinite() && x in 0.0..1.0 && y in 0.0..1.0
    fun hitTest(x: Double, y: Double): PalmAction? = buttons.firstOrNull { it.contains(x, y) }?.action
}

data class PalmPokeResult(
    val hoveredAction: PalmAction? = null,
    val pressedAction: PalmAction? = null,
    /** Non-null for the single update in which an armed front-to-panel contact fires. */
    val action: PalmAction? = null,
)

/**
 * Consumes actual LEFT index-tip samples in the panel's coordinate system.
 * Positive depth is in front of the panel. Proximity/hover alone never fires.
 * A fresh sample above 3 cm arms a contact at <=8 mm, no deeper than 2 cm behind.
 * Contact anywhere (including the gap) latches until withdrawal; off-panel,
 * lost/invalid tracking, large time gaps, and discontinuous motion reset capture.
 */
class PalmPoke {
    private data class Sample(val x: Double, val y: Double, val depth: Double)
    private var previous: Sample? = null
    private var armed = false
    private var latched = false
    private var pressedTarget: PalmAction? = null
    private var activeLayout: PalmPanelSpec? = null
    private var disabledActions: Set<PalmAction> = emptySet()

    fun reset() {
        previous = null
        armed = false
        latched = false
        pressedTarget = null
        activeLayout = null
        disabledActions = emptySet()
    }

    fun update(
        normalizedX: Double,
        normalizedY: Double,
        signedDepthMeters: Double,
        valid: Boolean,
        dt: Double,
        layout: PalmPanelSpec = PalmPanelLayout.lesson,
        disabled: Set<PalmAction> = emptySet(),
    ): PalmPokeResult {
        // A held finger must never activate a newly displayed page or newly enabled target.
        if (activeLayout != layout || disabledActions != disabled) {
            reset()
            activeLayout = layout
            disabledActions = disabled.toSet()
        }
        if (!valid || !PalmPanelLayout.inPanel(normalizedX, normalizedY) || !signedDepthMeters.isFinite() ||
            !dt.isFinite() || dt <= 0.0 || dt > MAX_SAMPLE_GAP_SECONDS) return resetResult()

        val sample = Sample(normalizedX, normalizedY, signedDepthMeters)
        val last = previous
        if (last != null) {
            val dx = (sample.x - last.x) * layout.widthMeters
            val dy = (sample.y - last.y) * layout.heightMeters
            val dz = sample.depth - last.depth
            if (dx * dx + dy * dy + dz * dz > MAX_SAMPLE_MOTION_METERS * MAX_SAMPLE_MOTION_METERS) return resetResult()
        }
        previous = sample

        if (sample.depth > REARM_DEPTH_METERS) {
            armed = true
            latched = false
            pressedTarget = null
        }
        val hover = if (sample.depth in MIN_CONTACT_DEPTH_METERS..MAX_HOVER_DEPTH_METERS) {
            layout.hitTest(sample.x, sample.y)?.takeUnless { it in disabled }
        } else null
        var action: PalmAction? = null
        val crossesContact = last != null && last.depth > CONTACT_DEPTH_METERS && sample.depth <= CONTACT_DEPTH_METERS
        if (armed && !latched && last != null && crossesContact) {
            // A missed/too-deep contact also latches: sliding behind the plane must not activate a target.
            armed = false
            latched = true
            val fraction = (last.depth - CONTACT_DEPTH_METERS) / (last.depth - sample.depth)
            val crossingTarget = layout.hitTest(
                last.x + (sample.x - last.x) * fraction,
                last.y + (sample.y - last.y) * fraction,
            )
            if (sample.depth >= MIN_CONTACT_DEPTH_METERS && hover != null && crossingTarget == hover) {
                pressedTarget = hover
                action = hover
            }
        }
        val pressed = pressedTarget?.takeIf {
            latched && hover == it && sample.depth in MIN_CONTACT_DEPTH_METERS..CONTACT_DEPTH_METERS
        }
        return PalmPokeResult(hover, pressed, action)
    }

    private fun resetResult(): PalmPokeResult {
        reset()
        return PalmPokeResult()
    }

    companion object {
        const val REARM_DEPTH_METERS = 0.03
        const val CONTACT_DEPTH_METERS = 0.008
        const val MIN_CONTACT_DEPTH_METERS = -0.02
        const val MAX_HOVER_DEPTH_METERS = 0.08
        const val MAX_SAMPLE_GAP_SECONDS = 0.12
        const val MAX_SAMPLE_MOTION_METERS = 0.2
    }
}
