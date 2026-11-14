package com.fourthward.game.vr

import org.junit.Assert.*
import org.junit.Test

class UiMotionStateTest {
    private class Clock {
        val motion = UiMotionState()
        var now = 0L
        fun frame(visible: Boolean = true, page: String = "map", count: Int = 3, active: Boolean = true, dt: Double = .02): UiMotionSample {
            now += (dt * 1_000_000_000).toLong()
            return motion.update(now, active, visible, page, count)
        }
        fun settle(page: String = "map", count: Int = 3): UiMotionSample {
            var result = frame(page = page, count = count)
            repeat(40) { result = frame(page = page, count = count) }
            return result
        }
    }

    @Test fun revealStaggersCardsAndEventuallyAllowsContact() {
        val c = Clock()
        c.frame()
        val sample = c.frame(dt = .1)
        assertTrue(sample.cardOpacity(0) > sample.cardOpacity(2))
        assertFalse(c.motion.interactionReady("map"))
        val settled = c.settle()
        assertEquals(1.0, settled.panelOpacity, 1e-9)
        assertEquals(1.0, settled.cardOpacity(2), 1e-9)
        assertEquals(0.0, settled.cardOffsetY(2), 1e-9)
        assertTrue(c.motion.interactionReady("map"))
    }

    @Test fun oldPageRemainsUntilInvisibleAndNewPageStartsInvisible() {
        val c = Clock(); c.settle()
        val start = c.frame(page = "levels", count = 7)
        assertEquals("map", start.pageKey)
        assertFalse(c.motion.interactionReady("map"))
        repeat(4) { assertEquals("map", c.frame(page = "levels", count = 7).pageKey) }
        var switch: UiMotionSample? = null
        repeat(4) {
            val s = c.frame(page = "levels", count = 7)
            if (s.pageKey == "levels" && switch == null) switch = s
        }
        assertNotNull(switch)
        assertEquals(0.0, switch!!.panelOpacity, 1e-8)
        assertEquals(0.0, switch!!.cardOpacity(0), 1e-8)
        c.settle(page = "levels", count = 7)
        assertTrue(c.motion.interactionReady("levels"))
    }

    @Test fun requestingThirdPageWhileLeavingSkipsObsoletePage() {
        val c = Clock(); c.settle()
        c.frame(page = "old-request")
        repeat(3) { c.frame(page = "old-request") }
        val pages = (0..12).map { c.frame(page = "latest").pageKey }
        assertFalse(pages.contains("old-request"))
        assertTrue(pages.contains("latest"))
        c.settle("latest")
        assertTrue(c.motion.interactionReady("latest"))
    }

    @Test fun leavingDuringEntranceDoesNotPopInUnrevealedCards() {
        val c = Clock(); c.frame(count = 7)
        repeat(4) { c.frame(count = 7) }
        val before = c.frame(count = 7, dt = 0.0)
        val leaving = c.frame(page = "other", count = 7, dt = 0.0)
        assertEquals(before.cardOpacity(5), leaving.cardOpacity(5), 1e-9)
        repeat(4) {
            val sample = c.frame(page = "other", count = 7)
            assertEquals(0.0, sample.cardOpacity(5), 1e-9)
        }
    }

    @Test fun returningToCurrentPageReversesFadeWithoutOpacityJump() {
        val c = Clock(); c.settle()
        c.frame(page = "levels")
        repeat(3) { c.frame(page = "levels") }
        val before = c.frame(page = "levels", dt = 0.0)
        val reversed = c.frame(page = "map", dt = 0.0)
        assertEquals(before.panelOpacity, reversed.panelOpacity, 1e-9)
        assertEquals("map", reversed.pageKey)
        assertEquals(1.0, c.settle().panelOpacity, 1e-9)
    }

    @Test fun closeAndReopenStartsAtDisplayedOpacity() {
        val c = Clock(); c.settle()
        c.frame(visible = false)
        repeat(4) { c.frame(visible = false) }
        val before = c.frame(visible = false, dt = 0.0)
        val reopened = c.frame(visible = true, dt = 0.0)
        assertEquals(before.visibility, reopened.visibility, 1e-9)
        assertTrue(reopened.visibility in .01.. .99)
        assertEquals(1.0, c.settle().visibility, 1e-9)
        repeat(20) { c.frame(visible = false) }
        assertEquals(0.0, c.frame(visible = false).panelOpacity, 1e-9)
        assertFalse(c.motion.interactionReady("map"))
    }

    @Test fun pausedTimeDoesNotAdvanceAndResumeDoesNotCatchUp() {
        val c = Clock(); c.frame(); c.frame(dt = .1)
        val before = c.motion.clock
        repeat(10) { c.frame(active = false, dt = 4.0) }
        assertEquals(before, c.motion.clock, 1e-9)
        assertFalse(c.motion.interactionReady("map"))
        c.frame(active = true, dt = 4.0)
        assertEquals(before, c.motion.clock, 1e-9)
        c.frame(dt = .02)
        assertEquals(before + .02, c.motion.clock, 1e-9)
    }

    @Test fun largeDeltaIsBoundedAndBackwardsClockNeverRewinds() {
        val c = Clock(); c.frame()
        c.frame(dt = 60.0)
        assertEquals(.1, c.motion.clock, 1e-9)
        val state = c.motion.update(c.now - 1_000_000_000L, true, true, "map", 3)
        assertEquals(.1, state.clock, 1e-9)
        assertTrue(state.panelOpacity.isFinite())
    }

