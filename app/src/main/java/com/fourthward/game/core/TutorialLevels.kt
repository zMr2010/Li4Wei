package com.fourthward.game.core

data class TutorialLevel(
    val id: String,
    val title: String,
    val instruction: String,
    val hint: String,
    val goal: Sphere4,
    val goalColorArgb: Int,
    val world: World4,
    val start: Vec4 = Vec4.ZERO,
    val groupId: String = "movement",
    val groupTitle: String = "移动",
    val geometryTopic: GeometryTopic? = null,
    val expedition: ExpeditionDefinition? = null,
    val motionTopic: MotionTopic? = null,
    val motionPuzzle: MotionPuzzle? = null,
)

object TutorialLevels {
    fun all(): List<TutorialLevel> {
        val firstGoal = Sphere4("goal", Vec4(0.0, 0.0, 0.0, -2.4), 0.40)
        val secondGoal = Sphere4("goal", Vec4(0.0, 1.8, 0.0, -1.2), 0.40)
        val thirdGoal = Sphere4("goal", Vec4(0.0, 0.0, 0.0, -4.2), 0.40)
        return listOf(
            TutorialLevel("walk", "01 · 走向出口", "先不用旋转，沿地面向前走到青色旗帜。", "向前约 2.4 米。头部转动只改变观察方向，低头或蹲下不会跳跃。",
                firstGoal, 0xFF67E8DB.toInt(), World4(
                    boxes = listOf(Box4("demo-box", Vec4(-1.5, 0.15, 1.0, -1.5), Vec4(0.35, 0.35, 0.35, 0.35), solid = false)),
                    spheres = listOf(firstGoal, Sphere4("demo-sphere", Vec4(1.4, 0.2, 1.0, -1.8), 0.45)),
                )),
            TutorialLevel("turn", "02 · 看见另一个方向", "出口起初不在当前切片中。先转动切片，再向出现的金色旗帜移动。", "转到约 90°，向右约 1.8 米，再向前约 1.2 米。旋转保持你的四维位置不变。",
                secondGoal, 0xFFFFC66D.toInt(), World4(
                    boxes = listOf(Box4("demo-box", Vec4(-1.3, 0.15, 1.0, -2.0), Vec4(0.4, 0.4, 0.4, 0.4), solid = false)),
                    spheres = listOf(secondGoal),
                )),
            TutorialLevel("around", "03 · 进入封闭空间", "终点被四周、顶面和底面的六面墙包围，当前切片没有入口。转向另一维并移动，再回到内部。", "转到 90°，向右 1.2 米；回到 0° 后向前 3 米。再转到 90° 向左 1.2 米，向前到绿色旗帜。只旋转并不能穿墙。",
                thirdGoal, 0xFF9FE870.toInt(), World4(
                    // At Y=0, six overlapping faces enclose a closed X-Z-W volume.
                    // The base lies below the navigational Z=0 ground, leaving the capsule
                    // clear while containing the full goal sphere. All six have finite Y thickness:
                    // the taught excursion to Y=1.2 really leaves their 4D volume.
                    boxes = listOf(
                        Box4("tutorial-wall", Vec4(0.0, 0.0, 1.2, -2.0), Vec4(3.0, 0.65, 2.0, 0.25)),
                        Box4("tutorial-wall-left", Vec4(-3.0, 0.0, 1.2, -4.0), Vec4(0.25, 0.65, 2.0, 2.25)),
                        Box4("tutorial-wall-right", Vec4(3.0, 0.0, 1.2, -4.0), Vec4(0.25, 0.65, 2.0, 2.25)),
                        Box4("tutorial-wall-back", Vec4(0.0, 0.0, 1.2, -6.0), Vec4(3.0, 0.65, 2.0, 0.25)),
                        Box4("tutorial-wall-bottom", Vec4(0.0, 0.0, -0.65, -4.0), Vec4(3.25, 0.65, 0.15, 2.25)),
                        Box4("tutorial-wall-top", Vec4(0.0, 0.0, 3.0, -4.0), Vec4(3.25, 0.65, 0.2, 2.25)),
                    ),
                    spheres = listOf(thirdGoal),
                )),
            geometryLevel("hypersphere", "04 · 四维球的截面", GeometryTopic.HYPERSPHERE,
                "观察球体截面怎样变大、变小和消失，把这些变化联系到同一个四维球。", "依次转到 0°、45°、135°，各停留片刻。转动头部只改变观看方向。", 0xFF67E8DB.toInt()),
            geometryLevel("tesseract", "05 · 四维正方体", GeometryTopic.TESSERACT,
                "比较三个方向的截面，看看宽、高和前后深度中哪些长度发生了变化。", "依次转到 0°、45°、90°，各停留片刻。物体固定，变化来自真实相交。", 0xFFFFC66D.toInt()),
            geometryLevel("ambiguity", "06 · 相同截面，不同物体", GeometryTopic.AMBIGUOUS_SECTION,
                "比较上下两个物体在不同方向的截面，找出起初看不见的长度差异。", "依次转到 0°、45°、90°，各停留片刻。一张相同的截面，不能确定完整物体相同。", 0xFF9FE870.toInt()),
        ).map { it.copy(title = it.title.replace(Regex("^\\d+ · "), "")) } +
            ExpeditionLevels.all() + MotionStudyLevels.all() + MotionPuzzles.levels()
    }

    private fun geometryLevel(id: String, title: String, topic: GeometryTopic,
                              instruction: String, hint: String, color: Int): TutorialLevel {
        // Navigation metadata is retained for the shared level type, but geometry studies
        // have no exit object: their ordered observations are the entire win condition.
        val goal = Sphere4("goal", Vec4(0.0, 0.0, 0.0, -1.3), .35)
        val exhibit = GeometryExhibits.world(topic)
        return TutorialLevel(id, title, instruction, hint, goal, color,
            exhibit, groupId = "recognition", groupTitle = "辨认", geometryTopic = topic)
    }
}
