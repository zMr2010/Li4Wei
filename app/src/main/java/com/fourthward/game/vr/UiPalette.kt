package com.fourthward.game.vr

import com.fourthward.game.core.UiThemeMode

internal data class UiPalette(
    val surface: Int, val raised: Int, val inset: Int,
    val ink: Int, val muted: Int, val line: Int,
    val selected: Int, val onSelected: Int,
    val success: Int, val warning: Int,
    val accent: Int = 0xfff9d240.toInt(),
    val onAccent: Int = 0xff232827.toInt(),
)

private val light = UiPalette(
    surface = 0xffeeeee7.toInt(), raised = 0xfffafaf5.toInt(), inset = 0xffdedfd6.toInt(),
    ink = 0xff232827.toInt(), muted = 0xff515a55.toInt(), line = 0xff818980.toInt(),
    selected = 0xff353c38.toInt(), onSelected = 0xfff8f8ef.toInt(),
    success = 0xff27634e.toInt(), warning = 0xff8e401e.toInt(),
)
private val dark = UiPalette(
    surface = 0xff272c2c.toInt(), raised = 0xff343a39.toInt(), inset = 0xff1e2323.toInt(),
    ink = 0xfff1f1e7.toInt(), muted = 0xffbbc3b9.toInt(), line = 0xff7c877f.toInt(),
    selected = 0xffe1e5d9.toInt(), onSelected = 0xff252b26.toInt(),
    success = 0xff93dbb6.toInt(), warning = 0xffffae78.toInt(),
)

internal val UiThemeMode.palette: UiPalette get() = when (this) {
    UiThemeMode.LIGHT -> light
    UiThemeMode.DARK -> dark
}
