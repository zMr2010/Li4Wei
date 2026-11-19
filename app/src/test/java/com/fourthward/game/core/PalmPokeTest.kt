package com.fourthward.game.core

import org.junit.Assert.*
import org.junit.Test

class PalmPokeTest {
    private fun PalmPoke.sample(x: Double = 0.25, y: Double = 0.5, depth: Double, valid: Boolean = true, dt: Double = 0.016) =
        update(x, y, depth, valid, dt)

    @Test fun sharedLayoutHasThreeSeparatedTargetsAndNoGapTarget() {
        assertEquals(PalmAction.RESET_ANGLE, PalmPanelLayout.hitTest(0.25, 0.5))
        assertEquals(PalmAction.TOGGLE_REFERENCE, PalmPanelLayout.hitTest(0.5, 0.5))
        assertEquals(PalmAction.CONTEXT, PalmPanelLayout.hitTest(0.82, 0.5))
        assertNull(PalmPanelLayout.hitTest(0.34, 0.5))
        assertNull(PalmPanelLayout.hitTest(0.66, 0.5))
        assertNull(PalmPanelLayout.hitTest(-0.1, 0.5))
        assertNull(PalmPanelLayout.hitTest(Double.NaN, 0.5))
    }

    @Test fun frontApproachFiresExactlyOnceAndWithdrawRearms() {
        val detector = PalmPoke()
        assertNull(detector.sample(depth = 0.04).action)
        assertNull(detector.sample(depth = 0.02).action)
        val contact = detector.sample(depth = 0.006)
        assertEquals(PalmAction.RESET_ANGLE, contact.action)
        assertEquals(PalmAction.RESET_ANGLE, contact.pressedAction)
        repeat(10) { assertNull(detector.sample(depth = 0.006).action) }
        assertNull(detector.sample(depth = 0.02).action)
        assertNull(detector.sample(depth = 0.006).action)
        detector.sample(depth = 0.031)
        assertEquals(PalmAction.RESET_ANGLE, detector.sample(depth = 0.008).action)
    }

    @Test fun lessonAndGameOfferThemeSwitchWithoutLosingTheRouteButton() {
        for (layout in listOf(PalmPanelLayout.lessonMenu, PalmPanelLayout.game)) {
            val theme = layout.buttons.single { it.action == PalmAction.TOGGLE_THEME }
            val x = (theme.left + theme.right) / 2
            val y = (theme.top + theme.bottom) / 2
            val poke = PalmPoke()
            poke.update(x, y, .045, true, .016, layout)
            assertEquals(PalmAction.TOGGLE_THEME, poke.update(x, y, .006, true, .016, layout).action)
            assertNull(poke.update(x, y, .006, true, .016, layout).action)
            assertTrue(layout.buttons.any { it.action == PalmAction.LEVEL_LIST })
        }
    }

    @Test fun firstSampleAtOrBehindPlaneCannotFire() {
        for (depth in listOf(0.0, -0.01, -0.1, 0.008)) {
            val detector = PalmPoke()
            assertNull(detector.sample(depth = depth).action)
            detector.sample(depth = 0.02)
            assertNull(detector.sample(depth = 0.004).action)
        }
    }

    @Test fun heldContactCannotActivateAnotherButton() {
        val detector = PalmPoke()
        detector.sample(depth = 0.04)
        assertEquals(PalmAction.RESET_ANGLE, detector.sample(depth = 0.004).action)
        assertNull(detector.sample(x = 0.34, depth = 0.004).action)
        val other = detector.sample(x = 0.5, depth = 0.004)
        assertEquals(PalmAction.TOGGLE_REFERENCE, other.hoveredAction)
        assertNull(other.action)
        assertNull(other.pressedAction)
        detector.sample(x = 0.5, depth = 0.04)
        assertEquals(PalmAction.TOGGLE_REFERENCE, detector.sample(x = 0.5, depth = 0.004).action)
    }

    @Test fun missingInTheGapCannotBecomeAContactBySlidingBehindPlane() {
        val detector = PalmPoke()
        detector.sample(x = 0.34, depth = 0.04)
        assertNull(detector.sample(x = 0.34, depth = 0.004).action)
        assertNull(detector.sample(x = 0.25, depth = 0.004).action)
        detector.sample(x = 0.25, depth = 0.04)
        assertEquals(PalmAction.RESET_ANGLE, detector.sample(depth = 0.004).action)
    }

