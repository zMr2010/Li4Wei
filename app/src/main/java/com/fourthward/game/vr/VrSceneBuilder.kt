package com.fourthward.game.vr

import com.fourthward.game.core.BoxSection
import com.fourthward.game.core.FourthwardGame
import com.fourthward.game.core.SliceFrame
import com.fourthward.game.core.SphereSection
import com.fourthward.game.core.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** CPU-only exact section authoring. Native consumes opaque sRGB xyzrgba triangle vertices. */
object VrSceneBuilder {
    fun build(game: FourthwardGame, showReference: Boolean): FloatArray {
        val sections = game.sections()
        val spheres = game.visibleSpheres()
        val frame = game.frame
        val head = game.trackedHead
        val color = game.level.goalColorArgb.toLong()
        val completed = game.completed
        val checkpoints = game.level.expedition?.checkpoints.orEmpty()
        val firstPending = checkpoints.firstOrNull { it.id !in game.completedCheckpointIds }?.id
        val markers = checkpoints.associate { checkpoint ->
            checkpoint.volume.id to BeaconStyle(
                argbTint(checkpoint.colorArgb.toLong()),
                checkpoint.id in game.completedCheckpointIds,
                game.level.expedition?.ordered != true || checkpoint.id == firstPending,
            )
        }
        val meshes = worldScene(sections, spheres, showReference, frame, head, color, completed, markers, game.goalReady)
        val output = FloatArray(meshes.sumOf { it.second.indices.size } * 7)
        var cursor = 0
        fun srgb(linear: Float): Float {
            val value = linear.coerceIn(0f, 1f)
            return if (value <= 0.0031308f) value * 12.92f else 1.055f * value.pow(1f / 2.4f) - 0.055f
        }
        for ((_, mesh) in meshes) for (index in mesh.indices) {
            val point = mesh.positions[index]
            val rgba = mesh.colors[index]
            output[cursor++] = point.x.toFloat()
            output[cursor++] = point.y.toFloat()
            output[cursor++] = point.z.toFloat()
            output[cursor++] = srgb(rgba[0])
            output[cursor++] = srgb(rgba[1])
            output[cursor++] = srgb(rgba[2])
            output[cursor++] = rgba[3]
        }
        return output
    }
}

private data class Tint(val r: Float, val g: Float, val b: Float) {
    fun color(brightness: Double = 1.0) = floatArrayOf((r * brightness).toFloat(), (g * brightness).toFloat(), (b * brightness).toFloat(), 1f)
    fun dimmed(factor: Float) = Tint(r * factor, g * factor, b * factor)
    fun mixed(other: Tint, amount: Float) = Tint(r + (other.r - r) * amount, g + (other.g - g) * amount, b + (other.b - b) * amount)
}

private data class BeaconStyle(val tint: Tint, val reached: Boolean, val available: Boolean)

// Linear-light tints for face shading; encoded to sRGB in the exported native vertex stream.
private val PAPER = Tint(0.830770f, 0.814847f, 0.760525f) // sRGB #EBE9E2
private val COOL_PAPER = Tint(0.558340f, 0.693872f, 0.679542f) // sRGB #C5D9D7
private val SPHERE = Tint(0.184475f, 0.571125f, 0.564712f) // sRGB #77C7C6
private val SIGNAL = Tint(0.854993f, 0.603827f, 0.194618f) // sRGB #EECC7A
private val GRAPHITE = Tint(0.025187f, 0.031896f, 0.033105f) // sRGB #2C3233
private val METAL = Tint(0.194618f, 0.223228f, 0.215861f) // sRGB #7A8280
private val INK = Tint(0.006995f, 0.009134f, 0.010330f) // sRGB #14181A
private val RULE = Tint(0.152926f, 0.177888f, 0.174647f) // sRGB #6D7574
private val GRID = Tint(0.038204f, 0.043735f, 0.043735f) // sRGB #373B3B
private val FLOOR = Tint(0.008568f, 0.010330f, 0.010330f) // sRGB #171A1A

