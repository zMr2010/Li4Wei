package com.fourthward.game.core

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class FourthwardGameTest {
    private fun assertVector(expected: Vec4, actual: Vec4) {
        for (axis in 0..3) assertEquals(expected[axis], actual[axis], 1e-8)
    }
    private fun thirdLevel() = FourthwardGame(listOf(TutorialLevels.all()[2]))

    @Test fun soleAngleWrapsAndResetPreservesPlayerPosition() {
        val game = FourthwardGame()
        game.updateTrackedHead(Vec3(0.7, 1.6, -0.3))
        assertFalse(game.rotate(0.0))
        assertFalse(game.rotate(2 * PI))
        assertTrue(game.rotate(-PI / 2))
        assertEquals(270.0, game.rotationDegrees, 1e-8)
        assertTrue(game.rotate(PI))
        assertEquals(90.0, game.rotationDegrees, 1e-8)
        assertFalse(game.rotate(4 * PI))
        val position = game.playerWorld()
        game.resetAngle()
        assertEquals(0.0, game.rotationDegrees, 1e-8)
        assertVector(position, game.playerWorld())
    }

    @Test fun resetAngleCanonicalizesIncrementalRoundoffWithoutMovingThePlayer() {
        val game = FourthwardGame()
        game.updateTrackedHead(Vec3(0.73, 1.67, -0.41))
        for (angle in listOf(0.1, 0.37, PI / 2, 2.718, -0.37, 5.9)) {
            game.rotate(angle)
            game.movePlanar(Vec3(0.17, 0.0, -0.11))
            val head = game.playerWorld()
            val feet = game.playerFootWorld()
            game.resetAngle()
            assertEquals("Canonical radians after resetting $angle", 0.0, game.frame.angleRadians, 0.0)
            assertEquals("Canonical degrees after resetting $angle", 0.0, game.rotationDegrees, 0.0)
            assertVector(head, game.playerWorld())
            assertVector(feet, game.playerFootWorld())
        }
    }

    @Test fun firstTrackedSampleRecentersSpawnAroundActualPhysicalFeet() {
        val game = FourthwardGame()
        val head = Vec3(0.8, 1.72, -0.35)
        val result = game.updateTrackedHead(head)
        assertTrue(result.changed)
        assertFalse(result.blocked)
        assertVector(Vec4.ZERO, game.playerFootWorld())
        assertVector(Vec4(0.0, 0.0, 1.72, 0.0), game.playerWorld())
        val goal = game.visibleSpheres().single { it.source.id == "goal" }
        assertEquals(head.x, goal.center.x, 1e-8)
        assertEquals(head.z - 2.4, goal.center.z, 1e-8)
    }

    @Test fun physicalHeadTranslationMapsAllThreeComponentsWithoutChangingTheta() {
        val game = FourthwardGame()
        game.updateTrackedHead(Vec3(0.4, 1.6, -0.2))
        game.rotate(PI / 3)
        val frame = game.frame
        game.updateTrackedHead(Vec3(0.6, 1.3, -0.5))
        assertVector(Vec4(0.1, 0.2 * sqrt(3.0) / 2, 1.3, -0.3), game.playerWorld())
        assertSame(frame, game.frame)
        assertEquals(60.0, game.rotationDegrees, 1e-8)
        assertEquals(0.0, game.frame.signedDistance(game.playerWorld()), 1e-8)
    }

    @Test fun rotationKeepsHeadAndFeetFixedInFourDimensions() {
        val game = FourthwardGame()
        game.updateTrackedHead(Vec3(0.5, 1.6, -0.4))
        game.movePlanar(Vec3(0.6, 0.0, -0.3))
        val head = game.playerWorld(); val feet = game.playerFootWorld(); val origin = game.frame.origin
        assertTrue(game.rotate(PI / 2))
        assertVector(head, game.playerWorld())
        assertVector(feet, game.playerFootWorld())
        assertTrue((game.frame.origin - origin).length() > 0.5)
        assertEquals(Vec4.Z, game.frame.up)
        assertEquals(Vec4.W, game.frame.forward)
    }

    @Test fun planarMovementUsesCurrentSliceHeadingAndNeverControllerHeight() {
        val game = FourthwardGame()
        assertFalse(game.movePlanar(Vec3(0.0, 100.0, 0.0)).changed)
        game.rotate(PI / 2)
        val result = game.movePlanar(Vec3(0.6, 50.0, -0.3))
        assertTrue(result.changed)
        assertFalse(result.blocked)
        assertVector(Vec4(0.0, 0.6, 0.0, -0.3), game.playerFootWorld())
        assertEquals(1.65, game.playerWorld().z, 1e-8)
        assertEquals(0.0, game.frame.origin.z, 1e-8)
    }

    @Test fun offPlaneGoalCannotCompleteAndRotationAloneDoesNotReachIt() {
        val game = FourthwardGame(listOf(TutorialLevels.all()[1]))
        assertTrue(game.visibleSpheres().none { it.source.id == "goal" })
        assertFalse(game.completed)
        val feet = game.playerFootWorld()
        game.rotate(PI / 2)
        assertTrue(game.visibleSpheres().any { it.source.id == "goal" })
        assertVector(feet, game.playerFootWorld())
        assertFalse(game.completed)
        game.movePlanar(Vec3(1.8, 0.0, 0.0))
        assertFalse(game.completed)
        assertTrue(game.movePlanar(Vec3(0.0, 0.0, -1.2)).completed)
        assertTrue((game.playerFootWorld() - game.level.goal.center).length() <= game.level.goal.radius + 1e-8)
    }

    @Test fun wallBlocksDirectMovementAtEveryStartingAngle() {
        for (degrees in listOf(0.0, 30.0, 60.0, 90.0, 120.0, 150.0, 180.0)) {
            val game = thirdLevel()
            game.rotate(Math.toRadians(degrees))
            assertTrue(game.sections().any { it.source.id == "tutorial-wall" })
            val movement = game.movePlanar(Vec3(0.0, 0.0, -4.2))
            assertTrue("Blocked at $degrees degrees", movement.blocked)
            assertTrue(game.playerFootWorld().w > -1.54)
            assertFalse(game.completed)
        }
    }

    @Test fun fourthDimensionalDetourClearsWallAndReachesThirdExit() {
        val game = thirdLevel()
        assertTrue(game.rotate(PI / 2))
        assertFalse(game.movePlanar(Vec3(1.2, 0.0, 0.0)).blocked)
        game.resetAngle()
        assertEquals(1.2, game.playerFootWorld().y, 1e-8)
        assertTrue(game.sections().none { it.source.id == "tutorial-wall" })
        assertFalse(game.movePlanar(Vec3(0.0, 0.0, -3.0)).blocked)
        game.rotate(PI / 2)
        assertFalse(game.movePlanar(Vec3(-1.2, 0.0, 0.0)).blocked)
        assertFalse(game.completed)
        assertTrue(game.movePlanar(Vec3(0.0, 0.0, -1.2)).completed)
    }

    @Test fun physicalWallCrossingClampsWorldPositionWhileKeepingTheRawHeadPose() {
        val game = thirdLevel()
        game.updateTrackedHead(Vec3(0.0, 1.6, 0.0))
        val rawHead = Vec3(0.0, 1.6, -4.2)
        val crossed = game.updateTrackedHead(rawHead)
        assertTrue(crossed.blocked)
        assertTrue(game.physicalContact)
        assertEquals(rawHead, game.trackedHead)
        // Front surface is W=-1.75; the capsule stops one radius plus a small clearance away.
        assertEquals(-1.529, game.playerWorld().w, 0.00001)
        assertFalse(game.completed)
        assertTrue(game.sections().filter { it.source.solid }.none {
            SliceGeometry.intersectsCapsule(it, Vec3(rawHead.x, 0.0, rawHead.z), game.playerRadius, game.playerHeight)
        })
    }

    @Test fun continuedPhysicalPressureDoesNotMoveTheViewAndSmallRetreatHasNoMovementDebt() {
        val game = thirdLevel()
        game.updateTrackedHead(Vec3(0.0, 1.6, 0.0))
        game.updateTrackedHead(Vec3(0.0, 1.6, -4.2))
        val stopped = game.playerWorld()
        repeat(20) { index ->
            assertTrue(game.updateTrackedHead(Vec3(0.0, 1.6, -4.2 - (index + 1) * 0.1)).blocked)
            assertEquals(stopped.w, game.playerWorld().w, 0.00001)
            assertFalse(game.completed)
        }
        val rawBeforeRetreat = game.trackedHead
        assertFalse(game.updateTrackedHead(rawBeforeRetreat + Vec3(0.0, 0.0, 0.05)).blocked)
        assertFalse(game.physicalContact)
        assertEquals(stopped.w + 0.05, game.playerWorld().w, 0.00001)
    }

    @Test fun keyboardMovementAndRotationAreCheckedIndependentlyAfterPhysicalContact() {
        val game = thirdLevel()
        game.updateTrackedHead(Vec3(0.0, 1.6, 0.0))
        game.updateTrackedHead(Vec3(0.0, 1.6, -4.2))
        assertTrue(game.physicalContact)
        val stopped = game.playerWorld()
        assertTrue(game.movePlanar(Vec3(0.0, 0.0, -0.2)).blocked)
        assertVector(stopped, game.playerWorld())
        assertTrue(game.rotate(PI / 2))
        assertVector(stopped, game.playerWorld())
        assertFalse(game.lastMotionBlocked)
        val retreat = game.movePlanar(Vec3(0.0, 0.0, 0.1))
        assertTrue(retreat.changed)
        assertFalse(retreat.blocked)
        assertEquals(stopped.w + 0.1, game.playerWorld().w, 1e-8)
    }

    @Test fun physicalCollisionAtNonzeroAngleCompensatesBothSliceDirectionsAndRetainsHeight() {
        val game = thirdLevel()
        val initialHead = Vec3(0.73, 1.6, -0.41)
        game.updateTrackedHead(initialHead)
        val theta = PI / 4
        game.rotate(theta)
        val rawHead = initialHead + Vec3(0.2, -0.2, -4.2)
        assertTrue(game.updateTrackedHead(rawHead).blocked)
        assertEquals(rawHead, game.trackedHead)
        assertEquals(45.0, game.rotationDegrees, 1e-8)
        val world = game.playerWorld()
        val fraction = world.w / -4.2
        assertTrue(fraction in 0.35..0.38)
        assertEquals(cos(theta) * 0.2 * fraction, world.x, 1e-8)
        assertEquals(sin(theta) * 0.2 * fraction, world.y, 1e-8)
        assertEquals(1.4, world.z, 1e-8)
        assertEquals(0.0, game.frame.signedDistance(world), 1e-8)
        val beforeRetreat = game.playerWorld()
        assertFalse(game.updateTrackedHead(rawHead + Vec3(0.0, 0.0, 0.05)).blocked)
        assertVector(beforeRetreat + Vec4.W * 0.05, game.playerWorld())
    }

    @Test fun restartAfterPhysicalContactRebasesAroundCurrentRawPoseWithoutResidualMotion() {
        val game = thirdLevel()
        game.updateTrackedHead(Vec3(0.5, 1.72, -0.3))
        game.rotate(PI / 4)
        val rawHead = Vec3(0.5, 1.72, -5.0)
        assertTrue(game.updateTrackedHead(rawHead).blocked)
        game.restartLevel()
        assertEquals(rawHead, game.trackedHead)
        assertVector(game.level.start, game.playerFootWorld())
        assertFalse(game.physicalContact)
        assertFalse(game.lastMotionBlocked)
        assertEquals(0.0, game.rotationDegrees, 0.0)
        assertFalse(game.updateTrackedHead(rawHead).blocked)
        assertVector(game.level.start, game.playerFootWorld())
        assertFalse(game.updateTrackedHead(rawHead + Vec3(0.1, 0.0, -0.2)).blocked)
        assertVector(game.level.start + Vec4.X * 0.1 - Vec4.W * 0.2, game.playerFootWorld())
    }

    @Test fun lessonExitCanAdoptANewRawPoseWithoutWalkingTheOldGameplayPath() {
        val game = FourthwardGame()
        game.updateTrackedHead(Vec3(0.0, 1.65, 0.0))
        // As gameplay motion this reaches the first goal; during a lesson it must only
        // establish the fresh tracking origin for starting gameplay at the spawn.
        val afterLesson = Vec3(0.0, 1.72, -2.4)
        game.restartLevel(afterLesson)
        assertEquals(afterLesson, game.trackedHead)
        assertTrue(game.progress.completedIds.isEmpty())
        assertFalse(game.progress.isUnlocked(1))
        assertVector(game.level.start, game.playerFootWorld())
        assertFalse(game.completed)
        assertFalse(game.nextLevel())
        assertFalse(game.updateTrackedHead(afterLesson).changed)
        assertVector(game.level.start, game.playerFootWorld())
        assertFalse(game.completed)
        assertFalse(game.updateTrackedHead(afterLesson + Vec3(0.0, 0.0, -0.1)).blocked)
        assertVector(Vec4(0.0, 0.0, 0.0, -0.1), game.playerFootWorld())
    }

    @Test fun restartAndNextLevelPreserveTrackingAndRequireActualCompletion() {
        val game = FourthwardGame()
        val head = Vec3(0.4, 1.75, -0.2)
        game.updateTrackedHead(head)
        assertFalse(game.nextLevel())
        game.movePlanar(Vec3(0.0, 0.0, -2.4))
        assertTrue(game.completed)
        assertTrue(game.nextLevel())
        assertEquals(1, game.levelIndex)
        assertEquals("turn", game.level.id)
        assertFalse(game.completed)
        assertEquals(head, game.trackedHead)
        assertVector(Vec4.ZERO, game.playerFootWorld())
        assertEquals(1.75, game.playerWorld().z, 1e-8)
        game.rotate(PI / 2)
        game.movePlanar(Vec3(1.8, 0.0, -1.2))
        assertTrue(game.completed)
        game.restartLevel()
        assertEquals(1, game.levelIndex)
        assertFalse(game.completed)
        assertEquals(0.0, game.rotationDegrees, 0.0)
        assertVector(Vec4.ZERO, game.playerFootWorld())
    }

    @Test fun enteringAndRetryingAStageUseItsAuthoredPositionAndSliceAngle() {
        val previous = TutorialLevels.all().first()
        val stage = TutorialLevels.all()[1].copy(start = Vec4(.8, -.3, .2, .7), startAngleRadians = PI / 3)
        val game = FourthwardGame(listOf(previous, stage))
        val head = Vec3(1.3, 1.69, -1.8)
        game.updateTrackedHead(head)
        game.rotate(.7)
        game.movePlanar(Vec3(0.0, 0.0, -2.4))
        assertTrue(game.nextLevel())
        assertVector(stage.start, game.playerFootWorld())
        assertEquals(60.0, game.rotationDegrees, 1e-8)
        assertEquals(head, game.trackedHead)
        game.rotate(.9)
        game.movePlanar(Vec3(.25, 0.0, -.8))
        game.restartLevel()
        assertVector(stage.start, game.playerFootWorld())
        assertEquals(60.0, game.rotationDegrees, 1e-8)
    }
    @Test fun selectionUsesPersistentUnlocksAndCannotSkipOrResetProgress() {
        val saves = mutableListOf<Set<String>>()
        val levels = TutorialLevels.all()
        val game = FourthwardGame(levels, onProgressChanged = { saves += it.completedIds })
        assertFalse(game.selectLevel(-1))
        assertFalse(game.selectLevel(1))
        assertFalse(game.selectLevel(2))
        assertFalse(game.selectLevel(3))
        assertEquals(0, game.levelIndex)
        game.movePlanar(Vec3(0.0, 0.0, -2.4))
        assertEquals(listOf(setOf("walk")), saves)
        assertTrue(game.selectLevel(0))
        assertFalse(game.completed)
        assertTrue(game.progress.isUnlocked(1))
        assertTrue(game.selectLevel(1))
        assertFalse(game.selectLevel(2))
        game.rotate(PI / 2)
        game.movePlanar(Vec3(1.8, 0.0, -1.2))
        assertEquals(listOf(setOf("walk"), setOf("walk", "turn")), saves)
        assertTrue(game.selectLevel(2))
        game.restartLevel(Vec3(20.0, 1.7, -30.0))
        assertEquals(setOf("walk", "turn"), game.progress.completedIds)
        val loaded = FourthwardGame(levels, LevelProgress(levels.map { it.id }, saves.last()))
        assertTrue(loaded.selectLevel(2))
        assertFalse(loaded.completed)
        assertEquals("around", loaded.level.id)
        assertTrue(loaded.selectLevel(0))
        loaded.movePlanar(Vec3(0.0, 0.0, -2.4))
        assertEquals(2, loaded.progress.completedCount)
    }

}
