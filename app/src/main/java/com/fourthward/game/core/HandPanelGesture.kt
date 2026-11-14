package com.fourthward.game.core

/** Non-thumb: base=PROXIMAL, knuckle=INTERMEDIATE, distal=DISTAL, tip=TIP.
 * Thumb: base=METACARPAL, knuckle=PROXIMAL, distal=DISTAL, tip=TIP. */
data class FingerLandmarks(
    val base: Vec3,
    val knuckle: Vec3,
    val tip: Vec3,
    val distal: Vec3? = null,
)

/** Right-hand joints and head position must share one coordinate system, normally stage space.
 * [palmNormal], when supplied, points OUT of the palmar surface, not the back of the hand.
 * A still hand is not stale: populate [sampleAgeSeconds] only from actual sample timing. */
data class HandLandmarks(
    val wrist: Vec3,
    val palm: Vec3,
    val thumb: FingerLandmarks,
    val index: FingerLandmarks,
    val middle: FingerLandmarks,
    val ring: FingerLandmarks,
    val little: FingerLandmarks,
    val palmNormal: Vec3? = null,
    val sampleAgeSeconds: Double = 0.0,
)

data class HandPanelGestureConfig(
    val showSeconds: Double = 0.15,
    val hideSeconds: Double = 0.20,
    val staleAfterSeconds: Double = 0.12,
    val facingEnterDot: Double = 0.65,
    val facingExitDot: Double = 0.50,
) {
    init {
        require(showSeconds.isFinite() && showSeconds >= 0.0)
        require(hideSeconds.isFinite() && hideSeconds >= 0.0)
        require(staleAfterSeconds.isFinite() && staleAfterSeconds > 0.0)
        require(facingEnterDot in 0.0..1.0 && facingExitDot in 0.0..facingEnterDot)
    }
}

data class HandPanelGestureState(
    val visible: Boolean = false,
    val isOpen: Boolean = false,
    val isFacingHead: Boolean = false,
    val validTracking: Boolean = false,
    val facingDot: Double = 0.0,
    val palmNormal: Vec3? = null,
)

/** Five extended fingers + right palm facing the head. This controls visibility only;
 * left-hand pointing/clicking belongs to the UI input adapter, not this predicate. */
class HandPanelGesture(private val config: HandPanelGestureConfig = HandPanelGestureConfig()) {
    private var visible = false
    private var showElapsed = 0.0
    private var hideElapsed = 0.0

    fun reset() {
        visible = false
        showElapsed = 0.0
        hideElapsed = 0.0
    }

    fun update(hand: HandLandmarks?, headPosition: Vec3, deltaSeconds: Double): HandPanelGestureState {
        if (hand == null || !deltaSeconds.isFinite() || deltaSeconds < 0.0 ||
            !headPosition.finite() || !hand.sampleAgeSeconds.isFinite() || hand.sampleAgeSeconds < 0.0 ||
            hand.sampleAgeSeconds > config.staleAfterSeconds) return trackingLost()

        val fingers = listOf(hand.thumb, hand.index, hand.middle, hand.ring, hand.little)
        if (!hand.wrist.finite() || !hand.palm.finite() || fingers.any { !it.finite() }) return trackingLost()

        val across = hand.index.base - hand.little.base // Toward the thumb side of this RIGHT hand.
        val along = hand.middle.base - hand.wrist
        val width = across.length()
        val palmLength = along.length()
        val scale = (width + palmLength) * 0.5
        if (!scale.isFinite() || scale <= 1e-6 || width < 0.20 * scale || palmLength < 0.20 * scale) return trackingLost()

        // Canonical right palm facing +Z: wrist=(0,0,0), index=(-a,b,0), little=(a,b,0).
        // little×index points +Z. Reversing this sign would accept the back of the hand.
        val anatomicalNormal = (hand.little.base - hand.wrist).cross(hand.index.base - hand.wrist)
        if (anatomicalNormal.length() <= scale * scale * 0.02) return trackingLost()
        val normal = (hand.palmNormal ?: anatomicalNormal).unitOrNull() ?: return trackingLost()
        val toHead = (headPosition - hand.palm).unitOrNull() ?: return trackingLost()

        // A long update gap is not evidence that a gesture was continuously held.
        // Hide immediately, then require fresh consecutive frames before showing again.
        val interrupted = deltaSeconds > config.staleAfterSeconds
        if (interrupted) reset()
        val elapsed = if (interrupted) 0.0 else deltaSeconds
        val relaxed = visible
        val longitudinal = along * (1.0 / palmLength)
        val thumbward = across * (1.0 / width)
        val open = listOf(hand.index, hand.middle, hand.ring, hand.little).all {
            extendedFinger(it, hand.wrist, longitudinal, scale, relaxed)
        } && extendedThumb(hand.thumb, hand.index.base, hand.wrist, thumbward, scale, relaxed)
        val facingDot = normal.dot(toHead).coerceIn(-1.0, 1.0)
        val facing = facingDot >= if (relaxed) config.facingExitDot else config.facingEnterDot

        if (open && facing) {
            hideElapsed = 0.0
            showElapsed += elapsed
            if (showElapsed + 1e-9 >= config.showSeconds) visible = true
        } else {
            showElapsed = 0.0
            hideElapsed += elapsed
            if (hideElapsed + 1e-9 >= config.hideSeconds) visible = false
        }
        return HandPanelGestureState(visible, open, facing, true, facingDot, normal)
    }

