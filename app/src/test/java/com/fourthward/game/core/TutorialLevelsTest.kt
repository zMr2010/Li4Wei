package com.fourthward.game.core

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI

class TutorialLevelsTest {
    private fun thirdLevel() = FourthwardGame(listOf(TutorialLevels.all()[2]))

    @Test fun eachTutorialHasGuidanceASafeSpawnAndOnlyNavigationLevelsHaveAFlag() {
        val levels = TutorialLevels.all()
        assertEquals(15, levels.size)
        assertEquals(15, levels.map { it.id }.toSet().size)
        for (level in levels) {
            assertTrue(level.title.isNotBlank() && level.instruction.isNotBlank() && level.hint.isNotBlank())
            assertEquals("goal", level.goal.id)
            if (level.geometryTopic == null && level.motionTopic == null) assertTrue(level.world.spheres.contains(level.goal))
            else assertTrue(level.world.spheres.none { it.id == level.goal.id })
            assertTrue(level.expedition != null || (level.start - level.goal.center).length() > level.goal.radius)
            val game = FourthwardGame(listOf(level))
            assertFalse(game.updateTrackedHead(Vec3(0.0, 1.65, 0.0)).blocked)
            assertFalse(game.completed)
        }
    }

    @Test fun theThreeGuidedRoutesContinueIntoMovementPuzzles() {
        val game = FourthwardGame()
        assertFalse(game.selectLevel(3))
        assertTrue(game.movePlanar(Vec3(0.0, 0.0, -2.4)).completed)
        assertTrue(game.nextLevel())
        game.rotate(PI / 2)
        assertFalse(game.movePlanar(Vec3(1.8, 0.0, 0.0)).blocked)
        assertTrue(game.movePlanar(Vec3(0.0, 0.0, -1.2)).completed)
        assertTrue(game.nextLevel())
        game.rotate(PI / 2)
        assertFalse(game.movePlanar(Vec3(1.2, 0.0, 0.0)).blocked)
        game.resetAngle()
        assertFalse(game.movePlanar(Vec3(0.0, 0.0, -3.0)).blocked)
        game.rotate(PI / 2)
        assertFalse(game.movePlanar(Vec3(-1.2, 0.0, 0.0)).blocked)
        assertFalse(game.selectLevel(3))
        assertTrue(game.movePlanar(Vec3(0.0, 0.0, -1.2)).completed)
        assertEquals(2, game.levelIndex)
        assertTrue(game.nextLevel())
        assertEquals("crossed-portals", game.level.id)
        assertEquals(6, game.levelIndex)
        assertTrue(game.observationsComplete)
        assertFalse(game.nextLevel())
        game.restartLevel()
        assertFalse(game.completed)
        assertEquals(6, game.levelIndex)
    }

    @Test fun thirdGoalIsSeparatedFromSpawnByAClosedGroundedBarrierAtZeroAngle() {
        val game = thirdLevel()
        val level = game.level
        // Each entire side of this simple closed rectangle, at every capsule height, lies
        // inside a solid convex box. Thus every continuous grounded path from the outside
        // spawn to the inside goal crosses solid geometry, not merely the sampled routes.
        val corners = listOf(Vec4(-3.0, 0.0, 0.0, -2.0), Vec4(3.0, 0.0, 0.0, -2.0),
            Vec4(3.0, 0.0, 0.0, -6.0), Vec4(-3.0, 0.0, 0.0, -6.0))
        fun contains(box: Box4, point: Vec4): Boolean = box.basis.indices.all { axis ->
            kotlin.math.abs((point - box.center).dot(box.basis[axis])) <= box.halfExtents[axis] + 1e-10
        }
        for (index in corners.indices) {
            val a = corners[index]
            val b = corners[(index + 1) % corners.size]
            val side = listOf(a, b, a + Vec4.Z * game.playerHeight, b + Vec4.Z * game.playerHeight)
            assertTrue("A full side of the separating rectangle must be solid", level.world.boxes.any { box ->
                box.solid && side.all { contains(box, it) }
            })
        }
        assertTrue(level.start.w > -2.0)
        assertTrue(level.goal.center.x - level.goal.radius > -3.0)
        assertTrue(level.goal.center.x + level.goal.radius < 3.0)
        assertTrue(level.goal.center.w - level.goal.radius > -6.0)
        assertTrue(level.goal.center.w + level.goal.radius < -2.0)
        assertEquals(0.0, level.start.y, 0.0)
        assertEquals(0.0, game.rotationDegrees, 0.0)
    }

