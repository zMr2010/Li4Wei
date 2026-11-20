package com.fourthward.game.vr

import com.fourthward.game.core.FingerLandmarks
import com.fourthward.game.core.FourthwardGame
import com.fourthward.game.core.HandLandmarks
import com.fourthward.game.core.LevelDependencyGraph
import com.fourthward.game.core.LevelMapWindow
import com.fourthward.game.core.PalmPanelSpec
import com.fourthward.game.core.TutorialLevels
import com.fourthward.game.core.PalmAction
import com.fourthward.game.core.PalmPanelLayout
import com.fourthward.game.core.Vec3
import org.junit.Assert.*
import org.junit.Test

class VrHandControlsTest {
    private val head = VrTrackingMath.Pose(Vec3(0.0, 1.65, 0.0), Vec3(1.0, 0.0, 0.0),
        Vec3(0.0, 1.0, 0.0), Vec3(0.0, 0.0, 1.0))
    private val reach = Vec3(0.0, 1.48, -.55)
    private val layout = PalmPanelLayout.game
    private val target = PalmAction.RESET_ANGLE

    @Test fun forwardRequiresAFreshGripInFrontAndMeasuresOnlyNewRetraction() {
        val hand = openHand(reach, left = true)
        val closed = fist(hand)
        val controls = VrHandControls()
        assertFalse(update(controls, left = closed).pulling)
        assertEquals(0.0, update(controls, left = shift(closed, Vec3(0.0, 0.0, .05))).forwardMeters, 0.0)
        update(controls, left = hand)
        val grabbed = update(controls, left = closed)
        assertTrue(grabbed.pulling)
        assertEquals(0.0, grabbed.forwardMeters, 0.0)
        fun pullTo(z: Double) = update(controls, left = shift(closed, Vec3(0.0, 0.0, z))).forwardMeters
        assertEquals(.045 * 2.4, pullTo(.05), 1e-9)
        assertEquals(.10 * 2.4, pullTo(.15), 1e-9)
        assertEquals(0.0, pullTo(.05), 1e-9)
        assertEquals(0.0, pullTo(.15), 1e-9)
        assertEquals(.03 * 2.4, pullTo(.18), 1e-9)
        assertFalse(update(controls, left = shift(hand, Vec3(0.0, 0.0, .18))).pulling)

        val near = openHand(reach.copy(z = -.15), left = false)
        update(controls, right = near)
        assertFalse(update(controls, right = fist(near)).pulling)
    }

    @Test fun aHeldPullSteersWithCurrentHeadYawAndPitchAndDoesNotMeasureHeadOnlyMotion() {
        val controls = VrHandControls()
        val open = openHand(reach, left = true)
        val closed = fist(open)
        val game = FourthwardGame()
        game.restartLevel(head.position)
        update(controls, left = open)
        update(controls, left = closed)
        val first = update(controls, left = shift(closed, Vec3(0.0, 0.0, .08)))
        game.move(VrTrackingMath.pullMovement(head, first.forwardMeters))
        assertEquals(-.075 * 2.4, game.playerFootWorld().w, 1e-9)
        val turned = head.copy(right = Vec3(0.0, 0.0, -1.0),
            up = Vec3(.6, .8, 0.0), normal = Vec3(.8, -.6, 0.0))
        val still = update(controls, view = turned, left = shift(closed, Vec3(0.0, 0.0, .08)))
        assertTrue(still.pulling)
        assertEquals(0.0, still.forwardMeters, 0.0)
        val second = update(controls, view = turned, left = shift(closed, Vec3(0.0, 0.0, .16)))
        game.move(VrTrackingMath.pullMovement(turned, second.forwardMeters))
        assertEquals(-.08 * 2.4 * .8, game.playerFootWorld().x, 1e-9)
        assertEquals(.08 * 2.4 * .6, game.playerFootWorld().z, 1e-9)
        assertEquals(-.075 * 2.4, game.playerFootWorld().w, 1e-9)
        assertEquals(0.0, game.rotationDegrees, 0.0)
        val translated = turned.copy(position = Vec3(0.0, 1.65, -.08))
        assertEquals(0.0, update(controls, view = translated, left = shift(closed, Vec3(0.0, 0.0, .16))).forwardMeters, 0.0)
        assertFalse(update(controls).pulling)
        assertFalse(update(controls, left = shift(closed, Vec3(0.0, 0.0, .20))).pulling)
    }

