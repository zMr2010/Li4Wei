package com.fourthward.game.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

enum class MotionTopic { NORMAL_SPHERE, OBLIQUE_SPHERE, MOVING_BOX }

/** Moving source volumes have constant size and orientation. Only their 4D centers move. */
object MotionStudies {
    const val cycleSeconds = 12.0
    const val passageSeconds = cycleSeconds / 2.0
    const val sphereRadius = .65
    const val sphereAmplitude = 1.15
    const val boxAmplitude = 1.0
    val displayCenter = Vec3(0.0, 1.3, -2.4)

    // All three source axes have the same Y component. At Y=0 the passing cube's
    // transverse section changes from a triangle to a hexagon, then a triangle again.
    val boxBasis = listOf(
        Vec4(1 / sqrt(2.0), 1 / sqrt(3.0), 1 / sqrt(6.0), 0.0),
        Vec4(-1 / sqrt(2.0), 1 / sqrt(3.0), 1 / sqrt(6.0), 0.0),
        Vec4(0.0, 1 / sqrt(3.0), -2 / sqrt(6.0), 0.0),
        Vec4.W,
    )

    fun normalOffset(topic: MotionTopic, timeSeconds: Double): Double {
        require(timeSeconds.isFinite())
        val amplitude = if (topic == MotionTopic.MOVING_BOX) boxAmplitude else sphereAmplitude
        return -amplitude * cos(2 * PI * (timeSeconds % cycleSeconds) / cycleSeconds)
    }

    fun world(topic: MotionTopic, timeSeconds: Double): World4 {
        val hidden = normalOffset(topic, timeSeconds)
        val x = if (topic == MotionTopic.OBLIQUE_SPHERE) hidden * .75 else 0.0
        val center = Vec4(x, hidden, displayCenter.y, displayCenter.z)
        return if (topic == MotionTopic.MOVING_BOX) World4(
            listOf(
                Box4("motion-box", Vec4(0.0, hidden, 1.95, displayCenter.z), Vec4(.45, .45, .45, .40),
                    basis = boxBasis, solid = false, colorArgb = 0xFFE9D9B0.toInt()),
                Box4("motion-long-box", Vec4(hidden * .7, hidden, .55, displayCenter.z), Vec4(.85, .40, .28, .40),
                    solid = false, colorArgb = 0xFF77C7C6.toInt()),
            ), emptyList(),
        ) else World4(emptyList(), listOf(Sphere4("motion-sphere", center, sphereRadius)))
    }

    fun instruction(topic: MotionTopic): String = when (topic) {
        MotionTopic.NORMAL_SPHERE -> "观察球体穿过切片时，截面怎样在原处出现、变大，再缩小离开。"
        MotionTopic.OBLIQUE_SPHERE -> "留意截面的大小和位置，比较斜向穿行带来的两种变化。"
        MotionTopic.MOVING_BOX -> "比较上方棱数变化的盒体与下方保持长方体轮廓的移动截面。"
    }
}
