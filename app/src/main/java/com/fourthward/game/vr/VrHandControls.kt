package com.fourthward.game.vr

import com.fourthward.game.core.HandContact
import com.fourthward.game.core.HandContactGesture
import com.fourthward.game.core.HandLandmarks
import com.fourthward.game.core.NormalizedButtonRect
import com.fourthward.game.core.LevelMapWindow
import com.fourthward.game.core.HandPanelGesture
import com.fourthward.game.core.HandPanelGestureConfig
import com.fourthward.game.core.HandSurface
import com.fourthward.game.core.HandSwipeRotation
import com.fourthward.game.core.HandSwipeRotationState
import com.fourthward.game.core.PalmAction
import com.fourthward.game.core.PalmPanelLayout
import com.fourthward.game.core.PalmPanelSpec
import com.fourthward.game.core.PalmPointerPosition
import com.fourthward.game.core.PalmPoke
import com.fourthward.game.core.Vec3
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign

/** Arbitrates tracked hands in stage space. The game applies the returned motion and UI actions. */
internal class VrHandControls {
    data class Panel(val pose: VrTrackingMath.Pose, val scale: Double = 1.0)

    data class Frame(
        val panel: Panel? = null,
        val opened: Boolean = false,
        val closed: Boolean = false,
        val pointer: PalmPointerPosition? = null,
        val hovered: PalmAction? = null,
        val pressed: PalmAction? = null,
        val action: PalmAction? = null,
        val clickedByPinch: Boolean = false,
        val clickedByDwell: Boolean = false,
        val dwellProgress: Double = 0.0,
        val mapDragRows: Int = 0,
        val rotation: HandSwipeRotationState = HandSwipeRotationState(),
        val forwardMeters: Double = 0.0,
        val pulling: Boolean = false,
    )

    private enum class Side { LEFT, RIGHT }
    private enum class PanelState { HIDDEN, PINNED, DISMISSED_BY_GRIP }

    private val leftGesture = HandContactGesture()
    private val rightGesture = HandContactGesture()
    private val leftOpener = PanelOpener()
    private val rightOpener = PanelOpener()
    private val leftPointer = PanelPointer()
    private val rightPointer = PanelPointer()
    private val swipe = HandSwipeRotation()
    private val pull = GrabPull()
    // A hand placement belongs to this tracking session, across all level coordinate systems.
    var preferredPanel: Panel? = null
        private set
    private var panelState = PanelState.HIDDEN
    private val pinnedPanel get() = preferredPanel.takeIf { panelState == PanelState.PINNED }
    private var pointerOwner: Side? = null
    private var swiping = false
    private var pullingHand: Side? = null

    fun resetPanelInput() {
        leftPointer.interruptPage()
        rightPointer.interruptPage()
        pointerOwner = null
    }

    fun resetMotion() {
        swipe.reset()
        swiping = false
        pull.reset()
        pullingHand = null
    }

    fun resetInput() {
        leftGesture.reset()
        rightGesture.reset()
        leftOpener.resetDwell()
        rightOpener.resetDwell()
        leftPointer.reset()
        rightPointer.reset()
        pointerOwner = null
        resetMotion()
    }

    fun reset() {
        resetPanelInput()
        resetInput()
        panelState = PanelState.HIDDEN
        leftOpener.reset()
        rightOpener.reset()
    }

