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
}

private data class BeaconStyle(val tint: Tint, val reached: Boolean, val available: Boolean)

// Linear-light tints for face shading; encoded to sRGB in the exported native vertex stream.
private val PAPER = Tint(0.887923f, 0.887923f, 0.871367f) // sRGB #F2F2F0
private val COOL_PAPER = Tint(0.558340f, 0.693872f, 0.679542f) // sRGB #C5D9D7
private val SPHERE = Tint(0.184475f, 0.571125f, 0.564712f) // sRGB #77C7C6
private val SIGNAL = Tint(1.000000f, 0.955973f, 0.000000f) // sRGB #FFFA00
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
        val rings = 12; val sectors = 24
        for (ring in 0..rings) {
            val latitude = PI * ring / rings
            for (sector in 0..sectors) {
                val longitude = 2 * PI * sector / sectors
                val normal = Vec3(sin(latitude) * cos(longitude), cos(latitude), sin(latitude) * sin(longitude))
                positions += (center + normal * radius)
                normals += normal
                colors += tint.color(.38 + .62 * max(0.0, normal.dot(Vec3(-.35, .82, .45))))
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
        solids.section(section, customTint ?: if (cool) COOL_PAPER else PAPER)
        // The core returns actual convex-section edges, excluding face triangulation diagonals.
        section.edges.forEach { (start, end) ->
            edges.line(start, end, .004, customTint ?: if (cool) SPHERE else SIGNAL)
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

/** A small navigation glyph attached only to an intersecting checkpoint volume. */
private fun addBeaconAnnotation(mesh: PaintedMesh, section: SphereSection, floorY: Double, tint: Tint, reached: Boolean) {
    val base = Vec3(section.center.x, floorY + .04, section.center.z)
    val radius = section.radius.coerceIn(.10, .25)
    val top = base + Vec3(0.0, .82, 0.0)
    // Open brackets are readable from either side; a lit diamond records the visit.
    for (side in listOf(-1.0, 1.0)) {
        val foot = base + Vec3(radius * side, 0.0, 0.0)
        val shoulder = foot + Vec3(0.0, .74, 0.0)
        mesh.line(foot, shoulder, .012, tint)
        mesh.line(shoulder, top + Vec3(radius * .38 * side, 0.0, 0.0), .012, tint)
    }
    val center = top + Vec3(0.0, .13, 0.0)
    val diamond = listOf(Vec3(-.08, 0.0, 0.0), Vec3(0.0, .10, 0.0),
        Vec3(.08, 0.0, 0.0), Vec3(0.0, -.10, 0.0)).map { center + it }
    diamond.indices.forEach { i -> mesh.line(diamond[i], diamond[(i + 1) % diamond.size], .012, tint) }
    if (reached) {
        mesh.face(diamond, Vec3(0.0, 0.0, 1.0), tint)
        mesh.face(diamond.reversed(), Vec3(0.0, 0.0, -1.0), tint)
        mesh.line(base + Vec3(-radius, 0.0, 0.0), base + Vec3(radius, 0.0, 0.0), .025, tint)
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
    val base = Vec3(goal.center.x, floorY + .025, goal.center.z)
    val poleTop = base + Vec3(0.0, 1.05, 0.0)
    val radius = goal.radius.coerceIn(.12, .28)
    val corners = listOf(Vec3(-radius, 0.0, -radius), Vec3(radius, 0.0, -radius),
        Vec3(radius, 0.0, radius), Vec3(-radius, 0.0, radius))
    corners.indices.forEach { index ->
        mesh.line(base + corners[index], base + corners[(index + 1) % corners.size], .012, tint)
    }
    mesh.line(base, poleTop, .014, PAPER)
    mesh.face(listOf(poleTop, poleTop + Vec3(.30, -.11, 0.0), poleTop + Vec3(0.0, -.23, 0.0)),
        Vec3(0.0, 0.0, 1.0), tint)
    if (completed) {
        val check = poleTop + Vec3(.09, .12, 0.0)
        mesh.line(check, check + Vec3(.06, -.06, 0.0), .014, tint)
        mesh.line(check + Vec3(.06, -.06, 0.0), check + Vec3(.18, .08, 0.0), .014, tint)
    }
}
