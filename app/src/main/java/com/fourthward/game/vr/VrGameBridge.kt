package com.fourthward.game.vr

import android.content.Context
import android.util.Log
import android.view.KeyEvent
import androidx.annotation.Keep
import com.fourthward.game.core.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/** JNI simulation bridge. Only frame() mutates game/gesture/render state; UI callbacks use InputState. */
@Keep
class VrGameBridge(context: Context, skipStartup: Boolean = false) {
    private val levels = TutorialLevels.all()
    private val catalog = LevelCatalog(levels)
    private val levelGraph = LevelDependencyGraph(levels)
    private val uiPreferences = context.applicationContext.getSharedPreferences("ui_preferences", Context.MODE_PRIVATE)
    private var theme = if (uiPreferences.all[DARK_MODE] == true) UiThemeMode.DARK else UiThemeMode.LIGHT
    private val progressStore = LevelProgressStore(context)
    private val progress = progressStore.loadLevels(levels)
    private val resume = progressStore.loadResume(levels, progress)
    private val game = FourthwardGame(levels, progress, progressStore::save).apply {
        if (resume.index != levelIndex) selectLevel(resume.index)
    }
    private enum class MapPage { WELCOME, IN_GAME, HIDDEN }
    private var mapPage = MapPage.WELCOME
    private val welcomeVisible get() = mapPage == MapPage.WELCOME
    private val levelListVisible get() = mapPage != MapPage.HIDDEN
    private var mapWindow = levelGraph.windowForLevel(resume.index, PalmAction.CONTEXT)
    private val mapFirstRow get() = mapWindow.firstRow
    private var welcomeDock: VrHandControls.Panel? = null
    private var welcomeModel: FloatArray? = null
    private val welcomeTesseract = WelcomeTesseract()
    private var exhibitUploaded = false
    private val input = InputState()
    private val focusPolicy = VrFocusPolicy(input)
    private val keyboardMode = AtomicBoolean(false)
    private val focusReset = AtomicBoolean(true)
    @Volatile private var focused = false
    private var ui = VrUiRenderer()
    private var worldDirty = true
    private var referenceVisible = true
    private var debugVisible = false
    private var navigationRevision = 0L
    private var feedbackRevision = 0L
    private var effectsSceneRevision = 0L
    private val worldEffects = VrWorldEffects()
    private var selectedControl = mapWindow.layout.buttons.indexOfFirst { it.action == PalmAction.CONTEXT }
    private var clickFeedback = 0.0
    private var blockedFeedback = 0.0
    private var deathFeedback = 0.0
    private var lastHead = VrTrackingMath.Pose(Vec3(0.0, 1.65, 0.0), Vec3(1.0, 0.0, 0.0), Vec3(0.0, 1.0, 0.0), Vec3(0.0, 0.0, 1.0))
    private var gameplaySpace: VrTrackingMath.LevelSpace? = null
    private var diagnosticsElapsed = 0.0
    private var motionSinceLog = false
    private var meshedHeadPosition: Vec3? = null
    private val handControls = VrHandControls()
    private var rotationActive = false
    private var pullActive = false
    private var lastFrameNanos = 0L
    private var panelPose: VrTrackingMath.Pose? = null
    private var debugAnchor: Vec3? = null
    private var pointer: PalmPointerPosition? = null
    private var hoveredAction: PalmAction? = null
    private var dwellProgress = 0.0
    private var pressedAction: PalmAction? = null
    private var lesson: LessonPlayer? = null
    private var lessonAnchor: VrPose? = null
    private var lessonPanelScale = 1.0
    private var lessonDockDismissed = false
    private var narratedRevision: Pair<Int, Long>? = null
    private var wasLessonAnimating = false
    private var lessonControlFocused = false
    private var lessonFeedbackAction: PalmAction? = null
    private val soundtrack: GameSoundtrack = GameSoundtrack(context, skipStartup) { narration.setAudioAllowed(it) }
    private val narration: LessonNarration = LessonNarration(context, soundtrack::setSpeaking)
    val startupPlayed get() = soundtrack.startupPlayed

    @Keep
    @Synchronized
    fun setFocused(value: Boolean) {
        // An immersive runtime may own focus instead of the Android host window. Losing this
        // window clears transient input, but cannot override the OpenXR session's authority.
        updateFocus(focusPolicy.windowFocus(value))
    }

    @Keep
    @Synchronized
    fun setResumed(value: Boolean) {
        updateFocus(focusPolicy.resumed(value))
    }

    @Keep
    @Synchronized
    fun setXrFocused(value: Boolean) {
        updateFocus(focusPolicy.xrFocused(value))
    }

    private fun updateFocus(state: VrFocusPolicy.Change) {
        if (state.clearTransient) focusReset.set(true)
        if (focused != state.active) Log.i(TAG, "focus resumed=${state.resumed} xr=${state.xrFocused} active=${state.active}")
        focused = state.active
        soundtrack.setActive(focused)
        narration.setActive(focused && lesson != null && !levelListVisible)
    }

