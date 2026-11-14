package com.fourthward.game.vr

import com.fourthward.game.core.LessonBox
import com.fourthward.game.core.LessonSnapshot
import com.fourthward.game.core.BoxSection
import com.fourthward.game.core.GeometryExhibits
import com.fourthward.game.core.GeometryTopic
import com.fourthward.game.core.MotionStudies
import com.fourthward.game.core.SliceFrame
import com.fourthward.game.core.SliceGeometry
import com.fourthward.game.core.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** Shared lesson-anchor-local placement for models, labels and the control dock. */
object GeometryLessonLayout {
    val LEFT_CENTER = Vec3(-.32, 0.0, 0.0)
    val RIGHT_CENTER = Vec3(.32, 0.0, 0.0)
    const val TITLE_HEIGHT = .28
    const val CONTROL_HEIGHT = -.38
}

/**
 * The original lessons use small 2D-in-3D teaching models: wire cuboids and their planar
 * sections. Geometry lessons pair a 2D-in-3D analogue with actual 3D sections of 4D exhibits.
 * Every model is placed under one fixed anchor so the observer can walk around it.
 */
object LessonSceneBuilder {
    const val MODEL_SCALE = 0.68
    private const val DISPLAY_YAW = -PI / 9.0
    private const val ANALOGY_DISPLAY_YAW = 35.0 * PI / 180.0
    internal const val GEOMETRY_DISPLAY_YAW = -PI / 4.0
    private val displayCenter = Vec3(0.075, -0.10, 0.20)
    internal const val GEOMETRY_SCALE = 0.18
    internal const val MOTION_SCALE = 0.13

