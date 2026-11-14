package com.fourthward.game.core

import kotlin.math.hypot

/** Device-independent input, consumed once per simulation tick. */
enum class InputCommand {
    TOGGLE_DEBUG_PANEL, RESET_ANGLE, TOGGLE_REFERENCE, RESTART_LEVEL, NEXT_LEVEL,
    SELECT_NEXT_CONTROL, SELECT_PREVIOUS_CONTROL, ACTIVATE_CONTROL,
    PREVIOUS_LESSON_STEP, OPEN_LESSON,
    TOGGLE_LEVEL_LIST, BACK, CHOOSE_LEVEL_1, CHOOSE_LEVEL_2, CHOOSE_LEVEL_3,
    CHOOSE_LEVEL_4, CHOOSE_LEVEL_5, CHOOSE_LEVEL_6,
    CHOOSE_LEVEL_7, CHOOSE_LEVEL_8, CHOOSE_LEVEL_9, PREVIOUS_GROUP, NEXT_GROUP,
}

enum class InputKey {
    FORWARD, BACKWARD, LEFT, RIGHT, ROTATE_LEFT, ROTATE_RIGHT,
    MENU, RESET, REFERENCE, RESTART, NEXT_LEVEL, SELECT, ACTIVATE,
    PREVIOUS_STEP, LESSON,
    LEVEL_LIST, BACK, LEVEL_1, LEVEL_2, LEVEL_3,
    LEVEL_4, LEVEL_5, LEVEL_6, LEVEL_7, LEVEL_8, LEVEL_9, PREVIOUS_GROUP, NEXT_GROUP,
}

data class InputFrame(
    val moveRightMeters: Double = 0.0,
    val moveForwardMeters: Double = 0.0,
    val thetaRadians: Double = 0.0,
    val commands: List<InputCommand> = emptyList(),
)

/** A copied controller sample; the adapter must mark absent controllers as disconnected. */
data class InputControllerState(
    val leftConnected: Boolean = false,
    val rightConnected: Boolean = false,
    val leftX: Double = 0.0,
    val leftY: Double = 0.0,
    val rightX: Double = 0.0,
    val menu: Boolean = false,
    val activate: Boolean = false,
    val reset: Boolean = false,
    val reference: Boolean = false,
    val restart: Boolean = false,
    val nextLevel: Boolean = false,
    val selectNext: Boolean = false,
)

/**
 * No Android or SDK dependency. Event callbacks may run on the tracking thread; frame() runs on
 * the scene thread. Focus changes clear both held input and queued actions, never replaying them.
 */
