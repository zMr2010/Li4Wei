package com.fourthward.game.vr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import com.fourthward.game.core.PalmAction
import com.fourthward.game.core.GeometryTopic
import com.fourthward.game.core.MotionTopic
import com.fourthward.game.core.PalmPanelLayout
import com.fourthward.game.core.PalmPanelSpec
import com.fourthward.game.core.PalmPointerPosition
import com.fourthward.game.core.Vec3
import java.util.Locale
import kotlin.math.abs

/** Orthonormal OpenXR world basis; normal is local+Z (front of the panel). */
data class VrPose(val position: Vec3, val right: Vec3, val up: Vec3, val normal: Vec3)

data class VrTutorialStatus(
    val levelIndex: Int, val totalLevels: Int, val title: String,
    val instruction: String, val hint: String, val completed: Boolean,
    val distanceMeters: Double? = null, val blocked: Boolean = false, val keyboardMode: Boolean = false,
    val locomotionLocked: Boolean = false,
    val expedition: Boolean = false,
    val checkpointAvailable: Boolean = false,
    val progressLabel: String? = null,
    val levelCode: String = "",
    val chapterTitle: String = "",
)

data class VrPanelState(
    val referenceVisible: Boolean = true,
    val hoveredAction: PalmAction? = null,
    val pressedAction: PalmAction? = null,
    val pointer: PalmPointerPosition? = null,
    val contextLabel: String = "重试",
    val debugMode: Boolean = false,
    val labels: List<String>? = null,
    val disabled: Set<PalmAction> = emptySet(),
    val header: String? = null,
    val layout: PalmPanelSpec = PalmPanelLayout.lesson,
    val levelItems: List<VrLevelItem> = emptyList(),
    val groupTitle: String? = null,
    val completedCount: Int = 0,
    val groupIndex: Int = 0,
    val groupCount: Int = 1,
    val referenceLabel: String = "参考平面",
    val referenceSelected: Boolean = referenceVisible,
    val chapterItems: List<VrChapterItem> = emptyList(),
) {
    /** Button actions identify visible rows, while each item retains its absolute level index. */
    fun levelItem(action: PalmAction): VrLevelItem? = when (action) {
        PalmAction.LEVEL_1 -> levelItems.getOrNull(0)
        PalmAction.LEVEL_2 -> levelItems.getOrNull(1)
        PalmAction.LEVEL_3 -> levelItems.getOrNull(2)
        PalmAction.LEVEL_4 -> levelItems.getOrNull(3)
        PalmAction.LEVEL_5 -> levelItems.getOrNull(4)
        PalmAction.LEVEL_6 -> levelItems.getOrNull(5)
        else -> null
    }
    fun chapterItem(action: PalmAction): VrChapterItem? = PalmPanelLayout.levelActions.indexOf(action)
        .takeIf { it >= 0 }?.let(chapterItems::getOrNull)
}

data class VrChapterItem(
    val index: Int, val title: String, val completedCount: Int, val totalLevels: Int,
    val unlocked: Boolean, val current: Boolean,
) { val completed: Boolean get() = completedCount == totalLevels }

data class VrLevelItem(
    val index: Int, val title: String, val unlocked: Boolean, val completed: Boolean, val current: Boolean,
    val hasIntroduction: Boolean = true,
    val code: String = (index + 1).toString(),
    val lockedReason: String = "先完成上一关",
)

data class VrLessonStatus(
    val stepIndex: Int, val stepCount: Int, val title: String, val narration: String,
    val animating: Boolean, val voiceLabel: String,
    val geometryTopic: GeometryTopic? = null,
    val anchorPose: VrPose? = null,
    val motionTopic: MotionTopic? = null,
) {
    /** Comparison headings belong to the teaching model, never to the moving headset. */
    fun comparisonLabels(): List<VrComparisonLabel> {
        val anchor = anchorPose ?: return emptyList()
        if (geometryTopic == null && motionTopic == null) return emptyList()
        fun label(center: Vec3, title: String, subtitle: String): VrComparisonLabel {
            val local = center + Vec3(0.0, GeometryLessonLayout.TITLE_HEIGHT, 0.0)
            val pose = anchor.copy(position = anchor.position + anchor.right * local.x +
                anchor.up * local.y + anchor.normal * local.z)
            return VrComparisonLabel(pose, title, subtitle)
        }
        return listOf(
            label(GeometryLessonLayout.LEFT_CENTER, "二维观察三维", "完整三维物体 + 二维截面"),
            label(GeometryLessonLayout.RIGHT_CENTER, "三维观察四维", "只显示真实三维截面"),
        )
    }
}