private fun argbTint(argb: Long): Tint {
    fun channel(shift: Int): Float {
        val srgb = ((argb shr shift) and 0xff).toDouble() / 255.0
        return (if (srgb <= 0.04045) srgb / 12.92 else Math.pow((srgb + 0.055) / 1.055, 2.4)).toFloat()
    }
    return Tint(channel(16), channel(8), channel(0))
}

/** CPU scratch mesh, flattened to native triangle vertices by the builder. */
private class PaintedMesh {
    val positions = mutableListOf<Vec3>()
    val normals = mutableListOf<Vec3>()
    val indices = mutableListOf<Int>()
    val colors = mutableListOf<FloatArray>()


    fun face(vertices: List<Vec3>, normal: Vec3, tint: Tint, shaded: Boolean = false) {
        if (vertices.size < 3) return
        val first = positions.size
        val shade = if (shaded) .48 + .52 * max(0.0, normal.dot(Vec3(-.35, .82, .45))) else 1.0
        vertices.forEach { positions += it; normals += normal; colors += tint.color(shade) }
        for (index in 1 until vertices.lastIndex) indices.addAll(listOf(first, first + index, first + index + 1))
    }

    fun section(section: BoxSection, tint: Tint) {
        val first = positions.size
        section.mesh.positions.forEachIndexed { index, point ->
            val normal = section.mesh.normals[index]
            positions += point; normals += normal
            colors += tint.color(.48 + .52 * max(0.0, normal.dot(Vec3(-.35, .82, .45))))
        }
        indices.addAll(section.mesh.indices.map { it + first })
    }

    fun box(center: Vec3, half: Vec3, tint: Tint) {
        val x = Vec3(half.x, 0.0, 0.0); val y = Vec3(0.0, half.y, 0.0); val z = Vec3(0.0, 0.0, half.z)
        for (sign in listOf(-1.0, 1.0)) {
            fun side(offset: Vec3, a: Vec3, b: Vec3, normal: Vec3) {
                val corners = listOf(center + offset - a - b, center + offset + a - b,
                    center + offset + a + b, center + offset - a + b)
                face(if (sign > 0) corners else corners.reversed(), normal * sign, tint, shaded = true)
            }
            side(x * sign, y, z, Vec3(1.0, 0.0, 0.0))
            side(y * sign, z, x, Vec3(0.0, 1.0, 0.0))
            side(z * sign, x, y, Vec3(0.0, 0.0, 1.0))
        }
    }

    /** Eight-sided clipped-corner equipment housing. Dimensions are independent of a goal trigger. */
    fun housing(base: Vec3, width: Double, depth: Double, height: Double, tint: Tint) {
        val corner = min(width, depth) * .22
        val rim = listOf(Vec3(-width + corner, 0.0, -depth), Vec3(width - corner, 0.0, -depth),
            Vec3(width, 0.0, -depth + corner), Vec3(width, 0.0, depth - corner),
            Vec3(width - corner, 0.0, depth), Vec3(-width + corner, 0.0, depth),
            Vec3(-width, 0.0, depth - corner), Vec3(-width, 0.0, -depth + corner))
        val up = Vec3(0.0, height, 0.0)
        face(rim.map { base + it }, Vec3(0.0, -1.0, 0.0), tint, shaded = true)
        face(rim.map { base + it + up }.reversed(), Vec3(0.0, 1.0, 0.0), tint, shaded = true)
        for (index in rim.indices) {
            val a = rim[index]; val b = rim[(index + 1) % rim.size]
            val delta = b - a
            val normal = Vec3(delta.z, 0.0, -delta.x) * (1.0 / delta.length())
            face(listOf(base + a, base + a + up, base + b + up, base + b), normal, tint, shaded = true)
        }
    }

