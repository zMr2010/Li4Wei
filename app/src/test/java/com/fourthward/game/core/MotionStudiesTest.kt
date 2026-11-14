package com.fourthward.game.core

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class MotionStudiesTest {
    @Test fun perpendicularPassageChangesRadiusWithoutMovingTheSectionCenter() {
        val frame = SliceFrame()
        val sections = listOf(2.0, 3.0, 4.0).map { time ->
            val sphere = MotionStudies.world(MotionTopic.NORMAL_SPHERE, time).spheres.single()
            assertEquals(MotionStudies.sphereRadius, sphere.radius, 0.0)
            SliceGeometry.sliceSphere(sphere, frame)!!
        }
        assertEquals(sections[0].center, sections[1].center)
        assertEquals(sections[1].center, sections[2].center)
        assertTrue(sections[0].radius < sections[1].radius)
        assertEquals(sections[0].radius, sections[2].radius, 1e-12)
        assertEquals(MotionStudies.sphereRadius, sections[1].radius, 1e-12)
        for (time in listOf(0.0, 6.0, 12.0)) assertNull(SliceGeometry.sliceSphere(
            MotionStudies.world(MotionTopic.NORMAL_SPHERE, time).spheres.single(), frame))
    }

    @Test fun obliquePassageMovesTheCenterWhileRadiusFollowsTrueDistance() {
        val sections = listOf(2.0, 3.0, 4.0).map { time ->
            val source = MotionStudies.world(MotionTopic.OBLIQUE_SPHERE, time).spheres.single()
            val section = SliceGeometry.sliceSphere(source, SliceFrame())!!
            assertEquals(source.center.y * .75, section.center.x, 1e-12)
            assertEquals(sqrt(source.radius * source.radius - source.center.y * source.center.y), section.radius, 1e-12)
            section
        }
        assertTrue(sections[0].center.x < sections[1].center.x)
        assertTrue(sections[1].center.x < sections[2].center.x)
        assertTrue(sections[0].radius < sections[1].radius)
        assertTrue(sections[2].radius < sections[1].radius)
        assertEquals(sections[0].center.y, sections[2].center.y, 0.0)
        assertEquals(sections[0].center.z, sections[2].center.z, 0.0)
    }

    @Test fun aRigidTiltedBoxPassesFromTriangularToHexagonalPrismAndBack() {
        val counts = mutableListOf<Int>()
        for (time in listOf(1.5, 3.0, 4.5)) {
            val source = MotionStudies.world(MotionTopic.MOVING_BOX, time).boxes.first()
            assertFalse(source.solid)
            assertEquals(Vec4(.45, .45, .45, .40), source.halfExtents)
            assertEquals(MotionStudies.boxBasis, source.basis)
            val section = SliceGeometry.sliceBox(source, SliceFrame())!!
            counts += section.vertices.size
            for (point in section.vertices) {
                val world = SliceFrame().world(point)
                assertEquals(0.0, world.y, 1e-9)
                for (axis in 0..3) assertTrue(abs((world - source.center).dot(source.basis[axis])) <= source.halfExtents[axis] + 1e-8)
            }
        }
        assertEquals(listOf(6, 12, 6), counts)
        for (time in listOf(0.0, 6.0)) for (box in MotionStudies.world(MotionTopic.MOVING_BOX, time).boxes)
            assertNull(SliceGeometry.sliceBox(box, SliceFrame()))
    }

    @Test fun cycleIsDeterministicAndSourceGeometryDoesNotResize() {
        for (topic in MotionTopic.entries) for (time in listOf(.5, 2.0, 5.0, 9.0)) {
            val a = MotionStudies.world(topic, time)
            val b = MotionStudies.world(topic, time + MotionStudies.cycleSeconds)
            val ca = a.boxes.firstOrNull()?.center ?: a.spheres.single().center
            val cb = b.boxes.firstOrNull()?.center ?: b.spheres.single().center
            assertEquals(0.0, (ca - cb).length(), 1e-12)
            assertTrue(a.boxes.all { !it.solid })
            if (a.boxes.isNotEmpty()) assertEquals(MotionStudies.boxBasis, a.boxes.first().basis)
            assertTrue(a.spheres.all { it.radius == MotionStudies.sphereRadius })
        }
    }

    @Test fun theLongBoxKeepsItsSectionDimensionsWhileSlidingAcrossThePlane() {
        val sections = listOf(2.5, 3.0, 3.5).map { time ->
            val source = MotionStudies.world(MotionTopic.MOVING_BOX, time).boxes.last()
            SliceGeometry.sliceBox(source, SliceFrame())!!
        }
        for (section in sections) {
            assertEquals(8, section.vertices.size)
            assertEquals(1.7, section.vertices.maxOf { it.x } - section.vertices.minOf { it.x }, 1e-9)
            assertEquals(.56, section.vertices.maxOf { it.y } - section.vertices.minOf { it.y }, 1e-9)
            assertEquals(.8, section.vertices.maxOf { it.z } - section.vertices.minOf { it.z }, 1e-9)
        }
        assertTrue(sections[0].vertices.minOf { it.x } < sections[1].vertices.minOf { it.x })
        assertTrue(sections[1].vertices.minOf { it.x } < sections[2].vertices.minOf { it.x })
    }
}