    fun update(
        dt: Double,
        head: VrTrackingMath.Pose,
        left: HandLandmarks?,
        right: HandLandmarks?,
        lessonPanel: Panel?,
        layout: PalmPanelSpec,
        disabled: Set<PalmAction>,
        interactionReady: Boolean,
        allowMotion: Boolean,
        panelHoldDeltaSeconds: Double,
        allowMapDrag: Boolean = false,
        allowPanelGestures: Boolean = true,
    ): Frame {
        val leftContact = leftGesture.update(left, dt)
        val rightContact = rightGesture.update(right, dt)
        val existingPanel = pinnedPanel ?: lessonPanel
        if (allowPanelGestures && existingPanel != null) {
            val leftCloses = gripsPanel(leftContact, existingPanel, layout)
            val rightCloses = gripsPanel(rightContact, existingPanel, layout)
            if (leftCloses || rightCloses) {
                panelState = PanelState.DISMISSED_BY_GRIP
                leftOpener.suppress(leftContact?.hand, head, leftCloses)
                rightOpener.suppress(rightContact?.hand, head, rightCloses)
                resetMotion()
                resetPanelInput()
                return Frame(closed = true)
            }
        }

        val opened = if (allowPanelGestures && pinnedPanel == null) {
            val leftPanel = leftOpener.update(leftContact?.hand, head, panelHoldDeltaSeconds)
            val rightPanel = rightOpener.update(rightContact?.hand, head, panelHoldDeltaSeconds)
            val placement = rightPanel ?: leftPanel
            if (placement != null) {
                // An automatic close between levels recalls the prior pose. Closing by
                // hand lets the player choose a new spot with the next palm gesture.
                if (preferredPanel == null || panelState == PanelState.DISMISSED_BY_GRIP) {
                    preferredPanel = placement
                }
                panelState = PanelState.PINNED
            }
            placement != null
        } else false
        val panel = if (allowPanelGestures) pinnedPanel ?: lessonPanel else lessonPanel
        if (panel != null) {
            resetMotion()
            // A newly revealed map row can have more cards. Its entrance gate applies to new presses.
            val continuingDrag = allowMapDrag && (leftPointer.dragging || rightPointer.dragging)
            if (opened || (!interactionReady && !continuingDrag)) {
                resetPanelInput()
                return Frame(panel = panel, opened = opened)
            }
            // A captured pinch owns its release, even if the other hand pokes a button meanwhile.
            val wasLeft = leftPointer.captured
            val wasRight = rightPointer.captured
            val leftInput = if (wasRight) Frame(panel = panel) else leftPointer.update(leftContact, panel, layout, disabled, dt, allowMapDrag)
            val rightInput = if (wasLeft) Frame(panel = panel) else rightPointer.update(rightContact, panel, layout, disabled, dt, allowMapDrag)
            val useRight = when {
                wasLeft || wasRight -> wasRight
                leftPointer.captured != rightPointer.captured -> rightPointer.captured
                rightInput.priority() != leftInput.priority() -> rightInput.priority() > leftInput.priority()
                leftPointer.canDwell != rightPointer.canDwell -> rightPointer.canDwell
                else -> pointerOwner == Side.RIGHT
            }
            val owner = if (useRight) rightPointer else leftPointer
            val other = if (useRight) leftPointer else rightPointer
            pointerOwner = if (useRight) Side.RIGHT else Side.LEFT
            val chosen = if (useRight) rightInput else leftInput
            val result = owner.finishDwell(chosen, panelHoldDeltaSeconds)
            if (owner.captured || result.action != null) other.interruptPage() else other.cancelDwell()
            return result
        }

        resetPanelInput()
        val rightward = VrTrackingMath.horizontalRight(head)
        if (!allowMotion || rightward == null) {
            resetMotion()
            return Frame()
        }
        val forward = Vec3(rightward.z, 0.0, -rightward.x)
        fun reached(contact: HandContact) = (contact.hand.palm - head.position).dot(forward) >= FORWARD_REACH_METERS
        fun selectedHand() = when (pullingHand) {
            Side.LEFT -> leftContact
            Side.RIGHT -> rightContact
            null -> null
        }
        if (selectedHand()?.gripping != true) {
            pull.reset()
            pullingHand = when {
                rightContact?.gripStarted == true && reached(rightContact) -> Side.RIGHT
                leftContact?.gripStarted == true && reached(leftContact) -> Side.LEFT
                else -> null
            }
        }
        val grip = selectedHand()
        val distance = grip?.let { pull.update(it.hand.palm, head.position) } ?: 0.0

        val hand = rightContact?.takeIf { swiping || reached(it) }?.hand
        val rotation = swipe.update(hand, head.position, rightward, dt)
        swiping = rotation.active
        return Frame(rotation = rotation, pulling = grip != null, forwardMeters = distance)
    }

    private fun gripsPanel(contact: HandContact?, panel: Panel, layout: PalmPanelSpec): Boolean {
        if (contact?.gripStarted != true) return false
        val hand = contact.hand
        val center = (hand.palm + hand.middle.tip) * .5
        val width = layout.widthMeters * panel.scale
        val height = layout.heightMeters * panel.scale
        val hit = VrTrackingMath.pointer(center, panel.pose, width, height)
        val edge = .02
        return hit.x in (-edge / width)..(1 + edge / width) && hit.y in (-edge / height)..(1 + edge / height) &&
            abs(hit.depthMeters) <= .10
    }

    private fun Frame.priority(): Int = when {
        action != null && clickedByPinch -> 5
        action != null -> 4
        pressed != null -> 3
        hovered != null -> 2
        pointer != null -> 1
        else -> 0
    }

    private companion object {
        const val FORWARD_REACH_METERS = .24
    }
}

