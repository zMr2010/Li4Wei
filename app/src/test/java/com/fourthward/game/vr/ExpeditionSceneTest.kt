package com.fourthward.game.vr

import com.fourthward.game.core.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI

class ExpeditionSceneTest {
    @Test fun offSliceBeaconDoesNotLeaveAProjectedMarkerBehind() {
        val beacon = Sphere4("beacon", Vec4(0.0, 2.0, 0.0, -2.0), .35)
        val goal = Sphere4("goal", Vec4(0.0, 4.0, 0.0, -4.0), .35)
        val mission = ExpeditionDefinition(
            listOf(MissionCheckpoint("beacon", "信标", beacon, 0xffffb85c.toInt())), false,
            List(2) { MissionStage("寻找信标", listOf("观察", "旋转", "接近")) },
        )
        val level = TutorialLevel("test", "test", "test", "test", goal, 0xff77c7c6.toInt(),
            World4(emptyList(), listOf(beacon)), expedition = mission)
        val game = FourthwardGame(listOf(level))
        val empty = FourthwardGame(listOf(level.copy(world = World4(emptyList(), emptyList()))))
        assertArrayEquals(VrSceneBuilder.build(empty, false), VrSceneBuilder.build(game, false), 0f)
        game.rotate(PI / 2)
        empty.rotate(PI / 2)
        assertTrue(VrSceneBuilder.build(game, false).size > VrSceneBuilder.build(empty, false).size)
        game.rotate(-PI / 2)
        empty.rotate(-PI / 2)
        assertArrayEquals(VrSceneBuilder.build(empty, false), VrSceneBuilder.build(game, false), 0f)
    }
}