    @Test fun onlyTheExtendedRightHandWithPalmFacingForwardSwipesLikeQAndE() {
        val controls = VrHandControls()
        val left = openHand(reach, left = true, forwardPalm = true)
        repeat(5) { assertFalse(update(controls, left = left).rotation.active) }
        val close = openHand(reach.copy(z = -.16), left = false, forwardPalm = true)
        repeat(5) { assertFalse(update(controls, right = close).rotation.active) }
        val towardHead = openHand(reach, left = false)
        repeat(5) { assertFalse(update(controls, right = towardHead).rotation.active) }
        val right = openHand(reach, left = false, forwardPalm = true)
        repeat(4) { update(controls, right = right) }
        val toRight = update(controls, right = shift(right, Vec3(.04, 0.0, 0.0)))
        val toLeft = update(controls, right = shift(right, Vec3(-.04, 0.0, 0.0)))
        assertTrue(toRight.rotation.active && toRight.rotation.deltaRadians > 0.0)
        assertTrue(toLeft.rotation.active && toLeft.rotation.deltaRadians < 0.0)
        assertNull(toRight.panel)
    }

    @Test fun eitherPalmFacingTheHeadOrUpOpensAfterThreeSecondsAndThePanelStaysPinned() {
        for (left in listOf(false, true)) {
            for (upward in listOf(false, true)) {
                val controls = VrHandControls()
                // A horizontal hand level with the head has no head-facing component.
                val at = if (upward) reach.copy(y = head.position.y) else reach
                fun orientedHand(palmForward: Boolean): HandLandmarks {
                    val hand = openHand(at, left, forwardPalm = palmForward)
                    fun tilt(v: Vec3) = Vec3(v.x, v.z, -v.y)
                    return if (upward) transform(hand, { at + tilt(it - at) }, ::tilt) else hand
                }
                fun sample(palm: HandLandmarks) = update(controls, left = palm.takeIf { left }, right = palm.takeUnless { left },
                    panelHoldDeltaSeconds = .025)
                val wrongSide = orientedHand(palmForward = true)
                repeat(125) { assertNull(sample(wrongSide).panel) }
                val palm = orientedHand(palmForward = false)
                // The simulated frame takes 50 ms, but real elapsed time advances by 25 ms.
                repeat(120) { assertNull(sample(palm).panel) }
                val frame = sample(palm)
                assertTrue(frame.opened)
                val panel = checkNotNull(frame.panel)
                val palmNormal = checkNotNull(palm.palmNormal)
                val expected = at + palmNormal * (.04 / palmNormal.length())
                assertEquals(0.0, (panel.pose.position - expected).length(), 1e-9)

                val moved = head.copy(position = Vec3(.37, 1.60, -.2))
                assertEquals(panel, update(controls, view = moved).panel)
                controls.resetInput()
                assertEquals(panel, update(controls, view = moved).panel)
            }
        }
    }

    @Test fun newLessonUsesLastPlacementUntilASummonSetsANewPositionAndOrientation() {
        val controls = VrHandControls()
        val defaultDock = VrHandControls.Panel(checkNotNull(VrTrackingMath.startPanelPose(head)), scale = 1.6)
        assertEquals(defaultDock, controls.lessonPanel(defaultDock))
        val placed = openPanel(controls)
        controls.reset()
        val movedHead = head.copy(position = head.position + Vec3(.30, -.03, .06))
        val movedHand = openHand(reach + Vec3(.19, .04, .05), left = false)
        assertNull(update(controls, view = movedHead).panel)
        fun sample(palm: HandLandmarks?) = controls.update(.05, movedHead, null, palm,
            controls.lessonPanel(defaultDock), PalmPanelLayout.lessonMenu, emptySet(),
            interactionReady = true, allowMotion = false, panelHoldDeltaSeconds = .05)
        assertEquals(placed, sample(null).panel)
        repeat(60) { assertEquals(placed, sample(movedHand).panel) }
        val recalled = sample(movedHand)
        assertTrue(recalled.opened)
        val expected = VrTrackingMath.panelPose(movedHand.palm + checkNotNull(movedHand.palmNormal) * .04, movedHead)
        assertEquals(expected, recalled.panel?.pose)
        assertNotEquals(placed, recalled.panel)
        controls.reset()
        assertEquals(recalled.panel, sample(null).panel)
    }