    fun plate(vertices: List<Vec3>, normal: Vec3, thickness: Double, tint: Tint) {
        val offset = normal * (thickness * .5)
        face(vertices.map { it + offset }, normal, tint, shaded = true)
        face(vertices.map { it - offset }.reversed(), normal * -1.0, tint, shaded = true)
        vertices.indices.forEach { index ->
            val a = vertices[index]; val b = vertices[(index + 1) % vertices.size]
            val outward = (b - a).cross(normal)
            face(listOf(a + offset, a - offset, b - offset, b + offset),
                outward * (1.0 / outward.length()), tint, shaded = true)
        }
    }

    fun line(start: Vec3, end: Vec3, width: Double, tint: Tint) {
        val delta = end - start
        if (delta.lengthSquared() < 1e-12) return
        val along = delta * (1.0 / delta.length())
        val axis = if (abs(along.y) < .9) Vec3(0.0, 1.0, 0.0) else Vec3(1.0, 0.0, 0.0)
        val rawSide = along.cross(axis)
        val side = rawSide * (.5 * width / rawSide.length())
        val up = along.cross(side)
        val corners = listOf(side + up, side - up, (side + up) * -1.0, up - side)
        for (index in 0..3) {
            val a = corners[index]; val b = corners[(index + 1) % 4]
            val n = a + b
            face(listOf(start + a, end + a, end + b, start + b), n * (1.0 / n.length()), tint)
        }
        face(corners.map { start + it }.reversed(), along * -1.0, tint)
        face(corners.map { end + it }, along, tint)
    }

    fun sphere(center: Vec3, radius: Double, tint: Tint) {
        val first = positions.size
        val rings = SceneMaterials.SPHERE_RINGS; val sectors = SceneMaterials.SPHERE_SECTORS
        val baseColor = tint.color()
        for (ring in 0..rings) {
            val latitude = PI * ring / rings
            for (sector in 0..sectors) {
                val longitude = 2 * PI * sector / sectors
                val normal = Vec3(sin(latitude) * cos(longitude), cos(latitude), sin(latitude) * sin(longitude))
                positions += (center + normal * radius)
                normals += normal
                colors += SceneMaterials.sphereLinear(baseColor, normal)
            }
        }
        for (ring in 0 until rings) for (sector in 0 until sectors) {
            val a = first + ring * (sectors + 1) + sector
            val b = a + sectors + 1
            if (ring > 0) indices.addAll(listOf(a, a + 1, b))
            if (ring < rings - 1) indices.addAll(listOf(a + 1, b + 1, b))
        }
    }
}


/** Mesh positions are already in the snapshotted affine slice's local(s,z,w) coordinates. */
private fun worldScene(
    sections: List<BoxSection>,
    spheres: List<SphereSection>,
    showReference: Boolean,
    frame: SliceFrame,
    trackedHead: Vec3,
    goalColorArgb: Long,
    completed: Boolean,
    markers: Map<String, BeaconStyle>,
    goalReady: Boolean,
): List<Pair<String, PaintedMesh>> {
    val solids = PaintedMesh()
    val edges = PaintedMesh()
    val roundSections = PaintedMesh()
    val floorReference = PaintedMesh()
    val planeReference = PaintedMesh()
    val goalAnnotation = PaintedMesh()
    val goalTint = argbTint(goalColorArgb).dimmed(if (goalReady) 1f else .22f)

    // An explicitly observer-owned ground reference, not another projected 4D object.
    addGroundReference(floorReference, frame, trackedHead)
    sections.forEach { section ->
        val cool = section.source.id in setOf("tesseract-secondary", "study-long")
        val customTint = section.source.colorArgb?.let { argbTint(it.toLong()) }
        val tint = customTint ?: if (cool) COOL_PAPER else PAPER
        solids.section(section, if (section.source.solid) tint.dimmed(.72f) else tint)
        if (section.source.solid) {
            addArchitecturalSurfaces(solids, section, tint, -frame.origin.z)
        } else {
            // Teaching volumes retain only their real section edges. The restrained rule
            // separates adjacent faces without turning every object into a yellow cage.
            section.edges.forEach { (start, end) ->
                edges.line(start, end, .0024, (customTint ?: if (cool) SPHERE else METAL).dimmed(.78f))
            }
        }
    }
    spheres.forEach { section ->
        // Hidden 4D spheres are absent from this list. No ghost, full sphere, or projected hull.
        val isGoal = section.source.id == "goal"
        val marker = markers[section.source.id]
        val tint = marker?.tint?.dimmed(if (marker.reached || marker.available) 1f else .25f)
            ?: if (isGoal) goalTint else if (section.source.id == "guide-light") SIGNAL else SPHERE
        roundSections.sphere(section.center, section.radius, tint)
        if (marker != null) addBeaconAnnotation(goalAnnotation, section, -frame.origin.z, tint, marker.reached)
        if (isGoal) addGoalAnnotation(goalAnnotation, section, -frame.origin.z, goalTint, completed)
    }
    if (showReference) addPlayerZWReference(planeReference, trackedHead, -frame.origin.z)
    return listOf(
        "GroundReference" to floorReference,
        "SolidSections" to solids,
        "TrueSectionEdges" to edges,
        "SphereSections" to roundSections,
        "PlayerZWReference" to planeReference,
        "VisibleGoalAnnotation" to goalAnnotation,
    )
}

