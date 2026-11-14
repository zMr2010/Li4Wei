package com.fourthward.game.content.input

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import android.view.KeyEvent
import android.view.ViewTreeObserver
import android.view.Window
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.fourthward.game.core.InputControllerState
import com.fourthward.game.core.InputFrame
import com.fourthward.game.core.InputKey
import com.fourthward.game.core.InputState
import com.pico.spatial.tracking.DataProvider
import com.pico.spatial.tracking.controller.ControllerTrackingData
import com.pico.spatial.tracking.controller.ControllerTrackingProvider
import java.util.concurrent.atomic.AtomicReference

/**
 * Native Full Stage input. Install GameInputSurface inside every AttachmentPanel, since each
 * native attachment owns an Android input window independent of the Activity's main window.
 * Controller Y/B are deliberately unmapped: SDK 6.2.8 reserves them for system Back.
 */
class SpatialGameInput {
    /** Sticky for this session so keyboard help does not disappear when the user releases a key. */
    var keyboardMode: Boolean = false
        private set
    private val state = InputState()
    private val provider = ControllerTrackingProvider()
    private data class PoseSample(val data: ControllerTrackingData, val atNanos: Long)
    private val pose = AtomicReference<PoseSample?>(null)
    @Volatile private var running = false
    @Volatile private var focused = false
    private var mainWindowFocused = false
    private val focusedSurfaces = mutableSetOf<Any>()
    private var listening = false

    private val poseListener = DataProvider.DataListener<ControllerTrackingData> {
        if (running) pose.set(PoseSample(it, java.lang.System.nanoTime()))
    }
    private val actionListener = ControllerTrackingProvider.ControllerActionListener { action ->
        if (running && focused) {
            val now = java.lang.System.nanoTime()
            val tracked = pose.get()?.takeIf { now - it.atNanos in 0L..350_000_000L }?.data
            state.controller(
                InputControllerState(
                    leftConnected = tracked?.left != null,
                    rightConnected = tracked?.right != null,
                    leftX = action.left.thumbstickValue.x.toDouble(),
                    leftY = action.left.thumbstickValue.y.toDouble(),
                    rightX = action.right.thumbstickValue.x.toDouble(),
                    menu = action.left.xButtonPressed,
                    activate = action.right.aButtonPressed || action.right.triggerPressed,
                    reset = action.right.gripPressed,
                    reference = action.left.gripPressed,
                    restart = action.left.thumbstickPressed,
                    nextLevel = action.right.thumbstickPressed,
                    selectNext = action.left.triggerPressed,
                ), now,
            )
        }
    }

    fun start() {
        if (running) return
        running = true
        state.setEnabled(focused)
        try {
            provider.addListener(poseListener)
            provider.addControllerActionListener(actionListener)
            listening = true
            val result = provider.start()
            Log.i(TAG, "Controller input start=$result support=${provider.supportState}")
        } catch (error: Exception) {
            stopProvider()
            Log.w(TAG, "Controller input unavailable; keyboard remains active", error)
        }
    }

    fun stop() {
        running = false
        state.setEnabled(false)
        stopProvider()
    }

    fun setWindowFocused(value: Boolean) {
        mainWindowFocused = value
        updateFocus()
    }

    internal fun setSurfaceFocused(owner: Any, value: Boolean) {
        if (value) focusedSurfaces.add(owner) else focusedSurfaces.remove(owner)
        updateFocus()
    }

    private fun updateFocus() {
        focused = mainWindowFocused || focusedSurfaces.isNotEmpty()
        state.setEnabled(running && focused)
    }

    fun frame(deltaSeconds: Double): InputFrame {
        val now = java.lang.System.nanoTime()
        val sample = pose.get()
        if (!listening || provider.state != DataProvider.State.STARTED || sample == null ||
            now - sample.atNanos !in 0L..350_000_000L
        ) state.controller(null, now)
        return state.frame(deltaSeconds, now)
    }

    /** Return false for unmapped keys so Android/system shortcuts keep their normal behavior. */
    fun onKeyEvent(event: KeyEvent): Boolean {
        val key = keyForCode(event.keyCode) ?: return false
        if (!running) return false
        if (event.action == KeyEvent.ACTION_DOWN) {
            keyboardMode = true
            // A fresh key delivered to the Stage's actual window proves it owns keyboard input.
            // Native ViewLink windows can forward keys before the host's focus callback arrives.
            if (!focused && event.repeatCount == 0) {
                focused = true
                state.setEnabled(true)
            }
        }
        when (event.action) {
            KeyEvent.ACTION_DOWN -> state.key(key, true, event.repeatCount > 0, event.isShiftPressed)
            KeyEvent.ACTION_UP -> state.key(key, false)
            else -> return false
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            Log.d(TAG, "keyboard=$key focused=$focused")
        }
        return true
    }

