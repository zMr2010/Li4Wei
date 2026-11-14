package com.fourthward.game.core

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sqrt

class GeometryObservationTest {
    private fun geometryLevels() = TutorialLevels.all().filter { it.geometryTopic != null }
    private fun newGame(level: TutorialLevel) = FourthwardGame(listOf(level))
    private fun angles(topic: GeometryTopic) = if (topic == GeometryTopic.HYPERSPHERE)
        listOf(0.0, 45.0, 135.0) else listOf(0.0, 45.0, 90.0)
    private fun turnTo(game: FourthwardGame, degrees: Double) {
        game.rotate(Math.toRadians(degrees) - game.frame.angleRadians)
    }
    private fun hold(game: FourthwardGame) {
        repeat(7) { assertFalse(game.tickObservation(.1)) }
        assertTrue(game.tickObservation(.1))
    }
    private fun observeAll(game: FourthwardGame) {
        angles(game.level.geometryTopic!!).forEachIndexed { index, degrees ->
            assertEquals(index, game.observationStep)
            turnTo(game, degrees)
            hold(game)
            assertEquals(index == game.observationCount - 1, game.completed)
        }
        assertTrue(game.observationsComplete)
    }
    private fun width(section: BoxSection) = section.vertices.maxOf { it.x } - section.vertices.minOf { it.x }

    @Test fun exhibitsHaveThePromisedTrueSectionsAndNeverContainTheGameExit() {
        GeometryTopic.entries.forEach { topic ->
            val world = GeometryExhibits.world(topic)
            assertTrue(world.spheres.none { it.id == "goal" })
            assertTrue(world.boxes.all { !it.solid })
        }
        val sphere = GeometryExhibits.world(GeometryTopic.HYPERSPHERE).spheres.single()
        assertEquals(sqrt(.75 * .75 - .7 * .7), SliceGeometry.sliceSphere(sphere, SliceFrame())!!.radius, 1e-9)
        assertEquals(.75, SliceGeometry.sliceSphere(sphere, SliceFrame(PI / 4))!!.radius, 1e-9)
        assertNull(SliceGeometry.sliceSphere(sphere, SliceFrame(3 * PI / 4)))
        val cube = GeometryExhibits.world(GeometryTopic.TESSERACT).boxes.single()
        for ((angle, expected) in listOf(0.0 to 1.3, PI / 4 to 1.3 * sqrt(2.0), PI / 2 to 1.3)) {
            assertEquals(expected, width(SliceGeometry.sliceBox(cube, SliceFrame(angle))!!), 1e-8)
        }
        val pair = GeometryExhibits.world(GeometryTopic.AMBIGUOUS_SECTION).boxes
        for ((angle, expected) in listOf(0.0 to listOf(.9, .9), PI / 4 to listOf(.5 * sqrt(2.0), .9 * sqrt(2.0)), PI / 2 to listOf(.5, 2.2))) {
            pair.forEachIndexed { index, box ->
                assertEquals(expected[index], width(SliceGeometry.sliceBox(box, SliceFrame(angle))!!), 1e-8)
            }
        }
    }

    @Test fun eachGeometryLevelPassesAndSavesExactlyOnceAtTheThirdHoldWithoutAnyFlagOrWalking() {
        for (level in geometryLevels()) {
            val saves = mutableListOf<Set<String>>()
            val game = FourthwardGame(listOf(level), onProgressChanged = { saves += it.completedIds })
            val startingFeet = game.playerFootWorld()
            assertEquals(3, game.observationCount)
            assertFalse(game.observationsComplete)
            assertTrue(game.observationInstruction!!.isNotBlank())
            assertTrue(game.observationHint!!.isNotBlank())
            assertTrue(game.world.spheres.none { it.id == "goal" })
            assertTrue(game.visibleSpheres().none { it.source.id == "goal" })
            observeAll(game)
            assertTrue(game.completed)
            assertEquals(startingFeet, game.playerFootWorld())
            assertTrue((game.playerFootWorld() - level.goal.center).length() > level.goal.radius)
            assertEquals(1, game.progress.completedCount)
            assertTrue(game.world.spheres.none { it.id == "goal" })
            assertTrue(game.visibleSpheres().none { it.source.id == "goal" })
            assertEquals(setOf(level.id), game.progress.completedIds)
            assertEquals(listOf(setOf(level.id)), saves)
            repeat(10) { assertFalse(game.tickObservation(.1)) }
            game.resetAngle()
            game.updateTrackedHead(Vec3(0.0, 1.65, 0.0))
            assertEquals(listOf(setOf(level.id)), saves)
            assertTrue(game.visibleSpheres().none { it.source.id == "goal" })
            assertFalse(game.nextLevel())
            game.restartLevel()
            assertFalse(game.completed)
            assertTrue(game.visibleSpheres().none { it.source.id == "goal" })
            observeAll(game)
            assertEquals("Replaying a passed study must not save another clear", listOf(setOf(level.id)), saves)
        }
    }