    @Test fun aNewPalmHoldRecallsAnAlreadyOpenPanelAndOneContinuousHoldPlacesItOnlyOnce() {
        for (recallWithLeft in listOf(false, true)) {
            val controls = VrHandControls()
            val hand = openHand(reach, left = false)
            val original = openAfterThreeSeconds { update(controls, right = hand) }.panel
            repeat(100) { i ->
                val moved = shift(hand, Vec3(.002 * i, 0.0, 0.0))
                val held = update(controls, right = moved)
                assertFalse(held.opened)
                assertEquals(original, held.panel)
            }
            assertEquals(original, update(controls).panel)
            // The old panel is behind the wearer. The summon needs no contact with it.
            val turned = head.copy(right = head.right * -1.0, normal = head.normal * -1.0)
            val palm = openHand(Vec3(.1, 1.4, .5), recallWithLeft, forwardPalm = true)
            fun sample(hand: HandLandmarks) = update(controls, view = turned,
                left = hand.takeIf { recallWithLeft }, right = hand.takeUnless { recallWithLeft })
            repeat(60) { assertEquals(original, sample(palm).panel) }
            val finalPalm = shift(palm, Vec3(.02, 0.0, 0.0))
            val recalled = sample(finalPalm)
            assertTrue(recalled.opened)
            assertNull(recalled.action)
            val expected = VrTrackingMath.panelPose(finalPalm.palm + checkNotNull(finalPalm.palmNormal) * .04, turned)
            assertEquals(expected, recalled.panel?.pose)
            assertEquals(recalled.panel, update(controls, view = turned).panel)
        }
    }

    @Test fun recallingAMapOnPinchReleaseDoesNotSelectTheOldCard() {
        val controls = VrHandControls()
        val dock = openPanel(controls)
        val map = LevelDependencyGraph(TutorialLevels.all()).window(0).layout
        val aim = pointingHand(dock, .08, action = PalmAction.LEVEL_1, layout = map)
        val grippingCard = pinch(aim)
        val summon = openHand(reach + Vec3(.27, 0.0, 0.0), left = false)
        fun sample(pointer: HandLandmarks) = controls.update(.05, head, pointer, summon, null, map, emptySet(),
            interactionReady = true, allowMotion = false, panelHoldDeltaSeconds = .05, allowMapDrag = true)
        assertEquals(PalmAction.LEVEL_1, sample(aim).hovered)
        repeat(59) { assertEquals(PalmAction.LEVEL_1, sample(grippingCard).pressed) }
        val recalled = sample(aim)
        assertTrue(recalled.opened)
        assertNotEquals(dock, recalled.panel)
        assertNull(recalled.action)
        assertEquals(0, recalled.mapDragRows)
        assertNull(sample(aim).action)
    }

    @Test fun aSummonCanRelocateAVisibleDockThatCannotBeDismissed() {
        val controls = VrHandControls()
        val dock = VrHandControls.Panel(checkNotNull(VrTrackingMath.startPanelPose(head)))
        val palm = openHand(reach, left = false)
        fun sample(hand: HandLandmarks) = controls.update(.05, head, null, hand, dock, layout, emptySet(),
            interactionReady = true, allowMotion = false, panelHoldDeltaSeconds = .05, allowPanelDismiss = false)
        repeat(60) { assertEquals(dock, sample(palm).panel) }
        val recalled = sample(palm)
        assertTrue(recalled.opened)
        assertNotEquals(dock.pose, recalled.panel?.pose)
        val close = sample(fist(palm))
        assertFalse(close.closed)
        assertEquals(recalled.panel, close.panel)
    }

    @Test fun interruptingThePoseRestartsTheThreeSecondHold() {
        val palm = openHand(reach, left = false)
        for (interruption in listOf(null, fist(palm), openHand(reach, left = false, forwardPalm = true))) {
            val controls = VrHandControls()
            repeat(40) { assertNull(update(controls, right = palm).panel) }
            assertNull(update(controls, right = interruption).panel)
            openAfterThreeSeconds { update(controls, right = palm) }
        }
    }

    @Test fun closingSuppressesTheOtherHeldPalmAndRequiresAFreshFullHoldToReopenAtTheSameSpot() {
        val orientations = listOf<(Vec3) -> Vec3>(
            { it }, { Vec3(it.z, it.y, -it.x) }, { Vec3(-it.x, it.y, -it.z) },
            { Vec3(it.x, it.z, -it.y) }, { Vec3(it.x, -it.z, it.y) },
        )
        for (leftCloses in listOf(false, true)) for (orient in orientations) {
            val controls = VrHandControls()
            val panel = openPanel(controls, left = !leftCloses)
            val far = openHand(panel.pose.position + Vec3(.34, 0.0, 0.0), leftCloses)
            val heldOpen = openHand(reach, left = !leftCloses)
            fun use(closingHand: HandLandmarks) =
                if (leftCloses) update(controls, left = closingHand, right = heldOpen)
                else update(controls, left = heldOpen, right = closingHand)
            assertEquals(panel, use(far).panel)
            assertEquals(panel, use(fist(far)).panel)
            val at = panel.pose.position
            fun oriented(hand: HandLandmarks) = transform(hand, { at + orient(it - at) }, orient)
            val palm = openHand(at, leftCloses)
            val near = oriented(palm)
            use(near)
            use(near)
            val closed = use(oriented(fist(palm)))
            assertTrue(closed.closed)
            assertNull(closed.panel)
            repeat(65) { assertNull(use(oriented(fist(palm))).panel) }
            openAfterThreeSeconds { use(palm) }
        }
    }