    @Keep
    fun onKeyEvent(event: KeyEvent): Boolean {
        val key = when (event.keyCode) {
            KeyEvent.KEYCODE_W -> InputKey.FORWARD
            KeyEvent.KEYCODE_S -> InputKey.BACKWARD
            KeyEvent.KEYCODE_A -> InputKey.LEFT
            KeyEvent.KEYCODE_D -> InputKey.RIGHT
            KeyEvent.KEYCODE_SPACE -> InputKey.ASCEND
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> InputKey.DESCEND
            KeyEvent.KEYCODE_Q -> InputKey.ROTATE_LEFT
            KeyEvent.KEYCODE_E -> InputKey.ROTATE_RIGHT
            KeyEvent.KEYCODE_M -> InputKey.MENU
            KeyEvent.KEYCODE_F2 -> InputKey.THEME
            KeyEvent.KEYCODE_R, KeyEvent.KEYCODE_P -> InputKey.RESET
            KeyEvent.KEYCODE_V -> InputKey.REFERENCE
            KeyEvent.KEYCODE_T -> InputKey.RESTART
            KeyEvent.KEYCODE_N -> InputKey.NEXT_LEVEL
            KeyEvent.KEYCODE_B -> InputKey.PREVIOUS_STEP
            KeyEvent.KEYCODE_H -> InputKey.LESSON
            KeyEvent.KEYCODE_L -> InputKey.LEVEL_LIST
            KeyEvent.KEYCODE_ESCAPE -> InputKey.BACK
            KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_NUMPAD_1 -> InputKey.LEVEL_1
            KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_NUMPAD_2 -> InputKey.LEVEL_2
            KeyEvent.KEYCODE_3, KeyEvent.KEYCODE_NUMPAD_3 -> InputKey.LEVEL_3
            KeyEvent.KEYCODE_4, KeyEvent.KEYCODE_NUMPAD_4 -> InputKey.LEVEL_4
            KeyEvent.KEYCODE_5, KeyEvent.KEYCODE_NUMPAD_5 -> InputKey.LEVEL_5
            KeyEvent.KEYCODE_6, KeyEvent.KEYCODE_NUMPAD_6 -> InputKey.LEVEL_6
            KeyEvent.KEYCODE_7, KeyEvent.KEYCODE_NUMPAD_7 -> InputKey.LEVEL_7
            KeyEvent.KEYCODE_8, KeyEvent.KEYCODE_NUMPAD_8 -> InputKey.LEVEL_8
            KeyEvent.KEYCODE_9, KeyEvent.KEYCODE_NUMPAD_9 -> InputKey.LEVEL_9
            KeyEvent.KEYCODE_LEFT_BRACKET, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_DPAD_DOWN -> InputKey.PREVIOUS_GROUP
            KeyEvent.KEYCODE_RIGHT_BRACKET, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_DPAD_UP -> InputKey.NEXT_GROUP
            KeyEvent.KEYCODE_TAB -> InputKey.SELECT
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> InputKey.ACTIVATE
            else -> return false
        }
        if (event.action == KeyEvent.ACTION_UP) {
            input.key(key, false)
            return focused
        }
        if (event.action != KeyEvent.ACTION_DOWN || !focused) return false
        keyboardMode.set(true)
        input.key(key, true, event.repeatCount > 0, event.isShiftPressed)
        if (event.repeatCount == 0) Log.d(TAG, "key=$key")
        return true
    }

