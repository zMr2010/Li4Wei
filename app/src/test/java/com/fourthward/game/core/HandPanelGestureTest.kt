package com.fourthward.game.core

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class HandPanelGestureTest {
    private val head = Vec3(0.0, 0.06, 0.60)
    private fun openHand() = HandLandmarks(
        wrist = Vec3.ZERO, palm = Vec3(0.0, 0.032, 0.0),
        thumb = FingerLandmarks(Vec3(-0.025, 0.014, 0.0), Vec3(-0.047, 0.030, 0.0), Vec3(-0.078, 0.057, 0.0), Vec3(-0.063, 0.045, 0.0)),
        index = FingerLandmarks(Vec3(-0.026, 0.065, 0.0), Vec3(-0.029, 0.095, 0.0), Vec3(-0.033, 0.130, 0.0), Vec3(-0.031, 0.113, 0.0)),
        middle = FingerLandmarks(Vec3(-0.006, 0.070, 0.0), Vec3(-0.006, 0.105, 0.0), Vec3(-0.006, 0.145, 0.0), Vec3(-0.006, 0.128, 0.0)),
        ring = FingerLandmarks(Vec3(0.014, 0.065, 0.0), Vec3(0.016, 0.095, 0.0), Vec3(0.019, 0.132, 0.0), Vec3(0.018, 0.116, 0.0)),
        little = FingerLandmarks(Vec3(0.032, 0.052, 0.0), Vec3(0.038, 0.078, 0.0), Vec3(0.044, 0.106, 0.0), Vec3(0.041, 0.094, 0.0)),
    )

    private fun curled(finger: FingerLandmarks) = finger.copy(
        knuckle = finger.base + Vec3(0.0, 0.020, 0.015),
        distal = finger.base + Vec3(0.0, 0.005, 0.027),
        tip = finger.base + Vec3(0.0, -0.020, 0.015),
    )

    private fun fist(): HandLandmarks {
        val hand = openHand()
        return hand.copy(thumb = hand.thumb.copy(tip = Vec3(0.0, 0.048, 0.01)),
            index = curled(hand.index), middle = curled(hand.middle), ring = curled(hand.ring), little = curled(hand.little))
    }

    private fun show(gesture: HandPanelGesture, hand: HandLandmarks = openHand(), target: Vec3 = head): HandPanelGestureState {
        var state = HandPanelGestureState()
        repeat(3) { state = gesture.update(hand, target, 0.05) }
        assertTrue("Open palm held for 150 ms must show the panel", state.visible)
        return state
    }

    @Test fun rightOpenPalmFacesHeadAndShowsOnlyAfter150Milliseconds() {
        val gesture = HandPanelGesture()
        repeat(2) {
            val state = gesture.update(openHand(), head, 0.05)
            assertTrue(state.validTracking)
            assertTrue(state.isOpen)
            assertTrue(state.isFacingHead)
            assertFalse(state.visible)
            assertEquals(1.0, state.palmNormal!!.z, 1e-9)
        }
        assertTrue(gesture.update(openHand(), head, 0.05).visible)
    }

    @Test fun backOfHandAndSidewaysPalmNeverShow() {
        for (target in listOf(Vec3(0.0, 0.06, -0.6), Vec3(0.6, 0.032, 0.0))) {
            val gesture = HandPanelGesture()
            repeat(20) {
                val state = gesture.update(openHand(), target, 0.05)
                assertTrue(state.isOpen)
                assertFalse(state.isFacingHead)
                assertFalse(state.visible)
            }
        }
    }

    @Test fun fistAndOneCurledFingerFailFiveFingerPredicate() {
        val open = openHand()
        for (hand in listOf(fist(), open.copy(index = curled(open.index)), open.copy(little = curled(open.little)))) {
            val gesture = HandPanelGesture()
            repeat(20) {
                val state = gesture.update(hand, head, 0.05)
                assertTrue(state.validTracking)
                assertFalse(state.isOpen)
                assertFalse(state.visible)
            }
        }
    }

    @Test fun extendedFingersWithTuckedThumbAreNotAnOpenFiveFingerHand() {
        val hand = openHand()
        val tucked = hand.copy(thumb = FingerLandmarks(Vec3(-0.025, 0.014, 0.0), Vec3(-0.016, 0.029, 0.0), Vec3(0.0, 0.055, 0.0)))
        val state = HandPanelGesture().update(tucked, head, 0.05)
        assertTrue(state.validTracking)
        assertFalse(state.isOpen)
    }

    @Test fun hookedFingerCannotPassJustBecauseItsTipIsFarFromWrist() {
        val hand = openHand()
        val hooked = hand.index.copy(knuckle = Vec3(-0.026, 0.110, 0.0), distal = Vec3(-0.026, 0.125, 0.025), tip = Vec3(-0.026, 0.105, 0.055))
        assertFalse(HandPanelGesture().update(hand.copy(index = hooked), head, 0.05).isOpen)
    }

    @Test fun closingHandHidesAfter200MillisecondsButBriefClosureDoesNotFlicker() {
        val gesture = HandPanelGesture()
        show(gesture)
        repeat(3) {
            val state = gesture.update(fist(), head, 0.05)
            assertFalse(state.isOpen)
            assertTrue(state.visible)
        }
        assertFalse(gesture.update(fist(), head, 0.05).visible)
        show(gesture)
        repeat(2) { assertTrue(gesture.update(fist(), head, 0.05).visible) }
        assertTrue(gesture.update(openHand(), head, 0.05).visible)
        repeat(3) { assertTrue(gesture.update(fist(), head, 0.05).visible) }
        assertFalse(gesture.update(fist(), head, 0.05).visible)
    }

    @Test fun entryRequiresConsecutiveValidTime() {
        val gesture = HandPanelGesture()
        repeat(2) { assertFalse(gesture.update(openHand(), head, 0.05).visible) }
        gesture.update(fist(), head, 0.01)
        repeat(2) { assertFalse(gesture.update(openHand(), head, 0.05).visible) }
        assertTrue(gesture.update(openHand(), head, 0.05).visible)
    }

    @Test fun facingAngleHasSeparateEntryAndExitThresholds() {
        val hand = openHand()
        fun headAt(dot: Double) = hand.palm + Vec3(sqrt(1.0 - dot * dot), 0.0, dot) * 0.6
        val gesture = HandPanelGesture()
        repeat(10) { assertFalse(gesture.update(hand, headAt(0.60), 0.05).visible) }
        show(gesture, hand, headAt(0.80))
        repeat(10) { assertTrue(gesture.update(hand, headAt(0.55), 0.05).visible) }
        repeat(3) { assertTrue(gesture.update(hand, headAt(0.45), 0.05).visible) }
        assertFalse(gesture.update(hand, headAt(0.45), 0.05).visible)
    }

    @Test fun trackingLossAndStaleSamplesHideImmediatelyAndResetEntryTimer() {
        val gesture = HandPanelGesture()
        show(gesture)
        val lost = gesture.update(null, head, 0.001)
        assertFalse(lost.visible)
        assertFalse(lost.validTracking)
        repeat(2) { assertFalse(gesture.update(openHand(), head, 0.05).visible) }
        assertTrue(gesture.update(openHand(), head, 0.05).visible)
        val stale = gesture.update(openHand().copy(sampleAgeSeconds = 0.121), head, 0.001)
        assertFalse(stale.visible)
        assertFalse(stale.validTracking)
    }

    @Test fun invalidJointNormalOrClockInputFailsClosed() {
        val hand = openHand()
        val invalidHands = listOf(hand.copy(wrist = Vec3(Double.NaN, 0.0, 0.0)),
            hand.copy(index = hand.index.copy(tip = Vec3(Double.POSITIVE_INFINITY, 0.0, 0.0))),
            hand.copy(palmNormal = Vec3.ZERO), hand.copy(sampleAgeSeconds = Double.NaN),
            hand.copy(little = hand.little.copy(base = hand.index.base)))
        for (invalid in invalidHands) {
            val gesture = HandPanelGesture()
            show(gesture)
            val state = gesture.update(invalid, head, 0.01)
            assertFalse(state.visible)
            assertFalse(state.validTracking)
        }
        for (dt in listOf(Double.NaN, -0.01, Double.POSITIVE_INFINITY)) {
            val gesture = HandPanelGesture()
            show(gesture)
            assertFalse(gesture.update(hand, head, dt).visible)
        }
    }

    @Test fun stalledRenderFrameDoesNotCountAsContinuousGestureTime() {
        val gesture = HandPanelGesture()
        assertFalse(gesture.update(openHand(), head, 1.0).visible)
        show(gesture)
        assertFalse(gesture.update(openHand(), head, 0.5).visible)
        repeat(2) { assertFalse(gesture.update(openHand(), head, 0.05).visible) }
        assertTrue(gesture.update(openHand(), head, 0.05).visible)
    }

    @Test fun predicateIsInvariantUnderHandScaleAndRigidStageTransforms() {
        fun rotate(p: Vec3): Vec3 {
            val aroundX = Vec3(p.x, p.y * cos(0.6) - p.z * sin(0.6), p.y * sin(0.6) + p.z * cos(0.6))
            return Vec3(aroundX.x * cos(-0.9) + aroundX.z * sin(-0.9), aroundX.y, -aroundX.x * sin(-0.9) + aroundX.z * cos(-0.9))
        }
        for (scale in listOf(0.5, 1.0, 1.8)) {
            fun transform(p: Vec3) = rotate(p * scale) + Vec3(2.3, 1.2, -4.7)
            fun finger(f: FingerLandmarks) = FingerLandmarks(transform(f.base), transform(f.knuckle), transform(f.tip), f.distal?.let(::transform))
            val hand = openHand()
            val transformed = hand.copy(wrist = transform(hand.wrist), palm = transform(hand.palm),
                thumb = finger(hand.thumb), index = finger(hand.index), middle = finger(hand.middle), ring = finger(hand.ring), little = finger(hand.little))
            assertTrue(show(HandPanelGesture(), transformed, transform(head)).visible)
        }
    }

    @Test fun explicitOutwardPalmNormalIsNormalizedAndItsSignIsRespected() {
        val hand = openHand()
        val state = show(HandPanelGesture(), hand.copy(palmNormal = Vec3(0.0, 0.0, 7.0)))
        assertEquals(Vec3(0.0, 0.0, 1.0), state.palmNormal)
        val gesture = HandPanelGesture()
        repeat(10) { assertFalse(gesture.update(hand.copy(palmNormal = Vec3(0.0, 0.0, -7.0)), head, 0.05).visible) }
    }

    @Test fun optionalDistalJointsAndCustomTimingAreSupported() {
        val hand = openHand()
        val reduced = hand.copy(thumb = hand.thumb.copy(distal = null), index = hand.index.copy(distal = null),
            middle = hand.middle.copy(distal = null), ring = hand.ring.copy(distal = null), little = hand.little.copy(distal = null))
        val gesture = HandPanelGesture(HandPanelGestureConfig(showSeconds = 0.12, hideSeconds = 0.18))
        repeat(2) { assertFalse(gesture.update(reduced, head, 0.04).visible) }
        assertTrue(gesture.update(reduced, head, 0.04).visible)
        repeat(2) { assertTrue(gesture.update(fist(), head, 0.06).visible) }
        assertFalse(gesture.update(fist(), head, 0.06).visible)
        gesture.reset()
        assertFalse(gesture.update(reduced, head, 0.04).visible)
    }
}
