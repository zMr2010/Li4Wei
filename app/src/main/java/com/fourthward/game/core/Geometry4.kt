package com.fourthward.game.core

import kotlin.math.*

data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(other: Vec3) = Vec3(x + other.x, y + other.y, z + other.z)
    operator fun minus(other: Vec3) = Vec3(x - other.x, y - other.y, z - other.z)
    operator fun times(scale: Double) = Vec3(x * scale, y * scale, z * scale)
    fun dot(other: Vec3) = x * other.x + y * other.y + z * other.z
    fun cross(other: Vec3) = Vec3(y * other.z - z * other.y, z * other.x - x * other.z, x * other.y - y * other.x)
    fun lengthSquared() = dot(this)
    fun length() = sqrt(lengthSquared())
    companion object { val ZERO = Vec3(0.0, 0.0, 0.0) }
}

data class Vec4(val x: Double, val y: Double, val z: Double, val w: Double) {
    operator fun plus(other: Vec4) = Vec4(x + other.x, y + other.y, z + other.z, w + other.w)
    operator fun minus(other: Vec4) = Vec4(x - other.x, y - other.y, z - other.z, w - other.w)
    operator fun times(scale: Double) = Vec4(x * scale, y * scale, z * scale, w * scale)
    operator fun get(index: Int): Double = when (index) { 0 -> x; 1 -> y; 2 -> z; 3 -> w; else -> error("4D vector index: $index") }
    fun dot(other: Vec4) = x * other.x + y * other.y + z * other.z + w * other.w
    fun lengthSquared() = dot(this)
    fun length() = sqrt(lengthSquared())
    companion object {
        val ZERO = Vec4(0.0, 0.0, 0.0, 0.0)
        val X = Vec4(1.0, 0.0, 0.0, 0.0)
        val Y = Vec4(0.0, 1.0, 0.0, 0.0)
        val Z = Vec4(0.0, 0.0, 1.0, 0.0)
        val W = Vec4(0.0, 0.0, 0.0, 1.0)
    }
}

/** Affine H=origin+span{(cos(theta),sin(theta),0,0), e_z, e_w}.
 * Native VR (horizontal,vertical,depth) is (s,z,w). Tracking alone never changes theta. */
class SliceFrame(angleRadians: Double = 0.0, val origin: Vec4 = Vec4.ZERO) {
    val angleRadians: Double = normalizeAngle(angleRadians)
    val right = Vec4(cos(this.angleRadians), sin(this.angleRadians), 0.0, 0.0)
    val up: Vec4 = Vec4.Z
    val forward: Vec4 = Vec4.W
    val normal = Vec4(-sin(this.angleRadians), cos(this.angleRadians), 0.0, 0.0)

    init { require((0..3).all { origin[it].isFinite() }) }
    fun world(local: Vec3): Vec4 = origin + right * local.x + up * local.y + forward * local.z
    /** Coordinates of the orthogonal component in H; only a section test establishes membership. */
    fun local(world: Vec4): Vec3 {
        val relative = world - origin
        return Vec3(relative.dot(right), relative.z, relative.w)
    }
    fun signedDistance(world: Vec4): Double = (world - origin).dot(normal)
    fun rotated(deltaRadians: Double, pivotLocal: Vec3): SliceFrame {
        val rotated = SliceFrame(angleRadians + deltaRadians, origin)
        val fixedPlayer = world(pivotLocal)
        return SliceFrame(rotated.angleRadians, fixedPlayer - rotated.right * pivotLocal.x - up * pivotLocal.y - forward * pivotLocal.z)
    }
    fun movedPlanar(side: Double, depth: Double): SliceFrame = SliceFrame(angleRadians, origin + right * side + forward * depth)

    companion object {
        private fun normalizeAngle(angle: Double): Double {
            require(angle.isFinite()) { "Slice angle must be finite" }
            val remainder = angle % (2.0 * PI)
            val positive = if (remainder < 0.0) remainder + 2.0 * PI else remainder
            // Incremental pivot-preserving rotations can leave roundoff on either side of a
            // full turn. Canonical zero keeps angle labels and normalized progress consistent.
            return if (positive <= 1e-12 || 2.0 * PI - positive <= 1e-12) 0.0 else positive
        }
    }
}