    /** Head XYZ + xyzw. Hands contain 26 joint positions and an optional palm quaternion, or null on tracking loss. */
    @Keep
    @Synchronized
    fun frame(deltaSeconds: Float, head: FloatArray?, leftHand: FloatArray?, rightHand: FloatArray?, controls: FloatArray): VrRenderFrame {
        val dt = deltaSeconds.toDouble().let { if (it.isFinite() && it >= 0.0) it else 0.0 }
        val clockStep = dt.coerceAtMost(0.1)
        val now = System.nanoTime()
        val elapsedSeconds = if (lastFrameNanos == 0L) 0.0 else (now - lastFrameNanos) / 1e9
        val handDt = maxOf(dt, elapsedSeconds)
        lastFrameNanos = now
        if (focusReset.getAndSet(false)) {
            welcomeDock = null
            welcomeModel = null
            exhibitUploaded = false
            resetHandTracking()
            worldDirty = true
        }
        clickFeedback = (clickFeedback - clockStep).coerceAtLeast(0.0)
        blockedFeedback = (blockedFeedback - clockStep).coerceAtLeast(0.0)
        deathFeedback = (deathFeedback - clockStep).coerceAtLeast(0.0)
        val tracked = VrTrackingMath.head(head)
        val beforeFrame = game.frame
        val beforeCompleted = game.completed
        val beforeDeaths = game.deathCount
        val beforeMissionRevision = game.missionRevision
        val beforeCheckpoints = game.completedCheckpointIds
        val beforeObservation = game.observationStep
        val previousRawHead = lastHead.position
        val startedInLesson = lesson != null
        val startedInLevelList = levelListVisible
        if (tracked != null) {
            lastHead = tracked
            if (focused && lesson != null && lessonAnchor == null) {
                val right = VrTrackingMath.horizontalRight(tracked) ?: Vec3(1.0, 0.0, 0.0)
                val normal = Vec3(-right.z, 0.0, right.x)
                val position = tracked.position - normal * 1.25
                lessonAnchor = VrPose(position.copy(y = maxOf(0.80, position.y - 0.10)), right, Vec3(0.0, 1.0, 0.0), normal)
                worldDirty = true
                Log.i(TAG, "lesson-anchor=$lessonAnchor")
            }
            if (focused && lesson == null && !welcomeVisible) {
                val space = gameplaySpace
                if (space == null) {
                    restartGameplay(tracked)
                    logState("initial-rebased-head")
                } else {
                    val result = game.updateTrackedHead(space.localPosition(tracked.position))
                    if (result.blocked) blockedFeedback = 0.8
                }
                if ((tracked.position - previousRawHead).lengthSquared() > 1e-8) motionSinceLog = true
                val meshed = meshedHeadPosition
                // Reference lines/floor are player-relative even when the affine frame itself
                // stays unchanged during physical walking. Accumulate movement from last mesh.
                if (meshed == null || (game.trackedHead - meshed).lengthSquared() >= 0.002 * 0.002) worldDirty = true
            }
        }
        val actions = if (game.deathCount == beforeDeaths) {
            input.controller(controllerState(controls), now)
            input.frame(dt, now)
        } else InputFrame()
        val commandPage = navigationRevision
        for (command in actions.commands) {
            command(command)
            // Do not apply the old page's remaining queued clicks to its replacement.
            if (navigationRevision != commandPage || game.deathCount != beforeDeaths) break
        }
        val hand = if (focused && game.deathCount == beforeDeaths) updateHands(handDt, elapsedSeconds, tracked,
            VrTrackingMath.hand(leftHand, true), VrTrackingMath.hand(rightHand, false)) else {
            resetHandTracking()
            VrHandControls.Frame()
        }
        fun canSimulate() = focused && tracked != null && navigationRevision == commandPage &&
            game.deathCount == beforeDeaths && lesson == null && !startedInLesson && !levelListVisible && !startedInLevelList
        val theta = if (abs(actions.thetaRadians) > 1e-12) actions.thetaRadians
            else hand.rotation.deltaRadians
        if (canSimulate() && abs(theta) > 1e-12) {
            if (game.rotate(theta)) motionSinceLog = true
            if (game.lastMotionBlocked) blockedFeedback = 0.8
        }
        if (canSimulate()) {
            val movingWithKeysOrController = actions.moveRightMeters != 0.0 ||
                actions.moveForwardMeters != 0.0 || actions.moveUpMeters != 0.0
            val rawMovement = if (movingWithKeysOrController) {
                VrTrackingMath.locomotion(tracked!!, actions.moveRightMeters, actions.moveForwardMeters, actions.moveUpMeters)
            } else VrTrackingMath.pullMovement(tracked!!, hand.forwardMeters)
            if (rawMovement.lengthSquared() > 1e-16) {
                val result = game.move(checkNotNull(gameplaySpace).localVector(rawMovement))
                motionSinceLog = motionSinceLog || result.changed
                if (result.blocked) blockedFeedback = 0.8
            }
        }
        if (canSimulate() && game.tickWorld(clockStep)) {
            worldDirty = true
            motionSinceLog = true
        }
        if (!welcomeVisible && lesson == null && !startedInLesson && game.tickObservation(clockStep, canSimulate())) {
            worldDirty = true
            logState("observation=${game.observationStep}/${game.observationCount}")
        }
        if (game.deathCount != beforeDeaths) respawn()
        worldDirty = worldDirty || game.frame !== beforeFrame || beforeCompleted != game.completed ||
            beforeMissionRevision != game.missionRevision
        if (beforeCheckpoints != game.completedCheckpointIds) logState("checkpoint")
        if (!beforeCompleted && game.completed) logState("completed")
        if (beforeCheckpoints != game.completedCheckpointIds || beforeCompleted != game.completed ||
            beforeObservation != game.observationStep) feedbackRevision++
        lesson?.let { player ->
            if (player.tick(clockStep, focused && tracked != null && !levelListVisible)) worldDirty = true
            val key = player.levelIndex to player.revision
            if (lessonAnchor != null && focused && !levelListVisible && key != narratedRevision) {
                narratedRevision = key
                narration.setActive(true)
                narration.play(player.currentStep.id)
                Log.i(TAG, "lesson-step level=${player.levelIndex + 1} step=${player.stepIndex + 1}/${player.stepCount} id=${player.currentStep.id} revision=${player.revision}")
            }
            if (wasLessonAnimating && !player.isAnimating) Log.i(TAG, "lesson-held step=${player.currentStep.id}")
            wasLessonAnimating = player.isAnimating
        }
        diagnosticsElapsed += clockStep
        if (diagnosticsElapsed >= 1.0 && motionSinceLog) {
            diagnosticsElapsed = 0.0
            motionSinceLog = false
            logState("motion")
        }
        val layout = activePanelLayout()
        val selected = layout.buttons[selectedControl.coerceIn(layout.buttons.indices)].action
        val activeLesson = lesson
        val disabled = disabledPanelActions()
        val keyboardFocus = debugVisible || ((welcomeVisible || activeLesson != null) && (keyboardMode.get() || lessonControlFocused))
        val panel = VrPanelState(referenceVisible,
            theme = theme,
            hoveredAction = hoveredAction ?: selected.takeIf { pointer == null && keyboardFocus },
            pressedAction = pressedAction ?: if (clickFeedback > 0.0) {
                if (activeLesson != null) lessonFeedbackAction else if (debugVisible) selected else null
            } else null,
            pointer = pointer,
            dwellProgress = dwellProgress,
            contextLabel = contextLabel(),
            referenceLabel = if (game.level.expedition != null) "提示" else "参考平面",
            referenceSelected = game.level.expedition == null && referenceVisible,
            debugMode = debugVisible || (welcomeVisible && keyboardMode.get()),
            labels = if (levelListVisible) null else activeLesson?.let { listOf("上一步", "重播本步", if (it.isAnimating) "演示中" else if (it.isLastStep) "开始探索" else "下一步") },
            disabled = disabled,
            header = if (levelListVisible) null else activeLesson?.let {
                val buttons = if (keyboardMode.get()) "Tab 选择 · Enter 确认" else "捏合、戳按或停留 2 秒"
                "讲解 ${it.stepIndex + 1} / ${it.stepCount} · $buttons"
            },
            layout = layout,
            levelItems = if (levelListVisible) mapWindow.nodes.map { node ->
                val index = node.index
                VrLevelItem(index, levels[index].title, game.progress.isUnlocked(index), game.progress.isCompleted(index), index == game.levelIndex,
                    hasIntroduction = LessonTutorials.hasLesson(index), code = catalog.levelCode(index),
                    lockedReason = lockedReason(index))
            } else emptyList(),
            groupTitle = if (levelListVisible) "关卡路线" else null,
            startLabel = if (!welcomeVisible) null else if (resume.returning) "继续 ${catalog.levelCode(resume.index)}" else "开始游戏",
            completedCount = game.progress.completedCount,
            map = if (levelListVisible) VrLevelMapState(
                levelGraph.nodes.map { VrMapNode(it.index, it.row, it.column, game.progress.isCompleted(it.index), game.progress.isUnlocked(it.index), it.width) },
                mapWindow.edges.map { VrMapEdge(it.fromIndex, it.toIndex) },
                mapFirstRow, levelGraph.totalRows, game.progress.completedCount, levels.size,
                mapWindow.visibleRows,
            ) else null)
        val voiceLabel = when (narration.status) {
            LessonNarration.Status.PLAYING -> "语音讲解中"
            LessonNarration.Status.PAUSED -> "语音已暂停"
            LessonNarration.Status.PREPARING -> "准备语音"
            LessonNarration.Status.UNAVAILABLE -> if (keyboardMode.get()) "字幕讲解 · R 重试语音" else "字幕讲解 · 可点重播"
            else -> "讲解已结束 · 可继续观察"
        }
        val lessonStatus = activeLesson?.let { VrLessonStatus(it.stepIndex, it.stepCount, it.currentStep.title,
            it.currentStep.narration, it.isAnimating, voiceLabel,
            geometryTopic = it.definition.geometryTopic, anchorPose = lessonAnchor, motionTopic = it.definition.motionTopic,
            gesture = it.currentStep.gesture) }
        val uiFrame = ui.render(VrUiState(activeLesson?.let { Math.toDegrees(it.scene.angleRadians) } ?: game.rotationDegrees, tutorialStatus(), panel,
            panelPose = if (focused) panelPose?.renderPose() else null, headPose = lastHead.renderPose(),
            lesson = lessonStatus, panelScale = lessonPanelScale,
            active = focused && tracked != null, pageRevision = navigationRevision,
            feedbackRevision = feedbackRevision), now)
        val vertices = when {
            !worldDirty -> null
            welcomeVisible -> if (welcomeModel != null) WelcomeEnvironment.build(theme) else floatArrayOf()
            activeLesson == null && gameplaySpace != null -> VrSceneBuilder.build(game, referenceVisible, theme)
            activeLesson != null && lessonAnchor != null -> LessonSceneBuilder.build(activeLesson.scene, lessonAnchor!!)
            else -> floatArrayOf()
        }
        if (vertices != null) {
            worldDirty = false
            meshedHeadPosition = game.trackedHead
        }
        val exhibitVisible = welcomeVisible && welcomeModel != null
        val projection = if (exhibitVisible) welcomeTesseract.projection(
            if (focused && tracked != null) clockStep else 0.0) else null
        val effects = when {
            exhibitVisible -> if (exhibitUploaded) null else WelcomeTesseract.vertices
            welcomeVisible -> floatArrayOf()
            else -> worldEffects.frame(game, clockStep, focused && tracked != null,
                visible = activeLesson == null && !levelListVisible, sceneRevision = effectsSceneRevision)
        }
        exhibitUploaded = exhibitVisible
        return VrRenderFrame(vertices, uiFrame.uiBitmap, uiFrame.uiQuads, effects,
            worldModel = if (welcomeVisible) welcomeModel else if (activeLesson == null) gameplaySpace?.worldModel else null,
            exhibitProjection = projection, lightEnvironment = theme == UiThemeMode.LIGHT)
    }

