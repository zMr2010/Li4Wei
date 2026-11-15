package com.fourthward.game.core

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI

class VerticalMovementTest {
    private fun level(boxes: List<Box4> = emptyList(), start: Vec4 = Vec4.ZERO): TutorialLevel {
        val goal = Sphere4("goal", Vec4(20.0, 20.0, 0.0, 20.0), .2)
        return TutorialLevel("vertical", "Vertical", "", "", goal,
            0xff67e8db.toInt(), World4(boxes, listOf(goal)), start = start)
    }

    private fun assertPoint(expected: Vec4, actual: Vec4) {
        for (axis in 0..3) assertEquals(expected[axis], actual[axis], 1e-8)
    }

    @Test fun simultaneousMovementUsesSliceDirectionsAndWorldVerticalWithoutChangingTracking() {
        val game = FourthwardGame(listOf(level()))
        val raw = Vec3(.7, 1.62, -.3)
        game.updateTrackedHead(raw)
        game.rotate(PI / 2)
        val result = game.move(Vec3(.4, .9, -.6))
        assertTrue(result.changed)
        assertFalse(result.blocked)
        assertPoint(Vec4(0.0, .4, .9, -.6), game.playerFootWorld())
        assertPoint(Vec4(0.0, .4, 2.52, -.6), game.playerWorld())
        assertEquals(raw, game.trackedHead)
        assertEquals(90.0, game.rotationDegrees, 1e-8)
        val beforeReset = game.playerWorld()
        game.resetAngle()
        assertPoint(beforeReset, game.playerWorld())
        assertEquals(.9, game.playerFootWorld().z, 1e-8)
    }

    @Test fun ascentStopsBelowThinCeilingAndImmediateDescentHasNoStoredMovement() {
        val ceiling = Box4("ceiling", Vec4(0.0, 0.0, 2.51, 0.0), Vec4(5.0, 5.0, .01, 5.0))
        val game = FourthwardGame(listOf(level(listOf(ceiling))))
        game.updateTrackedHead(Vec3(.4, 1.6, -.3))
        assertTrue(game.move(Vec3(0.0, 4.0, 0.0)).blocked)
        val stopped = game.playerFootWorld()
        assertTrue(stopped.z in .74.. .80)
        repeat(10) {
            assertTrue(game.move(Vec3(0.0, .2, 0.0)).blocked)
            assertPoint(stopped, game.playerFootWorld())
        }
        assertFalse(game.move(Vec3(0.0, -.05, 0.0)).blocked)
        assertPoint(stopped - Vec4.Z * .05, game.playerFootWorld())
        assertEquals(1.6, game.playerWorld().z - game.playerFootWorld().z, 1e-8)
    }

    @Test fun descentStopsAboveThinFloorAndCanImmediatelyRise() {
        val floor = Box4("floor", Vec4(0.0, 0.0, .49, 0.0), Vec4(5.0, 5.0, .01, 5.0))
        val game = FourthwardGame(listOf(level(listOf(floor), start = Vec4.Z)))
        assertTrue(game.move(Vec3(0.0, -4.0, 0.0)).blocked)
        val stopped = game.playerFootWorld()
        assertTrue(stopped.z in .50.. .55)
        repeat(10) {
            assertTrue(game.move(Vec3(0.0, -.2, 0.0)).blocked)
            assertPoint(stopped, game.playerFootWorld())
        }
        assertFalse(game.move(Vec3(0.0, .05, 0.0)).blocked)
        assertPoint(stopped + Vec4.Z * .05, game.playerFootWorld())
    }

    @Test fun diagonalAscentSweepsTheEntireBodyInsteadOfCrossingTheCeiling() {
        val ceiling = Box4("ceiling", Vec4(0.0, 0.0, 2.51, 0.0), Vec4(5.0, 5.0, .01, 5.0))
        val game = FourthwardGame(listOf(level(listOf(ceiling))))
        assertTrue(game.move(Vec3(.4, 4.0, -.2)).blocked)
        val feet = game.playerFootWorld()
        val ceilingLimit = 2.5 - (game.trackedHead.y + game.playerHeadRadius)
        assertTrue(feet.z <= ceilingLimit && feet.z > ceilingLimit - .041)
        assertEquals(feet.z * .1, feet.x, 1e-8)
        assertEquals(feet.z * -.05, feet.w, 1e-8)
    }