    @Test fun eitherHandCanPinchOnceAndKeepsItsAimWhenTheFingerCurls() {
        for (left in listOf(false, true)) {
            val controls = VrHandControls()
            val panel = openPanel(controls, left = left)
            val pointing = pointingHand(panel, depth = .16, left = left)
            fun use(hand: HandLandmarks) = if (left) update(controls, left = hand) else update(controls, right = hand)
            assertEquals(target, use(pointing).hovered)
            val curling = pointing.copy(index = pointing.index.copy(tip = pointing.index.knuckle + panel.pose.right * .034))
            assertEquals(target, use(curling).hovered)
            val curled = pinch(curling, gap = .027)
            assertEquals(target, use(curled).pressed)
            repeat(6) { assertNull(use(curled).action) }
            val click = use(pointing)
            assertEquals(target, click.action)
            assertTrue(click.clickedByPinch)
            assertFalse(click.closed)
            assertNull(use(pointing).action)
            assertNull(use(curled).action)
            controls.resetPanelInput()
            assertNull(use(curled).action)
            assertNull(use(pointing).action)
            assertNull(use(pinch(pointing)).action)
            assertEquals(target, use(pointing).action)
        }
    }

    @Test fun gradualPinchRetainsItsInitialTargetAsTheIndexMovesAcrossSeveralVisibleButtons() {
        for (depth in listOf(.055, .20, .40)) {
            val controls = VrHandControls()
            val panel = openPanel(controls)
            val open = pointingHand(panel, depth)
            val initialGap = open.thumb.tip - open.index.tip
            assertEquals(target, update(controls, left = open).hovered)
            for (i in 1..10) {
                val fraction = i / 10.0
                val tip = open.index.tip + panel.pose.up * (.045 * fraction)
                val gap = initialGap.length() * (1.0 - fraction) + .017 * fraction
                val closing = open.copy(index = open.index.copy(tip = tip),
                    thumb = open.thumb.copy(tip = tip + initialGap * (gap / initialGap.length())))
                val frame = update(controls, left = closing)
                assertNull(frame.action)
                assertEquals(target, frame.hovered)
            }
            assertEquals(target, update(controls, left = open).action)
        }
    }

    @Test fun aPartialPinchCanBecomeAFullGripAtThePanel() {
        val controls = VrHandControls()
        val panel = openPanel(controls)
        val open = openHand(panel.pose.position, left = false)
        update(controls, right = open)
        val pinched = pinch(open, gap = .018)
        assertEquals(panel, update(controls, right = pinched).panel)
        assertTrue(update(controls, right = fist(pinched)).closed)
    }

    @Test fun fingertipPokeRemainsAvailableWithoutDoubleFiringTheSameContact() {
        for (left in listOf(false, true)) {
            val controls = VrHandControls()
            val panel = openPanel(controls)
            val arm = pointingHand(panel, depth = .05, left = left)
            val contact = pointingHand(panel, depth = .004, left = left)
            fun use(hand: HandLandmarks) = if (left) update(controls, left = hand) else update(controls, right = hand)
            assertNull(use(arm).action)
            val click = use(contact)
            assertEquals(target, click.action)
            assertFalse(click.clickedByPinch)
            assertNull(use(pinch(contact)).action)
            assertNull(use(arm).action)
            assertNull(use(pinch(arm)).action)
            assertEquals(target, use(arm).action)
        }
    }

    @Test fun simultaneousPokeAndPinchActivateOnlyThePinchTarget() {
        val controls = VrHandControls()
        val panel = openPanel(controls)
        val reference = PalmAction.TOGGLE_REFERENCE
        val left = pointingHand(panel, depth = .05)
        val right = pointingHand(panel, depth = .16, left = false, action = reference)
        update(controls, left = left, right = right)
        val touching = pointingHand(panel, depth = .004)
        assertNull(update(controls, left = touching, right = pinch(right)).action)
        assertNull(update(controls, left = touching, right = pinch(right)).action)
        val click = update(controls, left = touching, right = right)
        assertTrue(click.clickedByPinch)
        assertEquals(reference, click.action)
        assertNull(update(controls, left = touching, right = right).action)
    }