    @Test fun hiddenPageCanBeReplacedWithoutShowingObsoleteContent() {
        val c = Clock()
        c.frame(visible = false)
        val s = c.frame(visible = false, page = "levels")
        assertEquals("levels", s.pageKey)
        assertEquals(0.0, s.panelOpacity, 1e-9)
        c.settle("levels")
        assertTrue(c.motion.interactionReady("levels"))
    }

    @Test fun sevenCardsMustSettleBeforePokeIsEnabled() {
        val c = Clock(); c.frame(count = 7)
        repeat(20) { c.frame(count = 7) }
        assertFalse(c.motion.interactionReady("map"))
        repeat(10) { c.frame(count = 7) }
        assertTrue(c.motion.interactionReady("map"))
    }

    @Test fun pressIsBoundedAndReleaseReturnsToHoverPlane() {
        val m = UiButtonMotion()
        m.update(0.0, true, false, true, 0)
        assertEquals(.004, m.update(.2, true, false, true, 0).liftMeters, 1e-9)
        m.update(.21, true, true, true, 1)
        assertEquals(-.0007, m.update(.30, true, true, true, 1).liftMeters, 1e-9)
        m.update(.31, true, false, true, 1)
        val released = m.update(.55, true, false, true, 1)
        assertEquals(.004, released.liftMeters, 1e-9)
        m.update(.56, true, false, false, 1)
        assertEquals(0.0, m.update(.80, true, false, false, 1).liftMeters, 1e-9)
    }

    @Test fun hoverSweepsOnceAndReenterRestartsWithoutLiftJump() {
        val m = UiButtonMotion()
        assertEquals(0.0, m.update(0.0, true, false, true, 0).sweep, 1e-9)
        assertEquals(.004, m.update(.12, true, false, true, 0).liftMeters, 1e-9)
        assertEquals(1.0, m.update(.43, true, false, true, 0).sweep, 1e-9)
        assertEquals(1.0, m.update(1.0, true, false, true, 0).sweep, 1e-9)
        m.update(1.01, false, false, true, 0)
        val leaving = m.update(1.10, false, false, true, 0)
        val returning = m.update(1.10, true, false, true, 0)
        assertEquals(leaving.liftMeters, returning.liftMeters, 1e-9)
        assertEquals(0.0, returning.sweep, 1e-9)
        assertEquals(.004, m.update(1.23, true, false, true, 0).liftMeters, 1e-9)
        m.update(1.24, false, false, true, 0)
        assertEquals(0.0, m.update(1.45, false, false, true, 0).liftMeters, 1e-9)
    }

    @Test fun disabledHoverNeitherRaisesFaceNorStartsScan() {
        val m = UiButtonMotion()
        val inactive = m.update(0.0, true, true, false, 1)
        assertEquals(0.0, inactive.liftMeters, 1e-9)
        assertEquals(1.0, inactive.sweep, 1e-9)
        assertEquals(0.0, m.update(.3, true, true, false, 1).liftMeters, 1e-9)
        assertEquals(0.0, m.update(.4, true, false, true, 1).sweep, 1e-9)
    }

    @Test fun depthRevealStaysWithinEightMillimetresAndSettlesAtContactPlane() {
        val c = Clock()
        repeat(45) {
            val sample = c.frame(count = 7)
            for (index in 0..6) {
                val z = sample.panelOffsetNormal + sample.cardOffsetNormal(index)
                assertTrue(z >= -.008 - 1e-9 && z <= 1e-9)
            }
        }
        val settled = c.frame(count = 7)
        assertEquals(0.0, settled.panelOffsetNormal, 1e-9)
        (0..6).forEach { assertEquals(0.0, settled.cardOffsetNormal(it), 1e-9) }
        assertTrue(c.motion.interactionReady("map"))
    }

    @Test fun repeatFeedbackRestartsSweepWithoutMovingContactPlane() {
        val m = UiButtonMotion()
        m.update(0.0, true, true, true, 1)
        assertTrue(m.update(.2, true, true, true, 1).sweep > .0)
        assertEquals(0.0, m.update(.2, true, true, true, 2).sweep, 1e-9)
        assertEquals(-.0007, m.update(.3, true, true, true, 2).liftMeters, 1e-9)
    }

    @Test fun focusBracketMovesContinuouslyBetweenTargets() {
        val m = UiFocusMotion()
        val first = listOf(.1, .2, .4, .4)
        val second = listOf(.6, .6, .9, .8)
        m.update(0.0, first)
        assertEquals(first, m.update(.3, first).take(4))
        assertEquals(first, m.update(.31, second).take(4))
        val middle = m.update(.4, second)
        assertTrue(middle[0] > .1 && middle[0] < .6)
        assertEquals(second, m.update(.6, second).take(4))
        m.update(.61, null)
        assertEquals(0.0, m.update(.8, null)[4], 1e-9)
    }

    @Test fun angleTrailCrossesZeroUsingShortPath() {
        val m = UiAngleTrail()
        assertEquals(359.0, m.update(359.0, 0.0), 1e-9)
        val next = m.update(1.0, .02)
        assertTrue(next > 359.0 || next < 1.0)
        repeat(50) { m.update(1.0, .04 + it * .02) }
        assertEquals(1.0, m.update(1.0, 2.0), .03)
    }
}