data class VrComparisonLabel(val pose: VrPose, val title: String, val subtitle: String)

data class VrUiState(
    val angleDegrees: Double,
    val tutorial: VrTutorialStatus,
    val panel: VrPanelState,
    val panelPose: VrPose?,
    val headPose: VrPose,
    val lesson: VrLessonStatus? = null,
    val panelScale: Double = 1.0,
)

/** One cached Android atlas, separate real 3D button quads, no Spatial SDK dependency. */
class VrUiRenderer {
    private data class Region(val x: Int, val y: Int, val width: Int, val height: Int)
    private val angleRegion = Region(0, 0, 300, 72)
    private val comparisonRegions = listOf(Region(320, 0, 340, 72), Region(670, 0, 340, 72))
    private val tutorialRegion = Region(0, 80, 640, 184)
    private val panelRegion = Region(0, 280, 600, 460)
    private val buttonRegions = List(7) { Region(620 + (it % 2) * 600, 280 + (it / 2) * 110, 580, 100) }
    private val pointerRegion = Region(1840, 280, 64, 64)
    private val lessonRegion = Region(0, 768, 900, 240)
    private val lifts = mutableMapOf<PalmAction, Lift>()
    private var lastKey: Any? = null
    private var lastUpload = Long.MIN_VALUE
    private var uploadedLayout: PalmPanelSpec? = null
    private var uploadedGroupIndex: Int? = null
    private data class Lift(var start: Double = 0.0, var target: Double = 0.0, var at: Long = 0L) {
        fun value(now: Long): Double {
            val fraction = ((now - at).coerceAtLeast(0L) / 120_000_000.0).coerceIn(0.0, 1.0)
            val smooth = fraction * fraction * (3.0 - 2.0 * fraction)
            return start + (target - start) * smooth
        }
        fun update(next: Double, now: Long): Double {
            if (next != target) { start = value(now); target = next; at = now }
            return value(now)
        }
    }

