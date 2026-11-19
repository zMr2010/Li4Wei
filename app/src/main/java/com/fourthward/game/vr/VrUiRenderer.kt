package com.fourthward.game.vr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.text.LineBreaker
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import com.fourthward.game.core.PalmAction
import com.fourthward.game.core.LevelMapWindow
import com.fourthward.game.core.LessonGesture
import com.fourthward.game.core.GeometryTopic
import com.fourthward.game.core.MotionTopic
import com.fourthward.game.core.PalmPanelLayout
import com.fourthward.game.core.PalmPanelSpec
import com.fourthward.game.core.PalmPointerPosition
import com.fourthward.game.core.UiThemeMode
import com.fourthward.game.core.Vec3
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

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
    val gesture: LessonGesture? = null,
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
    val map: VrLevelMapState? = null,
    val startLabel: String? = null,
    val dwellProgress: Double = 0.0,
    val theme: UiThemeMode = UiThemeMode.LIGHT,
) {
    /** Button actions identify visible rows, while each item retains its absolute level index. */
    fun levelItem(action: PalmAction): VrLevelItem? = PalmPanelLayout.levelActions.indexOf(action)
        .takeIf { it >= 0 }?.let(levelItems::getOrNull)
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

data class VrMapNode(val index: Int, val row: Int, val column: Double, val completed: Boolean, val unlocked: Boolean, val width: Double = .27)
data class VrMapEdge(val fromIndex: Int, val toIndex: Int)
data class VrMapSegment(
    val fromIndex: Int, val toIndex: Int, val x1: Double, val y1: Double, val x2: Double, val y2: Double,
    val completed: Boolean, val available: Boolean,
)

/** Display geometry comes from the same dependency graph as progression, never from chapter order. */
data class VrLevelMapState(
    val nodes: List<VrMapNode>, val edges: List<VrMapEdge>, val firstRow: Int,
    val totalRows: Int, val totalCompleted: Int, val totalLevels: Int,
    val visibleRows: Int = LevelMapWindow.VISIBLE_ROWS,
) {
    fun centerY(row: Int): Double = LevelMapWindow.centerY(row, firstRow)
    val clipTop: Double get() = maxOf(TOP,
        centerY(firstRow + visibleRows - 1) - LevelMapWindow.NODE_HEIGHT / 2 - 8.0 / LevelMapWindow.HEIGHT_DP)

    /** Orthogonal routes clipped at the viewport, before conversion to world-space quads. */
    fun segments(): List<VrMapSegment> {
        val byIndex = nodes.associateBy { it.index }
        return edges.flatMap { edge ->
            val from = byIndex[edge.fromIndex] ?: return@flatMap emptyList()
            val to = byIndex[edge.toIndex] ?: return@flatMap emptyList()
            val startY = centerY(from.row) - LevelMapWindow.NODE_HEIGHT / 2
            val endY = centerY(to.row) + LevelMapWindow.NODE_HEIGHT / 2
            val middle = (startY + endY) * .5
            val points = if (abs(from.column - to.column) < .00001) listOf(from.column to startY, to.column to endY)
                else listOf(from.column to startY, from.column to middle, to.column to middle, to.column to endY)
            points.zipWithNext().mapNotNull { (a, b) ->
                val (x1, y1) = a; val (x2, y2) = b
                if (maxOf(y1, y2) < clipTop || minOf(y1, y2) > BOTTOM) return@mapNotNull null
                val clippedY1 = y1.coerceIn(clipTop, BOTTOM); val clippedY2 = y2.coerceIn(clipTop, BOTTOM)
                if (abs(x1 - x2) < .00001 && abs(clippedY1 - clippedY2) < .00001) return@mapNotNull null
                VrMapSegment(from.index, to.index, x1, clippedY1, x2, clippedY2,
                    from.completed && to.completed, from.completed && to.unlocked)
            }
        }
    }

    companion object { const val TOP = LevelMapWindow.CONTENT_TOP; const val BOTTOM = LevelMapWindow.CONTENT_BOTTOM }
}

data class VrLessonStatus(
    val stepIndex: Int, val stepCount: Int, val title: String, val narration: String,
    val animating: Boolean, val voiceLabel: String,
    val geometryTopic: GeometryTopic? = null,
    val anchorPose: VrPose? = null,
    val motionTopic: MotionTopic? = null,
    val gesture: LessonGesture? = null,
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
    val active: Boolean = true,
    val pageRevision: Long = 0L,
    val feedbackRevision: Long = 0L,
    val theme: UiThemeMode = panel.theme,
)

internal data class VrAtlasRegion(val x: Int, val y: Int, val width: Int, val height: Int)
private typealias Region = VrAtlasRegion

// Enlarge the captions in tracking space so even paragraphs fitted to their tile
// retain their line breaks and get the same increase in apparent type size.
private const val CAPTION_SCALE = 1.5

/** Tiles start on 8-texel boundaries and have a full gutter texel at the coarsest mip (level 3). */
internal object VrUiAtlas {
    val angle = Region(0, 0, 300, 72)
    val comparisons = listOf(Region(320, 0, 340, 72), Region(672, 0, 340, 72))
    val tutorial = Region(0, 80, 640, 184)
    val startTitle = Region(656, 88, 600, 180)
    val panel = Region(0, 280, LevelMapWindow.WIDTH_DP, LevelMapWindow.HEIGHT_DP)
    val buttons = List(12) { Region(616 + (it % 4) * 352, 280 + (it / 4) * 152, 340, 140) } +
        Region(1552, 768, 340, 140)
    val pointer = Region(1496, 0, 64, 64)
    val lesson = Region(616, 768, 900, 240)
    // A one-texel swatch gives all four quad corners the same texel-center UV.
    // Bevels and subpixel route lines therefore always sample their exact solid color.
    private fun swatch(x: Int, y: Int) = Region(x, y, 1, 1)
    val yellow = swatch(1040, 0)
    val white = swatch(1056, 0)
    val mint = swatch(1072, 0)
    val base = swatch(1040, 16)
    val bevel = swatch(1056, 16)
    val graphite = swatch(1072, 16)
    val availableRoute = swatch(1088, 24)
    val scan = Region(1104, 0, 128, 16)
    val glow = Region(1272, 0, 96, 96)
    val mintGlow = Region(1376, 0, 96, 96)
    val all = listOf(angle, tutorial, startTitle, panel, pointer, lesson, yellow, white, mint, base, bevel, graphite, availableRoute, scan, glow, mintGlow) + comparisons + buttons
}

/** One cached Android atlas, separate real 3D button quads, no Spatial SDK dependency. */
class VrUiRenderer {
    private val angleRegion = VrUiAtlas.angle
    private val comparisonRegions = VrUiAtlas.comparisons
    private val tutorialRegion = VrUiAtlas.tutorial
    private val panelRegion = VrUiAtlas.panel
    private val buttonRegions = VrUiAtlas.buttons
    private val pointerRegion = VrUiAtlas.pointer
    private val lessonRegion = VrUiAtlas.lesson
    private val motion = UiMotionState()
    private val buttonMotion = mutableMapOf<PalmAction, UiButtonMotion>()
    private var focusMotion = UiFocusMotion()
    private val angleTrail = UiAngleTrail()
    private var displayedPanel: VrPanelState? = null
    private var displayedPose: VrPose? = null
    private var displayedScale = 1.0
    private var displayedPageKey: String? = null
    private var desiredPageKey = ""
    private var desiredPageRevision = 0L
    private var lastKey: Any? = null
    private var theme = UiThemeMode.LIGHT
    private var lastUpload = Double.NEGATIVE_INFINITY
    private var uploadedPage: String? = null
    private var uploadedMapRow: Int? = null
    private var captionKey: Any? = null
    private var captionAt = 0.0
    private var completionKey: Any? = null
    private var completedAt = Double.NEGATIVE_INFINITY
    private val yellowRegion = VrUiAtlas.yellow
    private val whiteRegion = VrUiAtlas.white
    private val mintRegion = VrUiAtlas.mint
    private val baseRegion = VrUiAtlas.base
    private val bevelRegion = VrUiAtlas.bevel
    private val scanRegion = VrUiAtlas.scan
    private val glowRegion = VrUiAtlas.glow
    private val mintGlowRegion = VrUiAtlas.mintGlow

    /** Gate hand contacts only; debug keyboard commands can interrupt any transition. */
    fun interactionReady(pageRevision: Long): Boolean = pageRevision == desiredPageRevision && motion.interactionReady(desiredPageKey)

    // Dragging scrolls within the existing map. Button/keyboard page changes increment pageRevision.
    private fun pageKey(state: VrUiState): String = if (state.panel.map != null) "map|${state.pageRevision}"
        else listOf(state.pageRevision, state.panel.layout, state.panel.groupTitle, state.panel.groupIndex).joinToString("|")

    fun render(state: VrUiState, nowNanos: Long): VrRenderFrame {
        desiredPageKey = pageKey(state)
        desiredPageRevision = state.pageRevision
        val movement = motion.update(nowNanos, state.active, state.panelPose != null, desiredPageKey, state.panel.layout.buttons.size)
        val time = movement.clock
        if (displayedPageKey != movement.pageKey) {
            // The old layer has reached zero alpha; upload the new page on this same frame.
            displayedPageKey = movement.pageKey
            displayedPanel = state.panel
            displayedPose = state.panelPose ?: displayedPose
            displayedScale = state.panelScale
            buttonMotion.clear()
            focusMotion = UiFocusMotion()
        } else if (movement.pageKey == desiredPageKey) {
            displayedPanel = state.panel
            if (state.panelPose != null) displayedPose = state.panelPose
            displayedScale = state.panelScale
        }
        val panel = displayedPanel ?: state.panel
        val visible = movement.panelOpacity > .001
        val layout = panel.layout
        require(layout.widthDp <= panelRegion.width && layout.heightDp <= panelRegion.height && layout.buttons.size <= buttonRegions.size)
        val backing = panelRegion.copy(width = layout.widthDp, height = layout.heightDp)
        val angle = normalized(state.angleDegrees)
        val trail = angleTrail.update(angle, time)
        val textAngle = String.format(Locale.ROOT, "%.1f°", if (angle >= 359.95) 0.0 else angle)
        val comparisonLabels = state.lesson?.comparisonLabels().orEmpty()
        val nextCaptionKey = listOf(state.tutorial.levelCode, state.tutorial.title, state.tutorial.levelIndex, state.lesson?.stepIndex, state.lesson?.title,
            state.tutorial.completed, state.tutorial.progressLabel)
        val captionChanged = captionKey != nextCaptionKey
        if (captionChanged) { captionKey = nextCaptionKey; captionAt = time }
        val nextCompletion = Triple(state.tutorial.levelCode, state.tutorial.title, state.tutorial.completed)
        if (completionKey != nextCompletion) {
            if (state.tutorial.completed) completedAt = time
            completionKey = nextCompletion
        }
        // Position and dwell are live quads. Only the pointer's ink (contact or hover radius)
        // affects the atlas; a steady hand with submillimetre jitter should not rebuild 8 MB.
        val cursorInk = validPointer(panel)?.let {
            val depth = abs(it.depthMeters)
            val radiusStep = if (depth <= .008) 0.0 else kotlin.math.ceil((depth.coerceAtMost(.08) / .004)) * .004
            PalmPointerPosition(.5, .5, radiusStep)
        }
        val themeChanged = theme != state.theme
        val inkPanel = panel.copy(pointer = cursorInk, dwellProgress = 0.0, theme = state.theme)
        val key = listOf(textAngle, state.tutorial, state.lesson, inkPanel)
        val texture = if (key != lastKey && (lastKey == null || themeChanged || captionChanged || uploadedPage != movement.pageKey ||
                uploadedMapRow != panel.map?.firstRow || time - lastUpload >= .10)) {
            theme = state.theme
            val bitmap = Bitmap.createBitmap(2048, 1024, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            inRegion(canvas, angleRegion) { drawAngle(it, textAngle, state.lesson != null) }
            comparisonLabels.forEachIndexed { index, label ->
                inRegion(canvas, comparisonRegions[index]) { drawComparisonLabel(it, label) }
            }
            inRegion(canvas, tutorialRegion) { drawTutorial(it, state.tutorial) }
            if (panel.startLabel != null) inRegion(canvas, VrUiAtlas.startTitle) { WelcomeRouteArt.title(it, theme) }
            inRegion(canvas, backing) { drawPanel(it, inkPanel) }
            layout.buttons.forEachIndexed { index, button ->
                val region = buttonRegions[index]
                inRegion(canvas, region) {
                    val width = (button.width * layout.widthDp).toFloat()
                    val height = (button.height * layout.heightDp).toFloat()
                    it.scale(region.width / width, region.height / height)
                    drawButton(it, button.action, inkPanel, width, height)
                }
            }
            inRegion(canvas, pointerRegion) { drawPointer(it, inkPanel) }
            state.lesson?.let { lesson -> inRegion(canvas, lessonRegion) { drawLesson(it, lesson, state.tutorial.keyboardMode) } }
            drawMotionAssets(canvas)
            inRegion(canvas, VrUiAtlas.availableRoute) {
                it.drawColor(if (theme == UiThemeMode.LIGHT) 0xff7b5700.toInt() else theme.palette.accent)
            }
            lastKey = key; lastUpload = time; uploadedPage = movement.pageKey; uploadedMapRow = panel.map?.firstRow
            bitmap
        } else null
        val quads = ArrayList<Float>(21 * 80)
        fun add(region: Region, pose: VrPose, width: Double, height: Double, opacity: Double = 1.0) {
            if (opacity <= .001 || width <= .000001 || height <= .000001) return
            val r = pose.right * width; val u = pose.up * height; val n = pose.normal; val p = pose.position
            quads.addAll(listOf(r.x.toFloat(), r.y.toFloat(), r.z.toFloat(), 0f,
                u.x.toFloat(), u.y.toFloat(), u.z.toFloat(), 0f,
                n.x.toFloat(), n.y.toFloat(), n.z.toFloat(), 0f,
                p.x.toFloat(), p.y.toFloat(), p.z.toFloat(), 1f,
                (region.x + .5f) / 2048f, (region.y + .5f) / 1024f,
                (region.x + region.width - .5f) / 2048f, (region.y + region.height - .5f) / 1024f,
                opacity.coerceIn(0.0, 1.0).toFloat()))
        }
        fun line(pose: VrPose, x1: Double, y1: Double, x2: Double, y2: Double,
                 thickness: Double, region: Region = yellowRegion, opacity: Double = 1.0) {
            val dx = x2 - x1; val dy = y2 - y1
            val length = kotlin.math.sqrt(dx * dx + dy * dy)
            if (length < .00001) return
            val at = offset(pose, (x1 + x2) * .5, (y1 + y2) * .5, .00035)
            add(region, at.copy(right = pose.right * (dx / length) + pose.up * (dy / length),
                up = pose.right * (-dy / length) + pose.up * (dx / length)), length, thickness, opacity)
        }
        fun corners(pose: VrPose, width: Double, height: Double, reach: Double, opacity: Double,
                    region: Region = yellowRegion) {
            for (sx in listOf(-1.0, 1.0)) for (sy in listOf(-1.0, 1.0)) {
                val x = sx * width / 2; val y = sy * height / 2
                line(pose, x, y, x - sx * reach, y, .00065, region, opacity)
                line(pose, x, y, x, y - sy * reach, .00065, region, opacity)
            }
        }

        // The numeric reading is atlas-cached; the actual bar and its soft trail update at XR frequency.
        if (state.panel.map == null) {
            val anglePose = offset(state.headPose, .40, .26, -1.0)
            val angleWidth = .24
            val angleHeight = angleWidth * 72 / 300
            add(angleRegion, anglePose, angleWidth, angleHeight)
            val barY = (.5 - 43.0 / 72) * angleHeight
            val barLeft = (14.0 / 300 - .5) * angleWidth
            val barWidth = 272.0 / 300 * angleWidth
            line(anglePose, barLeft, barY, barLeft + barWidth * angle / 360, barY, .0010, yellowRegion, .9)
            if (abs(trail - angle) < 45) line(anglePose, barLeft + barWidth * trail / 360, barY,
                barLeft + barWidth * angle / 360, barY, .0021, yellowRegion, .18)
            val activity = (abs(trail - angle) / 4.0).coerceIn(0.0, 1.0)
            add(glowRegion, offset(anglePose, barLeft + barWidth * angle / 360, barY, .0005), .012, .012, .16 + .35 * activity)
        }

        val captionAlpha = UiMotionTokens.smooth((time - captionAt) / .28)
        val captionOffset = -.009 * (1.0 - UiMotionTokens.out((time - captionAt) / .28))
        if (state.panel.groupTitle == null) {
            if (state.lesson == null) {
                val width = .58 * CAPTION_SCALE
                val height = width * tutorialRegion.height / tutorialRegion.width
                val at = offset(state.headPose, 0.0, -.347 - height / 2 + captionOffset, -1.2)
                add(tutorialRegion, at, width, height, captionAlpha)
                val age = time - completedAt
                if (age in 0.0..1.6 && state.tutorial.completed) {
                    val reveal = UiMotionTokens.out(age / .38)
                    val fade = (1.0 - ((age - .75) / .85).coerceIn(0.0, 1.0))
                    corners(offset(at, 0.0, 0.0, .001), width + .018 * reveal,
                        height + .012 * reveal, .022 * reveal, fade * .85, mintRegion)
                    // Two fine traces meet in the centre, then settle; the world and camera stay still.
                    val y = height / 2 + .009
                    val reach = width / 3
                    line(at, -reach, y, -reach + reach * reveal, y, .0012, mintRegion, fade)
                    line(at, reach, y, reach - reach * reveal, y, .0012, mintRegion, fade)
                }
            } else {
                val modelAnchor = state.lesson.anchorPose?.takeIf { state.lesson.geometryTopic != null || state.lesson.motionTopic != null }
                val width = .75 * CAPTION_SCALE
                val height = width * lessonRegion.height / lessonRegion.width
                // Keep the top edge below the models. The first three lessons have controls on the right.
                val at = if (modelAnchor != null) offset(modelAnchor, 0.0, -.21 - height / 2 + captionOffset, 0.0)
                    else offset(state.headPose, -.17, -.24 - height / 2 + captionOffset, -1.35)
                add(lessonRegion, at, width, height, captionAlpha)
                val sweep = ((time - captionAt) / .65).coerceIn(0.0, 1.0)
                if (sweep < 1.0) {
                    val left = -.48 * width
                    val y = height / 2 - .002
                    line(at, left, y, left + width * .96 * UiMotionTokens.out(sweep), y, .0007, yellowRegion, (1.0 - sweep) * .7)
                }
            }
            comparisonLabels.forEachIndexed { index, label ->
                val alpha = UiMotionTokens.smooth((time - captionAt - index * .06) / .30)
                add(comparisonRegions[index], label.pose, .44, .44 * 72 / 340, alpha)
            }
        }
        if (visible) displayedPose?.let { pose ->
            val scale = displayedScale.coerceIn(1.0, 2.0)
            val width = layout.widthMeters * scale * movement.panelScale
            val height = layout.heightMeters * scale * movement.panelScale
            val panelPose = offset(pose, 0.0, movement.panelOffsetY, movement.panelOffsetNormal)
            val opacity = movement.panelOpacity
            if (panel.startLabel != null) {
                val title = VrUiAtlas.startTitle
                val titleHeight = width * title.height / title.width
                add(title, offset(panelPose, 0.0, (height + titleHeight) / 2 + .011, -.004),
                    width, titleHeight, opacity)
                // Plate thickness is visible when the player leans to either side.
                val depth = .009
                val side = offset(panelPose, 0.0, 0.0, -.0015 * scale - depth / 2)
                for (sign in listOf(-1.0, 1.0)) {
                    val vertical = offset(side, sign * width / 2, 0.0, 0.0)
                    add(VrUiAtlas.graphite, vertical.copy(right = pose.normal, normal = pose.right * sign),
                        depth, height, opacity)
                    val horizontal = offset(side, 0.0, sign * height / 2, 0.0)
                    add(bevelRegion, horizontal.copy(up = pose.normal, normal = pose.up * sign),
                        width, depth, opacity)
                }
            }
            add(backing, offset(panelPose, 0.0, 0.0, -.0015 * scale), width, height, opacity)
            // A quiet aperture forms before the content, echoing the reference HUD's layered reveal.
            if (panel.startLabel == null) corners(offset(panelPose, 0.0, 0.0, -.0012 * scale), width - .001, height - .001,
                .012 * UiMotionTokens.out(movement.entryAge / .3), opacity * .50)
            layout.buttons.forEachIndexed { index, button ->
                val enabled = button.action !in panel.disabled && panel.levelItem(button.action)?.unlocked != false && panel.chapterItem(button.action)?.unlocked != false
                val response = buttonMotion.getOrPut(button.action) { UiButtonMotion() }.update(time,
                    panel.hoveredAction == button.action, panel.pressedAction == button.action, enabled, state.feedbackRevision)
                val alpha = movement.cardOpacity(index)
                val buttonAnchor = offset(panelPose,
                    ((button.left + button.right) / 2.0 - .5) * width,
                    (.5 - (button.top + button.bottom) / 2.0) * height + movement.cardOffsetY(index),
                    movement.cardOffsetNormal(index))
                val buttonPose = offset(buttonAnchor, 0.0, 0.0, response.liftMeters)
                val faceScale = 1.0 - .02 * response.press
                val bw = button.width * width * faceScale; val bh = button.height * height * faceScale
                val baseDepth = -.0011 * scale
                val baseWidth = button.width * width + .0012 * scale
                val baseHeight = button.height * height + .0012 * scale
                add(baseRegion, offset(buttonAnchor, 0.0, 0.0, baseDepth), baseWidth, baseHeight, alpha * .75)
                // These four strips are real side planes, joining a stationary recessed base
                // to the lifted face. Their changing parallax is visible when viewed obliquely.
                val rise = response.liftMeters - baseDepth
                val insetX = (baseWidth - bw) / 2.0
                val insetY = (baseHeight - bh) / 2.0
                fun bevel(centerX: Double, centerY: Double, along: Vec3, across: Vec3, length: Double, shade: Double) {
                    val depth = across.length()
                    val vertical = across * (1.0 / depth)
                    val center = offset(buttonAnchor, centerX, centerY, (baseDepth + response.liftMeters) / 2.0)
                    val sidePose = center.copy(right = along, up = vertical, normal = along.cross(vertical))
                    add(bevelRegion, sidePose, length, depth, alpha * shade)
                }
                for (side in listOf(-1.0, 1.0)) {
                    bevel(side * (bw / 2 + insetX / 2), 0.0, panelPose.up,
                        panelPose.normal * rise - panelPose.right * (side * insetX), bh, .46)
                    bevel(0.0, side * (bh / 2 + insetY / 2), panelPose.right,
                        panelPose.normal * rise - panelPose.up * (side * insetY), bw, if (side > 0) .64 else .34)
                }
                add(buttonRegions[index], buttonPose, bw, bh, alpha)
                if (response.hover > .001) {
                    line(buttonPose, -bw * .46, bh * .5 - .001, bw * .46, bh * .5 - .001,
                        .0007 * scale, yellowRegion, response.hover * alpha * .8)
                    // Concentrated glow under the raised face communicates its physical depth.
                    add(glowRegion, offset(panelPose,
                        ((button.left + button.right) / 2 - .5) * width,
                        (.5 - button.bottom) * height, -.0010 * scale), bw * .65, .014 * scale,
                        response.hover * alpha * .20)
                }
                if (response.sweep < 1.0) {
                    val progress = UiMotionTokens.out(response.sweep)
                    val sweepWidth = bw * .22
                    val x = -bw * .5 + sweepWidth * .5 + (bw - sweepWidth) * progress
                    add(scanRegion, offset(buttonPose, x, 0.0, .0006), sweepWidth, bh * .86,
                        alpha * .30 * kotlin.math.sin(kotlin.math.PI * response.sweep))
                }
            }
            val focusButton = layout.buttons.firstOrNull { it.action == panel.hoveredAction &&
                it.action !in panel.disabled && panel.levelItem(it.action)?.unlocked != false && panel.chapterItem(it.action)?.unlocked != false }
            val focus = focusMotion.update(time, focusButton?.let { listOf(it.left, it.top, it.right, it.bottom) })
            if (focus[4] > .001 && movement.entryAge > .20) {
                val at = offset(panelPose, ((focus[0] + focus[2]) / 2 - .5) * width,
                    (.5 - (focus[1] + focus[3]) / 2) * height, .0052 * scale)
                corners(at, (focus[2] - focus[0]) * width + .003 * scale,
                    (focus[3] - focus[1]) * height + .003 * scale, .006 * scale,
                    opacity * focus[4] * .9)
            }
            panel.map?.let { map ->
                val segments = map.segments()
                fun routeInk(segment: VrMapSegment) = when {
                    segment.completed -> mintRegion
                    segment.available -> VrUiAtlas.availableRoute
                    else -> whiteRegion
                }
                segments.forEachIndexed { index, segment ->
                    val progress = UiMotionTokens.out((movement.entryAge - .10 - index * .012) / .38)
                    val tint = routeInk(segment)
                    val strength = opacity * when { segment.completed -> .90; segment.available -> 1.0; else -> .65 }
                    fun route(a: Double, b: Double) {
                        val x1 = segment.x1 + (segment.x2 - segment.x1) * a
                        val y1 = segment.y1 + (segment.y2 - segment.y1) * a
                        val x2 = segment.x1 + (segment.x2 - segment.x1) * b
                        val y2 = segment.y1 + (segment.y2 - segment.y1) * b
                        line(panelPose, (x1 - .5) * width, (.5 - y1) * height,
                            (x2 - .5) * width, (.5 - y2) * height, .00085 * scale, tint, strength)
                    }
                    if (segment.completed || segment.available) route(0.0, progress)
                    else {
                        val length = kotlin.math.sqrt(((segment.x2 - segment.x1) * width).let { it * it } +
                            ((segment.y2 - segment.y1) * height).let { it * it })
                        val count = maxOf(1, (length / (.004 * scale)).toInt())
                        repeat(count) { dash ->
                            val start = dash.toDouble() / count
                            val end = minOf((dash + .48) / count, progress)
                            if (end > start) route(start, end)
                        }
                    }
                    if (progress > .99) {
                        // Tiny square junctions give the wiring a deliberate mechanical termination.
                        add(tint, offset(panelPose, (segment.x2 - .5) * width, (.5 - segment.y2) * height, .0005),
                            .0012 * scale, .0012 * scale, strength)
                    }
                }
                // At clipped routes the chevron indicates which direction continues beyond the window.
                segments.filter { it.y1 == VrLevelMapState.BOTTOM || it.y2 == map.clipTop }.forEach { segment ->
                    val x = if (segment.y2 == map.clipTop) segment.x2 else segment.x1
                    val y = if (segment.y2 == map.clipTop) map.clipTop + .008 else VrLevelMapState.BOTTOM - .008
                    val tint = routeInk(segment)
                    val a = opacity * movement.branchProgress * .55
                    line(panelPose, (x - .5 - .006) * width, (.5 - y - .006) * height,
                        (x - .5) * width, (.5 - y) * height, .0005 * scale, tint, a)
                    line(panelPose, (x - .5 + .006) * width, (.5 - y - .006) * height,
                        (x - .5) * width, (.5 - y) * height, .0005 * scale, tint, a)
                }
            }
            validPointer(panel)?.let { pointer ->
                // The physical finger/contact coordinates never inherit the reveal transform.
                val contactWidth = layout.widthMeters * scale
                val contactHeight = layout.heightMeters * scale
                val markerWidth = 64.0 * contactWidth / layout.widthDp
                val cursor = offset(pose, (pointer.x - .5) * contactWidth, (.5 - pointer.y) * contactHeight, .006 * scale)
                add(pointerRegion, cursor, markerWidth, markerWidth, opacity)
                if (panel.dwellProgress > 0.0) {
                    val half = markerWidth * .25
                    val y = -markerWidth * .37
                    line(cursor, -half, y, half, y, markerWidth * .065, baseRegion, opacity)
                    line(cursor, -half, y, (2 * panel.dwellProgress - 1) * half, y, markerWidth * .04, yellowRegion, opacity)
                }
            }
        }
        return VrRenderFrame(uiBitmap = texture, uiQuads = quads.toFloatArray())
    }

    /** No retained Bitmap: the caller/native uploader owns each non-null frame bitmap. */
    fun close() { lastKey = null; uploadedPage = null; buttonMotion.clear(); displayedPanel = null; displayedPose = null }

    private fun drawMotionAssets(c: Canvas) {
        fun solid(region: Region, color: Int) = inRegion(c, region) { it.drawColor(color) }
        val inks = theme.palette
        solid(yellowRegion, inks.accent)
        solid(whiteRegion, inks.muted)
        solid(mintRegion, inks.success)
        solid(baseRegion, Ink.alpha(inks.onAccent, 224))
        solid(bevelRegion, inks.line)
        solid(VrUiAtlas.graphite, inks.selected)
        inRegion(c, scanRegion) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = android.graphics.LinearGradient(0f, 0f, 128f, 0f,
                    intArrayOf(Color.TRANSPARENT, Ink.alpha(inks.accent, 150), Color.TRANSPARENT),
                    floatArrayOf(0f, .5f, 1f), android.graphics.Shader.TileMode.CLAMP)
            }
            val band = android.graphics.Path().apply {
                moveTo(25f, 0f); lineTo(55f, 0f); lineTo(103f, 16f); lineTo(73f, 16f); close()
            }
            it.drawPath(band, p)
        }
        for ((region, color) in listOf(glowRegion to inks.accent, mintGlowRegion to inks.success)) inRegion(c, region) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = android.graphics.RadialGradient(48f, 48f, 46f,
                    intArrayOf(color, Color.argb(90, Color.red(color), Color.green(color), Color.blue(color)), Color.TRANSPARENT),
                    floatArrayOf(0f, .18f, 1f), android.graphics.Shader.TileMode.CLAMP)
            }
            it.drawCircle(48f, 48f, 46f, p)
        }
    }

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

    private fun drawAngle(c: Canvas, formatted: String, lesson: Boolean) {
        val p = theme.palette
        Ink.card(c, 300f, 72f, p)
        Ink.box(c, 14f, 7f, 48f, 35f, p.accent)
        Ink.center(c, "θ", 31f, 21f, Ink.paint(24f, p.onAccent, true))
        Ink.text(c, if (lesson) "示意角" else "切片角", 58f, 28f, Ink.paint(18f, p.muted, true))
        val number = Ink.paint(31f, p.ink, true, true)
        Ink.text(c, formatted, 286f - number.measureText(formatted), 33f, number)

        // The live indicator overlays this rail at y=43. Dark backing keeps the yellow trace visible.
        Ink.box(c, 14f, 40f, 286f, 46f, p.onAccent)
        for (i in 0..4) {
            val x = 14f + 68f * i
            Ink.box(c, x - .7f, 46f, x + .7f, if (i % 2 == 0) 51f else 49f, p.line)
        }
        val scale = Ink.paint(14f, p.muted, mono = true)
        Ink.text(c, "0°", 14f, 66f, scale)
        Ink.text(c, "180°", 150f - scale.measureText("180°") / 2, 66f, scale)
        Ink.text(c, "360°", 286f - scale.measureText("360°"), 66f, scale)
    }

    private fun drawLesson(c: Canvas, status: VrLessonStatus, keyboardMode: Boolean) {
        val p = theme.palette
        Ink.card(c, 900f, 240f, p)
        val badgeEdge = Ink.badge(c, "讲解 ${status.stepIndex + 1} / ${status.stepCount}", 19f, 8f, p.selected, p.onSelected)
        val voice = Ink.paint(18f, p.muted)
        val voiceX = 880f - voice.measureText(status.voiceLabel)
        Ink.text(c, status.title, badgeEdge + 15f, 34f, Ink.paint(29f, p.ink, true), voiceX - badgeEdge - 33f)
        Ink.text(c, status.voiceLabel, voiceX, 33f, voice)
        Ink.line(c, 20f, 47f, 880f, p.line)
        Ink.paragraph(c, status.narration, 20f, 57f, 860, 110, Ink.paint(28f, p.ink))

        Ink.footer(c, 900f, 240f, 171f, p)
        val next = if (status.stepIndex == status.stepCount - 1) "开始探索" else "下一步"
        val hint = status.gesture?.hint ?: if (status.animating) "演示结束后可点「$next」" else "可绕着模型观察，准备好后点「$next」"
        Ink.text(c, hint, 22f, 200f, Ink.paint(21f, p.ink, true), 856f)
        val controls = if (keyboardMode) "N $next    B 上一步    R 重播" else "对准面板按钮，捏合、戳按或停留 2 秒"
        Ink.text(c, controls, 22f, 227f, Ink.paint(18f, p.muted), 856f)
    }

    private fun drawComparisonLabel(c: Canvas, label: VrComparisonLabel) {
        val p = theme.palette
        Ink.card(c, 340f, 72f, p)
        Ink.box(c, 1f, 14f, 6f, 58f, p.accent)
        Ink.center(c, label.title, 170f, 23f, Ink.paint(27f, p.ink, true), 312f)
        Ink.center(c, label.subtitle, 170f, 54f, Ink.paint(19f, p.muted), 312f)
    }

    private fun drawTutorial(c: Canvas, status: VrTutorialStatus) {
        val p = theme.palette
        Ink.card(c, 640f, 184f, p)
        val count = status.totalLevels.coerceAtLeast(1)
        val index = status.levelIndex.coerceIn(0, count - 1)
        val code = status.levelCode.ifBlank { "${index + 1} / $count" }
        val codeEnd = Ink.badge(c, code, 17f, 7f, p.accent, p.onAccent)
        val label = when {
            status.completed -> "已完成"
            status.blocked -> "前方阻挡"
            status.progressLabel != null -> status.progressLabel
            status.distanceMeters?.let { it.isFinite() && it >= 0 } == true ->
                String.format(Locale.ROOT, "距目标 %.1f m", status.distanceMeters)
            status.locomotionLocked -> "仅可旋转"
            else -> ""
        }
        val statusInk = when {
            status.completed -> p.success
            status.blocked -> p.warning
            else -> p.muted
        }
        val statusPaint = Ink.paint(18f, statusInk, true)
        val statusX = 622f - statusPaint.measureText(label)
        Ink.text(c, status.title, codeEnd + 12f, 32f, Ink.paint(26f, p.ink, true), statusX - codeEnd - 25f)
        Ink.text(c, label, statusX, 30f, statusPaint)
        Ink.line(c, 18f, 42f, 622f, p.line)
        Ink.paragraph(c, status.instruction, 18f, 48f, 604, 54, Ink.paint(24f, p.ink, true))
        Ink.paragraph(c, status.hint, 18f, 104f, 604, 46, Ink.paint(20f, p.muted))
        Ink.footer(c, 640f, 184f, 151f, p)
        val controls = when {
            !status.keyboardMode -> status.gesture?.hint ?: "抓握回拉前进 · 右手掌心向前横移旋转 · 掌心朝自己或朝上 3 秒开面板"
            status.expedition -> "WASD 随视线移动 · 空格/Shift 升降 · Q/E 旋转 · H 提示"
            status.locomotionLocked -> "Q/E 旋转 · M 面板 · L 关卡路线"
            else -> "WASD 随视线移动 · 空格/Shift 升降 · Q/E 旋转 · M 面板"
        }
        Ink.fitLine(c, controls, 18f, 174f, 604f, Ink.paint(17f, p.ink))
    }

    private fun drawPanel(c: Canvas, state: VrPanelState) {
        state.map?.let {
            WelcomeRouteArt.board(c, state, it)
            return
        }
        val p = state.theme.palette
        val width = state.layout.widthDp.toFloat()
        val height = state.layout.heightDp.toFloat()
        Ink.card(c, width, height, p)
        val hint = if (state.debugMode) "Tab 选择 · Enter 确认 · M 收起" else "捏合、戳按或停留 2 秒 · 握拳关闭"
        if (state.groupTitle != null || state.chapterItems.isNotEmpty()) {
            val title = state.groupTitle?.let { "第 ${state.groupIndex + 1} 章 · $it" } ?: "关卡路线"
            val counter = if (state.groupTitle != null) "${state.completedCount} / ${state.levelItems.size} 已完成" else ""
            val small = Ink.paint(18f, p.muted)
            val countX = width - 20f - small.measureText(counter)
            Ink.text(c, title, 20f, 37f, Ink.paint(29f, p.ink, true), countX - 32f)
            Ink.text(c, counter, countX, 35f, small)
            Ink.line(c, 20f, 56f, width - 20f, p.line)
            Ink.text(c, if (state.debugMode) "数字键选择 · Esc 返回 · Tab / Enter 确认" else hint,
                20f, height - 9f, Ink.paint(16f, p.muted), width - 40f)
        } else {
            // These short panels are held at arm’s length. Keep the guidance in a single high-contrast header.
            val header = state.header?.takeUnless { state.debugMode } ?: hint
            Ink.box(c, 1f, 11f, 6f, 34f, p.accent)
            Ink.fitLine(c, header, 16f, 32f, width - 30f, Ink.paint(19f, p.ink, true))
        }
    }

    private fun drawButton(c: Canvas, action: PalmAction, state: VrPanelState, width: Float, height: Float) {
        if (state.map != null) {
            WelcomeRouteArt.button(c, action, state, width, height)
            return
        }
        val p = state.theme.palette
        val item = state.levelItem(action)
        val chapter = state.chapterItem(action)
        val disabled = action in state.disabled || item?.unlocked == false || chapter?.unlocked == false
        val pressed = !disabled && state.pressedAction == action
        val selected = !disabled && (state.hoveredAction == action ||
            (state.labels == null && action == PalmAction.TOGGLE_REFERENCE && state.referenceSelected))
        val fill = when {
            disabled -> p.inset
            pressed -> p.accent
            selected -> p.selected
            else -> p.raised
        }
        val foreground = when {
            disabled -> p.muted
            pressed -> p.onAccent
            selected -> p.onSelected
            else -> p.ink
        }
        Ink.button(c, width, height, fill, if (selected || pressed) p.ink else p.line, selected || pressed)
        if (chapter != null) {
            Ink.text(c, "${chapter.index + 1}   ${chapter.title}", 14f, 32f, Ink.paint(27f, foreground, true), width - 28f)
            val status = when { chapter.completed -> "已完成"; chapter.unlocked -> "可探索"; else -> "待解锁" }
            Ink.text(c, "${chapter.completedCount} / ${chapter.totalLevels} 关 · $status", 14f, 58f,
                Ink.paint(18f, foreground), width - 28f)
            if (!chapter.unlocked) Ink.text(c, "完成「移动」后解锁", 14f, height - 10f, Ink.paint(16f, foreground), width - 28f)
            return
        }
        if (item != null) {
            val status = when { item.completed -> "已完成"; !item.unlocked -> "未解锁"; else -> "可挑战" }
            Ink.text(c, "${item.code} · ${if (item.hasIntroduction) "教程" else "挑战"}", 12f, 21f,
                Ink.paint(17f, foreground, true), width - 86f)
            val statusPaint = Ink.paint(15f, foreground)
            Ink.text(c, status, width - statusPaint.measureText(status) - 12f, 21f, statusPaint)
            Ink.text(c, item.title.substringAfter(" · ", item.title), 12f, 47f,
                Ink.paint(23f, foreground, true), width - 24f)
            val detail = when {
                !item.unlocked -> item.lockedReason
                item.current && item.completed -> "当前关卡 · 可重玩"
                item.current -> "当前关卡"
                item.completed -> "可重新挑战"
                item.hasIntroduction -> "立体讲解 · 实际观察"
                else -> "进入探索"
            }
            Ink.text(c, detail, 12f, height - 8f, Ink.paint(15f, foreground), width - 24f)
            return
        }
        val label = state.labels?.getOrNull(state.layout.buttons.indexOfFirst { it.action == action }) ?: when (action) {
            PalmAction.RESET_ANGLE -> "θ 归零"
            PalmAction.TOGGLE_REFERENCE -> state.referenceLabel
            PalmAction.TOGGLE_THEME -> return Ink.themeSwitch(c, state.theme, foreground, width, height)
            PalmAction.CONTEXT -> state.contextLabel
            PalmAction.LEVEL_LIST -> "关卡路线"
            PalmAction.BACK -> "返回"
            PalmAction.PREVIOUS_GROUP -> "前序"
            PalmAction.NEXT_GROUP -> "后续"
            else -> "第 ${PalmPanelLayout.levelActions.indexOf(action) + 1} 关"
        }
        val type = Ink.paint(if (width < 140f) 25f else 27f, foreground, true)
        Ink.center(c, label, width * .5f, height * .5f, type, width - 14f)
    }

    private fun validPointer(state: VrPanelState) = state.pointer?.takeIf {
        PalmPanelLayout.inPanel(it.x, it.y) && it.depthMeters.isFinite() && abs(it.depthMeters) <= .35
    }

    private fun drawPointer(c: Canvas, state: VrPanelState) {
        val pointer = validPointer(state) ?: return
        val distance = abs(pointer.depthMeters)
        val radius = (7.0 + 11.0 * (distance / .08).coerceIn(0.0, 1.0)).toFloat()
        val x = 32f
        val y = 32f
        val color = if (distance <= .008) theme.palette.accent else Color.rgb(250, 250, 245)
        c.drawCircle(x, y, radius + 2, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = theme.palette.onAccent; style = Paint.Style.STROKE; strokeWidth = 5f })
        c.drawCircle(x, y, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.STROKE; strokeWidth = 2f })
        c.drawCircle(x, y, if (distance <= .008) 5f else 2.5f, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
    }
}

