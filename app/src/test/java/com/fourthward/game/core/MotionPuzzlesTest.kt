package com.fourthward.game.core

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs

class MotionPuzzlesTest {
    private val levels = MotionPuzzles.levels()
    private fun game(index: Int) = FourthwardGame(listOf(levels[index]))
    private fun wait(game: FourthwardGame, seconds: Double) {
        repeat((seconds * 100).toInt()) { game.tickWorld(.01) }
    }
    private fun move(game: FourthwardGame, side: Double = 0.0, depth: Double = 0.0) {
        val result = game.movePlanar(Vec3(side, 0.0, depth))
        assertFalse("${game.level.id}: blocked at ${game.playerFootWorld()}", result.blocked)
    }
    private fun point(expected: Vec4, actual: Vec4) {
        for (axis in 0..3) assertEquals(expected[axis], actual[axis], 1e-8)
    }

    @Test fun challengeDefinitionsUseStableIdsAndTheMotionChapter() {
        assertEquals(listOf("motion-follow", "motion-drift", "motion-gates"), levels.map { it.id })
        assertEquals(MotionPuzzle.entries.toList(), levels.map { it.motionPuzzle })
        for (level in levels) {
            assertEquals("motion", level.groupId)
            assertEquals("运动", level.groupTitle)
            assertNull(level.geometryTopic)
            assertNull(level.motionTopic)
            assertNotNull(level.expedition)
            assertTrue(level.expedition!!.stages.all { it.hints.size == 3 })
            assertEquals(level.world.boxes.size, level.world.boxes.map { it.id }.distinct().size)
        }
    }

    @Test fun guideWaitsWithoutCompletingThePlayersObjectiveAndResumesContinuously() {
        val game = game(0)
        wait(game, 20.0)
        assertEquals(6.0, game.motionTimeSeconds, 1e-8)
        assertTrue(game.completedCheckpointIds.isEmpty())
        assertFalse(game.completed)
        val stopped = game.world.spheres.single { it.id == "guide-light" }.center
        point(Vec4(0.0, 1.4, .95, -2.8), stopped)
        game.rotate(PI / 2)
        move(game, side = 1.4)
        move(game, depth = -2.8)
        assertEquals(setOf("light-amber"), game.completedCheckpointIds)
        game.tickWorld(.1)
        val resumed = game.world.spheres.single { it.id == "guide-light" }.center
        assertTrue((resumed - stopped).length() in .001.. .066)
        wait(game, 20.0)
        assertEquals(14.0, game.motionTimeSeconds, 1e-8)
        game.rotate(-PI / 2)
        move(game, side = 2.4)
        move(game, depth = -2.6)
        assertTrue(game.goalReady)
        assertFalse(game.completed)
        wait(game, 2.0)
        assertEquals(15.5, game.motionTimeSeconds, 1e-8)
        move(game, depth = -.8)
        assertTrue(game.completed)
    }

    @Test fun guideOffTheCurrentSliceIsAbsentAndAppearsOnlyAfterARealRotation() {
        val game = game(0)
        assertTrue(game.visibleSpheres().any { it.source.id == "guide-light" })
        wait(game, 2.2)
        assertFalse(game.visibleSpheres().any { it.source.id == "guide-light" })
        game.rotate(PI / 2)
        assertTrue(game.visibleSpheres().any { it.source.id == "guide-light" })
        point(Vec4.ZERO, game.playerFootWorld())
    }

    @Test fun theGuideTravelsThroughRealDoorwaysWithoutIntersectingSolidWalls() {
        val level = levels.first()
        for (step in 0..310) {
            val center = MotionPuzzles.guidePosition(step * .05)
            for (wall in level.world.boxes.filter { it.solid }) {
                val relative = center - wall.center
                val distanceSquared = (0..3).sumOf { axis ->
                    val outside = (abs(relative.dot(wall.basis[axis])) - wall.halfExtents[axis]).coerceAtLeast(0.0)
                    outside * outside
                }
                assertTrue("Guide intersects ${wall.id} at t=${step * .05}", distanceSquared > .16 * .16)
            }
        }
    }

    @Test fun driftingSolidActuallyBlocksThenMovesOutOfTheWay() {
        val game = game(1)
        assertTrue(game.movePlanar(Vec3(0.0, 0.0, -4.0)).blocked)
        assertTrue(game.playerFootWorld().w > -2.5)
        assertTrue(game.completedCheckpointIds.isEmpty())
        game.restartLevel()
        wait(game, 4.0)
        assertEquals(4.0, game.motionTimeSeconds, 1e-8)
        assertTrue(game.sections().none { it.source.id == "drifting-block" })
        move(game, depth = -3.4)
        assertEquals(setOf("drift-past"), game.completedCheckpointIds)
        move(game, depth = -1.6)
        assertTrue(game.completed)
    }

    @Test fun staggeredDoorsRequireTwoSeparateOpeningsAndHaveASafeMiddleRegion() {
        val game = game(2)
        assertTrue(game.movePlanar(Vec3(0.0, 0.0, -3.1)).blocked)
        game.restartLevel()
        wait(game, 4.0)
        move(game, depth = -3.1)
        assertEquals(setOf("gates-middle"), game.completedCheckpointIds)
        assertTrue(game.movePlanar(Vec3(0.0, 0.0, -2.4)).blocked)
        assertTrue(game.playerFootWorld().w > -4.7)
        assertFalse(game.completed)
        // The player can retreat to the checkpoint while the second door is shut.
        val blockedAt = game.playerFootWorld().w
        move(game, depth = -3.1 - blockedAt)
        wait(game, 4.0)
        assertEquals(8.0, game.motionTimeSeconds, 1e-8)
        move(game, depth = -2.4)
        assertEquals(setOf("gates-middle", "gates-far"), game.completedCheckpointIds)
        move(game, depth = -.8)
        assertTrue(game.completed)
    }

