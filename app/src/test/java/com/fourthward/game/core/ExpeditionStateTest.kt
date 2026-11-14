package com.fourthward.game.core

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI

class ExpeditionStateTest {
    private fun checkpoint(id: String, point: Vec4) = MissionCheckpoint(
        id, "路标 $id", Sphere4(id, point, .2), 0xFF67E8DB.toInt())

    private fun level(
        ordered: Boolean = true,
        checkpoints: List<MissionCheckpoint> = listOf(
            checkpoint("first", Vec4(0.0, 0.0, 0.0, -2.0)),
            checkpoint("second", Vec4(1.0, 0.0, 0.0, 0.0)),
        ),
        boxes: List<Box4> = emptyList(),
    ): TutorialLevel {
        val goal = Sphere4("goal", Vec4.ZERO, .2)
        return TutorialLevel("test-expedition", "归航", "寻找路标。", "观察四周。", goal,
            0xFF67E8DB.toInt(), World4(boxes, checkpoints.map { it.volume } + goal),
            expedition = ExpeditionDefinition(checkpoints, ordered,
                List(checkpoints.size + 1) { index -> MissionStage("目标 $index", List(3) { "提示 $index / ${it + 1}" }) }))
    }

    private fun assertPoint(expected: Vec4, actual: Vec4) {
        for (axis in 0..3) assertEquals(expected[axis], actual[axis], 1e-8)
    }

    @Test fun returnGoalAtSpawnWaitsForAllOrderedLandmarksAndARealReturn() {
        var saves = 0
        val game = FourthwardGame(listOf(level()), onProgressChanged = { saves++ })
        assertFalse(game.completed)
        assertFalse(game.goalReady)
        assertFalse(game.hasCheckpoint)
        game.movePlanar(Vec3(1.0, 0.0, 0.0))
        assertTrue("Second landmark cannot be visited first", game.completedCheckpointIds.isEmpty())
        game.movePlanar(Vec3(-1.0, 0.0, -2.0))
        assertEquals(setOf("first"), game.completedCheckpointIds)
        assertFalse(game.goalReady)
        game.movePlanar(Vec3(1.0, 0.0, 2.0))
        assertEquals(setOf("first", "second"), game.completedCheckpointIds)
        assertTrue(game.goalReady)
        assertFalse(game.completed)
        assertEquals(0, saves)
        game.movePlanar(Vec3(-1.0, 0.0, 0.0))
        assertTrue(game.completed)
        assertEquals(1, saves)
        assertEquals(setOf("test-expedition"), game.progress.completedIds)
        game.rotate(.1)
        game.updateTrackedHead(game.trackedHead)
        assertEquals("Completion persists once", 1, saves)
    }

    @Test fun unorderedBeaconsCanBeReachedInEitherOrderButStillRequireReturning() {
        for (secondFirst in listOf(false, true)) {
            val game = FourthwardGame(listOf(level(ordered = false)))
            if (secondFirst) {
                game.movePlanar(Vec3(1.0, 0.0, 0.0))
                assertEquals(setOf("second"), game.completedCheckpointIds)
                game.movePlanar(Vec3(-1.0, 0.0, -2.0))
            } else {
                game.movePlanar(Vec3(0.0, 0.0, -2.0))
                assertEquals(setOf("first"), game.completedCheckpointIds)
                game.movePlanar(Vec3(1.0, 0.0, 2.0))
            }
            assertTrue(game.goalReady)
            assertFalse(game.completed)
            val feet = game.playerFootWorld()
            game.movePlanar(Vec3(-feet.x, 0.0, -feet.w))
            assertTrue(game.completed)
        }
    }

    @Test fun hintsRevealThreeTiersAndResetWhenTheObjectiveChanges() {
        val game = FourthwardGame(listOf(level()))
        val idsBefore = game.completedCheckpointIds
        val initialRevision = game.missionRevision
        assertEquals("目标 0", game.missionInstruction)
        assertNull(game.missionHint)
        for (tier in 1..3) {
            assertTrue(game.requestHint())
            assertEquals(tier, game.hintTier)
            assertEquals("提示 0 / $tier", game.missionHint)
        }
        assertFalse(game.requestHint())
        assertEquals(initialRevision + 3, game.missionRevision)
        game.movePlanar(Vec3(0.0, 0.0, -2.0))
        assertEquals("目标 1", game.missionInstruction)
        assertEquals(0, game.hintTier)
        assertNull(game.missionHint)
        assertTrue("Previously returned snapshots remain unchanged", idsBefore.isEmpty())
        assertTrue(game.requestHint())
        assertEquals("提示 1 / 1", game.missionHint)
    }

