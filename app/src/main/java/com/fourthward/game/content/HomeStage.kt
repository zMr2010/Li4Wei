package com.fourthward.game.content

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.platform.LocalPhysicalLengthConverter
import com.pico.spatial.ui.platform.LengthUnit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.fourthward.game.ui.FourthwardPanel
import com.fourthward.game.core.PalmPanelLayout
import com.fourthward.game.ui.AngleIndicator
import com.fourthward.game.ui.ANGLE_INDICATOR_WIDTH_DP
import com.fourthward.game.ui.TutorialIndicator
import com.fourthward.game.ui.TUTORIAL_INDICATOR_WIDTH_DP
import com.fourthward.game.content.input.StageGameInput
import com.fourthward.game.content.input.GameInputSurface
import com.fourthward.game.rendering.GameEnvironment
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.foundation.dsl.registerSystem
import com.pico.spatial.ui.foundation.dsl.unregisterSystem
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

@Composable
fun HomeStage() {
    val session = remember { GameSession() }
    val environment = remember { GameEnvironment() }
    val panel = session.panel
    val angle = session.angleDegrees
    val tutorial = session.tutorial
    StageGameInput(session.input)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, session) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> session.startTracking()
                Lifecycle.Event.ON_PAUSE -> session.pauseTracking()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(session, environment) {
        try {
            ActiveSession.current = session
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) session.startTracking()
            registerSystem<FourthwardSystem>()
            environment.prepare()
            session.renderLoop()
        } finally {
            withContext(NonCancellable) {
                ActiveSession.current = null
                runCatching { unregisterSystem<FourthwardSystem>() }
                try {
                    session.close()
                } finally {
                    environment.close()
                }
            }
        }
    }
    SpatialView(
        initial = { content, attachments ->
            content.addEntity(environment.root)
            content.addEntity(session.renderer.root)
            content.addEntity(session.angleHud.root)
            content.addEntity(session.tutorialHud.root)
            attachments.entity("fourthward_controls")?.let(session::attachControls)
            attachments.entity("fourthward_angle")?.let(session.angleHud::attach)
            attachments.entity("fourthward_tutorial")?.let(session.tutorialHud::attach)
            session.attachScene()
        },
        attachments = {
            AttachmentPanel(id = "fourthward_controls") {
                GameInputSurface(session.input) {
                val converter = LocalPhysicalLengthConverter.current
                val baseWidth = converter.dpToLength(PalmPanelLayout.WIDTH_DP.dp, LengthUnit.Meters)
                val baseHeight = converter.dpToLength(PalmPanelLayout.HEIGHT_DP.dp, LengthUnit.Meters)
                SideEffect { session.setPanelBaseSize(baseWidth, baseHeight) }
                // Physical activation is determined by real LEFT index-tip plane contact.
                // Consume the parallel native pointer stream to prevent a second click on release.
                Box(Modifier.pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                    }
                }) { FourthwardPanel(panel, session::performPalmAction) }
                }
            }
            AttachmentPanel(id = "fourthward_angle") {
                GameInputSurface(session.input, Modifier.alpha(if (session.angleHud.overlayReady) 0f else 1f)) {
                val baseWidth = LocalPhysicalLengthConverter.current.dpToLength(ANGLE_INDICATOR_WIDTH_DP.dp, LengthUnit.Meters)
                SideEffect { session.angleHud.setBaseWidth(baseWidth); session.angleHud.update(angle) }
                AngleIndicator(angle)
                }
            }
            AttachmentPanel(id = "fourthward_tutorial") {
                GameInputSurface(session.input, Modifier.alpha(if (session.tutorialHud.overlayReady) 0f else 1f)) {
                val baseWidth = LocalPhysicalLengthConverter.current.dpToLength(TUTORIAL_INDICATOR_WIDTH_DP.dp, LengthUnit.Meters)
                SideEffect { session.tutorialHud.setBaseWidth(baseWidth); session.tutorialHud.update(tutorial) }
                TutorialIndicator(tutorial)
                }
            }
        },
    )
}