/** Paint is clipped to each exact convex section face; no trim spans a missing 4D section. */
private fun addArchitecturalSurfaces(mesh: PaintedMesh, section: BoxSection, tint: Tint, floorY: Double) {
    val positions = section.mesh.positions
    val normals = section.mesh.normals
    var first = 0
    while (first < positions.size) {
        val normal = normals[first]
        var end = first + 1
        while (end < positions.size && (normals[end] - normal).lengthSquared() < 1e-16) end++
        addArchitecturalFace(mesh, positions.subList(first, end), normal, tint, floorY)
        first = end
    }
}

private fun addArchitecturalFace(mesh: PaintedMesh, polygon: List<Vec3>, normal: Vec3, tint: Tint, floorY: Double) {
    if (polygon.size < 3) return
    val center = polygon.fold(Vec3.ZERO) { sum, point -> sum + point } * (1.0 / polygon.size)
    val rawU = if (abs(normal.y) < .92) Vec3(0.0, 1.0, 0.0).cross(normal)
        else Vec3(0.0, 0.0, 1.0).cross(normal)
    val u = rawU * (1.0 / rawU.length())
    val v = normal.cross(u)
    fun coordinate(point: Vec3, axis: Vec3) = (point - center).dot(axis)
    val width = polygon.maxOf { coordinate(it, u) } - polygon.minOf { coordinate(it, u) }
    val height = polygon.maxOf { coordinate(it, v) } - polygon.minOf { coordinate(it, v) }
    // Sliver sections keep their exact plain face. Insetting avoids expanding any silhouette.
    if (min(width, height) < .16) return
    val inset = polygon.map { center + (it - center) * .955 }
    val left = inset.minOf { coordinate(it, u) }; val right = inset.maxOf { coordinate(it, u) }
    val low = inset.minOf { coordinate(it, v) }; val high = inset.maxOf { coordinate(it, v) }
    fun paint(points: List<Vec3>, color: Tint, offset: Double = .00045) {
        for (triangle in surfaceTriangles(points)) {
            mesh.face(triangle.map { it + normal * offset }, normal, color, shaded = true)
        }
    }
    fun rectangle(x0: Double, x1: Double, y0: Double, y1: Double, color: Tint, offset: Double = .00075) {
        if (x1 <= x0 || y1 <= y0) return
        var clipped = inset
        for ((axis, limit) in listOf(u to x1, u * -1.0 to -x0, v to y1, v * -1.0 to -y0)) {
            clipped = clipSurface(clipped, center, axis, limit)
        }
        paint(clipped, color, offset)
    }
    paint(inset, tint)
    if (abs(normal.y) >= .92) return // Roof and floor fields stay quiet.

    val floor = (floorY - center.y) / v.y
    val footLow = max(low, floor + .035 / v.y)
    val footHigh = min(high, floor + .26 / v.y)
    rectangle(left, right, footLow, footHigh, GRAPHITE)
    rectangle(left, right, footHigh, min(high, footHigh + .012 / v.y), METAL)
    // A quiet service joint sits in the ordinary eye-level view, even on very tall walls.
    rectangle(left, right, floor + 1.03 / v.y, floor + 1.043 / v.y, tint.dimmed(.72f))
    // At most three panels per face, regardless of wall size: bounded geometry on mobile VR.
    val panels = if (width > 3.2) 3 else if (width > 1.35) 2 else 1
    for (division in 1 until panels) {
        val x = left + (right - left) * division / panels
        rectangle(x - .008, x + .008, low, high, GRAPHITE)
        rectangle(x + .008, x + .012, low, high, PAPER.dimmed(.82f))
    }
    if (width > .55 && height > .55) {
        val margin = min(.10, width * .07)
        val top = min(high - min(.12, height * .09), floor + 1.47 / v.y)
        val tabWidth = min(.38, width * .20)
        rectangle(left + margin, left + margin + tabWidth, top - .045, top, SIGNAL.dimmed(.80f))
        // Three short technical ticks read as a manufactured surface, without fake text.
        for (tick in 0..2) {
            val x = right - margin - .025 - tick * .044
            rectangle(x - .018, x, top - .065, top - .018, GRAPHITE)
        }
        rectangle(left + margin, left + margin + tabWidth * .55, top - .074, top - .066, METAL)
    }
}

