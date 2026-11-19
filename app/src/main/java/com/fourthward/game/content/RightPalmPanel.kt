package com.fourthward.game.content

import android.util.Log
import com.fourthward.game.core.*
import com.fourthward.game.rendering.LeftFingerMarker
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.LookAtComponent
import com.pico.spatial.core.ecs.LookAtForwardDirection
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.tracking.DataProvider
import com.pico.spatial.tracking.hand.HandJoint
import com.pico.spatial.tracking.hand.HandPose
import com.pico.spatial.tracking.hand.HandTrackingData
import com.pico.spatial.tracking.hand.HandTrackingProvider
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.exp

/** Mutually exclusive hand modes: palm-facing controls, or an outward-palm slice swipe. */
class RightPalmPanel(private val sceneRoot: Entity) {
    private enum class Side { RIGHT, LEFT }
    private data class Sample(val data: HandTrackingData, val atNanos: Long)
    private val provider = HandTrackingProvider()
    private val sample = AtomicReference<Sample?>(null)
    private val listener = DataProvider.DataListener<HandTrackingData> {
        sample.set(Sample(it, java.lang.System.nanoTime()))
    }
    private val palmGesture = HandPanelGesture()
    private val leftInspector = HandPanelGesture(HandPanelGestureConfig(showSeconds = 0.0, hideSeconds = 0.0))
    private val swipe = HandSwipeRotation()
    private val poke = PalmPoke()
    private val marker = LeftFingerMarker()
    private var entity: Entity? = null
    private var active = false
    private var baseWidth = 0f
    private var baseHeight = 0f
    private var smoothedPosition: Vec3? = null
    private var debugPosition: Vec3? = null
    var debugVisible = false
        private set
    private var selectedHand: Side? = null
    private var rotationActive = false
    private var rotationSampleNanos: Long? = null
    private var rotationElapsed = 0.0
    private var pokeSampleNanos: Long? = null
    private var pokeElapsed = 0.0
    private var pokeLogNanos = 0L
    private var pendingAction: PalmAction? = null
    var hoveredAction: PalmAction? = null
        private set
    var pressedAction: PalmAction? = null
        private set
    var pointer: PalmPointerPosition? = null
        private set
    var visible = false
        private set

    suspend fun prepare() { marker.prepare(sceneRoot) }
    fun close() { stop(); marker.close() }
    fun takeAction(): PalmAction? = pendingAction.also { pendingAction = null }
    fun setDebugVisible(value: Boolean) {
        debugVisible = value
        debugPosition = null
        resetSwipe()
        resetPoke()
        if (!value) setVisible(false)
    }

    fun attach(panel: Entity) {
        check(entity == null)
        entity = panel
        panel.enabled = false
        panel.components.set(LookAtComponent().apply {
            setViewerAsTarget()
            alignLocalUpToWorldUp = true
            lookAtForwardDirection = LookAtForwardDirection.POSITIVE_Z
        })
        sceneRoot.addChild(panel)
        applySize()
    }

    fun setBaseSize(width: Float, height: Float) {
        if (width.isFinite() && height.isFinite() && width > 0f && height > 0f) {
            baseWidth = width; baseHeight = height; applySize()
        }
    }

    private fun applySize() {
        if (baseWidth > 0f) entity?.components?.get(TransformComponent::class.java)?.scaleVector =
            Vector3(PalmPanelLayout.WIDTH_METERS.toFloat() / baseWidth)
    }

    fun start() {
        if (active) return
        try {
            provider.addListener(listener)
            val result = provider.start()
            active = true
            Log.i("FourthwardHand", "Hand provider start=$result support=${provider.supportState}")
        } catch (error: Exception) {
            provider.removeListener(listener)
            sample.set(null)
            Log.e("FourthwardHand", "Hand tracking unavailable", error)
        }
    }

    fun stop() {
        if (active) { provider.removeListener(listener); provider.stop(); active = false }
        sample.set(null)
        palmGesture.reset(); leftInspector.reset()
        selectedHand = null
        resetSwipe()
        setVisible(false)
        resetPoke()
    }

