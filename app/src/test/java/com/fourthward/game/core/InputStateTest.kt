package com.fourthward.game.core

import kotlin.math.hypot
import org.junit.Assert.*
import org.junit.Test

class InputStateTest {
    private fun input() = InputState().apply { setEnabled(true) }
    private val now = 1_000_000_000L

    @Test fun heldMovementStopsOnReleaseAndDiagonalSpeedIsLimited() {
        val state = input()
        state.key(InputKey.FORWARD, true)
        state.key(InputKey.RIGHT, true)
        val moving = state.frame(0.05, now)
        assertEquals(0.075, hypot(moving.moveRightMeters, moving.moveForwardMeters), 1e-10)
        assertTrue(moving.moveRightMeters > 0.0 && moving.moveForwardMeters > 0.0)
        state.key(InputKey.FORWARD, false)
        state.key(InputKey.RIGHT, false)
        assertEquals(InputFrame(), state.frame(0.05, now))
    }

    @Test fun tapBetweenFramesProducesOneRotationFrame() {
        val state = input()
        state.key(InputKey.ROTATE_LEFT, true)
        state.key(InputKey.ROTATE_LEFT, false)
        assertEquals(-0.02, state.frame(0.02, now).thetaRadians, 1e-10)
        assertEquals(0.0, state.frame(0.02, now).thetaRadians, 0.0)
    }

    @Test fun oppositeDirectionsCancel() {
        val state = input()
        listOf(InputKey.FORWARD, InputKey.BACKWARD, InputKey.ASCEND, InputKey.DESCEND,
            InputKey.ROTATE_LEFT, InputKey.ROTATE_RIGHT)
            .forEach { state.key(it, true) }
        assertEquals(InputFrame(), state.frame(0.05, now))
    }

    @Test fun elevationIsHeldMovementAndThreeAxisSpeedDoesNotIncrease() {
        val state = input()
        state.key(InputKey.ASCEND, true)
        val ascending = state.frame(.05, now)
        assertEquals(.075, ascending.moveUpMeters, 1e-10)
        assertTrue(ascending.commands.isEmpty())
        state.key(InputKey.FORWARD, true); state.key(InputKey.RIGHT, true)
        val diagonal = state.frame(.05, now)
        val length = Vec3(diagonal.moveRightMeters, diagonal.moveUpMeters, diagonal.moveForwardMeters).length()
        assertEquals(.075, length, 1e-10)
        state.clearForPageChange()
        assertEquals(InputFrame(), state.frame(.05, now))
        state.key(InputKey.DESCEND, true)
        assertEquals(-.075, state.frame(.05, now).moveUpMeters, 1e-10)
        state.key(InputKey.DESCEND, false)
        assertEquals(InputFrame(), state.frame(.05, now))
    }

    @Test fun elevationStopsOnFocusLossAndShiftTabDoesNotDescend() {
        val state = input()
        state.key(InputKey.ASCEND, true)
        state.setEnabled(false); state.setEnabled(true)
        assertEquals(InputFrame(), state.frame(.05, now))
        state.key(InputKey.DESCEND, true)
        state.key(InputKey.SELECT, true, shift = true)
        val navigation = state.frame(.05, now)
        assertEquals(0.0, navigation.moveUpMeters, 0.0)
        assertEquals(listOf(InputCommand.SELECT_PREVIOUS_CONTROL), navigation.commands)
        state.key(InputKey.SELECT, false)
        assertEquals(InputFrame(), state.frame(.05, now))
    }

    @Test fun actionKeysAreEdgeTriggeredAndTabCanReverse() {
        val state = input()
        state.key(InputKey.MENU, true)
        repeat(5) { state.key(InputKey.MENU, true, repeat = true) }
        state.key(InputKey.MENU, true)
        state.key(InputKey.SELECT, true, shift = true)
        assertEquals(listOf(InputCommand.TOGGLE_DEBUG_PANEL, InputCommand.SELECT_PREVIOUS_CONTROL), state.frame(0.02, now).commands)
        assertTrue(state.frame(0.02, now).commands.isEmpty())
        state.key(InputKey.MENU, false)
        state.key(InputKey.MENU, true)
        assertEquals(listOf(InputCommand.TOGGLE_DEBUG_PANEL), state.frame(0.02, now).commands)
    }