    fun build(snapshot: LessonSnapshot, anchorPose: VrPose): FloatArray {
        if (snapshot.motionTopic != null) return buildMotion(snapshot, anchorPose)
        if (snapshot.geometryTopic != null) return buildGeometry(snapshot, anchorPose)
        val mesh = LessonMesh { point ->
            val p = point - displayCenter
            val x = cos(DISPLAY_YAW) * p.x + sin(DISPLAY_YAW) * p.z
            val z = -sin(DISPLAY_YAW) * p.x + cos(DISPLAY_YAW) * p.z
            anchorPose.position + anchorPose.right * (x * MODEL_SCALE) +
                anchorPose.up * (p.y * MODEL_SCALE) + anchorPose.normal * (z * MODEL_SCALE)
        }
        val up = Vec3(0.0, 1.0, 0.0)
        val right = snapshot.planeRight
        val normal = snapshot.planeNormal

        // A quiet horizontal plinth supplies depth references while the observer walks around.
        mesh.plinth(Vec3(.075, -.665, .225), Vec3(.99, .025, .565), BASE, BORDER, PLINTH_METAL)
        for (i in 0..8) {
            val x = -.85 + i * .23
            mesh.line(Vec3(x, -.638, -.29), Vec3(x, -.638, .74), .0022, GRID)
        }
        for (i in 0..4) {
            val z = -.23 + i * .23
            mesh.line(Vec3(-.86, -.638, z), Vec3(1.01, -.638, z), .0022, GRID)
        }
        mesh.line(Vec3(-.915, -.633, -.34), Vec3(1.065, -.633, -.34), .0045, BORDER)
        mesh.line(Vec3(-.915, -.633, .79), Vec3(1.065, -.633, .79), .0045, BORDER)
        mesh.plinthMarkers(Vec3(.075, -.636, .225), Vec3(.99, 0.0, .565), .003, PLINTH_METAL, SIGNAL)

        // The viewport clips an infinite affine plane to a fixed demonstration volume.
        // It does not slide with the character when the character moves within that plane.
        val range = LessonSectionGeometry.horizontalRange(snapshot.planeOrigin, right,
            -.90, 1.05, -.30, .75)
        if (range != null) {
            fun point(t: Double, y: Double) = snapshot.planeOrigin + right * t +
                up * (y - snapshot.planeOrigin.y)
            val low = -.56
            val high = .40
            val a = point(range.first, low); val b = point(range.second, low)
            val c = point(range.second, high); val d = point(range.first, high)
            mesh.loop(listOf(a, b, c, d), .006, PLANE_FRAME)
            for (i in 1..5) {
                val y = low + (high - low) * i / 6.0
                mesh.line(point(range.first, y), point(range.second, y), .0025, PLANE_GRID)
            }
            // World-phased grid coordinates avoid implying that walking translates the plane.
            val fixedCenter = Vec3(.075, -.10, .225)
            val projectedCenter = fixedCenter - normal * (fixedCenter - snapshot.planeOrigin).dot(normal)
            for (i in -8..8) {
                val center = projectedCenter + right * (i * .18)
                val t = (center - snapshot.planeOrigin).dot(right)
                if (t > range.first && t < range.second)
                    mesh.line(point(t, low), point(t, high), .0025, PLANE_GRID)
            }
        }

        // Wire cuboids keep the true interior section visible from either side. Their corner
        // braces have shaded solid faces, so their finite third-dimensional thickness is legible.
        for (wall in snapshot.walls) {
            mesh.wireBox(wall.center, wall.halfExtents, .008, WALL)
            val section = LessonSectionGeometry.section(wall, snapshot.planeOrigin, snapshot.angleRadians)
            if (section.isNotEmpty()) {
                mesh.face(section, SECTION)
                mesh.loop(section, .008, SIGNAL)
            }
        }

        // These dotted 3D trails belong to the external teaching diagram, not the character's
        // field of view. They make the out-of-plane detour inspectable after playback stops.
        snapshot.path.zipWithNext().forEach { (a, b) -> mesh.dashed(a, b, .005, TRAIL) }
        snapshot.path.dropLast(1).forEach { mesh.octahedron(it, .012, TRAIL) }
        if (snapshot.path.size >= 2) {
            val end = snapshot.path.last()
            val previous = snapshot.path[snapshot.path.lastIndex - 1]
            if ((end - previous).lengthSquared() > .0025)
                mesh.arrowHead(end, end - previous, .037, if (snapshot.blocked) BLOCKED else CHARACTER)
        }

        // Full target is visible to the external observer as a dim wire marker. Its visible
        // circular section shrinks continuously and vanishes when this plane misses it.
        mesh.wireOctahedron(snapshot.goal, .061, if (snapshot.goalReached) SUCCESS else GOAL_VOLUME)
        val targetRadius = .045
        val distance = (snapshot.goal - snapshot.planeOrigin).dot(normal)
        if (abs(distance) <= targetRadius) {
            val radius = kotlin.math.sqrt(max(0.0, targetRadius * targetRadius - distance * distance))
            val center = snapshot.goal - normal * distance
            mesh.disc(center, right, up, radius, if (snapshot.goalReached) SUCCESS else GOAL)
            mesh.ring(center, right, up, radius + .009, .004, if (snapshot.goalReached) SUCCESS else GOAL)
        }

        // A flat person, with all silhouette vertices on the observed plane. Moving the viewer
        // edge-on makes the character edge-on as it should; no billboard follows the VR camera.
        val actor = snapshot.character
        fun person(x: Double, y: Double) = actor + right * x + up * y
        val actorColor = if (snapshot.blocked) BLOCKED else CHARACTER
        mesh.disc(person(0.0, .068), right, up, .026, actorColor)
        mesh.face(listOf(person(-.027, .027), person(.027, .027), person(.020, -.035), person(-.020, -.035)), actorColor)
        mesh.flatLine(person(-.022, .010), person(-.055, -.018), .012, normal, actorColor)
        mesh.flatLine(person(.022, .010), person(.055, -.018), .012, normal, actorColor)
        mesh.flatLine(person(-.012, -.030), person(-.035, -.084), .014, normal, actorColor)
        mesh.flatLine(person(.012, -.030), person(.035, -.084), .014, normal, actorColor)
        if (snapshot.blocked) {
            val center = actor + right * .065
            mesh.flatLine(center + right * -.012 + up * -.016, center + right * .012 + up * .016, .004, normal, BLOCKED)
            mesh.flatLine(center + right * -.012 + up * .016, center + right * .012 + up * -.016, .004, normal, BLOCKED)
        }

        if (snapshot.angleRadians > .025) {
            val center = actor + up * .33
            mesh.dashed(actor + up * -.12, actor + up * .40, .0028, PIVOT)
            val count = max(2, (snapshot.angleRadians / (PI / 36)).toInt())
            var previous = center + Vec3(.14, 0.0, 0.0)
            for (i in 1..count) {
                val angle = snapshot.angleRadians * i / count
                val point = center + Vec3(cos(angle) * .14, 0.0, sin(angle) * .14)
                mesh.line(previous, point, .0035, PIVOT)
                if (i == count) mesh.arrowHead(point, point - previous, .020, PIVOT)
                previous = point
            }
        }
        return mesh.result()
    }

    /** The left full objects belong to an explicitly lower-dimensional analogy. The right
     * has only true 4D/3D sections, never a full-volume ghost when the slice misses. */
    private fun buildGeometry(snapshot: LessonSnapshot, anchor: VrPose): FloatArray {
        val table = geometryTable(anchor, GeometryLessonLayout.LEFT_CENTER) +
            geometryTable(anchor, GeometryLessonLayout.RIGHT_CENTER)
        val leftAnchor = anchor.copy(position = atAnchor(anchor, GeometryLessonLayout.LEFT_CENTER))
        val rightAnchor = anchor.copy(position = atAnchor(anchor, GeometryLessonLayout.RIGHT_CENTER))
        return table + analogyVertices(snapshot, leftAnchor) + geometryExhibitVertices(snapshot, rightAnchor)
    }

