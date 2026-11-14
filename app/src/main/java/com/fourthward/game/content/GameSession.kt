package com.fourthward.game.content

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.fourthward.game.content.input.SpatialGameInput
import com.fourthward.game.core.InputCommand
import com.fourthward.game.core.PalmPanelLayout
import com.fourthward.game.ui.TutorialStatus
import com.fourthward.game.core.FourthwardGame
import com.fourthward.game.core.HandSwipeRotationState
import com.fourthward.game.core.PalmAction
import com.fourthward.game.core.Vec3
import com.fourthward.game.rendering.SliceSceneRenderer
import com.fourthward.game.ui.PanelState
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.SceneUpdateContext
import com.pico.spatial.core.ecs.System
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.tracking.DataProvider
import com.pico.spatial.tracking.hmd.HMDTrackingData
import com.pico.spatial.tracking.hmd.HMDTrackingProvider
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel

internal object ActiveSession { var current: GameSession? = null }

class FourthwardSystem : System() {
    override fun update(context: SceneUpdateContext) {
        ActiveSession.current?.tick(context.deltaTime.toDouble())
    }
}

/** One affine slice through the player. All input devices share the same simulation. */
class GameSession {
    val game = FourthwardGame()
    val renderer = SliceSceneRenderer()
    val handPanel = RightPalmPanel(renderer.root)
    val angleHud = AngleHud()
    val tutorialHud = TutorialHud()
    val input = SpatialGameInput()
    var tutorial by mutableStateOf(tutorialStatus())
        private set
    var angleDegrees by mutableStateOf(0.0)
        private set
    var panel by mutableStateOf(PanelState())
        private set
    private val hmd = HMDTrackingProvider()
    private data class HeadSample(val data: HMDTrackingData, val atNanos: Long)
    private val receivedPose = AtomicReference<HeadSample?>(null)
    private val poseListener = DataProvider.DataListener<HMDTrackingData> {
        receivedPose.set(HeadSample(it, java.lang.System.nanoTime()))
    }
    private var tracked = false
    private var lastHead = Vec3.ZERO
    private var cameraRight: Vec3? = null
    private var sampleClock = 0.0
    private var rotationClock = 0.0
    private var pendingMovement = Vec3.ZERO
    private var headInitialized = false
    private var selectedControl = 0
    private var clickFeedbackSeconds = 0.0
    private var blockedSeconds = 0.0
    private var pendingRotation = 0.0
    private var closed = false
    private var paused = false
    private var revision = 0L
    private var sceneAttached = false
    private var renderBusy = false
    private var referenceVisible = true
    private val renders = Channel<Unit>(Channel.CONFLATED)

    fun startTracking() {
        if (closed) return
        paused = false
        handPanel.start()
        if (tracked) return
        try {
            hmd.addListener(poseListener)
            hmd.start()
            tracked = true
        } catch (error: Exception) {
            hmd.removeListener(poseListener)
            receivedPose.set(null)
            Log.w("Fourthward", "HMD tracking could not start", error)
        }
    }

    fun pauseTracking() {
        paused = true
        pendingRotation = 0.0
        pendingMovement = Vec3.ZERO
        rotationClock = 0.0
        handPanel.stop()
        if (tracked) {
            hmd.removeListener(poseListener)
            hmd.stop()
            tracked = false
            receivedPose.set(null)
        }
    }

    fun attachControls(entity: Entity) { handPanel.attach(entity) }
    fun setPanelBaseSize(width: Float, height: Float) { handPanel.setBaseSize(width, height) }

    fun performPalmAction(action: PalmAction) {
        if (closed || paused || !handPanel.visible) return
        executeAction(action)
    }

    private fun executeAction(action: PalmAction) {
        pendingRotation = 0.0
        pendingMovement = Vec3.ZERO
        when (action) {
            PalmAction.RESET_ANGLE -> game.resetAngle()
            PalmAction.TOGGLE_REFERENCE -> referenceVisible = !referenceVisible
            PalmAction.CONTEXT -> {
                if (game.completed && game.levelIndex + 1 < game.totalLevels) game.nextLevel()
                else game.restartLevel()
            }
            PalmAction.LEVEL_LIST, PalmAction.BACK, PalmAction.LEVEL_1,
            PalmAction.LEVEL_2, PalmAction.LEVEL_3, PalmAction.LEVEL_4, PalmAction.LEVEL_5, PalmAction.LEVEL_6,
            PalmAction.PREVIOUS_GROUP, PalmAction.NEXT_GROUP -> return // Native OpenXR menu only.
        }
        publish()
        requestRender()
        logState("action=$action")
    }

