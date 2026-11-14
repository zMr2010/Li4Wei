package com.fourthward.game.vr

import com.fourthward.game.core.GeometryExhibits
import com.fourthward.game.core.GeometryTopic
import com.fourthward.game.core.LessonBox
import com.fourthward.game.core.SliceFrame
import com.fourthward.game.core.SliceGeometry
import com.fourthward.game.core.Vec3
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class GeometryLessonAnalogyTest {
    @Test fun observerRemainsOnTheFixedVerticalPivotAndInsideEveryRotatingPlane() {
        for (topic in GeometryTopic.values()) {
            val observer = GeometryLessonAnalogy.observer(topic)
            assertEquals(0.0, observer.x, 0.0)
            assertEquals(0.0, observer.z, 0.0)
            for (angle in listOf(0.0, .3, PI / 4, PI / 2, 3 * PI / 4, PI))
                assertEquals(0.0, observer.dot(GeometryLessonAnalogy.normal(angle)), 1e-10)
            // Box observers fit below their solids. The sphere observer is outside its
            // horizontal footprint, so it does not need to stand directly below it.
            if (topic == GeometryTopic.HYPERSPHERE) {
                val sphere = GeometryLessonAnalogy.spheres(topic).single()
                assertTrue(Vec3(sphere.center.x, 0.0, sphere.center.z).length() > sphere.radius)
            } else {
                val lowerSurface = if (topic == GeometryTopic.TESSERACT) .6 else .35
                assertTrue(observer.y + .124 < lowerSurface + 1e-10)
            }
        }
    }

    @Test fun rotatingPlanarCircleMatchesTheSynchronizedThreeDimensionalSphereSection() {
        val topic = GeometryTopic.HYPERSPHERE
        val sphere = GeometryLessonAnalogy.spheres(topic).single()
        val highDimensional = GeometryExhibits.world(topic).spheres.single()
        for (angle in listOf(0.0, .17, PI / 4, PI / 2, 3 * PI / 4, 2.5, PI, 5.1)) {
            val circle = GeometryLessonAnalogy.circle(sphere, angle)
            val section = SliceGeometry.sliceSphere(highDimensional, SliceFrame(angle))
            assertEquals(section == null, circle == null)
            if (circle == null || section == null) continue
            val right = GeometryLessonAnalogy.right(angle)
            val normal = GeometryLessonAnalogy.normal(angle)
            assertEquals(section.radius, circle.radius, 1e-10)
            assertEquals(section.center.x, circle.center.dot(right), 1e-10)
            assertEquals(section.center.y, circle.center.y, 1e-10)
            // Verify the circumference against both original constraints independently:
            // it lies in the moving plane and on the original unchanging 3D sphere.
            for (sample in 0 until 32) {
                val phase = sample * 2 * PI / 32
                val point = circle.center + right * (cos(phase) * circle.radius) +
                    Vec3(0.0, sin(phase) * circle.radius, 0.0)
                assertEquals(0.0, point.dot(normal), 1e-10)
                assertEquals(sphere.radius, (point - sphere.center).length(), 1e-10)
            }
        }
    }

    @Test fun missedAndTangentSpheresHaveNoInventedCircularFace() {
        val sphere = GeometryLessonAnalogy.spheres(GeometryTopic.HYPERSPHERE).single()
        assertNull(GeometryLessonAnalogy.circle(sphere, 3 * PI / 4))
        assertNull(GeometryLessonAnalogy.circle(AnalogySphere("tangent", Vec3(0.0, 1.0, 1.0), 1.0), 0.0))
    }

    @Test fun planarRectanglesMatchFourDimensionalSectionWidthsAtEveryDemonstratedAngle() {
        for (topic in listOf(GeometryTopic.TESSERACT, GeometryTopic.AMBIGUOUS_SECTION)) {
            val originals = GeometryExhibits.world(topic).boxes.associateBy { it.id }
            for (box in GeometryLessonAnalogy.boxes(topic)) for (angle in listOf(0.0, .2, PI / 4, 1.1, PI / 2)) {
                val rectangle = GeometryLessonAnalogy.rectangle(box, angle)
                val section = SliceGeometry.sliceBox(originals.getValue(box.id), SliceFrame(angle))!!
                val right = GeometryLessonAnalogy.right(angle)
                val normal = GeometryLessonAnalogy.normal(angle)
                assertEquals(4, rectangle.size)
                val width = rectangle.maxOf { it.dot(right) } - rectangle.minOf { it.dot(right) }
                assertEquals(section.vertices.maxOf { it.x } - section.vertices.minOf { it.x }, width, 1e-9)
                assertEquals(section.vertices.maxOf { it.y } - section.vertices.minOf { it.y },
                    rectangle.maxOf { it.y } - rectangle.minOf { it.y }, 1e-9)
                for (point in rectangle) {
                    assertEquals(0.0, point.dot(normal), 1e-9)
                    val relative = point - box.center
                    assertTrue(abs(relative.x) <= box.halfExtents.x + 1e-9)
                    assertTrue(abs(relative.y) <= box.halfExtents.y + 1e-9)
                    assertTrue(abs(relative.z) <= box.halfExtents.z + 1e-9)
                }
            }
        }
    }

    @Test fun boxOutsideThePlaneHasNoInventedRectangle() {
        val box = LessonBox("hidden", Vec3(0.0, 1.0, 2.0), Vec3(.4, .4, .4))
        assertTrue(GeometryLessonAnalogy.rectangle(box, 0.0).isEmpty())
        assertEquals(4, GeometryLessonAnalogy.rectangle(box, PI / 2).size)
    }
}
