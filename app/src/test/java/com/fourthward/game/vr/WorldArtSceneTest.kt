package com.fourthward.game.vr

import com.fourthward.game.core.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs

class WorldArtSceneTest {
    private val absentGoal = Sphere4("goal", Vec4(0.0, 20.0, 0.0, -2.0), .4)

    private fun game(world: World4, goal: Sphere4 = absentGoal) = FourthwardGame(listOf(
        TutorialLevel("art", "art", "art", "art", goal, 0xff77c7c6.toInt(), world),
    ))

    private fun verticesAfterGround(game: FourthwardGame, empty: FourthwardGame): FloatArray {
        val scene = VrSceneBuilder.build(game, false)
        val ground = VrSceneBuilder.build(empty, false)
        assertArrayEquals(ground, scene.copyOfRange(0, ground.size), 0f)
        return scene.copyOfRange(ground.size, scene.size)
    }

    private fun point(vertices: FloatArray, index: Int) = Vec3(
        vertices[index].toDouble(), vertices[index + 1].toDouble(), vertices[index + 2].toDouble(),
    )

    @Test fun themeChangesGroundInkAndPreservesLevelGeometryAndWallPaint() {
        val wall = Box4("wall", Vec4(0.0, 0.0, 1.1, -2.0), Vec4(1.4, .8, 1.4, .35))
        val world = game(World4(listOf(wall), emptyList()))
        val empty = game(World4(emptyList(), emptyList()))
        val groundSize = VrSceneBuilder.build(empty, false).size
        val light = VrSceneBuilder.build(world, false, UiThemeMode.LIGHT)
        val dark = VrSceneBuilder.build(world, false, UiThemeMode.DARK)
        assertEquals(light.size, dark.size)
        assertTrue(light[3] > dark[3])
        for (i in 0 until groundSize step 7) assertEquals(point(light, i), point(dark, i))
        assertArrayEquals(light.copyOfRange(groundSize, light.size), dark.copyOfRange(groundSize, dark.size), 0f)
    }

    @Test fun architecturalPaintStaysOnItsActualConvexFaceAtDifferentSliceAngles() {
        val box = Box4("wall", Vec4(0.0, 0.0, 1.1, -2.0), Vec4(1.4, .8, 1.4, .35))
        for (angle in listOf(0.0, .37, .91, PI / 2)) {
            val world = game(World4(listOf(box), emptyList()))
            val empty = game(World4(emptyList(), emptyList()))
            world.rotate(angle); empty.rotate(angle)
            val section = world.sections().single()
            val rendered = verticesAfterGround(world, empty)
            assertTrue("Wall detail has a bounded mobile-VR budget", rendered.size / 7 < 2000)
            assertTrue("Wall receives internal surface detail", rendered.size / 7 > section.mesh.indices.size)
            section.mesh.indices.forEachIndexed { index, sourceIndex ->
                val exact = section.mesh.positions[sourceIndex]
                assertEquals(exact.x.toFloat(), rendered[index * 7], 0f)
                assertEquals(exact.y.toFloat(), rendered[index * 7 + 1], 0f)
                assertEquals(exact.z.toFloat(), rendered[index * 7 + 2], 0f)
            }
            for (index in section.mesh.indices.size * 7 until rendered.size step 21) {
                val a = point(rendered, index); val b = point(rendered, index + 7); val c = point(rendered, index + 14)
                assertTrue("Paint emits no zero-area triangles", (b - a).cross(c - a).lengthSquared() > 0.0)
            }
            val faces = mutableListOf<Pair<List<Vec3>, Vec3>>()
            var first = 0
            while (first < section.mesh.positions.size) {
                val normal = section.mesh.normals[first]
                var end = first + 1
                while (end < section.mesh.positions.size && (section.mesh.normals[end] - normal).lengthSquared() < 1e-16) end++
                faces += section.mesh.positions.subList(first, end) to normal
                first = end
            }
            for (index in rendered.indices step 7) {
                val vertex = point(rendered, index)
                assertTrue("No painted vertex extends the convex hull by more than 1 mm", faces.all { (polygon, normal) ->
                    normal.dot(vertex - polygon.first()) <= .00101
                })
                assertTrue("Every decal projects inside its own exact face", faces.any { (polygon, normal) ->
                    val distance = normal.dot(vertex - polygon.first())
                    val projected = vertex - normal * distance
                    abs(distance) <= .00081 && polygon.indices.all { edge ->
                        val a = polygon[edge]; val b = polygon[(edge + 1) % polygon.size]
                        (b - a).cross(projected - a).dot(normal) >= -2e-6
                    }
                })
                assertEquals(1f, rendered[index + 6], 0f)
            }
        }
    }

