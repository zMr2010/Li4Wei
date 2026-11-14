package com.fourthward.game.vr

import com.fourthward.game.core.*
import org.junit.Assert.*
import org.junit.Test

class VrFocusPolicyTest {
    @Test fun xrAndResumeAuthorityCannotBeOverwrittenByLaterWindowCallbacks() {
        for (xrFirst in listOf(false, true)) {
            val input = InputState()
            val focus = VrFocusPolicy(input)
            val first = if (xrFirst) focus.xrFocused(true) else focus.resumed(true)
            assertFalse(first.active)
            input.key(InputKey.FORWARD, true)
            assertEquals(0.0, input.frame(0.02, 1L).moveForwardMeters, 0.0)
            val ready = if (xrFirst) focus.resumed(true) else focus.xrFocused(true)
            assertTrue(ready.active)
            input.key(InputKey.FORWARD, true)
            input.key(InputKey.MENU, true)
            val windowLost = focus.windowFocus(false)
            assertTrue(windowLost.active) // Immersive runtime still owns valid XR input.
            assertTrue(windowLost.clearTransient)
            val cleared = input.frame(0.02, 2L)
            assertEquals(0.0, cleared.moveForwardMeters, 0.0)
            assertTrue(cleared.commands.isEmpty())
            input.key(InputKey.FORWARD, true)
            assertTrue(input.frame(0.02, 3L).moveForwardMeters > 0.0)
            assertFalse(focus.xrFocused(false).active)
            assertFalse(focus.windowFocus(true).active) // Window focus cannot reenable a lost XR session.
            input.key(InputKey.FORWARD, true)
            assertEquals(0.0, input.frame(0.02, 4L).moveForwardMeters, 0.0)
            assertTrue(focus.xrFocused(true).active)
            assertEquals(0.0, input.frame(0.02, 5L).moveForwardMeters, 0.0) // No replay of old held keys.
        }
    }

    @Test fun activityPauseClearsQueuedActionsAndValidXrAloneCannotResumeInput() {
        val input = InputState()
        val focus = VrFocusPolicy(input)
        focus.resumed(true); focus.xrFocused(true)
        input.key(InputKey.RESET, true)
        input.key(InputKey.ROTATE_RIGHT, true)
        assertFalse(focus.resumed(false).active)
        assertFalse(focus.xrFocused(true).active)
        assertFalse(focus.windowFocus(true).active)
        val paused = input.frame(0.02, 1L)
        assertEquals(0.0, paused.thetaRadians, 0.0)
        assertTrue(paused.commands.isEmpty())
        assertTrue(focus.resumed(true).active)
        val resumed = input.frame(0.02, 2L)
        assertEquals(0.0, resumed.thetaRadians, 0.0)
        assertTrue(resumed.commands.isEmpty())
        input.key(InputKey.RESET, true)
        assertEquals(listOf(InputCommand.RESET_ANGLE), input.frame(0.02, 3L).commands)
    }
}