    /** All pose conversion and Entity changes happen on the main ECS thread. */
    fun update(dt: Double, head: Vec3?, cameraRight: Vec3?): HandSwipeRotationState {
        pendingAction = null
        if (debugVisible) {
            resetSwipe()
            resetPoke()
            val forward = cameraRight?.let { Vec3(it.z, 0.0, -it.x) } ?: Vec3(0.0, 0.0, -1.0)
            val position = debugPosition ?: ((head ?: Vec3(0.0, 1.4, 0.0)) +
                forward * 0.65 + Vec3(0.0, -0.12, 0.0)).also { debugPosition = it }
            entity?.components?.get(TransformComponent::class.java)?.position = position.native()
            setVisible(entity != null && baseWidth > 0f && baseHeight > 0f)
            return HandSwipeRotationState()
        }
        val latest = sample.get()
        val age = latest?.let { (java.lang.System.nanoTime() - it.atNanos) / 1e9 }
        val valid = active && provider.state == DataProvider.State.STARTED && head != null &&
            age != null && age in 0.0..0.12 && dt.isFinite() && dt in 0.0..0.12
        val right = if (valid) latest?.data?.right?.let { mapHand(it, age!!, false) } else null
        val rightPose = palmGesture.update(right, head ?: Vec3.ZERO, dt)
        // Facing-palm controls take priority even during their entry dwell, so left-hand
        // movement while reaching for a button can never rotate the section.
        val controlsMode = rightPose.validTracking &&
            (rightPose.visible || (rightPose.isOpen && rightPose.isFacingHead))
        val left = if (valid && !controlsMode) latest?.data?.left?.let { mapHand(it, age!!, true) } else null
        val leftPose = leftInspector.update(left, head ?: Vec3.ZERO, dt)
        fun eligible(pose: HandPanelGestureState, side: Side): Boolean {
            val threshold = if (selectedHand == side && rotationActive) -0.50 else -0.65
            return pose.validTracking && pose.isOpen && pose.facingDot <= threshold
        }
        val desired = when {
            !valid || controlsMode || cameraRight == null -> null
            selectedHand == Side.RIGHT && eligible(rightPose, Side.RIGHT) -> Side.RIGHT
            selectedHand == Side.LEFT && eligible(leftPose, Side.LEFT) -> Side.LEFT
            eligible(rightPose, Side.RIGHT) -> Side.RIGHT
            eligible(leftPose, Side.LEFT) -> Side.LEFT
            else -> null
        }
        if (desired != selectedHand) { resetSwipe(); selectedHand = desired }
        val rotation = if (desired == null || latest == null || head == null || cameraRight == null) {
            resetSwipe(); HandSwipeRotationState()
        } else {
            rotationElapsed += dt
            if (latest.atNanos != rotationSampleNanos) {
                val result = swipe.update(if (desired == Side.RIGHT) right else left, head, cameraRight, rotationElapsed)
                rotationSampleNanos = latest.atNanos
                rotationElapsed = 0.0
                result
            } else HandSwipeRotationState(active = rotationActive)
        }
        if (rotation.active != rotationActive) Log.i("FourthwardHand", "swipeRotationActive=${rotation.active} hand=$selectedHand")
        rotationActive = rotation.active

        if (controlsMode && rightPose.visible && right != null && rightPose.palmNormal != null &&
            entity != null && baseWidth > 0f && baseHeight > 0f) {
            val target = right.palm + rightPose.palmNormal * 0.04
            val previous = smoothedPosition
            val alpha = 1.0 - exp(-dt.coerceIn(0.0, 0.05) / 0.035)
            // Keep a stable target during a brief classification wobble or an actual near touch.
            // Missing/stale tracking still hides the panel immediately through validTracking.
            val nearContact = pointer?.let { PalmPanelLayout.inPanel(it.x, it.y) &&
                it.depthMeters in -0.02..0.10 } == true
            val hold = previous != null && visible &&
                ((!rightPose.isOpen || !rightPose.isFacingHead) ||
                    (nearContact && (target - previous).length() < 0.12))
            val position = if (hold) previous!! else if (!visible || previous == null) target
                else previous + (target - previous) * alpha
            smoothedPosition = position
            entity?.components?.get(TransformComponent::class.java)?.position = position.native()
            setVisible(true)
        } else setVisible(false)
        updatePoke(latest, valid, dt)
        return rotation
    }

    private fun resetSwipe() {
        if (rotationActive) Log.i("FourthwardHand", "swipeRotationActive=false")
        swipe.reset()
        rotationActive = false
        rotationSampleNanos = null
        rotationElapsed = 0.0
    }

