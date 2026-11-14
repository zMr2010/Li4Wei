package com.fourthward.game.core

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI

class LessonTutorialTest {
    @Test fun everyLevelHasDistinctNamedNarratedStepsAndAnExplicitReadyStep() {
        val lessons = LessonTutorials.all()
        assertEquals(TutorialLevels.all().count { it.expedition == null }, lessons.size)
        val steps = lessons.flatMap { it.steps }
        assertEquals(16, lessons.take(3).sumOf { it.steps.size })
        assertEquals(41, steps.size)
        assertEquals(steps.size, steps.map { it.id }.toSet().size)
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 9, 10, 11), lessons.map { it.levelIndex })
        lessons.forEach { lesson ->
            assertTrue(lesson.steps.any { it.durationSeconds > 0.0 })
            assertTrue(lesson.steps.all { it.title.isNotBlank() && it.narration.isNotBlank() })
            assertTrue(lesson.steps.last().id.endsWith("_ready"))
            assertEquals(0.0, lesson.steps.last().durationSeconds, 0.0)
        }
    }

    @Test fun timeNeverChangesStepsOrStartsTheGameAndAnimationsFreezeAtTheirEnd() {
        for (definition in LessonTutorials.all()) {
            val player = LessonPlayer(definition.levelIndex)
            assertFalse(player.tick(100.0))
            assertEquals(0, player.stepIndex)
            assertTrue(player.next())
            val activeIndex = player.stepIndex
            assertTrue(player.isAnimating)
            assertFalse(player.next()) // A second click cannot skip an in-flight explanation.
            assertTrue(player.tick(100.0))
            val frozen = player.scene
            repeat(10) { assertFalse(player.tick(100.0)) }
            assertEquals(frozen, player.scene)
            assertEquals(activeIndex, player.stepIndex)
            while (!player.isLastStep) {
                assertTrue(player.next())
                player.tick(100.0)
            }
            val ready = player.scene
            assertFalse(player.next()) // Explicit game-start remains the caller's action.
            assertFalse(player.tick(100.0))
            assertEquals(ready, player.scene)
        }
    }

    @Test fun geometryLessonsUseTheSharedTopicsAndOnlyRotateTheirTrueSections() {
        val expected = listOf(GeometryTopic.HYPERSPHERE, GeometryTopic.TESSERACT, GeometryTopic.AMBIGUOUS_SECTION)
        assertEquals(expected, LessonTutorials.all().filter { it.geometryTopic != null }.map { it.geometryTopic })
        assertTrue(LessonTutorials.all().take(3).all { it.geometryTopic == null })
        for (definition in LessonTutorials.all().filter { it.geometryTopic != null }) {
            assertTrue(definition.walls.isEmpty())
            assertTrue(definition.steps.all {
                it.id.startsWith("l${definition.levelIndex + 1}_") && it.from.character == Vec3.ZERO && it.to.character == Vec3.ZERO
            })
            val player = LessonPlayer(definition.levelIndex)
            do {
                player.tick(100.0)
                assertEquals(definition.geometryTopic, player.scene.geometryTopic)
                assertTrue(player.scene.path.isEmpty())
                assertFalse(player.scene.blocked)
                assertFalse(player.scene.goalReached)
            } while (player.next())
        }
        assertEquals(listOf(0.0, PI / 4, 3 * PI / 4, PI / 4, PI / 4),
            LessonTutorials.lesson(3).steps.map { it.to.angleRadians })
        for (level in 4..5) assertEquals(listOf(0.0, PI / 4, PI / 2, PI / 2),
            LessonTutorials.lesson(level).steps.map { it.to.angleRadians })
    }

    @Test fun focusLossPausesTheAnimationAndDoesNotReplayElapsedBackgroundTime() {
        val player = LessonPlayer(1)
        assertTrue(player.next())
        player.tick(0.5)
        val before = player.scene
        val elapsed = player.elapsedSeconds
        assertFalse(player.tick(50.0, active = false))
        assertEquals(elapsed, player.elapsedSeconds, 0.0)
        assertEquals(before, player.scene)
        assertTrue(player.tick(0.1))
        assertEquals(elapsed + 0.1, player.elapsedSeconds, 1e-12)
        assertEquals(1, player.stepIndex)
    }

    @Test fun previousReplayAndRestartAreDeterministicIncludingInterruptedAnimations() {
        val player = LessonPlayer(2)
        assertFalse(player.previous())
        player.next()
        val start = player.scene
        player.tick(0.6)
        assertNotEquals(start, player.scene)
        val version = player.revision
        player.replay()
        assertEquals(start, player.scene)
        assertEquals(version + 1, player.revision)
        player.tick(0.3)
        assertTrue(player.previous())
        assertEquals(0, player.stepIndex)
        assertEquals(0.0, player.elapsedSeconds, 0.0)
        player.next(); player.tick(10.0); player.next(); player.tick(0.4)
        player.restart()
        assertEquals(0, player.stepIndex)
        assertEquals(LessonPlayer(2).scene, player.scene)
    }

    @Test fun everyAnimatedPoseKeepsTheCharacterInAVerticalPlaneAndRotationDoesNotTranslateIt() {
        for (definition in LessonTutorials.all()) {
            val player = LessonPlayer(definition.levelIndex)
            do {
                val step = player.currentStep
                val rotates = abs(step.to.angleRadians - step.from.angleRadians) > 1e-9
                if (rotates) assertEquals(step.from.character, step.to.character)
                for (sample in 0..20) {
                    val scene = player.scene
                    assertEquals(0.0, scene.planeNormal.y, 0.0)
                    assertEquals(0.0, scene.planeRight.y, 0.0)
                    assertEquals(1.0, scene.planeNormal.length(), 1e-12)
                    assertEquals(0.0, (scene.character - scene.planeOrigin).dot(scene.planeNormal), 1e-12)
                    if (rotates) assertEquals(step.from.character, scene.character)
                    else {
                        val normal = Vec3(-sin(step.from.angleRadians), 0.0, cos(step.from.angleRadians))
                        assertEquals(0.0, (scene.character - step.from.character).dot(normal), 1e-12)
                    }
                    player.tick(step.durationSeconds / 20.0)
                }
                player.tick(1.0) // Remove accumulated floating-point residue at the last sample.
            } while (player.next())
        }
    }

    @Test fun secondLessonRevealsTheOffPlaneGoalByRotationWithoutMovingTheCharacter() {
        val player = LessonPlayer(1)
        assertFalse(player.scene.goalInPlane)
        val initialPosition = player.scene.character
        player.next(); player.tick(10.0)
        assertEquals(initialPosition, player.scene.character)
        assertTrue(player.scene.goalInPlane)
        assertFalse(player.scene.goalReached)
        player.next(); player.tick(10.0)
        assertEquals(player.scene.goal, player.scene.character)
        assertTrue(player.scene.goalReached)
    }

    @Test fun thirdLessonHasAClosedTwoDimensionalBarrierButItsThreeDimensionalRouteNeverCrossesWalls() {
        val player = LessonPlayer(2)
        val definition = player.definition
        assertEquals(4, definition.walls.size)
        // The four sides of this rectangle are wholly inside four convex box sections.
        val corners = listOf(Vec3(0.0, -0.45, 0.0), Vec3(0.90, -0.45, 0.0),
            Vec3(0.90, 0.25, 0.0), Vec3(0.0, 0.25, 0.0))
        for (i in corners.indices) assertTrue(definition.walls.any { box ->
            inside(box, corners[i]) && inside(box, corners[(i + 1) % corners.size])
        })
        assertTrue(player.scene.character.x < 0.0)
        assertTrue(definition.goal.x in 0.0..0.90 && definition.goal.y in -0.45..0.25)
        do {
            val step = player.currentStep
            // Test the whole continuous segment against radius-expanded solid boxes, not
            // just its end points. Rotation steps are stationary and satisfy the same check.
            for (wall in definition.walls) assertFalse("${step.id} crosses ${wall.id}",
                segmentIntersectsBox(step.from.character, step.to.character, wall, 0.035))
            player.tick(10.0)
            if (step.id == "l3_blocked") assertTrue(player.scene.blocked)
            if (step.id == "l3_rotate_parallel") {
                assertEquals(0.0, player.scene.angleRadians, 1e-12)
                assertTrue(abs(player.scene.planeOrigin.z) > 0.10)
                assertFalse(player.scene.goalInPlane)
            }
        } while (player.next())
        assertTrue(player.scene.goalReached)
        assertEquals(definition.goal, player.scene.character)
    }

    @Test fun invalidOrNonPositiveFrameDeltasDoNotAdvanceAnimations() {
        val player = LessonPlayer(0)
        player.next()
        val before = player.scene
        for (dt in listOf(-1.0, 0.0, Double.NaN, Double.POSITIVE_INFINITY)) assertFalse(player.tick(dt))
        assertEquals(before, player.scene)
    }

    private fun inside(box: LessonBox, p: Vec3): Boolean {
        val q = p - box.center
        return abs(q.x) <= box.halfExtents.x + 1e-10 && abs(q.y) <= box.halfExtents.y + 1e-10 && abs(q.z) <= box.halfExtents.z + 1e-10
    }

    private fun segmentIntersectsBox(start: Vec3, end: Vec3, box: LessonBox, radius: Double): Boolean {
        val p = start - box.center; val d = end - start
        val origins = listOf(p.x, p.y, p.z); val deltas = listOf(d.x, d.y, d.z)
        val extents = listOf(box.halfExtents.x + radius, box.halfExtents.y + radius, box.halfExtents.z + radius)
        var lo = 0.0; var hi = 1.0
        for (axis in 0..2) {
            if (abs(deltas[axis]) < 1e-12) {
                if (abs(origins[axis]) > extents[axis]) return false
            } else {
                val a = (-extents[axis] - origins[axis]) / deltas[axis]
                val b = (extents[axis] - origins[axis]) / deltas[axis]
                lo = maxOf(lo, minOf(a, b)); hi = minOf(hi, maxOf(a, b))
                if (lo > hi) return false
            }
        }
        return true
    }
}
