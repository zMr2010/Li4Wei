package com.fourthward.game.core

/** Small, closed 4D buildings. Every wall and landmark is intersected by the same slice. */
object ExpeditionLevels {
    private val amber = 0xFFFFC66D.toInt()
    private val cyan = 0xFF67E8DB.toInt()
    private val white = 0xFFE8ECEA.toInt()
    private const val low = -0.5
    private const val high = 2.8

    fun all(): List<TutorialLevel> = listOf(crossedPortals(), hiddenGallery(), beaconHome())

    private fun crossedPortals(): TutorialLevel {
        val room = Bounds(-1.2, 3.6, -1.0, 2.6, -7.0, 0.9)
        val nodes = listOf(
            checkpoint("amber-door", "金色门廊", Vec4(0.0, 1.4, 0.0, -2.8), amber),
        )
        val mission = ExpeditionDefinition(nodes, true, listOf(
            stage("找到金色门廊，穿过第一道隔墙。",
                "门没有消失，只是没有落在眼前的截面里。",
                "先转动切片，寻找金色的门框；移动后再观察。",
                "转到 90°，向右走约 1.4 米，再向前穿过金色门廊。"),
            stage("穿过青色门廊，到达出口。",
                "两扇门没有排在同一条直线上，出口在第二道隔墙后。",
                "在中间房间换向，沿青色门框后的通道前进。",
                "回到 0°，向右约 2.4 米，再向前穿过青色门廊，走到出口标记。"),
        ))
        val boxes = shell(room) + gate("amber-gate", room, -2.0, 0.0, 1.4) +
            gate("cyan-gate", room, -4.6, 2.4, 1.4) +
            arch("amber-landmark", -2.0, 0.0, 1.4, amber) +
            arch("cyan-landmark", -4.6, 2.4, 1.4, cyan)
        return level("crossed-portals", "07 · 交错门廊", mission,
            Sphere4("goal", Vec4(2.4, 1.4, 0.0, -6.2), .38), cyan, boxes)
    }

    private fun hiddenGallery(): TutorialLevel {
        val room = Bounds(-1.2, 3.0, -0.9, 2.4, -6.4, .9)
        val nodes = listOf(
            checkpoint("connector", "隐藏连接处", Vec4(0.0, 1.5, 0.0, -4.7), amber),
            checkpoint("return-corridor", "另一条回廊", Vec4(1.8, 0.0, 0.0, -4.7), cyan),
        )
        val mission = ExpeditionDefinition(nodes, true, listOf(
            stage("寻找两条回廊之间的连接处。",
                "眼前分开的两段空间，可能在别的截面中相连。",
                "走到回廊深处，在金色地标附近换个方向观察。",
                "先向前约 4.7 米；转到 90°，向右约 1.5 米，进入连接处。"),
            stage("沿连接处进入另一条回廊。",
                "沿着金色地带，观察原来隔墙背后的空间。",
                "换个方向横穿连接处，再回到青色回廊。",
                "回到 0°，向右约 1.8 米；转到 90°，向左约 1.5 米。"),
            stage("沿青色回廊返回出口。",
                "你现在位于起点旁边的另一条回廊。",
                "沿回廊向来时的方向走。",
                "回到 0°，向后约 4.5 米，到达青色出口。"),
        ))
        // X divides the two visible corridors. Its end in Y is only accessible through
        // the far W opening in the transverse Y wall; neither W end can be rounded.
        val walls = listOf(
            box("corridor-divider", .8, 1.0, room.y0 - .2, .85, room.w0 - .2, room.w1 + .2),
            box("hidden-wall-front", room.x0 - .2, room.x1 + .2, .65, .85, -4.1, room.w1 + .2),
            box("hidden-wall-back", room.x0 - .2, room.x1 + .2, .65, .85, room.w0 - .2, -5.3),
        )
        val landmarks = listOf(
            box("amber-landmark-approach", -.5, .5, -.15, .15, -5.15, -4.25, .015, .04, amber),
            box("amber-landmark-connector", -.5, 2.3, 1.2, 1.8, -4.75, -4.65, .015, .04, amber),
            box("cyan-landmark-return", 1.3, 2.3, -.15, .15, -5.15, -4.25, .015, .04, cyan),
            box("cyan-landmark-exit", 1.3, 2.3, -.15, .15, -.6, .2, .015, .04, cyan),
        )
        return level("hidden-gallery", "08 · 隐藏回廊", mission,
            Sphere4("goal", Vec4(1.8, 0.0, 0.0, -.2), .38), cyan, shell(room) + walls + landmarks)
    }