    private fun controllerState(values: FloatArray): InputControllerState? {
        if (values.size < 6 || values.take(6).any { !it.isFinite() }) return null
        val bits = values[5].toInt().takeIf { it in 0..127 } ?: 0
        return InputControllerState(values[0] > 0.5f, values[1] > 0.5f,
            values[2].toDouble(), values[3].toDouble(), values[4].toDouble(),
            menu = bits and 1 != 0, activate = bits and 2 != 0, reset = bits and 4 != 0,
            reference = bits and 8 != 0, restart = bits and 16 != 0, nextLevel = bits and 32 != 0,
            selectNext = bits and 64 != 0)
    }

    private fun activePanelLayout(): PalmPanelSpec = when {
        levelListVisible -> mapWindow.layout
        lesson != null -> PalmPanelLayout.lessonMenu
        else -> PalmPanelLayout.game
    }

    private fun disabledPanelActions(): Set<PalmAction> = if (levelListVisible) buildSet {
        levelActions.forEachIndexed { row, action ->
            val index = mapWindow.nodes.getOrNull(row)?.index
            if (index == null || !game.progress.isUnlocked(index)) add(action)
        }
        if (mapFirstRow == 0) add(PalmAction.PREVIOUS_GROUP)
        if (mapFirstRow == levelGraph.maxFirstRow) add(PalmAction.NEXT_GROUP)
    } else lesson?.let { player -> buildSet {
        if (player.stepIndex == 0) add(PalmAction.RESET_ANGLE)
        if (player.isAnimating) add(PalmAction.CONTEXT)
    } } ?: emptySet()

    private fun resetNavigationPage() {
        navigationRevision++
        clickFeedback = 0.0
        lessonFeedbackAction = null
        resetPanelInput()
        handControls.resetMotion()
        input.clearForPageChange()
    }