class InputState(
    private val walkMetersPerSecond: Double = 1.5,
    private val thetaRadiansPerSecond: Double = 1.0,
    private val deadzone: Double = 0.18,
    private val controllerTimeoutNanos: Long = 350_000_000L,
) {
    init {
        require(walkMetersPerSecond.isFinite() && walkMetersPerSecond >= 0.0)
        require(thetaRadiansPerSecond.isFinite() && thetaRadiansPerSecond >= 0.0)
        require(deadzone.isFinite() && deadzone >= 0.0 && deadzone < 1.0)
        require(controllerTimeoutNanos > 0)
    }

    private var enabled = false
    private val held = mutableSetOf<InputKey>()
    // Preserve a down/up pair that occurs between two frames (e.g. adb input keyevent Q).
    private val pendingContinuous = mutableSetOf<InputKey>()
    private val commands = ArrayDeque<InputCommand>()
    private var controller: InputControllerState? = null
    private var controllerAtNanos = 0L

    @Synchronized
    fun setEnabled(value: Boolean) {
        if (enabled != value || !value) clear()
        enabled = value
    }

    @Synchronized
    fun key(key: InputKey, pressed: Boolean, repeat: Boolean = false, shift: Boolean = false) {
        if (!pressed) {
            held.remove(key)
            return
        }
        if (!enabled || repeat || !held.add(key)) return
        val command = when (key) {
            InputKey.MENU -> InputCommand.TOGGLE_DEBUG_PANEL
            InputKey.RESET -> InputCommand.RESET_ANGLE
            InputKey.REFERENCE -> InputCommand.TOGGLE_REFERENCE
            InputKey.RESTART -> InputCommand.RESTART_LEVEL
            InputKey.NEXT_LEVEL -> InputCommand.NEXT_LEVEL
            InputKey.SELECT -> if (shift) InputCommand.SELECT_PREVIOUS_CONTROL else InputCommand.SELECT_NEXT_CONTROL
            InputKey.ACTIVATE -> InputCommand.ACTIVATE_CONTROL
            InputKey.PREVIOUS_STEP -> InputCommand.PREVIOUS_LESSON_STEP
            InputKey.LESSON -> InputCommand.OPEN_LESSON
            InputKey.LEVEL_LIST -> InputCommand.TOGGLE_LEVEL_LIST
            InputKey.BACK -> InputCommand.BACK
            InputKey.LEVEL_1 -> InputCommand.CHOOSE_LEVEL_1
            InputKey.LEVEL_2 -> InputCommand.CHOOSE_LEVEL_2
            InputKey.LEVEL_3 -> InputCommand.CHOOSE_LEVEL_3
            InputKey.LEVEL_4 -> InputCommand.CHOOSE_LEVEL_4
            InputKey.LEVEL_5 -> InputCommand.CHOOSE_LEVEL_5
            InputKey.LEVEL_6 -> InputCommand.CHOOSE_LEVEL_6
            InputKey.LEVEL_7 -> InputCommand.CHOOSE_LEVEL_7
            InputKey.LEVEL_8 -> InputCommand.CHOOSE_LEVEL_8
            InputKey.LEVEL_9 -> InputCommand.CHOOSE_LEVEL_9
            InputKey.PREVIOUS_GROUP -> InputCommand.PREVIOUS_GROUP
            InputKey.NEXT_GROUP -> InputCommand.NEXT_GROUP
            else -> null
        }
        if (command != null) enqueue(command) else pendingContinuous.add(key)
    }

    @Synchronized
    fun controller(sample: InputControllerState?, nowNanos: Long) {
        if (!enabled || sample == null) {
            controller = null
            return
        }
        val old = controller?.takeIf { isFresh(nowNanos) }
        fun rising(current: Boolean, previous: Boolean, command: InputCommand) {
            if (current && !previous) enqueue(command)
        }
        rising(sample.leftConnected && sample.menu, old?.let { it.leftConnected && it.menu } == true, InputCommand.TOGGLE_DEBUG_PANEL)
        rising(sample.rightConnected && sample.activate, old?.let { it.rightConnected && it.activate } == true, InputCommand.ACTIVATE_CONTROL)
        rising(sample.rightConnected && sample.reset, old?.let { it.rightConnected && it.reset } == true, InputCommand.RESET_ANGLE)
        rising(sample.leftConnected && sample.reference, old?.let { it.leftConnected && it.reference } == true, InputCommand.TOGGLE_REFERENCE)
        rising(sample.leftConnected && sample.restart, old?.let { it.leftConnected && it.restart } == true, InputCommand.RESTART_LEVEL)
        rising(sample.rightConnected && sample.nextLevel, old?.let { it.rightConnected && it.nextLevel } == true, InputCommand.NEXT_LEVEL)
        rising(sample.leftConnected && sample.selectNext, old?.let { it.leftConnected && it.selectNext } == true, InputCommand.SELECT_NEXT_CONTROL)
        controller = sample
        controllerAtNanos = nowNanos
    }

    @Synchronized
    fun frame(deltaSeconds: Double, nowNanos: Long): InputFrame {
        if (!enabled) return InputFrame()
        val dt = if (deltaSeconds.isFinite()) deltaSeconds.coerceIn(0.0, 0.05) else 0.0
        val keys = held + pendingContinuous
        fun axis(positive: InputKey, negative: InputKey) =
            (if (positive in keys) 1.0 else 0.0) - (if (negative in keys) 1.0 else 0.0)
        val pad = controller?.takeIf { isFresh(nowNanos) }
        if (pad == null) controller = null
        val stick = if (pad?.leftConnected == true) radialDeadzone(pad.leftX, pad.leftY) else Pair(0.0, 0.0)
        var right = axis(InputKey.RIGHT, InputKey.LEFT) + stick.first
        var forward = axis(InputKey.FORWARD, InputKey.BACKWARD) + stick.second
        val movementLength = hypot(right, forward)
        if (movementLength > 1.0) { right /= movementLength; forward /= movementLength }
        val controllerTheta = if (pad?.rightConnected == true) radialDeadzone(pad.rightX, 0.0).first else 0.0
        val theta = (axis(InputKey.ROTATE_RIGHT, InputKey.ROTATE_LEFT) + controllerTheta).coerceIn(-1.0, 1.0)
        val queued = commands.toList()
        commands.clear()
        if (dt > 0.0) pendingContinuous.clear()
        return InputFrame(
            moveRightMeters = right * walkMetersPerSecond * dt,
            moveForwardMeters = forward * walkMetersPerSecond * dt,
            thetaRadians = theta * thetaRadiansPerSecond * dt,
            commands = queued,
        )
    }

    private fun isFresh(nowNanos: Long): Boolean {
        val age = nowNanos - controllerAtNanos
        return age >= 0L && age <= controllerTimeoutNanos
    }

    private fun radialDeadzone(rawX: Double, rawY: Double): Pair<Double, Double> {
        if (!rawX.isFinite() || !rawY.isFinite()) return Pair(0.0, 0.0)
        val x = rawX.coerceIn(-1.0, 1.0)
        val y = rawY.coerceIn(-1.0, 1.0)
        val length = hypot(x, y)
        if (length <= deadzone) return Pair(0.0, 0.0)
        val magnitude = ((length.coerceAtMost(1.0) - deadzone) / (1.0 - deadzone))
        return Pair(x / length * magnitude, y / length * magnitude)
    }

    private fun enqueue(command: InputCommand) {
        if (commands.size < 32) commands.addLast(command)
    }

    /** Switching a page cancels movement keys, but a held trigger must remain held.
     * Otherwise opening the selector would also activate its first row next frame. */
    @Synchronized
    fun clearForPageChange() {
        held.clear()
        pendingContinuous.clear()
        commands.clear()
    }

    private fun clear() {
        held.clear()
        pendingContinuous.clear()
        commands.clear()
        controller = null
    }
}