    private fun buildMotion(snapshot: LessonSnapshot, anchor: VrPose): FloatArray {
        val table = geometryTable(anchor, GeometryLessonLayout.LEFT_CENTER) +
            geometryTable(anchor, GeometryLessonLayout.RIGHT_CENTER)
        val leftAnchor = anchor.copy(position = atAnchor(anchor, GeometryLessonLayout.LEFT_CENTER))
        val rightAnchor = anchor.copy(position = atAnchor(anchor, GeometryLessonLayout.RIGHT_CENTER))
        return table + motionAnalogyVertices(snapshot, leftAnchor) + motionExhibitVertices(snapshot, rightAnchor)
    }

    /** The plane and actor stay still while a real 3D source crosses it. Both displays
     * consume the same motion time; no scale is fitted to the current section. */
    internal fun motionAnalogyVertices(snapshot: LessonSnapshot, anchor: VrPose): FloatArray {
        val topic = snapshot.motionTopic ?: return FloatArray(0)
        val mesh = LessonMesh { point -> atAnchor(anchor,
            displayPoint(point - Vec3(0.0, 1.3, 0.0), ANALOGY_DISPLAY_YAW) * MOTION_SCALE) }
        val angle = snapshot.angleRadians
        val right = GeometryLessonAnalogy.right(angle)
        val normal = GeometryLessonAnalogy.normal(angle)
        val up = Vec3(0.0, 1.0, 0.0)
        fun point(s: Double, height: Double) = right * s + up * height
        mesh.loop(listOf(point(-1.25, .1), point(1.25, .1), point(1.25, 2.5), point(-1.25, 2.5)), .012, PLANE_FRAME)
        for (i in 1..5) {
            val height = .1 + i * .4
            mesh.line(point(-1.25, height), point(1.25, height), .003, PLANE_GRID)
        }
        for (i in -2..2) mesh.line(point(i * .4, .1), point(i * .4, 2.5), .003, PLANE_GRID)
        MotionLessonAnalogy.sphere(topic, snapshot.motionTimeSeconds)?.let { sphere ->
            mesh.wireSphere(sphere.center, sphere.radius, ANALOGY_VOLUME)
            GeometryLessonAnalogy.circle(sphere, angle)?.let { circle ->
                mesh.disc(circle.center, right, up, circle.radius, SECTION)
                mesh.ring(circle.center, right, up, circle.radius, .018, SIGNAL)
            }
        }
        for (box in MotionStudies.world(topic, snapshot.motionTimeSeconds).boxes) {
            MotionLessonAnalogy.edges(box).forEach { (a, b) -> mesh.line(a, b, .012, ANALOGY_VOLUME) }
            val polygon = MotionLessonAnalogy.polygon(box, angle)
            if (polygon.isNotEmpty()) {
                val longBox = box.id == "motion-long-box"
                mesh.face(polygon, if (longBox) EXHIBIT_TEAL else SECTION)
                mesh.loop(polygon, .018, if (longBox) EXHIBIT_TEAL_EDGE else SIGNAL)
            }
        }
        val actor = point(-1.0, .38)
        fun person(x: Double, y: Double) = actor + right * x + up * y
        mesh.disc(person(0.0, .11), right, up, .04, CHARACTER)
        mesh.face(listOf(person(-.04, .05), person(.04, .05), person(.03, -.05), person(-.03, -.05)), CHARACTER)
        mesh.flatLine(person(-.03, .02), person(-.085, -.02), .02, normal, CHARACTER)
        mesh.flatLine(person(.03, .02), person(.085, -.02), .02, normal, CHARACTER)
        mesh.flatLine(person(-.02, -.045), person(-.05, -.13), .02, normal, CHARACTER)
        mesh.flatLine(person(.02, -.045), person(.05, -.13), .02, normal, CHARACTER)
        return mesh.result()
    }