private class PanelOpener {
    private val gesture = HandPanelGesture(HandPanelGestureConfig(
        showSeconds = 3.0, facingSurface = HandSurface.PALM, allowFacingUp = true,
    ))
    private var dismissedAt: Vec3? = null
    private var holdingPose = false

    fun resetDwell() {
        gesture.reset()
        holdingPose = false
    }

    fun reset() {
        resetDwell()
        dismissedAt = null
    }

    fun suppress(hand: HandLandmarks?, head: VrTrackingMath.Pose, closed: Boolean) {
        resetDwell()
        val heldOpen = gesture.update(hand, head.position, 0.0).isPanelPose
        dismissedAt = hand?.palm?.takeIf { closed || heldOpen }
    }

    fun update(hand: HandLandmarks?, head: VrTrackingMath.Pose, elapsedSeconds: Double): VrHandControls.Panel? {
        // Unclenching at the same spot after a dismissal must leave the panel closed.
        val origin = dismissedAt
        if (origin != null && hand != null && (hand.palm - origin).lengthSquared() > .10 * .10) dismissedAt = null
        val waiting = dismissedAt != null
        // Begin measuring at the first sample of a valid pose.
        val open = gesture.update(hand, head.position, if (holdingPose && !waiting) elapsedSeconds else 0.0)
        holdingPose = !waiting && open.isPanelPose
        if (open.isOpen && !open.isPanelPose) dismissedAt = null
        if (waiting || !open.visible || !open.isPanelPose || hand == null) return null
        val normal = open.palmNormal ?: return null
        return VrTrackingMath.panelPose(hand.palm + normal * .04, head)?.let { VrHandControls.Panel(it) }
    }
}

private class PanelPointer {
    private data class Aim(val point: PalmPointerPosition, val palm: Vec3, val fingerGap: Double)
    private data class Surface(val panel: VrHandControls.Panel, val layout: PalmPanelSpec) {
        val width = layout.widthMeters * panel.scale
        val height = layout.heightMeters * panel.scale
        fun aim(hand: HandContact, contact: PalmPointerPosition): PalmPointerPosition? {
            val projected = contact.takeIf { PalmPanelLayout.inPanel(it.x, it.y) && it.depthMeters in -.02.. .35 }
            if (projected != null && projected.depthMeters <= .12) return projected
            val ray = if (hand.indexExtended) VrTrackingMath.pointAtPanel(hand.hand.index, panel.pose, width, height) else null
            if (ray == null || projected == null) return ray ?: projected
            // Ease from perpendicular near touch to the pointing ray instead of jumping at a fixed depth.
            val mix = ((projected.depthMeters - .12) / .07).coerceIn(0.0, 1.0)
            return ray.copy(x = projected.x + (ray.x - projected.x) * mix, y = projected.y + (ray.y - projected.y) * mix)
        }
    }
    private data class DwellTarget(val surface: Surface, val button: NormalizedButtonRect) {
        fun near(point: PalmPointerPosition?): Boolean = point != null &&
            point.x in (button.left - .003 / surface.width)..(button.right + .003 / surface.width) &&
            point.y in (button.top - .003 / surface.height)..(button.bottom + .003 / surface.height)
    }
    private data class Pinch(
        val start: PalmPointerPosition,
        val palm: Vec3,
        val action: PalmAction?,
        var lastPinchOffset: Vec3,
        var fingerTravel: Vec3 = Vec3.ZERO,
        var dragged: Boolean = false,
        var rows: Int = 0,
    )

    private val poke = PalmPoke()
    private var pinch: Pinch? = null
    val captured get() = pinch != null
    val dragging get() = pinch?.dragged == true
    private var lastAim: Aim? = null
    private var previousFingerGap = Double.NaN
    private var aimAge = 0.0
    private var closingGap = Double.NaN
    private var openGap = 0.0
    private var candidate: DwellTarget? = null
    private var dwelling: DwellTarget? = null
    private var dwellTime = 0.0
    private var blocked: DwellTarget? = null
    private var blockedByDwell = false
    private var readyForPinch = false
    private var pokeConsumed = false

    val canDwell get() = candidate != null && blocked == null

    fun cancelDwell() {
        dwelling = null
        dwellTime = 0.0
    }

    fun interruptPage() {
        // Remember the old button in physical space, even if the next page has a different layout.
        if (blocked == null) blocked = dwelling ?: candidate
        reset()
    }