    @Test fun physicalWalkingAndCrouchingRetainTheArtificialAltitude() {
        val game = FourthwardGame(listOf(level()))
        val raw = Vec3(.5, 1.6, -.2)
        game.updateTrackedHead(raw)
        game.move(Vec3(0.0, .9, 0.0))
        val next = raw + Vec3(.2, -.3, -.1)
        assertFalse(game.updateTrackedHead(next).blocked)
        assertEquals(next, game.trackedHead)
        assertPoint(Vec4(.2, 0.0, .9, -.1), game.playerFootWorld())
        assertPoint(Vec4(.2, 0.0, 2.2, -.1), game.playerWorld())
    }

    @Test fun restartClearsAltitudeWithoutChangingRawEyeHeight() {
        val game = FourthwardGame(listOf(level()))
        val raw = Vec3(3.0, 1.72, -4.0)
        game.updateTrackedHead(raw)
        game.move(Vec3(.6, 2.0, -.4))
        game.restartLevel()
        assertPoint(Vec4.ZERO, game.playerFootWorld())
        assertPoint(Vec4(0.0, 0.0, 1.72, 0.0), game.playerWorld())
        assertEquals(raw, game.trackedHead)
        assertFalse(game.updateTrackedHead(raw).changed)
    }

    @Test fun fixedObservationLessonsRejectArtificialVerticalAndCombinedMovement() {
        for (level in TutorialLevels.all().filter { it.geometryTopic != null }) {
            val game = FourthwardGame(listOf(level))
            game.updateTrackedHead(Vec3(1.0, 1.72, -.5))
            val head = game.playerWorld()
            assertFalse(game.move(Vec3(0.0, 1.0, 0.0)).changed)
            assertFalse(game.move(Vec3(.5, -.5, .5)).changed)
            assertPoint(level.start, game.playerFootWorld())
            assertPoint(head, game.playerWorld())
        }
    }

    @Test fun physicalHeadSweepsThinCeilingAndRepeatedPressureDoesNotLowerTheFeet() {
        val ceiling = Box4("ceiling", Vec4(0.0, 0.0, 2.51, 0.0), Vec4(5.0, 5.0, .01, 5.0))
        val game = FourthwardGame(listOf(level(listOf(ceiling))))
        val first = Vec3(.4, 1.6, -.3)
        game.updateTrackedHead(first)
        assertTrue(game.updateTrackedHead(first + Vec3(0.0, 4.0, 0.0)).blocked)
        val stoppedHead = game.playerWorld()
        val stoppedFeet = game.playerFootWorld()
        assertEquals(2.5 - game.playerHeadRadius - .001, stoppedHead.z, 1e-6)
        repeat(12) {
            val raw = game.trackedHead + Vec3(0.0, .2, 0.0)
            assertTrue(game.updateTrackedHead(raw).blocked)
            assertEquals(raw, game.trackedHead)
            assertPoint(stoppedFeet, game.playerFootWorld())
            assertEquals(stoppedHead.z, game.playerWorld().z, 1e-6)
        }
        assertFalse(game.updateTrackedHead(game.trackedHead + Vec3(.1, 0.0, 0.0)).blocked)
        assertPoint(stoppedFeet + Vec4.X * .1, game.playerFootWorld())
        assertFalse(game.updateTrackedHead(game.trackedHead - Vec3(0.0, .05, 0.0)).blocked)
        assertEquals(stoppedHead.z - .05, game.playerWorld().z, 1e-6)
        assertEquals(stoppedFeet.z, game.playerFootWorld().z, 1e-8)
    }

    @Test fun physicalHeadCannotCrossThinFloorAndCanRetreatAfterPressureAndSidewaysMotion() {
        val floor = Box4("floor", Vec4(0.0, 0.0, .49, 0.0), Vec4(5.0, 5.0, .01, 5.0))
        val game = FourthwardGame(listOf(level(listOf(floor), start = Vec4.Z)))
        game.updateTrackedHead(Vec3(.2, 1.6, -.4))
        assertTrue(game.updateTrackedHead(Vec3(.2, -4.0, -.4)).blocked)
        val stopped = game.playerWorld()
        assertEquals(.5 + game.playerHeadRadius + .001, stopped.z, 1e-6)
        repeat(12) {
            assertTrue(game.updateTrackedHead(game.trackedHead - Vec3(0.0, .2, 0.0)).blocked)
            assertEquals(stopped.z, game.playerWorld().z, 1e-6)
            assertEquals(1.0, game.playerFootWorld().z, 1e-8)
        }
        assertFalse(game.updateTrackedHead(game.trackedHead + Vec3(0.0, 0.0, -.1)).blocked)
        assertEquals(-.1, game.playerFootWorld().w, 1e-8)
        assertFalse(game.updateTrackedHead(game.trackedHead + Vec3(0.0, .05, 0.0)).blocked)
        assertEquals(stopped.z + .05, game.playerWorld().z, 1e-6)
        assertEquals(1.0, game.playerFootWorld().z, 1e-8)
    }