    @Test fun thirdLevelHasSixContinuousFacesEnclosingTheFullGoalSphere() {
        val level = TutorialLevels.all()[2]
        assertEquals(6, level.world.boxes.size)
        val x = listOf(-3.0, 3.0)
        val z = listOf(-0.65, 3.0)
        val w = listOf(-6.0, -2.0)
        val faces = buildList {
            for (fixed in x) add(z.flatMap { vertical -> w.map { depth -> Vec4(fixed, 0.0, vertical, depth) } })
            for (fixed in z) add(x.flatMap { side -> w.map { depth -> Vec4(side, 0.0, fixed, depth) } })
            for (fixed in w) add(x.flatMap { side -> z.map { vertical -> Vec4(side, 0.0, vertical, fixed) } })
        }
        fun contains(box: Box4, p: Vec4) = box.basis.indices.all { axis ->
            kotlin.math.abs((p - box.center).dot(box.basis[axis])) <= box.halfExtents[axis] + 1e-10
        }
        // Convexity covers every point of each rectangular face, including all shared edges.
        faces.forEach { face -> assertTrue(level.world.boxes.any { it.solid && face.all { p -> contains(it, p) } }) }
        val goal = level.goal
        assertTrue(goal.center.x - goal.radius > x.first() && goal.center.x + goal.radius < x.last())
        assertTrue(goal.center.z - goal.radius > z.first() && goal.center.z + goal.radius < z.last())
        assertTrue(goal.center.w - goal.radius > w.first() && goal.center.w + goal.radius < w.last())
        assertTrue(level.start.w > w.last())
        val bottom = level.world.boxes.single { it.id == "tutorial-wall-bottom" }
        assertTrue(bottom.center.z + bottom.halfExtents.z < -0.02)
        val top = level.world.boxes.single { it.id == "tutorial-wall-top" }
        assertTrue(top.center.z - top.halfExtents.z >= 2.8)
    }

    @Test fun ceilingAndBaseBlockVerticalCrossingsWhileTheInteriorStandingCapsuleRemainsClear() {
        val game = thirdLevel()
        val sections = game.sections()
        val bottom = sections.single { it.source.id == "tutorial-wall-bottom" }
        val top = sections.single { it.source.id == "tutorial-wall-top" }
        val standing = Vec3(0.0, 0.0, -4.2)
        assertFalse(SliceGeometry.intersectsCapsule(bottom, standing, game.playerRadius, game.playerHeight))
        assertFalse(SliceGeometry.intersectsCapsule(top, standing, game.playerRadius, game.playerHeight))
        assertTrue(SliceGeometry.intersectsSweptCapsule(bottom, Vec3(0.0, -3.0, -4.2), standing, game.playerRadius, game.playerHeight))
        assertTrue(SliceGeometry.intersectsSweptCapsule(top, Vec3(0.0, 4.0, -4.2), standing, game.playerRadius, game.playerHeight))
    }

    @Test fun walkingAroundEitherEndOfTheFormerSingleWallCannotReachTheGoal() {
        for (side in listOf(-1.0, 1.0)) {
            val game = thirdLevel()
            assertFalse(game.movePlanar(Vec3(side * 4.0, 0.0, 0.0)).blocked)
            assertFalse(game.movePlanar(Vec3(0.0, 0.0, -4.2)).blocked)
            assertTrue(game.movePlanar(Vec3(-side * 4.0, 0.0, 0.0)).blocked)
            assertTrue(kotlin.math.abs(game.playerFootWorld().x) > 3.25)
            assertFalse(game.completed)
            assertEquals(0.0, game.rotationDegrees, 0.0)
        }
    }