/** A box in its own orthonormal 4D basis; object orientation is fixed world geometry. */
data class Box4(
    val id: String,
    val center: Vec4,
    val halfExtents: Vec4,
    val basis: List<Vec4> = listOf(Vec4.X, Vec4.Y, Vec4.Z, Vec4.W),
    val solid: Boolean = true,
    val colorArgb: Int? = null,
) {
    init {
        require((0..3).all { center[it].isFinite() && halfExtents[it].isFinite() && halfExtents[it] > 0.0 })
        require(basis.size == 4 && basis.all { axis -> (0..3).all { axis[it].isFinite() } })
        require(basis.all { abs(it.lengthSquared() - 1.0) <= 1e-8 }) { "Box basis axes must be unit length" }
        require((0..3).all { i -> (i + 1..3).all { j -> abs(basis[i].dot(basis[j])) <= 1e-8 } }) { "Box basis must be orthogonal" }
    }
}
data class Sphere4(val id: String, val center: Vec4, val radius: Double) {
    init { require(radius.isFinite() && radius > 0.0 && (0..3).all { center[it].isFinite() }) }
}
data class MeshData(val positions: List<Vec3>, val normals: List<Vec3>, val indices: IntArray)
data class BoxSection(val source: Box4, val vertices: List<Vec3>, val edges: List<Pair<Vec3, Vec3>>, val mesh: MeshData)
data class SphereSection(val source: Sphere4, val center: Vec3, val radius: Double)

object SliceGeometry {
    private data class Plane(val normal: Vec3, val limit: Double)

    /** Substitute world(q) into all eight box inequalities, then intersect plane triples.
     * These are true convex sections, never projections of off-plane vertices or edges. */
    fun sliceBox(box: Box4, frame: SliceFrame): BoxSection? {
        val scale = max(1.0, (0..3).maxOf { box.halfExtents[it] })
        val epsilon = 1e-8 * scale
        val planes = mutableListOf<Plane>()
        for (axis in 0..3) {
            val basis = box.basis[axis]
            val coefficient = Vec3(basis.dot(frame.right), basis.dot(frame.up), basis.dot(frame.forward))
            val offset = basis.dot(box.center - frame.origin)
            for (sign in listOf(1.0, -1.0)) {
                val normal = coefficient * sign
                val limit = box.halfExtents[axis] + sign * offset
                val length = normal.length()
                if (length < 1e-10) {
                    if (limit < -epsilon) return null
                    continue
                }
                val plane = Plane(normal * (1.0 / length), limit / length)
                // Coincident supporting planes occur at symmetric angles; render one face only.
                val parallel = planes.indexOfFirst { (it.normal - plane.normal).lengthSquared() < 1e-20 }
                if (parallel < 0) planes += plane
                else if (plane.limit < planes[parallel].limit) planes[parallel] = plane
            }
        }

        val vertices = mutableListOf<Vec3>()
        for (i in planes.indices) for (j in i + 1 until planes.size) for (k in j + 1 until planes.size) {
            val a = planes[i]; val b = planes[j]; val c = planes[k]
            val determinant = a.normal.dot(b.normal.cross(c.normal))
            if (abs(determinant) < 1e-10) continue
            val point = (b.normal.cross(c.normal) * a.limit + c.normal.cross(a.normal) * b.limit + a.normal.cross(b.normal) * c.limit) * (1.0 / determinant)
            if (planes.all { it.normal.dot(point) <= it.limit + epsilon } &&
                vertices.none { (it - point).lengthSquared() <= epsilon * epsilon * 16.0 }) vertices += point
        }
        if (!hasVolume(vertices, epsilon)) return null

        val faces = mutableListOf<Pair<List<Int>, Vec3>>()
        for (plane in planes) {
            val onFace = vertices.indices.filter { abs(plane.normal.dot(vertices[it]) - plane.limit) <= epsilon * 4.0 }
            if (onFace.size < 3) continue
            val centroid = onFace.fold(Vec3.ZERO) { sum, index -> sum + vertices[index] } * (1.0 / onFace.size)
            val reference = if (abs(plane.normal.x) < 0.8) Vec3(1.0, 0.0, 0.0) else Vec3(0.0, 1.0, 0.0)
            val rawU = reference.cross(plane.normal)
            val u = rawU * (1.0 / rawU.length())
            val v = plane.normal.cross(u) // u x v = outward normal.
            val sorted = onFace.sortedBy { index ->
                val delta = vertices[index] - centroid
                atan2(delta.dot(v), delta.dot(u))
            }.toMutableList()
            removeCollinear(sorted, vertices, epsilon)
            if (sorted.size >= 3) faces += sorted to plane.normal
        }
        if (faces.size < 4) return null

        val positions = mutableListOf<Vec3>()
        val normals = mutableListOf<Vec3>()
        val indices = mutableListOf<Int>()
        val edgeIndices = linkedSetOf<Pair<Int, Int>>()
        for ((face, normal) in faces) {
            val start = positions.size
            positions += face.map { vertices[it] }
            normals += List(face.size) { normal }
            for (i in 1 until face.size - 1) {
                indices += start; indices += start + i; indices += start + i + 1
            }
            for (i in face.indices) {
                val a = face[i]; val b = face[(i + 1) % face.size]
                edgeIndices += min(a, b) to max(a, b)
            }
        }
        val usedVertices = faces.flatMap { it.first }.distinct().map { vertices[it] }
        return BoxSection(box, usedVertices, edgeIndices.map { vertices[it.first] to vertices[it.second] },
            MeshData(positions, normals, indices.toIntArray()))
    }