    @Test fun tangentSurfaceClipsDoNotEmitDuplicateEndpointsOrTriangles() {
        val triangle = listOf(Vec3.ZERO, Vec3(1.0, 0.0, 0.0), Vec3(0.0, 1.0, 0.0))
        val edge = clipSurface(triangle, Vec3.ZERO, Vec3(1.0, 0.0, 0.0), 0.0)
        assertEquals(listOf(triangle[0], triangle[2]), edge)
        assertTrue(surfaceTriangles(edge).isEmpty())
        val vertex = clipSurface(triangle, Vec3.ZERO, Vec3(1.0, 1.0, 0.0), 0.0)
        assertEquals(listOf(Vec3.ZERO), vertex)
        assertTrue(surfaceTriangles(vertex).isEmpty())
    }

    @Test fun surfaceCleanupPreservesAreaWhenTheFanContainsCollinearOrRepeatedVertices() {
        val polygon = listOf(Vec3.ZERO, Vec3(.5, 0.0, 0.0), Vec3(1.0, 0.0, 0.0),
            Vec3(1.0, 1.0, 0.0), Vec3(0.0, 1.0, 0.0), Vec3(0.0, 1.0, 0.0), Vec3.ZERO)
        val triangles = surfaceTriangles(polygon)
        assertEquals(2, triangles.size)
        assertEquals(1.0, triangles.sumOf { (a, b, c) -> (b - a).cross(c - a).length() * .5 }, 1e-12)
        val clipped = clipSurface(listOf(Vec3.ZERO, Vec3(1.0, 0.0, 0.0), Vec3(0.0, 1.0, 0.0)),
            Vec3.ZERO, Vec3(1.0, 0.0, 0.0), .5)
        assertEquals(.375, surfaceTriangles(clipped).sumOf { (a, b, c) -> (b - a).cross(c - a).length() * .5 }, 1e-12)
    }

    @Test fun smoothSpherePreservesExactRadiusAndUsesOutwardTriangles() {
        val source = Sphere4("study-sphere", Vec4(1.1, 0.0, 1.3, -2.3), .63)
        val world = game(World4(emptyList(), listOf(source)))
        val rendered = verticesAfterGround(world, game(World4(emptyList(), emptyList())))
        val section = world.visibleSpheres().single()
        assertEquals(6624, rendered.size / 7)
        for (index in rendered.indices step 7) {
            assertEquals(section.radius, (point(rendered, index) - section.center).length(), 3e-7)
            for (channel in 3..5) assertTrue(rendered[index + channel].isFinite() && rendered[index + channel] in 0f..1f)
            assertEquals(1f, rendered[index + 6], 0f)
        }
        for (index in rendered.indices step 21) {
            val a = point(rendered, index); val b = point(rendered, index + 7); val c = point(rendered, index + 14)
            assertTrue("Smooth sphere winding points out of the real section", (b - a).cross(c - a).dot(a - section.center) > 0.0)
        }
    }

    @Test fun destinationHardwareDisappearsTogetherWithAnOffSliceGoal() {
        val goal = Sphere4("goal", Vec4(0.0, 2.0, 0.0, -2.0), .4)
        val world = game(World4(emptyList(), listOf(goal)), goal)
        val empty = game(World4(emptyList(), emptyList()), goal)
        assertArrayEquals(VrSceneBuilder.build(empty, false), VrSceneBuilder.build(world, false), 0f)
        world.rotate(PI / 2); empty.rotate(PI / 2)
        val visible = verticesAfterGround(world, empty)
        assertTrue("Destination hardware accompanies the true sphere", visible.size / 7 > 6624)
        assertTrue("Destination stays within a bounded triangle budget", visible.size / 7 < 8500)
        world.rotate(-PI / 2); empty.rotate(-PI / 2)
        assertArrayEquals(VrSceneBuilder.build(empty, false), VrSceneBuilder.build(world, false), 0f)
    }

    @Test fun sharedMaterialsKeepOpaqueColorAndRoundTripSrgb() {
        val color = floatArrayOf(.32f, .67f, .84f)
        val roundTrip = SceneMaterials.toSrgb(SceneMaterials.toLinear(color))
        for (channel in 0..2) assertEquals(color[channel], roundTrip[channel], 1e-6f)
        assertEquals(1f, roundTrip[3], 0f)
        for (normal in listOf(Vec3(1.0, 0.0, 0.0), Vec3(0.0, -1.0, 0.0), Vec3(0.0, 0.0, 1.0))) {
            val shaded = SceneMaterials.sphereLinear(SceneMaterials.toLinear(color), normal)
            assertTrue(shaded.all { it.isFinite() && it in 0f..1f })
            assertEquals(1f, shaded[3], 0f)
        }
    }
}