    fun render(state: VrUiState, nowNanos: Long): VrRenderFrame {
        val angle = normalized(state.angleDegrees)
        val textAngle = String.format(Locale.ROOT, "%.1f°", if (angle >= 359.95) 0.0 else angle)
        val panelVisible = state.panelPose != null
        val comparisonLabels = state.lesson?.comparisonLabels().orEmpty()
        val layout = state.panel.layout
        require(layout.widthDp <= panelRegion.width && layout.heightDp <= panelRegion.height && layout.buttons.size <= buttonRegions.size)
        val backing = panelRegion.copy(width = layout.widthDp, height = layout.heightDp)
        val key = listOf(textAngle, state.tutorial, state.lesson, if (panelVisible) state.panel else null)
        // A page switch must update its atlas and geometry in the same frame.
        val texture = if (key != lastKey && (lastKey == null || uploadedLayout != layout ||
                uploadedGroupIndex != state.panel.groupIndex || nowNanos - lastUpload >= 100_000_000L)) {
            val bitmap = Bitmap.createBitmap(2048, 1024, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            inRegion(canvas, angleRegion) { drawAngle(it, angle, textAngle, state.lesson != null) }
            comparisonLabels.forEachIndexed { index, label ->
                inRegion(canvas, comparisonRegions[index]) { drawComparisonLabel(it, label) }
            }
            inRegion(canvas, tutorialRegion) { drawTutorial(it, state.tutorial) }
            inRegion(canvas, backing) { drawPanel(it, state.panel) }
            layout.buttons.forEachIndexed { index, button ->
                val region = buttonRegions[index]
                inRegion(canvas, region) {
                    val width = (button.width * layout.widthDp).toFloat()
                    val height = (button.height * layout.heightDp).toFloat()
                    it.scale(region.width / width, region.height / height)
                    drawButton(it, button.action, state.panel, width, height)
                }
            }
            inRegion(canvas, pointerRegion) { drawPointer(it, state.panel) }
            state.lesson?.let { lesson -> inRegion(canvas, lessonRegion) { drawLesson(it, lesson) } }
            lastKey = key
            lastUpload = nowNanos
            uploadedLayout = layout
            uploadedGroupIndex = state.panel.groupIndex
            bitmap
        } else null
        val quads = ArrayList<Float>(21 * 7)
        fun add(region: Region, pose: VrPose, width: Double, height: Double, depth: Boolean) {
            val r = pose.right * width; val u = pose.up * height; val n = pose.normal; val p = pose.position
            quads.addAll(listOf(r.x.toFloat(), r.y.toFloat(), r.z.toFloat(), 0f,
                u.x.toFloat(), u.y.toFloat(), u.z.toFloat(), 0f,
                n.x.toFloat(), n.y.toFloat(), n.z.toFloat(), 0f,
                p.x.toFloat(), p.y.toFloat(), p.z.toFloat(), 1f,
                region.x / 2048f, region.y / 1024f,
                (region.x + region.width) / 2048f, (region.y + region.height) / 1024f,
                if (depth) 1f else 0f))
        }
        add(angleRegion, offset(state.headPose, .50, .52, -1.0), .24, .24 * 72 / 300, false)
        if (state.panel.groupTitle == null) {
            if (state.lesson == null) add(tutorialRegion, offset(state.headPose, 0.0, -.43, -1.2), .58, .58 * 184 / 640, false)
            else {
                val modelAnchor = state.lesson.anchorPose?.takeIf { (state.lesson.geometryTopic != null || state.lesson.motionTopic != null) }
                if (modelAnchor != null) {
                    // Keep the comparison's caption with its models. A head-locked caption
                    // would slide across the fixed controls when leaning or looking down.
                    add(lessonRegion, offset(modelAnchor, 0.0, -.47, 0.0), .75, .75 * 240 / 900, true)
                } else add(lessonRegion, offset(state.headPose, 0.0, -.57, -1.35), .75, .75 * 240 / 900, false)
            }
            comparisonLabels.forEachIndexed { index, label ->
                add(comparisonRegions[index], label.pose, .44, .44 * 72 / 340, true)
            }
        }
        state.panelPose?.let { pose ->
            val scale = state.panelScale.coerceIn(1.0, 2.0)
            val width = layout.widthMeters * scale
            val height = layout.heightMeters * scale
            // Scale depth with the dock so the backing stays behind the pressed face.
            // Physical contact remains at zero regardless of visual animation.
            add(backing, offset(pose, 0.0, 0.0, -.0015 * scale), width, height, true)
            layout.buttons.forEachIndexed { index, button ->
                val target = if (button.action in state.panel.disabled || state.panel.levelItem(button.action)?.unlocked == false) 0.0 else when (button.action) {
                    state.panel.pressedAction -> -2.0
                    state.panel.hoveredAction -> 4.0
                    else -> 0.0
                }
                val lift = lifts.getOrPut(button.action) { Lift(at = nowNanos) }.update(target, nowNanos)
                val buttonPose = offset(pose,
                    ((button.left + button.right) / 2.0 - .5) * width,
                    (.5 - (button.top + button.bottom) / 2.0) * height,
                    lift * width / layout.widthDp)
                add(buttonRegions[index], buttonPose, button.width * width, button.height * height, true)
            }
            validPointer(state.panel)?.let { pointer ->
                val markerWidth = 64.0 * width / layout.widthDp
                add(pointerRegion, offset(pose, (pointer.x - .5) * width, (.5 - pointer.y) * height, .0036 * scale),
                    markerWidth, markerWidth, true)
            }
        }
        return VrRenderFrame(uiBitmap = texture, uiQuads = quads.toFloatArray())
    }

    /** No retained Bitmap: the caller/native uploader owns each non-null frame bitmap. */
    fun close() { lastKey = null; uploadedLayout = null; uploadedGroupIndex = null; lifts.clear() }

    private fun offset(pose: VrPose, x: Double, y: Double, z: Double) =
        pose.copy(position = pose.position + pose.right * x + pose.up * y + pose.normal * z)

    private fun inRegion(canvas: Canvas, region: Region, draw: (Canvas) -> Unit) {
        canvas.save()
        canvas.clipRect(region.x, region.y, region.x + region.width, region.y + region.height)
        canvas.translate(region.x.toFloat(), region.y.toFloat())
        draw(canvas)
        canvas.restore()
    }

    private fun normalized(value: Double) = if (value.isFinite()) ((value % 360.0) + 360.0) % 360.0 else 0.0

    private fun drawAngle(c: Canvas, angle: Double, formatted: String, lesson: Boolean) {
        Ink.card(c, 300f, 72f)
        Ink.text(c, if (lesson) "示意 θ" else "θ", 14f, 29f, Ink.paint(if (lesson) 16f else 20f))
        val value = Ink.paint(24f, bold = true, mono = true)
        Ink.text(c, formatted, 286f - value.measureText(formatted), 29f, value)
        Ink.line(c, 14f, 43f, 286f, Ink.dim)
        if (angle > 0) Ink.line(c, 14f, 43f, 14f + 272f * (angle / 360).toFloat(), Ink.signal)
        Ink.text(c, "0°", 14f, 61f, Ink.paint(12f, Ink.dim))
        Ink.text(c, "360° 周期", 224f, 61f, Ink.paint(12f, Ink.dim))
    }

    private fun drawLesson(c: Canvas, status: VrLessonStatus) {
        Ink.card(c, 900f, 240f)
        Ink.text(c, "讲解 ${status.stepIndex + 1} / ${status.stepCount}", 18f, 34f, Ink.paint(24f, Ink.signal, true))
        Ink.text(c, status.title, 170f, 34f, Ink.paint(27f, bold = true), 710f)
        Ink.line(c, 18f, 47f, 882f, Ink.dim)
        Ink.lines(c, status.narration, 18f, 59f, 864, Ink.paint(25f), 4)
        val hint = if (status.animating) "演示中 · 结束后停住，不会自动翻页" else "可绕着模型观察 · 准备好后点下一步"
        Ink.text(c, hint, 18f, 204f, Ink.paint(18f, Ink.signal), 625f)
        val voice = Ink.paint(17f, Ink.dim)
        Ink.text(c, status.voiceLabel, 882f - voice.measureText(status.voiceLabel), 204f, voice)
        val dock = if (status.geometryTopic == null && status.motionTopic == null) "右侧" else "下方"
        Ink.text(c, "N / 空格 下一步 · B 上一步 · R 重播 · 也可用左手点击${dock}按钮", 18f, 228f, Ink.paint(17f, Ink.dim), 864f)
    }

    private fun drawComparisonLabel(c: Canvas, label: VrComparisonLabel) {
        Ink.card(c, 340f, 72f, alpha = 190)
        val titlePaint = Ink.paint(27f, Ink.signal, true)
        Ink.text(c, label.title, (340f - titlePaint.measureText(label.title)) / 2, 31f, titlePaint, 316f)
        val subtitlePaint = Ink.paint(18f, Ink.ivory)
        Ink.text(c, label.subtitle, (340f - subtitlePaint.measureText(label.subtitle)) / 2, 59f, subtitlePaint, 316f)
    }

    private fun drawTutorial(c: Canvas, status: VrTutorialStatus) {
        Ink.card(c, 640f, 184f)
        val count = status.totalLevels.coerceAtLeast(1)
        val index = status.levelIndex.coerceIn(0, count - 1)
        val signal = if (status.completed) Ink.success else Ink.signal
        Ink.text(c, status.levelCode.ifBlank { "${index + 1} / $count" }, 18f, 32f, Ink.paint(23f, signal, true))
        Ink.text(c, status.title, 94f, 32f, Ink.paint(25f, bold = true), 385f)
        val label = when {
            status.completed -> "已完成"
            status.blocked -> "前方阻挡"
            status.progressLabel != null -> status.progressLabel
            status.distanceMeters?.let { it.isFinite() && it >= 0 } == true ->
                String.format(Locale.ROOT, "距目标 %.1f m", status.distanceMeters)
            status.locomotionLocked -> "观察 · 比较"
            else -> "观察 · 探索"
        }
        val small = Ink.paint(17f, signal)
        Ink.text(c, label, 622f - small.measureText(label), 31f, small)
        Ink.line(c, 18f, 43f, 622f, Ink.dim)
        val progress = (index + if (status.completed) 1f else 0f) / count
        if (progress > 0) Ink.line(c, 18f, 43f, 18f + 604f * progress, signal)
        Ink.lines(c, status.instruction, 18f, 52f, 604, Ink.paint(23f), 2)
        Ink.lines(c, status.hint, 18f, 108f, 604, Ink.paint(18f, Ink.dim), 2)
        val controls = when {
            !status.keyboardMode -> "张手背向自己横移：旋转 · 右掌朝自己：辅助面板"
            status.expedition -> "WASD 移动 · Q/E 旋转 · H 提示 · T ${if (status.checkpointAvailable && !status.completed) "返回路标" else "重试"}"
            status.locomotionLocked -> "Q/E 旋转 · M 面板 · L 关卡列表"
            else -> "WASD 移动 · Q/E 旋转 · M 面板 · L 关卡列表"
        }
        Ink.text(c, controls, 18f, 166f, Ink.paint(16f, Ink.dim), 604f)
    }

    private fun drawPanel(c: Canvas, state: VrPanelState) {
        val width = state.layout.widthDp.toFloat()
        val height = state.layout.heightDp.toFloat()
        Ink.card(c, width, height, alpha = 190)
        val valid = state.pointer?.takeIf { it.x.isFinite() && it.y.isFinite() && it.depthMeters.isFinite() }
        val distance = valid?.let { abs(it.depthMeters) } ?: 0.0
        val cm = if (distance > 9.99) ">999" else String.format(Locale.ROOT, "%.1f", distance * 100)
        val pointerHint = when {
            valid == null -> state.header ?: "左手食指靠近面板"
            !PalmPanelLayout.inPanel(valid.x, valid.y) -> "左指未对准"
            distance > .35 -> "左指距面板 $cm cm"
            else -> "左指 · ${if (valid.depthMeters >= 0) "前" else "后"} $cm cm"
        }
        if (state.chapterItems.isNotEmpty()) {
            Ink.text(c, "章节地图", 20f, 34f, Ink.paint(28f, bold = true))
            Ink.text(c, "先探索移动，再选择新的方向", 20f, 60f, Ink.paint(17f, Ink.dim))
            val connected = state.chapterItems.first().completed
            val path = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (connected) Ink.signal else Ink.dim
                strokeWidth = 2.5f
            }
            c.drawLine(width * .5f, height * .40f, width * .5f, height * .485f, path)
            c.drawLine(width * .26f, height * .485f, width * .74f, height * .485f, path)
            for (x in listOf(.26f, .74f)) {
                c.drawLine(width * x, height * .485f, width * x, height * .57f, path)
                c.drawCircle(width * x, height * .485f, 4f, path)
            }
            Ink.text(c, if (state.debugMode) "1–3 查看章节 · Esc 返回 · Tab / Enter 选择" else pointerHint,
                20f, height - 8f, Ink.paint(16f, Ink.dim), width - 40f)
        } else if (state.groupTitle != null) {
            Ink.text(c, "第 ${state.groupIndex + 1} 章 · ${state.groupTitle}", 20f, 34f, Ink.paint(28f, bold = true), width - 160f)
            val countText = "${state.completedCount} / ${state.levelItems.size} 已完成"
            val countPaint = Ink.paint(17f, Ink.signal)
            Ink.text(c, countText, width - countPaint.measureText(countText) - 20f, 33f, countPaint)
            Ink.text(c, "教程建立直觉 · 挑战运用理解", 20f, 60f, Ink.paint(17f, Ink.dim), width - 40f)
            Ink.text(c, if (state.debugMode) "1–${state.levelItems.size} 选关 · Esc 章节地图 · Tab / Enter 选择" else pointerHint,
                20f, height - 8f, Ink.paint(16f, Ink.dim), width - 40f)
        } else {
            val text = if (state.debugMode) "Tab 选择 · Enter 点击 · M 收起" else pointerHint
            val p = Ink.paint(if (state.debugMode) 20f else 22f)
            Ink.text(c, text, 16f, 32f, p, width - 32f)
            if (state.layout == PalmPanelLayout.game) {
                Ink.text(c, "L 章节地图", 16f, height - 9f, Ink.paint(16f, Ink.dim), width - 32f)
            }
        }
    }

