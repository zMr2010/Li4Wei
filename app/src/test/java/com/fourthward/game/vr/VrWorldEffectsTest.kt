package com.fourthward.game.vr

import com.fourthward.game.core.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI

class VrWorldEffectsTest {
    private fun game(goal: Sphere4 = Sphere4("goal", Vec4(0.0, 0.0, 0.0, -2.4), .4)) =
        FourthwardGame(listOf(TutorialLevel("test", "测试", "测试", "测试", goal, 0xff67e8db.toInt(),
            World4(emptyList(), listOf(goal)))))

    private fun settled(fx: VrWorldEffects, game: FourthwardGame, seconds: Double = .8): FloatArray {
        var result = floatArrayOf()
        repeat((seconds * 100).toInt()) { result = fx.frame(game, .01, active = true, visible = true) }
        return result
    }

    @Test fun offSliceGoalCannotLeaveAnyDecorativeTrace() {
        val game = game(Sphere4("goal", Vec4(0.0, 2.0, 0.0, -2.0), .35))
        val fx = VrWorldEffects()
        assertTrue(settled(fx, game).isEmpty())
        game.rotate(PI / 2)
        assertTrue(settled(fx, game).isNotEmpty())
        game.rotate(-PI / 2)
        assertTrue(fx.frame(game, .01, true, true).isEmpty())
    }

    @Test fun noDecorationsAreAddedToGeometryOrMotionObservationObjects() {
        for (level in TutorialLevels.all().filter { it.geometryTopic != null || it.motionTopic != null }) {
            val game = FourthwardGame(listOf(level))
            val fx = VrWorldEffects()
            repeat(60) {
                game.tickWorld(.1)
                assertTrue("${level.id} received a decorative object", fx.frame(game, .1, true, true).isEmpty())
            }
        }
    }

    @Test fun worldEffectsDoNotAdvancePhysicsOrCompleteTheGame() {
        val game = FourthwardGame(listOf(MotionPuzzles.levels().first()))
        val beforeWorld = game.world
        val beforeFrame = game.frame
        val beforeHead = game.trackedHead
        settled(VrWorldEffects(), game, 5.0)
        assertSame(beforeWorld, game.world)
        assertSame(beforeFrame, game.frame)
        assertEquals(beforeHead, game.trackedHead)
        assertEquals(0.0, game.motionTimeSeconds, 0.0)
        assertFalse(game.completed)
        assertTrue(game.completedCheckpointIds.isEmpty())
    }

    @Test fun activeSignalMovesAndFocusLossFreezesItExactly() {
        val game = game()
        val fx = VrWorldEffects()
        val first = settled(fx, game)
        val next = fx.frame(game, .1, true, true)
        assertFalse(first.contentEquals(next))
        repeat(20) { assertArrayEquals(next, fx.frame(game, .1, false, true), 0f) }
    }

    @Test fun hiddenPageClearsTheGpuBufferWithoutAdvancingItsPhase() {
        val game = game()
        val fx = VrWorldEffects()
        val before = settled(fx, game)
        repeat(20) { assertTrue(fx.frame(game, .1, true, false).isEmpty()) }
        assertArrayEquals(before, fx.frame(game, 0.0, true, true), 0f)
    }

    @Test fun completionConfirmationIsShortLivedAndDoesNotRestartWhileCompleted() {
        val game = game()
        val fx = VrWorldEffects()
        settled(fx, game)
        game.movePlanar(Vec3(0.0, 0.0, -2.3))
        assertTrue(game.completed)
        fx.frame(game, 0.0, true, true)
        val confirmation = fx.frame(game, .1, true, true)
        val settled = settled(fx, game, 1.0)
        assertTrue(confirmation.size > settled.size)
        repeat(25) { assertEquals(settled.size, fx.frame(game, .1, true, true).size) }
    }

    @Test fun enteringAlreadyCompletedStateDoesNotReplaySuccess() {
        val game = game()
        game.movePlanar(Vec3(0.0, 0.0, -2.3))
        assertTrue(game.completed)
        val after = settled(VrWorldEffects(), game, .7)
        val completedFx = VrWorldEffects()
        settled(completedFx, game, 1.8)
        assertEquals(completedFx.frame(game, .1, true, true).size, after.size)
    }

