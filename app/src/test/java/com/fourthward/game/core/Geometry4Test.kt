package com.fourthward.game.core

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class Geometry4Test {
    private val cube = Box4("cube", Vec4.ZERO, Vec4(1.0, 1.0, 1.0, 1.0))
    private fun assertVector(expected: Vec4, actual: Vec4, tolerance: Double = 1e-8) {
        for (axis in 0..3) assertEquals("axis $axis", expected[axis], actual[axis], tolerance)
    }
    private fun assertLocal(expected: Vec3, actual: Vec3) {
        assertEquals(expected.x, actual.x, 1e-8)
        assertEquals(expected.y, actual.y, 1e-8)
        assertEquals(expected.z, actual.z, 1e-8)
    }
    private fun volume(mesh: MeshData): Double = mesh.indices.toList().chunked(3).sumOf { triangle ->
        val a = mesh.positions[triangle[0]]; val b = mesh.positions[triangle[1]]; val c = mesh.positions[triangle[2]]
        a.dot(b.cross(c)) / 6.0
    }
    private fun tiltedBasis(): List<Vec4> = listOf(Vec4.X, Vec4.Y, Vec4.Z, Vec4.W).map { a ->
        Vec4(a.x * cos(0.41) - a.z * sin(0.41), a.y * cos(0.67) - a.w * sin(0.67),
            a.x * sin(0.41) + a.z * cos(0.41), a.y * sin(0.67) + a.w * cos(0.67))
    }

    @Test fun formalCoordinateMapAndInverseOnTheSliceAreExact() {
        val frame = SliceFrame(PI / 6)
        val local = Vec3(2.0, 1.4, -3.0)
        val world = frame.world(local)
        assertVector(Vec4(sqrt(3.0), 1.0, 1.4, -3.0), world)
        assertLocal(local, frame.local(world))
        assertEquals(0.0, frame.signedDistance(world), 1e-8)
        assertVector(Vec4(-0.5, sqrt(3.0) / 2, 0.0, 0.0), frame.normal)
    }

    @Test fun affineOriginAndZWDirectionsAreRetainedByTheCoordinateMap() {
        val origin = Vec4(1.2, -0.7, 0.3, -1.1)
        for (angle in listOf(0.0, 0.23, PI / 2, PI, 5.8, -7.4, 100.0)) {
            val frame = SliceFrame(angle, origin)
            assertEquals(origin, frame.origin)
            assertEquals(Vec4.Z, frame.up)
            assertEquals(Vec4.W, frame.forward)
            assertVector(origin + Vec4(0.0, 0.0, 1.7, -2.4), frame.world(Vec3(0.0, 1.7, -2.4)))
            assertEquals(0.0, frame.right.dot(frame.normal), 1e-8)
            assertEquals(1.0, frame.right.length(), 1e-8)
            assertEquals(1.0, frame.normal.length(), 1e-8)
        }
    }

    @Test fun affineRotationPreservesTheTrackedPlayerInsteadOfOrbitingTheWorldOrigin() {
        val frame = SliceFrame(0.3, Vec4(1.1, -0.4, 0.0, -2.0))
        val head = Vec3(0.7, 1.67, -0.35)
        val rotated = frame.rotated(PI / 2, head)
        assertVector(frame.world(head), rotated.world(head))
        assertVector(frame.world(Vec3(head.x, 0.0, head.z)), rotated.world(Vec3(head.x, 0.0, head.z)))
        assertTrue((rotated.origin - frame.origin).length() > 0.5)
        assertEquals(Vec4.Z, rotated.up)
        assertEquals(Vec4.W, rotated.forward)
        assertLocal(head, rotated.local(rotated.world(head)))
    }

    @Test fun anglesNormalizeToHalfOpenTwoPiInterval() {
        assertEquals(3 * PI / 2, SliceFrame(-PI / 2).angleRadians, 1e-12)
        assertEquals(0.0, SliceFrame(2 * PI).angleRadians, 0.0)
        assertEquals(0.0, SliceFrame(-1e-16).angleRadians, 0.0)
        assertEquals(0.37, SliceFrame(0.37 + 12 * PI).angleRadians, 1e-12)
        for (angle in listOf(-1000.0, -PI, 0.0, 2 * PI, 1000.0)) {
            assertTrue(SliceFrame(angle).angleRadians >= 0.0)
            assertTrue(SliceFrame(angle).angleRadians < 2 * PI)
        }
        assertThrows(IllegalArgumentException::class.java) { SliceFrame(Double.NaN) }
    }

    @Test fun thetaPlusPiIsSameWorldPlaneWithMirroredHorizontalCoordinate() {
        val first = SliceFrame(0.41); val opposite = SliceFrame(0.41 + PI)
        val q = Vec3(0.8, 1.5, -3.2)
        assertVector(first.world(q), opposite.world(Vec3(-q.x, q.y, q.z)))
        val arbitrary = Vec4(1.2, -0.4, 2.0, -1.0)
        assertEquals(-first.signedDistance(arbitrary), opposite.signedDistance(arbitrary), 1e-8)
    }

    @Test fun canonicalTesseractSectionHasEightVerticesTwelveEdgesAndCorrectVolume() {
        val section = SliceGeometry.sliceBox(cube, SliceFrame())!!
        assertEquals(8, section.vertices.size)
        assertEquals(12, section.edges.size)
        assertEquals(36, section.mesh.indices.size)
        assertEquals(8.0, volume(section.mesh), 1e-8)
        for (point in section.vertices) {
            assertEquals(1.0, abs(point.x), 1e-8)
            assertEquals(1.0, abs(point.y), 1e-8)
            assertEquals(1.0, abs(point.z), 1e-8)
        }
    }

    @Test fun symmetricAngleDeduplicatesCoincidentFacesInsteadOfRenderingThemTwice() {
        val section = SliceGeometry.sliceBox(cube, SliceFrame(PI / 4))!!
        assertEquals(8, section.vertices.size)
        assertEquals(12, section.edges.size)
        assertEquals(36, section.mesh.indices.size)
        assertEquals(8 * sqrt(2.0), volume(section.mesh), 1e-8)
        assertEquals(sqrt(2.0), section.vertices.maxOf { it.x }, 1e-8)
    }

    @Test fun orientedBoxSectionsSatisfyAllOriginalConstraintsAtSeveralAngles() {
        val box = Box4("tilted", Vec4(0.2, -0.1, 1.0, -2.4), Vec4(0.8, 0.7, 0.6, 0.9), tiltedBasis())
        val vertexCounts = mutableSetOf<Int>()
        for (angle in listOf(0.0, 0.21, 0.7, 1.3, PI / 2, 2.1, PI, 5.8)) {
            val frame = SliceFrame(angle, Vec4(0.1, 0.2, 0.4, -0.3))
            val section = SliceGeometry.sliceBox(box, frame)!!
            vertexCounts += section.vertices.size
            for (point in section.vertices) {
                val world = frame.world(point)
                assertEquals(0.0, frame.signedDistance(world), 1e-8)
                for (axis in 0..3) assertTrue(abs((world - box.center).dot(box.basis[axis])) <= box.halfExtents[axis] + 1e-7)
            }
            assertTrue(volume(section.mesh) > 0.0)
            val uniqueNormals = mutableListOf<Vec3>()
            for (normal in section.mesh.normals) if (uniqueNormals.none { (it - normal).length() < 1e-8 }) uniqueNormals += normal
            assertEquals(2, section.vertices.size - section.edges.size + uniqueNormals.size)
        }
        assertTrue("The fixed object's section topology changes as the sole angle changes", vertexCounts.size > 1)
    }

    @Test fun meshWindingNormalsAndBoundaryEdgesAreOutwardForGeneralSections() {
        val box = Box4("tilted", Vec4(0.1, 0.2, 1.0, -3.0), Vec4(0.8, 0.8, 0.8, 0.8), tiltedBasis())
        for (angle in listOf(0.0, 0.4, 1.1, 2.0)) {
            val frame = SliceFrame(angle)
            val section = SliceGeometry.sliceBox(box, frame)!!
            val inside = section.vertices.fold(Vec3.ZERO) { sum, vertex -> sum + vertex } * (1.0 / section.vertices.size)
            val mesh = section.mesh
            for (triangle in mesh.indices.toList().chunked(3)) {
                val a = mesh.positions[triangle[0]]; val b = mesh.positions[triangle[1]]; val c = mesh.positions[triangle[2]]
                val normal = mesh.normals[triangle[0]]
                assertEquals(1.0, normal.length(), 1e-8)
                assertTrue((b - a).cross(c - a).dot(normal) > 1e-10)
                assertTrue((a - inside).dot(normal) > 1e-8)
            }
            for ((a, b) in section.edges) {
                assertTrue((b - a).length() > 1e-8)
                val worldA = frame.world(a) - box.center; val worldB = frame.world(b) - box.center
                val commonBounds = (0..3).count { axis ->
                    val coordinateA = worldA.dot(box.basis[axis]); val coordinateB = worldB.dot(box.basis[axis])
                    abs(coordinateA - coordinateB) < 1e-7 && abs(abs(coordinateA) - box.halfExtents[axis]) < 1e-7
                }
                assertTrue("Edges must be real polytope boundaries, not triangle diagonals", commonBounds >= 2)
            }
        }
    }

    @Test fun disjointAndZeroVolumeBoxSectionsAreNotRendered() {
        val hidden = cube.copy(center = Vec4(0.0, 3.0, 0.0, 0.0))
        assertNull(SliceGeometry.sliceBox(hidden, SliceFrame()))
        assertNotNull(SliceGeometry.sliceBox(hidden, SliceFrame(PI / 2)))
        val tangent = cube.copy(center = Vec4(1.0, -1.0, 0.0, 0.0))
        assertNull(SliceGeometry.sliceBox(tangent, SliceFrame(PI / 4)))
        assertNotNull(SliceGeometry.sliceBox(tangent, SliceFrame()))
    }

    @Test fun sphereUsesSignedNormalDistanceAndAnalyticSectionRadius() {
        val sphere = Sphere4("sphere", Vec4(1.5, 1.0, 1.25, -3.5), 1.1)
        val initial = SliceGeometry.sliceSphere(sphere, SliceFrame())!!
        assertLocal(Vec3(1.5, 1.25, -3.5), initial.center)
        assertEquals(sqrt(1.1 * 1.1 - 1.0), initial.radius, 1e-8)
        val throughCenter = SliceGeometry.sliceSphere(sphere, SliceFrame(atan2(1.0, 1.5)))!!
        assertEquals(1.1, throughCenter.radius, 1e-8)
        assertNull(SliceGeometry.sliceSphere(sphere, SliceFrame(PI / 2)))
        val opposite = SliceGeometry.sliceSphere(sphere, SliceFrame(PI))!!
        assertEquals(initial.radius, opposite.radius, 1e-8)
        assertLocal(Vec3(-initial.center.x, initial.center.y, initial.center.z), opposite.center)
        assertNull(SliceGeometry.sliceSphere(Sphere4("tangent", Vec4(0.0, 2.0, 0.0, 0.0), 2.0), SliceFrame()))
        val shiftedFrame = SliceFrame(0.0, Vec4(1.0, 0.5, 0.2, -0.4))
        val shiftedSection = SliceGeometry.sliceSphere(sphere, shiftedFrame)!!
        assertLocal(Vec3(0.5, 1.05, -3.1), shiftedSection.center)
        assertEquals(sqrt(1.1 * 1.1 - 0.5 * 0.5), shiftedSection.radius, 1e-8)
    }

    @Test fun capsuleSweepStopsAtThinSectionsAndIncludesTheWholeBodyRadius() {
        val wall = Box4("thin", Vec4(0.0, 0.0, 0.9, -1.0), Vec4(0.5, 0.5, 0.9, 0.002))
        val section = SliceGeometry.sliceBox(wall, SliceFrame())!!
        assertFalse(SliceGeometry.intersectsCapsule(section, Vec3.ZERO, 0.22, 1.7))
        assertFalse(SliceGeometry.intersectsCapsule(section, Vec3(0.0, 0.0, -2.0), 0.22, 1.7))
        assertTrue(SliceGeometry.intersectsSweptCapsule(section, Vec3.ZERO, Vec3(0.0, 0.0, -2.0), 0.22, 1.7))
        assertTrue(SliceGeometry.intersectsCapsule(section, Vec3(0.70, 0.0, -1.0), 0.22, 1.7))
        assertFalse(SliceGeometry.intersectsSweptCapsule(section, Vec3(0.73, 0.0, 0.0), Vec3(0.73, 0.0, -2.0), 0.22, 1.7))
        val decorative = SliceGeometry.sliceBox(wall.copy(solid = false), SliceFrame())!!
        assertFalse(SliceGeometry.intersectsSweptCapsule(decorative, Vec3.ZERO, Vec3(0.0, 0.0, -2.0), 0.22, 1.7))
    }

    @Test fun capsuleSweepHandlesOrientedWallsAndVerticalClearance() {
        val angle = PI / 4
        val basis = listOf(Vec4(cos(angle), 0.0, 0.0, sin(angle)), Vec4.Y, Vec4.Z, Vec4(-sin(angle), 0.0, 0.0, cos(angle)))
        val wall = Box4("oriented", Vec4(0.0, 0.0, 0.9, -1.0), Vec4(0.6, 0.5, 0.9, 0.02), basis)
        val section = SliceGeometry.sliceBox(wall, SliceFrame())!!
        assertTrue(SliceGeometry.intersectsSweptCapsule(section, Vec3.ZERO, Vec3(0.0, 0.0, -2.0), 0.22, 1.7))
        val overhead = SliceGeometry.sliceBox(wall.copy(center = Vec4(0.0, 0.0, 2.2, -1.0), halfExtents = Vec4(0.6, 0.5, 0.1, 0.02)), SliceFrame())!!
        assertFalse(SliceGeometry.intersectsSweptCapsule(overhead, Vec3.ZERO, Vec3(0.0, 0.0, -2.0), 0.22, 1.7))
    }

    @Test fun nonOrthonormalObjectBasesAndInvalidExtentsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { cube.copy(basis = listOf(Vec4.X, Vec4.X, Vec4.Z, Vec4.W)) }
        assertThrows(IllegalArgumentException::class.java) { cube.copy(halfExtents = Vec4(1.0, 0.0, 1.0, 1.0)) }
    }
}
