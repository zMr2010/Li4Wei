package com.fourthward.game.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

enum class MotionPuzzle { FOLLOW_LIGHT, DRIFTING_PASSAGE, STAGGERED_GATES }

/** Motion belongs to fixed-size 4D objects. The renderer still consumes only real sections. */
object MotionPuzzles {
    const val periodSeconds = 8.0
    private val amber = 0xFFFFC66D.toInt()
    private val cyan = 0xFF67E8DB.toInt()
    private const val low = -.5
    private const val high = 2.8

    fun levels(): List<TutorialLevel> = listOf(followLight(), driftingPassage(), staggeredGates())

    /** The guide waits at a real landmark until the player arrives. Keeping the clock at
     * that path time makes its next segment continuous even after a long player pause. */
    fun advanceTime(level: TutorialLevel, currentTime: Double, delta: Double, completedIds: Set<String>): Double {
        require(currentTime.isFinite() && currentTime >= 0.0 && delta.isFinite() && delta >= 0.0)
        val next = currentTime + delta
        if (level.motionPuzzle != MotionPuzzle.FOLLOW_LIGHT) return next
        val limit = when {
            "light-amber" !in completedIds -> 6.0
            "light-cyan" !in completedIds -> 14.0
            else -> 15.5
        }
        return next.coerceAtMost(limit)
    }

    fun world(level: TutorialLevel, timeSeconds: Double, completedCheckpointIds: Set<String>): World4 {
        require(timeSeconds.isFinite() && timeSeconds >= 0.0)
        val phase = 2.0 * PI * timeSeconds / periodSeconds
        return when (level.motionPuzzle) {
            MotionPuzzle.FOLLOW_LIGHT -> {
                val time = advanceTime(level, timeSeconds, 0.0, completedCheckpointIds)
                level.world.copy(spheres = level.world.spheres.map {
                    if (it.id == "guide-light") it.copy(center = guidePosition(time)) else it
                })
            }
            MotionPuzzle.DRIFTING_PASSAGE -> level.world.copy(boxes = level.world.boxes.map {
                if (it.id == "drifting-block") it.copy(center = it.center + Vec4.Y * (1.0 - cos(phase))) else it
            })
            MotionPuzzle.STAGGERED_GATES -> level.world.copy(boxes = level.world.boxes.map {
                val offset = when {
                    it.id.startsWith("moving-gate-a-") -> .9 * (1.0 + cos(phase))
                    it.id.startsWith("moving-gate-b-") -> .9 * (1.0 - cos(phase))
                    else -> 0.0
                }
                if (offset == 0.0) it else it.copy(center = it.center + Vec4.Y * offset)
            })
            null -> level.world
        }
    }

    /** The brightest light is above the corresponding ground landmark, not its trigger. */
    fun guidePosition(timeSeconds: Double): Vec4 {
        val path = listOf(
            0.0 to Vec4(0.0, 0.0, .95, -.6),
            2.2 to Vec4(0.0, 1.4, .95, -.6),
            6.0 to Vec4(0.0, 1.4, .95, -2.8),
            10.0 to Vec4(2.4, 1.4, .95, -2.8),
            14.0 to Vec4(2.4, 1.4, .95, -5.4),
            15.5 to Vec4(2.4, 1.4, .95, -6.2),
        )
        val time = timeSeconds.coerceIn(path.first().first, path.last().first)
        val end = path.indexOfFirst { it.first >= time }.coerceAtLeast(1)
        val from = path[end - 1]; val to = path[end]
        return from.second + (to.second - from.second) * ((time - from.first) / (to.first - from.first))
    }

    private fun followLight(): TutorialLevel {
        val room = Bounds(-1.2, 3.6, -1.0, 2.6, -7.0, .9)
        val nodes = listOf(
            checkpoint("light-amber", "金色停靠点", Vec4(0.0, 1.4, 0.0, -2.8), amber),
            checkpoint("light-cyan", "青色停靠点", Vec4(2.4, 1.4, 0.0, -5.4), cyan),
        )
        val mission = ExpeditionDefinition(nodes, true, listOf(
            stage("跟随光球，找到它停留的金色门廊。",
                "光暂时离开了你的截面，仍在同一个空间里移动。",
                "转动切片寻找光；它会在门后等你靠近。",
                "转到 90°，向右约 1.4 米，再向前穿过金色门廊。"),
            stage("光球再次出发，跟上它进入青色门廊。",
                "观察光球离开时的方向，再换一个截面寻找它。",
                "在中间房间转向，找到右侧的青色标记。",
                "回到 0°，向右约 2.4 米，再向前穿过青色门廊。"),
            stage("沿光球指引的方向走到出口。",
                "光球的最后一次停留就在出口。",
                "从青色门廊继续向前。",
                "沿当前通道再向前约 0.8 米，抵达出口标记。"),
        ))
        val walls = shell(room) + gate("light-amber-wall", room, -1.8, 0.0, 1.4) +
            gate("light-cyan-wall", room, -4.6, 2.4, 1.4) +
            arch("light-amber-arch", -1.8, 0.0, 1.4, amber) +
            arch("light-cyan-arch", -4.6, 2.4, 1.4, cyan)
        return level("motion-follow", "追光", MotionPuzzle.FOLLOW_LIGHT, mission,
            Sphere4("goal", Vec4(2.4, 1.4, 0.0, -6.2), .38), walls,
            listOf(Sphere4("guide-light", guidePosition(0.0), .16)))
    }