    private fun stopProvider() {
        // Remove both callbacks, including a partially completed start.
        runCatching { provider.removeControllerActionListener(actionListener) }
        runCatching { provider.removeListener(poseListener) }
        if (listening) runCatching { provider.stop() }
        listening = false
        pose.set(null)
        state.controller(null, java.lang.System.nanoTime())
    }

    private fun keyForCode(code: Int): InputKey? = when (code) {
        KeyEvent.KEYCODE_W -> InputKey.FORWARD
        KeyEvent.KEYCODE_S -> InputKey.BACKWARD
        KeyEvent.KEYCODE_A -> InputKey.LEFT
        KeyEvent.KEYCODE_D -> InputKey.RIGHT
        KeyEvent.KEYCODE_Q -> InputKey.ROTATE_LEFT
        KeyEvent.KEYCODE_E -> InputKey.ROTATE_RIGHT
        KeyEvent.KEYCODE_M -> InputKey.MENU
        KeyEvent.KEYCODE_R -> InputKey.RESET
        KeyEvent.KEYCODE_V -> InputKey.REFERENCE
        KeyEvent.KEYCODE_T -> InputKey.RESTART
        KeyEvent.KEYCODE_N -> InputKey.NEXT_LEVEL
        KeyEvent.KEYCODE_TAB -> InputKey.SELECT
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> InputKey.ACTIVATE
        else -> null
    }

    private companion object { const val TAG = "FourthwardInput" }
}

/**
 * Call once in HomeStage, outside AttachmentPanel. The actual Stage host Activity comes from its
 * LocalView (the SDK creates it with ComponentActivity.setContent), never from a launch stub.
 * This covers the Activity's window. GameInputSurface additionally covers native attachment
 * windows, which dispatch directly to their own Compose owners and bypass this callback.
 */
@Composable
fun StageGameInput(input: SpatialGameInput) {
    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(view, lifecycleOwner, input) {
        val activity = requireNotNull(view.context.findActivity()) { "Stage input requires an Activity host" }
        val window = activity.window
        val previous = requireNotNull(window.callback) { "Stage host has no Window.Callback" }
        var installed = true
        val callback = object : Window.Callback by previous {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean =
                (installed && input.onKeyEvent(event)) || previous.dispatchKeyEvent(event)

            override fun onWindowFocusChanged(hasFocus: Boolean) {
                if (installed) input.setWindowFocused(hasFocus)
                previous.onWindowFocusChanged(hasFocus)
            }
        }
        window.callback = callback
        fun resume() {
            input.setWindowFocused(view.hasWindowFocus())
            input.start()
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> resume()
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY -> input.stop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) resume()
        onDispose {
            installed = false
            lifecycleOwner.lifecycle.removeObserver(observer)
            input.stop()
            if (window.callback === callback) window.callback = previous
        }
    }
}

/**
 * Wrap the content of EVERY AttachmentPanel, including the always-visible HUD. PICO creates a
 * separate Sys2996 Android input window for each attachment. Giving each Compose owner a local
 * focus target means whichever window the OS focuses can receive keyboard controls; Entity
 * visibility and palm tracking are irrelevant to routing. This does not steal OS window focus.
 */
@Composable
fun GameInputSurface(
    input: SpatialGameInput,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val view = LocalView.current
    val requester = remember { FocusRequester() }
    val owner = remember { Any() }
    DisposableEffect(view, input, owner) {
        var mounted = true
        val observer = view.viewTreeObserver
        val listener = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
            input.setSurfaceFocused(owner, hasFocus)
            if (hasFocus) view.post { if (mounted) requester.requestFocus() }
        }
        observer.addOnWindowFocusChangeListener(listener)
        input.setSurfaceFocused(owner, view.hasWindowFocus())
        onDispose {
            mounted = false
            if (observer.isAlive) observer.removeOnWindowFocusChangeListener(listener)
            input.setSurfaceFocused(owner, false)
        }
    }
    LaunchedEffect(view, requester) {
        withFrameNanos { }
        view.requestFocus()
        requester.requestFocus()
        Log.i("FourthwardInput", "attachment keyboard receiver attached windowFocus=${view.hasWindowFocus()}")
    }
    Box(
        modifier
            .onPreviewKeyEvent { input.onKeyEvent(it.nativeKeyEvent) }
            .focusRequester(requester)
            .focusable(),
        content = content,
    )
}

private fun Context.findActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        val next = current.baseContext
        if (next === current) break
        current = next
    }
    return current as? Activity
}
