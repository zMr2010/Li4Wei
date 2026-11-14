package com.fourthward.game.core

object MotionStudyLevels {
    fun all(): List<TutorialLevel> = listOf(
        level("motion-normal", "垂直穿行", MotionTopic.NORMAL_SPHERE,
            "先保持切片方向，看看截面中心是否移动；也可以走到侧面观察。"),
        level("motion-oblique", "斜向穿行", MotionTopic.OBLIQUE_SPHERE,
            "对照刚才的垂直穿行，留意这个球一边改变大小，一边横向移动。"),
        level("motion-box", "移动的棱面", MotionTopic.MOVING_BOX,
            "留意上方截面的棱数，以及下方长方体的位置；可以换个位置看看它们的厚度。"),
    )

    private fun level(id: String, title: String, topic: MotionTopic, hint: String) = TutorialLevel(
        id, title, MotionStudies.instruction(topic), hint,
        Sphere4("goal", Vec4(0.0, 0.0, 0.0, -1.3), .35), 0xFF67E8DB.toInt(),
        MotionStudies.world(topic, 0.0), groupId = "motion", groupTitle = "运动", motionTopic = topic,
    )
}