    @Test fun offPanelOrLostTrackingRequiresFreshApproach() {
        for (resetSample in listOf<(PalmPoke) -> PalmPokeResult>(
            { it.sample(x = -0.1, depth = 0.02) },
            { it.sample(y = 1.1, depth = 0.02) },
            { it.sample(depth = 0.02, valid = false) },
        )) {
            val detector = PalmPoke()
            detector.sample(depth = 0.04)
            assertEquals(PalmPokeResult(), resetSample(detector))
            assertNull(detector.sample(depth = 0.004).action)
        }
    }

    @Test fun crossingTheGapThenLandingBehindAButtonDoesNotFire() {
        val detector = PalmPoke()
        detector.sample(x = 0.25, depth = 0.04)
        // The segment crosses the 8mm contact boundary at x=0.342..., in the first gap.
        assertNull(detector.sample(x = 0.42, depth = -0.019).action)
        assertNull(detector.sample(x = 0.42, depth = 0.004).action)
    }

    @Test fun staleInvalidOrZeroTimeSamplesResetArming() {
        for (dt in listOf(0.0, -0.01, 0.121, Double.NaN, Double.POSITIVE_INFINITY)) {
            val detector = PalmPoke()
            detector.sample(depth = 0.04)
            assertEquals(PalmPokeResult(), detector.sample(depth = 0.004, dt = dt))
            assertNull(detector.sample(depth = 0.004).action)
        }
    }

    @Test fun invalidCoordinatesAndDepthResetArming() {
        for (sample in listOf<(PalmPoke) -> PalmPokeResult>(
            { it.sample(x = Double.NaN, depth = 0.004) },
            { it.sample(y = Double.POSITIVE_INFINITY, depth = 0.004) },
            { it.sample(depth = Double.NaN) },
        )) {
            val detector = PalmPoke()
            detector.sample(depth = 0.04)
            assertEquals(PalmPokeResult(), sample(detector))
            assertNull(detector.sample(depth = 0.004).action)
        }
    }

    @Test fun teleportMotionCannotTriggerPlaneContact() {
        val detector = PalmPoke()
        detector.sample(depth = 0.25)
        assertEquals(PalmPokeResult(), detector.sample(depth = 0.004))
        assertNull(detector.sample(depth = 0.004).action)
        detector.sample(depth = 0.04)
        assertEquals(PalmAction.RESET_ANGLE, detector.sample(depth = 0.004).action)
    }

    @Test fun deepPenetrationIsRejectedAndRequiresWithdrawal() {
        val detector = PalmPoke()
        detector.sample(depth = 0.04)
        assertNull(detector.sample(depth = -0.021).action)
        assertNull(detector.sample(depth = 0.004).action)
        detector.sample(depth = 0.04)
        assertEquals(PalmAction.RESET_ANGLE, detector.sample(depth = -0.019).action)
    }

    @Test fun hoverAndPressedOnlyDescribeActualNearbyTip() {
        val detector = PalmPoke()
        assertNull(detector.sample(depth = 0.09).hoveredAction)
        assertEquals(PalmAction.RESET_ANGLE, detector.sample(depth = 0.05).hoveredAction)
        assertEquals(PalmAction.RESET_ANGLE, detector.sample(depth = 0.005).pressedAction)
        assertNull(detector.sample(depth = 0.015).pressedAction)
        assertNull(detector.sample(depth = -0.03).hoveredAction)
    }

    @Test fun explicitResetForgetsContactAndApproach() {
        val detector = PalmPoke()
        detector.sample(depth = 0.04)
        detector.reset()
        assertNull(detector.sample(depth = 0.004).action)
    }

    @Test fun everyPageUsesTheSameNonoverlappingTargetsForRenderingAndPoking() {
        for (layout in listOf(PalmPanelLayout.lesson, PalmPanelLayout.lessonMenu, PalmPanelLayout.game, PalmPanelLayout.levels)) {
            for (button in layout.buttons) {
                val x = (button.left + button.right) / 2
                val y = (button.top + button.bottom) / 2
                assertEquals(button.action, layout.hitTest(x, y))
                val detector = PalmPoke()
                detector.update(x, y, .04, true, .016, layout)
                assertEquals(button.action, detector.update(x, y, .004, true, .016, layout).action)
                for (other in layout.buttons.filter { it != button }) {
                    assertFalse(other.contains(x, y))
                }
            }
            assertNull(layout.hitTest(.5, .05))
            assertNull(layout.hitTest(-.01, .5))
            assertEquals(layout.widthMeters * layout.heightDp / layout.widthDp, layout.heightMeters, 1e-12)
        }
    }