    private fun driftingPassage(): TutorialLevel {
        val room = Bounds(-1.3, 1.3, -1.2, 2.8, -5.8, .9)
        val nodes = listOf(checkpoint("drift-past", "通道另一侧", Vec4(0.0, 0.0, 0.0, -3.4), cyan))
        val mission = ExpeditionDefinition(nodes, true, listOf(
            stage("观察漂游体的节奏，穿过它让出的通道。",
                "挡路的物体会沿另一个方向来回运动。",
                "在门前稍等，观察它的截面退去，再走向青色标记。",
                "保持 0°，在隔墙前等待开口出现，向前穿过；门后的青色标记距起点约 3.4 米。"),
            stage("穿过漂游体后，沿通道抵达出口。",
                "出口位于通道尽头。",
                "保持在青色标记所在的通道中继续向前。",
                "再向前约 1.6 米，抵达出口标记。"),
        ))
        val tilt = PI / 12.0
        val blocker = Box4("drifting-block", Vec4(0.0, 0.0, 1.15, -2.5),
            Vec4(.9, .65, 1.85, .22),
            basis = listOf(Vec4(cos(tilt), sin(tilt), 0.0, 0.0),
                Vec4(-sin(tilt), cos(tilt), 0.0, 0.0), Vec4.Z, Vec4.W),
            colorArgb = amber)
        return level("motion-drift", "漂游之间", MotionPuzzle.DRIFTING_PASSAGE, mission,
            Sphere4("goal", Vec4(0.0, 0.0, 0.0, -5.0), .38),
            shell(room) + gate("drift-wall", room, -2.5, 0.0, 0.0, .65) +
                arch("drift-arch", -2.5, 0.0, 0.0, cyan, .65) + blocker)
    }

    private fun staggeredGates(): TutorialLevel {
        val room = Bounds(-1.3, 1.3, -1.2, 3.0, -7.0, .9)
        val nodes = listOf(
            checkpoint("gates-middle", "中间休息区", Vec4(0.0, 0.0, 0.0, -3.1), amber),
            checkpoint("gates-far", "青色门后", Vec4(0.0, 0.0, 0.0, -5.5), cyan),
        )
        val mission = ExpeditionDefinition(nodes, true, listOf(
            stage("观察金色门的移动，穿过开口到达中间区域。",
                "门的开口在移动，同一个位置有时连通、有时封闭。",
                "可以在门前等待，也可以转动切片追随开口。",
                "保持 0°，等金色门打开后向前约 3.1 米，到中间的金色标记处停留。"),
            stage("在中间区域观察青色门，寻找下一次通行机会。",
                "两扇门的开放节奏错开了。",
                "中间区域可以停留，先看清青色门的下一次开口。",
                "保持 0°，等青色门打开后再向前约 2.4 米，到青色标记处。"),
            stage("已穿过两道移动门，继续走到出口。",
                "出口就在青色门后。",
                "沿通道继续向前。",
                "再向前约 0.8 米，抵达出口标记。"),
        ))
        return level("motion-gates", "错峰门廊", MotionPuzzle.STAGGERED_GATES, mission,
            Sphere4("goal", Vec4(0.0, 0.0, 0.0, -6.3), .38),
            shell(room) + slidingGate("a", room, -2.0, amber) + slidingGate("b", room, -4.7, cyan))
    }

    private fun level(id: String, title: String, puzzle: MotionPuzzle, mission: ExpeditionDefinition,
                      goal: Sphere4, boxes: List<Box4>, extraSpheres: List<Sphere4> = emptyList()) = TutorialLevel(
        id, title, mission.stages.first().instruction, mission.stages.first().hints.first(),
        goal, cyan, World4(boxes, mission.checkpoints.map { it.volume } + goal + extraSpheres),
        groupId = "motion", groupTitle = "运动", expedition = mission, motionPuzzle = puzzle)