    private fun showLevelList(visible: Boolean, keyboard: Boolean = false) {
        if (welcomeVisible || levelListVisible == visible) return
        mapPage = if (visible) MapPage.IN_GAME else MapPage.HIDDEN
        if (keyboard) debugVisible = true
        if (visible) mapWindow = levelGraph.windowForLevel(game.levelIndex)
        selectedControl = if (visible) mapWindow.nodes.indexOfFirst { it.index == game.levelIndex }.coerceAtLeast(0)
            else if (lesson != null) 2 else 0
        resetNavigationPage()
        narration.setActive(focused && lesson != null && !visible)
        Log.i(TAG, "level-map=$visible firstRow=$mapFirstRow slots=${mapWindow.nodes.map { catalog.levelCode(it.index) }} completedIds=${game.progress.completedIds}")
        logState("level-map=$visible")
    }

    private fun backFromList() {
        showLevelList(false)
    }

    private fun changeGroup(direction: Int, dragging: Boolean = false) {
        if (!levelListVisible) return
        val next = (mapFirstRow + direction).coerceIn(0, levelGraph.maxFirstRow)
        if (next == mapFirstRow) return
        mapWindow = levelGraph.window(next, if (welcomeVisible) PalmAction.CONTEXT else PalmAction.BACK)
        val action = if (direction > 0) PalmAction.NEXT_GROUP else PalmAction.PREVIOUS_GROUP
        selectedControl = mapWindow.layout.buttons.indexOfFirst { it.action == action }
        if (!dragging) resetNavigationPage()
        Log.i(TAG, "map-row=$mapFirstRow slots=${mapWindow.nodes.map { catalog.levelCode(it.index) }}")
    }

    private fun chooseSlot(slot: Int) {
        if (!levelListVisible) return
        mapWindow.nodes.getOrNull(slot)?.index?.let(::chooseLevel)
    }

    private fun lockedReason(index: Int): String {
        val missing = levelGraph.prerequisiteIndices(index).filterNot(game.progress::isCompleted)
            .sortedBy { levelGraph.nodes.first { node -> node.index == it }.row }
        return if (missing.isEmpty()) "可进入" else "需完成 " + missing.joinToString("、") { catalog.levelCode(it) }
    }

    private fun chooseLevel(index: Int) {
        if (!levelListVisible) return
        if (!game.selectLevel(index)) {
            Log.i(TAG, "level-selection-denied=${index + 1}")
            return
        }
        enterLevel()
        logState("level-selected")
    }

    private fun selectControl(direction: Int) {
        if (lesson != null || welcomeVisible) lessonControlFocused = true
        else if (!debugVisible) { debugVisible = true; resetHands() }
        val buttons = activePanelLayout().buttons
        val disabled = disabledPanelActions()
        repeat(buttons.size) {
            selectedControl = (selectedControl + direction + buttons.size) % buttons.size
            if (buttons[selectedControl].action !in disabled) return
        }
    }

    private fun command(command: InputCommand) {
        when (command) {
            InputCommand.TOGGLE_THEME -> { execute(PalmAction.TOGGLE_THEME); return }
            InputCommand.TOGGLE_LEVEL_LIST -> { showLevelList(!levelListVisible, keyboard = true); return }
            InputCommand.BACK -> {
                if (levelListVisible) backFromList()
                else if (debugVisible) { debugVisible = false; resetHands() }
                return
            }
            InputCommand.CHOOSE_LEVEL_1 -> { chooseSlot(0); return }
            InputCommand.CHOOSE_LEVEL_2 -> { chooseSlot(1); return }
            InputCommand.CHOOSE_LEVEL_3 -> { chooseSlot(2); return }
            InputCommand.CHOOSE_LEVEL_4 -> { chooseSlot(3); return }
            InputCommand.CHOOSE_LEVEL_5 -> { chooseSlot(4); return }
            InputCommand.CHOOSE_LEVEL_6 -> { chooseSlot(5); return }
            InputCommand.CHOOSE_LEVEL_7 -> { chooseSlot(6); return }
            InputCommand.CHOOSE_LEVEL_8 -> { chooseSlot(7); return }
            InputCommand.CHOOSE_LEVEL_9 -> { chooseSlot(8); return }
            InputCommand.PREVIOUS_GROUP -> { changeGroup(-1); return }
            InputCommand.NEXT_GROUP -> { changeGroup(1); return }
            InputCommand.TOGGLE_DEBUG_PANEL -> {
                if (levelListVisible) { showLevelList(false); debugVisible = false }
                else debugVisible = !debugVisible
                resetHands(); logState("debug-panel=$debugVisible"); return
            }
            InputCommand.SELECT_NEXT_CONTROL -> { selectControl(1); return }
            InputCommand.SELECT_PREVIOUS_CONTROL -> { selectControl(-1); return }
            InputCommand.ACTIVATE_CONTROL -> {
                if (welcomeVisible || debugVisible || panelPose != null) {
                    lessonControlFocused = true
                    clickFeedback = 0.18
                    execute(activePanelLayout().buttons[selectedControl].action)
                }
                return
            }
            else -> Unit
        }
        // A selector is modal for artificial movement and commands. Physical tracking stays live.
        if (levelListVisible) return
        val activeLesson = lesson
        if (activeLesson != null) {
            when (command) {
                InputCommand.NEXT_LEVEL -> lessonAction(PalmAction.CONTEXT)
                InputCommand.PREVIOUS_LESSON_STEP -> lessonAction(PalmAction.RESET_ANGLE)
                InputCommand.RESET_ANGLE, InputCommand.TOGGLE_REFERENCE -> lessonAction(PalmAction.TOGGLE_REFERENCE)
                InputCommand.RESTART_LEVEL, InputCommand.OPEN_LESSON -> {
                    activeLesson.restart(); worldDirty = true; resetHands(); selectedControl = 2
                }
                else -> Unit
            }
            return
        }
        when (command) {
            InputCommand.RESET_ANGLE -> execute(PalmAction.RESET_ANGLE)
            InputCommand.TOGGLE_REFERENCE -> execute(PalmAction.TOGGLE_REFERENCE)
            InputCommand.RESTART_LEVEL -> retryLevel()
            InputCommand.NEXT_LEVEL -> advanceAfterCompletion()
            InputCommand.OPEN_LESSON -> if (game.level.expedition != null) requestHint() else enterLevel()
            else -> Unit
        }
    }

