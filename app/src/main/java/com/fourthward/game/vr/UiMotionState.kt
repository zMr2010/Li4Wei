package com.fourthward.game.vr

import kotlin.math.abs
import kotlin.math.exp

/** Motion tokens are seconds/metres, independent of texture resolution and headset refresh rate. */
object UiMotionTokens {
    const val OPEN_SECONDS = .30
    const val CLOSE_SECONDS = .24
    const val PAGE_OUT_SECONDS = .12
    const val PAGE_IN_SECONDS = .28
    const val CARD_STAGGER_SECONDS = .045
    const val HOVER_METERS = .004
    const val PRESS_METERS = -.0007
    fun smooth(value: Double): Double {
        val t = value.coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }
    fun out(value: Double): Double {
        val t = 1.0 - value.coerceIn(0.0, 1.0)
        return 1.0 - t * t * t
    }
}

/** Interruptible interpolation: retargeting always begins at the currently displayed value. */
class UiMotionTween(initial: Double = 0.0) {
    private var from = initial
    private var target = initial
    private var start = 0.0
    private var duration = .001
    fun value(time: Double): Double = from + (target - from) * UiMotionTokens.smooth((time - start) / duration)
    fun target(next: Double, time: Double, seconds: Double) {
        if (next == target) return
        from = value(time); target = next; start = time; duration = seconds.coerceAtLeast(.001)
    }
    fun snap(next: Double, time: Double) { from = next; target = next; start = time }
}

data class UiMotionSample(
    val pageKey: String,
    val clock: Double,
    val visibility: Double,
    val pageOpacity: Double,
    val entryAge: Double,
    val exitingPage: Boolean,
) {
    val panelOpacity: Double get() = visibility * pageOpacity
    val panelOffsetY: Double get() = -.008 * (1.0 - visibility)
    val panelOffsetNormal: Double get() = -.003 * (1.0 - visibility)
    val panelScale: Double get() = .975 + .025 * visibility
    val branchProgress: Double get() = UiMotionTokens.out((entryAge - .14) / .62)
    fun cardOpacity(index: Int): Double = panelOpacity *
        UiMotionTokens.smooth((entryAge - index * UiMotionTokens.CARD_STAGGER_SECONDS) / UiMotionTokens.PAGE_IN_SECONDS)
    private fun cardReveal(index: Int): Double = UiMotionTokens.out(
        (entryAge - index * UiMotionTokens.CARD_STAGGER_SECONDS) / UiMotionTokens.PAGE_IN_SECONDS)
    fun cardOffsetY(index: Int): Double = -.005 * (1.0 - cardReveal(index))
    fun cardOffsetNormal(index: Int): Double = -.005 * (1.0 - cardReveal(index))
}

/**
 * A single-atlas page transition. The old page remains selected until fully transparent.
 * Only then is pageKey replaced, so old geometry can never sample the new page's texture.
 */
class UiMotionState {
    var clock = 0.0
        private set
    private var previousNanos: Long? = null
    private var current: String? = null
    private var pending: String? = null
    private var entryAt = 0.0
    private var leavingAt: Double? = null
    private val visibility = UiMotionTween()
    private val page = UiMotionTween(1.0)
    private var requestedVisible = false
    private var active = true
    private var lastButtonCount = 0

    fun update(nowNanos: Long, running: Boolean, visible: Boolean, requestedPage: String, buttonCount: Int): UiMotionSample {
        val previous = previousNanos
        previousNanos = nowNanos
        if (running && active && previous != null && nowNanos >= previous)
            clock += ((nowNanos - previous) / 1_000_000_000.0).coerceAtMost(.1)
        active = running
        lastButtonCount = buttonCount.coerceAtLeast(0)
        if (current == null) { current = requestedPage; entryAt = clock }
        if (visible != requestedVisible) {
            if (visible && visibility.value(clock) <= .001) entryAt = clock
            visibility.target(if (visible) 1.0 else 0.0, clock,
                if (visible) UiMotionTokens.OPEN_SECONDS else UiMotionTokens.CLOSE_SECONDS)
            requestedVisible = visible
        }
        if (requestedPage == current) {
            if (pending != null) {
                pending = null; leavingAt = null
                page.target(1.0, clock, UiMotionTokens.PAGE_IN_SECONDS)
            }
        } else if (visibility.value(clock) <= .001) {
            current = requestedPage; pending = null; leavingAt = null; entryAt = clock
            page.snap(1.0, clock)
        } else {
            pending = requestedPage
            if (leavingAt == null) {
                leavingAt = clock
                page.target(0.0, clock, UiMotionTokens.PAGE_OUT_SECONDS)
            }
            if (clock - (leavingAt ?: clock) >= UiMotionTokens.PAGE_OUT_SECONDS - 1e-9) {
                current = pending; pending = null; leavingAt = null; entryAt = clock
                page.snap(0.0, clock)
                page.target(1.0, clock, UiMotionTokens.PAGE_IN_SECONDS)
            }
        }
        return UiMotionSample(current!!, clock, visibility.value(clock), page.value(clock), (leavingAt ?: clock) - entryAt, pending != null)
    }