    @Test fun walkingToTheFormerExitOrSweepingAnglesCannotSkipTheStudy() {
        for (level in geometryLevels()) {
            val game = newGame(level)
            assertFalse(game.movePlanar(Vec3(0.0, 0.0, -1.3)).completed)
            assertFalse(game.completed)
            assertTrue(game.visibleSpheres().none { it.source.id == "goal" })
            assertFalse(game.nextLevel())
            angles(level.geometryTopic!!).forEach { degrees ->
                turnTo(game, degrees)
                assertFalse(game.tickObservation(.05))
            }
            assertEquals(0, game.observationStep)
            assertTrue(game.progress.completedIds.isEmpty())
        }
    }

    @Test fun holdsRequireTheRightOrderAndContinuousActiveTime() {
        for (level in geometryLevels()) {
            val game = newGame(level)
            turnTo(game, 45.0)
            repeat(10) { assertFalse(game.tickObservation(.1)) }
            assertEquals(0, game.observationStep)
            turnTo(game, 0.0)
            repeat(6) { assertFalse(game.tickObservation(.1)) }
            assertFalse(game.tickObservation(10.0, active = false))
            hold(game)
            assertEquals(1, game.observationStep)
            repeat(10) { assertFalse(game.tickObservation(.1)) }
            assertEquals("Unchanged theta cannot advance another observation", 1, game.observationStep)
            turnTo(game, 45.0)
            repeat(6) { assertFalse(game.tickObservation(.1)) }
            turnTo(game, 65.0)
            assertFalse(game.tickObservation(.1))
            turnTo(game, 45.0)
            hold(game)
            assertEquals(2, game.observationStep)
        }
    }

