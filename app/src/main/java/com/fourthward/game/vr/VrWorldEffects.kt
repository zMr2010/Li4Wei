package com.fourthward.game.vr

import com.fourthward.game.core.FourthwardGame
import com.fourthward.game.core.SphereSection
import com.fourthward.game.core.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Navigational light, authored separately from the exact 4D section mesh.
 *
 * The native renderer draws these straight-alpha xyzrgba triangles with depth testing,
 * but without depth writes. Every anchor is obtained from the current real section;
 * there is deliberately no projected/off-slice marker or cached world-space trail.
 */
class VrWorldEffects {
    private var sceneKey: Pair<String, Long>? = null
    private var time = 0.0
    private var previousCompleted = false
    private var previousCheckpoints = emptySet<String>()
    private val appearedAt = mutableMapOf<String, Double>()
    private val confirmedAt = mutableMapOf<String, Double>()

    /**
     * [sceneRevision] changes on restart/return-to-checkpoint, not on ordinary movement.
     * Hidden menus/lessons pause effects and return an empty buffer, clearing native FX.
     */
    fun frame(
        game: FourthwardGame,
        deltaSeconds: Double,
        active: Boolean,
        visible: Boolean,
        sceneRevision: Long = 0L,
    ): FloatArray {
        val key = game.level.id to sceneRevision
        val checkpoints = game.completedCheckpointIds
        if (sceneKey != key) {
            reset()
            sceneKey = key
            previousCompleted = game.completed
            previousCheckpoints = checkpoints
        }
        if (active && visible && deltaSeconds.isFinite() && deltaSeconds > 0.0) {
            time += deltaSeconds.coerceAtMost(.1)
        }

        val checkpointByVolume = game.level.expedition?.checkpoints.orEmpty().associateBy { it.volume.id }
        val firstPending = game.level.expedition?.checkpoints?.firstOrNull { it.id !in checkpoints }?.id
        val sections = game.visibleSpheres().filter {
            it.source.id == game.level.goal.id || it.source.id == "guide-light" || it.source.id in checkpointByVolume
        }.take(MAX_MARKERS)
        val currentIds = sections.mapTo(mutableSetOf()) { it.source.id }
        appearedAt.keys.retainAll(currentIds)
        confirmedAt.keys.retainAll(currentIds)

        if (active && visible) {
            if (!previousCompleted && game.completed && game.level.goal.id in currentIds) {
                confirmedAt[game.level.goal.id] = time
            }
            checkpointByVolume.forEach { (volumeId, checkpoint) ->
                if (checkpoint.id in checkpoints && checkpoint.id !in previousCheckpoints && volumeId in currentIds) {
                    confirmedAt[volumeId] = time
                }
            }
        }
        previousCompleted = game.completed
        previousCheckpoints = checkpoints
        confirmedAt.entries.removeAll { time - it.value >= CONFIRM_SECONDS }
        if (!visible || sections.isEmpty()) return EMPTY

        val mesh = EffectMesh(sections.size)
        sections.forEach { section ->
            val id = section.source.id
            val checkpoint = checkpointByVolume[id]
            val goal = id == game.level.goal.id
            val guide = id == "guide-light"
            val reached = if (goal) game.completed else checkpoint?.id in checkpoints
            val available = when {
                goal -> game.goalReady
                checkpoint != null -> game.level.expedition?.ordered != true || checkpoint.id == firstPending || reached
                else -> true
            }
            val tint = when {
                reached -> SUCCESS
                guide -> SIGNAL
                checkpoint != null -> Color.fromArgb(checkpoint.colorArgb)
                else -> Color.fromArgb(game.level.goalColorArgb)
            }
            val appeared = appearedAt.getOrPut(id) { time }
            val reveal = ease((time - appeared) / REVEAL_SECONDS)
            val strength = if (available) 1.0 else .22
            val phase = stablePhase(id)
            if (guide) {
                guideLight(mesh, section, game.trackedHead, tint, reveal, strength, phase)
            } else {
                landmark(mesh, section, -game.frame.origin.z, game.trackedHead, tint, reveal, strength, phase, goal)
            }
            confirmedAt[id]?.let { confirmation(mesh, section, -game.frame.origin.z, time - it, tint) }
        }
        return mesh.finish()
    }