    private fun execute(action: PalmAction) {
        if (action in disabledPanelActions()) return
        if (action == PalmAction.TOGGLE_THEME) {
            theme = theme.toggled()
            uiPreferences.edit().putBoolean(DARK_MODE, theme == UiThemeMode.DARK).apply()
            worldDirty = true
            Log.i(TAG, "theme=$theme")
            return
        }
        if (action == PalmAction.LEVEL_LIST) { showLevelList(true); return }
        if (levelListVisible) {
            when (action) {
                PalmAction.CONTEXT -> if (welcomeVisible) {
                    enterLevel(showLesson = resume.showLesson)
                    logState("start-selected")
                }
                PalmAction.BACK -> backFromList()
                PalmAction.PREVIOUS_GROUP -> changeGroup(-1)
                PalmAction.NEXT_GROUP -> changeGroup(1)
                else -> levelActions.indexOf(action).takeIf { it >= 0 }?.let(::chooseSlot)
            }
            return
        }
        if (lesson != null) { lessonAction(action); return }
        when (action) {
            PalmAction.RESET_ANGLE -> game.resetAngle()
            PalmAction.TOGGLE_REFERENCE -> if (game.level.expedition != null) requestHint() else referenceVisible = !referenceVisible
            PalmAction.CONTEXT -> if (game.completed) advanceAfterCompletion() else retryLevel()
            else -> return
        }
        worldDirty = true
        blockedFeedback = 0.0
        logState("action=$action")
    }

    private fun updateHands(
        dt: Double, elapsedSeconds: Double, head: VrTrackingMath.Pose?, left: HandLandmarks?, right: HandLandmarks?,
    ): VrHandControls.Frame {
        if (head == null) {
            resetHandTracking()
            return VrHandControls.Frame()
        }
        val debugDock = if (debugVisible && !welcomeVisible) {
            val anchor = debugAnchor ?: (head.position - head.normal * .65 - head.up * .08).also { debugAnchor = it }
            VrTrackingMath.panelPose(anchor, head)?.let { VrHandControls.Panel(it) }
        } else {
            debugAnchor = null
            null
        }
        val defaultDock = debugDock ?: activeDock(head)
        val dock = if (welcomeVisible || defaultDock == null) defaultDock else handControls.preferredPanel ?: defaultDock
        val frame = handControls.update(dt, head, left, right, dock, activePanelLayout(), disabledPanelActions(),
            interactionReady = ui.interactionReady(navigationRevision),
            allowMotion = lesson == null && !levelListVisible,
            allowPanelGestures = !welcomeVisible && !debugVisible,
            allowMapDrag = levelListVisible,
            panelHoldDeltaSeconds = elapsedSeconds)
        // A keyboard shortcut may be used while wearing a headset. Switch the hints back
        // on an intentional hand action so merely tracking a resting hand never steals focus.
        if (frame.action != null || frame.opened || frame.closed || frame.mapDragRows != 0 ||
            frame.pulling || frame.rotation.active) {
            keyboardMode.set(false)
            lessonControlFocused = false
        }
        if (rotationActive != frame.rotation.active) Log.i(TAG, "right-hand-swipe=${frame.rotation.active}")
        if (pullActive != frame.pulling) Log.i(TAG, "hand-pull=${frame.pulling}")
        rotationActive = frame.rotation.active
        pullActive = frame.pulling
        panelPose = frame.panel?.pose
        lessonPanelScale = frame.panel?.scale ?: 1.0
        pointer = frame.pointer
        dwellProgress = frame.dwellProgress
        hoveredAction = frame.hovered
        pressedAction = frame.pressed
        if (frame.opened) Log.i(TAG, "hand-panel=opened at=${panelPose?.position}")
        if (frame.closed) {
            lessonDockDismissed = lesson != null
            if (levelListVisible) backFromList()
            Log.i(TAG, "hand-panel=closed")
        }
        if (frame.mapDragRows != 0) changeGroup(frame.mapDragRows, dragging = true)
        frame.action?.let { action ->
            execute(action)
            val gesture = when {
                frame.clickedByDwell -> "hand-dwell"
                frame.clickedByPinch -> "hand-pinch"
                else -> "hand-index-poke"
            }
            Log.i(TAG, "$gesture=$action")
        }
        return frame
    }

    private fun activeDock(head: VrTrackingMath.Pose): VrHandControls.Panel? {
        if (!welcomeVisible) return lessonDock()
        welcomeDock?.let { return it }
        val pose = VrTrackingMath.startPanelPose(head) ?: return null
        return VrHandControls.Panel(pose).also {
            welcomeDock = it
            welcomeModel = VrTrackingMath.levelSpace(head).worldModel.apply { this[13] = head.position.y.toFloat() }
            worldDirty = true
            Log.i(TAG, "start-map anchor=${pose.position} level=${catalog.levelCode(resume.index)} lesson=${resume.showLesson} returning=${resume.returning} firstRow=$mapFirstRow theme=$theme")
        }
    }