    /** This side only draws the actual 4D intersection. A missing section draws nothing. */
    internal fun motionExhibitVertices(snapshot: LessonSnapshot, anchor: VrPose): FloatArray {
        val topic = snapshot.motionTopic ?: return FloatArray(0)
        val mesh = LessonMesh { point -> atAnchor(anchor,
            displayPoint(point - MotionStudies.displayCenter, GEOMETRY_DISPLAY_YAW) * MOTION_SCALE) }
        val world = MotionStudies.world(topic, snapshot.motionTimeSeconds)
        val frame = SliceFrame(snapshot.angleRadians)
        for (box in world.boxes) {
            val section = SliceGeometry.sliceBox(box, frame) ?: continue
            val longBox = box.id == "motion-long-box"
            mesh.section(section, if (longBox) EXHIBIT_TEAL else EXHIBIT_PAPER)
            section.edges.forEach { (a, b) -> mesh.line(a, b, .012, if (longBox) EXHIBIT_TEAL_EDGE else SIGNAL) }
        }
        for (sphere in world.spheres) {
            val section = SliceGeometry.sliceSphere(sphere, frame) ?: continue
            mesh.sphere(section.center, section.radius, EXHIBIT_TEAL)
        }
        return mesh.result()
    }

    private fun geometryTable(anchor: VrPose, offset: Vec3): FloatArray {
        val table = LessonMesh { point -> atAnchor(anchor, point + offset) }
        // Both plinths sit below the fixed observer's feet, including the low stacked-box
        // observer. Display/model coordinates and label anchors remain unchanged.
        table.plinth(Vec3(0.0, -.27, 0.0), Vec3(.24, .012, .18), BASE, BORDER, PLINTH_METAL)
        for (i in -3..3) {
            val position = i * .06
            table.line(Vec3(position, -.257, -.15), Vec3(position, -.257, .15), .001, GRID)
        }
        for (i in -2..2) {
            val position = i * .06
            table.line(Vec3(-.21, -.257, position), Vec3(.21, -.257, position), .001, GRID)
        }
        table.loop(listOf(Vec3(-.24, -.256, -.18), Vec3(.24, -.256, -.18),
            Vec3(.24, -.256, .18), Vec3(-.24, -.256, .18)), .002, BORDER)
        table.plinthMarkers(Vec3(0.0, -.255, 0.0), Vec3(.24, 0.0, .18), .0015, PLINTH_METAL, SIGNAL)
        return table.result()
    }

    /** Real 3D source volumes and their true planar intersections, synchronized with the
     * right-hand 4D section through snapshot.angleRadians. The tiny observer is flat. */
    internal fun analogyVertices(snapshot: LessonSnapshot, anchor: VrPose): FloatArray {
        val topic = snapshot.geometryTopic ?: return FloatArray(0)
        val center = when (topic) {
            GeometryTopic.HYPERSPHERE -> Vec3(.7, 1.35, .7)
            GeometryTopic.TESSERACT -> Vec3(0.0, 1.25, 0.0)
            GeometryTopic.AMBIGUOUS_SECTION -> Vec3(0.0, 1.45, 0.0)
        }
        // The left column has its own constant presentation yaw so the planar circle and
        // rectangle remain legible at the key 0/45/90 degree states. The actual plane angle
        // still comes solely from snapshot.angleRadians, exactly as on the right.
        val mesh = LessonMesh { point -> atAnchor(anchor,
            displayPoint(point - center, ANALOGY_DISPLAY_YAW) * GEOMETRY_SCALE) }
        val angle = snapshot.angleRadians
        val right = GeometryLessonAnalogy.right(angle)
        val normal = GeometryLessonAnalogy.normal(angle)
        val up = Vec3(0.0, 1.0, 0.0)
        val bounds = when (topic) {
            GeometryTopic.HYPERSPHERE -> listOf(-.25, 1.65, -.25, 1.65, .20, 2.35)
            GeometryTopic.TESSERACT -> listOf(-.90, .90, -.90, .90, .30, 2.15)
            GeometryTopic.AMBIGUOUS_SECTION -> listOf(-.95, .95, -1.25, 1.25, .05, 2.70)
        }
        val range = LessonSectionGeometry.horizontalRange(Vec3.ZERO, right, bounds[0], bounds[1], bounds[2], bounds[3])
        if (range != null) {
            fun point(s: Double, y: Double) = right * s + up * y
            mesh.loop(listOf(point(range.first, bounds[4]), point(range.second, bounds[4]),
                point(range.second, bounds[5]), point(range.first, bounds[5])), .010, PLANE_FRAME)
            for (i in 1..5) {
                val y = bounds[4] + (bounds[5] - bounds[4]) * i / 6
                mesh.line(point(range.first, y), point(range.second, y), .003, PLANE_GRID)
            }
            for (i in -6..8) {
                val s = i * .25
                if (s > range.first && s < range.second)
                    mesh.line(point(s, bounds[4]), point(s, bounds[5]), .003, PLANE_GRID)
            }
        }

        for (sphere in GeometryLessonAnalogy.spheres(topic)) {
            mesh.wireSphere(sphere.center, sphere.radius, ANALOGY_VOLUME)
            val circle = GeometryLessonAnalogy.circle(sphere, angle) ?: continue
            mesh.disc(circle.center, right, up, circle.radius, SECTION)
            mesh.ring(circle.center, right, up, circle.radius, .018, SIGNAL)
        }
        for (box in GeometryLessonAnalogy.boxes(topic)) {
            val upper = box.id == "study-long"
            mesh.wireBox(box.center, box.halfExtents, .010, ANALOGY_VOLUME)
            val section = GeometryLessonAnalogy.rectangle(box, angle)
            if (section.isEmpty()) continue
            mesh.face(section, if (upper) EXHIBIT_TEAL else EXHIBIT_PAPER)
            mesh.loop(section, .018, if (upper) EXHIBIT_TEAL_EDGE else SIGNAL)
        }

        val actor = GeometryLessonAnalogy.observer(topic)
        fun person(x: Double, y: Double) = actor + right * x + up * y
        mesh.disc(person(0.0, .09), right, up, .034, CHARACTER)
        mesh.face(listOf(person(-.035, .038), person(.035, .038), person(.026, -.045), person(-.026, -.045)), CHARACTER)
        mesh.flatLine(person(-.028, .018), person(-.068, -.02), .016, normal, CHARACTER)
        mesh.flatLine(person(.028, .018), person(.068, -.02), .016, normal, CHARACTER)
        mesh.flatLine(person(-.016, -.04), person(-.043, -.11), .018, normal, CHARACTER)
        mesh.flatLine(person(.016, -.04), person(.043, -.11), .018, normal, CHARACTER)
        return mesh.result()
    }