    private fun checkpoint(id: String, title: String, center: Vec4, color: Int) =
        MissionCheckpoint(id, title, Sphere4(id, center, .36), color)
    private fun stage(instruction: String, vararg hints: String) = MissionStage(instruction, hints.toList())
    private data class Bounds(val x0: Double, val x1: Double, val y0: Double, val y1: Double,
                              val w0: Double, val w1: Double)

    /** All eight faces overlap: an opening can never be bypassed outside the building. */
    private fun shell(b: Bounds) = listOf(
        box("boundary-x-low", b.x0 - .2, b.x0, b.y0 - .2, b.y1 + .2, b.w0 - .2, b.w1 + .2),
        box("boundary-x-high", b.x1, b.x1 + .2, b.y0 - .2, b.y1 + .2, b.w0 - .2, b.w1 + .2),
        box("boundary-y-low", b.x0 - .2, b.x1 + .2, b.y0 - .2, b.y0, b.w0 - .2, b.w1 + .2),
        box("boundary-y-high", b.x0 - .2, b.x1 + .2, b.y1, b.y1 + .2, b.w0 - .2, b.w1 + .2),
        box("boundary-w-low", b.x0 - .2, b.x1 + .2, b.y0 - .2, b.y1 + .2, b.w0 - .2, b.w0),
        box("boundary-w-high", b.x0 - .2, b.x1 + .2, b.y0 - .2, b.y1 + .2, b.w1, b.w1 + .2),
        box("boundary-z-low", b.x0 - .2, b.x1 + .2, b.y0 - .2, b.y1 + .2, b.w0 - .2, b.w1 + .2, low - .2, low),
        box("boundary-z-high", b.x0 - .2, b.x1 + .2, b.y0 - .2, b.y1 + .2, b.w0 - .2, b.w1 + .2, high, high + .2),
    )

    private fun gate(id: String, b: Bounds, w: Double, x: Double, y: Double, radius: Double = .6) = listOf(
        box("$id-left", b.x0 - .2, x - radius, b.y0 - .2, b.y1 + .2, w - .2, w + .2),
        box("$id-right", x + radius, b.x1 + .2, b.y0 - .2, b.y1 + .2, w - .2, w + .2),
        box("$id-lower", x - radius, x + radius, b.y0 - .2, y - radius, w - .2, w + .2),
        box("$id-upper", x - radius, x + radius, y + radius, b.y1 + .2, w - .2, w + .2),
    )

    private fun slidingGate(id: String, b: Bounds, w: Double, color: Int): List<Box4> {
        // The moving upper/lower panels have enough overlap beyond the shell for the
        // complete 0..1.8 m travel. No resizing or rotation changes a solid's shape.
        return listOf(
            box("gate-$id-left", b.x0 - .2, -.65, b.y0 - .2, b.y1 + .2, w - .2, w + .2),
            box("gate-$id-right", .65, b.x1 + .2, b.y0 - .2, b.y1 + .2, w - .2, w + .2),
            box("moving-gate-$id-lower", -.65, .65, -5.0, -.65, w - .2, w + .2),
            box("moving-gate-$id-upper", -.65, .65, .65, 6.0, w - .2, w + .2),
        ) + arch("moving-gate-$id-arch", w, 0.0, 0.0, color, .65)
    }

    private fun arch(id: String, w: Double, x: Double, y: Double, color: Int, radius: Double = .6) = listOf(
        box("$id-lintel", x - radius, x + radius, y - radius, y + radius, w + .21, w + .25, 2.12, 2.2, color),
        box("$id-sill", x - radius, x + radius, y - radius, y + radius, w + .21, w + .25, .015, .04, color),
    )

    private fun box(id: String, x0: Double, x1: Double, y0: Double, y1: Double,
                    w0: Double, w1: Double, z0: Double = low - .2, z1: Double = high + .2,
                    color: Int? = null) = Box4(id,
        Vec4((x0 + x1) / 2, (y0 + y1) / 2, (z0 + z1) / 2, (w0 + w1) / 2),
        Vec4((x1 - x0) / 2, (y1 - y0) / 2, (z1 - z0) / 2, (w1 - w0) / 2),
        solid = color == null, colorArgb = color ?: when {
            id == "boundary-z-low" -> 0xFF171D20.toInt()
            id == "boundary-z-high" -> 0xFF303A3D.toInt()
            id.startsWith("boundary-") -> 0xFF536064.toInt()
            else -> 0xFFBBC4C3.toInt()
        })
}