    fun finishDwell(frame: VrHandControls.Frame, elapsed: Double): VrHandControls.Frame {
        val current = candidate
        if (frame.action != null) {
            cancelDwell()
            if (blocked != null && blockedByDwell) return frame.copy(action = null, clickedByPinch = false)
            blocked = current ?: blocked
            return frame
        }
        if (current == null || blocked != null || !elapsed.isFinite() || elapsed !in 0.0..PalmPoke.MAX_SAMPLE_GAP_SECONDS) {
            cancelDwell()
            return frame
        }
        if (dwelling?.button != current.button) dwellTime = 0.0 else dwellTime += elapsed
        dwelling = current
        if (dwellTime + 1e-9 < 2.0) return frame.copy(dwellProgress = dwellTime / 2.0)
        blocked = current
        blockedByDwell = true
        cancelDwell()
        return frame.copy(pressed = current.button.action, action = current.button.action, clickedByDwell = true, dwellProgress = 1.0)
    }

    fun reset() {
        cancelDwell()
        candidate = null
        poke.reset()
        pinch = null
        lastAim = null
        previousFingerGap = Double.NaN
        aimAge = 0.0
        closingGap = Double.NaN
        openGap = 0.0
        readyForPinch = false
        pokeConsumed = false
    }

    fun update(hand: HandContact?, panel: VrHandControls.Panel, layout: PalmPanelSpec, disabled: Set<PalmAction>,
               dt: Double, allowMapDrag: Boolean): VrHandControls.Frame {
        if (hand == null || hand.gripping) {
            reset()
            return VrHandControls.Frame(panel = panel)
        }
        val palm = hand.hand.palm
        val index = hand.hand.index
        candidate = null
        val surface = Surface(panel, layout)
        val width = surface.width
        val height = surface.height
        val contact = VrTrackingMath.pointer(index.tip, panel.pose, width, height)
        val aim = surface.aim(hand, contact)
        fun target(point: PalmPointerPosition?) = point?.let { layout.hitTest(it.x, it.y) }?.takeUnless { it in disabled }
        // Dropping a hand or briefly curling the finger does not count as leaving a fired button.
        val latch = blocked
        if (latch != null && hand.indexExtended && !hand.pinching) {
            val prior = latch.surface
            val priorAim = if (prior == surface) aim else prior.aim(hand,
                VrTrackingMath.pointer(index.tip, prior.panel.pose, prior.width, prior.height))
            if (!latch.near(priorAim)) {
                blocked = null
                blockedByDwell = false
            }
        }
        // Use cumulative closing distance. A slow pinch can move less than a millimetre per frame.
        val fingerGap = (hand.hand.thumb.tip - index.tip).length()
        aimAge += dt
        openGap = maxOf(openGap, fingerGap)
        val closing = fingerGap < previousFingerGap - .0003 || (openGap - fingerGap >= .002 &&
            (!closingGap.isFinite() || fingerGap < closingGap + .004))
        if (closing && (!closingGap.isFinite() || fingerGap < closingGap - .0008)) {
            closingGap = fingerGap
            aimAge = 0.0
        }
        val recent = lastAim?.takeIf { aimAge <= .7 && (it.palm - palm).lengthSquared() <= .055 * .055 }
        val gapChange = abs(fingerGap - previousFingerGap)
        previousFingerGap = fingerGap
        val pinchOffset = (hand.hand.thumb.tip + index.tip) * .5 - palm

        if (hand.pinching && hand.pinchStarted && readyForPinch && !pokeConsumed) {
            val start = recent?.point ?: aim
            if (start != null && (allowMapDrag || target(start) != null)) pinch = Pinch(start, palm, target(start), pinchOffset)
        }
        val held = pinch
        if (held != null) {
            poke.reset()
            // Wrist rotation moves the pinch around the palm. If the aperture also changes,
            // discount only midpoint motion along the thumb/index axis caused by closing.
            if (hand.pinching) {
                val step = pinchOffset - held.lastPinchOffset
                if (step.lengthSquared() > .08 * .08) {
                    reset()
                    return VrHandControls.Frame(panel = panel)
                }
                val apertureAxis = if (fingerGap > .001) (hand.hand.thumb.tip - index.tip) * (1.0 / fingerGap) else Vec3.ZERO
                val curlMotion = if (gapChange > .003) step.dot(apertureAxis).coerceIn(-gapChange * .5, gapChange * .5) else 0.0
                held.fingerTravel += step - apertureAxis * curlMotion
            }
            held.lastPinchOffset = pinchOffset
            val travel = palm - held.palm + held.fingerTravel
            val across = travel.dot(panel.pose.right)
            val down = -travel.dot(panel.pose.up)
            // Ignore finger curl and small hand jitter. A drag cannot turn back into a tap.
            if (hypot(across, down) >= (if (allowMapDrag) .025 else .045) || abs(travel.dot(panel.pose.normal)) >= .09) held.dragged = true
            val rowHeight = height * LevelMapWindow.ROW_SPACING
            val remainingRows = down / rowHeight - held.rows
            val rows = if (allowMapDrag && held.dragged && abs(remainingRows) >= .65) remainingRows.roundToInt() else 0
            held.rows += rows
            val action = held.action?.takeUnless { held.dragged || it in disabled }
            val pointer = if (held.dragged) held.start.copy(x = held.start.x + across / width, y = held.start.y + down / height)
                else held.start
            val clicked = action.takeUnless { hand.pinching }
            if (!hand.pinching) {
                reset()
                val endedOver = if (held.dragged) target(aim) else action
                val button = layout.buttons.firstOrNull { it.action == endedOver }
                if (button != null) {
                    candidate = DwellTarget(surface, button)
                    if (held.dragged) blocked = candidate
                }
                readyForPinch = true
                lastAim = aim?.let { Aim(it, palm, fingerGap) }
                previousFingerGap = fingerGap
            }
            return VrHandControls.Frame(panel = panel, pointer = pointer, hovered = action,
                pressed = action.takeIf { hand.pinching }, action = clicked, clickedByPinch = clicked != null, mapDragRows = rows)
        }

        if (hand.pinching) {
            poke.reset()
            readyForPinch = false
            pokeConsumed = false
            return VrHandControls.Frame(panel = panel, pointer = aim)
        }
        readyForPinch = true
        val preserveAim = recent != null && (!hand.indexExtended || closing)
        val hover = if (preserveAim) recent?.point else aim
        if (!preserveAim) {
            if (closingGap.isFinite() || recent == null || target(aim) != target(recent.point)) openGap = fingerGap
            closingGap = Double.NaN
            lastAim = aim?.let { Aim(it, palm, fingerGap) }
            aimAge = 0.0
        }
        if (contact.depthMeters > PalmPoke.REARM_DEPTH_METERS) pokeConsumed = false
        if (!hand.indexExtended || (closing && recent != null && recent.fingerGap - fingerGap > .008)) {
            poke.reset()
            return VrHandControls.Frame(panel = panel, pointer = hover, hovered = target(hover))
        }
        val directTarget = target(hover)
        val priorButton = dwelling?.button?.takeIf { it.action !in disabled && dwelling?.near(hover) == true }
        val button = layout.buttons.firstOrNull { it.action == directTarget } ?: priorButton.takeIf {
            hover != null && layout.hitTest(hover.x, hover.y) == null
        }
        // Small variations in finger spacing are common while holding a point. A stored
        // pinch aim can never earn dwell time unless this frame also points at the button.
        if (button != null && (!preserveAim || target(aim) == button.action) &&
            (!closing || openGap - fingerGap < .006)) candidate = DwellTarget(surface, button)
        val tap = poke.update(contact.x, contact.y, contact.depthMeters, valid = true, dt = dt, layout = layout, disabled = disabled)
        if (tap.action != null) pokeConsumed = true
        return VrHandControls.Frame(panel = panel, pointer = hover, hovered = tap.hoveredAction ?: button?.action,
            pressed = tap.pressedAction, action = tap.action)
    }
}