    private fun drawButton(c: Canvas, action: PalmAction, state: VrPanelState, width: Float, height: Float) {
        val item = state.levelItem(action)
        val chapter = state.chapterItem(action)
        val disabled = action in state.disabled || item?.unlocked == false
        val pressed = !disabled && state.pressedAction == action
        val active = !disabled && (state.hoveredAction == action ||
            (state.labels == null && action == PalmAction.TOGGLE_REFERENCE && state.referenceSelected))
        val rect = RectF(1f, 1f, width - 1f, height - 1f)
        c.drawRoundRect(rect, 6f, 6f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (pressed) Ink.signal else if (disabled) Color.argb(215, 32, 34, 34) else Color.argb(240, 25, 25, 25)
        })
        c.drawRoundRect(rect, 6f, 6f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (active || pressed) Ink.signal else if (disabled) Color.rgb(79, 84, 83) else Ink.dim
            style = Paint.Style.STROKE; strokeWidth = if (active) 2.5f else 1.2f
        })
        val foreground = if (disabled) Color.rgb(130, 136, 134) else if (pressed) Ink.black else Ink.ivory
        if (chapter != null) {
            val accent = if (pressed) Ink.black else if (chapter.completed) Ink.success else if (chapter.unlocked) Ink.signal else Ink.dim
            Ink.text(c, "0${chapter.index + 1}  ${chapter.title}", 14f, 31f, Ink.paint(27f, foreground, true), width - 28f)
            val status = when { chapter.completed -> "已完成"; chapter.unlocked -> "可探索"; else -> "待解锁" }
            Ink.text(c, "${chapter.completedCount} / ${chapter.totalLevels} 关 · $status", 14f, 56f, Ink.paint(18f, accent), width - 28f)
            Ink.text(c, if (chapter.unlocked || chapter.completed) "查看关卡" else "完成「移动」后解锁", 14f, height - 13f,
                Ink.paint(16f, if (pressed) Ink.black else Ink.dim), width - 28f)
            return
        }
        if (item != null) {
            val status = when { item.completed -> "已完成"; !item.unlocked -> "未解锁"; else -> "可挑战" }
            val accent = if (disabled) foreground else if (pressed) Ink.black else if (item.completed) Ink.success else Ink.signal
            Ink.text(c, "${item.code} · ${if (item.hasIntroduction) "教程" else "挑战"}", 12f, 22f,
                Ink.paint(17f, accent, true), width - 86f)
            val statusPaint = Ink.paint(14f, accent)
            Ink.text(c, status, width - statusPaint.measureText(status) - 12f, 21f, statusPaint)
            Ink.text(c, item.title.substringAfter(" · ", item.title), 12f, 47f,
                Ink.paint(22f, foreground, true), width - 24f)
            val detail = when {
                !item.unlocked -> item.lockedReason
                item.current && item.completed -> "当前关卡 · 可重玩"
                item.current -> "当前关卡"
                item.completed -> "可重新挑战"
                item.hasIntroduction -> "立体讲解 · 实际观察"
                else -> "进入探索"
            }
            Ink.text(c, detail, 12f, height - 10f, Ink.paint(14f, if (pressed) Ink.black else foreground), width - 24f)
            if (item.current) c.drawRect(1f, 10f, 4f, height - 10f, Paint().apply { color = if (pressed) Ink.black else Ink.signal })
            return
        }
        val label = state.labels?.getOrNull(state.layout.buttons.indexOfFirst { it.action == action }) ?: when (action) {
            PalmAction.RESET_ANGLE -> "θ归零"
            PalmAction.TOGGLE_REFERENCE -> state.referenceLabel
            PalmAction.CONTEXT -> state.contextLabel
            PalmAction.LEVEL_LIST -> "章节地图"
            PalmAction.BACK -> if (state.levelItems.isNotEmpty()) "章节地图" else "返回"
            PalmAction.LEVEL_1 -> "第一关"
            PalmAction.LEVEL_2 -> "第二关"
            PalmAction.LEVEL_3 -> "第三关"
            PalmAction.LEVEL_4 -> "第四关"
            PalmAction.LEVEL_5 -> "第五关"
            PalmAction.LEVEL_6 -> "第六关"
            PalmAction.PREVIOUS_GROUP -> "‹"
            PalmAction.NEXT_GROUP -> "›"
        }
        val p = Ink.paint(if (width < 140f) 25f else 27f, foreground, true)
        Ink.text(c, label, maxOf(6f, (width - p.measureText(label)) / 2), (height - p.ascent() - p.descent()) / 2, p, width - 12f)
    }

    private fun validPointer(state: VrPanelState) = state.pointer?.takeIf {
        !state.debugMode && PalmPanelLayout.inPanel(it.x, it.y) && it.depthMeters.isFinite() && abs(it.depthMeters) <= .35
    }

    private fun drawPointer(c: Canvas, state: VrPanelState) {
        val pointer = validPointer(state) ?: return
        val distance = abs(pointer.depthMeters)
        val radius = (7.0 + 11.0 * (distance / .08).coerceIn(0.0, 1.0)).toFloat()
        val x = 32f
        val y = 32f
        val color = if (distance <= .008) Ink.signal else Ink.ivory
        c.drawCircle(x, y, radius + 2, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Ink.black; style = Paint.Style.STROKE; strokeWidth = 5f })
        c.drawCircle(x, y, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.STROKE; strokeWidth = 2f })
        c.drawCircle(x, y, if (distance <= .008) 5f else 2.5f, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
    }
}

