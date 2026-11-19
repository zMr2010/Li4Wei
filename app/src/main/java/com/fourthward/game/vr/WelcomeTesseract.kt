package com.fourthward.game.vr

import com.fourthward.game.core.Vec3
import com.fourthward.game.core.Vec4
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** A display model only. The game and the teaching exhibits still use exact sections. */
internal class WelcomeTesseract {
    private var time = 0.0
    private val uniforms = FloatArray(20)

    /** Column-major 4D rotation with the meter scale in XYZ, followed by center XYZ and 4D focal distance. */
    fun projection(deltaSeconds: Double): FloatArray {
        if (deltaSeconds.isFinite()) time += deltaSeconds.coerceIn(0.0, .1)
        uniforms.fill(0f, 0, 16)
        for (i in 0..3) uniforms[i * 5] = 1f
        fun rotate(a: Int, b: Int, angle: Double) {
            val c = cos(angle)
            val s = sin(angle)
            for (column in 0 until 16 step 4) {
                val x = uniforms[column + a]
                val y = uniforms[column + b]
                uniforms[column + a] = (c * x - s * y).toFloat()
                uniforms[column + b] = (s * x + c * y).toFloat()
            }
        }
        rotate(0, 3, .14 + time * .19)
        rotate(2, 3, -.12 + .24 * sin(time * .11))
        rotate(1, 2, .34 + time * .085)
        rotate(2, 0, .12 + .11 * sin(time * .13))
        rotate(0, 1, -.08)
        val focalDistance = 3.55f
        for (column in 0 until 16 step 4) for (row in 0..2) {
            uniforms[column + row] *= .103f * focalDistance
        }
        uniforms[16] = .41f
        uniforms[17] = (-.015 + .005 * sin(time * .75)).toFloat()
        uniforms[18] = -.98f
        uniforms[19] = focalDistance
        return uniforms
    }