/** Sutherland–Hodgman clipping keeps every surface decal inside the original convex polygon. */
internal fun clipSurface(polygon: List<Vec3>, origin: Vec3, axis: Vec3, limit: Double): List<Vec3> {
    if (polygon.isEmpty()) return polygon
    val result = mutableListOf<Vec3>()
    var previous = polygon.last()
    var previousDistance = (previous - origin).dot(axis) - limit
    for (point in polygon) {
        val distance = (point - origin).dot(axis) - limit
        if ((distance <= 0.0) != (previousDistance <= 0.0)) {
            result += previous + (point - previous) * (previousDistance / (previousDistance - distance))
        }
        if (distance <= 0.0) result += point
        previous = point; previousDistance = distance
    }
    return compactSurface(result)
}

/** Tangent clips can emit a crossing and an existing endpoint at exactly the same position. */
private fun compactSurface(polygon: List<Vec3>): List<Vec3> {
    val result = mutableListOf<Vec3>()
    for (point in polygon) {
        if (result.isEmpty() || (point - result.last()).lengthSquared() > 1e-20) result += point
    }
    if (result.size > 1 && (result.first() - result.last()).lengthSquared() <= 1e-20) result.removeAt(result.lastIndex)
    return result
}

/** Only decorative paint uses this filtered fan; the core's exact section mesh stays intact. */
internal fun surfaceTriangles(polygon: List<Vec3>): List<List<Vec3>> {
    val points = compactSurface(polygon)
    if (points.size < 3) return emptyList()
    val result = mutableListOf<List<Vec3>>()
    for (index in 1 until points.lastIndex) {
        val a = points.first(); val b = points[index]; val c = points[index + 1]
        if ((b - a).cross(c - a).lengthSquared() > 1e-20) result += listOf(a, b, c)
    }
    return result
}

