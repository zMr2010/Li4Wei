package com.fourthward.game.vr

import com.fourthward.game.core.LessonBox
import com.fourthward.game.core.LessonPlayer
import com.fourthward.game.core.LessonTutorials
import com.fourthward.game.core.GeometryTopic
import com.fourthward.game.core.GeometryExhibits
import com.fourthward.game.core.SliceFrame
import com.fourthward.game.core.SliceGeometry
import com.fourthward.game.core.Vec3
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class LessonSceneBuilderTest {
    @Test fun actualSectionsStayOnThePlaneAndInsideTheirCuboidAtEveryAngle() {
        val box = LessonBox("test", Vec3(.2, -.1, .1), Vec3(.3, .4, .2))
        for (angle in listOf(0.0, .3, PI / 4, PI / 2, PI * .8)) {
            val origin = Vec3(.2, 0.0, .1)
            val normal = Vec3(-sin(angle), 0.0, cos(angle))
            val section = LessonSectionGeometry.section(box, origin, angle)
            assertEquals(4, section.size)
            section.forEach { point ->
                assertEquals(0.0, (point - origin).dot(normal), 1e-9)
                assertTrue(abs(point.x - box.center.x) <= box.halfExtents.x + 1e-9)
                assertTrue(abs(point.y - box.center.y) <= box.halfExtents.y + 1e-9)
                assertTrue(abs(point.z - box.center.z) <= box.halfExtents.z + 1e-9)
            }
            val area = (section[1] - section[0]).cross(section[3] - section[0]).length()
            assertTrue(area > 0.0)
        }
    }

    @Test fun thePlaneBeyondWallThicknessHasNoWallSection() {
        val walls = LessonTutorials.lesson(2).walls
        assertEquals(4, walls.count { LessonSectionGeometry.section(it, Vec3(-.65, -.1, 0.0), 0.0).isNotEmpty() })
        assertTrue(walls.all { LessonSectionGeometry.section(it, Vec3(-.12, -.1, .35), 0.0).isEmpty() })
    }

    @Test fun translatingTheCharacterWithinAPlaneDoesNotTranslateItsRenderedWorldWindow() {
        val a = Vec3(-.65, -.1, 0.0)
        val b = Vec3(.45, -.1, 0.0)
        val right = Vec3(1.0, 0.0, 0.0)
        val first = LessonSectionGeometry.horizontalRange(a, right, -.9, 1.05, -.3, .75)!!
        val second = LessonSectionGeometry.horizontalRange(b, right, -.9, 1.05, -.3, .75)!!
        assertEquals(0.0, ((a + right * first.first) - (b + right * second.first)).length(), 1e-9)
        assertEquals(0.0, ((a + right * first.second) - (b + right * second.second)).length(), 1e-9)
    }

    @Test fun allTutorialFramesAreFiniteOpaqueTrianglesAndUseAWorldFixedAnchor() {
        val identity = VrPose(Vec3.ZERO, Vec3(1.0, 0.0, 0.0), Vec3(0.0, 1.0, 0.0), Vec3(0.0, 0.0, 1.0))
        val delta = Vec3(4.0, 1.0, -7.0)
        val shifted = identity.copy(position = delta)
        for (definition in LessonTutorials.all()) {
            val player = LessonPlayer(definition.levelIndex)
            do {
                // Both moving and held end poses exercise different section topology.
                for (seconds in listOf(.7, 100.0)) {
                    player.tick(seconds)
                    val vertices = LessonSceneBuilder.build(player.scene, identity)
                    val moved = LessonSceneBuilder.build(player.scene, shifted)
                    assertTrue(vertices.isNotEmpty())
                    assertEquals(0, vertices.size % 21)
                    assertTrue(vertices.all { it.isFinite() })
                    assertEquals(vertices.size, moved.size)
                    for (i in vertices.indices step 7) {
                        assertEquals(delta.x.toFloat(), moved[i] - vertices[i], 1e-5f)
                        assertEquals(delta.y.toFloat(), moved[i + 1] - vertices[i + 1], 1e-5f)
                        assertEquals(delta.z.toFloat(), moved[i + 2] - vertices[i + 2], 1e-5f)
                        for (channel in 3..5) assertTrue(vertices[i + channel] in 0f..1f)
                        assertEquals(1f, vertices[i + 6], 0f)
                    }
                }
            } while (player.next())
        }
    }

    @Test fun miniatureSphereVerticesStayOnTheExactSectionAndDisappearWhenTheSliceMisses() {
        val anchor = identityAnchor()
        val initial = LessonPlayer(3).scene
        val source = GeometryExhibits.world(GeometryTopic.HYPERSPHERE).spheres.single()
        val scale = LessonSceneBuilder.GEOMETRY_SCALE
        val displayCenter = LessonSceneBuilder.geometryDisplayCenter(GeometryTopic.HYPERSPHERE)
        for ((angle, expectedRadius) in listOf(0.0 to sqrt(.75 * .75 - .7 * .7), PI / 4 to .75)) {
            val snapshot = initial.copy(angleRadians = angle)
            val section = SliceGeometry.sliceSphere(source, SliceFrame(angle))!!
            assertEquals(expectedRadius, section.radius, 1e-9)
            val mesh = LessonSceneBuilder.geometryExhibitVertices(snapshot, anchor)
            assertTrue(mesh.isNotEmpty())
            for (vertex in points(mesh)) {
                val unscaled = undoDisplayYaw(vertex) * (1.0 / scale) + displayCenter
                assertEquals(expectedRadius, (unscaled - section.center).length(), 1e-6)
            }
        }
        val absent = initial.copy(angleRadians = 3 * PI / 4)
        assertTrue(LessonSceneBuilder.geometryExhibitVertices(absent, anchor).isEmpty())
        // The right-hand section is absent, but the left-hand 3D source sphere and its
        // now-disjoint 2D plane deliberately remain available for the observer to inspect.
        assertTrue(LessonSceneBuilder.analogyVertices(absent, anchor).isNotEmpty())
        val pairedWithoutRightSection = LessonSceneBuilder.build(absent, anchor)
        assertTrue(pairedWithoutRightSection.isNotEmpty())
        assertTrue(pairedWithoutRightSection.all { it.isFinite() })
        assertTrue(pairedWithoutRightSection.size < LessonSceneBuilder.build(initial, anchor).size)
    }

    @Test fun miniatureSphereFacesOutwardAndShadingIsContinuousAcrossTriangleBoundaries() {
        val snapshot = LessonPlayer(3).scene.copy(angleRadians = PI / 4)
        val mesh = LessonSceneBuilder.geometryExhibitVertices(snapshot, identityAnchor())
        val vertexColors = mutableMapOf<List<Float>, FloatArray>()
        var sharedVertices = 0
        for (index in mesh.indices step 7) {
            val position = listOf(mesh[index], mesh[index + 1], mesh[index + 2])
            val color = mesh.copyOfRange(index + 3, index + 6)
            val previous = vertexColors.putIfAbsent(position, color)
            if (previous != null) {
                sharedVertices++
                assertArrayEquals("Adjacent triangles must share their surface color", previous, color, 1e-6f)
            }
        }
        assertTrue(sharedVertices > 0)
        assertTrue("Lighting must vary over a curved surface, not flatten the whole sphere",
            vertexColors.values.map { it.toList() }.distinct().size > 20)
        assertTrue("At least one triangle must interpolate lighting between its vertices",
            mesh.indices.step(21).any { index ->
                (3..5).any { channel -> abs(mesh[index + channel] - mesh[index + 7 + channel]) > 1e-4f }
            })
        val source = GeometryExhibits.world(GeometryTopic.HYPERSPHERE).spheres.single()
        val section = SliceGeometry.sliceSphere(source, SliceFrame(snapshot.angleRadians))!!
        val displayCenter = LessonSceneBuilder.geometryDisplayCenter(GeometryTopic.HYPERSPHERE)
        val positions = points(mesh).map {
            undoDisplayYaw(it) * (1.0 / LessonSceneBuilder.GEOMETRY_SCALE) + displayCenter
        }
        for ((a, b, c) in positions.chunked(3)) {
            val outward = (a + b + c) * (1.0 / 3.0) - section.center
            assertTrue("Every sphere triangle must face away from its analytic center",
                (b - a).cross(c - a).dot(outward) > 0.0)
        }
    }

    @Test fun miniatureTesseractShowsACubeThenAWiderCuboidThenACubeUsingFixedScale() {
        val initial = LessonPlayer(4).scene
        val source = GeometryExhibits.world(GeometryTopic.TESSERACT).boxes.single()
        val scale = LessonSceneBuilder.GEOMETRY_SCALE
        val center = LessonSceneBuilder.geometryDisplayCenter(GeometryTopic.TESSERACT)
        for ((angle, width) in listOf(0.0 to 1.3, PI / 4 to 1.3 * sqrt(2.0), PI / 2 to 1.3)) {
            val section = SliceGeometry.sliceBox(source, SliceFrame(angle))!!
            assertEquals(8, section.vertices.size)
            assertEquals(12, section.edges.size)
            val mesh = points(LessonSceneBuilder.geometryExhibitVertices(initial.copy(angleRadians = angle), identityAnchor())).map(::undoDisplayYaw)
            val faces = mesh.take(section.mesh.indices.size)
            assertEquals(width * scale, extent(faces) { it.x }, 1e-6)
            assertEquals(1.3 * scale, extent(faces) { it.y }, 1e-6)
            assertEquals(1.3 * scale, extent(faces) { it.z }, 1e-6)
            section.mesh.indices.forEachIndexed { index, sourceIndex ->
                val expected = (section.mesh.positions[sourceIndex] - center) * scale
                assertEquals(0.0, (mesh[index] - expected).length(), 1e-6)
            }
        }
    }

    @Test fun stackedExhibitsBeginWithMatchingCubesButRevealDifferentHiddenWidths() {
        val initial = LessonPlayer(5).scene
        val scale = LessonSceneBuilder.GEOMETRY_SCALE
        for ((angle, expected) in listOf(0.0 to (.9 to .9), PI / 4 to (.5 * sqrt(2.0) to .9 * sqrt(2.0)),
            PI / 2 to (.5 to 2.2))) {
            val mesh = points(LessonSceneBuilder.geometryExhibitVertices(initial.copy(angleRadians = angle), identityAnchor())).map(::undoDisplayYaw)
            val lower = mesh.filter { it.y < 0.0 }
            val upper = mesh.filter { it.y > 0.0 }
            assertTrue(lower.isNotEmpty() && upper.isNotEmpty())
            // The section-edge rods add at most 2.2 mm to the measured display width.
            assertEquals(expected.first * scale, extent(lower) { it.x }, .003)
            assertEquals(expected.second * scale, extent(upper) { it.x }, .003)
        }
    }

    @Test fun pairedModelsUseSeparateFixedAnchorsWithoutChangingTheActualSectionGeometry() {
        val anchor = identityAnchor()
        for (level in 3..5) for (angle in listOf(0.0, PI / 4, PI / 2, 3 * PI / 4)) {
            val snapshot = LessonPlayer(level).scene.copy(angleRadians = angle)
            val left = LessonSceneBuilder.analogyVertices(snapshot, anchor.copy(position = GeometryLessonLayout.LEFT_CENTER))
            val right = LessonSceneBuilder.geometryExhibitVertices(snapshot, anchor.copy(position = GeometryLessonLayout.RIGHT_CENTER))
            val composed = LessonSceneBuilder.build(snapshot, anchor)
            assertTrue(left.isNotEmpty())
            assertTrue(composed.size > left.size + right.size) // Two independently fixed plinths.
            assertArrayEquals(left + right, composed.copyOfRange(composed.size - left.size - right.size, composed.size), 0f)
            assertTrue(left.all { it.isFinite() })
            assertTrue(right.all { it.isFinite() })
            // Both panels remain on their own side of the shared center gap.
            assertTrue(points(left).maxOf { it.x } < -.02)
            if (right.isNotEmpty()) assertTrue(points(right).minOf { it.x } > .02)
        }
    }

    @Test fun leftCircleHasVisibleFaceWidthAtAllThreeKeyPresentationAngles() {
        val initial = LessonPlayer(3).scene
        val sphere = GeometryLessonAnalogy.spheres(GeometryTopic.HYPERSPHERE).single()
        for (angle in listOf(0.0, PI / 4, PI / 2)) {
            val mesh = LessonSceneBuilder.analogyVertices(initial.copy(angleRadians = angle), identityAnchor())
            // The filled circular face has one unshaded color; source wires and rim rods
            // use other colors. Measure that real emitted face, not a projected bound.
            val circleFace = mesh.indices.step(7).filter { index ->
                abs(mesh[index + 3] - 190f / 255) < 1e-5 &&
                    abs(mesh[index + 4] - 167f / 255) < 1e-5 && abs(mesh[index + 5] - 81f / 255) < 1e-5
            }.map { Vec3(mesh[it].toDouble(), mesh[it + 1].toDouble(), mesh[it + 2].toDouble()) }
            assertTrue(circleFace.isNotEmpty())
            val radius = GeometryLessonAnalogy.circle(sphere, angle)!!.radius
            val expectedWidth = 2 * radius * LessonSceneBuilder.GEOMETRY_SCALE * abs(cos(angle - 35 * PI / 180))
            assertEquals(expectedWidth, extent(circleFace) { it.x }, 1e-6)
            assertTrue(extent(circleFace) { it.x } > .05)
        }
    }

    private fun identityAnchor() = VrPose(Vec3.ZERO, Vec3(1.0, 0.0, 0.0), Vec3(0.0, 1.0, 0.0), Vec3(0.0, 0.0, 1.0))
    /** Remove only the fixed right-hand miniature presentation yaw, leaving the section
     * angle untouched so dimension checks still verify the original four-dimensional cut. */
    private fun undoDisplayYaw(point: Vec3): Vec3 {
        val angle = -LessonSceneBuilder.GEOMETRY_DISPLAY_YAW
        return Vec3(cos(angle) * point.x + sin(angle) * point.z, point.y,
            -sin(angle) * point.x + cos(angle) * point.z)
    }
    private fun points(mesh: FloatArray): List<Vec3> = mesh.indices.step(7).map {
        Vec3(mesh[it].toDouble(), mesh[it + 1].toDouble(), mesh[it + 2].toDouble())
    }
    private fun extent(points: List<Vec3>, axis: (Vec3) -> Double): Double = points.maxOf(axis) - points.minOf(axis)
}
