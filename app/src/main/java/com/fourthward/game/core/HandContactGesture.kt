package com.fourthward.game.core

internal data class HandContact(
    val hand: HandLandmarks,
    val gripping: Boolean,
    val gripStarted: Boolean,
    val pinching: Boolean,
    val pinchStarted: Boolean,
    val indexExtended: Boolean,
)

/** Recognizes a closed hand and a thumb-index pinch from the same OpenXR joints. */
internal class HandContactGesture {
    private var previous: HandLandmarks? = null
    private var gripping = false
    private var pinching = false
    private var separatingSeconds = 0.0

    fun reset() {
        previous = null
        gripping = false
        pinching = false
        separatingSeconds = 0.0
    }

    fun update(hand: HandLandmarks?, dt: Double): HandContact? {
        if (hand == null || !dt.isFinite() || dt <= 0.0 || dt > PalmPoke.MAX_SAMPLE_GAP_SECONDS ||
            hand.sampleAgeSeconds !in 0.0..PalmPoke.MAX_SAMPLE_GAP_SECONDS) return invalidSample()
        val fingers = listOf(hand.index, hand.middle, hand.ring, hand.little)
        val along = hand.middle.base - hand.wrist
        val length = along.length()
        val width = (hand.index.base - hand.little.base).length()
        val last = previous
        if (!length.isFinite() || length <= 1e-6 || !width.isFinite() || width <= 1e-6 ||
            !hand.palm.lengthSquared().isFinite() || !hand.thumb.tip.lengthSquared().isFinite() ||
            (last != null && (hand.palm - last.palm).lengthSquared() > .15 * .15)) return invalidSample()
        val longitudinal = along * (1.0 / length)
        val scale = (length + width) * .5
        val straightness = fingers.map(::straightness)
        val indexStraightness = straightness.first() ?: return invalidSample()
        val curls = fingers.mapIndexed { index, finger ->
            val measured = straightness[index] ?: return@mapIndexed false
            val chord = finger.tip - finger.base
            chord.dot(longitudinal) <= scale * (if (gripping) .30 else .15) &&
                (finger.tip - hand.palm).length() <= scale * (if (gripping) 1.20 else 1.04) &&
                measured <= (if (gripping) .86 else .76)
        }
        val pinchDistance = (hand.thumb.tip - hand.index.tip).length()
        val releaseDistance = (width * .76).coerceIn(.039, .051)
        // Pads can meet before the tracked tips coincide. A small separation spike during a drag
        // must persist across samples, while opening the fingers clearly releases immediately.
        val enterDistance = (width * .52).coerceIn(.026, .036)
        separatingSeconds = if (pinching && pinchDistance > releaseDistance) separatingSeconds + dt else 0.0
        val withinPinch = if (pinching) pinchDistance <= releaseDistance ||
            (pinchDistance <= releaseDistance + .009 && separatingSeconds < .055) else pinchDistance <= enterDistance
        // While the thumb approaches the index, relaxed resting fingers must not start a grip.
        val nextGrip = straightness.all { it != null } && (if (pinchDistance <= releaseDistance) curls.all { it }
            else curls.first() && curls.count { it } >= 3)
        val nextPinch = !nextGrip && withinPinch
        val indexExtended = (hand.index.tip - hand.index.base).dot(longitudinal) >= scale * .25 &&
            indexStraightness >= .74
        val gripStarted = last != null && !gripping && nextGrip
        val pinchStarted = last != null && !gripping && !pinching && nextPinch
        val contact = HandContact(hand, nextGrip, gripStarted, nextPinch, pinchStarted, indexExtended)
        previous = hand
        gripping = nextGrip
        pinching = nextPinch
        return contact
    }

    private fun straightness(finger: FingerLandmarks): Double? {
        val distal = finger.distal
        val tail = if (distal == null) (finger.tip - finger.knuckle).length()
            else (distal - finger.knuckle).length() + (finger.tip - distal).length()
        val chain = (finger.knuckle - finger.base).length() + tail
        return if (chain.isFinite() && chain > 1e-6) (finger.tip - finger.base).length() / chain else null
    }

    private fun invalidSample(): HandContact? {
        reset()
        return null
    }
}