    @Test fun closingDoorPausesAtThePlayerWithoutMovingTheCameraOrPassingThroughThem() {
        val game = game(2)
        game.updateTrackedHead(Vec3(.3, 1.7, -.4))
        wait(game, 4.0)
        move(game, depth = -2.0)
        val feet = game.playerFootWorld()
        val rawHead = game.trackedHead
        wait(game, 4.0)
        assertTrue(game.motionBlocked)
        assertTrue(game.motionTimeSeconds in 4.0..6.0)
        val stoppedTime = game.motionTimeSeconds
        wait(game, 2.0)
        assertEquals(stoppedTime, game.motionTimeSeconds, 1e-8)
        point(feet, game.playerFootWorld())
        assertEquals(rawHead, game.trackedHead)
        assertTrue(game.sections().filter { it.source.solid }.none {
            SliceGeometry.intersectsCapsule(it, Vec3(rawHead.x, 0.0, rawHead.z), game.playerRadius, game.playerHeight)
        })
        move(game, depth = -1.1)
        wait(game, .5)
        assertTrue(game.motionTimeSeconds > stoppedTime)
    }

    @Test fun checkpointRestoresTheClockTogetherWithTheReachedPose() {
        val game = game(2)
        wait(game, 4.0)
        move(game, depth = -3.1)
        val savedTime = game.motionTimeSeconds
        wait(game, 2.0)
        assertTrue(game.motionTimeSeconds > savedTime)
        move(game, side = .3)
        assertTrue(game.retryAtCheckpoint(Vec3(2.0, 1.5, -4.0)))
        assertEquals(savedTime, game.motionTimeSeconds, 1e-8)
        assertEquals(setOf("gates-middle"), game.completedCheckpointIds)
        assertTrue(game.playerFootWorld().w in -3.1..-2.7)
        assertFalse(game.motionBlocked)
    }

    @Test fun movingSolidsKeepTheirShapesAndRepeatAfterEightSeconds() {
        for (level in levels.drop(1)) for (time in listOf(0.0, .7, 2.5, 4.0, 6.2)) {
            val first = MotionPuzzles.world(level, time, emptySet())
            val repeated = MotionPuzzles.world(level, time + MotionPuzzles.periodSeconds, emptySet())
            val after = MotionPuzzles.world(level, time + .01, emptySet())
            assertEquals(first.boxes.map { it.id }, repeated.boxes.map { it.id })
            for (i in first.boxes.indices) {
                point(first.boxes[i].center, repeated.boxes[i].center)
                assertEquals(first.boxes[i].halfExtents, after.boxes[i].halfExtents)
                assertEquals(first.boxes[i].basis, after.boxes[i].basis)
                assertTrue((after.boxes[i].center - first.boxes[i].center).length() <= .008)
            }
        }
    }

    @Test fun allEightBoundaryFacesStayClosedAtEveryDoorPhase() {
        for (level in levels) for (time in listOf(0.0, 2.0, 4.0, 6.0)) {
            val world = MotionPuzzles.world(level, time, emptySet())
            val shell = world.boxes.filter { it.id.startsWith("boundary-") }
            assertEquals(8, shell.size)
            assertEquals(level.world.boxes.filter { it.id.startsWith("boundary-") }, shell)
            val lower = (0..3).map { axis -> shell.minOf { it.center[axis] - it.halfExtents[axis] } }
            val upper = (0..3).map { axis -> shell.maxOf { it.center[axis] + it.halfExtents[axis] } }
            for (axis in 0..3) for (side in 0..1) {
                val varying = (0..3).filter { it != axis }
                val corners = (0 until 8).map { mask ->
                    val p = DoubleArray(4) { lower[it] }
                    p[axis] = if (side == 0) lower[axis] else upper[axis]
                    varying.forEachIndexed { bit, coordinate ->
                        p[coordinate] = if (mask and (1 shl bit) == 0) lower[coordinate] else upper[coordinate]
                    }
                    Vec4(p[0], p[1], p[2], p[3])
                }
                assertTrue("${level.id}: boundary $axis/$side opened at t=$time",
                    shell.any { wall -> corners.all { point -> contains(wall, point) } })
            }
        }
    }

    @Test fun pauseStopsObjectsAndDoesNotAdvanceOrCompleteAnyMission() {
        for (level in levels) {
            val game = FourthwardGame(listOf(level))
            repeat(100) { assertFalse(game.tickWorld(.1, active = false)) }
            assertEquals(0.0, game.motionTimeSeconds, 0.0)
            assertTrue(game.completedCheckpointIds.isEmpty())
            assertFalse(game.completed)
        }
    }

    private fun contains(box: Box4, point: Vec4): Boolean = (0..3).all { axis ->
        abs((point - box.center).dot(box.basis[axis])) <= box.halfExtents[axis] + 1e-8
    }
}
