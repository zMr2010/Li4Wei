package com.fourthward.game.vr

import com.fourthward.game.core.FingerLandmarks
import com.fourthward.game.core.HandLandmarks
import com.fourthward.game.core.PalmPanelLayout
import com.fourthward.game.core.PalmPointerPosition
import com.fourthward.game.core.Vec3
import kotlin.math.hypot
import kotlin.math.sqrt

/** CPU-only OpenXR coordinate adapter. No tracking pose is recentered or radius-clamped here. */
internal object VrTrackingMath {
    data class Pose(val position: Vec3, val right: Vec3, val up: Vec3, val normal: Vec3)

    fun head(sample: FloatArray?): Pose? {
        if (sample == null || sample.size < 7 || (0..6).any { !sample[it].isFinite() }) return null
        val position = Vec3(sample[0].toDouble(), sample[1].toDouble(), sample[2].toDouble())
        val norm = sqrt((3..6).sumOf { sample[it].toDouble() * sample[it] })
        if (norm <= 1e-9 || !norm.isFinite()) return null
        val xyz = Vec3(sample[3] / norm, sample[4] / norm, sample[5] / norm)
        val w = sample[6] / norm
        fun rotate(vector: Vec3) = vector + xyz.cross(xyz.cross(vector) + vector * w) * 2.0
        return Pose(position, rotate(Vec3(1.0, 0.0, 0.0)), rotate(Vec3(0.0, 1.0, 0.0)), rotate(Vec3(0.0, 0.0, 1.0)))
    }

    fun horizontalRight(head: Pose): Vec3? {
        val forward = head.normal * -1.0
        val length = hypot(forward.x, forward.z)
        if (length > 1e-6) return Vec3(-forward.z / length, 0.0, forward.x / length)
        return unit(Vec3(head.right.x, 0.0, head.right.z))
    }

    /** XR_HAND_JOINT_*_EXT order,26 positions. Invalid/missing hands must arrive as null. */
    fun hand(sample: FloatArray?, isLeft: Boolean): HandLandmarks? {
        if (sample == null || sample.size < 78 || (0 until 78).any { !sample[it].isFinite() }) return null
        fun point(index: Int) = Vec3(sample[index * 3].toDouble(), sample[index * 3 + 1].toDouble(), sample[index * 3 + 2].toDouble())
        fun finger(base: Int, knuckle: Int, distal: Int, tip: Int) = FingerLandmarks(point(base), point(knuckle), point(tip), point(distal))
        val hand = HandLandmarks(point(1), point(0), finger(2, 3, 4, 5), finger(7, 8, 9, 10),
            finger(12, 13, 14, 15), finger(17, 18, 19, 20), finger(22, 23, 24, 25))
        if ((hand.middle.base - hand.wrist).lengthSquared() < 1e-12 ||
            (hand.index.base - hand.little.base).lengthSquared() < 1e-12) return null
        val normal = if (isLeft) (hand.index.base - hand.wrist).cross(hand.little.base - hand.wrist)
            else (hand.little.base - hand.wrist).cross(hand.index.base - hand.wrist)
        if (unit(normal) == null) return null
        return hand.copy(palmNormal = normal)
    }

    /** +normal points out of the physical panel toward the viewer; upright world-Y billboarding. */
    fun panelPose(position: Vec3, head: Pose): Pose? {
        val normal = unit(head.position - position) ?: return null
        val right = unit(Vec3(0.0, 1.0, 0.0).cross(normal))
            ?: unit(head.right - normal * head.right.dot(normal)) ?: return null
        return Pose(position, right, normal.cross(right), normal)
    }

    fun pointer(indexTip: Vec3, panel: Pose,
                widthMeters: Double = PalmPanelLayout.WIDTH_METERS,
                heightMeters: Double = PalmPanelLayout.HEIGHT_METERS): PalmPointerPosition {
        require(widthMeters.isFinite() && widthMeters > 0.0 && heightMeters.isFinite() && heightMeters > 0.0)
        val relative = indexTip - panel.position
        return PalmPointerPosition(0.5 + relative.dot(panel.right) / widthMeters,
            0.5 - relative.dot(panel.up) / heightMeters, relative.dot(panel.normal))
    }

    private fun unit(vector: Vec3): Vec3? {
        val length = vector.length()
        return if (!length.isFinite() || length <= 1e-9) null else vector * (1.0 / length)
    }
}