    /** Kept separate from the plinth so an absent section really has no exhibit geometry. */
    internal fun geometryExhibitVertices(snapshot: LessonSnapshot, anchor: VrPose): FloatArray {
        val topic = snapshot.geometryTopic ?: return FloatArray(0)
        val center = geometryDisplayCenter(topic)
        val mesh = LessonMesh { point ->
            // A fixed 3D display orientation exposes depth on the miniature. It is applied
            // after slicing and never adds another angle to the four-dimensional frame.
            val p = point - center
            atAnchor(anchor, displayPoint(p, GEOMETRY_DISPLAY_YAW) * GEOMETRY_SCALE)
        }
        val world = GeometryExhibits.world(topic)
        val frame = SliceFrame(snapshot.angleRadians)
        for (box in world.boxes) {
            val section = SliceGeometry.sliceBox(box, frame) ?: continue
            val upper = box.id == "study-long"
            mesh.section(section, if (upper) EXHIBIT_TEAL else EXHIBIT_PAPER)
            section.edges.forEach { (a, b) -> mesh.line(a, b, .012, if (upper) EXHIBIT_TEAL_EDGE else SIGNAL) }
        }
        for (sphere in world.spheres) {
            val section = SliceGeometry.sliceSphere(sphere, frame) ?: continue
            mesh.sphere(section.center, section.radius, EXHIBIT_TEAL)
        }
        return mesh.result()
    }

    /** Fixed per exhibit, never fitted to the changing mesh bounds: size changes stay real. */
    internal fun geometryDisplayCenter(topic: GeometryTopic): Vec3 = when (topic) {
        GeometryTopic.HYPERSPHERE -> Vec3(.85, 1.35, -2.2)
        GeometryTopic.TESSERACT -> Vec3(0.0, 1.25, -2.3)
        GeometryTopic.AMBIGUOUS_SECTION -> Vec3(0.0, 1.45, -2.6)
    }

    private fun atAnchor(anchor: VrPose, point: Vec3): Vec3 = anchor.position +
        anchor.right * point.x + anchor.up * point.y + anchor.normal * point.z

    private fun displayPoint(point: Vec3, yaw: Double = DISPLAY_YAW) = Vec3(
        cos(yaw) * point.x + sin(yaw) * point.z, point.y,
        -sin(yaw) * point.x + cos(yaw) * point.z)

    private val BASE = LessonColor(0x181E21)
    private val GRID = LessonColor(0x303A3E)
    private val BORDER = LessonColor(0x607073)
    private val PLINTH_METAL = LessonColor(0xAFB9B6)
    private val PLANE_FRAME = LessonColor(0x6D9BA4)
    private val PLANE_GRID = LessonColor(0x304F58)
    private val WALL = LessonColor(0xABB2AD)
    private val SECTION = LessonColor(0xBEA751)
    private val SIGNAL = LessonColor(0xFFE07A)
    private val CHARACTER = LessonColor(0x67E8DB)
    private val TRAIL = LessonColor(0x376F6A)
    private val BLOCKED = LessonColor(0xF18A78)
    private val GOAL_VOLUME = LessonColor(0x857149)
    private val GOAL = LessonColor(0xFFCD63)
    private val SUCCESS = LessonColor(0xA9E688)
    private val PIVOT = LessonColor(0xAFA66C)
    private val EXHIBIT_PAPER = LessonColor(0xF2F2E8)
    private val EXHIBIT_TEAL = LessonColor(0x77C7C6)
    private val EXHIBIT_TEAL_EDGE = LessonColor(0xA4ECE5)
    private val ANALOGY_VOLUME = LessonColor(0x748A91)
}

