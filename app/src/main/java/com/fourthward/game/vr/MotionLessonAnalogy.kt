package com.fourthward.game.vr

import com.fourthward.game.core.Box4
import com.fourthward.game.core.MotionStudies
import com.fourthward.game.core.MotionTopic
import com.fourthward.game.core.Vec3
import com.fourthward.game.core.Vec4
import kotlin.math.abs
import kotlin.math.atan2

/** A lower-dimensional source model, in (X,Z,Y), with W omitted from these product boxes. */
internal object MotionLessonAnalogy {
    fun point(value: Vec4) = Vec3(value.x, value.z, value.y)

    fun sphere(topic: MotionTopic, timeSeconds: Double): AnalogySphere? =
        MotionStudies.world(topic, timeSeconds).spheres.firstOrNull()?.let {
            AnalogySphere(it.id, point(it.center), it.radius)
        }

    fun corners(box: Box4): List<Vec3> = (0..7).map { bits ->
        var corner = box.center
        for (axis in 0..2) corner += box.basis[axis] *
            (if (bits and (1 shl axis) == 0) -box.halfExtents[axis] else box.halfExtents[axis])
        point(corner)
    }

    fun edges(box: Box4): List<Pair<Vec3, Vec3>> {
        val vertices = corners(box)
        return (0..7).flatMap { index -> (0..2).mapNotNull { axis ->
            val bit = 1 shl axis
            if (index and bit == 0) vertices[index] to vertices[index or bit] else null
        } }
    }

    /** Intersect all real source edges with the fixed plane; never project outside vertices. */
    fun polygon(box: Box4, angle: Double = 0.0): List<Vec3> {
        val normal = GeometryLessonAnalogy.normal(angle)
        val right = GeometryLessonAnalogy.right(angle)
        val intersections = mutableListOf<Vec3>()
        fun add(point: Vec3) {
            if (intersections.none { (it - point).lengthSquared() < 1e-16 }) intersections += point
        }
        for ((a, b) in edges(box)) {
            val da = a.dot(normal); val db = b.dot(normal)
            if (abs(da) < 1e-9) add(a)
            if (abs(db) < 1e-9) add(b)
            if (da * db < 0.0) add(a + (b - a) * (da / (da - db)))
        }
        if (intersections.size < 3) return emptyList()
        val center = intersections.fold(Vec3.ZERO) { total, point -> total + point } * (1.0 / intersections.size)
        return intersections.sortedBy { point ->
            val relative = point - center
            atan2(relative.y, relative.dot(right))
        }
    }
}
