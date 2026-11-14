package com.fourthward.game.vr

import com.fourthward.game.core.GeometryExhibits
import com.fourthward.game.core.GeometryTopic
import com.fourthward.game.core.LessonBox
import com.fourthward.game.core.Vec3
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

internal data class AnalogySphere(val id: String, val center: Vec3, val radius: Double)
internal data class AnalogyCircle(val center: Vec3, val radius: Double)

/** Drop W to construct a lower-dimensional analogue, not a projection of a rendered 4D
 * object. Coordinates (X,Z,Y) become (horizontal,vertical,hidden depth). The same XY angle
 * then gives exactly the corresponding circular radius or rectangular horizontal width. */
internal object GeometryLessonAnalogy {
    fun right(angle: Double) = Vec3(cos(angle), 0.0, sin(angle))
    fun normal(angle: Double) = Vec3(-sin(angle), 0.0, cos(angle))
    /** A fixed vertical rotation axis passes through the observer; changing theta must
     * rotate the world plane around them, never carry them around the source object. */
    fun observer(topic: GeometryTopic) = Vec3(0.0, when (topic) {
        GeometryTopic.HYPERSPHERE -> .48
        GeometryTopic.TESSERACT -> .45
        GeometryTopic.AMBIGUOUS_SECTION -> .20
    }, 0.0)

    fun boxes(topic: GeometryTopic): List<LessonBox> = GeometryExhibits.world(topic).boxes.map {
        LessonBox(it.id, Vec3(it.center.x, it.center.z, it.center.y),
            Vec3(it.halfExtents.x, it.halfExtents.z, it.halfExtents.y))
    }

    fun spheres(topic: GeometryTopic): List<AnalogySphere> = GeometryExhibits.world(topic).spheres.map {
        AnalogySphere(it.id, Vec3(it.center.x, it.center.z, it.center.y), it.radius)
    }

    fun circle(sphere: AnalogySphere, angle: Double): AnalogyCircle? {
        val normal = normal(angle)
        val distance = sphere.center.dot(normal)
        val radiusSquared = sphere.radius * sphere.radius - distance * distance
        if (radiusSquared <= 0.0) return null
        return AnalogyCircle(sphere.center - normal * distance, sqrt(radiusSquared))
    }

    fun rectangle(box: LessonBox, angle: Double): List<Vec3> =
        LessonSectionGeometry.section(box, Vec3.ZERO, angle)
}