    @Test fun anAbnormallyLongFrameCannotCompleteAHoldAndInvalidTimeDoesNotCount() {
        val game = newGame(geometryLevels().first())
        for (dt in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0, 0.0)) assertFalse(game.tickObservation(dt))
        assertFalse(game.tickObservation(30.0))
        repeat(6) { assertFalse(game.tickObservation(.1)) }
        assertEquals(0, game.observationStep)
        assertTrue(game.tickObservation(.1))
        assertEquals(1, game.observationStep)
    }

    @Test fun matchingStillChecksActualGeometryAnglesAndCoordinatesWhilePlayerMovementIsLocked() {
        for (level in geometryLevels()) {
            val topic = level.geometryTopic!!
            assertTrue(GeometryExhibits.matches(topic, 0, level.world, SliceFrame(), level.start, level.start))
            assertFalse(GeometryExhibits.matches(topic, 0, level.world, SliceFrame(Math.toRadians(20.0)), level.start, level.start))
            // Keep the pure matcher defensive even though gameplay cannot leave its origin.
            val away = level.start + Vec4.X * .3
            assertFalse(GeometryExhibits.matches(topic, 0, level.world, SliceFrame(0.0, away), away, level.start))
            val game = newGame(level)
            assertFalse(game.movePlanar(Vec3(.3, 0.0, 0.0)).changed)
            assertEquals(level.start, game.playerFootWorld())
            hold(game)
            val withoutExhibits = newGame(level.copy(world = World4(emptyList(), listOf(level.goal))))
            repeat(10) { assertFalse(withoutExhibits.tickObservation(.1)) }
            assertEquals(0, withoutExhibits.observationStep)
        }
    }

    @Test fun restartClearsStudyAndItsHoldButPreservesPreviouslyEarnedProgress() {
        for (level in geometryLevels()) {
            val game = newGame(level)
            observeAll(game)
            assertTrue(game.completed)
            game.restartLevel(Vec3(3.0, 1.6, -4.0))
            assertFalse(game.completed)
            assertEquals(0, game.observationStep)
            assertFalse(game.observationsComplete)
            assertTrue(game.visibleSpheres().none { it.source.id == "goal" })
            assertEquals(setOf(level.id), game.progress.completedIds)
            repeat(7) { assertFalse(game.tickObservation(.1)) }
            game.restartLevel()
            hold(game)
            assertEquals(1, game.observationStep)
        }
    }

    @Test fun physicalAndVirtualMotionKeepObservationHintsRelevantAndCompletedGuidanceIntact() {
        for (level in geometryLevels()) {
            val game = newGame(level)
            game.restartLevel(Vec3(1.0, 1.65, -2.0))
            val firstHint = game.observationHint
            repeat(7) { assertFalse(game.tickObservation(.1)) }
            game.movePlanar(Vec3(.3, 0.0, -2.0))
            game.updateTrackedHead(Vec3(3.0, 1.2, -4.0))
            assertEquals(firstHint, game.observationHint)
            assertTrue("Real movement cannot interrupt an otherwise valid observation", game.tickObservation(.1))
            assertEquals(1, game.observationStep)
            game.restartLevel()
            observeAll(game)
            val completionHint = game.observationHint
            game.updateTrackedHead(Vec3(.3, 1.65, 0.0))
            game.updateTrackedHead(Vec3(.6, 1.65, 0.0))
            assertEquals("Finished studies should retain completion guidance", completionHint, game.observationHint)
            assertFalse(game.observationInstruction!!.contains("旗帜"))
            assertFalse(game.observationHint!!.contains("旗帜"))
        }
        val basic = newGame(TutorialLevels.all().first())
        basic.movePlanar(Vec3(.3, 0.0, 0.0))
        assertNull(basic.observationHint)
    }

    @Test fun recognitionProgressStaysInsideItsBranch() {
        val levels = TutorialLevels.all()
        assertEquals(listOf("walk", "turn", "around", "hypersphere", "tesseract", "ambiguity"), levels.take(6).map { it.id })
        assertEquals(listOf("movement", "recognition", "motion"), levels.map { it.groupId }.distinct())
        assertTrue(levels.take(3).all { it.groupTitle == "移动" && it.geometryTopic == null })
        assertTrue(levels.subList(3, 6).all { it.groupTitle == "辨认" && it.geometryTopic != null })
        val progress = LevelProgress.forLevels(levels, levels.filter { it.groupId == "movement" }.map { it.id }.toSet())
        val game = FourthwardGame(levels, progress)
        assertTrue(game.selectLevel(3))
        assertFalse(game.selectLevel(4))
        assertFalse(game.selectLevel(5))
        for (index in 3..5) {
            assertEquals(index, game.levelIndex)
            assertFalse(game.nextLevel())
            observeAll(game)
            assertTrue(game.completed)
            assertTrue(game.visibleSpheres().none { it.source.id == "goal" })
            if (index < 5) {
                assertTrue(game.progress.isUnlocked(index + 1))
                assertTrue(game.observationHint!!.contains("按 N"))
                assertTrue(game.nextLevel())
                assertFalse(game.completed)
            } else {
                assertTrue(game.progress.isUnlocked(6))
                assertTrue(game.observationHint!!.contains("按 N"))
                assertFalse(game.nextLevel())
                assertNull(game.nextLevelIndex)
                assertTrue(game.completed)
            }
        }
        assertEquals(9, progress.completedCount)
        assertTrue(game.selectLevel(0))
        assertTrue(game.observationsComplete)
        assertEquals(0, game.observationCount)
        assertNull(game.observationInstruction)
        assertNull(game.observationHint)
        repeat(10) { assertFalse(game.tickObservation(.1)) }
        assertFalse("Basic navigation still requires reaching its real exit", game.completed)
        assertTrue(game.visibleSpheres().any { it.source.id == "goal" })
        assertTrue(game.movePlanar(Vec3(0.0, 0.0, -2.4)).completed)
    }
}
