package com.fourthward.game.core

import org.junit.Assert.*
import org.junit.Test

class ChapterProgressTest {
    private val levels = TutorialLevels.all()
    private val tutorials = listOf(0, 1, 2, 3, 4, 5, 9, 10, 11)
    private val movementChallenges = listOf(6, 7, 8)
    private val motionChallenges = listOf(12, 13, 14)
    private fun ids(indices: List<Int>) = indices.map { levels[it].id }.toSet()

    @Test fun allNineTutorialsCanBeCompletedInOrderWithoutAnyChallenge() {
        val progress = LevelProgress.forLevels(levels)
        tutorials.forEachIndexed { order, index ->
            assertTrue(progress.isUnlocked(index))
            tutorials.drop(order + 1).forEach { assertFalse(progress.isUnlocked(it)) }
            assertTrue(progress.recordCompletion(index))
            assertTrue(movementChallenges.all { progress.isUnlocked(it) == (order >= 2) })
            assertTrue(motionChallenges.all { progress.isUnlocked(it) == (order == tutorials.lastIndex) })
        }
        assertEquals(ids(tutorials), progress.completedIds)
        assertEquals(9, progress.completedCount)
    }

    @Test fun optionalMovementChallengesUnlockTogetherAndCanBePlayedInAnyOrder() {
        val progress = LevelProgress.forLevels(levels, ids(tutorials.take(3)))
        assertTrue(progress.isUnlocked(3))
        assertTrue(movementChallenges.all(progress::isUnlocked))
        for (index in listOf(8, 6, 7)) {
            assertTrue(progress.recordCompletion(index))
            assertFalse(progress.isUnlocked(4))
            assertFalse(progress.isUnlocked(9))
            assertTrue(progress.isUnlocked(3))
        }
        assertTrue(progress.recordCompletion(3))
        assertTrue(progress.isUnlocked(4))
    }

    @Test fun optionalMotionChallengesAreIndependentAfterTheLastTutorial() {
        val progress = LevelProgress.forLevels(levels, ids(tutorials))
        assertTrue(motionChallenges.all(progress::isUnlocked))
        for (index in listOf(14, 12, 13)) assertTrue(progress.recordCompletion(index))
        assertTrue(movementChallenges.none(progress::isCompleted))
    }

    @Test fun oldClearsRemainReplayableWithoutAllowingMissingTutorialsToBeSkipped() {
        val old = ids(listOf(0, 2, 5, 8, 11, 14))
        val progress = LevelProgress.forLevels(levels, old + "removed")
        assertEquals(old, progress.completedIds)
        listOf(0, 2, 5, 8, 11, 14).forEach { assertTrue(progress.isUnlocked(it)); assertTrue(progress.isCompleted(it)) }
        assertTrue(progress.isUnlocked(1))
        listOf(3, 4, 6, 7, 9, 10, 12, 13).forEach { assertFalse(progress.isUnlocked(it)) }
        assertFalse(progress.recordCompletion(9))
        val reloaded = LevelProgress.forLevels(levels, progress.completedIds)
        assertEquals(progress.completedIds, reloaded.completedIds)
        assertFalse(reloaded.isUnlocked(12))
    }

    @Test fun oldRecognitionCompletionContinuesDirectlyToMotionWithoutMovementChallenges() {
        val old = ids(tutorials.take(6))
        val progress = LevelProgress.forLevels(levels, old)
        assertTrue(progress.isUnlocked(9))
        assertFalse(progress.isUnlocked(10))
        assertTrue(movementChallenges.all(progress::isUnlocked))
        assertEquals(old, progress.completedIds)
    }

    @Test fun nextFollowsTutorialsAcrossChaptersAndBranchesReturnToTheMap() {
        val game = FourthwardGame(levels, LevelProgress.forLevels(levels, levels.map { it.id }.toSet()))
        for ((index, next) in tutorials.zipWithNext()) {
            assertTrue(game.selectLevel(index))
            assertEquals(next, game.nextLevelIndex)
        }
        for (index in movementChallenges + motionChallenges + tutorials.last()) {
            assertTrue(game.selectLevel(index))
            assertNull(game.nextLevelIndex)
            assertFalse(game.nextLevel())
        }
    }

    @Test fun syntheticStandaloneChallengesAndEmptyCatalogsStillWork() {
        val challenge = levels[6]
        val progress = LevelProgress.forLevels(listOf(challenge))
        assertTrue(progress.isUnlocked(0))
        assertTrue(progress.recordCompletion(0))
        assertTrue(LevelProgress.requirementsForLevels(emptyList()).isEmpty())
        assertFalse(LevelProgress.forLevels(emptyList()).isUnlocked(0))
    }

    @Test fun oldSaveHolesDoNotAdvertiseAnUnavailableNextLessonOrFinishedMainline() {
        val progress = LevelProgress.forLevels(levels, ids(listOf(0, 2, 11)))
        val game = FourthwardGame(levels, progress)
        assertTrue(game.selectLevel(2))
        assertEquals(3, game.nextLevelIndex)
        assertFalse(game.nextLevelUnlocked)
        assertFalse(game.tutorialRouteComplete)
        assertTrue(game.selectLevel(11))
        assertFalse(game.tutorialRouteComplete)
        tutorials.forEach { assertTrue(progress.recordCompletion(it) || progress.isCompleted(it)) }
        assertTrue(game.tutorialRouteComplete)
        assertTrue(game.selectLevel(2))
        assertTrue(game.nextLevelUnlocked)
    }
}
