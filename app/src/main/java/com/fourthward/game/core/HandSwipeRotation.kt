package com.fourthward.game.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sign

data class HandSwipeRotationConfig(
    val activationSeconds: Double = 0.15,
    val radiansPerMeter: Double = PI / 0.4,
    val deadzoneMeters: Double = 0.003,
    val maxPoseStepMeters: Double = 0.12,
    val staleAfterSeconds: Double = 0.12,
    val facingEnterDot: Double = 0.65,
    val facingHoldDot: Double = 0.50,
    val facingSurface: HandSurface = HandSurface.BACK,
) {
    init {
        require(activationSeconds.isFinite() && activationSeconds >= 0.0)
        require(radiansPerMeter.isFinite() && radiansPerMeter > 0.0)
        require(deadzoneMeters.isFinite() && deadzoneMeters >= 0.0)
        require(maxPoseStepMeters.isFinite() && maxPoseStepMeters > deadzoneMeters)
        require(staleAfterSeconds.isFinite() && staleAfterSeconds > 0.0)
        require(facingEnterDot in 0.0..1.0 && facingHoldDot in 0.0..facingEnterDot)
    }
}

data class HandSwipeRotationState(val active: Boolean = false, val deltaRadians: Double = 0.0)

/** Horizontal motion of an open hand with the configured surface facing the camera changes the slice angle.
 * All positions share stage space. Supply the hand's outward anatomical palm normal:
 * [HandLandmarks.palmNormalFromJoints] computes it for either hand.
 * Omitting palmNormal retains the classifier's right-hand convention.
 * Camera yaw/right is locked at activation, so turning the head cannot rotate the input axis. */
class HandSwipeRotation(private val config: HandSwipeRotationConfig = HandSwipeRotationConfig()) {
    private val pose = HandPanelGesture(HandPanelGestureConfig(
        showSeconds = config.activationSeconds,
        hideSeconds = 0.0,
        staleAfterSeconds = config.staleAfterSeconds,
        facingEnterDot = config.facingEnterDot,
        facingExitDot = config.facingHoldDot,
        facingSurface = config.facingSurface,
    ))
    private var lockedRight: Vec3? = null
    private var previousPalm: Vec3? = null
    private var previousRelativePalm: Vec3? = null
    private var pendingMeters = 0.0

    fun reset() {
        pose.reset()
        clearAnchor()
    }

    fun update(hand: HandLandmarks?, cameraPosition: Vec3, cameraRight: Vec3, deltaSeconds: Double): HandSwipeRotationState {
        if (hand == null || !cameraPosition.finite() || !cameraRight.finite()) return stop()
        val gesture = pose.update(hand, cameraPosition, deltaSeconds)
        if (!gesture.visible || !gesture.validTracking || !gesture.isOpen || !gesture.isFacingHead) {
            clearAnchor()
            return HandSwipeRotationState()
        }

        val relativePalm = hand.palm - cameraPosition
        val right = lockedRight
        val previous = previousPalm
        val previousRelative = previousRelativePalm
        if (right == null || previous == null || previousRelative == null) {
            val horizontalRight = Vec3(cameraRight.x, 0.0, cameraRight.z)
            val length = horizontalRight.length()
            if (!length.isFinite() || length <= 1e-9) return stop()
            lockedRight = horizontalRight * (1.0 / length)
            previousPalm = hand.palm
            previousRelativePalm = relativePalm
            pendingMeters = 0.0
            return HandSwipeRotationState(active = true) // Never replay motion made before activation.
        }

        val palmStep = hand.palm - previous
        val relativeStep = relativePalm - previousRelative
        if (palmStep.length() > config.maxPoseStepMeters || relativeStep.length() > config.maxPoseStepMeters) return stop()
        previousPalm = hand.palm
        previousRelativePalm = relativePalm

        val worldHorizontal = palmStep.dot(right)
        val relativeHorizontal = relativeStep.dot(right)
        // Relative coordinates remove common hand+head translation, but by themselves invent a
        // swipe when ONLY the head moves. Require agreeing world-hand motion and cap its amount:
        // camera-only => zero; common translation => zero; hand-only => exact metric displacement.
        val displacement = if (worldHorizontal * relativeHorizontal <= 0.0) 0.0
            else sign(relativeHorizontal) * min(abs(worldHorizontal), abs(relativeHorizontal))
        if (abs(displacement) > 1e-9) pendingMeters += displacement

        // Retain sub-deadzone displacement across frames; slow movement is not discarded.
        // Once the 3 mm slack is taken up, continued motion is smooth rather than quantized.
        val emittedMeters = if (abs(pendingMeters) > config.deadzoneMeters)
            pendingMeters - sign(pendingMeters) * config.deadzoneMeters else 0.0
        pendingMeters -= emittedMeters
        return HandSwipeRotationState(active = true, deltaRadians = emittedMeters * config.radiansPerMeter)
    }

    private fun stop(): HandSwipeRotationState {
        reset()
        return HandSwipeRotationState()
    }

    private fun clearAnchor() {
        lockedRight = null
        previousPalm = null
        previousRelativePalm = null
        pendingMeters = 0.0
    }

    private fun Vec3.finite() = x.isFinite() && y.isFinite() && z.isFinite()
}