    fun interactionReady(requestedPage: String): Boolean = active && requestedVisible && current == requestedPage &&
        pending == null && visibility.value(clock) >= .995 && page.value(clock) >= .995 &&
        clock - entryAt >= UiMotionTokens.PAGE_IN_SECONDS + (lastButtonCount - 1).coerceAtLeast(0) * UiMotionTokens.CARD_STAGGER_SECONDS
}

data class UiButtonMotionSample(val liftMeters: Double, val hover: Double, val press: Double, val sweep: Double)
class UiButtonMotion {
    private val hover = UiMotionTween()
    private val press = UiMotionTween()
    private var wasHovered = false
    private var wasPressed = false
    private var lastFeedback = Long.MIN_VALUE
    private var sweepAt = Double.NEGATIVE_INFINITY
    fun update(time: Double, hovered: Boolean, pressed: Boolean, enabled: Boolean, feedbackRevision: Long): UiButtonMotionSample {
        val isPressed = pressed && enabled
        val isHovered = hovered && enabled
        hover.target(if (isHovered) 1.0 else 0.0, time, if (isHovered) .12 else .20)
        press.target(if (isPressed) 1.0 else 0.0, time, if (isPressed) .065 else .20)
        if (enabled && ((isHovered && !wasHovered) || (isPressed && !wasPressed) || (isPressed && feedbackRevision != lastFeedback))) sweepAt = time
        wasHovered = isHovered; wasPressed = isPressed; lastFeedback = feedbackRevision
        val h = hover.value(time); val p = press.value(time)
        val lift = UiMotionTokens.HOVER_METERS * h * (1.0 - p) + UiMotionTokens.PRESS_METERS * p
        return UiButtonMotionSample(lift, h, p, ((time - sweepAt) / .42).coerceIn(0.0, 1.0))
    }
}

/** A shared selection bracket glides between cards while each hit rectangle stays fixed. */
class UiFocusMotion {
    private val channels = List(4) { UiMotionTween() }
    private val opacity = UiMotionTween()
    private var initialized = false
    fun update(time: Double, rectangle: List<Double>?): List<Double> {
        if (rectangle != null) {
            require(rectangle.size == 4 && rectangle.all { it.isFinite() })
            rectangle.forEachIndexed { index, value ->
                if (!initialized) channels[index].snap(value, time) else channels[index].target(value, time, .18)
            }
            initialized = true
        }
        opacity.target(if (rectangle != null) 1.0 else 0.0, time, .14)
        return channels.map { it.value(time) } + opacity.value(time)
    }
}

/** Shortest-path angular inertia is used only for a fading trail, never the actual θ reading. */
class UiAngleTrail {
    private var angle: Double? = null
    private var previousTime: Double? = null
    fun update(degrees: Double, time: Double): Double {
        val target = if (degrees.isFinite()) ((degrees % 360.0) + 360.0) % 360.0 else 0.0
        val dt = previousTime?.let { (time - it).coerceIn(0.0, .1) } ?: 0.0
        val old = angle ?: target
        val delta = ((target - old + 540.0) % 360.0) - 180.0
        val next = if (abs(delta) < .02) target else old + delta * (1.0 - exp(-dt / .075))
        angle = ((next % 360.0) + 360.0) % 360.0; previousTime = time
        return angle!!
    }
}