/** Exact axis-aligned cuboid/vertical-plane intersection used by the lesson diagram. */
internal object LessonSectionGeometry {
    fun section(box: LessonBox, origin: Vec3, angle: Double): List<Vec3> {
        val right = Vec3(cos(angle), 0.0, sin(angle))
        val range = horizontalRange(origin, right, box.center.x - box.halfExtents.x,
            box.center.x + box.halfExtents.x, box.center.z - box.halfExtents.z,
            box.center.z + box.halfExtents.z) ?: return emptyList()
        if (range.second - range.first <= 1e-9) return emptyList()
        val low = box.center.y - box.halfExtents.y
        val high = box.center.y + box.halfExtents.y
        fun p(t: Double, y: Double) = origin + right * t + Vec3(0.0, y - origin.y, 0.0)
        return listOf(p(range.first, low), p(range.second, low), p(range.second, high), p(range.first, high))
    }

    fun horizontalRange(origin: Vec3, direction: Vec3, minX: Double, maxX: Double,
        minZ: Double, maxZ: Double): Pair<Double, Double>? {
        var low = Double.NEGATIVE_INFINITY
        var high = Double.POSITIVE_INFINITY
        for ((position, delta, lower, upper) in listOf(
            listOf(origin.x, direction.x, minX, maxX), listOf(origin.z, direction.z, minZ, maxZ))) {
            if (abs(delta) < 1e-10) {
                if (position < lower - 1e-9 || position > upper + 1e-9) return null
            } else {
                val a = (lower - position) / delta
                val b = (upper - position) / delta
                low = max(low, min(a, b)); high = min(high, max(a, b))
            }
        }
        return if (low.isFinite() && high.isFinite() && high > low + 1e-9) low to high else null
    }
}

private class LessonColor(private val hex: Int, val factor: Double = 1.0) {
    private fun channel(shift: Int): Float {
        val s = ((hex shr shift) and 255) / 255.0
        val linear = (if (s <= .04045) s / 12.92 else ((s + .055) / 1.055).pow(2.4)) * factor
        return (if (linear <= .0031308) linear * 12.92 else 1.055 * linear.pow(1.0 / 2.4) - .055).toFloat().coerceIn(0f, 1f)
    }
    val r = channel(16); val g = channel(8); val b = channel(0)
    private val color = hex
    fun shade(amount: Double) = LessonColor(color, amount.coerceIn(0.0, 1.0))
}

private class LessonMesh(private val transform: (Vec3) -> Vec3) {
    private var values = FloatArray(8192)
    private var size = 0
    private fun vertex(point: Vec3, color: LessonColor) = vertex(point, color.r, color.g, color.b)
    private fun vertex(point: Vec3, r: Float, g: Float, b: Float) {
        if (size + 7 > values.size) values = values.copyOf(values.size * 2)
        val p = transform(point)
        values[size++] = p.x.toFloat(); values[size++] = p.y.toFloat(); values[size++] = p.z.toFloat()
        values[size++] = r; values[size++] = g; values[size++] = b; values[size++] = 1f
    }
    fun result() = values.copyOf(size)
    fun section(section: BoxSection, color: LessonColor) {
        val source = section.mesh
        for (i in source.indices.indices step 3) {
            val first = source.indices[i]
            val shade = .48 + .52 * max(0.0, source.normals[first].dot(Vec3(-.35, .82, .45)))
            val tint = color.shade(shade)
            for (corner in 0..2) vertex(source.positions[source.indices[i + corner]], tint)
        }
    }
    fun sphere(center: Vec3, radius: Double, color: LessonColor) {
        val latitudes = SceneMaterials.SPHERE_RINGS
        val longitudes = SceneMaterials.SPHERE_SECTORS
        val base = SceneMaterials.toLinear(floatArrayOf(color.r, color.g, color.b, 1f))
        // Shared vertex normals keep adjacent triangles continuous, including the seam
        // and poles. Every position remains on the exact analytic section sphere.
        val normals = Array((latitudes + 1) * longitudes) { index ->
            val row = index / longitudes
            val latitude = -PI / 2 + row * PI / latitudes
            val longitude = (index % longitudes) * 2 * PI / longitudes
            when (row) {
                0 -> Vec3(0.0, -1.0, 0.0)
                latitudes -> Vec3(0.0, 1.0, 0.0)
                else -> Vec3(cos(latitude) * cos(longitude), sin(latitude), cos(latitude) * sin(longitude))
            }
        }
        val positions = normals.map { center + it * radius }
        val colors = normals.map { SceneMaterials.toSrgb(SceneMaterials.sphereLinear(base, it)) }
        fun emit(index: Int) {
            val tint = colors[index]
            vertex(positions[index], tint[0], tint[1], tint[2])
        }
        fun triangle(a: Int, b: Int, c: Int) { emit(a); emit(b); emit(c) }
        for (latitude in 0 until latitudes) for (longitude in 0 until longitudes) {
            val next = (longitude + 1) % longitudes
            val a = latitude * longitudes + longitude
            val b = latitude * longitudes + next
            val c = (latitude + 1) * longitudes + next
            val d = (latitude + 1) * longitudes + longitude
            when (latitude) {
                0 -> triangle(a, d, c)
                latitudes - 1 -> triangle(a, c, b)
                else -> { triangle(a, c, b); triangle(a, d, c) }
            }
        }
    }

