package com.fourthward.game.core

import org.junit.Assert.*
import org.junit.Test

class LevelCatalogTest {
    private val levels = TutorialLevels.all()
    private val catalog = LevelCatalog(levels)

    @Test fun chaptersUseLocalNumbersWithoutReorderingSavedLevelIds() {
        assertEquals(listOf("移动", "辨认", "运动"), catalog.groups.map { it.title })
        assertEquals(listOf(0, 1, 2, 6, 7, 8), catalog.groups[0].levelIndices)
        assertEquals(listOf(3, 4, 5), catalog.groups[1].levelIndices)
        assertEquals((9..14).toList(), catalog.groups[2].levelIndices)
        assertEquals("1-4", catalog.levelCode(6))
        assertEquals("2-1", catalog.levelCode(3))
        assertEquals("3-6", catalog.levelCode(14))
        assertEquals(6, catalog.groups[0].levelAtRow(3))
        assertEquals(8, catalog.groups[0].levelAtRow(5))
        assertNull(catalog.groups[0].levelAtRow(6))
        assertNull(catalog.groups[1].levelAtRow(3))
        assertNull(catalog.groups[0].levelAtRow(-1))
    }

    @Test fun nextLevelStaysWithinItsChapterAndStopsAtBothBranches() {
        assertEquals(6, catalog.nextWithinChapter(2))
        assertEquals(4, catalog.nextWithinChapter(3))
        assertEquals(12, catalog.nextWithinChapter(11))
        assertNull(catalog.nextWithinChapter(8))
        assertNull(catalog.nextWithinChapter(5))
        assertNull(catalog.nextWithinChapter(14))
    }

    @Test fun movementCompletionOpensBothBranchesIndependently() {
        val progress = LevelProgress.forLevels(levels, catalog.groups[0].levelIndices.map { levels[it].id }.toSet())
        assertTrue(catalog.chapterComplete(0, progress))
        assertTrue(progress.isUnlocked(3))
        assertTrue(progress.isUnlocked(9))
        assertFalse(progress.isUnlocked(4))
        assertFalse(progress.isUnlocked(10))
        assertTrue(progress.recordCompletion(9))
        assertTrue(progress.isUnlocked(10))
        assertFalse(progress.isUnlocked(4))
    }

    @Test fun oldClearsKeepTheirIdentityAndChapterLocalSuccessorsStayLocked() {
        val oldIds = listOf("walk", "turn", "around", "hypersphere", "tesseract", "ambiguity")
        assertEquals(oldIds, levels.take(6).map { it.id })
        val progress = LevelProgress.forLevels(levels, oldIds.toSet())
        assertTrue(progress.isUnlocked(6))
        assertTrue(catalog.chapterComplete(1, progress))
        assertFalse(progress.isUnlocked(7))
        assertFalse(progress.isUnlocked(9))
        assertTrue(progress.recordCompletion(6))
        assertTrue(progress.recordCompletion(7))
        assertTrue(progress.recordCompletion(8))
        assertTrue(progress.isUnlocked(9))
    }
}