    @Test fun disabledButtonsAndTrackingGapsNeverActivateAndLessonBlockingCancelsThePull() {
        val controls = VrHandControls()
        val panel = openPanel(controls)
        val left = pointingHand(panel, depth = .05)
        update(controls, left = left, ready = false)
        assertNull(update(controls, left = pinch(left)).action)
        update(controls, left = left)
        assertNull(update(controls, left = pinch(left), disabled = setOf(target)).pressed)
        assertNull(update(controls, left = left).action)
        update(controls)
        assertNull(update(controls, left = pinch(left)).action)

        controls.reset()
        val reachHand = openHand(reach, left = true)
        update(controls, left = reachHand, allowMotion = false)
        assertFalse(update(controls, left = fist(reachHand), allowMotion = false).pulling)
        val displaced = shift(fist(reachHand), Vec3(0.0, 0.0, .05))
        assertEquals(0.0, update(controls, left = displaced).forwardMeters, 0.0)
    }

    @Test fun capturedPinchIsCanceledByTrackingLossFocusResetOrAPageTransition() {
        for (cancel in 0..3) {
            val controls = VrHandControls()
            val panel = openPanel(controls)
            val open = pointingHand(panel, depth = .16)
            update(controls, left = open)
            assertEquals(target, update(controls, left = pinch(open)).pressed)
            when (cancel) {
                0 -> update(controls)
                1 -> controls.resetInput()
                2 -> update(controls, left = pinch(open), ready = false)
                else -> update(controls, left = pinch(open).copy(sampleAgeSeconds = .2))
            }
            assertNull(update(controls, left = pinch(open)).action)
            assertNull(update(controls, left = open).action)
            assertEquals(target, update(controls, left = pinch(open)).pressed)
            assertEquals(target, update(controls, left = open).action)
        }
    }

    @Test fun projectedCursorCanPinchEvenWhenTheFingerDoesNotCastARay() {
        val controls = VrHandControls()
        val panel = openPanel(controls)
        val straight = pointingHand(panel, depth = .24)
        fun sideways(v: Vec3) = v - panel.pose.normal * v.dot(panel.pose.normal) - panel.pose.right * v.dot(panel.pose.right) +
            panel.pose.normal * v.dot(panel.pose.right) - panel.pose.right * v.dot(panel.pose.normal)
        val hand = transform(straight, { straight.index.tip + sideways(it - straight.index.tip) }, ::sideways)
        assertEquals(target, update(controls, left = hand).hovered)
        assertEquals(target, update(controls, left = pinch(hand)).pressed)
        assertEquals(target, update(controls, left = hand).action)
    }

    @Test fun capturedMapScrollFollowsTheHandAcrossRowsAndReversesWithoutSelectingAChangedSlot() {
        val controls = VrHandControls()
        val panel = VrHandControls.Panel(checkNotNull(VrTrackingMath.panelPose(reach, head)))
        val graph = LevelDependencyGraph(TutorialLevels.all())
        var window = graph.window(0)
        val target = PalmAction.LEVEL_1
        val open = pointingHand(panel, .08, action = target, layout = window.layout)
        val rowHeight = window.layout.heightMeters * LevelMapWindow.ROW_SPACING
        fun sample(hand: HandLandmarks?, ready: Boolean = true) = controls.update(.025, head, hand, null, panel, window.layout, emptySet(),
            interactionReady = ready, allowMotion = false, panelHoldDeltaSeconds = .025, allowMapDrag = true,
            allowPanelDismiss = false).also { window = graph.window(window.firstRow + it.mapDragRows) }
        fun at(rows: Double, pinching: Boolean = true) =
            shift(if (pinching) pinch(open) else open, panel.pose.up * (-rows * rowHeight))
        assertEquals(target, sample(open).hovered)
        assertEquals(target, sample(at(0.0)).pressed)
        assertEquals(0, sample(at(.15)).mapDragRows)
        assertEquals(1, sample(at(.8)).mapDragRows)
        assertEquals(1, sample(at(1.85), ready = false).mapDragRows)
        assertEquals(2, window.firstRow)
        assertEquals(0, sample(at(1.80)).mapDragRows)
        assertEquals(-1, sample(at(1.1)).mapDragRows)
        assertEquals(-1, sample(at(.1)).mapDragRows)
        assertEquals(0, window.firstRow)
        assertNull(sample(at(0.0, pinching = false)).action)
        assertNull(sample(open).action)
        assertEquals(target, sample(pinch(open)).pressed)
        assertEquals(target, sample(at(.15, pinching = false)).action)
        sample(pinch(open))
        assertEquals(-1, sample(at(-.9)).mapDragRows)
        assertEquals(0, window.firstRow)
        assertNull(sample(open).action)
    }