    @Test fun lockedLevelCannotHoverPressOrActivateAndUnlockNeedsFreshApproach() {
        val detector = PalmPoke()
        val layout = PalmPanelLayout.levels
        val disabled = setOf(PalmAction.LEVEL_2, PalmAction.LEVEL_3)
        detector.update(.75, .285, .04, true, .016, layout, disabled)
        assertEquals(PalmPokeResult(), detector.update(.75, .285, .004, true, .016, layout, disabled))
        // A progress update cannot turn an already touching fingertip into a click.
        assertNull(detector.update(.75, .285, .004, true, .016, layout).action)
        detector.update(.75, .285, .04, true, .016, layout)
        assertEquals(PalmAction.LEVEL_2, detector.update(.75, .285, .004, true, .016, layout).action)
    }

    @Test fun switchingPagesDropsArmedOrHeldContactUntilAnewApproach() {
        val detector = PalmPoke()
        detector.update(.75, .73, .04, true, .016, PalmPanelLayout.game)
        assertEquals(PalmAction.LEVEL_LIST, detector.update(.75, .73, .004, true, .016, PalmPanelLayout.game).action)
        assertNull(detector.update(.75, .73, .004, true, .016, PalmPanelLayout.levels).action)
        detector.update(.75, .73, .04, true, .016, PalmPanelLayout.levels)
        // Even an armed approach from the old page is not carried to a replacement page.
        assertNull(detector.update(.75, .73, .004, true, .016, PalmPanelLayout.game).action)
        detector.update(.75, .73, .04, true, .016, PalmPanelLayout.game)
        assertEquals(PalmAction.LEVEL_LIST, detector.update(.75, .73, .004, true, .016, PalmPanelLayout.game).action)
    }

    @Test fun discontinuityUsesCurrentPanelsPhysicalSize() {
        val large = PalmPanelSpec(400, 400, 1.0, listOf(NormalizedButtonRect(PalmAction.BACK, 0.0, 0.0, 1.0, 1.0)))
        val detector = PalmPoke()
        detector.update(.1, .5, .04, true, .016, large)
        // 30 cm across the panel is a discontinuity, even though legacy dimensions would accept it.
        assertEquals(PalmPokeResult(), detector.update(.4, .5, .004, true, .016, large))
    }

    @Test fun chapterTreeAndSixLevelGridHaveDistinctPhysicalTargets() {
        val tree = PalmPanelLayout.chapters
        assertEquals(PalmAction.LEVEL_1, tree.hitTest(.5, .30))
        assertEquals(PalmAction.LEVEL_2, tree.hitTest(.26, .67))
        assertEquals(PalmAction.LEVEL_3, tree.hitTest(.74, .67))
        assertNull(tree.hitTest(.5, .485)) // The connector is not an invisible button.
        val grid = PalmPanelLayout.chapterLevels(6)
        assertEquals(PalmPanelLayout.levelActions.take(6) + PalmAction.BACK, grid.buttons.map { it.action })
        assertEquals(PalmAction.LEVEL_6, grid.hitTest(.74, .70))
        assertEquals(PalmAction.BACK, grid.hitTest(.5, .9))
        assertEquals(4, PalmPanelLayout.chapterLevels(3).buttons.size)
        val detector = PalmPoke()
        val disabled = setOf(PalmAction.LEVEL_6)
        detector.update(.74, .70, .04, true, .016, grid, disabled)
        assertNull(detector.update(.74, .70, .004, true, .016, grid, disabled).action)
    }

    @Test fun groupChangeResetDropsContactEvenWhenLayoutAndDisabledSetAreUnchanged() {
        val layout = PalmPanelLayout.levels
        val detector = PalmPoke()
        detector.update(.26, .285, .04, true, .016, layout)
        // The bridge calls reset when changing the group; the row rectangles stay identical.
        detector.reset()
        assertNull(detector.update(.26, .285, .004, true, .016, layout).action)
        detector.update(.26, .285, .04, true, .016, layout)
        assertEquals(PalmAction.LEVEL_1, detector.update(.26, .285, .004, true, .016, layout).action)
    }
}