    fun sliceSphere(sphere: Sphere4, frame: SliceFrame): SphereSection? {
        val distance = frame.signedDistance(sphere.center)
        val radiusSquared = sphere.radius * sphere.radius - distance * distance
        if (radiusSquared <= 0.0) return null
        return SphereSection(sphere, frame.local(sphere.center), sqrt(radiusSquared))
    }

    /** A vertical player capsule in slice coordinates, including the full horizontal radius. */
    fun intersectsCapsule(section: BoxSection, feet: Vec3, radius: Double, height: Double): Boolean {
        require(radius > 0.0 && height >= 2.0 * radius)
        if (!section.source.solid) return false
        val bottom = feet + Vec3(0.0, radius, 0.0)
        val top = feet + Vec3(0.0, height - radius, 0.0)
        if (inside(section.mesh, bottom) || inside(section.mesh, top)) return true
        val mesh = section.mesh
        for (i in mesh.indices.indices step 3) {
            if (segmentTriangleDistanceSquared(bottom, top, mesh.positions[mesh.indices[i]],
                    mesh.positions[mesh.indices[i + 1]], mesh.positions[mesh.indices[i + 2]]) <= radius * radius + 1e-10) return true
        }
        return false
    }

    /** The swept capsule centerline is a rectangle or a trapezoid when the head rises.
     * Its distance to every polytope face prevents tunneling through thin sections. */
    fun intersectsSweptCapsule(section: BoxSection, start: Vec3, end: Vec3, radius: Double, height: Double,
                               endHeight: Double = height): Boolean {
        require(radius > 0.0 && height >= 2.0 * radius && endHeight >= 2.0 * radius)
        if (!section.source.solid) return false
        if (intersectsCapsule(section, start, radius, height) || intersectsCapsule(section, end, radius, endHeight)) return true
        if ((end - start).lengthSquared() < 1e-16 && abs(endHeight - height) < 1e-12) return false
        val a = start + Vec3(0.0, radius, 0.0); val b = start + Vec3(0.0, height - radius, 0.0)
        val c = end + Vec3(0.0, endHeight - radius, 0.0); val d = end + Vec3(0.0, radius, 0.0)
        val mesh = section.mesh
        for (i in mesh.indices.indices step 3) {
            val p = mesh.positions[mesh.indices[i]]; val q = mesh.positions[mesh.indices[i + 1]]; val r = mesh.positions[mesh.indices[i + 2]]
            if (triangleDistanceSquared(a, b, c, p, q, r) <= radius * radius + 1e-10 ||
                triangleDistanceSquared(a, c, d, p, q, r) <= radius * radius + 1e-10) return true
        }
        return false
    }

    private fun inside(mesh: MeshData, point: Vec3): Boolean = mesh.positions.indices.all {
        mesh.normals[it].dot(point - mesh.positions[it]) <= 1e-9
    }

    private fun triangleDistanceSquared(a: Vec3, b: Vec3, c: Vec3, p: Vec3, q: Vec3, r: Vec3): Double = minOf(
        segmentTriangleDistanceSquared(a, b, p, q, r), segmentTriangleDistanceSquared(b, c, p, q, r),
        segmentTriangleDistanceSquared(c, a, p, q, r), segmentTriangleDistanceSquared(p, q, a, b, c),
        segmentTriangleDistanceSquared(q, r, a, b, c), segmentTriangleDistanceSquared(r, p, a, b, c),
    )

    private fun segmentTriangleDistanceSquared(start: Vec3, end: Vec3, a: Vec3, b: Vec3, c: Vec3): Double {
        val direction = end - start; val ab = b - a; val ac = c - a
        val cross = direction.cross(ac); val determinant = ab.dot(cross)
        if (abs(determinant) > 1e-12) {
            val relative = start - a
            val u = relative.dot(cross) / determinant
            val q = relative.cross(ab)
            val v = direction.dot(q) / determinant
            val t = ac.dot(q) / determinant
            if (u >= -1e-10 && v >= -1e-10 && u + v <= 1.0 + 1e-10 && t in 0.0..1.0) return 0.0
        }
        return minOf(pointTriangleDistanceSquared(start, a, b, c), pointTriangleDistanceSquared(end, a, b, c),
            segmentDistanceSquared(start, end, a, b), segmentDistanceSquared(start, end, b, c), segmentDistanceSquared(start, end, c, a))
    }

