package com.fourthward.game.core

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI

class MotionGameTest {
    @Test fun allThreeStudiesAllowWalkingAndCompleteOnlyAfterAnObservedPassage() {
        for (level in MotionStudyLevels.all()) {
            val game = FourthwardGame(listOf(level))
            game.updateTrackedHead(Vec3(0.0, 1.65, 0.0))
            assertFalse(game.locomotionLocked)
            game.movePlanar(Vec3(.25, 0.0, -.2))
            game.updateTrackedHead(Vec3(.15, 1.75, -.1))
            assertEquals(.40, game.playerFootWorld().x, 1e-8)
            assertEquals(-.3, game.playerFootWorld().w, 1e-8)
            assertEquals(1.75, game.playerWorld().z, 1e-8)
            repeat(18) { game.tickWorld(.1) }
            assertFalse(game.completed)
            repeat(45) { game.tickWorld(.1) }
            assertTrue("${level.id}: ${game.observationStep}", game.completed)
            assertEquals(3, game.observationStep)
            assertTrue(game.visibleSpheres().none { it.source.id == "goal" })
            val before = game.playerFootWorld()
            assertTrue(game.movePlanar(Vec3(.2, 0.0, 0.0)).changed)
            assertEquals(before.x + .2, game.playerFootWorld().x, 1e-8)
        }
    }

    @Test fun inactiveWorldAndInvisibleObjectsCannotAutoCompleteAStudy() {
        val game = FourthwardGame(listOf(MotionStudyLevels.all().first()))
        repeat(100) { assertFalse(game.tickWorld(.1, active = false)) }
        assertEquals(0.0, game.motionTimeSeconds, 0.0)
        game.rotate(PI / 2)
        game.movePlanar(Vec3(8.0, 0.0, 0.0))
        game.resetAngle()
        repeat(180) { game.tickWorld(.1) }
        assertFalse(game.completed)
        assertEquals(0, game.observationStep)
        game.restartLevel()
        assertEquals(0.0, game.motionTimeSeconds, 0.0)
        repeat(65) { game.tickWorld(.1) }
        assertTrue(game.completed)
    }

    @Test fun inPlaneConstantRadiusMotionCannotCountAsNormalPassage() {
        val game = FourthwardGame(listOf(MotionStudyLevels.all().first()))
        game.rotate(PI / 2)
        repeat(150) { game.tickWorld(.1) }
        assertEquals(MotionStudies.sphereRadius, game.visibleSpheres().single().radius, 1e-8)
        assertEquals(0, game.observationStep)
        assertFalse(game.completed)
    }

    @Test fun leavingAfterTheMiddleCannotReplaceActuallySeeingTheDeparture() {
        val game = FourthwardGame(listOf(MotionStudyLevels.all().first()))
        repeat(32) { game.tickWorld(.1) }
        assertEquals(2, game.observationStep)
        game.rotate(PI / 2)
        game.movePlanar(Vec3(8.0, 0.0, 0.0))
        game.resetAngle()
        repeat(150) { game.tickWorld(.1) }
        assertTrue(game.visibleSpheres().isEmpty())
        assertEquals(2, game.observationStep)
        assertFalse(game.completed)
    }
}