    @Test fun physicalDiagonalRiseAtNonzeroThetaStopsWithoutMovingTheFeetVertically() {
        val ceiling = Box4("ceiling", Vec4(0.0, 0.0, 2.51, 0.0), Vec4(5.0, 5.0, .01, 5.0))
        val game = FourthwardGame(listOf(level(listOf(ceiling))))
        val start = Vec3(.5, 1.6, -.4)
        game.updateTrackedHead(start)
        game.rotate(PI / 4)
        val delta = Vec3(.5, 4.0, -.6)
        assertTrue(game.updateTrackedHead(start + delta).blocked)
        val head = game.playerWorld()
        val fraction = (head.z - 1.6) / delta.y
        assertTrue(fraction in .19.. .20)
        assertEquals(delta.x * fraction / kotlin.math.sqrt(2.0), head.x, 1e-8)
        assertEquals(delta.x * fraction / kotlin.math.sqrt(2.0), head.y, 1e-8)
        assertEquals(delta.z * fraction, head.w, 1e-8)
        assertEquals(0.0, game.playerFootWorld().z, 1e-8)
        assertEquals(start + delta, game.trackedHead)
        assertEquals(45.0, game.rotationDegrees, 1e-8)
    }

    @Test fun keyboardRiseAndDescentAlsoProtectTheHeadAboveOrBelowTheBodyCapsule() {
        val ceiling = Box4("ceiling", Vec4(0.0, 0.0, 2.51, 0.0), Vec4(5.0, 5.0, .01, 5.0))
        val high = FourthwardGame(listOf(level(listOf(ceiling))))
        high.updateTrackedHead(Vec3(0.0, 2.2, 0.0))
        assertTrue(high.move(Vec3(0.0, 1.0, 0.0)).blocked)
        assertTrue(high.playerWorld().z + high.playerHeadRadius < 2.5)
        assertTrue(high.playerFootWorld().z < .19)

        val low = FourthwardGame(listOf(level(start = Vec4.Z)))
        low.updateTrackedHead(Vec3(0.0, 1.6, 0.0))
        low.updateTrackedHead(Vec3(0.0, -4.0, 0.0))
        val before = low.playerWorld()
        assertTrue(low.move(Vec3(0.0, -.3, 0.0)).blocked)
        assertPoint(before, low.playerWorld())
        assertFalse(low.move(Vec3(0.0, .05, 0.0)).blocked)
        assertEquals(before.z + .05, low.playerWorld().z, 1e-8)
    }

    @Test fun highInitialTrackingFitsUnderTheRoofWithoutMovingRawPoseOrSpawnFeet() {
        val ceiling = Box4("ceiling", Vec4(0.0, 0.0, 2.81, 0.0), Vec4(5.0, 5.0, .01, 5.0))
        val game = FourthwardGame(listOf(level(listOf(ceiling))))
        val raw = Vec3(.7, 3.6, -.4)
        assertFalse(game.updateTrackedHead(raw).blocked)
        assertEquals(raw, game.trackedHead)
        assertPoint(Vec4.ZERO, game.playerFootWorld())
        assertEquals(2.8 - game.playerHeadRadius - .001, game.playerWorld().z, 1e-6)
        assertFalse(game.updateTrackedHead(raw + Vec3(.2, -.05, -.1)).blocked)
        assertPoint(Vec4(.2, 0.0, 0.0, -.1), game.playerFootWorld())
        assertTrue(game.updateTrackedHead(game.trackedHead + Vec3(0.0, .2, 0.0)).blocked)
        game.restartLevel()
        assertPoint(Vec4.ZERO, game.playerFootWorld())
        assertFalse(game.physicalContact)
        assertFalse(game.move(Vec3(.1, 0.0, 0.0)).blocked)
    }

