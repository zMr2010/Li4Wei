package com.fourthward.game.core

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sqrt

enum class GeometryTopic { HYPERSPHERE, TESSERACT, AMBIGUOUS_SECTION }

internal data class GeometryObservation(val angleDegrees: Double, val instruction: String, val hint: String)

/** Fixed four-dimensional objects shared by the narrated introduction and playable study. */
object GeometryExhibits {
    fun world(topic: GeometryTopic): World4 = when (topic) {
        GeometryTopic.HYPERSPHERE -> World4(emptyList(), listOf(
            Sphere4("study-sphere", Vec4(.7, .7, 1.35, -2.2), .75),
        ))
        GeometryTopic.TESSERACT -> World4(listOf(
            Box4("study-cube", Vec4(0.0, 0.0, 1.25, -2.3), Vec4(.65, .65, .65, .65), solid = false),
        ), emptyList())
        GeometryTopic.AMBIGUOUS_SECTION -> World4(listOf(
            Box4("study-short", Vec4(0.0, 0.0, .8, -2.6), Vec4(.45, .25, .45, .45), solid = false),
            Box4("study-long", Vec4(0.0, 0.0, 2.1, -2.6), Vec4(.45, 1.1, .45, .45), solid = false),
        ), emptyList())
    }

    internal fun observations(topic: GeometryTopic): List<GeometryObservation> = when (topic) {
        GeometryTopic.HYPERSPHERE -> sphereObservations
        GeometryTopic.TESSERACT -> cubeObservations
        GeometryTopic.AMBIGUOUS_SECTION -> ambiguousObservations
    }

    /** Angle alone is insufficient: the section must actually have the demonstrated shape. */
    internal fun matches(topic: GeometryTopic, step: Int, world: World4, frame: SliceFrame,
                         playerFeet: Vec4, observationOrigin: Vec4): Boolean {
        val observation = observations(topic).getOrNull(step) ?: return false
        if (hypot(playerFeet.x - observationOrigin.x, playerFeet.y - observationOrigin.y) > .2 + 1e-9) return false
        val difference = abs(Math.toDegrees(frame.angleRadians) - observation.angleDegrees)
        if (min(difference, 360.0 - difference) > 6.0 + 1e-9) return false
        fun width(id: String): Double? {
            val source = world.boxes.firstOrNull { it.id == id } ?: return null
            val section = SliceGeometry.sliceBox(source, frame) ?: return null
            return section.vertices.maxOf { it.x } - section.vertices.minOf { it.x }
        }
        fun near(actual: Double?, expected: Double) = actual != null && abs(actual - expected) <= expected * .15
        return when (topic) {
            GeometryTopic.HYPERSPHERE -> {
                val source = world.spheres.firstOrNull { it.id == "study-sphere" } ?: return false
                val section = SliceGeometry.sliceSphere(source, frame)
                when (step) {
                    0 -> section != null && section.radius in .08.. .46
                    1 -> section != null && section.radius >= .70
                    else -> section == null
                }
            }
            GeometryTopic.TESSERACT -> near(width("study-cube"), if (step == 1) 1.3 * sqrt(2.0) else 1.3)
            GeometryTopic.AMBIGUOUS_SECTION -> {
                val shortWidth = width("study-short")
                val longWidth = width("study-long")
                when (step) {
                    0 -> near(shortWidth, .9) && near(longWidth, .9) && abs(shortWidth!! - longWidth!!) < .04
                    1 -> near(shortWidth, .5 * sqrt(2.0)) && near(longWidth, .9 * sqrt(2.0))
                    else -> near(shortWidth, .5) && near(longWidth, 2.2)
                }
            }
        }
    }

    private val sphereObservations = listOf(
        GeometryObservation(0.0, "观察 1 / 3：保持 θ = 0°，看看眼前的小球。", "停留片刻，仔细观察。它是四维球偏离中心的三维截面。"),
        GeometryObservation(45.0, "观察 2 / 3：转到 θ = 45°，观察球体变大。", "保持约 45° 并停留。切片经过四维球中心时，截面球最大。"),
        GeometryObservation(135.0, "观察 3 / 3：转到 θ = 135°，观察球体消失。", "保持约 135° 并停留。物体没有消失，只是当前切片不再与它相交。"),
    )
    private val cubeObservations = listOf(
        GeometryObservation(0.0, "观察 1 / 3：保持 θ = 0°，观察正方体截面。", "停留片刻，仔细观察。此时截面的三个方向一样长。"),
        GeometryObservation(45.0, "观察 2 / 3：转到 θ = 45°，看看横向拉长的截面。", "保持约 45° 并停留。四维正方体保持不动，改变的是相交的方向。"),
        GeometryObservation(90.0, "观察 3 / 3：转到 θ = 90°，观察截面重新变成正方体。", "比较宽、高和前后深度：截面的三个方向又一样长了。"),
    )
    private val ambiguousObservations = listOf(
        GeometryObservation(0.0, "观察 1 / 3：保持 θ = 0°，比较上下两个相同的截面。", "停留片刻，仔细观察。只凭这一张截面，还不能判断完整物体是否相同。"),
        GeometryObservation(45.0, "观察 2 / 3：转到 θ = 45°，比较两个截面的宽度。", "保持约 45° 并停留。下方变窄、上方变宽，差别开始显现。"),
        GeometryObservation(90.0, "观察 3 / 3：转到 θ = 90°，观察隐藏方向上的长度差异。", "保持约 90° 并停留。多个方向的截面，才能帮助我们区分四维物体。"),
    )
}