    @Test fun walkingBehindTheWholeEnclosureCannotReachTheGoalWithoutRotation() {
        val game = thirdLevel()
        assertFalse(game.movePlanar(Vec3(4.0, 0.0, 0.0)).blocked)
        assertFalse(game.movePlanar(Vec3(0.0, 0.0, -7.0)).blocked)
        assertFalse(game.movePlanar(Vec3(-4.0, 0.0, 0.0)).blocked)
        assertTrue(game.movePlanar(Vec3(0.0, 0.0, 2.8)).blocked)
        assertTrue(game.playerFootWorld().w < -6.25)
        assertFalse(game.completed)
        assertEquals(0.0, game.rotationDegrees, 0.0)
    }

    @Test fun overlappingCornersBlockDiagonalEntriesFromEveryOutsideCorner() {
        for (side in listOf(-1.0, 1.0)) for (rear in listOf(false, true)) {
            val game = thirdLevel()
            assertFalse(game.movePlanar(Vec3(side * 4.0, 0.0, 0.0)).blocked)
            assertFalse(game.movePlanar(Vec3(0.0, 0.0, if (rear) -7.0 else -1.0)).blocked)
            assertTrue(game.movePlanar(Vec3(-side * 2.0, 0.0, if (rear) 2.0 else -2.0)).blocked)
            assertFalse(game.completed)
            assertEquals(0.0, game.rotationDegrees, 0.0)
        }
    }

    @Test fun physicalSideEntryStopsOutsideBothWallsAndAllowsImmediateRetreat() {
        for (side in listOf(-1.0, 1.0)) {
            val game = thirdLevel()
            game.updateTrackedHead(Vec3(0.0, 1.65, 0.0))
            assertFalse(game.updateTrackedHead(Vec3(side * 4.0, 1.65, 0.0)).blocked)
            assertFalse(game.updateTrackedHead(Vec3(side * 4.0, 1.65, -4.2)).blocked)
            assertTrue(game.updateTrackedHead(Vec3(0.0, 1.65, -4.2)).blocked)
            // Actual XR tracking stays raw, while the scene origin keeps the in-game head
            // outside the side wall even after a large physical sample crosses it entirely.
            assertEquals(Vec3(0.0, 1.65, -4.2), game.trackedHead)
            val stopped = game.playerFootWorld()
            assertEquals(side * 3.471, stopped.x, 0.00001)
            assertEquals(-4.2, stopped.w, 1e-8)
            assertTrue(game.physicalContact)
            assertFalse(game.completed)
            assertTrue(game.movePlanar(Vec3(-side * 0.1, 0.0, 0.0)).blocked)
            assertFalse(game.updateTrackedHead(Vec3(side * 0.05, 1.65, -4.2)).blocked)
            assertFalse(game.physicalContact)
            assertEquals(stopped.x + side * 0.05, game.playerFootWorld().x, 1e-8)
        }
    }

    @Test fun fourthDimensionalExcursionLeavesEveryWallAndReturnsInsideTheCourtyard() {
        val game = thirdLevel()
        assertTrue(game.rotate(PI / 2))
        assertFalse(game.movePlanar(Vec3(1.2, 0.0, 0.0)).blocked)
        game.resetAngle()
        assertEquals(1.2, game.playerFootWorld().y, 1e-8)
        assertTrue(game.sections().isEmpty())
        assertFalse(game.movePlanar(Vec3(0.0, 0.0, -3.0)).blocked)
        assertTrue(game.rotate(PI / 2))
        assertFalse(game.movePlanar(Vec3(-1.2, 0.0, 0.0)).blocked)
        assertEquals(0.0, game.playerFootWorld().y, 1e-8)
        assertFalse(game.completed)
        assertTrue(game.movePlanar(Vec3(0.0, 0.0, -1.2)).completed)
    }
}