    private fun lessonDock(): VrHandControls.Panel? {
        val definition = lesson?.definition ?: return null
        val anchor = lessonAnchor ?: return null
        if (lessonDockDismissed) return null
        val geometryLesson = definition.geometryTopic != null || definition.motionTopic != null
        val x = if (geometryLesson) 0.0 else .35
        val y = if (geometryLesson) GeometryLessonLayout.CONTROL_HEIGHT else -.23
        return VrHandControls.Panel(VrTrackingMath.Pose(anchor.position + anchor.right * x + anchor.normal * .60 +
            anchor.up * y, anchor.right, anchor.up, anchor.normal), scale = 1.6)
    }

    private fun clearPanelFeedback() {
        pointer = null
        dwellProgress = 0.0
        hoveredAction = null
        pressedAction = null
    }

    private fun resetPanelInput() {
        handControls.resetPanelInput()
        clearPanelFeedback()
    }

    private fun resetHandTracking() {
        handControls.resetInput()
        clearPanelFeedback()
        rotationActive = false
        pullActive = false
        panelPose = null
        debugAnchor = null
    }

    private fun resetHands() {
        handControls.reset()
        resetHandTracking()
        lessonDockDismissed = false
    }

    private fun enterLevel(showLesson: Boolean = true) {
        navigationRevision++
        effectsSceneRevision++
        mapPage = MapPage.HIDDEN
        welcomeDock = null
        welcomeModel = null
        lesson = if (showLesson && LessonTutorials.hasLesson(game.levelIndex)) LessonPlayer(game.levelIndex) else null
        lessonAnchor = null
        narratedRevision = null
        wasLessonAnimating = false
        lessonControlFocused = false
        lessonFeedbackAction = null
        selectedControl = if (lesson != null) 2 else 0
        debugVisible = false
        blockedFeedback = 0.0
        deathFeedback = 0.0
        gameplaySpace = null
        resetHands()
        worldDirty = true
        input.clearForPageChange()
        narration.stop()
        narration.setActive(focused && lesson != null)
        progressStore.saveResume(game.level.id, showLesson = lesson != null)
        // Exploration is rebased on the next valid tracked pose, including after startup tracking loss.
    }

    private fun restartGameplay(head: VrTrackingMath.Pose) {
        val space = VrTrackingMath.levelSpace(head)
        gameplaySpace = space
        worldDirty = true
        game.restartLevel(space.localPosition(head.position))
    }

    private fun respawn() {
        // The collision already restored the level. Align it in front of the user's live pose.
        gameplaySpace = VrTrackingMath.levelSpace(lastHead, game.trackedHead)
        effectsSceneRevision++
        debugVisible = false
        mapPage = MapPage.HIDDEN
        selectedControl = 0
        resetNavigationPage()
        resetHands()
        blockedFeedback = 0.0
        deathFeedback = 2.0
        worldDirty = true
        logState("respawn")
    }

    private fun contextLabel(): String = when {
        game.completed && game.nextLevelUnlocked -> "下一课"
        game.completed -> "关卡路线"
        game.hasCheckpoint && !game.completed -> "返回路标"
        else -> "重试"
    }

    private fun advanceAfterCompletion() {
        if (!game.completed) return
        if (game.nextLevel()) { enterLevel(); logState("next") }
        else showLevelList(true, keyboard = keyboardMode.get())
    }

    private fun retryLevel() {
        val restored = game.retryAtCheckpoint(gameplaySpace?.localPosition(lastHead.position))
        if (!restored) gameplaySpace = VrTrackingMath.levelSpace(lastHead, game.trackedHead)
        navigationRevision++
        effectsSceneRevision++
        input.clearForPageChange()
        resetHands()
        worldDirty = true
        blockedFeedback = 0.0
        deathFeedback = 0.0
        logState(if (restored) "checkpoint-retry" else "restart")
    }

    private fun requestHint() {
        if (game.requestHint()) {
            worldDirty = true
            logState("hint=${game.hintTier}")
        }
    }

    private fun lessonAction(action: PalmAction) {
        val player = lesson ?: return
        when (action) {
            PalmAction.RESET_ANGLE -> if (!player.previous()) return
            PalmAction.TOGGLE_REFERENCE -> player.replay()
            PalmAction.CONTEXT -> {
                if (player.isAnimating) return
                if (player.isLastStep) {
                    lesson = null
                    navigationRevision++
                    effectsSceneRevision++
                    narration.stop()
                    narration.setActive(false)
                    // Observation can involve unrestricted room-scale walking. Start the actual
                    // level under the current head, never credit tutorial walking as game progress.
                    progressStore.saveResume(game.level.id, showLesson = false)
                    restartGameplay(lastHead)
                    input.clearForPageChange()
                    debugVisible = false
                    resetHands()
                    selectedControl = 0
                    worldDirty = true
                    logState("lesson-finished")
                    return
                }
                if (!player.next()) return
            }
            else -> return
        }
        selectedControl = 2
        navigationRevision++
        lessonFeedbackAction = action
        clickFeedback = 0.18
        resetPanelInput()
        worldDirty = true
    }

    @Keep
    @Synchronized
    fun close() { narration.close(); soundtrack.close(); ui.close(); worldEffects.reset(); resetHands(); input.setEnabled(false) }

