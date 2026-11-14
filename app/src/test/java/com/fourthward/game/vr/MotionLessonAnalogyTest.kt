package com.fourthward.game.vr

import com.fourthward.game.core.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class MotionLessonAnalogyTest {
    @Test fun leftCircleAndRightSphereHaveMatchingRadiiAndHorizontalMotion() {
        for (topic in listOf(MotionTopic.NORMAL_SPHERE, MotionTopic.OBLIQUE_SPHERE)) {
            for (time in listOf(0.0, 2.0, 3.0, 4.0, 6.0)) {
                val left = GeometryLessonAnalogy.circle(MotionLessonAnalogy.sphere(topic, time)!!, 0.0)
                val right = SliceGeometry.sliceSphere(MotionStudies.world(topic, time).spheres.single(), SliceFrame())
                assertEquals(left == null, right == null)
                if (left != null && right != null) {
                    assertEquals(right.radius, left.radius, 1e-12)
                    assertEquals(right.center.x, left.center.x, 1e-12)
                    assertEquals(right.center.y, left.center.y, 1e-12)
                    assertEquals(0.0, left.center.z, 1e-12)
                }
            }
        }
    }

    @Test fun cubePlanePolygonIsTheCrossSectionOfTheMatchingPrism() {
        for ((time, count) in listOf(0.0 to 0, 1.5 to 3, 3.0 to 6, 4.5 to 3, 6.0 to 0)) {
            val box = MotionStudies.world(MotionTopic.MOVING_BOX, time).boxes.first()
            val polygon = MotionLessonAnalogy.polygon(box)
            assertEquals(count, polygon.size)
            val section = SliceGeometry.sliceBox(box, SliceFrame())
            if (count == 0) assertNull(section) else {
                assertEquals(polygon.size * 2, section!!.vertices.size)
                for (point in polygon) {
                    assertEquals(0.0, point.z, 1e-9)
                    assertEquals(2, section.vertices.count { abs(it.x - point.x) < 1e-8 && abs(it.y - point.y) < 1e-8 })
                }
            }
        }
    }

    @Test fun absentRightSectionHasNoGhostWhileExternalModelRemainsVisible() {
        val anchor = VrPose(Vec3.ZERO, Vec3(1.0, 0.0, 0.0), Vec3(0.0, 1.0, 0.0), Vec3(0.0, 0.0, 1.0))
        for (index in 9..11) {
            val player = LessonPlayer(index)
            for (time in listOf(0.0, 6.0)) {
                val snapshot = player.scene.copy(motionTimeSeconds = time)
                assertTrue(LessonSceneBuilder.motionAnalogyVertices(snapshot, anchor).isNotEmpty())
                assertTrue(LessonSceneBuilder.motionExhibitVertices(snapshot, anchor).isEmpty())
            }
            assertTrue(LessonSceneBuilder.motionExhibitVertices(player.scene.copy(motionTimeSeconds = 3.0), anchor).isNotEmpty())
        }
    }

    @Test fun eachMotionLessonFreezesTheSameSourceTimeUntilTheNextStep() {
        for (index in 9..11) {
            val player = LessonPlayer(index)
            assertNotNull(player.scene.motionTopic)
            assertEquals(0.0, player.scene.angleRadians, 0.0)
            assertTrue(player.next())
            player.tick(1.0)
            assertTrue(player.scene.motionTimeSeconds in 0.01..2.99)
            player.tick(100.0)
            assertEquals(3.0, player.scene.motionTimeSeconds, 0.0)
            val held = player.scene
            assertFalse(player.tick(100.0))
            assertEquals(held, player.scene)
            assertTrue(player.next())
            player.tick(100.0)
            assertEquals(6.0, player.scene.motionTimeSeconds, 0.0)
            assertTrue(player.next())
            assertTrue(player.isLastStep)
            assertFalse(player.next())
        }
    }
}
