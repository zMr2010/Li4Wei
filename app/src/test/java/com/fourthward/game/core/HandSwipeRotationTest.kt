package com.fourthward.game.core

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class HandSwipeRotationTest {
    private val camera = Vec3(0.0, 0.035, 0.60)
    private val right = Vec3(1.0, 0.0, 0.0)

    private fun finger(base: Vec3, tip: Vec3) = FingerLandmarks(base, base + (tip - base) * 0.45, tip, base + (tip - base) * 0.75)
    private fun backFacingHand() = HandLandmarks(
        wrist = Vec3.ZERO, palm = Vec3(0.0, 0.035, 0.0),
        thumb = finger(Vec3(0.025, 0.014, 0.0), Vec3(0.078, 0.057, 0.0)),
        index = finger(Vec3(0.026, 0.065, 0.0), Vec3(0.033, 0.130, 0.0)),
        middle = finger(Vec3(0.006, 0.070, 0.0), Vec3(0.006, 0.145, 0.0)),
        ring = finger(Vec3(-0.014, 0.065, 0.0), Vec3(-0.019, 0.132, 0.0)),
        little = finger(Vec3(-0.032, 0.052, 0.0), Vec3(-0.044, 0.106, 0.0)),
    )

    private fun transform(hand: HandLandmarks, point: (Vec3) -> Vec3, vector: (Vec3) -> Vec3 = { it }): HandLandmarks {
        fun finger(f: FingerLandmarks) = FingerLandmarks(point(f.base), point(f.knuckle), point(f.tip), f.distal?.let(point))
        return hand.copy(wrist = point(hand.wrist), palm = point(hand.palm), thumb = finger(hand.thumb),
            index = finger(hand.index), middle = finger(hand.middle), ring = finger(hand.ring), little = finger(hand.little),
            palmNormal = hand.palmNormal?.let(vector))
    }
    private fun shift(hand: HandLandmarks, displacement: Vec3) = transform(hand, { it + displacement })
    private fun closed(hand: HandLandmarks): HandLandmarks {
        fun curl(f: FingerLandmarks) = f.copy(knuckle = f.base + Vec3(0.0, 0.020, -0.015),
            distal = f.base + Vec3(0.0, 0.005, -0.027), tip = f.base + Vec3(0.0, -0.020, -0.015))
        return hand.copy(index = curl(hand.index), middle = curl(hand.middle), ring = curl(hand.ring), little = curl(hand.little))
    }
    private fun activate(control: HandSwipeRotation, hand: HandLandmarks = backFacingHand(), head: Vec3 = camera, axis: Vec3 = right) {
        repeat(2) { assertFalse(control.update(hand, head, axis, 0.05).active) }
        val activated = control.update(hand, head, axis, 0.05)
        assertTrue(activated.active)
        assertEquals(0.0, activated.deltaRadians, 0.0)
    }

    @Test fun backFacingFiveFingerPoseEntersAfter150msWithNoActivationJump() {
        val control = HandSwipeRotation()
        val hand = backFacingHand()
        assertFalse(control.update(hand, camera, right, 0.05).active)
        assertFalse(control.update(shift(hand, Vec3(0.03, 0.0, 0.0)), camera, right, 0.05).active)
        val finalPose = shift(hand, Vec3(0.06, 0.0, 0.0))
        val state = control.update(finalPose, camera, right, 0.05)
        assertTrue(state.active)
        assertEquals(0.0, state.deltaRadians, 0.0)
        assertEquals(0.0, control.update(finalPose, camera, right, 0.02).deltaRadians, 0.0)
    }

    @Test fun palmFacingCameraAndClosedHandDoNotActivate() {
        val back = backFacingHand()
        val front = transform(back, { Vec3(-it.x, it.y, -it.z) })
        for (hand in listOf(front, closed(back), back.copy(index = closed(back).index))) {
            val control = HandSwipeRotation()
            repeat(10) {
                val state = control.update(hand, camera, right, 0.05)
                assertFalse(state.active)
                assertEquals(0.0, state.deltaRadians, 0.0)
            }
        }
    }

    @Test fun rightAndLeftSwipesProduceOppositeSingleAngleDeltasWithMetricGain() {
        for (direction in listOf(-1.0, 1.0)) {
            val control = HandSwipeRotation(HandSwipeRotationConfig(deadzoneMeters = 0.0))
            activate(control)
            var total = 0.0
            for (step in 1..20) {
                val state = control.update(shift(backFacingHand(), Vec3(direction * step * 0.01, 0.0, 0.0)), camera, right, 0.02)
                assertTrue(state.active)
                total += state.deltaRadians
            }
            assertEquals(direction * PI / 2, total, 1e-9)
        }
    }

    @Test fun mirroredLeftHandWithAnatomicalNormalMatchesRightHandSwipeDirection() {
        val rightHand = backFacingHand()
        val mirrored = transform(rightHand, { Vec3(-it.x, it.y, it.z) })
        val leftHand = mirrored.copy(palmNormal = (mirrored.index.base - mirrored.wrist).cross(mirrored.little.base - mirrored.wrist))
        // This is genuinely opposite chirality: the implicit right-hand normal would reject it.
        val wrongChirality = HandSwipeRotation()
        repeat(10) { assertFalse(wrongChirality.update(mirrored, camera, right, 0.05).active) }
        for (direction in listOf(-1.0, 1.0)) {
            val rightControl = HandSwipeRotation()
            val leftControl = HandSwipeRotation()
            activate(rightControl, rightHand)
            activate(leftControl, leftHand)
            val displacement = Vec3(direction * 0.02, 0.0, 0.0)
            val rightState = rightControl.update(shift(rightHand, displacement), camera, right, 0.02)
            val leftState = leftControl.update(shift(leftHand, displacement), camera, right, 0.02)
            assertTrue(rightState.active)
            assertTrue(leftState.active)
            assertEquals(direction * (0.02 - 0.003) * PI / 0.4, leftState.deltaRadians, 1e-9)
            assertEquals(rightState.deltaRadians, leftState.deltaRadians, 1e-9)
        }
    }

    @Test fun leftPalmFacingCameraIsRejectedAndImmediatelyStopsAnActiveSwipe() {
        val mirrored = transform(backFacingHand(), { Vec3(-it.x, it.y, it.z) })
        val leftBack = mirrored.copy(palmNormal = (mirrored.index.base - mirrored.wrist).cross(mirrored.little.base - mirrored.wrist))
        fun turnTowardCamera(point: Vec3) = Vec3(-point.x, point.y, -point.z)
        val leftFront = transform(leftBack, ::turnTowardCamera, ::turnTowardCamera)
        assertTrue(leftBack.palmNormal!!.z < 0.0)
        assertTrue(leftFront.palmNormal!!.z > 0.0)
        val control = HandSwipeRotation()
        activate(control, leftBack)
        repeat(10) {
            val state = control.update(leftFront, camera, right, 0.01)
            assertFalse(state.active)
            assertEquals(0.0, state.deltaRadians, 0.0)
        }
    }

    @Test fun millimeterMotionAccumulatesAcrossFramesInsteadOfBeingDropped() {
        val control = HandSwipeRotation()
        activate(control)
        var total = 0.0
        for (step in 1..20) {
            val state = control.update(shift(backFacingHand(), Vec3(step * 0.001, 0.0, 0.0)), camera, right, 0.02)
            assertTrue(state.active)
            if (step <= 3) assertEquals(0.0, state.deltaRadians, 1e-12)
            total += state.deltaRadians
        }
        assertEquals((0.020 - 0.003) * PI / 0.4, total, 1e-9)
    }

    @Test fun stationaryVerticalAndDepthMotionProduceNoRotation() {
        val control = HandSwipeRotation()
        activate(control)
        repeat(10) { assertEquals(0.0, control.update(backFacingHand(), camera, right, 0.02).deltaRadians, 0.0) }
        for (step in 1..8) {
            val state = control.update(shift(backFacingHand(), Vec3(0.0, step * 0.01, step * 0.01)), camera, right, 0.02)
            assertTrue(state.active)
            assertEquals(0.0, state.deltaRadians, 0.0)
        }
    }

    @Test fun cameraYawDoesNotRotateTheLockedHorizontalInputAxis() {
        val control = HandSwipeRotation(HandSwipeRotationConfig(deadzoneMeters = 0.0))
        activate(control)
        val changedCameraRight = Vec3(0.0, 0.0, 1.0)
        val xMoved = shift(backFacingHand(), Vec3(0.02, 0.0, 0.0))
        assertEquals(0.02 * PI / 0.4, control.update(xMoved, camera, changedCameraRight, 0.02).deltaRadians, 1e-9)
        val zMoved = shift(xMoved, Vec3(0.0, 0.0, 0.02))
        assertEquals(0.0, control.update(zMoved, camera, changedCameraRight, 0.02).deltaRadians, 0.0)
    }

    @Test fun cameraTranslationAloneAndCommonTranslationDoNotRotate() {
        val hand = backFacingHand()
        val cameraOnly = HandSwipeRotation(HandSwipeRotationConfig(deadzoneMeters = 0.0))
        activate(cameraOnly)
        for (step in 1..8) {
            val state = cameraOnly.update(hand, camera + Vec3(step * 0.01, 0.0, 0.0), right, 0.02)
            assertTrue(state.active)
            assertEquals(0.0, state.deltaRadians, 0.0)
        }
        val common = HandSwipeRotation(HandSwipeRotationConfig(deadzoneMeters = 0.0))
        activate(common)
        for (step in 1..8) {
            val offset = Vec3(step * 0.01, step * 0.005, -step * 0.01)
            val state = common.update(shift(hand, offset), camera + offset, right, 0.02)
            assertTrue(state.active)
            assertEquals(0.0, state.deltaRadians, 1e-12)
        }
    }

    @Test fun opposingCameraMotionCannotAmplifyAHandSwipe() {
        val control = HandSwipeRotation(HandSwipeRotationConfig(deadzoneMeters = 0.0))
        activate(control)
        val state = control.update(shift(backFacingHand(), Vec3(0.02, 0.0, 0.0)), camera + Vec3(-0.03, 0.0, 0.0), right, 0.02)
        assertTrue(state.active)
        assertEquals(0.02 * PI / 0.4, state.deltaRadians, 1e-9)
    }

    @Test fun closingHandOrFacingPalmForwardImmediatelyStopsAndDropsAnchor() {
        val hand = backFacingHand()
        for (invalidPose in listOf(closed(hand), hand.copy(palmNormal = Vec3(0.0, 0.0, 1.0)))) {
            val control = HandSwipeRotation()
            activate(control)
            val stopped = control.update(invalidPose, camera, right, 0.001)
            assertFalse(stopped.active)
            assertEquals(0.0, stopped.deltaRadians, 0.0)
            activate(control, shift(hand, Vec3(0.08, 0.0, 0.0)))
        }
    }

    @Test fun missingStaleAndInvalidTrackingStopImmediatelyAndReacquireWithoutJump() {
        val hand = backFacingHand()
        val badInputs = listOf(null, hand.copy(sampleAgeSeconds = 0.121), hand.copy(palm = Vec3(Double.NaN, 0.0, 0.0)))
        for (bad in badInputs) {
            val control = HandSwipeRotation()
            activate(control)
            val state = control.update(bad, camera, right, 0.001)
            assertFalse(state.active)
            assertEquals(0.0, state.deltaRadians, 0.0)
            activate(control, shift(hand, Vec3(-0.09, 0.0, 0.0)))
        }
    }

    @Test fun largePoseJumpStopsInsteadOfApplyingAnAngularSpike() {
        val control = HandSwipeRotation()
        activate(control)
        val jumped = shift(backFacingHand(), Vec3(0.20, 0.0, 0.0))
        val state = control.update(jumped, camera, right, 0.02)
        assertFalse(state.active)
        assertEquals(0.0, state.deltaRadians, 0.0)
        activate(control, jumped)
        val next = control.update(shift(jumped, Vec3(0.01, 0.0, 0.0)), camera, right, 0.02)
        assertTrue(next.active)
        assertEquals(0.007 * PI / 0.4, next.deltaRadians, 1e-9)
    }

    @Test fun signedBackFacingThresholdUsesEntryAndHoldHysteresis() {
        val hand = backFacingHand()
        fun cameraAt(dot: Double) = hand.palm + Vec3(sqrt(1.0 - dot * dot), 0.0, dot) * 0.6
        val control = HandSwipeRotation()
        repeat(8) { assertFalse(control.update(hand, cameraAt(0.60), right, 0.05).active) }
        activate(control, hand, cameraAt(0.70))
        // Change the facing direction gradually so this is not mistaken for a camera tracking jump.
        for (step in 1..15) assertTrue(control.update(hand, cameraAt(0.70 - step * 0.01), right, 0.02).active)
        assertFalse(control.update(hand, cameraAt(0.49), right, 0.02).active)
    }

    @Test fun yawAndTranslationOfStageCoordinatesDoNotChangeTheMeasuredSwipe() {
        val yaw = 0.83
        fun rotate(p: Vec3) = Vec3(p.x * cos(yaw) + p.z * sin(yaw), p.y, -p.x * sin(yaw) + p.z * cos(yaw))
        fun point(p: Vec3) = rotate(p) + Vec3(2.4, 1.2, -3.7)
        val control = HandSwipeRotation(HandSwipeRotationConfig(deadzoneMeters = 0.0))
        val hand = transform(backFacingHand(), ::point, ::rotate)
        activate(control, hand, point(camera), rotate(right))
        val moved = transform(shift(backFacingHand(), Vec3(0.02, 0.0, 0.0)), ::point, ::rotate)
        val state = control.update(moved, point(camera), rotate(right), 0.02)
        assertTrue(state.active)
        assertEquals(0.02 * PI / 0.4, state.deltaRadians, 1e-9)
    }

    @Test fun renderGapAndInvalidCameraAxisResetWithoutBacklog() {
        val control = HandSwipeRotation()
        activate(control)
        assertFalse(control.update(backFacingHand(), camera, right, 0.5).active)
        activate(control)
        assertFalse(control.update(backFacingHand(), camera, Vec3(Double.NaN, 0.0, 0.0), 0.02).active)
        val invalidAxis = HandSwipeRotation()
        repeat(10) { assertFalse(invalidAxis.update(backFacingHand(), camera, Vec3(0.0, 1.0, 0.0), 0.05).active) }
        activate(control)
        control.reset()
        activate(control, shift(backFacingHand(), Vec3(0.10, 0.0, 0.0)))
    }
}
