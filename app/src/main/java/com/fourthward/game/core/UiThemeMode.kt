package com.fourthward.game.core

enum class UiThemeMode(val switchLabel: String) {
    LIGHT("切换深色"),
    DARK("切换浅色");

    fun toggled() = if (this == LIGHT) DARK else LIGHT
}