    @Test fun allGameplayActionsHaveKeyboardCommands() {
        val state = input()
        listOf(InputKey.RESET, InputKey.REFERENCE, InputKey.RESTART, InputKey.NEXT_LEVEL, InputKey.SELECT, InputKey.ACTIVATE)
            .forEach { state.key(it, true); state.key(it, false) }
        assertEquals(listOf(InputCommand.RESET_ANGLE, InputCommand.TOGGLE_REFERENCE, InputCommand.RESTART_LEVEL,
            InputCommand.NEXT_LEVEL, InputCommand.SELECT_NEXT_CONTROL, InputCommand.ACTIVATE_CONTROL), state.frame(0.02, now).commands)
    }

    @Test fun losingFocusDropsHeldKeysAndQueuedActions() {
        val state = input()
        state.key(InputKey.FORWARD, true)
        state.key(InputKey.RESET, true)
        state.controller(InputControllerState(leftConnected = true, leftY = 1.0, menu = true), now)
        state.setEnabled(false)
        state.setEnabled(true)
        state.key(InputKey.FORWARD, true, repeat = true)
        assertEquals(InputFrame(), state.frame(0.05, now))
    }

    @Test fun deltaTimeIsFiniteNonnegativeAndBounded() {
        val state = input()
        state.key(InputKey.ROTATE_RIGHT, true)
        assertEquals(0.0, state.frame(Double.NaN, now).thetaRadians, 0.0)
        assertEquals(0.0, state.frame(-0.1, now).thetaRadians, 0.0)
        assertEquals(0.05, state.frame(10.0, now).thetaRadians, 1e-10)
    }

    @Test fun radialDeadzoneRejectsDriftAndPreservesDirection() {
        val state = input()
        state.controller(InputControllerState(leftConnected = true, leftX = 0.1, leftY = 0.1), now)
        assertEquals(InputFrame(), state.frame(0.05, now))
        state.controller(InputControllerState(leftConnected = true, leftX = 1.0, leftY = 1.0), now)
        val moving = state.frame(0.05, now)
        assertEquals(0.075, hypot(moving.moveRightMeters, moving.moveForwardMeters), 1e-10)
        assertEquals(moving.moveForwardMeters, moving.moveRightMeters, 1e-10)
    }

    @Test fun missingOrStaleControllersCannotKeepMoving() {
        val state = input()
        state.controller(InputControllerState(leftConnected = true, leftY = 1.0, rightConnected = true, rightX = 1.0), now)
        assertTrue(state.frame(0.05, now).thetaRadians > 0.0)
        assertEquals(InputFrame(), state.frame(0.05, now + 350_000_001L))
        state.controller(InputControllerState(leftConnected = true, leftY = 1.0), now)
        state.controller(null, now)
        assertEquals(InputFrame(), state.frame(0.05, now))
    }

    @Test fun absentSideIgnoresItsAxisAndButtons() {
        val state = input()
        state.controller(InputControllerState(leftX = 1.0, leftY = 1.0, rightX = 1.0, menu = true, activate = true), now)
        assertEquals(InputFrame(), state.frame(0.05, now))
    }

    @Test fun controllerButtonEdgesSurviveTwoCallbacksBetweenFrames() {
        val state = input()
        val neutral = InputControllerState(rightConnected = true)
        state.controller(neutral.copy(activate = true), now)
        state.controller(neutral.copy(activate = true), now + 1)
        state.controller(neutral, now + 2)
        assertEquals(listOf(InputCommand.ACTIVATE_CONTROL), state.frame(0.02, now + 3).commands)
        assertTrue(state.frame(0.02, now + 4).commands.isEmpty())
    }

    @Test fun simultaneousDevicesCannotDoubleMovementOrRotationSpeed() {
        val state = input()
        state.key(InputKey.FORWARD, true)
        state.key(InputKey.ROTATE_RIGHT, true)
        state.controller(InputControllerState(leftConnected = true, leftY = 1.0, rightConnected = true, rightX = 1.0), now)
        val frame = state.frame(0.05, now)
        assertEquals(0.075, frame.moveForwardMeters, 1e-10)
        assertEquals(0.05, frame.thetaRadians, 1e-10)
    }

    @Test fun corruptStickSampleCannotPropagateNan() {
        val state = input()
        state.controller(InputControllerState(leftConnected = true, leftX = Double.NaN, rightConnected = true, rightX = Double.POSITIVE_INFINITY), now)
        assertEquals(InputFrame(), state.frame(0.05, now))
    }