    @Test fun steadyPinchMovesTheMapWhenTheFingertipsTravelAroundAStationaryPalm() {
        val controls = VrHandControls()
        val panel = VrHandControls.Panel(checkNotNull(VrTrackingMath.panelPose(reach, head)))
        val map = LevelDependencyGraph(TutorialLevels.all()).window(0).layout
        val open = pointingHand(panel, .08, action = PalmAction.LEVEL_1, layout = map)
        fun sample(hand: HandLandmarks) = controls.update(.025, head, hand, null, panel, map, emptySet(),
            true, false, .025, allowMapDrag = true, allowPanelDismiss = false)
        sample(open)
        sample(pinch(open))
        var rows = 0
        for (i in 1..6) {
            val offset = panel.pose.up * (-.01 * i)
            rows += sample(pinch(open, offset = offset)).mapDragRows
        }
        assertEquals(1, rows)
        assertNull(sample(open).action)
    }

    @Test fun mapDragsCanStartOnLockedNodesAndInEmptySpaceAndLossCannotBecomeARelease() {
        val panel = VrHandControls.Panel(checkNotNull(VrTrackingMath.panelPose(reach, head)))
        val map = LevelDependencyGraph(TutorialLevels.all()).window(0).layout
        val locked = PalmAction.LEVEL_2
        val aimed = pointingHand(panel, .08, action = locked, layout = map)
        val empty = shift(aimed, panel.pose.right * (.96 - map.buttons[1].let { (it.left + it.right) * .5 }) * map.widthMeters)
        for (open in listOf(aimed, empty)) {
            val controls = VrHandControls()
            fun sample(hand: HandLandmarks?) = controls.update(.025, head, null, hand, panel, map, setOf(locked),
                true, false, .025, allowMapDrag = true, allowPanelDismiss = false)
            assertNull(sample(open).hovered)
            assertNull(sample(pinch(open)).pressed)
            val dragged = shift(pinch(open), panel.pose.up * -.075)
            assertEquals(1, sample(dragged).mapDragRows)
            assertNull(sample(null).action)
            assertEquals(0, sample(dragged).mapDragRows)
            val release = sample(open)
            assertEquals(0, release.mapDragRows)
            assertNull(release.action)
        }
    }

    @Test fun dwellUsesTwoFullRealSecondsAndHoldingThroughAPageChangeOrOcclusionNeverRepeats() {
        val controls = VrHandControls()
        val panel = VrHandControls.Panel(checkNotNull(VrTrackingMath.panelPose(reach, head)))
        var spec = layout
        val hand = pointingHand(panel, .09)
        val away = shift(hand, panel.pose.right * .10)
        var elapsed = .025
        fun sample(input: HandLandmarks?) = controls.update(.05, head, input, null, panel, spec, emptySet(),
            true, false, elapsed, allowPanelDismiss = false)
        repeat(80) { assertNull(sample(hand).action) }
        val fired = sample(hand)
        assertEquals(target, fired.action)
        assertTrue(fired.clickedByDwell)
        assertNull(sample(null).action)
        controls.resetPanelInput()
        spec = spec.copy(buttons = spec.buttons.map {
            if (it.action == target) it.copy(action = PalmAction.BACK) else it
        })
        repeat(180) { assertNull(sample(hand).action) }
        assertNull(sample(pinch(hand)).action)
        assertNull(sample(hand).action)
        sample(away)
        repeat(40) { assertNull(sample(hand).action) }
        elapsed = 0.0
        repeat(150) { assertNull(sample(hand).action) }
        elapsed = .025
        repeat(40) { assertNull(sample(hand).action) }
        assertEquals(PalmAction.BACK, sample(hand).action)
    }

    @Test fun dwellRestartsOnLossDisabledTargetsOrAnotherHandTakingOver() {
        val controls = VrHandControls()
        val panel = VrHandControls.Panel(checkNotNull(VrTrackingMath.panelPose(reach, head)))
        val left = pointingHand(panel, .18)
        val right = pointingHand(panel, .18, left = false, action = PalmAction.TOGGLE_REFERENCE)
        fun sample(l: HandLandmarks?, r: HandLandmarks?, disabled: Set<PalmAction> = emptySet()) =
            controls.update(.05, head, l, r, panel, layout, disabled, true, false, .05, allowPanelDismiss = false)
        repeat(35) { assertNull(sample(left, null).action) }
        sample(null, null)
        assertEquals(0.0, sample(left, null).dwellProgress, 0.0)
        repeat(30) { assertNull(sample(left, null).action) }
        repeat(50) { assertNull(sample(left, null, setOf(target)).action) }
        repeat(25) { assertNull(sample(left, right).action) }
        repeat(40) { assertNull(sample(null, right).action) }
        assertEquals(PalmAction.TOGGLE_REFERENCE, sample(null, right).action)
    }

