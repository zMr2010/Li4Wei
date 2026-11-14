package com.fourthward.game.vr

import com.fourthward.game.core.Vec3
import kotlin.math.max
import kotlin.math.pow

/** Opaque, linear-light materials shared by the world and its miniature demonstrations. */
internal object SceneMaterials {
    const val SPHERE_RINGS = 24
    const val SPHERE_SECTORS = 48

    private fun unit(value: Vec3): Vec3 = value * (1.0 / value.length())
    private val key = unit(Vec3(-.38, .82, .43))
    private val fill = unit(Vec3(.75, .16, -.56))
    private val sheen = unit(Vec3(-.30, .68, .67))

    /** Fixed world lighting: neither head translation nor moving the teaching stage changes it. */
    fun sphereLinear(base: FloatArray, normal: Vec3): FloatArray {
        val diffuse = .29 + .59 * max(0.0, normal.dot(key))
        val coolFill = .12 * max(0.0, normal.dot(fill)).pow(2.0)
        val broadSheen = .12 * max(0.0, normal.dot(sheen)).pow(10.0)
        val softHighlight = .14 * max(0.0, normal.dot(sheen)).pow(42.0)
        return floatArrayOf(
            (base[0] * diffuse + coolFill * .38 + broadSheen * .94 + softHighlight).toFloat().coerceIn(0f, 1f),
            (base[1] * diffuse + coolFill * .66 + broadSheen * .94 + softHighlight).toFloat().coerceIn(0f, 1f),
            (base[2] * diffuse + coolFill * .72 + broadSheen * .86 + softHighlight * .91).toFloat().coerceIn(0f, 1f),
            1f,
        )
    }

    fun toLinear(srgb: FloatArray): FloatArray = FloatArray(4) { channel ->
        if (channel == 3) 1f else {
            val value = srgb[channel].coerceIn(0f, 1f)
            if (value <= .04045f) value / 12.92f else ((value + .055f) / 1.055f).pow(2.4f)
        }
    }

    fun toSrgb(linear: FloatArray): FloatArray = FloatArray(4) { channel ->
        if (channel == 3) 1f else {
            val value = linear[channel].coerceIn(0f, 1f)
            if (value <= .0031308f) value * 12.92f else 1.055f * value.pow(1f / 2.4f) - .055f
        }
    }
}
