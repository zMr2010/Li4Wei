package com.fourthward.game.vr

import com.fourthward.game.core.FingerLandmarks
import com.fourthward.game.core.HandLandmarks
import com.fourthward.game.core.PalmPanelLayout
import com.fourthward.game.core.PalmPoke
import com.fourthward.game.core.PalmPointerPosition
import com.fourthward.game.core.Vec3
import kotlin.math.hypot
import kotlin.math.sqrt

/** CPU-only OpenXR coordinate adapter. */
internal object VrTrackingMath {
    private val missingJoint = Vec3(Double.NaN, Double.NaN, Double.NaN)
    private const val HAND_POSITION_FLOATS = 26 * 3
    data class Pose(val position: Vec3, val right: Vec3, val up: Vec3, val normal: Vec3)

    /** Places the level's forward axis along the horizontal heading at spawn. */
    class LevelSpace internal constructor(private val origin: Vec3, private val right: Vec3, private val back: Vec3) {
        fun localVector(vector: Vec3) = Vec3(vector.dot(right), vector.y, vector.dot(back))
        fun localPosition(position: Vec3) = localVector(position - origin)

        val worldModel = floatArrayOf(
            right.x.toFloat(), 0f, right.z.toFloat(), 0f,
            0f, 1f, 0f, 0f,
            back.x.toFloat(), 0f, back.z.toFloat(), 0f,
            origin.x.toFloat(), 0f, origin.z.toFloat(), 1f,
        )
    }

    fun levelSpace(head: Pose, localHead: Vec3 = Vec3.ZERO): LevelSpace {
        val right = horizontalRight(head) ?: Vec3(1.0, 0.0, 0.0)
        val back = Vec3(-right.z, 0.0, right.x)
        val origin = head.position.copy(y = 0.0) - right * localHead.x - back * localHead.z
        return LevelSpace(origin, right, back)
    }

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

    /** Head-relative ground movement; pitch/roll never introduce vertical travel or slow walking.
     * The returned coordinates belong to the current 3D slice. SliceFrame alone applies theta. */
    fun locomotion(head: Pose, rightMeters: Double, forwardMeters: Double, upMeters: Double = 0.0): Vec3 {
        val right = horizontalRight(head) ?: return Vec3(0.0, upMeters, 0.0)
        val forward = Vec3(right.z, 0.0, -right.x)
        return right * rightMeters + forward * forwardMeters + Vec3(0.0, upMeters, 0.0)
    }

    /** A grab pull advances along the current OpenXR view's local −Z axis, including pitch. */
    fun pullMovement(head: Pose, meters: Double): Vec3 = head.normal * -meters

    /** 26 XYZ positions in XR_HAND_JOINT_*_EXT order, optionally followed by palm joint orientation (xyzw). */
    fun hand(sample: FloatArray?, isLeft: Boolean): HandLandmarks? {
        if (sample == null || sample.size < HAND_POSITION_FLOATS) return null
        fun point(index: Int): Vec3? {
            val x = sample[index * 3]
            val y = sample[index * 3 + 1]
            val z = sample[index * 3 + 2]
            return if (x.isFinite() && y.isFinite() && z.isFinite()) Vec3(x.toDouble(), y.toDouble(), z.toDouble()) else null
        }
        // The pointer needs the thumb/index tips, index axis and palm axes. Preserve missing
        // landmarks on the other fingers so full-hand classifiers can reject incomplete shapes.
        fun finger(base: Int, knuckle: Int, distal: Int, tip: Int) = FingerLandmarks(
            point(base) ?: missingJoint, point(knuckle) ?: missingJoint, point(tip) ?: missingJoint, point(distal))

        val wrist = point(1) ?: return null
        val middleMetacarpal = point(11)
        val middleProximal = point(12) ?: return null
        val trackedPalm = point(0) ?: return null
        // Quest Bridge 0.2.5 synthesizes PALM halfway from wrist to the *base* of the
        // middle metacarpal. OpenXR locates it halfway along that bone, toward its MCP.
        val palm = if (middleMetacarpal != null && (trackedPalm - (wrist + middleMetacarpal) * .5).lengthSquared() <= .003 * .003)
            (middleMetacarpal + middleProximal) * .5 else trackedPalm
        val hand = HandLandmarks(wrist, palm, finger(2, 3, 4, 5), finger(7, 8, 9, 10),
            finger(12, 13, 14, 15), finger(17, 18, 19, 20), finger(22, 23, 24, 25))
        if (!hand.thumb.tip.lengthSquared().isFinite() || !hand.index.tip.lengthSquared().isFinite() ||
            !hand.index.knuckle.lengthSquared().isFinite()) return null
        if ((hand.middle.base - hand.wrist).lengthSquared() < 1e-12 ||
            (hand.index.base - hand.little.base).lengthSquared() < 1e-12) return null
        val planeNormal = unit(hand.palmNormalFromJoints(isLeft)) ?: return null
        val alongBone = middleMetacarpal?.let { unit(middleProximal - it) } ?: return hand.copy(palmNormal = planeNormal)
        val trackedNormal = trackedPalmNormal(sample, alongBone, planeNormal)
        return hand.copy(palmNormal = trackedNormal ?: planeNormal)
    }