    private fun tutorialStatus(): VrTutorialStatus {
        val geometryLesson = game.level.geometryTopic != null
        val motionStudy = game.level.motionTopic != null
        val chapter = catalog.groups[catalog.groupForLevel(game.levelIndex)]
        val expedition = game.level.expedition
        val hasNext = game.nextLevelUnlocked
        val instruction = when {
            deathFeedback > 0.0 -> if (game.level.motionPuzzle == MotionPuzzle.DRIFTING_PASSAGE)
                "被漂游体撞到，已回到起点。" else "被移动墙撞到，已回到起点。"
            game.completed -> when {
                hasNext -> "本课完成。按 N 或在掌心选择「下一课」。"
                catalog.isTutorial(game.levelIndex) && game.tutorialRouteComplete -> "教程主线完成。按 N 查看游玩支线。"
                catalog.isTutorial(game.levelIndex) -> "本课完成。按 N 返回关卡路线。"
                else -> "本关完成。按 N 返回关卡路线，选择新的挑战。"
            }
            expedition != null -> game.missionInstruction ?: game.level.instruction
            geometryLesson || motionStudy -> game.observationInstruction ?: game.level.instruction
            else -> game.level.instruction
        }
        val p = game.playerFootWorld()
        val goalHidden = game.levelIndex == 1 && game.visibleSpheres().none { it.source.id == "goal" }
        val hint = if (game.completed) "T 可重玩本关 · L 选择关卡"
            else if (expedition != null) game.missionHint?.let { "提示 ${game.hintTier} / 3 · $it" }
                ?: "需要线索时，按 H 或在掌心选择「提示」。"
            else if (geometryLesson || motionStudy) game.observationHint ?: "保持当前截面，完成提示中的观察。"
            else when (game.levelIndex) {
            0 -> "沿当前平面向前约 2.4 米，走到青色旗帜。"
            1 -> if (goalHidden) "转到约 90°，金色出口会进入当前切片。"
                else "保持角度，向右靠近旗帜，再向前走；只旋转不会移动你。"
            2 -> when {
                abs(p.x) >= 3.0 || p.w <= -6.0 -> "你仍在封闭空间外；可重试，先转到 90° 再向右移动。"
                p.w > -2.9 && abs(p.y) < 0.95 -> "当前切片没有入口。转到 90°，向右移动约 1.2 米。"
                p.w > -2.9 -> "已离开墙体所在范围。沿 w 向前约 3 米，与封闭空间内部对齐。"
                abs(p.y) > 0.35 -> if (p.y > 0.0) "转到 90°，向左返回封闭空间内部，靠近绿色旗帜。" else "转到 90°，向右返回封闭空间内部，靠近绿色旗帜。"
                else -> "向前走到绿色旗帜，完成教程。"
            }
            else -> game.level.hint
        }
        val gesture = when {
            game.completed -> LessonGesture.PANEL
            goalHidden -> LessonGesture.ROTATE
            game.levelIndex == 1 -> LessonGesture.WALK
            else -> LessonTutorials.all().firstOrNull { it.levelIndex == game.levelIndex }?.steps?.last()?.gesture
        }
        return VrTutorialStatus(catalog.localNumber(game.levelIndex) - 1, chapter.levelIndices.size, game.level.title, instruction, hint,
            game.completed, if (geometryLesson || motionStudy || expedition != null) null else (p - game.level.goal.center).length(), blockedFeedback > 0.0 || game.physicalContact, keyboardMode.get(),
            locomotionLocked = game.locomotionLocked, expedition = expedition != null,
            checkpointAvailable = game.hasCheckpoint,
            progressLabel = expedition?.checkpoints?.takeIf { it.isNotEmpty() }
                ?.let { "路标 ${game.completedCheckpointIds.size} / ${it.size}" },
            levelCode = catalog.levelCode(game.levelIndex), chapterTitle = chapter.title, gesture = gesture)
    }

    private fun VrTrackingMath.Pose.renderPose() = VrPose(position, right, up, normal)
    private fun logState(reason: String) {
        val forward = lastHead.normal * -1.0
        Log.i(TAG, "$reason rawHead=${lastHead.position} viewForward=$forward levelForward=${gameplaySpace?.localVector(forward)} " +
            "playerHead4=${game.playerWorld()} playerFeet4=${game.playerFootWorld()} theta=${game.rotationDegrees} " +
            "level=${game.levelIndex + 1} completed=${game.completed} origin=${game.frame.origin} " +
            "checkpoints=${game.completedCheckpointIds} hint=${game.hintTier} chapter=${catalog.levelCode(game.levelIndex)} " +
            "motionTime=${game.motionTimeSeconds} observation=${game.observationStep}/${game.observationCount} deaths=${game.deathCount}")
    }
    private companion object {
        const val TAG = "FourthwardXR/Game"
        const val DARK_MODE = "dark_mode"
        val levelActions = PalmPanelLayout.levelActions
    }
}

/** Pure focus policy shared by both callback paths; window focus is not XR session authority. */
internal class VrFocusPolicy(private val input: InputState) {
    data class Change(val active: Boolean, val clearTransient: Boolean, val resumed: Boolean, val xrFocused: Boolean)
    private var resumed = false
    private var xrFocused = false
    private var active = false

    @Synchronized fun resumed(value: Boolean): Change { resumed = value; return reconcile() }
    @Synchronized fun xrFocused(value: Boolean): Change { xrFocused = value; return reconcile() }
    @Synchronized fun windowFocus(value: Boolean): Change {
        if (!value) { input.setEnabled(false); input.setEnabled(active) }
        return Change(active, !value, resumed, xrFocused)
    }
    private fun reconcile(): Change {
        val next = resumed && xrFocused
        val clear = active != next || !next
        active = next
        input.setEnabled(active)
        return Change(active, clear, resumed, xrFocused)
    }
}