    @Test fun highInitialTrackingWithoutARoofKeepsItsNaturalHeight() {
        val game = FourthwardGame(listOf(level()))
        val raw = Vec3(.7, 3.6, -.4)
        assertFalse(game.updateTrackedHead(raw).blocked)
        assertPoint(Vec4(0.0, 0.0, 3.6, 0.0), game.playerWorld())
        assertEquals(raw, game.trackedHead)
        assertFalse(game.updateTrackedHead(raw - Vec3(0.0, 1.8, 0.0)).blocked)
        assertEquals(1.8, game.playerWorld().z, 1e-8)
        assertEquals(0.0, game.playerFootWorld().z, 1e-8)
    }

    @Test fun unsafeBodySpawnRemainsBlockedAndDoesNotMoveSidewaysToEscape() {
        val wall = Box4("wall", Vec4.ZERO, Vec4(.5, .5, 2.0, .5))
        val game = FourthwardGame(listOf(level(listOf(wall))))
        val raw = Vec3(.7, 3.6, -.4)
        assertTrue(game.updateTrackedHead(raw).blocked)
        assertPoint(Vec4.ZERO, game.playerFootWorld())
        assertEquals(raw, game.trackedHead)
    }

    @Test fun visibleGroundStopsDescentAndStillAllowsGroundedHorizontalMotion() {
        val game = FourthwardGame(listOf(level()))
        game.updateTrackedHead(Vec3(0.0, 1.65, 0.0))
        assertFalse(game.move(Vec3(0.0, 0.0, 0.0)).changed)
        assertTrue(game.move(Vec3(0.0, -.3, 0.0)).blocked)
        assertEquals(0.0, game.playerFootWorld().z, 1e-8)
        val forwardDown = game.move(Vec3(0.0, -.3, -.3))
        assertTrue(forwardDown.changed)
        assertTrue(forwardDown.blocked)
        assertEquals(-.3, game.playerFootWorld().w, 1e-8)
        assertEquals(0.0, game.playerFootWorld().z, 1e-8)
        assertFalse(game.move(Vec3(0.0, .5, 0.0)).blocked)
        assertTrue(game.move(Vec3(0.0, -1.0, 0.0)).blocked)
        assertEquals(0.0, game.playerFootWorld().z, 1e-8)
        assertFalse(game.move(Vec3(0.0, .05, 0.0)).blocked)
        assertEquals(.05, game.playerFootWorld().z, 1e-8)
    }

    @Test fun realHeadCannotEnterVisibleGroundWithoutASolidFloorMesh() {
        val game = FourthwardGame(listOf(level()))
        game.updateTrackedHead(Vec3(0.0, 1.65, 0.0))
        assertTrue(game.updateTrackedHead(Vec3(0.0, -3.0, 0.0)).blocked)
        val stopped = game.playerWorld()
        assertEquals(game.playerHeadRadius + .001, stopped.z, 1e-6)
        repeat(10) {
            assertTrue(game.updateTrackedHead(game.trackedHead - Vec3(0.0, .2, 0.0)).blocked)
            assertEquals(stopped.z, game.playerWorld().z, 1e-6)
            assertEquals(0.0, game.playerFootWorld().z, 1e-8)
        }
        assertFalse(game.updateTrackedHead(game.trackedHead + Vec3(0.0, .05, 0.0)).blocked)
        assertEquals(stopped.z + .05, game.playerWorld().z, 1e-6)
        assertEquals(0.0, game.playerFootWorld().z, 1e-8)
    }

    @Test fun belowGroundInitialPoseRaisesOnlyTheEffectiveHeadAndLeavesFeetAtSpawn() {
        val game = FourthwardGame(listOf(level()))
        val raw = Vec3(.2, -1.0, -.4)
        assertFalse(game.updateTrackedHead(raw).blocked)
        assertEquals(raw, game.trackedHead)
        assertPoint(Vec4.ZERO, game.playerFootWorld())
        assertEquals(game.playerHeadRadius + .001, game.playerWorld().z, 1e-8)
        assertFalse(game.updateTrackedHead(raw + Vec3(.1, .05, -.1)).blocked)
        assertPoint(Vec4(.1, 0.0, 0.0, -.1), game.playerFootWorld())
        assertEquals(game.playerHeadRadius + .051, game.playerWorld().z, 1e-8)
    }
}