    /** Layered, clipped-corner instrument base contained in the original box envelope. */
    fun plinth(center: Vec3, half: Vec3, surface: LessonColor, edge: LessonColor, metal: LessonColor) {
        fun rim(inset: Double, height: Double): List<Vec3> {
            val x = half.x - inset
            val z = half.z - inset
            val clip = min(x, z) * .08
            return listOf(Vec3(-x + clip, height, -z), Vec3(x - clip, height, -z),
                Vec3(x, height, -z + clip), Vec3(x, height, z - clip),
                Vec3(x - clip, height, z), Vec3(-x + clip, height, z),
                Vec3(-x, height, z - clip), Vec3(-x, height, -z + clip)).reversed().map { center + it }
        }
        val bevel = min(half.x, half.z) * .025
        val bottom = rim(bevel, -half.y)
        val shoulder = rim(0.0, -half.y * .55)
        val lip = rim(0.0, half.y * .55)
        val top = rim(bevel, half.y)
        fun band(lower: List<Vec3>, upper: List<Vec3>, color: LessonColor) {
            for (i in lower.indices) {
                val next = (i + 1) % lower.size
                face(listOf(lower[i], lower[next], upper[next], upper[i]), color.shade(if (i < 3) .75 else .52))
            }
        }
        face(bottom.reversed(), surface)
        band(bottom, shoulder, surface)
        band(shoulder, lip, edge)
        band(lip, top, metal)
        face(top, surface)
    }