    private fun trackingLost(): HandPanelGestureState {
        reset()
        return HandPanelGestureState()
    }

    private fun extendedFinger(finger: FingerLandmarks, wrist: Vec3, longitudinal: Vec3, scale: Double, relaxed: Boolean): Boolean {
        val chord = finger.tip - finger.base
        val length = chord.length()
        if (length < scale * if (relaxed) 0.40 else 0.50) return false
        if ((finger.tip - wrist).length() - (finger.base - wrist).length() < scale * if (relaxed) 0.24 else 0.36) return false
        if (chord.dot(longitudinal) / length < if (relaxed) 0.20 else 0.35) return false
        return straightChain(finger, scale, if (relaxed) 0.75 else 0.84, if (relaxed) 0.20 else 0.45)
    }

    private fun extendedThumb(thumb: FingerLandmarks, indexBase: Vec3, wrist: Vec3, thumbward: Vec3, scale: Double, relaxed: Boolean): Boolean {
        if ((thumb.tip - thumb.base).length() < scale * if (relaxed) 0.35 else 0.45) return false
        if ((thumb.tip - wrist).length() - (thumb.base - wrist).length() < scale * if (relaxed) 0.12 else 0.20) return false
        // A tucked thumb can have a straight chain: require it to leave the index-side palm edge.
        if ((thumb.tip - indexBase).dot(thumbward) < scale * if (relaxed) 0.08 else 0.14) return false
        return straightChain(thumb, scale, if (relaxed) 0.70 else 0.80, if (relaxed) -0.05 else 0.15)
    }

    private fun straightChain(finger: FingerLandmarks, scale: Double, minimumStraightness: Double, minimumBendDot: Double): Boolean {
        val joints = listOfNotNull(finger.base, finger.knuckle, finger.distal, finger.tip)
        val segments = joints.zipWithNext { from, to -> to - from }
        val lengths = segments.map { it.length() }
        if (lengths.any { it < 0.025 * scale }) return false
        if ((finger.tip - finger.base).length() / lengths.sum() < minimumStraightness) return false
        return (0 until segments.lastIndex).all { i -> segments[i].dot(segments[i + 1]) / (lengths[i] * lengths[i + 1]) >= minimumBendDot }
    }

    private fun FingerLandmarks.finite() = base.finite() && knuckle.finite() && tip.finite() && (distal?.finite() ?: true)
    private fun Vec3.finite() = x.isFinite() && y.isFinite() && z.isFinite()
    private fun Vec3.unitOrNull(): Vec3? {
        if (!finite()) return null
        val length = length()
        return if (length <= 1e-9 || !length.isFinite()) null else this * (1.0 / length)
    }
}