    private fun trackedPalmNormal(sample: FloatArray, towardFingers: Vec3, planeNormal: Vec3): Vec3? {
        val offset = HAND_POSITION_FLOATS
        if (sample.size < offset + 4) return null
        val xyz = Vec3(sample[offset].toDouble(), sample[offset + 1].toDouble(), sample[offset + 2].toDouble())
        val w = sample[offset + 3].toDouble()
        val normSquared = xyz.lengthSquared() + w * w
        if (normSquared !in .98..1.021) return null
        fun rotate(v: Vec3) = v + xyz.cross(xyz.cross(v) + v * w) * (2.0 / normSquared)
        // XR_EXT_hand_tracking specifies -Y through the palm and -Z toward the fingers.
        val fingers = rotate(Vec3(0.0, 0.0, -1.0))
        val palm = rotate(Vec3(0.0, -1.0, 0.0))
        // A synthesized wrist/palm rotation may agree with the finger axis yet reverse the palm.
        if (fingers.dot(towardFingers) < .8 || palm.dot(planeNormal) < .8) return null
        return palm
    }

    /** +normal points out of the physical panel toward the viewer; upright world-Y billboarding. */
    fun panelPose(position: Vec3, head: Pose): Pose? {
        val normal = unit(head.position - position) ?: return null
        val right = unit(Vec3(0.0, 1.0, 0.0).cross(normal))
            ?: unit(head.right - normal * head.right.dot(normal)) ?: return null
        return Pose(position, right, normal.cross(right), normal)
    }

    /** Keep the start map within arm's reach and upright even when the first tracked pose is tilted. */
    fun startPanelPose(head: Pose): Pose? {
        val right = horizontalRight(head) ?: return null
        val forward = Vec3(right.z, 0.0, -right.x)
        val center = head.position + forward * .56 - right * .19 - Vec3(0.0, .085, 0.0)
        return panelPose(center, head)
    }

    fun pointer(indexTip: Vec3, panel: Pose,
                widthMeters: Double = PalmPanelLayout.WIDTH_METERS,
                heightMeters: Double = PalmPanelLayout.HEIGHT_METERS): PalmPointerPosition {
        require(widthMeters.isFinite() && widthMeters > 0.0 && heightMeters.isFinite() && heightMeters > 0.0)
        val relative = indexTip - panel.position
        return PalmPointerPosition(0.5 + relative.dot(panel.right) / widthMeters,
            0.5 - relative.dot(panel.up) / heightMeters, relative.dot(panel.normal))
    }

    fun pointAtPanel(index: FingerLandmarks, panel: Pose, width: Double, height: Double): PalmPointerPosition? {
        // The MCP-to-tip axis is longer and less sensitive to small PIP joint errors.
        val direction = unit(index.tip - index.base) ?: return null
        val alignment = direction.dot(panel.normal)
        if (alignment >= -.15) return null
        val distance = (panel.position - index.tip).dot(panel.normal) / alignment
        if (distance !in 0.0.. .55) return null
        val hit = pointer(index.tip + direction * distance, panel, width, height)
        return if (PalmPanelLayout.inPanel(hit.x, hit.y)) hit.copy(depthMeters = PalmPoke.MAX_HOVER_DEPTH_METERS) else null
    }

    private fun unit(vector: Vec3): Vec3? {
        val length = vector.length()
        return if (!length.isFinite() || length <= 1e-9) null else vector * (1.0 / length)
    }
}