    private fun handleCommand(command: InputCommand) {
        when (command) {
            InputCommand.PREVIOUS_LESSON_STEP, InputCommand.OPEN_LESSON,
            InputCommand.TOGGLE_LEVEL_LIST, InputCommand.BACK, InputCommand.CHOOSE_LEVEL_1,
            InputCommand.CHOOSE_LEVEL_2, InputCommand.CHOOSE_LEVEL_3, InputCommand.CHOOSE_LEVEL_4,
            InputCommand.CHOOSE_LEVEL_5, InputCommand.CHOOSE_LEVEL_6,
            InputCommand.CHOOSE_LEVEL_7, InputCommand.CHOOSE_LEVEL_8, InputCommand.CHOOSE_LEVEL_9,
            InputCommand.PREVIOUS_GROUP, InputCommand.NEXT_GROUP -> Unit // Retained legacy Spatial host.
            InputCommand.TOGGLE_DEBUG_PANEL -> handPanel.setDebugVisible(!handPanel.debugVisible)
            InputCommand.RESET_ANGLE -> executeAction(PalmAction.RESET_ANGLE)
            InputCommand.TOGGLE_REFERENCE -> executeAction(PalmAction.TOGGLE_REFERENCE)
            InputCommand.RESTART_LEVEL -> {
                pendingMovement = Vec3.ZERO; pendingRotation = 0.0
                game.restartLevel(); requestRender(); publish(); logState("restart")
            }
            InputCommand.NEXT_LEVEL -> if (game.nextLevel()) {
                pendingMovement = Vec3.ZERO; pendingRotation = 0.0
                requestRender(); publish(); logState("next")
            }
            InputCommand.SELECT_NEXT_CONTROL -> {
                if (!handPanel.debugVisible) handPanel.setDebugVisible(true)
                selectedControl = (selectedControl + 1) % PalmPanelLayout.buttons.size
            }
            InputCommand.SELECT_PREVIOUS_CONTROL -> {
                if (!handPanel.debugVisible) handPanel.setDebugVisible(true)
                selectedControl = (selectedControl + PalmPanelLayout.buttons.size - 1) % PalmPanelLayout.buttons.size
            }
            InputCommand.ACTIVATE_CONTROL -> if (handPanel.visible || handPanel.debugVisible) {
                clickFeedbackSeconds = 0.18
                executeAction(PalmPanelLayout.buttons[selectedControl].action)
            }
        }
    }

    fun attachScene() {
        sceneAttached = true
        requestRender()
    }

    private fun requestRender() {
        if (!closed) {
            revision++
            renders.trySend(Unit)
        }
    }

    suspend fun renderLoop() {
        try {
            handPanel.prepare()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w("Fourthward", "Fingertip visual unavailable", error)
        }
        for (signal in renders) {
            val expectedRevision = revision
            renderBusy = true
            try {
                renderer.refresh(game, referenceVisible) { !closed && revision == expectedRevision }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e("Fourthward", "Slice rendering failed", error)
            } finally {
                renderBusy = false
            }
        }
    }

    fun tick(deltaSeconds: Double) {
        if (closed || paused) return
        val dt = if (deltaSeconds.isFinite()) deltaSeconds.coerceIn(0.0, 0.1) else 0.0
        clickFeedbackSeconds = (clickFeedbackSeconds - dt).coerceAtLeast(0.0)
        blockedSeconds = (blockedSeconds - dt).coerceAtLeast(0.0)
        val headReady = sampleHead()
        val controls = input.frame(dt)
        controls.commands.forEach(::handleCommand)
        val hand = handPanel.update(dt, if (headReady) lastHead else null, cameraRight)
        // Controls are expressed in the slice's (s,w) plane; gravity Z has no artificial input.
        pendingMovement += Vec3(controls.moveRightMeters, 0.0, -controls.moveForwardMeters)
        pendingRotation += controls.thetaRadians
        if (hand.active && controls.thetaRadians == 0.0) pendingRotation += hand.deltaRadians
        handPanel.takeAction()?.let(::performPalmAction)
        rotationClock = (rotationClock + dt).coerceAtMost(0.05)
        if (!renderBusy && rotationClock >= 0.05) {
            rotationClock = 0.0
            var changed = false
            val wasComplete = game.completed
            if (headReady && (!headInitialized || (lastHead - game.trackedHead).lengthSquared() > 1e-8)) {
                val result = game.updateTrackedHead(lastHead)
                headInitialized = true
                changed = result.changed
                if (result.blocked) blockedSeconds = 0.8
            }
            if (kotlin.math.abs(pendingRotation) > 1e-7) {
                if (game.rotate(pendingRotation)) changed = true else blockedSeconds = 0.8
                pendingRotation = 0.0
            }
            if (pendingMovement.lengthSquared() > 1e-10) {
                val result = game.movePlanar(pendingMovement)
                changed = changed || result.changed
                if (result.blocked) blockedSeconds = 0.8
                pendingMovement = Vec3.ZERO
            }
            if (changed) requestRender()
            if (!wasComplete && game.completed) logState("completed")
        }
        publishPanel()
        sampleClock += dt
        if (sampleClock >= 0.1) { sampleClock = 0.0; publish() }
    }

