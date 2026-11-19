package com.fourthward.game.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** A real 3D box in the miniature 2D-in-3D analogy, in model-local coordinates. */
data class LessonBox(val id: String, val center: Vec3, val halfExtents: Vec3)

data class LessonKeyframe(val character: Vec3, val angleRadians: Double = 0.0)

enum class LessonGesture(val hint: String) {
    WALK("面向目标，伸手抓握再回拉。拉得越远走得越远，回拉时转头可转向。"),
    ROTATE_RIGHT("前伸右手并张掌，掌心向前。向右横移增大切片角度，对应 E。"),
    ROTATE_LEFT("前伸右手并张掌，掌心向前。向左横移减小切片角度，对应 Q。"),
    ROTATE("前伸右手并张掌，掌心向前。左移对应 Q，右移对应 E。"),
    ROTATE_THEN_WALK("前伸右手张掌，掌心向前，横移转动切片。面向目标，抓握回拉前进。"),
    PANEL("任一手张开，掌心朝自己或朝上保持 3 秒开面板。把手伸到面板前，握拳关闭。"),
}

data class LessonStep(
    val id: String,
    val title: String,
    val narration: String,
    val durationSeconds: Double,
    val from: LessonKeyframe,
    val to: LessonKeyframe = from,
    val pathBefore: List<Vec3> = emptyList(),
    val blockedAtEnd: Boolean = false,
    val goalReachedAtEnd: Boolean = false,
    val fromMotionSeconds: Double = 0.0,
    val toMotionSeconds: Double = fromMotionSeconds,
    val gesture: LessonGesture? = when {
        to.angleRadians > from.angleRadians -> LessonGesture.ROTATE_RIGHT
        to.angleRadians < from.angleRadians -> LessonGesture.ROTATE_LEFT
        to.character != from.character -> LessonGesture.WALK
        else -> null
    },
) {
    init {
        require(id.matches(Regex("[a-z][a-z0-9_]*")))
        require(title.isNotBlank() && narration.isNotBlank())
        require(durationSeconds.isFinite() && durationSeconds >= 0.0)
        require(fromMotionSeconds.isFinite() && toMotionSeconds.isFinite())
        // Each click demonstrates either a rotation about the character, or an in-plane walk.
        val displacement = to.character - from.character
        if (abs(to.angleRadians - from.angleRadians) > 1e-10) {
            require(displacement.lengthSquared() <= 1e-20) { "Plane rotation cannot move the character" }
        } else {
            val normal = Vec3(-sin(from.angleRadians), 0.0, cos(from.angleRadians))
            require(abs(displacement.dot(normal)) <= 1e-10) { "Character walks must remain in the plane" }
        }
    }
}

data class LessonDefinition(
    val levelIndex: Int,
    val title: String,
    val goal: Vec3,
    val walls: List<LessonBox>,
    val steps: List<LessonStep>,
    val geometryTopic: GeometryTopic? = null,
    val motionTopic: MotionTopic? = null,
) {
    init { require(steps.isNotEmpty() && steps.map { it.id }.distinct().size == steps.size) }
}

/** The original lessons use an external 2D-in-3D analogy. A geometry topic instead selects
 * real 4D objects whose 3D sections are displayed in the miniature at angleRadians. */
data class LessonSnapshot(
    val character: Vec3,
    val goal: Vec3,
    val planeOrigin: Vec3,
    val angleRadians: Double,
    val walls: List<LessonBox>,
    val path: List<Vec3>,
    val blocked: Boolean,
    val goalReached: Boolean,
    val geometryTopic: GeometryTopic? = null,
    val motionTopic: MotionTopic? = null,
    val motionTimeSeconds: Double = 0.0,
) {
    val planeRight: Vec3 get() = Vec3(cos(angleRadians), 0.0, sin(angleRadians))
    val planeNormal: Vec3 get() = Vec3(-sin(angleRadians), 0.0, cos(angleRadians))
    val goalInPlane: Boolean get() = abs((goal - planeOrigin).dot(planeNormal)) <= 0.045
}