    /** Sparse alignment brackets and a short caution strip read as hardware, not a UI. */
    fun plinthMarkers(center: Vec3, half: Vec3, width: Double, metal: LessonColor, accent: LessonColor) {
        val inset = min(half.x, half.z) * .10
        val run = min(half.x, half.z) * .15
        for (sx in listOf(-1.0, 1.0)) for (sz in listOf(-1.0, 1.0)) {
            val corner = center + Vec3(sx * (half.x - inset), 0.0, sz * (half.z - inset))
            line(corner, corner - Vec3(sx * run, 0.0, 0.0), width, metal)
            line(corner, corner - Vec3(0.0, 0.0, sz * run), width, metal)
        }
        val front = center + Vec3(-half.x * .36, 0.0, half.z - inset)
        for (i in 0..2) {
            val start = front + Vec3(run * .75 * i, 0.0, 0.0)
            line(start, start + Vec3(run * .32, 0.0, -run * .42), width, accent)
        }
    }
    fun face(points: List<Vec3>, color: LessonColor) {
        for (i in 1 until points.lastIndex) {
            vertex(points[0], color); vertex(points[i], color); vertex(points[i + 1], color)
        }
    }
    fun loop(points: List<Vec3>, width: Double, color: LessonColor) {
        points.indices.forEach { line(points[it], points[(it + 1) % points.size], width, color) }
    }
    fun line(start: Vec3, end: Vec3, width: Double, color: LessonColor) {
        val delta = end - start
        if (delta.lengthSquared() < 1e-16) return
        val along = delta * (1.0 / delta.length())
        val axis = if (abs(along.y) < .9) Vec3(0.0, 1.0, 0.0) else Vec3(1.0, 0.0, 0.0)
        val raw = along.cross(axis)
        val side = raw * (width * .5 / raw.length())
        val up = along.cross(side)
        val corners = listOf(side + up, side - up, (side + up) * -1.0, up - side)
        for (i in corners.indices) {
            val a = corners[i]; val b = corners[(i + 1) % corners.size]
            val n = (a + b) * (1.0 / (a + b).length())
            val shade = .5 + .5 * max(0.0, n.dot(Vec3(-.30, .80, .50)))
            face(listOf(start + a, end + a, end + b, start + b), color.shade(shade))
        }
        face(corners.map { start + it }.reversed(), color)
        face(corners.map { end + it }, color)
    }
    fun flatLine(start: Vec3, end: Vec3, width: Double, normal: Vec3, color: LessonColor) {
        val delta = end - start
        if (delta.lengthSquared() < 1e-16) return
        val perpendicular = normal.cross(delta)
        val side = perpendicular * (width * .5 / perpendicular.length())
        face(listOf(start + side, end + side, end - side, start - side), color)
    }
    fun dashed(start: Vec3, end: Vec3, width: Double, color: LessonColor) {
        val distance = (end - start).length()
        if (distance < 1e-8) return
        val direction = (end - start) * (1.0 / distance)
        var at = 0.0
        while (at < distance) {
            line(start + direction * at, start + direction * min(at + .025, distance), width, color)
            at += .044
        }
    }
    private fun corners(center: Vec3, half: Vec3) = (0..7).map { bits ->
        center + Vec3(if (bits and 1 == 0) -half.x else half.x,
            if (bits and 2 == 0) -half.y else half.y,
            if (bits and 4 == 0) -half.z else half.z)
    }
    fun wireBox(center: Vec3, half: Vec3, width: Double, color: LessonColor) {
        val corners = corners(center, half)
        for (index in 0..7) for (axis in listOf(1, 2, 4)) if (index and axis == 0)
            line(corners[index], corners[index or axis], width, color)
    }
    fun wireSphere(center: Vec3, radius: Double, color: LessonColor) {
        val x = Vec3(1.0, 0.0, 0.0)
        val y = Vec3(0.0, 1.0, 0.0)
        val z = Vec3(0.0, 0.0, 1.0)
        ring(center, x, y, radius, .008, color)
        ring(center, x, z, radius, .008, color)
        ring(center, y, z, radius, .008, color)
        for (latitude in listOf(-PI / 4, PI / 4))
            ring(center + y * (sin(latitude) * radius), x, z, cos(latitude) * radius, .006, color)
    }
    fun disc(center: Vec3, right: Vec3, up: Vec3, radius: Double, color: LessonColor) {
        if (radius <= 1e-8) return
        val points = (0 until 24).map { i -> center + right * (cos(2 * PI * i / 24) * radius) + up * (sin(2 * PI * i / 24) * radius) }
        face(points, color)
    }
    fun ring(center: Vec3, right: Vec3, up: Vec3, radius: Double, width: Double, color: LessonColor) {
        val points = (0 until 24).map { i -> center + right * (cos(2 * PI * i / 24) * radius) + up * (sin(2 * PI * i / 24) * radius) }
        loop(points, width, color)
    }
    fun octahedron(center: Vec3, radius: Double, color: LessonColor) {
        val sides = listOf(Vec3(radius, 0.0, 0.0), Vec3(0.0, 0.0, radius), Vec3(-radius, 0.0, 0.0), Vec3(0.0, 0.0, -radius))
        for (i in sides.indices) for (sign in listOf(-1.0, 1.0))
            face(listOf(center + Vec3(0.0, radius * sign, 0.0), center + sides[i], center + sides[(i + 1) % 4]), color)
    }
    fun wireOctahedron(center: Vec3, radius: Double, color: LessonColor) {
        val sides = listOf(Vec3(radius, 0.0, 0.0), Vec3(0.0, 0.0, radius), Vec3(-radius, 0.0, 0.0), Vec3(0.0, 0.0, -radius))
        for (i in sides.indices) {
            line(center + sides[i], center + sides[(i + 1) % 4], .003, color)
            for (sign in listOf(-1.0, 1.0)) line(center + sides[i], center + Vec3(0.0, radius * sign, 0.0), .003, color)
        }
    }
    fun arrowHead(end: Vec3, direction: Vec3, size: Double, color: LessonColor) {
        if (direction.lengthSquared() < 1e-14) return
        val along = direction * (1.0 / direction.length())
        val raw = along.cross(if (abs(along.y) < .9) Vec3(0.0, 1.0, 0.0) else Vec3(1.0, 0.0, 0.0))
        val side = raw * (size * .42 / raw.length())
        val up = along.cross(side)
        val base = end - along * size
        val corners = listOf(base + side, base + up, base - side, base - up)
        for (i in corners.indices) face(listOf(end, corners[i], corners[(i + 1) % corners.size]), color)
    }
}