internal object Ink {
    fun alpha(color: Int, alpha: Int): Int = Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))
    fun cutShape(width: Float, height: Float, cut: Float = 6f): Path = Path().apply {
        moveTo(cut, .5f); lineTo(width - .5f, .5f); lineTo(width - .5f, height - cut)
        lineTo(width - cut, height - .5f); lineTo(.5f, height - .5f); lineTo(.5f, cut); close()
    }
    fun box(c: Canvas, left: Float, top: Float, right: Float, bottom: Float, tint: Int) =
        c.drawRect(left, top, right, bottom, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tint })

    fun card(c: Canvas, width: Float, height: Float, palette: UiPalette) {
        val shape = cutShape(width, height, if (width >= 400f) 8f else 5f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = alpha(palette.surface, 247)
        c.drawPath(shape, paint)
        paint.color = palette.line
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f
        c.drawPath(shape, paint)
    }

    fun badge(c: Canvas, label: String, x: Float, y: Float, fill: Int, foreground: Int): Float {
        val type = paint(19f, foreground, true, true)
        val right = x + type.measureText(label) + 18f
        box(c, x, y, right, y + 29f, fill)
        center(c, label, (x + right) * .5f, y + 14.5f, type)
        return right
    }

    fun footer(c: Canvas, width: Float, height: Float, top: Float, palette: UiPalette) {
        c.save()
        c.clipPath(cutShape(width - 1f, height - 1f, 8f))
        box(c, 1f, top, width - 1f, height - 1f, palette.inset)
        box(c, 2f, top + 1f, 6f, height - 2f, palette.accent)
        c.restore()
    }

    fun button(c: Canvas, width: Float, height: Float, fill: Int, border: Int, active: Boolean) {
        val shape = cutShape(width, height, 5f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = fill
        c.drawPath(shape, paint)
        paint.color = border
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = if (active) 2.5f else 1.5f
        c.drawPath(shape, paint)
    }

    fun themeSwitch(c: Canvas, current: UiThemeMode, ink: Int, width: Float, height: Float) {
        val type = paint(minOf(width / 8f, height * .52f).coerceIn(16f, 26f), ink, true)
        val scale = type.textSize / 18f
        val iconWidth = 26f * scale
        val label = current.switchLabel
        val left = (width - iconWidth - type.measureText(label)) / 2f
        val mid = height / 2f
        text(c, label, left + iconWidth, mid - (type.ascent() + type.descent()) / 2, type)
        c.save()
        c.translate(left + 9f * scale, mid)
        c.scale(scale, scale)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink
            style = Paint.Style.STROKE
            strokeWidth = 1.8f
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }
        if (current == UiThemeMode.LIGHT) {
            val crescent = Path().apply {
                moveTo(2f, -8f)
                cubicTo(-8f, -10f, -11f, 5f, -2f, 8f)
                cubicTo(2f, 10f, 7f, 7f, 8f, 3f)
                cubicTo(0f, 5f, -3f, -2f, 2f, -8f)
                close()
            }
            c.drawPath(crescent, stroke)
        } else {
            c.drawCircle(0f, 0f, 4.6f, stroke)
            for (i in 0 until 8) {
                val a = i * PI / 4
                val dx = cos(a).toFloat()
                val dy = sin(a).toFloat()
                c.drawLine(7f * dx, 7f * dy, 9f * dx, 9f * dy, stroke)
            }
        }
        c.restore()
    }

    /** Small vector symbols remain crisp in the atlas and do not depend on emoji fonts. */
    fun status(c: Canvas, x: Float, y: Float, size: Float, state: Int, color: Int) {
        val r = size * .5f
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.STROKE; strokeWidth = 1.4f; strokeJoin = Paint.Join.MITER }
        when (state) {
            2 -> {
                c.drawRect(x - r, y - r, x + r, y + r, p)
                c.drawPath(Path().apply { moveTo(x - r * .6f, y); lineTo(x - r * .05f, y + r * .5f); lineTo(x + r * .7f, y - r * .6f) }, p)
            }
            1 -> c.drawPath(Path().apply { moveTo(x, y - r); lineTo(x + r, y); lineTo(x, y + r); lineTo(x - r, y); close() }, p)
            else -> {
                c.drawRect(x - r * .8f, y - r * .05f, x + r * .8f, y + r, p)
                c.drawArc(RectF(x - r * .55f, y - r, x + r * .55f, y + r * .6f), 180f, 180f, false, p)
                c.drawLine(x, y + r * .25f, x, y + r * .65f, p)
            }
        }
    }
    fun chevron(c: Canvas, x: Float, y: Float, size: Float, direction: Float, color: Int) {
        c.drawPath(Path().apply { moveTo(x - size, y - direction * size * .4f); lineTo(x, y + direction * size * .6f); lineTo(x + size, y - direction * size * .4f) },
            Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.STROKE; strokeWidth = 1.5f })
    }
    fun paint(size: Float, color: Int, bold: Boolean = false, mono: Boolean = false) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size; this.color = color
        typeface = Typeface.create(if (mono) "monospace" else "sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
    }
    fun text(c: Canvas, text: String, x: Float, baseline: Float, p: TextPaint, width: Float = Float.POSITIVE_INFINITY) {
        val fitted = if (width.isFinite()) TextUtils.ellipsize(text, p, width.coerceAtLeast(0f), TextUtils.TruncateAt.END).toString() else text
        c.drawText(fitted, x, baseline, p)
    }
    fun center(c: Canvas, label: String, cx: Float, cy: Float, p: TextPaint, width: Float = Float.POSITIVE_INFINITY) {
        val measured = p.measureText(label)
        if (measured > width) p.textSize = (p.textSize * width / measured).coerceAtLeast(16f)
        val occupied = minOf(width, p.measureText(label))
        text(c, label, cx - occupied / 2, cy - (p.ascent() + p.descent()) / 2, p, width)
    }

    fun fitLine(c: Canvas, label: String, x: Float, baseline: Float, width: Float, p: TextPaint) {
        val measured = p.measureText(label)
        if (measured > width) p.textSize = (p.textSize * width / measured).coerceAtLeast(14f)
        text(c, label, x, baseline, p, width)
    }

    /** Keep all authored lines, including longer CJK instructions, inside their own text block. */
    fun paragraph(c: Canvas, text: String, x: Float, top: Float, width: Int, height: Int, p: TextPaint) {
        if (text.isEmpty()) return
        fun layout() = StaticLayout.Builder.obtain(text, 0, text.length, p, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false)
            .setBreakStrategy(LineBreaker.BREAK_STRATEGY_SIMPLE).build()
        var block = layout()
        while (block.height > height && p.textSize > 15f) {
            p.textSize -= 1f
            block = layout()
        }
        c.save()
        c.translate(x, top)
        c.clipRect(0, 0, width, height)
        block.draw(c)
        c.restore()
    }
    fun line(c: Canvas, x: Float, y: Float, end: Float, color: Int) {
        c.drawLine(x, y, end, y, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; strokeWidth = 2f })
    }
}
