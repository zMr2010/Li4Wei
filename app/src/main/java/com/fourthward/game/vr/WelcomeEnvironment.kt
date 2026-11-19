package com.fourthward.game.vr

import com.fourthward.game.core.UiThemeMode
import com.fourthward.game.core.Vec3
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/** Stationary exhibition architecture in metres, relative to the initial level gaze. */
internal class WelcomeEnvironment private constructor(
    private val wall: LessonColor,
    private val floor: LessonColor,
    private val deck: LessonColor,
    private val alloy: LessonColor,
    private val graphite: LessonColor,
    private val joint: LessonColor,
) {
    private val yellow = LessonColor(0xF4CA42)
    private val vertical = Vec3(0.0, 1.0, 0.0)

    companion object {
        private val light by lazy {
            WelcomeEnvironment(LessonColor(0xEEEDE6), LessonColor(0xD0D2C9), LessonColor(0xEEEDE6),
                LessonColor(0xA0A59E), LessonColor(0x353A39), LessonColor(0xBFC3B9)).vertices()
        }
        private val dark by lazy {
            WelcomeEnvironment(LessonColor(0x32393C), LessonColor(0x272E30), LessonColor(0x596465),
                LessonColor(0x828D8D), LessonColor(0x1A2021), LessonColor(0x444E50)).vertices()
        }

        fun build(theme: UiThemeMode): FloatArray = when (theme) {
            UiThemeMode.LIGHT -> light
            UiThemeMode.DARK -> dark
        }
    }

    private fun vertices(): FloatArray {
        val mesh = LessonMesh { it }
        room(mesh)
        instrumentStand(mesh)
        return mesh.result()
    }

    private fun room(mesh: LessonMesh) {
        val bottom = -1.45
        val top = 3.35
        val front = -3.60
        val rear = 4.40
        val left = -4.0
        val right = 4.0
        // Broad baked illumination keeps the gallery calm and gives each wall its own depth value.
        for (i in 0 until 8) for (j in 0 until 6) {
            val x = left + i
            val y = bottom + j * .8
            mesh.gradientFace(listOf(Vec3(x, y, front), Vec3(x + 1, y, front),
                Vec3(x + 1, y + .8, front), Vec3(x, y + .8, front))) { p ->
                val dx = (p.x + .9) / 3.8
                val dy = (p.y - 1.4) / 3.1
                wall.shade(.79 + .21 * exp(-dx * dx - dy * dy))
            }
        }
        mesh.face(listOf(Vec3(left, top, front), Vec3(right, top, front),
            Vec3(right, top, rear), Vec3(left, top, rear)), wall)
        mesh.face(listOf(Vec3(left, bottom, rear), Vec3(right, bottom, rear),
            Vec3(right, top, rear), Vec3(left, top, rear)), wall.shade(.88))
        for (x in listOf(left, right)) {
            mesh.face(listOf(Vec3(x, bottom, front), Vec3(x, bottom, rear),
                Vec3(x, top, rear), Vec3(x, top, front)), wall.shade(if (x == left) .91 else .85))
        }
        for (i in 0 until 16) for (j in 0 until 16) {
            val x = left + i * .5
            val z = front + j * .5
            mesh.gradientFace(listOf(Vec3(x, bottom, z), Vec3(x + .5, bottom, z),
                Vec3(x + .5, bottom, z + .5), Vec3(x, bottom, z + .5))) { p ->
                val dx = (p.x - .59) / .65
                val dz = (p.z + 1.02) / .43
                floor.shade(.98 - .24 * exp(-dx * dx - dz * dz))
            }
        }
        // Recessed wall joints and the low floor rails give scale as the player turns.
        val wallNormal = Vec3(0.0, 0.0, 1.0)
        for (x in listOf(-3.4, -1.95, 2.35, 3.52)) {
            mesh.flatLine(Vec3(x, bottom, front + .002), Vec3(x, top, front + .002), .008, wallNormal, joint)
        }
        mesh.flatLine(Vec3(left, -.99, front + .002), Vec3(right, -.99, front + .002), .006, wallNormal, joint)
        for (x in listOf(-2.18, 1.92)) {
            mesh.flatLine(Vec3(x, bottom + .002, front), Vec3(x, bottom + .002, rear), .009, vertical, alloy)
        }
    }

    private fun instrumentStand(mesh: LessonMesh) {
        val cx = .41
        val cz = -.98
        fun plinth(y: Double, width: Double, height: Double, depth: Double, top: LessonColor,
                   rim: LessonColor, bevel: LessonColor) = mesh.plinth(
            Vec3(cx, y, cz), Vec3(width / 2, height / 2, depth / 2), top, rim, bevel)
        plinth(-1.405, .55, .09, .43, alloy, graphite, alloy)
        plinth(-.884, .32, .955, .235, deck, alloy, deck)
        plinth(-.393, .49, .047, .39, graphite, graphite, alloy)
        plinth(-.335, .625, .070, .49, deck, graphite, alloy)

        val center = Vec3(cx, -.2995, cz)
        fun inPlane(angle: Double, radius: Double) = center + Vec3(cos(angle) * radius, 0.0, sin(angle) * radius)
        // Soft shadow on the deck and an engraved circular scale beneath the moving specimen.
        val steps = 64
        for (i in 0 until steps) {
            val a = i * 2 * PI / steps
            val b = (i + 1) * 2 * PI / steps
            mesh.gradientFace(listOf(center, inPlane(a, .204), inPlane(b, .204))) { p ->
                if (p == center) deck.shade(.72) else deck
            }
            val offset = vertical * .0006
            mesh.flatLine(inPlane(a, .207) + offset, inPlane(b, .207) + offset, .0014, vertical, alloy)
            if (i % 2 == 0) {
                val major = i % 8 == 0
                mesh.flatLine(inPlane(a, .211) + offset, inPlane(a, if (major) .228 else .218) + offset,
                    if (major) .0023 else .0015, vertical, if (major) graphite else alloy)
            }
        }
        // One enamel inset on the front fascia locates the viewing side.
        val z = cz + .2455
        mesh.face(listOf(Vec3(cx - .246, -.346, z), Vec3(cx - .108, -.346, z),
            Vec3(cx - .097, -.323, z), Vec3(cx - .235, -.323, z)), yellow)
    }
}