    @Test fun jitterAlongTheButtonEdgeAndMillimetresOfFingerGapNoiseCanStillDwell() {
        val controls = VrHandControls()
        val panel = VrHandControls.Panel(checkNotNull(VrTrackingMath.panelPose(reach, head)))
        val button = layout.buttons.first()
        val center = pointingHand(panel, .085)
        val hand = shift(center, panel.pose.right * (button.width * layout.widthMeters * .5 - .0005))
        val noise = kotlin.random.Random(775)
        fun sample(input: HandLandmarks) = controls.update(.02, head, input, null, panel, layout, emptySet(),
            true, false, .02, allowPanelDismiss = false)
        assertEquals(target, sample(hand).hovered)
        val clicks = (1..205).mapNotNull {
            val translation = panel.pose.right * noise.nextDouble(-.0015, .0015)
            val gapNoise = panel.pose.up * noise.nextDouble(-.0018, .0018)
            sample(shift(hand.copy(thumb = hand.thumb.copy(tip = hand.thumb.tip + gapNoise)), translation)).action
        }
        assertEquals(listOf(target), clicks)
    }

    @Test fun slowObliquePinchWithTrackingNoiseAndPartialClosurePreservesTheOriginalButton() {
        for (side in listOf(false, true)) for (depth in listOf(.09, .22, .38)) {
            val controls = VrHandControls()
            val panel = VrHandControls.Panel(checkNotNull(VrTrackingMath.panelPose(reach, head)))
            val straight = pointingHand(panel, depth, left = side)
            // Roll the hand about its pointing axis by 37 degrees.
            fun roll(v: Vec3): Vec3 {
                val x = v.dot(panel.pose.right)
                val y = v.dot(panel.pose.up)
                return panel.pose.right * (.7986 * x - .6018 * y) + panel.pose.up * (.6018 * x + .7986 * y) +
                    panel.pose.normal * v.dot(panel.pose.normal)
            }
            val aim = transform(straight, { straight.index.tip + roll(it - straight.index.tip) }, ::roll)
            val gap = aim.thumb.tip - aim.index.tip
            fun sample(input: HandLandmarks) = controls.update(.0125, head, input.takeIf { side }, input.takeUnless { side },
                panel, layout, emptySet(), true, false, .0125, allowPanelDismiss = false)
            assertEquals(target, sample(aim).hovered)
            val noise = kotlin.random.Random(99)
            var captured = false
            for (i in 1..130) {
                val phase = i / 130.0
                val tip = aim.index.tip - panel.pose.up * (.042 * phase) + panel.pose.right * noise.nextDouble(-.0005, .0005)
                val distance = gap.length() * (1 - phase) + .029 * phase + noise.nextDouble(-.0004, .0004)
                val closing = aim.copy(index = aim.index.copy(tip = tip, distal = null),
                    thumb = aim.thumb.copy(tip = tip + gap * (distance / gap.length())))
                val frame = sample(closing)
                assertNull(frame.action)
                if (frame.pressed != null) {
                    assertEquals(target, frame.pressed)
                    captured = true
                }
            }
            assertTrue(captured)
            assertEquals(target, sample(aim).action)
        }
    }

    @Test fun wristLedMapDragSurvivesApertureNoiseAndSingleFrameSeparationWithoutClicking() {
        val controls = VrHandControls()
        val panel = VrHandControls.Panel(checkNotNull(VrTrackingMath.panelPose(reach, head)))
        val map = LevelDependencyGraph(TutorialLevels.all()).window(0).layout
        val open = pointingHand(panel, .08, action = PalmAction.LEVEL_1, layout = map)
        fun sample(hand: HandLandmarks) = controls.update(.02, head, hand, null, panel, map, emptySet(),
            true, false, .02, allowMapDrag = true, allowPanelDismiss = false)
        repeat(90) { assertNull(sample(open).action) }
        assertEquals(PalmAction.LEVEL_1, sample(pinch(open)).pressed)
        var rows = 0
        for (i in 1..16) {
            val offset = panel.pose.up * (-.004 * i)
            val gap = if (i == 8) .052 else if (i % 2 == 0) .020 else .027
            val frame = sample(pinch(open, offset, gap))
            assertNull(frame.action)
            assertEquals(0.0, frame.dwellProgress, 0.0)
            rows += frame.mapDragRows
        }
        assertEquals(1, rows)
        assertNull(sample(open).action)
        repeat(110) { assertNull(sample(open).action) }
    }

