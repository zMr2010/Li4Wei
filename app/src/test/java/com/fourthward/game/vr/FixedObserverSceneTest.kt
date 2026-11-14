package com.fourthward.game.vr

import com.fourthward.game.core.FourthwardGame
import com.fourthward.game.core.TutorialLevels
import com.fourthward.game.core.Vec3
import org.junit.Assert.assertEquals
import org.junit.Test

class FixedObserverSceneTest {
    @Test fun physicalTranslationLeavesEveryRenderedVertexAtTheSameEyeRelativePosition() {
        for (level in TutorialLevels.all().filter { it.geometryTopic != null }) {
            val game = FourthwardGame(listOf(level))
            val initialHead = Vec3(2.0, 1.65, -3.0)
            game.updateTrackedHead(initialHead)
            val before = VrSceneBuilder.build(game, showReference = true)
            val movedHead = initialHead + Vec3(.45, -.5, -.75)
            game.updateTrackedHead(movedHead)
            val after = VrSceneBuilder.build(game, showReference = true)
            assertEquals("${level.id}: floor, reference and section topology remain stable", before.size, after.size)
            for (vertex in before.indices step 7) {
                for ((axis, offset) in listOf(0 to .45, 1 to -.5, 2 to -.75)) {
                    assertEquals("${level.id}: eye-relative vertex ${vertex / 7}, axis $axis",
                        before[vertex + axis].toDouble() + offset, after[vertex + axis].toDouble(), 2e-5)
                }
                for (channel in 3..6) assertEquals(before[vertex + channel], after[vertex + channel], 1e-6f)
            }
        }
    }
}