    private fun updatePoke(latest: Sample?, valid: Boolean, dt: Double) {
        val panel = entity
        val tip = if (visible && valid) latest?.data?.left?.handJoints
            ?.firstOrNull { it.index == HandJoint.Index.INDEX_TIP }?.position
            ?.takeIf { it.x.isFinite() && it.y.isFinite() && it.z.isFinite() } else null
        if (panel == null || tip == null || latest == null || baseWidth <= 0f || baseHeight <= 0f) {
            resetPoke(); return
        }
        val local = panel.convertPositionFrom(tip, null)
        val scale = panel.convertScaleTo(Vector3.ONE, null)
        val positionOnPanel = PalmPointerPosition(
            0.5 + local.x / baseWidth,
            0.5 - local.y / baseHeight,
            (local.z * scale.z).toDouble(),
        )
        pointer = positionOnPanel
        pokeElapsed += dt
        if (latest.atNanos != pokeSampleNanos) {
            val result = poke.update(
                positionOnPanel.x,
                positionOnPanel.y,
                positionOnPanel.depthMeters,
                valid = true,
                dt = pokeElapsed,
            )
            pokeSampleNanos = latest.atNanos
            pokeElapsed = 0.0
            hoveredAction = result.hoveredAction
            pressedAction = result.pressedAction
            pendingAction = result.action
            if (result.action != null) Log.i("FourthwardHand", "leftPoke=${result.action}")
            if (latest.atNanos - pokeLogNanos > 500_000_000L) {
                pokeLogNanos = latest.atNanos
                Log.d("FourthwardHand", "leftTipPanel=$positionOnPanel hover=${result.hoveredAction} pressed=${result.pressedAction}")
            }
        }
        val position = sceneRoot.convertPositionFrom(tip, null)
        marker.update(Vec3(position.x.toDouble(), position.y.toDouble(), position.z.toDouble()), pressedAction != null)
    }

    private fun resetPoke() {
        poke.reset()
        pokeSampleNanos = null; pokeElapsed = 0.0
        hoveredAction = null; pressedAction = null; pendingAction = null
        pointer = null
        marker.update(null)
    }

    private fun setVisible(value: Boolean) {
        if (value != visible) Log.i("FourthwardHand", "palmPanelVisible=$value")
        visible = value
        entity?.enabled = value
        if (!value) smoothedPosition = null
    }

    private fun mapHand(hand: HandPose, age: Double, isLeft: Boolean): HandLandmarks? {
        val joints = hand.handJoints.associateBy { it.index }
        fun point(index: HandJoint.Index): Vec3? = joints[index]?.position?.let {
            sceneRoot.convertPositionFrom(it, null).let { p -> Vec3(p.x.toDouble(), p.y.toDouble(), p.z.toDouble()) }
        }
        fun finger(base: HandJoint.Index, knuckle: HandJoint.Index, distal: HandJoint.Index, tip: HandJoint.Index): FingerLandmarks? {
            return FingerLandmarks(point(base) ?: return null, point(knuckle) ?: return null,
                point(tip) ?: return null, point(distal) ?: return null)
        }
        val result = HandLandmarks(
            wrist = point(HandJoint.Index.WRIST) ?: return null,
            palm = point(HandJoint.Index.PALM) ?: return null,
            thumb = finger(HandJoint.Index.THUMB_METACARPAL, HandJoint.Index.THUMB_PROXIMAL, HandJoint.Index.THUMB_DISTAL, HandJoint.Index.THUMB_TIP) ?: return null,
            index = finger(HandJoint.Index.INDEX_PROXIMAL, HandJoint.Index.INDEX_INTERMEDIATE, HandJoint.Index.INDEX_DISTAL, HandJoint.Index.INDEX_TIP) ?: return null,
            middle = finger(HandJoint.Index.MIDDLE_PROXIMAL, HandJoint.Index.MIDDLE_INTERMEDIATE, HandJoint.Index.MIDDLE_DISTAL, HandJoint.Index.MIDDLE_TIP) ?: return null,
            ring = finger(HandJoint.Index.RING_PROXIMAL, HandJoint.Index.RING_INTERMEDIATE, HandJoint.Index.RING_DISTAL, HandJoint.Index.RING_TIP) ?: return null,
            little = finger(HandJoint.Index.LITTLE_PROXIMAL, HandJoint.Index.LITTLE_INTERMEDIATE, HandJoint.Index.LITTLE_DISTAL, HandJoint.Index.LITTLE_TIP) ?: return null,
            sampleAgeSeconds = age,
        )
        return result.copy(palmNormal = result.palmNormalFromJoints(isLeft))
    }
}

private fun Vec3.native() = Vector3(x.toFloat(), y.toFloat(), z.toFloat())