    @Test fun lessonNavigationIsOneShotAndCannotReplayAfterFocusLoss() {
        val state = input()
        state.key(InputKey.PREVIOUS_STEP, true)
        state.key(InputKey.PREVIOUS_STEP, true, repeat = true)
        assertEquals(listOf(InputCommand.PREVIOUS_LESSON_STEP), state.frame(0.02, now).commands)
        assertTrue(state.frame(0.02, now + 1).commands.isEmpty())
        state.key(InputKey.LESSON, true)
        state.setEnabled(false)
        state.setEnabled(true)
        assertEquals(InputFrame(), state.frame(0.02, now + 2))
        state.key(InputKey.LESSON, true)
        assertEquals(listOf(InputCommand.OPEN_LESSON), state.frame(0.02, now + 3).commands)
    }
    @Test fun levelPickerKeysAreDiscreteCommandsAndClearOnFocusLoss() {
        val state = input()
        val keys = listOf(InputKey.LEVEL_LIST, InputKey.BACK, InputKey.LEVEL_1, InputKey.LEVEL_2, InputKey.LEVEL_3,
            InputKey.LEVEL_4, InputKey.LEVEL_5, InputKey.LEVEL_6, InputKey.LEVEL_7, InputKey.LEVEL_8, InputKey.LEVEL_9,
            InputKey.PREVIOUS_GROUP, InputKey.NEXT_GROUP)
        keys.forEach { state.key(it, true); state.key(it, true, repeat = true); state.key(it, false) }
        val frame = state.frame(0.02, now)
        assertEquals(listOf(InputCommand.TOGGLE_LEVEL_LIST, InputCommand.BACK, InputCommand.CHOOSE_LEVEL_1,
            InputCommand.CHOOSE_LEVEL_2, InputCommand.CHOOSE_LEVEL_3, InputCommand.CHOOSE_LEVEL_4,
            InputCommand.CHOOSE_LEVEL_5, InputCommand.CHOOSE_LEVEL_6,
            InputCommand.CHOOSE_LEVEL_7, InputCommand.CHOOSE_LEVEL_8, InputCommand.CHOOSE_LEVEL_9,
            InputCommand.PREVIOUS_GROUP, InputCommand.NEXT_GROUP), frame.commands)
        assertEquals(0.0, frame.thetaRadians, 0.0)
        assertEquals(0.0, frame.moveForwardMeters, 0.0)
        assertTrue(state.frame(0.02, now).commands.isEmpty())
        state.key(InputKey.LEVEL_LIST, true)
        state.setEnabled(false); state.setEnabled(true)
        assertEquals(InputFrame(), state.frame(0.02, now))
    }

    @Test fun changingPagesDoesNotTurnAHeldControllerTriggerIntoANewClick() {
        val state = input()
        val pressed = InputControllerState(rightConnected = true, activate = true)
        state.key(InputKey.FORWARD, true)
        state.controller(pressed, now)
        assertEquals(listOf(InputCommand.ACTIVATE_CONTROL), state.frame(0.02, now).commands)
        state.clearForPageChange()
        state.controller(pressed, now + 20_000_000)
        assertEquals(InputFrame(), state.frame(0.02, now + 20_000_000))
        state.controller(pressed.copy(activate = false), now + 40_000_000)
        state.controller(pressed, now + 60_000_000)
        assertEquals(listOf(InputCommand.ACTIVATE_CONTROL), state.frame(0.02, now + 60_000_000).commands)
    }

    @Test fun eachHelpPressRequestsOneHintAndPageChangeDiscardsQueuedHelp() {
        val state = input()
        state.key(InputKey.LESSON, true)
        state.key(InputKey.LESSON, true, repeat = true)
        assertEquals(listOf(InputCommand.OPEN_LESSON), state.frame(0.02, now).commands)
        assertTrue(state.frame(0.02, now + 1).commands.isEmpty())
        state.key(InputKey.LESSON, false)
        state.key(InputKey.LESSON, true)
        assertEquals(listOf(InputCommand.OPEN_LESSON), state.frame(0.02, now + 2).commands)
        state.key(InputKey.LESSON, false)
        state.key(InputKey.LESSON, true)
        state.clearForPageChange()
        assertEquals(InputFrame(), state.frame(0.02, now + 3))
    }

}
