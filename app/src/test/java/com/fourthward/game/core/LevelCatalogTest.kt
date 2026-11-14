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

    @Test fun chapterLookupRemainsPresentationOrderWithoutControllingTheMainline() {
        assertEquals(6, catalog.nextWithinChapter(2))
        assertEquals(4, catalog.nextWithinChapter(3))
        assertEquals(12, catalog.nextWithinChapter(11))
        assertNull(catalog.nextWithinChapter(8))
        assertNull(catalog.nextWithinChapter(5))
        assertNull(catalog.nextWithinChapter(14))
    }

    @Test fun tutorialRouteSkipsOptionalMissionsWithoutChangingChapterNumbers() {
        val tutorials = listOf(0, 1, 2, 3, 4, 5, 9, 10, 11)
        assertEquals(tutorials, catalog.tutorialIndices)
        tutorials.zipWithNext().forEach { (from, to) -> assertEquals(to, catalog.nextInRoute(from)) }
        listOf(6, 7, 8, 12, 13, 14).forEach {
            assertFalse(catalog.isTutorial(it))
            assertNull(catalog.nextInRoute(it))
        }
        assertNull(catalog.nextInRoute(11))
        assertEquals(listOf(0, 1, 2), catalog.requiredTutorialIndices(6))
        assertEquals(listOf(0, 1, 2), catalog.requiredTutorialIndices(3))
        assertEquals(tutorials, catalog.requiredTutorialIndices(14))
    }

    @Test fun oldClearsKeepTheirIdentityAndOptionalChallengesNeverGateTheMainline() {
        val oldIds = listOf("walk", "turn", "around", "hypersphere", "tesseract", "ambiguity")
        assertEquals(oldIds, levels.take(6).map { it.id })
        val progress = LevelProgress.forLevels(levels, oldIds.toSet())
        assertTrue(catalog.chapterComplete(1, progress))
        assertFalse(catalog.chapterComplete(0, progress))
        listOf(6, 7, 8, 9).forEach { assertTrue(progress.isUnlocked(it)) }
        assertFalse(progress.isUnlocked(10))
        assertEquals(oldIds.toSet(), progress.completedIds)
    }
}