private object Ink {
    val ivory = Color.rgb(242, 242, 240)
    val signal = Color.rgb(255, 250, 0)
    val success = Color.rgb(154, 227, 177)
    val black = Color.rgb(25, 25, 25)
    val dim = Color.rgb(177, 181, 179)
    fun card(c: Canvas, width: Float, height: Float, alpha: Int = 210) {
        val rect = RectF(.5f, .5f, width - .5f, height - .5f)
        c.drawRoundRect(rect, 12f, 12f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(alpha, 25, 25, 25) })
        c.drawRoundRect(rect, 12f, 12f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(80, 242, 242, 240); style = Paint.Style.STROKE; strokeWidth = 1f
        })
    }
    fun paint(size: Float, color: Int = ivory, bold: Boolean = false, mono: Boolean = false) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size; this.color = color
        typeface = Typeface.create(if (mono) "monospace" else "sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
    }
    fun text(c: Canvas, text: String, x: Float, baseline: Float, p: TextPaint, width: Float = Float.POSITIVE_INFINITY) {
        val fitted = if (width.isFinite()) TextUtils.ellipsize(text, p, width.coerceAtLeast(0f), TextUtils.TruncateAt.END).toString() else text
        c.drawText(fitted, x, baseline, p)
    }
    fun lines(c: Canvas, text: String, x: Float, top: Float, width: Int, p: TextPaint, lines: Int) {
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, p, width).setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false).setMaxLines(lines).setEllipsize(TextUtils.TruncateAt.END).build()
        c.save(); c.translate(x, top); layout.draw(c); c.restore()
    }
    fun line(c: Canvas, x: Float, y: Float, end: Float, color: Int) {
        c.drawLine(x, y, end, y, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; strokeWidth = 2f })
    }
}
