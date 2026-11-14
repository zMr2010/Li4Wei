package com.fourthward.game.vr

import com.fourthward.game.core.GeometryTopic
import com.fourthward.game.core.Vec3
import org.junit.Assert.*
import org.junit.Test

class VrComparisonLabelsTest {
    private val anchor = VrPose(Vec3(10.0, 2.0, -5.0), Vec3(0.0, 0.0, -1.0),
        Vec3(0.0, 1.0, 0.0), Vec3(1.0, 0.0, 0.0))

    private fun lesson(topic: GeometryTopic? = GeometryTopic.HYPERSPHERE, pose: VrPose? = anchor) =
        VrLessonStatus(0, 5, "示意", "讲解", false, "", topic, pose)

    @Test fun headingsOnlyExistForAnchoredGeometryLessons() {
        assertTrue(lesson(topic = null).comparisonLabels().isEmpty())
        assertTrue(lesson(pose = null).comparisonLabels().isEmpty())
        for (topic in GeometryTopic.entries) {
            val labels = lesson(topic).comparisonLabels()
            assertEquals(listOf("二维观察三维", "三维观察四维"), labels.map { it.title })
            assertEquals("完整三维物体 + 二维截面", labels[0].subtitle)
            assertEquals("只显示真实三维截面", labels[1].subtitle)
        }
    }

    @Test fun comparisonHeadingsUseModelsRotatedWorldBasis() {
        val labels = lesson().comparisonLabels()
        assertEquals(10.0, labels[0].pose.position.x, 1e-12)
        assertEquals(2.0 + GeometryLessonLayout.TITLE_HEIGHT, labels[0].pose.position.y, 1e-12)
        assertEquals(-5.0 - GeometryLessonLayout.LEFT_CENTER.x, labels[0].pose.position.z, 1e-12)
        assertEquals(-5.0 - GeometryLessonLayout.RIGHT_CENTER.x, labels[1].pose.position.z, 1e-12)
        labels.forEach {
            assertEquals(anchor.right, it.pose.right)
            assertEquals(anchor.up, it.pose.up)
            assertEquals(anchor.normal, it.pose.normal)
        }
    }

    @Test fun advancingAnimationKeepsBothHeadingsFixedToTheSameAnchor() {
        val first = lesson()
        val later = first.copy(stepIndex = 3, title = "新的截面", narration = "另一段讲解", animating = true)
        assertEquals(first.comparisonLabels(), later.comparisonLabels())
        val displacement = Vec3(3.0, -.4, 1.2)
        val moved = first.copy(anchorPose = anchor.copy(position = anchor.position + displacement))
        first.comparisonLabels().zip(moved.comparisonLabels()).forEach { (before, after) ->
            val actual = after.pose.position - before.pose.position
            assertEquals(displacement.x, actual.x, 1e-12)
            assertEquals(displacement.y, actual.y, 1e-12)
            assertEquals(displacement.z, actual.z, 1e-12)
        }
    }
}
