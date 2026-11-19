package com.fourthward.game.vr

import com.fourthward.game.core.LevelDependencyGraph
import com.fourthward.game.core.PalmAction
import com.fourthward.game.core.TutorialLevels
import com.fourthward.game.core.UiThemeMode
import com.fourthward.game.core.Vec3
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class WelcomeTesseractTest {
    // Mirrors the homogeneous divide in exhibitVertex, before OpenXR applies each eye matrix.
    private fun project(matrix: FloatArray, point: List<Float>): Vec3 {
        fun row(index: Int) = (0..3).sumOf { matrix[4 * it + index].toDouble() * point[it] }
        val divisor = matrix[19] - row(3)
        assertTrue(divisor > 1)
        return Vec3(matrix[16] + row(0) / divisor, matrix[17] + row(1) / divisor, matrix[18] + row(2) / divisor)
    }

    @Test fun movingInFourDimensionsChangesApparentEdgeLengthsAndStopsWhenPaused() {
        val exhibit = WelcomeTesseract()
        val a = listOf(1f, 1f, 1f, 1f)
        val b = listOf(-1f, 1f, 1f, 1f)
        val initial = exhibit.projection(0.0).copyOf()
        repeat(84) { exhibit.projection(.1) }
        val moved = exhibit.projection(0.0).copyOf()
        assertTrue(abs((project(initial, a) - project(initial, b)).length() -
            (project(moved, a) - project(moved, b)).length()) > .04)
        assertArrayEquals(moved, exhibit.projection(Double.NaN), 0f)
        assertArrayEquals(moved, exhibit.projection(-1.0), 0f)
        repeat(3) { assertArrayEquals(moved, exhibit.projection(0.0), 0f) }
    }

    @Test fun galleryThemeSwapsBakedMaterialsAndKeepsTheSameRoomAndStand() {
        val light = WelcomeEnvironment.build(UiThemeMode.LIGHT)
        val dark = WelcomeEnvironment.build(UiThemeMode.DARK)
        assertSame(light, WelcomeEnvironment.build(UiThemeMode.LIGHT))
        assertSame(dark, WelcomeEnvironment.build(UiThemeMode.DARK))
        assertEquals(light.size, dark.size)
        var darker = 0
        val vertices = light.size / 7
        for (i in light.indices step 7) {
            for (axis in 0..2) assertEquals(light[i + axis], dark[i + axis], 0f)
            assertEquals(light[i + 6], dark[i + 6], 0f)
            if (dark[i + 3] < light[i + 3] && dark[i + 4] < light[i + 4] && dark[i + 5] < light[i + 5]) {
                darker++
            }
        }
        assertTrue(darker > .75 * vertices)
    }

    @Test fun actualHardwareStaysAboveThePlinthAndOnTheRightAtAllSampledAngles() {
        val vertices = WelcomeTesseract.vertices
        assertSame(vertices, WelcomeTesseract.vertices)
        assertEquals(0, vertices.size % 21)
        assertTrue(vertices.size / 21 in 100..8000)
        val points = (vertices.indices step 7).mapTo(linkedSetOf()) { i ->
            listOf(vertices[i], vertices[i+1], vertices[i+2], vertices[i+6])
        }
        assertTrue(points.any { it[3] > 0 && it[3] < 1 }) // Sleeves occupy the full fourth direction.
        val exhibit = WelcomeTesseract()
        val head = VrTrackingMath.head(floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 1f))!!
        val panel = VrTrackingMath.startPanelPose(head)!!
        val layout = LevelDependencyGraph(TutorialLevels.all()).windowForLevel(0, PalmAction.CONTEXT).layout
        val rightEdge = listOf(-.5, .5).map { sign ->
            panel.position + panel.right * (layout.widthMeters / 2) + panel.up * (sign * layout.heightMeters)
        }
        repeat(80) {
            repeat(10) { exhibit.projection(.1) }
            val matrix = exhibit.projection(0.0)
            val projected = points.map { project(matrix, it) }
            assertTrue(projected.minOf { it.y } > -.30)
            for (eyeX in listOf(-.032, .032)) {
                val rightEdgeOnEye = rightEdge.maxOf { (it.x - eyeX) / -it.z }
                assertTrue(projected.minOf { (it.x - eyeX) / -it.z } > rightEdgeOnEye)
            }
            assertTrue(projected.maxOf { it.z } < -.65)
            assertTrue(projected.maxOf { it.z } - projected.minOf { it.z } > .17)
        }
    }
}
