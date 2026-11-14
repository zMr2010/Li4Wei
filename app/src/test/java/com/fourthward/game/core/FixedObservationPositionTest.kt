package com.fourthward.game.core

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI

class FixedObservationPositionTest {
    private fun levels() = TutorialLevels.all().filter { it.geometryTopic != null }
    private fun assertVector(expected: Vec4, actual: Vec4) {
        for (axis in 0..3) assertEquals(expected[axis], actual[axis], 1e-8)
    }
    private fun assertPoint(expected: Vec3, actual: Vec3) {
        assertEquals(expected.x, actual.x, 1e-8)
        assertEquals(expected.y, actual.y, 1e-8)
        assertEquals(expected.z, actual.z, 1e-8)
    }
    private fun hold(game: FourthwardGame) { repeat(8) { game.tickObservation(.1) } }

    @Test fun firstRawSampleCapturesEyeHeightAndIdenticalSamplesDoNotRebuildTheFrame() {
        for (level in levels()) {
            val game = FourthwardGame(listOf(level))
            val raw = Vec3(18.2, 1.72, -31.4)
            assertTrue(game.locomotionLocked)
            assertTrue(game.updateTrackedHead(raw).changed)
            assertEquals(raw, game.trackedHead)
            assertVector(level.start, game.playerFootWorld())
            assertVector(level.start + Vec4.Z * raw.y, game.playerWorld())
            val frame = game.frame
            assertFalse(game.updateTrackedHead(raw).changed)
            assertSame(frame, game.frame)
            assertFalse(game.physicalContact)
            assertFalse(game.lastMotionBlocked)
            // Consuming raw samples without compensating small deltas must not accumulate
            // a virtual drift over time. Exactly unchanged samples above still reuse frames.
            repeat(20) { sample ->
                game.updateTrackedHead(raw + Vec3((sample + 1) * 1e-9, 0.0, 0.0))
                assertVector(level.start + Vec4.Z * raw.y, game.playerWorld())
            }
        }
    }

    @Test fun allPhysicalTranslationIsCancelledWhileRawEyeOffsetsAndRotationBasisRemainLive() {
        for (level in levels()) for (angle in listOf(0.0, PI / 4, PI / 2)) {
            val game = FourthwardGame(listOf(level))
            val entry = Vec3(.4, 1.68, -.7)
            game.restartLevel(entry)
            game.rotate(angle)
            val fixedHead = game.playerWorld()
            for (raw in listOf(Vec3(4.0, .8, -7.0), Vec3(-12.0, 3.2, 19.0), entry)) {
                val result = game.updateTrackedHead(raw)
                assertTrue(result.changed)
                assertFalse(result.blocked)
                assertEquals(raw, game.trackedHead)
                assertVector(level.start, game.playerFootWorld())
                assertVector(fixedHead, game.playerWorld())
                assertEquals(angle, game.frame.angleRadians, 1e-12)
                assertFalse(game.physicalContact)
                assertFalse(game.lastMotionBlocked)
                // The scene correction does not collapse stereo eye separation or alter
                // the mapping of an offset supplied by the actual XR view pose.
                assertVector(fixedHead + game.frame.right * .032,
                    game.playerWorld(raw + Vec3(.032, 0.0, 0.0)))
                assertEquals(raw.y - entry.y, game.frame.local(level.start).y, 1e-8)
            }
        }
    }

    @Test fun keyboardAndControllerLocomotionCannotMoveOrReportAWallContact() {
        for (level in levels()) {
            val game = FourthwardGame(listOf(level))
            game.restartLevel(Vec3(2.0, 1.6, -3.0))
            game.rotate(.7)
            val head = game.playerWorld()
            val frame = game.frame
            for (delta in listOf(Vec3(1.0, 0.0, 0.0), Vec3(0.0, 0.0, -5.0),
                Vec3(-4.0, 100.0, 2.0), Vec3.ZERO)) {
                val result = game.movePlanar(delta)
                assertFalse(result.changed)
                assertFalse(result.blocked)
                assertFalse(result.completed)
                assertSame(frame, game.frame)
                assertVector(head, game.playerWorld())
                assertVector(level.start, game.playerFootWorld())
                assertFalse(game.lastMotionBlocked)
                assertFalse(game.physicalContact)
            }
        }
    }

