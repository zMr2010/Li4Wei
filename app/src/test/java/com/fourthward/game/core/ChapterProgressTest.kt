package com.fourthward.game.core

import org.junit.Assert.*
import org.junit.Test

class ChapterProgressTest {
    private val levels = TutorialLevels.all()
    private val movement = listOf(0, 1, 2, 6, 7, 8)

    @Test fun movementUnlocksTwoIndependentBranchesOnlyAfterItsSixthLevel() {
        val progress = LevelProgress.forLevels(levels)
        movement.forEachIndexed { local, index ->
            assertTrue(progress.isUnlocked(index))
            assertFalse(progress.isUnlocked(3))
            assertFalse(progress.isUnlocked(9))
            if (local < movement.lastIndex) assertFalse(progress.isUnlocked(movement[local + 1]))
            assertTrue(progress.recordCompletion(index))
        }
        assertTrue(progress.isUnlocked(3))
        assertTrue(progress.isUnlocked(9))
        assertFalse(progress.isUnlocked(4))
        assertFalse(progress.isUnlocked(10))
        assertTrue(progress.recordCompletion(9))
        assertTrue(progress.isUnlocked(10))
        assertFalse(progress.isUnlocked(4))
        assertTrue(progress.recordCompletion(3))
        assertTrue(progress.isUnlocked(4))
        assertFalse(progress.isUnlocked(11))
    }

    @Test fun legacyRecognitionClearsRemainReplayableButDoNotUnlockTheMotionBranch() {
        val old = levels.take(6).map { it.id }.toSet()
        val progress = LevelProgress.forLevels(levels, old + "removed")
        assertEquals(old, progress.completedIds)
        (0..5).forEach { assertTrue(progress.isUnlocked(it)); assertTrue(progress.isCompleted(it)) }
        assertTrue(progress.isUnlocked(6))
        assertFalse(progress.isUnlocked(7))
        assertFalse(progress.isUnlocked(9))
        val reloaded = LevelProgress.forLevels(levels, progress.completedIds)
        assertEquals(progress.completedIds, reloaded.completedIds)
    }

    @Test fun chapterEndDoesNotAutomaticallyEnterEitherBranch() {
        val game = FourthwardGame(levels, LevelProgress.forLevels(levels, levels.take(9).map { it.id }.toSet()))
        assertTrue(game.selectLevel(8))
        assertNull(game.nextLevelIndex)
        assertFalse(game.nextLevel())
        assertTrue(game.selectLevel(3))
        assertEquals(4, game.nextLevelIndex)
        assertTrue(game.selectLevel(5))
        assertNull(game.nextLevelIndex)
        assertTrue(game.selectLevel(9))
        assertEquals(10, game.nextLevelIndex)
    }
}