    private fun update(
        controls: VrHandControls, view: VrTrackingMath.Pose = head, left: HandLandmarks? = null, right: HandLandmarks? = null,
        ready: Boolean = true, disabled: Set<PalmAction> = emptySet(), allowMotion: Boolean = true,
        panelHoldDeltaSeconds: Double = .05,
    ) = controls.update(.05, view, left, right, null, layout, disabled, ready, allowMotion, panelHoldDeltaSeconds)

    private fun openAfterThreeSeconds(sample: () -> VrHandControls.Frame): VrHandControls.Frame {
        repeat(60) { assertNull(sample().panel) }
        return sample().also { assertTrue(it.opened) }
    }

    private fun openPanel(controls: VrHandControls, left: Boolean = false): VrHandControls.Panel {
        val palm = openHand(reach, left)
        val opened = openAfterThreeSeconds {
            if (left) update(controls, left = palm) else update(controls, right = palm)
        }
        val panel = checkNotNull(opened.panel)
        assertEquals(panel, update(controls).panel)
        return panel
    }

    private fun openHand(at: Vec3, left: Boolean, forwardPalm: Boolean = false): HandLandmarks {
        fun finger(base: Vec3, tip: Vec3) = FingerLandmarks(base, base + (tip - base) * .45, tip, base + (tip - base) * .75)
        val hand = HandLandmarks(Vec3.ZERO, Vec3(0.0, .035, 0.0),
            finger(Vec3(-.025, .014, 0.0), Vec3(-.078, .057, 0.0)),
            finger(Vec3(-.026, .065, 0.0), Vec3(-.026, .130, 0.0)),
            finger(Vec3(-.006, .070, 0.0), Vec3(-.006, .145, 0.0)),
            finger(Vec3(.014, .065, 0.0), Vec3(.019, .132, 0.0)),
            finger(Vec3(.032, .052, 0.0), Vec3(.044, .106, 0.0)), Vec3(0.0, 0.0, 1.0))
        // For a right palm toward the wearer (+Z), the upright thumb is on the wearer's right.
        fun rotate(point: Vec3) = Vec3(point.x * (if (left xor forwardPalm) 1 else -1),
            point.y, point.z * (if (forwardPalm) -1 else 1))
        return transform(hand, { at + rotate(it - hand.palm) }, ::rotate)
    }

    private fun fist(hand: HandLandmarks): HandLandmarks {
        val up = Vec3(0.0, 1.0, 0.0)
        val palmNormal = checkNotNull(hand.palmNormal)
        val inward = palmNormal * (1.0 / palmNormal.length())
        fun curl(f: FingerLandmarks) = f.copy(knuckle = f.base + up * .020 + inward * .015,
            distal = f.base + up * .005 + inward * .027, tip = f.base - up * .020 + inward * .015)
        return hand.copy(index = curl(hand.index), middle = curl(hand.middle), ring = curl(hand.ring), little = curl(hand.little))
    }

    private fun pointingHand(panel: VrHandControls.Panel, depth: Double, left: Boolean = true,
                             action: PalmAction = target, layout: PalmPanelSpec = this.layout): HandLandmarks {
        val pose = panel.pose
        val button = layout.buttons.first { it.action == action }
        val x = ((button.left + button.right) / 2 - .5) * layout.widthMeters * panel.scale
        val y = (.5 - (button.top + button.bottom) / 2) * layout.heightMeters * panel.scale
        val tip = pose.position + pose.right * x + pose.up * y + pose.normal * depth
        val open = openHand(Vec3.ZERO, left)
        val curled = fist(open)
        val hand = open.copy(middle = curled.middle, ring = curled.ring, little = curled.little)
        fun orient(v: Vec3) = pose.right * v.x - pose.normal * v.y + pose.up * v.z
        return transform(hand, { tip + orient(it - hand.index.tip) }, ::orient)
    }

    private fun pinch(hand: HandLandmarks, offset: Vec3 = Vec3.ZERO, gap: Double = .018): HandLandmarks {
        val tip = hand.index.tip + offset
        return hand.copy(index = hand.index.copy(tip = tip), thumb = hand.thumb.copy(tip = tip + Vec3(gap, 0.0, 0.0)))
    }

    private fun shift(hand: HandLandmarks, delta: Vec3) = transform(hand, { it + delta }, { it })

    private fun transform(hand: HandLandmarks, point: (Vec3) -> Vec3, vector: (Vec3) -> Vec3): HandLandmarks {
        fun finger(f: FingerLandmarks) = FingerLandmarks(point(f.base), point(f.knuckle), point(f.tip), f.distal?.let(point))
        return HandLandmarks(point(hand.wrist), point(hand.palm), finger(hand.thumb), finger(hand.index),
            finger(hand.middle), finger(hand.ring), finger(hand.little), hand.palmNormal?.let(vector))
    }
}
