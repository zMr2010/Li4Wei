package com.fourthward.game.vr

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import com.fourthward.game.core.LevelMapWindow
import com.fourthward.game.core.PalmAction
import com.fourthward.game.core.UiThemeMode
import java.util.Locale

/** Printed faces for the welcome console. Card positions and depth come from the interactive VR layout. */
internal object WelcomeRouteArt {
    private fun paint(tint: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tint }

    fun title(c: Canvas, theme: UiThemeMode) {
        val colors = theme.palette
        val name = Ink.paint(103f, colors.ink, bold = true)
        name.typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
        Ink.text(c, "理四维", 0f, 105f, name)
        val latin = Ink.paint(39f, colors.ink, mono = true, bold = true)
        Ink.text(c, "Li4Wei", 4f, 159f, latin)
        val x = 4f + latin.measureText("Li4Wei") + 17f
        c.drawRect(x, 145f, x + 39f, 155f, paint(colors.accent))
    }

    fun board(c: Canvas, state: VrPanelState, map: VrLevelMapState) {
        val colors = state.theme.palette
        val darkMode = state.theme == UiThemeMode.DARK
        val header = if (darkMode) colors.inset else colors.selected
        val onHeader = if (darkMode) colors.ink else colors.onSelected
        val width = state.layout.widthDp.toFloat()
        val height = state.layout.heightDp.toFloat()
        val contentTop = (LevelMapWindow.CONTENT_TOP * height).toFloat()
        val contentBottom = (LevelMapWindow.CONTENT_BOTTOM * height).toFloat()
        c.drawColor(colors.surface)
        c.drawRect(0f, contentTop, width * .262f, contentBottom, paint(colors.inset))
        c.drawLine(width * .262f, contentTop, width * .262f, contentBottom, paint(colors.line))
        c.drawRect(0f, 0f, width, contentTop, paint(header))
        c.drawRect(0f, 0f, 5f, contentTop, paint(colors.accent))
        Ink.text(c, "关卡路线", 19f, 37f, Ink.paint(32f, onHeader, true))
        for ((x, label) in listOf(20f to "主线教程", width * .277f to "可选挑战")) {
            Ink.text(c, label, x, 61f, Ink.paint(15f, onHeader))
        }
        val count = String.format(Locale.ROOT, "%02d / %02d", map.totalCompleted, map.totalLevels)
        val countPaint = Ink.paint(26f, onHeader, bold = true, mono = true)
        Ink.text(c, count, width - countPaint.measureText(count) - 19f, 38f, countPaint)
        val caption = Ink.paint(14f, onHeader)
        Ink.text(c, "已通过", width - caption.measureText("已通过") - 20f, 60f, caption)

        val railBottom = contentBottom - 4f
        val railTop = contentTop + 6f
        val railX = width - 10f
        val pitch = (railBottom - railTop) / map.totalRows.coerceAtLeast(map.visibleRows)
        c.drawRect(railX, railTop, railX + 2f, railBottom, paint(colors.line))
        val end = railBottom - map.firstRow * pitch
        c.drawRect(railX - 1f, end - map.visibleRows * pitch, railX + 3f, end, paint(colors.muted))
        val selected = state.hoveredAction?.let(state::levelItem)
        val hint = when {
            selected != null && !selected.unlocked -> "${selected.code}  ${selected.lockedReason}"
            selected != null -> "${selected.code}  ${selected.title} · ${if (selected.completed) "可重玩" else "可进入"}"
            state.debugMode -> "↑ ↓ 浏览路线"
            else -> "捏住路线，上下拖动"
        }
        val footer = if (state.debugMode) "数字键选关   ·   Tab 切换   ·   Enter 确认"
            else "指向停留 2 秒、捏合或戳按选关"
        Ink.text(c, hint, 18f, height - 74f, Ink.paint(15f, colors.muted), width - 36f)
        Ink.text(c, footer, 18f, height - 4f, Ink.paint(14f, colors.muted), width - 36f)
    }

    fun button(c: Canvas, action: PalmAction, state: VrPanelState, width: Float, height: Float) {
        val colors = state.theme.palette
        val item = state.levelItem(action)
        val disabled = action in state.disabled || item?.unlocked == false
        val focused = !disabled && state.hoveredAction == action
        val pressed = !disabled && state.pressedAction == action
        val primary = action == PalmAction.CONTEXT
        val selected = focused || item?.current == true && !disabled
        val fill = when {
            primary || pressed -> colors.accent
            selected -> colors.selected
            disabled -> colors.inset
            else -> colors.raised
        }
        val fg = when {
            primary || pressed -> colors.onAccent
            selected -> colors.onSelected
            disabled -> colors.muted
            else -> colors.ink
        }
        val outline = when {
            focused || pressed -> colors.ink
            disabled -> colors.line
            else -> colors.muted
        }
        val shape = Ink.cutShape(width, height, if (item != null) 6f else 3f)
        c.drawPath(shape, paint(fill))
        c.drawPath(shape, paint(outline).apply { style = Paint.Style.STROKE; strokeWidth = if (focused) 3f else 1.5f })
        if (item != null) {
            val codeInk = if (selected && !pressed && state.theme == UiThemeMode.LIGHT) colors.accent else fg
            Ink.text(c, item.code, 9f, 19f, Ink.paint(18f, codeInk, true, true))
            Ink.status(c, width - 14f, 13f, 12f, if (item.completed) 2 else if (item.unlocked) 1 else 0, fg)
            val title = item.title.substringAfter(" · ", item.title)
            val type = Ink.paint(18f, fg, bold = !disabled)
            val maxWidth = width - 18f
            if (type.measureText(title) <= maxWidth) {
                Ink.text(c, title, 9f, 49f, type, maxWidth)
            } else {
                val split = type.breakText(title, true, maxWidth, null).coerceAtLeast(1)
                Ink.text(c, title.take(split), 9f, 40f, type, maxWidth)
                Ink.text(c, title.drop(split), 9f, 61f, type, maxWidth)
            }
            if (item.current && !disabled) c.drawRect(8f, height - 4f, width * .35f, height - 1f, paint(colors.accent))
            return
        }
        val label = when (action) {
            PalmAction.CONTEXT -> state.startLabel.orEmpty()
            PalmAction.BACK -> "返回"
            PalmAction.TOGGLE_THEME -> return Ink.themeSwitch(c, state.theme, fg, width, height)
            PalmAction.PREVIOUS_GROUP -> "前序"
            PalmAction.NEXT_GROUP -> "后续"
            else -> return
        }
        val type = Ink.paint(if (primary) 21f else 18f, fg, true)
        val textWidth = type.measureText(label)
        val y = height / 2f
        val left = ((width - textWidth - 19f) / 2).coerceAtLeast(5f)
        Ink.text(c, label, left, y - (type.ascent() + type.descent()) / 2, type, width - 27f)
        val arrowX = left + textWidth + 13f
        if (primary || action == PalmAction.BACK) {
            val direction = if (primary) 1f else -1f
            val arrow = Path().apply {
                moveTo(arrowX - 3f * direction, y - 5f)
                lineTo(arrowX + 3f * direction, y)
                lineTo(arrowX - 3f * direction, y + 5f)
            }
            c.drawPath(arrow, paint(fg).apply { style = Paint.Style.STROKE; strokeWidth = 2f })
        } else Ink.chevron(c, arrowX, y, 4.5f, if (action == PalmAction.NEXT_GROUP) -1f else 1f, fg)
    }
}