/** Measures a single grip's horizontal retraction relative to the head. */
private class GrabPull {
    private data class Sample(val palm: Vec3, val relative: Vec3)
    private var previous: Sample? = null
    private var travel = 0.0
    private var emitted = 0.0

    fun reset() {
        previous = null
        travel = 0.0
        emitted = 0.0
    }

    fun update(palm: Vec3, head: Vec3): Double {
        val current = Sample(palm, palm - head)
        val last = previous
        previous = current
        if (last == null) return 0.0
        if ((current.relative - last.relative).lengthSquared() > .15 * .15) {
            travel = emitted
            return 0.0
        }
        val previousDistance = hypot(last.relative.x, last.relative.z)
        if (previousDistance < .01) return 0.0
        val radialTravel = previousDistance - hypot(current.relative.x, current.relative.z)
        val axis = Vec3(last.relative.x / previousDistance, 0.0, last.relative.z / previousDistance)
        val palmTravel = (last.palm - current.palm).dot(axis)
        if (radialTravel * palmTravel > 0.0) travel += sign(radialTravel) * min(abs(radialTravel), abs(palmTravel))
        // Reaching outward again while holding the same grip cannot repeat travel already emitted.
        val step = travel - emitted - .005
        if (step <= 1e-9) return 0.0
        emitted += step
        return step * 2.4
    }
}