    companion object {
        // The same seven-float VBO as the game. For this opaque draw only, its alpha lane holds W.
        val vertices: FloatArray by lazy { buildExhibit() }
        private val graphite = LessonColor(0x383D40)
        private val seam = LessonColor(0x202527)
        private val nickel = LessonColor(0x949D9C)
        private val ceramic = LessonColor(0xE4E2D5)
        private val yellow = LessonColor(0xF3CC43)
        private val warmMetal = LessonColor(0xAC8E4D)

        private fun buildExhibit(): FloatArray {
            val parts = mutableListOf<FloatArray>()
            fun mount(model: FloatArray, place: (Vec3) -> Vec4) {
                val part = model.copyOf()
                for (i in part.indices step 7) {
                    val p = place(Vec3(part[i].toDouble(), part[i + 1].toDouble(), part[i + 2].toDouble()))
                    part[i] = p.x.toFloat()
                    part[i + 1] = p.y.toFloat()
                    part[i + 2] = p.z.toFloat()
                    part[i + 6] = p.w.toFloat()
                }
                parts += part
            }
            val axes = listOf(Vec3(1.0, 0.0, 0.0), Vec3(0.0, 1.0, 0.0), Vec3(0.0, 0.0, 1.0))
            val signs = listOf(-1.0, 1.0)
            val joint = hub()
            val link = piston()
            for (w in signs) {
                val outer = w > 0.0
                for (axis in axes.indices) {
                    val along = axes[axis]
                    val u = axes[(axis + 1) % 3]
                    val v = axes[(axis + 2) % 3]
                    val shell = if (outer && axis == 0) graphite else if (outer) ceramic else nickel
                    val inlay = if (axis == 1 && !outer) yellow else if (outer && axis == 0) nickel else graphite
                    val rail = spar(shell, inlay)
                    for (i in signs) for (j in signs) mount(rail) { p ->
                        val xyz = along * p.y + u * (i + p.x) + v * (j + p.z)
                        Vec4(xyz.x, xyz.y, xyz.z, w)
                    }
                    // Cast gussets occupy only two corners of each selected face, leaving a view into both cells.
                    if (axis != 0) {
                        val plate = cornerPlate(if (outer) ceramic else graphite, marked = axis == 2)
                        for (i in signs) mount(plate) { p ->
                            val xyz = along * (1.0 + p.y) + u * (i * (1.0 - p.x)) + v * (i * (1.0 - p.z))
                            Vec4(xyz.x, xyz.y, xyz.z, w)
                        }
                    }
                }
                for (x in signs) for (y in signs) for (z in signs) mount(joint) { p ->
                    Vec4(x + p.x, y + p.y, z + p.z, w)
                }
            }
            // Each of the eight remaining edges runs parallel to W. Its sleeve and moving
            // linkage are solid 4D-authored parts; all their surfaces undergo the same projection.
            for (x in signs) for (y in signs) for (z in signs) {
                val radial = Vec3(x, y, z) * (1 / sqrt(3.0))
                val across = Vec3(-y, x, 0.0) * (1 / sqrt(2.0))
                val other = radial.cross(across)
                mount(link) { p ->
                    val xyz = Vec3(x, y, z) + across * p.x + other * p.z
                    Vec4(xyz.x, xyz.y, xyz.z, p.y)
                }
            }
            // A split enamel fiducial marks the invariant origin while the cells pass through one another.
            mount(originMarker()) { p -> Vec4(p.x, p.y, p.z, 0.0) }
            val result = FloatArray(parts.sumOf { it.size })
            var offset = 0
            for (part in parts) {
                part.copyInto(result, offset)
                offset += part.size
            }
            return result
        }

        private fun spar(shell: LessonColor, insert: LessonColor): FloatArray {
            val m = LessonMesh { it }
            m.sleeve(-.94, .94, .059, .059, seam)
            m.sleeve(-.78, .78, .083, .083, shell)
            m.sleeve(-.85, -.80, .072, .072, nickel)
            m.sleeve(.80, .85, .072, .072, nickel)
            // An asymmetric inlay on both outward faces identifies the spar direction.
            for (side in listOf(-1.0, 1.0)) {
                val z = side * .084
                m.face(listOf(Vec3(-.019, -.64, z), Vec3(.019, -.60, z),
                    Vec3(.019, .61, z), Vec3(-.019, .61, z)), insert)
            }
            return m.result()
        }

        private fun hub(): FloatArray {
            val m = LessonMesh { it }
            m.sleeve(-.146, -.113, .094, .130, nickel)
            m.sleeve(-.113, .085, .130, .130, graphite)
            m.sleeve(.085, .123, .130, .101, nickel)
            m.sleeve(.123, .131, .076, .076, seam)
            return m.result()
        }

        private fun piston(): FloatArray {
            val m = LessonMesh { it }
            m.sleeve(-1.0, 1.0, .033, .033, nickel)
            m.sleeve(-.62, .57, .055, .055, yellow)
            m.sleeve(-.69, -.61, .061, .061, warmMetal)
            m.sleeve(.57, .64, .061, .061, graphite)
            for (side in listOf(-1.0, 1.0)) {
                m.sleeve(side * .77, side * .98, .071, .089, graphite)
            }
            return m.result()
        }

        private fun originMarker(): FloatArray {
            val m = LessonMesh { it }
            fun ring(y: Double, radius: Double) = List(6) { i ->
                val angle = (i + .5) * PI / 3
                Vec3(cos(angle) * radius, y, sin(angle) * radius)
            }
            val waist = ring(-.024, .177)
            val shoulder = ring(.033, .168)
            val crown = ring(.14, .071)
            for (i in waist.indices) {
                val j = (i + 1) % waist.size
                m.face(listOf(Vec3(0.0, -.265, 0.0), waist[i], waist[j]), graphite)
                m.face(listOf(waist[i], waist[j], shoulder[j], shoulder[i]), yellow)
                m.face(listOf(shoulder[i], shoulder[j], crown[j], crown[i]), seam)
            }
            m.face(crown, nickel)
            return m.result()
        }

        /** A trapezoidal bridge between perpendicular spars, with a dark reveal on its edge. */
        private fun cornerPlate(shell: LessonColor, marked: Boolean): FloatArray {
            val m = LessonMesh { it }
            fun outline(y: Double) = listOf(Vec3(.10, y, .09), Vec3(.57, y, .09),
                Vec3(.57, y, .18), Vec3(.18, y, .57), Vec3(.09, y, .57), Vec3(.09, y, .10))
            val bottom = outline(.012)
            val top = outline(.074)
            m.face(bottom.reversed(), graphite)
            m.face(top, shell)
            for (i in top.indices) {
                val j = (i + 1) % top.size
                m.face(listOf(bottom[i], bottom[j], top[j], top[i]), if (i == 2) nickel else graphite)
            }
            if (marked) for (i in 0..1) {
                val x = .19 + i * .062
                m.face(listOf(Vec3(x, .075, .13), Vec3(x + .028, .075, .13),
                    Vec3(x + .002, .075, .21), Vec3(x - .026, .075, .21)), if (i == 0) yellow else seam)
            }
            return m.result()
        }

        /** Octagonal extrusion, with a continuous bevel where the two radii differ. */
        private fun LessonMesh.sleeve(start: Double, end: Double, r0: Double, r1: Double, metal: LessonColor) {
            fun rim(y: Double, r: Double): List<Vec3> {
                val k = .67 * r
                return listOf(Vec3(-k, y, -r), Vec3(k, y, -r), Vec3(r, y, -k), Vec3(r, y, k),
                    Vec3(k, y, r), Vec3(-k, y, r), Vec3(-r, y, k), Vec3(-r, y, -k))
            }
            val a = rim(start, r0)
            val b = rim(end, r1)
            for (i in a.indices) {
                val j = (i + 1) % a.size
                face(listOf(a[i], a[j], b[j], b[i]), metal)
            }
            face(a, metal)
            face(b.reversed(), metal)
        }
    }
}