    @Test fun newSceneRevisionDropsOldConfirmationAndRestartsTheLineReveal() {
        val game = game()
        val fx = VrWorldEffects()
        settled(fx, game)
        game.movePlanar(Vec3(0.0, 0.0, -2.3))
        fx.frame(game, .1, true, true)
        assertTrue(fx.frame(game, .1, true, true).isNotEmpty())
        assertTrue(fx.frame(game, 0.0, true, true, sceneRevision = 1L).isEmpty())
        fx.frame(game, .1, true, true, sceneRevision = 1L)
        fx.reset()
        assertTrue(fx.frame(game, 0.0, true, true, sceneRevision = 1L).isEmpty())
    }

    @Test fun checkpointVolumeIdIsUsedForTheConfirmationAndDisappearanceGate() {
        val beacon = Sphere4("beacon-volume", Vec4(1.2, 0.0, 0.0, -1.5), .3)
        val goal = Sphere4("goal", Vec4(0.0, 4.0, 0.0, -5.0), .4)
        val mission = ExpeditionDefinition(listOf(MissionCheckpoint("checkpoint-id", "路标", beacon, 0xffffb85c.toInt())),
            true, List(2) { MissionStage("寻找", listOf("观察", "旋转", "接近")) })
        val level = TutorialLevel("checkpoint-test", "test", "test", "test", goal, 0xff67e8db.toInt(),
            World4(emptyList(), listOf(beacon, goal)), expedition = mission)
        val game = FourthwardGame(listOf(level))
        val fx = VrWorldEffects()
        settled(fx, game)
        game.movePlanar(Vec3(1.2, 0.0, -1.5))
        assertEquals(setOf("checkpoint-id"), game.completedCheckpointIds)
        fx.frame(game, 0.0, true, true)
        val burst = fx.frame(game, .1, true, true)
        assertTrue(burst.size > settled(fx, game, 1.0).size)
        // A 90-degree slice through the reached point still contains the beacon; moving
        // one metre in Y then rotating back leaves its true volume entirely.
        game.rotate(PI / 2)
        game.movePlanar(Vec3(1.0, 0.0, 0.0))
        game.rotate(-PI / 2)
        assertTrue(fx.frame(game, .01, true, true).isEmpty())
    }

    @Test fun guideIsOnlyDecoratedWhileItsActualMovingSectionExists() {
        val game = FourthwardGame(listOf(MotionPuzzles.levels().first()))
        val fx = VrWorldEffects()
        val first = settled(fx, game)
        assertTrue(first.isNotEmpty())
        // At the origin slice, the guide leaves Y=0 before any checkpoint is visible.
        repeat(22) { game.tickWorld(.1) }
        assertFalse(game.visibleSpheres().any { it.source.id == "guide-light" })
        assertTrue(fx.frame(game, .1, true, true).isEmpty())
        game.rotate(PI / 2)
        assertTrue(settled(fx, game).isNotEmpty())
    }

    @Test fun verticesStayFiniteBoundedAndColorsUseStraightAlpha() {
        for (level in TutorialLevels.all()) {
            val game = FourthwardGame(listOf(level))
            val fx = VrWorldEffects()
            for (step in 0..30) {
                game.rotate(.07)
                val result = fx.frame(game, .1, true, true)
                assertEquals(0, result.size % 21)
                assertTrue(result.size <= VrWorldEffects.MAX_VERTICES * 7)
                assertTrue(result.all { it.isFinite() })
                for (v in result.indices step 7) {
                    assertTrue((3..6).all { result[v + it] in 0f..1f })
                }
            }
        }
        val result = settled(VrWorldEffects(), game())
        // Colors retain authored energy independently of alpha (native uses straight blending).
        assertTrue(result.indices.step(7).any { result[it + 4] > result[it + 6] })
    }

    @Test fun invalidDeltaCannotCorruptTheAnimationClock() {
        val game = game()
        val fx = VrWorldEffects()
        val first = settled(fx, game)
        for (dt in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0, 0.0)) {
            assertArrayEquals(first, fx.frame(game, dt, true, true), 0f)
        }
        val ordinary = VrWorldEffects()
        settled(ordinary, game)
        assertArrayEquals(ordinary.frame(game, .1, true, true), fx.frame(game, 1000.0, true, true), 0f)
    }
}
