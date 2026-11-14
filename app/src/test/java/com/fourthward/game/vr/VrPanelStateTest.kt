package com.fourthward.game.vr

import com.fourthward.game.core.PalmAction
import org.junit.Assert.*
import org.junit.Test

class VrPanelStateTest {
    @Test fun secondGroupRowsResolveToAbsoluteLevelsFourThroughSix() {
        val items = (3..5).map { VrLevelItem(it, "Level ${it + 1}", unlocked = it == 3, completed = false, current = false) }
        val panel = VrPanelState(levelItems = items, groupIndex = 1, groupCount = 2)
        assertEquals(3, panel.levelItem(PalmAction.LEVEL_1)?.index)
        assertEquals(4, panel.levelItem(PalmAction.LEVEL_2)?.index)
        assertEquals(5, panel.levelItem(PalmAction.LEVEL_3)?.index)
        assertTrue(panel.levelItem(PalmAction.LEVEL_1)!!.unlocked)
        assertFalse(panel.levelItem(PalmAction.LEVEL_2)!!.unlocked)
        assertNull(panel.levelItem(PalmAction.NEXT_GROUP))
        assertNull(panel.levelItem(PalmAction.BACK))
    }

    @Test fun absentRowDoesNotResolveToAnotherGroupsLevel() {
        val panel = VrPanelState(levelItems = listOf(VrLevelItem(3, "Fourth", true, false, true)))
        assertEquals(3, panel.levelItem(PalmAction.LEVEL_1)?.index)
        assertNull(panel.levelItem(PalmAction.LEVEL_2))
        assertNull(panel.levelItem(PalmAction.LEVEL_3))
    }

    @Test fun expeditionRowsRetainGlobalIndicesAndTheirDirectEntryType() {
        val items = (6..8).map {
            VrLevelItem(it, "Expedition ${it + 1}", unlocked = it == 6, completed = false,
                current = it == 6, hasIntroduction = false)
        }
        val panel = VrPanelState(levelItems = items, groupIndex = 2, groupCount = 3)
        listOf(PalmAction.LEVEL_1, PalmAction.LEVEL_2, PalmAction.LEVEL_3).forEachIndexed { row, action ->
            val item = requireNotNull(panel.levelItem(action))
            assertEquals(row + 6, item.index)
            assertFalse(item.hasIntroduction)
            assertEquals(row == 0, item.unlocked)
        }
    }
    @Test fun sixChapterSlotsKeepCodesAndTreeSlotsNeverResolveToLevels() {
        val indices = listOf(0, 1, 2, 6, 7, 8)
        val panel = VrPanelState(levelItems = indices.mapIndexed { row, index ->
            VrLevelItem(index, "Level", true, false, false, code = "1-${row + 1}")
        })
        assertEquals(8, panel.levelItem(PalmAction.LEVEL_6)?.index)
        assertEquals("1-6", panel.levelItem(PalmAction.LEVEL_6)?.code)
        val tree = VrPanelState(chapterItems = listOf(
            VrChapterItem(0, "移动", 3, 6, true, true),
            VrChapterItem(1, "辨认", 0, 3, false, false),
            VrChapterItem(2, "运动", 0, 6, false, false)))
        assertEquals("运动", tree.chapterItem(PalmAction.LEVEL_3)?.title)
        assertNull(tree.levelItem(PalmAction.LEVEL_1))
        assertNull(tree.chapterItem(PalmAction.LEVEL_4))
        assertNull(tree.chapterItem(PalmAction.BACK))
    }
}