    private fun tutorialStatus() = TutorialStatus(
        levelIndex = game.levelIndex,
        totalLevels = game.totalLevels,
        title = game.level.title,
        instruction = if (game.completed) {
            if (game.levelIndex + 1 < game.totalLevels) "已抵达终点。按 N 或在掌心选择「下一关」。"
            else "三个教程已完成。你已学会移动、转向和绕过四维障碍。"
        } else game.level.instruction,
        hint = if (game.completed) "T 可重玩本关" else tutorialHint(),
        completed = game.completed,
        distanceMeters = (game.playerFootWorld() - game.level.goal.center).length(),
        blocked = blockedSeconds > 0.0,
        keyboardMode = true,
    )

    private fun tutorialHint(): String {
        val p = game.playerFootWorld()
        return when (game.levelIndex) {
            0 -> "W/S 沿 w 前后移动；A/D 沿当前截线左右移动。"
            1 -> if (game.visibleSpheres().none { it.source.id == "goal" })
                "按 E 转到约 90°，金色出口会进入当前切片。"
            else "保持角度，向右靠近旗帜，再向前走；只旋转不会移动你。"
            2 -> when {
                p.w > -2.9 && kotlin.math.abs(p.y) < 0.95 -> "转到 90°，向右移动约 1.2 米，离开墙的范围。"
                p.w > -2.9 -> "现在沿 w 向前走约 3 米，来到墙后。"
                kotlin.math.abs(p.y) > 0.35 -> if (p.y > 0.0)
                    "转到 90°，向左回到绿色旗帜所在方向。" else
                    "转到 90°，向右回到绿色旗帜所在方向。"
                else -> "向前走到绿色旗帜，完成教程。"
            }
            else -> game.level.hint
        }
    }

    private fun publish() {
        angleDegrees = game.rotationDegrees
        tutorial = tutorialStatus()
        publishPanel()
    }

    private fun publishPanel() {
        val selected = PalmPanelLayout.buttons[selectedControl].action
        panel = PanelState(
            referenceVisible = referenceVisible,
            hoveredAction = if (handPanel.debugVisible) selected else handPanel.hoveredAction,
            pressedAction = if (handPanel.debugVisible && clickFeedbackSeconds > 0.0) selected else handPanel.pressedAction,
            pointer = handPanel.pointer,
            debugMode = handPanel.debugVisible,
            contextLabel = if (game.completed && game.levelIndex + 1 < game.totalLevels) "下一关" else "重试",
        )
    }

    private fun logState(reason: String) {
        Log.i("Fourthward", "$reason level=${game.levelIndex + 1} theta=${game.rotationDegrees} player=${game.playerFootWorld()} completed=${game.completed} reference=$referenceVisible")
    }

    private fun sampleHead(): Boolean {
        cameraRight = null
        if (sceneAttached && tracked && hmd.state == DataProvider.State.STARTED) {
            val sample = receivedPose.get() ?: return false
            if ((java.lang.System.nanoTime() - sample.atNanos) > 250_000_000L) return false
            val p = renderer.root.convertPositionFrom(sample.data.hmdPose.position, null)
            if (p.x.isFinite() && p.y.isFinite() && p.z.isFinite()) {
                lastHead = Vec3(p.x.toDouble(), p.y.toDouble(), p.z.toDouble())
                // -Z is HMD forward. Keep the gesture's horizontal axis independent of head roll.
                val forward = renderer.root.convertRotationFrom(sample.data.hmdPose.rotation, null)
                    .rotateVector(Vector3(0f, 0f, -1f))
                val length = kotlin.math.hypot(forward.x.toDouble(), forward.z.toDouble())
                if (length.isFinite() && length > 1e-4) {
                    cameraRight = Vec3(-forward.z / length, 0.0, forward.x / length)
                }
                return true
            }
        }
        return false
    }

    fun close() {
        if (closed) return
        closed = true
        pendingRotation = 0.0
        renders.close()
        handPanel.close()
        input.stop()
        try {
            if (tracked) {
                hmd.removeListener(poseListener)
                hmd.stop()
            }
            receivedPose.set(null)
        } finally {
            try { angleHud.close(); tutorialHud.close() } finally { renderer.close() }
        }
    }
}