    fun reset() {
        sceneKey = null
        time = 0.0
        previousCompleted = false
        previousCheckpoints = emptySet()
        appearedAt.clear()
        confirmedAt.clear()
    }

    private fun landmark(
        mesh: EffectMesh, section: SphereSection, floorY: Double, head: Vec3,
        tint: Color, reveal: Double, strength: Double, phase: Double, goal: Boolean,
    ) {
        val base = Vec3(section.center.x, floorY + .032, section.center.z)
        val radius = section.radius.coerceIn(.12, .28) + .09
        val breath = .68 + .14 * sin(time * .95 + phase)
        val alpha = reveal * strength
        val corners = square(base, radius)
        // The fine ground bracket writes on in four beats, then holds its structure.
        corners.indices.forEach { index ->
            val progress = ((reveal * 1.36 - index * .12) / 1.0).coerceIn(0.0, 1.0)
            val corner = corners[index]
            val next = corners[(index + 1) % 4]
            val previous = corners[(index + 3) % 4]
            mesh.line(corner, corner + (next - corner) * (.22 * progress), .0035, tint, alpha * breath)
            mesh.line(corner, corner + (previous - corner) * (.22 * progress), .0035, tint, alpha * breath)
        }
        // One tiny signal follows the footprint. There is no accumulating particle trail.
        val travel = ((time * .28 + phase / (2 * PI)) % 1.0 + 1.0) % 1.0
        val edge = (travel * 4).toInt().coerceIn(0, 3)
        val along = travel * 4 - edge
        val from = corners[edge]
        val to = corners[(edge + 1) % 4]
        val point = from + (to - from) * along
        val tangent = (to - from) * (1.0 / (to - from).length())
        mesh.lightLine(point - tangent * .022, point + tangent * .022, .004, tint, alpha * .8)

        val floatY = (if (goal) 1.34 else 1.16) + .012 * sin(time * .8 + phase)
        val center = base + Vec3(0.0, floatY, 0.0)
        val right = facingRight(center, head)
        val up = Vec3(0.0, 1.0, 0.0)
        val glyphRadius = if (goal) .065 else .052
        val diamond = listOf(center + up * glyphRadius, center + right * glyphRadius,
            center - up * glyphRadius, center - right * glyphRadius)
        diamond.indices.forEach { index ->
            val fromPoint = diamond[index]
            val toPoint = diamond[(index + 1) % 4]
            val progress = ((reveal - .22) / .78).coerceIn(0.0, 1.0)
            mesh.lightLine(fromPoint, fromPoint + (toPoint - fromPoint) * progress, .003, tint, alpha * .8)
        }
        // Detached guide rule stays below the glyph, rather than filling a solid panel.
        val rule = center - up * (glyphRadius + .035)
        mesh.line(rule - up * .075, rule - up * (.075 * (1 - reveal)), .0025, tint, alpha * .4)
    }

    private fun guideLight(
        mesh: EffectMesh, section: SphereSection, head: Vec3, tint: Color,
        reveal: Double, strength: Double, phase: Double,
    ) {
        val radius = section.radius + .055
        val right = facingRight(section.center, head)
        val up = Vec3(0.0, 1.0, 0.0)
        val opacity = reveal * strength * (.53 + .12 * sin(time * 1.2 + phase))
        // Open tracking corners follow the actual moving section, without changing its size.
        for (x in listOf(-1.0, 1.0)) for (y in listOf(-1.0, 1.0)) {
            val corner = section.center + right * (x * radius) + up * (y * radius)
            mesh.line(corner, corner - right * (x * radius * .32 * reveal), .0025, tint, opacity)
            mesh.line(corner, corner - up * (y * radius * .32 * reveal), .0025, tint, opacity)
        }
        val height = radius * sin(time * 1.25 + phase)
        val point = section.center + right * (radius + .018) + up * height
        mesh.lightLine(point - up * .012, point + up * .012, .003, tint, reveal * strength * .65)
    }