    @Test fun checkpointRetryUsesReachedPoseAndAngleWithTheCurrentPhysicalOrigin() {
        val checkpoints = listOf(
            checkpoint("hidden", Vec4(0.0, 1.0, 0.0, -1.0)),
            checkpoint("far", Vec4(2.0, 1.0, 0.0, -2.0)),
        )
        val game = FourthwardGame(listOf(level(checkpoints = checkpoints)))
        val rawStart = Vec3(.4, 1.7, -.3)
        game.updateTrackedHead(rawStart)
        assertTrue(game.visibleSpheres().none { it.source.id == "hidden" })
        game.rotate(PI / 2)
        assertTrue(game.visibleSpheres().any { it.source.id == "hidden" })
        assertTrue(game.completedCheckpointIds.isEmpty())
        // Physical movement is the only operation that reaches the landmark here.
        game.updateTrackedHead(rawStart + Vec3(1.0, 0.0, -1.0))
        val savedFeet = game.playerFootWorld()
        assertEquals(setOf("hidden"), game.completedCheckpointIds)
        assertTrue(game.hasCheckpoint)
        assertEquals("路标 hidden", game.checkpointTitle)
        game.rotate(-PI / 2)
        game.movePlanar(Vec3(.7, 0.0, -.4))
        game.requestHint()
        val rawOnRetry = Vec3(5.0, 1.55, -2.0)
        assertTrue(game.retryAtCheckpoint(rawOnRetry))
        assertEquals(rawOnRetry, game.trackedHead)
        assertEquals(90.0, game.rotationDegrees, 1e-8)
        assertPoint(savedFeet, game.playerFootWorld())
        assertEquals(setOf("hidden"), game.completedCheckpointIds)
        assertFalse(game.physicalContact)
        assertFalse(game.lastMotionBlocked)
        assertEquals(0, game.hintTier)
        assertFalse(game.updateTrackedHead(rawOnRetry).changed)
        game.updateTrackedHead(rawOnRetry + Vec3(.1, 0.0, 0.0))
        assertPoint(savedFeet + Vec4.Y * .1, game.playerFootWorld())
    }

    @Test fun rawHeadCrossingAWallCannotCollectTheLandmarkBeyondIt() {
        val target = checkpoint("behind-wall", Vec4(0.0, 0.0, 0.0, -2.0))
        val wall = Box4("wall", Vec4(0.0, 0.0, 1.0, -.7), Vec4(3.0, 3.0, 2.0, .1))
        val game = FourthwardGame(listOf(level(checkpoints = listOf(target), boxes = listOf(wall))))
        game.updateTrackedHead(Vec3(0.0, 1.65, 0.0))
        assertTrue(game.updateTrackedHead(Vec3(0.0, 1.65, -2.0)).blocked)
        assertTrue(game.physicalContact)
        assertTrue(game.playerFootWorld().w > -.4)
        assertTrue(game.completedCheckpointIds.isEmpty())
        assertFalse(game.hasCheckpoint)
        assertFalse(game.completed)
        assertFalse(game.retryAtCheckpoint())
        assertPoint(Vec4.ZERO, game.playerFootWorld())
        assertFalse(game.physicalContact)
        game.updateTrackedHead(game.trackedHead + Vec3(0.0, 0.0, .1))
        assertEquals(.1, game.playerFootWorld().w, 1e-8)
    }

    @Test fun freshSelectionResetsRunCheckpointsWhileCompletedProgressSurvives() {
        val game = FourthwardGame(listOf(level()))
        game.movePlanar(Vec3(0.0, 0.0, -2.0))
        assertTrue(game.hasCheckpoint)
        game.requestHint()
        assertTrue(game.selectLevel(0))
        assertTrue(game.completedCheckpointIds.isEmpty())
        assertFalse(game.hasCheckpoint)
        assertNull(game.checkpointTitle)
        assertEquals(0, game.hintTier)
        assertFalse(game.completed)
        game.movePlanar(Vec3(0.0, 0.0, -2.0))
        game.movePlanar(Vec3(1.0, 0.0, 2.0))
        game.movePlanar(Vec3(-1.0, 0.0, 0.0))
        assertTrue(game.completed)
        assertFalse("Retrying a completed run starts fresh", game.retryAtCheckpoint())
        assertFalse(game.completed)
        assertTrue(game.completedCheckpointIds.isEmpty())
        assertTrue(game.progress.isCompleted(0))
    }

    @Test fun tutorialsHaveNoMissionHintsOrCheckpointState() {
        val game = FourthwardGame()
        assertNull(game.missionInstruction)
        assertNull(game.missionHint)
        assertFalse(game.requestHint())
        assertTrue(game.goalReady)
        assertFalse(game.hasCheckpoint)
        assertFalse(game.retryAtCheckpoint())
        assertTrue(game.completedCheckpointIds.isEmpty())
    }
}