/** No timer starts the next step or enters the game. The caller explicitly handles the final button. */
class LessonPlayer(val levelIndex: Int) {
    val definition: LessonDefinition = LessonTutorials.lesson(levelIndex)
    var stepIndex: Int = 0; private set
    var elapsedSeconds: Double = 0.0; private set
    /** Changes on navigation/replay, including replaying the same narrated step. */
    var revision: Long = 0; private set
    val currentStep: LessonStep get() = definition.steps[stepIndex]
    val stepCount: Int get() = definition.steps.size
    val isLastStep: Boolean get() = stepIndex == definition.steps.lastIndex
    val isAnimating: Boolean get() = elapsedSeconds < currentStep.durationSeconds
    val progress: Double get() = if (currentStep.durationSeconds <= 0.0) 1.0
        else (elapsedSeconds / currentStep.durationSeconds).coerceIn(0.0, 1.0)

    val scene: LessonSnapshot get() {
        val step = currentStep
        val t = progress
        val eased = t * t * (3.0 - 2.0 * t)
        val character = step.from.character + (step.to.character - step.from.character) * eased
        val angle = step.from.angleRadians + (step.to.angleRadians - step.from.angleRadians) * eased
        val path = step.pathBefore.toMutableList()
        if ((step.to.character - step.from.character).lengthSquared() > 1e-12) {
            if (path.lastOrNull() != step.from.character) path += step.from.character
            if ((character - step.from.character).lengthSquared() > 1e-12) path += character
        }
        // This point may slide within the same plane while walking. Its normal offset is fixed.
        return LessonSnapshot(character, definition.goal, character, angle, definition.walls,
            path, step.blockedAtEnd && !isAnimating, step.goalReachedAtEnd && !isAnimating,
            definition.geometryTopic, definition.motionTopic,
            step.fromMotionSeconds + (step.toMotionSeconds - step.fromMotionSeconds) * eased)
    }

    fun tick(deltaSeconds: Double, active: Boolean = true): Boolean {
        if (!active || !deltaSeconds.isFinite() || deltaSeconds <= 0.0 || !isAnimating) return false
        elapsedSeconds = (elapsedSeconds + deltaSeconds).coerceAtMost(currentStep.durationSeconds)
        return true
    }

    fun next(): Boolean {
        if (isAnimating || isLastStep) return false
        stepIndex++
        elapsedSeconds = 0.0
        revision++
        return true
    }

    fun previous(): Boolean {
        if (stepIndex == 0) return false
        stepIndex--
        elapsedSeconds = 0.0
        revision++
        return true
    }

    fun replay() { elapsedSeconds = 0.0; revision++ }
    fun restart() { stepIndex = 0; elapsedSeconds = 0.0; revision++ }
}

/** Canonical Chinese scripts. Audio assets are named after these stable step IDs. */
object LessonTutorials {
    private val start = Vec3(-0.65, -0.10, 0.0)
    private val goal = Vec3(0.45, -0.10, 0.0)
    private val blocked = Vec3(-0.12, -0.10, 0.0)
    private val outside = Vec3(-0.12, -0.10, 0.35)
    private val aligned = Vec3(0.45, -0.10, 0.35)
    private val sideGoal = Vec3(-0.65, -0.10, 0.55)
    private val quarter = PI / 2.0
    private fun pose(position: Vec3, angle: Double = 0.0) = LessonKeyframe(position, angle)
    private val walls = listOf(
        LessonBox("left", Vec3(0.0, -0.10, 0.0), Vec3(0.045, 0.395, 0.10)),
        LessonBox("right", Vec3(0.90, -0.10, 0.0), Vec3(0.045, 0.395, 0.10)),
        LessonBox("bottom", Vec3(0.45, -0.45, 0.0), Vec3(0.495, 0.045, 0.10)),
        LessonBox("top", Vec3(0.45, 0.25, 0.0), Vec3(0.495, 0.045, 0.10)),
    )