    private fun beaconHome(): TutorialLevel {
        val room = Bounds(-2.8, 2.8, -1.2, 2.8, -6.0, .9)
        val nodes = listOf(
            checkpoint("amber-beacon", "金色信标", Vec4(1.4, 0.0, 0.0, -4.8), amber),
            checkpoint("cyan-beacon", "青色信标", Vec4(-1.4, 1.4, 0.0, -4.8), cyan),
        )
        val mission = ExpeditionDefinition(nodes, false, listOf(
            stage("点亮两座信标，记住返回入口的路。",
                "两座信标各在一间房里，可以先去任意一间。",
                "金色门与入口在同一层；青色门藏在另一方向。",
                "先向右约 1.4 米，再向前走进金色房间；另一扇门在入口左侧转动后的方向。"),
            stage("一座信标已点亮，继续寻找另一座。",
                "先回到两间房前的入口大厅，再寻找另一种颜色。",
                "青色门比金色门多偏出一个方向；从大厅转动切片寻找它。",
                "金色门在入口右侧 1.4 米处；青色门在左侧 1.4 米，再转到 90° 向右 1.4 米处。"),
            stage("两座信标已点亮，返回白色入口标记。",
                "沿来路退回两间房前的大厅。",
                "白色入口在两扇门之间，处于最初的截面上。",
                "离开房间；若从青色房间返回，先转到 90° 向左约 1.4 米，再回到 0° 找白色入口。"),
        ))
        val w0 = -2.2
        val w1 = -1.8
        val walls = listOf(
            box("beacon-gate-left-edge", room.x0 - .2, -2.0, room.y0 - .2, room.y1 + .2, w0, w1),
            box("beacon-gate-middle", -.8, .8, room.y0 - .2, room.y1 + .2, w0, w1),
            box("beacon-gate-right-edge", 2.0, room.x1 + .2, room.y0 - .2, room.y1 + .2, w0, w1),
            box("cyan-door-lower", -2.0, -.8, room.y0 - .2, .8, w0, w1),
            box("cyan-door-upper", -2.0, -.8, 2.0, room.y1 + .2, w0, w1),
            box("amber-door-lower", .8, 2.0, room.y0 - .2, -.6, w0, w1),
            box("amber-door-upper", .8, 2.0, .6, room.y1 + .2, w0, w1),
            box("beacon-room-divider", -.2, .2, room.y0 - .2, room.y1 + .2, room.w0 - .2, w0),
        )
        val landmarks = arch("amber-landmark", -2.0, 1.4, 0.0, amber) +
            arch("cyan-landmark", -2.0, -1.4, 1.4, cyan) +
            box("white-landmark-home", -.5, .5, -.5, .5, -.4, .4, .015, .04, white)
        return level("beacon-home", "09 · 信标归航", mission,
            Sphere4("goal", Vec4.ZERO, .38), white, shell(room) + walls + landmarks)
    }

    private fun level(id: String, title: String, mission: ExpeditionDefinition,
                      goal: Sphere4, color: Int, boxes: List<Box4>) = TutorialLevel(
        id, title.replace(Regex("^\\d+ · "), ""), mission.stages.first().instruction, mission.stages.first().hints.first(),
        goal, color, World4(boxes, mission.checkpoints.map { it.volume } + goal),
        groupId = "movement", groupTitle = "移动", expedition = mission,
    )

    private fun checkpoint(id: String, title: String, center: Vec4, color: Int) =
        MissionCheckpoint(id, title, Sphere4(id, center, .36), color)

    private fun stage(instruction: String, vararg hints: String) = MissionStage(instruction, hints.toList())

    private data class Bounds(val x0: Double, val x1: Double, val y0: Double, val y1: Double,
                              val w0: Double, val w1: Double)

    /** Eight overlapping 4D boundary faces: no outside detour in X, Y, Z or W. */
    private fun shell(b: Bounds): List<Box4> = listOf(
        box("boundary-x-low", b.x0 - .2, b.x0, b.y0 - .2, b.y1 + .2, b.w0 - .2, b.w1 + .2),
        box("boundary-x-high", b.x1, b.x1 + .2, b.y0 - .2, b.y1 + .2, b.w0 - .2, b.w1 + .2),
        box("boundary-y-low", b.x0 - .2, b.x1 + .2, b.y0 - .2, b.y0, b.w0 - .2, b.w1 + .2),
        box("boundary-y-high", b.x0 - .2, b.x1 + .2, b.y1, b.y1 + .2, b.w0 - .2, b.w1 + .2),
        box("boundary-w-low", b.x0 - .2, b.x1 + .2, b.y0 - .2, b.y1 + .2, b.w0 - .2, b.w0),
        box("boundary-w-high", b.x0 - .2, b.x1 + .2, b.y0 - .2, b.y1 + .2, b.w1, b.w1 + .2),
        box("boundary-z-low", b.x0 - .2, b.x1 + .2, b.y0 - .2, b.y1 + .2, b.w0 - .2, b.w1 + .2, low - .2, low),
        box("boundary-z-high", b.x0 - .2, b.x1 + .2, b.y0 - .2, b.y1 + .2, b.w0 - .2, b.w1 + .2, high, high + .2),
    )

    private fun gate(id: String, b: Bounds, w: Double, x: Double, y: Double) = listOf(
        box("$id-left", b.x0 - .2, x - .6, b.y0 - .2, b.y1 + .2, w - .2, w + .2),
        box("$id-right", x + .6, b.x1 + .2, b.y0 - .2, b.y1 + .2, w - .2, w + .2),
        box("$id-lower", x - .6, x + .6, b.y0 - .2, y - .6, w - .2, w + .2),
        box("$id-upper", x - .6, x + .6, y + .6, b.y1 + .2, w - .2, w + .2),
    )

    private fun arch(id: String, w: Double, x: Double, y: Double, color: Int) = listOf(
        box("$id-lintel", x - .6, x + .6, y - .6, y + .6, w + .21, w + .25, 2.12, 2.2, color),
        box("$id-sill", x - .6, x + .6, y - .6, y + .6, w + .21, w + .25, .015, .04, color),
    )

    private fun box(id: String, x0: Double, x1: Double, y0: Double, y1: Double,
                    w0: Double, w1: Double, z0: Double = low - .2, z1: Double = high + .2,
                    color: Int? = null) = Box4(id,
        Vec4((x0 + x1) / 2, (y0 + y1) / 2, (z0 + z1) / 2, (w0 + w1) / 2),
        Vec4((x1 - x0) / 2, (y1 - y0) / 2, (z1 - z0) / 2, (w1 - w0) / 2),
        solid = color == null,
        colorArgb = color ?: when {
            id == "boundary-z-low" -> 0xFF171D20.toInt()
            id == "boundary-z-high" -> 0xFF303A3D.toInt()
            id.startsWith("boundary-") -> 0xFF536064.toInt()
            else -> 0xFFBBC4C3.toInt()
        })
}