    private fun confirmation(mesh: EffectMesh, section: SphereSection, floorY: Double, age: Double, tint: Color) {
        val t = (age / CONFIRM_SECONDS).coerceIn(0.0, 1.0)
        val opacity = ease(t / .12) * (1.0 - ease((t - .42) / .58)) * .78
        val radius = section.radius.coerceIn(.12, .28) + .1 + .22 * ease(t)
        val base = Vec3(section.center.x, floorY + .045 + .045 * ease(t), section.center.z)
        val corners = square(base, radius)
        corners.indices.forEach { index ->
            val corner = corners[index]
            val next = corners[(index + 1) % 4]
            val previous = corners[(index + 3) % 4]
            mesh.lightLine(corner, corner + (next - corner) * .23, .004, tint, opacity)
            mesh.lightLine(corner, corner + (previous - corner) * .23, .004, tint, opacity)
        }
        // A brief vertical pair resolves into the expanded ground brackets.
        val high = .16 + .14 * ease(t)
        for (side in listOf(-1.0, 1.0)) {
            val point = base + Vec3(side * radius, 0.0, 0.0)
            mesh.line(point + Vec3(0.0, high * .3, 0.0), point + Vec3(0.0, high, 0.0),
                .003, SUCCESS, opacity * .65)
        }
    }

    private fun square(center: Vec3, radius: Double) = listOf(
        center + Vec3(-radius, 0.0, -radius), center + Vec3(radius, 0.0, -radius),
        center + Vec3(radius, 0.0, radius), center + Vec3(-radius, 0.0, radius),
    )

    private fun facingRight(center: Vec3, head: Vec3): Vec3 {
        val normal = Vec3(head.x - center.x, 0.0, head.z - center.z)
        return if (normal.lengthSquared() < 1e-8) Vec3(1.0, 0.0, 0.0)
        else Vec3(normal.z, 0.0, -normal.x) * (1.0 / normal.length())
    }

    private fun stablePhase(id: String): Double = (id.hashCode().toLong() and 0xffff) / 65536.0 * 2 * PI

    private fun ease(value: Double): Double = value.coerceIn(0.0, 1.0).let { it * it * (3 - 2 * it) }

    private data class Color(val r: Float, val g: Float, val b: Float) {
        companion object {
            fun fromArgb(argb: Int) = Color(((argb ushr 16) and 255) / 255f,
                ((argb ushr 8) and 255) / 255f, (argb and 255) / 255f)
        }
    }

    /** Two crossing ribbons are visible from all sides without camera-facing world geometry. */
    private class EffectMesh(markerCount: Int) {
        private val vertices = FloatArray(minOf(MAX_VERTICES, markerCount * 512) * 7)
        private var cursor = 0

        fun line(from: Vec3, to: Vec3, width: Double, color: Color, opacity: Double) {
            if (opacity <= .001 || width <= 0.0) return
            val delta = to - from
            if (delta.lengthSquared() < 1e-12) return
            val along = delta * (1.0 / delta.length())
            val axis = if (abs(along.y) < .85) Vec3(0.0, 1.0, 0.0) else Vec3(1.0, 0.0, 0.0)
            val rawSide = along.cross(axis)
            val side = rawSide * (width * .5 / rawSide.length())
            val other = along.cross(side)
            for (edge in listOf(side, other)) {
                triangle(from - edge, from + edge, to + edge, color, opacity)
                triangle(from - edge, to + edge, to - edge, color, opacity)
            }
        }

        fun lightLine(from: Vec3, to: Vec3, width: Double, color: Color, opacity: Double) {
            line(from, to, width * 3.5, color, opacity * .10)
            line(from, to, width, color, opacity)
        }

        private fun triangle(a: Vec3, b: Vec3, c: Vec3, color: Color, opacity: Double) {
            if (cursor + 21 > vertices.size) return
            for (point in listOf(a, b, c)) {
                vertices[cursor++] = point.x.toFloat()
                vertices[cursor++] = point.y.toFloat()
                vertices[cursor++] = point.z.toFloat()
                vertices[cursor++] = color.r
                vertices[cursor++] = color.g
                vertices[cursor++] = color.b
                vertices[cursor++] = opacity.coerceIn(0.0, 1.0).toFloat()
            }
        }

        fun finish() = if (cursor == 0) EMPTY else vertices.copyOf(cursor)
    }

    companion object {
        private const val REVEAL_SECONDS = .52
        private const val CONFIRM_SECONDS = .88
        private const val MAX_MARKERS = 8
        const val MAX_VERTICES = 8192
        private val EMPTY = floatArrayOf()
        private val SIGNAL = Color.fromArgb(0xfffffa00.toInt())
        private val SUCCESS = Color.fromArgb(0xff9ae3b1.toInt())
    }
}