    private val lessons: List<LessonDefinition> = listOf(
        LessonDefinition(0, "先理解：在切片内移动", goal, emptyList(), listOf(
            LessonStep(
                id = "l1_plane",
                title = "它生活在一个平面里",
                gesture = LessonGesture.PANEL,
                narration = "看这张竖着的平面，它就是小人的整个世界。你可以走到侧面看一看。想打开面板，张开任一只手，掌心朝向自己或朝上，保持三秒。对准按钮，捏合拇指和食指，再松开，就能点击。也可以用食指轻轻戳一下。",
                durationSeconds = 0.0, from = pose(start),
            ),
            LessonStep(
                id = "l1_walk",
                title = "沿着平面走到终点",
                narration = "终点和小人在同一个平面里，顺着这个方向走就能到达。进入游戏后，你也可以这样直走。面向目标，向前伸手，抓住，再把手向胸前拉回来。回拉越远，就走得越远。回拉时转头，就能转向。",
                durationSeconds = 2.4, from = pose(start), to = pose(goal), goalReachedAtEnd = true,
            ),
            LessonStep(
                id = "l1_ready",
                title = "轮到你在三维切片中移动",
                gesture = LessonGesture.WALK,
                narration = "准备好了，就点开始探索。进入游戏后，你会站在四维世界的一张三维切片里。试着面向青色旗帜，抓握回拉，走到旗帜旁。想关闭面板，把任一只手伸到面板前，握一下拳就行。",
                durationSeconds = 0.0, from = pose(goal), pathBefore = listOf(start, goal), goalReachedAtEnd = true,
            ),
        )),
        LessonDefinition(1, "先理解：旋转改变截面", sideGoal, emptyList(), listOf(
            LessonStep(
                id = "l2_hidden",
                title = "终点在平面之外",
                narration = "这次，终点在平面外面。我们能看到它，小人却看不到。只有跟平面相交的部分，才会出现在它的世界里。该怎么把终点找出来呢？",
                durationSeconds = 0.0, from = pose(start),
            ),
            LessonStep(
                id = "l2_rotate",
                title = "只增加一个旋转角度",
                narration = "小人站在原地，平面绕着穿过它的竖直线转动。看，终点出现了。你要转动切片时，向前伸出右手，张开手掌，掌心向前，手背朝自己。保持这个姿势，手向左平移，角度就会减小。向右平移，角度就会增大。",
                durationSeconds = 2.0, from = pose(start), to = pose(start, quarter),
            ),
            LessonStep(
                id = "l2_walk",
                title = "在新的平面里继续走",
                narration = "现在顺着新的平面走，就能到达终点了。记住这个区别：旋转，让你找到新的方向；走动，才会真正改变你的位置。",
                durationSeconds = 2.2, from = pose(start, quarter), to = pose(sideGoal, quarter), goalReachedAtEnd = true,
            ),
            LessonStep(
                id = "l2_ready",
                title = "用一个角度探索第四维",
                gesture = LessonGesture.ROTATE_THEN_WALK,
                narration = "你的三维切片，也只有这一个旋转角度。第二关试着转动切片，找到金色旗帜，再走过去。转头只是四处看看，转动切片要用手势，或者键盘上的 Q 和 E。",
                durationSeconds = 0.0, from = pose(sideGoal, quarter), pathBefore = listOf(start, sideGoal), goalReachedAtEnd = true,
            ),
        )),
        LessonDefinition(2, "先理解：从高维绕过封闭墙", goal, walls, listOf(
            LessonStep(
                id = "l3_closed",
                title = "二维里没有入口",
                narration = "看，终点就在四面墙里面。小人只能在这个平面里走，对它来说，四周都没有入口。先别急着找门。如果把它所在的平面转一下，会发生什么？",
                durationSeconds = 0.0, from = pose(start),
            ),
            LessonStep(
                id = "l3_blocked",
                title = "直接走会被墙挡住",
                narration = "看，走到这里就被挡住了。墙上没有门，小人也不能穿过去。我们先停下来，试着换一个方向。",
                durationSeconds = 1.6, from = pose(start), to = pose(blocked), blockedAtEnd = true,
            ),
            LessonStep(
                id = "l3_rotate_out",
                title = "绕角色转动平面",
                narration = "让小人站着不动，把它所在的平面转九十度。现在，它多了一条原来走不了的路。注意，刚才的旋转并没有把它挪走。",
                durationSeconds = 2.0, from = pose(blocked), to = pose(blocked, quarter), pathBefore = listOf(start, blocked),
            ),
            LessonStep(
                id = "l3_leave",
                title = "走出墙体的厚度",
                narration = "沿着这个方向走一小段，就能走到墙的厚度之外。你从侧面看会更清楚。小人一直待在自己的平面里，只是这个平面已经换了方向。",
                durationSeconds = 1.8, from = pose(blocked, quarter), to = pose(outside, quarter), pathBefore = listOf(start, blocked),
            ),
            LessonStep(
                id = "l3_rotate_parallel",
                title = "转到墙外的一张平面",
                narration = "再绕着小人，把平面转回原来的方向。方向一样了，位置却不同。现在整张平面都在墙外，墙也就不会出现在它的世界里。",
                durationSeconds = 2.0, from = pose(outside, quarter), to = pose(outside), pathBefore = listOf(start, blocked, outside),
            ),
            LessonStep(
                id = "l3_align",
                title = "从墙外绕到终点旁",
                narration = "这条路没有墙，我们可以走到终点旁边。从我们这边看，小人正在绕过围墙。对它自己来说，仍然只是在平面里往前走。",
                durationSeconds = 2.0, from = pose(outside), to = pose(aligned), pathBefore = listOf(start, blocked, outside),
            ),
            LessonStep(
                id = "l3_rotate_back",
                title = "让终点重新进入平面",
                narration = "到这里，再把平面转九十度。看，终点重新出现了！新的平面穿过围墙里面，给我们留出了一条回去的路。你可以走到侧面仔细看看。",
                durationSeconds = 2.0, from = pose(aligned), to = pose(aligned, quarter), pathBefore = listOf(start, blocked, outside, aligned),
            ),
            LessonStep(
                id = "l3_return",
                title = "从另一维回到围墙内部",
                narration = "顺着这条路走回来，就到终点了。我们没有穿过任何一面墙。原来在二维里完全封闭的地方，从三维的另一侧，就能绕进来。",
                durationSeconds = 1.8, from = pose(aligned, quarter), to = pose(goal, quarter), pathBefore = listOf(start, blocked, outside, aligned), goalReachedAtEnd = true,
            ),
            LessonStep(
                id = "l3_ready",
                title = "三维中的封闭，也可以从四维绕过",
                gesture = LessonGesture.ROTATE_THEN_WALK,
                narration = "接下来，把刚才的平面想成你的三维切片，把外面的空间想成四维空间。道理是一样的。第三关有六面墙，把终点完全围住。试着转动切片，走到墙外，再从另一个方向回到里面。",
                durationSeconds = 0.0, from = pose(goal, quarter), pathBefore = listOf(start, blocked, outside, aligned, goal), goalReachedAtEnd = true,
            ),
        )),
        LessonDefinition(3, "先理解：超球的三维截面", Vec3.ZERO, emptyList(), listOf(
            LessonStep(
                id = "l4_small", title = "先看左边：二维世界中的圆片",
                narration = "先看左边。线框是一个完整的三维球，竖直的格子平面是小人的二维世界。小人只能接触平面里亮起的圆片。再看右边：我们看到的球体，也只是四维球被三维空间切出的部分。",
                durationSeconds = 0.0, from = pose(Vec3.ZERO),
            ),
            LessonStep(
                id = "l4_center", title = "圆片变大，对应球体截面变大",
                narration = "两边一起转到四十五度。左边，平面切过球心，圆片变得最大。右边同样如此，三维切片经过四维球的中心，球体截面也变得最大。两个完整的物体都没有变大。",
                durationSeconds = 2.5, from = pose(Vec3.ZERO), to = pose(Vec3.ZERO, PI / 4),
            ),
            LessonStep(
                id = "l4_absent", title = "平面错过球，截面就消失",
                narration = "继续转。左边的圆片先变小，最后消失，但线框球还在那里。二维小人看不见平面外的球。右边也是一样：四维球并没有消失，只是我们的三维切片不再经过它。",
                durationSeconds = 3.2, from = pose(Vec3.ZERO, PI / 4), to = pose(Vec3.ZERO, 3 * PI / 4),
            ),
            LessonStep(
                id = "l4_return", title = "同一个物体，可以有不同截面",
                narration = "转回来，左边的圆片和右边的球体又出现了。你可以走到侧面，看看左边的圆片确实贴在平面里。就像小人只能接触二维截面，我们也只能看到四维物体的三维截面。",
                durationSeconds = 3.0, from = pose(Vec3.ZERO, 3 * PI / 4), to = pose(Vec3.ZERO, PI / 4),
            ),
            LessonStep(
                id = "l4_ready", title = "寻找超球的不同截面",
                gesture = LessonGesture.ROTATE,
                narration = "现在由你来试试。依次把切片转到零度、四十五度和一百三十五度，每个方向停下来看看。把眼前球体的变化，和刚才左边圆片的变化对应起来：切片经过的位置，决定了你能看到的截面。",
                durationSeconds = 0.0, from = pose(Vec3.ZERO, PI / 4),
            ),
        ), geometryTopic = GeometryTopic.HYPERSPHERE),
        LessonDefinition(4, "先理解：超立方体的三维截面", Vec3.ZERO, emptyList(), listOf(
            LessonStep(
                id = "l5_cube", title = "正方形之于立方体，立方体之于超立方体",
                narration = "先看左边。完整的立方体是线框，二维平面切过它，得到一个亮起的正方形。右边多了一个维度：我们看到的立方体，是四维超立方体的三维截面。左边的正方形，对应右边的立方体。",
                durationSeconds = 0.0, from = pose(Vec3.ZERO),
            ),
            LessonStep(
                id = "l5_diagonal", title = "斜着切，两个截面一起变宽",
                narration = "两边一起转到四十五度。左边，平面斜着穿过立方体，正方形变成更宽的长方形。右边也变宽了，立方体截面变成长方体。它们没有被拉伸，只是切入的方向改变了。",
                durationSeconds = 2.8, from = pose(Vec3.ZERO), to = pose(Vec3.ZERO, PI / 4),
            ),
            LessonStep(
                id = "l5_quarter", title = "转过直角，截面恢复原来的形状",
                narration = "转到九十度，左边又切出正方形，右边也回到立方体。你可以绕着模型观察：左边亮起的图形始终在二维平面内，右边的截面则有宽、高和前后深度。",
                durationSeconds = 2.8, from = pose(Vec3.ZERO, PI / 4), to = pose(Vec3.ZERO, PI / 2),
            ),
            LessonStep(
                id = "l5_ready", title = "用三个方向认识同一个物体",
                gesture = LessonGesture.ROTATE,
                narration = "轮到你了。依次观察零度、四十五度和九十度。先记住截面的宽、高和前后深度，再比较旋转后哪些长度变了。想一想，左边长方形变宽的原因，能不能解释眼前长方体的变化？",
                durationSeconds = 0.0, from = pose(Vec3.ZERO, PI / 2),
            ),
        ), geometryTopic = GeometryTopic.TESSERACT),
        LessonDefinition(5, "先理解：相同截面，不同物体", Vec3.ZERO, emptyList(), listOf(
            LessonStep(
                id = "l6_same", title = "相同的截面，能代表相同的整体吗",
                narration = "看左边两个线框盒子，上面在平面外更长，下面更短，但现在切出的两个正方形一样大。二维小人只看这一张截面，分不出差别。右边两个相同的立方体截面，也可能来自不同的四维物体。",
                durationSeconds = 0.0, from = pose(Vec3.ZERO),
            ),
            LessonStep(
                id = "l6_diagonal", title = "换一个方向，隐藏的差别出现了",
                narration = "两边一起转到四十五度。左边两个长方形的宽度开始不同，因为盒子在原来看不到的方向上长短不同。右边的两个三维截面也分出了宽窄，揭示出四维物体隐藏方向上的差别。",
                durationSeconds = 2.8, from = pose(Vec3.ZERO), to = pose(Vec3.ZERO, PI / 4),
            ),
            LessonStep(
                id = "l6_quarter", title = "更多截面，带来更多线索",
                narration = "到了九十度，左边清楚地切出了两个盒子的长短。右边也一样，上面的截面更宽，下面更窄。一次观察相同，不代表完整物体相同。换几个方向，才能得到更多线索。",
                durationSeconds = 2.8, from = pose(Vec3.ZERO, PI / 4), to = pose(Vec3.ZERO, PI / 2),
            ),
            LessonStep(
                id = "l6_ready", title = "比较截面，找出隐藏的差别",
                gesture = LessonGesture.ROTATE,
                narration = "现在比较眼前的两个物体。零度时，先记住它们相同的截面。转到四十五度，再转到九十度，看看差别从哪里出现。试着根据这些不同方向的截面，判断它们在原来看不见的方向上有什么不同。",
                durationSeconds = 0.0, from = pose(Vec3.ZERO, PI / 2),
            ),
        ), geometryTopic = GeometryTopic.AMBIGUOUS_SECTION),
        LessonDefinition(9, "先理解：垂直穿过切片", Vec3.ZERO, emptyList(), listOf(
            LessonStep(
                id = "l10_motion_plane", title = "平面不动，让球走过来",
                narration = "先看左边。这个球在平面外，小人还看不见它。这一次我们不转平面，而是让球朝着平面直直地移动。右边对应的是一个四维球，它也在朝你的三维切片靠近。",
                durationSeconds = 0.0, from = pose(Vec3.ZERO),
            ),
            LessonStep(
                id = "l10_motion_enter", title = "从小圆片到最大的圆片",
                narration = "球碰到平面了。左边先出现一个很小的圆片，随后越来越大，圆心一直留在原处。看看右边，小球截面也在原处变大。不是整个球膨胀了，而是球的不同部位正在穿过切片。",
                durationSeconds = 3.4, from = pose(Vec3.ZERO), fromMotionSeconds = 0.0, toMotionSeconds = 3.0,
            ),
            LessonStep(
                id = "l10_motion_leave", title = "穿过去以后，截面逐渐消失",
                narration = "球继续往前。左边的圆片从最大慢慢缩小，最后消失；右边也是一样。球仍然完整地存在，只是已经走到了切片另一侧。你可以绕到左边模型的侧面，确认球去了哪里。",
                durationSeconds = 3.4, from = pose(Vec3.ZERO), fromMotionSeconds = 3.0, toMotionSeconds = 6.0,
            ),
            LessonStep(
                id = "l10_motion_ready", title = "观察一次完整的穿行",
                gesture = LessonGesture.WALK,
                narration = "接下来，观察眼前四维球的往返运动。先保持切片方向，留意截面出现的位置、最大的时候，以及消失的过程。你也可以走到侧面看一看，再想想左边那个球穿过平面的样子。",
                durationSeconds = 0.0, from = pose(Vec3.ZERO), fromMotionSeconds = 3.0,
            ),
        ), motionTopic = MotionTopic.NORMAL_SPHERE),
        LessonDefinition(10, "先理解：斜着穿过切片", Vec3.ZERO, emptyList(), listOf(
            LessonStep(
                id = "l11_motion_plane", title = "换一条斜向的路线",
                narration = "这次，左边的球不再直直地穿过平面，而是沿一条斜线走。它既向平面靠近，也向旁边移动。右边的四维球沿对应的斜向路线移动。猜一猜，截面会有什么不同？",
                durationSeconds = 0.0, from = pose(Vec3.ZERO),
            ),
            LessonStep(
                id = "l11_motion_enter", title = "大小和位置同时变化",
                narration = "注意左边的圆片：它一边变大，圆心一边向旁边走。右边的小球截面也在变大，同时横向移动。大小变化告诉你球心离切片有多远，圆心移动则来自沿着平面的那部分运动。",
                durationSeconds = 3.4, from = pose(Vec3.ZERO), fromMotionSeconds = 0.0, toMotionSeconds = 3.0,
            ),
            LessonStep(
                id = "l11_motion_leave", title = "继续前进，边走边缩小",
                narration = "球走过最宽的部分后，截面开始缩小，但还在继续向旁边移动。看看两边，大小和位置这两种变化始终对应着。只看大小会漏掉一半线索；把路线也记下来，就更容易理解整个运动。",
                durationSeconds = 3.4, from = pose(Vec3.ZERO), fromMotionSeconds = 3.0, toMotionSeconds = 6.0,
            ),
            LessonStep(
                id = "l11_motion_ready", title = "同时追踪位置与大小",
                gesture = LessonGesture.ROTATE,
                narration = "现在轮到你观察。先看截面从哪里出现，又往哪里移动，再看看它何时最大、何时消失。可以从不同位置观察，也可以转动切片，比较同一条运动路线在不同方向上的样子。",
                durationSeconds = 0.0, from = pose(Vec3.ZERO), fromMotionSeconds = 3.0,
            ),
        ), motionTopic = MotionTopic.OBLIQUE_SPHERE),
        LessonDefinition(11, "先理解：有棱角的物体穿行", Vec3.ZERO, emptyList(), listOf(
            LessonStep(
                id = "l12_motion_plane", title = "换成一个倾斜的盒子",
                narration = "左边上方是一个倾斜的立方体，下方是一个平直的长盒子。它们各自保持朝向，向平面移动。右边对应两种四维盒体的三维截面。我们来比较一下，有棱角的物体经过切片时，会留下什么不同的形状。",
                durationSeconds = 0.0, from = pose(Vec3.ZERO),
            ),
            LessonStep(
                id = "l12_motion_enter", title = "从三角形到六边形",
                narration = "先看上方：左边一个角穿进来，出现了三角形，随后变成六边形；右边对应三棱柱和六棱柱。再看下方，长盒子进入以后，左边一直是长方形，右边一直是长方体。物体不同，运动中的截面变化也会不同。",
                durationSeconds = 3.8, from = pose(Vec3.ZERO), fromMotionSeconds = 0.0, toMotionSeconds = 3.0,
            ),
            LessonStep(
                id = "l12_motion_leave", title = "看着轮廓退出切片",
                narration = "它们继续前进。上方的六边形变回三角形，右边的棱柱也改变了轮廓。下方的长盒子沿斜线移动，截面保持长方形，却向旁边滑动；右边的长方体也是如此。最后，两种截面都离开了视野。",
                durationSeconds = 3.8, from = pose(Vec3.ZERO), fromMotionSeconds = 3.0, toMotionSeconds = 6.0,
            ),
            LessonStep(
                id = "l12_motion_ready", title = "从运动中辨认形状",
                gesture = LessonGesture.WALK,
                narration = "去比较这两种移动的盒体吧。先看上方截面的棱数怎样改变，再跟踪下方长方体的位置。你可以走到侧面观察它们的厚度，把眼前的立体变化和左边平面里的多边形联系起来。",
                durationSeconds = 0.0, from = pose(Vec3.ZERO), fromMotionSeconds = 3.0,
            ),
        ), motionTopic = MotionTopic.MOVING_BOX),
    )

    fun lesson(levelIndex: Int): LessonDefinition {
        return lessons.firstOrNull { it.levelIndex == levelIndex }
            ?: error("No introduction for level index $levelIndex")
    }

    fun hasLesson(levelIndex: Int): Boolean = lessons.any { it.levelIndex == levelIndex }

    fun all(): List<LessonDefinition> = lessons
}
