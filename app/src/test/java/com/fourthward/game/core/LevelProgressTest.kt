package com.fourthward.game.core

import org.junit.Assert.*
import org.junit.Test

class LevelProgressTest {
    private val ids = listOf("walk", "turn", "around")

    @Test fun freshProgressOnlyUnlocksTheFirstLevel() {
        val progress = LevelProgress(ids)
        assertEquals(0, progress.completedCount)
        assertTrue(progress.completedIds.isEmpty())
        assertTrue(progress.isUnlocked(0))
        assertFalse(progress.isUnlocked(1))
        assertFalse(progress.isUnlocked(2))
        assertFalse(progress.isCompleted(0))
        for (invalid in listOf(-1, 3, Int.MAX_VALUE)) {
            assertFalse(progress.isUnlocked(invalid))
            assertFalse(progress.isCompleted(invalid))
            assertFalse(progress.recordCompletion(invalid))
        }
    }

    @Test fun eachClearImmediatelyUnlocksOnlyTheNextLevel() {
        val progress = LevelProgress(ids)
        assertFalse(progress.recordCompletion(1))
        assertFalse(progress.recordCompletion(2))
        assertTrue(progress.recordCompletion(0))
        assertEquals(1, progress.completedCount)
        assertTrue(progress.isCompleted(0))
        assertTrue(progress.isUnlocked(1))
        assertFalse(progress.isUnlocked(2))
        assertTrue(progress.recordCompletion(1))
        assertTrue(progress.isUnlocked(2))
        assertTrue(progress.recordCompletion(2))
        assertEquals(ids.toSet(), progress.completedIds)
        assertFalse(progress.isUnlocked(3))
    }

    @Test fun replayingEarlierLevelsNeverRevokesOrDuplicatesProgress() {
        val progress = LevelProgress(ids, ids.toSet())
        ids.indices.reversed().forEach { index ->
            assertTrue(progress.isUnlocked(index))
            assertTrue(progress.isCompleted(index))
            assertFalse(progress.recordCompletion(index))
        }
        assertEquals(3, progress.completedCount)
    }

    @Test fun loadingDiscardsUnknownIdsAndCompletionBeyondTheFirstGap() {
        val progress = LevelProgress(ids, setOf("walk", "around", "removed-level"))
        assertEquals(setOf("walk"), progress.completedIds)
        assertTrue(progress.isUnlocked(1))
        assertFalse(progress.isUnlocked(2))
        assertFalse(progress.isCompleted(2))
        assertTrue(progress.recordCompletion(1))
        assertFalse("Discarded suffix must not reappear after filling the gap", progress.isCompleted(2))
        assertTrue(progress.recordCompletion(2))
        assertEquals(3, progress.completedCount)

        val missingFirst = LevelProgress(ids, setOf("turn", "around"))
        assertEquals(0, missingFirst.completedCount)
        assertFalse(missingFirst.isUnlocked(1))
    }

    @Test fun completionSnapshotsRoundTripWithoutSharingMutableState() {
        val suppliedIds = ids.toMutableList()
        val suppliedClears = mutableSetOf("walk")
        val progress = LevelProgress(suppliedIds, suppliedClears)
        suppliedIds.clear()
        suppliedClears.add("turn")
        assertEquals(setOf("walk"), progress.completedIds)
        val snapshot = progress.completedIds
        assertTrue(progress.recordCompletion(1))
        assertEquals(setOf("walk"), snapshot)
        val reloaded = LevelProgress(ids, progress.completedIds)
        assertEquals(2, reloaded.completedCount)
        assertTrue(reloaded.isUnlocked(2))
        assertTrue(progress.recordCompletion(2))
        assertFalse(reloaded.isCompleted(2))
    }

    @Test fun emptyCatalogHasNoUnlockedOrCompletableLevels() {
        val progress = LevelProgress(emptyList(), setOf("walk"))
        assertEquals(0, progress.completedCount)
        assertFalse(progress.isUnlocked(0))
        assertFalse(progress.recordCompletion(0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun duplicateIdsCannotShareACompletion() {
        LevelProgress(listOf("walk", "walk"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun blankIdsAreRejected() {
        LevelProgress(listOf("walk", " "))
    }
}