    private fun pointTriangleDistanceSquared(p: Vec3, a: Vec3, b: Vec3, c: Vec3): Double {
        val ab = b - a; val ac = c - a; val ap = p - a
        // Pure vertical motion and sphere sweeps collapse the centerline quad to segments.
        // Handle those before the barycentric edge tests, which can otherwise divide 0/0.
        if (ab.cross(ac).lengthSquared() <= 1e-24) return minOf(
            segmentDistanceSquared(p, p, a, b), segmentDistanceSquared(p, p, b, c), segmentDistanceSquared(p, p, c, a))
        val d1 = ab.dot(ap); val d2 = ac.dot(ap)
        if (d1 <= 0.0 && d2 <= 0.0) return ap.lengthSquared()
        val bp = p - b; val d3 = ab.dot(bp); val d4 = ac.dot(bp)
        if (d3 >= 0.0 && d4 <= d3) return bp.lengthSquared()
        val vc = d1 * d4 - d3 * d2
        if (vc <= 0.0 && d1 >= 0.0 && d3 <= 0.0) return (p - (a + ab * (d1 / (d1 - d3)))).lengthSquared()
        val cp = p - c; val d5 = ab.dot(cp); val d6 = ac.dot(cp)
        if (d6 >= 0.0 && d5 <= d6) return cp.lengthSquared()
        val vb = d5 * d2 - d1 * d6
        if (vb <= 0.0 && d2 >= 0.0 && d6 <= 0.0) return (p - (a + ac * (d2 / (d2 - d6)))).lengthSquared()
        val va = d3 * d6 - d5 * d4
        if (va <= 0.0 && d4 - d3 >= 0.0 && d5 - d6 >= 0.0) return (p - (b + (c - b) * ((d4 - d3) / ((d4 - d3) + (d5 - d6))))).lengthSquared()
        val sum = va + vb + vc
        if (abs(sum) < 1e-24) return minOf(segmentDistanceSquared(p, p, a, b), segmentDistanceSquared(p, p, b, c), segmentDistanceSquared(p, p, c, a))
        return (p - (a + ab * (vb / sum) + ac * (vc / sum))).lengthSquared()
    }

    private fun segmentDistanceSquared(p: Vec3, q: Vec3, a: Vec3, b: Vec3): Double {
        val first = q - p; val second = b - a; val relative = p - a
        val aa = first.lengthSquared(); val ee = second.lengthSquared(); val ff = second.dot(relative)
        var s: Double; var t: Double
        if (aa <= 1e-20 && ee <= 1e-20) return relative.lengthSquared()
        if (aa <= 1e-20) { s = 0.0; t = (ff / ee).coerceIn(0.0, 1.0) }
        else {
            val cc = first.dot(relative)
            if (ee <= 1e-20) { t = 0.0; s = (-cc / aa).coerceIn(0.0, 1.0) }
            else {
                val bb = first.dot(second); val denominator = aa * ee - bb * bb
                s = if (abs(denominator) > 1e-20) ((bb * ff - cc * ee) / denominator).coerceIn(0.0, 1.0) else 0.0
                t = (bb * s + ff) / ee
                if (t < 0.0) { t = 0.0; s = (-cc / aa).coerceIn(0.0, 1.0) }
                else if (t > 1.0) { t = 1.0; s = ((bb - cc) / aa).coerceIn(0.0, 1.0) }
            }
        }
        return (p + first * s - a - second * t).lengthSquared()
    }

    private fun hasVolume(vertices: List<Vec3>, epsilon: Double): Boolean {
        if (vertices.size < 4) return false
        val origin = vertices.first()
        val first = vertices.maxBy { (it - origin).lengthSquared() } - origin
        if (first.length() <= epsilon) return false
        val second = vertices.maxBy { first.cross(it - origin).lengthSquared() } - origin
        val perpendicular = first.cross(second)
        if (perpendicular.length() <= epsilon * first.length()) return false
        val unit = perpendicular * (1.0 / perpendicular.length())
        return vertices.any { abs((it - origin).dot(unit)) > epsilon }
    }

    private fun removeCollinear(face: MutableList<Int>, vertices: List<Vec3>, epsilon: Double) {
        var changed = true
        while (changed && face.size >= 3) {
            changed = false
            for (i in face.indices) {
                val before = vertices[face[(i + face.size - 1) % face.size]]
                val current = vertices[face[i]]
                val after = vertices[face[(i + 1) % face.size]]
                val a = current - before; val b = after - current
                if (a.cross(b).length() <= epsilon * max(a.length(), b.length())) {
                    face.removeAt(i); changed = true; break
                }
            }
        }
    }
}