/** A small navigation glyph attached only to an intersecting checkpoint volume. */
private fun addBeaconAnnotation(mesh: PaintedMesh, section: SphereSection, floorY: Double, tint: Tint, reached: Boolean) {
    val base = Vec3(section.center.x, floorY + .008, section.center.z)
    val radius = section.radius.coerceIn(.10, .45) + .06
    mesh.housing(base, radius, radius, .045, GRAPHITE)
    mesh.housing(base + Vec3(0.0, .045, 0.0), radius * .96, radius * .96, .026, METAL)
    mesh.housing(base + Vec3(0.0, .071, 0.0), radius * .91, radius * .91, .014, PAPER)
    mesh.box(base + Vec3(0.0, .32, 0.0), Vec3(.024, .24, .025), GRAPHITE)
    mesh.box(base + Vec3(0.0, .34, .027), Vec3(.010, .21, .003), METAL)
    mesh.housing(base + Vec3(0.0, .54, 0.0), .064, .047, .19, GRAPHITE)
    mesh.box(base + Vec3(0.0, .635, .050), Vec3(.039, .069, .006), tint)
    mesh.box(base + Vec3(0.0, .635, -.050), Vec3(.039, .069, .006), tint)
    mesh.housing(base + Vec3(0.0, .73, 0.0), .071, .052, .016, METAL)
    val center = base + Vec3(0.0, .85, 0.0)
    val diamond = listOf(Vec3(0.0, -.083, 0.0), Vec3(.066, 0.0, 0.0),
        Vec3(0.0, .083, 0.0), Vec3(-.066, 0.0, 0.0)).map { center + it }
    mesh.plate(diamond, Vec3(0.0, 0.0, 1.0), .014, GRAPHITE)
    for (side in listOf(-1.0, 1.0)) {
        val symbol = diamond.map { center + (it - center) * .71 + Vec3(0.0, 0.0, side * .008) }
        mesh.face(if (side > 0) symbol else symbol.reversed(), Vec3(0.0, 0.0, side), if (reached) tint else PAPER)
        mesh.box(base + Vec3(0.0, .055, side * radius * .97), Vec3(radius * .36, .009, .004), tint)
        if (reached) {
            val check = center + Vec3(-.027, .002, side * .014)
            mesh.line(check, check + Vec3(.020, -.023, 0.0), .008, INK)
            mesh.line(check + Vec3(.020, -.023, 0.0), check + Vec3(.054, .021, 0.0), .008, INK)
        }
    }
}

/** World Z=0 floor; metre phase shifts with in-plane translation so locomotion is visible. */
private fun addGroundReference(mesh: PaintedMesh, frame: SliceFrame, head: Vec3) {
    val extent = 12.0
    val floorY = -frame.origin.z
    val left = head.x - extent; val right = head.x + extent
    val back = head.z - extent; val front = head.z + extent
    mesh.face(
        listOf(Vec3(left, floorY - .012, back), Vec3(left, floorY - .012, front),
            Vec3(right, floorY - .012, front), Vec3(right, floorY - .012, back)),
        Vec3(0.0, 1.0, 0.0), FLOOR,
    )
    val originS = frame.origin.dot(frame.right)
    val originW = frame.origin.w
    val metreS = floor(head.x + originS)
    val metreW = floor(head.z + originW)
    for (step in -11..11) {
        val x = metreS + step - originS
        val z = metreW + step - originW
        mesh.line(Vec3(x, floorY - .005, back), Vec3(x, floorY - .005, front), .0025, GRID)
        mesh.line(Vec3(left, floorY - .005, z), Vec3(right, floorY - .005, z), .0025, GRID)
    }
}

/** Parallel to ZW through the current player, indicated by quiet corners, not a central axis. */
private fun addPlayerZWReference(mesh: PaintedMesh, head: Vec3, floorY: Double) {
    val low = floorY + .015
    val high = max(low + 1.0, head.y + .45)
    val near = head.z + .45
    val far = head.z - 2.5
    val subdued = Tint(RULE.r * .35f, RULE.g * .35f, RULE.b * .35f)
    for (y in listOf(low, high)) for (z in listOf(far, near)) {
        val corner = Vec3(head.x, y, z)
        mesh.line(corner, corner + Vec3(0.0, if (y == low) .16 else -.16, 0.0), .0018, subdued)
        mesh.line(corner, corner + Vec3(0.0, 0.0, if (z == far) .25 else -.25), .0018, subdued)
    }
}