    @Test fun physicalWalkingLeavesEverySectionUnchangedRelativeToTheViewer() {
        for (level in levels()) for (angle in listOf(0.0, PI / 4, 3 * PI / 4)) {
            val game = FourthwardGame(listOf(level))
            val entry = Vec3(.7, 1.63, -.2)
            game.restartLevel(entry)
            game.rotate(angle)
            val boxes = game.sections().associate { section ->
                section.source.id to section.vertices.map { it - entry }
            }
            val spheres = game.visibleSpheres().associate { it.source.id to (it.center - entry to it.radius) }
            val raw = Vec3(-9.4, .5, 14.3)
            game.updateTrackedHead(raw)
            assertEquals(boxes.keys, game.sections().map { it.source.id }.toSet())
            for (section in game.sections()) {
                val expected = boxes.getValue(section.source.id)
                val actual = section.vertices.map { it - raw }
                assertEquals(expected.size, actual.size)
                for (point in actual) assertTrue(expected.any { (it - point).length() < 1e-8 })
            }
            assertEquals(spheres.keys, game.visibleSpheres().map { it.source.id }.toSet())
            for (section in game.visibleSpheres()) {
                val expected = spheres.getValue(section.source.id)
                assertPoint(expected.first, section.center - raw)
                assertEquals(expected.second, section.radius, 1e-8)
            }
        }
    }

    @Test fun rotationAndOrderedObservationStillWorkAfterLargeRawHeadChanges() {
        for (level in levels()) {
            val game = FourthwardGame(listOf(level))
            game.restartLevel(Vec3(10.0, 1.7, -20.0))
            val head = game.playerWorld()
            hold(game)
            assertEquals(1, game.observationStep)
            game.updateTrackedHead(Vec3(50.0, .6, -60.0))
            assertTrue(game.rotate(PI / 4))
            hold(game)
            assertEquals(2, game.observationStep)
            game.updateTrackedHead(Vec3(-20.0, 2.3, 35.0))
            assertTrue(game.rotate(if (level.geometryTopic == GeometryTopic.HYPERSPHERE) PI / 2 else PI / 4))
            hold(game)
            assertTrue(game.completed)
            assertVector(head, game.playerWorld())
            assertVector(level.start, game.playerFootWorld())
            assertFalse(game.movePlanar(Vec3(0.0, 0.0, -1.0)).changed)
            game.updateTrackedHead(Vec3(0.0, 3.1, 0.0))
            assertVector(head, game.playerWorld())
        }
    }

    @Test fun restartAdoptsCurrentEntryHeightWithoutCarryingAnyPhysicalMotionDebt() {
        val game = FourthwardGame(listOf(levels().first()))
        game.restartLevel(Vec3(.4, 1.7, -.3))
        game.updateTrackedHead(Vec3(12.0, 1.1, -8.0))
        assertEquals(1.7, game.playerWorld().z, 1e-8)
        game.restartLevel()
        assertEquals(1.1, game.playerWorld().z, 1e-8)
        game.updateTrackedHead(Vec3(12.01, 1.2, -8.01))
        assertEquals(1.1, game.playerWorld().z, 1e-8)
        assertVector(game.level.start, game.playerFootWorld())
        game.restartLevel(Vec3(-20.0, 1.9, 30.0))
        assertEquals(1.9, game.playerWorld().z, 1e-8)
        assertVector(game.level.start, game.playerFootWorld())
        assertFalse(game.completed)
        assertEquals(0, game.observationStep)
    }

    @Test fun returningToNavigationRestoresPhysicalAndVirtualLocomotionImmediately() {
        val levels = TutorialLevels.all()
        val game = FourthwardGame(levels, LevelProgress(levels.map { it.id }, levels.map { it.id }.toSet()))
        assertTrue(game.selectLevel(3))
        game.restartLevel(Vec3(.5, 1.7, -.3))
        val raw = Vec3(20.0, 1.2, -30.0)
        game.updateTrackedHead(raw)
        assertTrue(game.selectLevel(0))
        assertFalse(game.locomotionLocked)
        assertVector(Vec4.ZERO, game.playerFootWorld())
        assertEquals(1.2, game.playerWorld().z, 1e-8)
        assertTrue(game.updateTrackedHead(raw + Vec3(.2, .1, -.3)).changed)
        assertVector(Vec4(.2, 0.0, 0.0, -.3), game.playerFootWorld())
        assertEquals(1.3, game.playerWorld().z, 1e-8)
        assertTrue(game.movePlanar(Vec3(.1, 0.0, 0.0)).changed)
        assertVector(Vec4(.3, 0.0, 0.0, -.3), game.playerFootWorld())
        assertTrue(game.selectLevel(4))
        assertTrue(game.locomotionLocked)
        assertVector(Vec4.ZERO, game.playerFootWorld())
        assertEquals(1.3, game.playerWorld().z, 1e-8)
    }

    @Test fun fixedObservationUsesTheConfiguredSpawnInsteadOfHardCodingWorldZero() {
        val level = levels().first().copy(start = Vec4(.2, -.3, .4, -.5))
        val game = FourthwardGame(listOf(level))
        game.restartLevel(Vec3(4.0, 1.8, -3.0))
        game.updateTrackedHead(Vec3(20.0, 1.0, -30.0))
        game.rotate(.9)
        assertVector(level.start, game.playerFootWorld())
        assertVector(level.start + Vec4.Z * 1.8, game.playerWorld())
        assertEquals(0.0, game.frame.signedDistance(level.start), 1e-8)
    }
}