/** A navigational flag annotation at an actual visible goal section, never an off-slice ghost. */
private fun addGoalAnnotation(mesh: PaintedMesh, goal: SphereSection, floorY: Double, tint: Tint, completed: Boolean) {
    val base = Vec3(goal.center.x, floorY + .008, goal.center.z)
    val radius = goal.radius.coerceIn(.12, .45) + .07
    mesh.housing(base, radius, radius, .040, GRAPHITE)
    mesh.housing(base + Vec3(0.0, .040, 0.0), radius * .96, radius * .96, .027, METAL)
    mesh.housing(base + Vec3(0.0, .067, 0.0), radius * .91, radius * .91, .014, PAPER)
    mesh.housing(base + Vec3(0.0, .081, 0.0), .045, .045, .045, GRAPHITE)
    mesh.housing(base + Vec3(0.0, .126, 0.0), .013, .013, 1.04, METAL)
    mesh.housing(base + Vec3(0.0, 1.166, 0.0), .018, .018, .022, PAPER)
    for (height in listOf(.87, 1.10)) {
        mesh.box(base + Vec3(.006, height, 0.0), Vec3(.025, .014, .022), GRAPHITE)
        mesh.box(base + Vec3(.006, height, .024), Vec3(.008, .006, .003), PAPER)
        mesh.box(base + Vec3(.006, height, -.024), Vec3(.008, .006, .003), PAPER)
    }
    val banner = base + Vec3(.018, .84, 0.0)
    fun at(x: Double, y: Double, z: Double = 0.0) = banner + Vec3(x, y, z)
    val outline = listOf(at(0.0, 0.0), at(.26, 0.0), at(.32, .06), at(.32, .27), at(.29, .30), at(0.0, .30))
    mesh.plate(outline, Vec3(0.0, 0.0, 1.0), .014, GRAPHITE)
    for (side in listOf(-1.0, 1.0)) {
        val normal = Vec3(0.0, 0.0, side)
        fun decal(points: List<Vec3>, color: Tint) = mesh.face(if (side > 0) points else points.reversed(), normal, color, shaded = true)
        val z = side * .0076
        decal(listOf(at(.018, .019, z), at(.250, .019, z), at(.301, .070, z),
            at(.301, .261, z), at(.279, .282, z), at(.018, .282, z)), PAPER)
        val graphicZ = side * .0082
        decal(listOf(at(.026, .025, graphicZ), at(.061, .025, graphicZ), at(.061, .276, graphicZ), at(.026, .276, graphicZ)), tint)
        decal(listOf(at(.075, .044, graphicZ), at(.235, .044, graphicZ), at(.235, .057, graphicZ), at(.075, .057, graphicZ)), GRAPHITE)
        decal(listOf(at(.256, .081, graphicZ), at(.286, .112, graphicZ), at(.286, .262, graphicZ), at(.256, .262, graphicZ)), tint.dimmed(.78f))
        // Compact destination diamond: physical inset layers remain legible from either side.
        val symbol = listOf(at(.154, .093, graphicZ), at(.205, .161, graphicZ),
            at(.154, .229, graphicZ), at(.103, .161, graphicZ))
        decal(symbol, GRAPHITE)
        decal(listOf(at(.154, .127, side * .0088), at(.179, .161, side * .0088),
            at(.154, .195, side * .0088), at(.129, .161, side * .0088)), tint)
        mesh.box(base + Vec3(0.0, .052, side * radius * .97), Vec3(radius * .45, .008, .005), tint)
    }
    if (completed) {
        val check = base + Vec3(.09, 1.24, 0.0)
        mesh.line(check, check + Vec3(.06, -.06, 0.0), .014, tint)
        mesh.line(check + Vec3(.06, -.06, 0.0), check + Vec3(.18, .08, 0.0), .014, tint)
    }
}
