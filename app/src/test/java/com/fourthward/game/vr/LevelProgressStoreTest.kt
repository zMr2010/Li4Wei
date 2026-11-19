package com.fourthward.game.vr

import android.content.SharedPreferences
import com.fourthward.game.core.LevelProgress
import com.fourthward.game.core.TutorialLevels
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class LevelProgressStoreTest {
    private val ids = listOf("walk", "turn", "around")

    @Test fun saveAndReloadPreserveCompletedIdsWithoutRetainingLiveProgress() {
        val memory = MemoryPreferences()
        val store = LevelProgressStore(memory.preferences)
        assertEquals(0, store.load(ids).completedCount)
        val progress = LevelProgress(ids)
        progress.recordCompletion(0)
        store.save(progress)
        progress.recordCompletion(1)
        assertEquals(setOf("walk"), LevelProgressStore(memory.preferences).load(ids).completedIds)
        store.save(progress)
        assertEquals(setOf("walk", "turn"), store.load(ids).completedIds)
        assertTrue(store.load(ids).isUnlocked(2))
    }

    @Test fun missingUnknownAndMalformedSchemasLoadFreshProgress() {
        val memory = MemoryPreferences()
        val store = LevelProgressStore(memory.preferences)
        memory.values["completed_level_ids"] = ids.toSet()
        for (schema in listOf(null, 0, 2, "invalid")) {
            memory.values["schema_version"] = schema
            assertEquals(0, store.load(ids).completedCount)
        }
        memory.values["schema_version"] = 1
        memory.values["completed_level_ids"] = "invalid"
        assertEquals(0, store.load(ids).completedCount)
    }

    @Test fun storedUnknownIdsAndGapsAreSanitizedWithoutModifyingPreferences() {
        val memory = MemoryPreferences()
        val savedIds = mutableSetOf("walk", "around", "removed-level")
        memory.values["schema_version"] = 1
        memory.values["completed_level_ids"] = savedIds
        val progress = LevelProgressStore(memory.preferences).load(ids)
        assertEquals(setOf("walk"), progress.completedIds)
        assertEquals(setOf("walk", "around", "removed-level"), savedIds)
        savedIds.clear()
        assertEquals(setOf("walk"), progress.completedIds)
    }

    @Test fun oldNineLevelSaveMigratesByIdAndUnlocksMotionTutorialsWithoutLosingClears() {
        val memory = MemoryPreferences()
        val levels = TutorialLevels.all()
        val oldIds = levels.take(9).map { it.id }.toSet()
        memory.values["schema_version"] = 1
        memory.values["completed_level_ids"] = oldIds
        val store = LevelProgressStore(memory.preferences)
        val progress = store.loadLevels(levels)
        assertEquals(oldIds, progress.completedIds)
        assertTrue(progress.isUnlocked(9))
        assertFalse(progress.isUnlocked(10))
        assertTrue(progress.recordCompletion(9))
        store.save(progress)
        val loaded = store.loadLevels(levels)
        assertEquals(oldIds + "motion-normal", loaded.completedIds)
        assertTrue(loaded.isUnlocked(10))
        assertFalse(loaded.isUnlocked(11))
    }

    @Test fun lastEnteredLevelAndLessonPhaseAreCommittedAndSurviveRecreatingTheStore() {
        val memory = MemoryPreferences()
        val levels = TutorialLevels.all()
        val store = LevelProgressStore(memory.preferences)
        val cleared = setOf("walk", "turn", "around", "hypersphere", "tesseract", "ambiguity")
        store.save(LevelProgress.forLevels(levels, cleared))
        store.saveResume("motion-normal", showLesson = true)
        val reopened = LevelProgressStore(memory.preferences)
        val progress = reopened.loadLevels(levels)
        val index = levels.indexOfFirst { it.id == "motion-normal" }
        assertEquals(LevelProgressStore.ResumeLevel(index, true), reopened.loadResume(levels, progress))
        store.saveResume("motion-normal", showLesson = false)
        assertEquals(LevelProgressStore.ResumeLevel(index, false), reopened.loadResume(levels, progress))
        assertEquals(cleared, reopened.loadLevels(levels).completedIds)
        assertEquals(3, memory.commits)
    }

    @Test fun firstLaunchShowsStartAndEnteringTheFirstLevelCreatesAContinueTargetBeforeAnyClear() {
        val memory = MemoryPreferences()
        val levels = TutorialLevels.all()
        val store = LevelProgressStore(memory.preferences)
        val progress = store.loadLevels(levels)
        val fresh = store.loadResume(levels, progress)
        assertEquals(LevelProgressStore.ResumeLevel(0, showLesson = true, returning = false), fresh)
        assertEquals(0, memory.commits)

        store.saveResume(levels.first().id, showLesson = false)
        assertEquals(LevelProgressStore.ResumeLevel(0, showLesson = false, returning = true),
            LevelProgressStore(memory.preferences).loadResume(levels, progress))
        assertEquals(1, memory.commits)
    }

    @Test fun aLegacyOrInvalidResumeFallsBackToTheFirstIncompleteTutorial() {
        val memory = MemoryPreferences()
        val levels = TutorialLevels.all()
        val store = LevelProgressStore(memory.preferences)
        store.save(LevelProgress.forLevels(levels, setOf("walk", "turn", "around")))
        val progress = store.loadLevels(levels)
        val fallback = LevelProgressStore.ResumeLevel(levels.indexOfFirst { it.id == "hypersphere" })
        assertEquals(fallback, store.loadResume(levels, progress))
        store.saveResume("motion-gates", showLesson = false)
        assertEquals(fallback, store.loadResume(levels, progress))
        store.saveResume("removed", showLesson = false)
        assertEquals(fallback, store.loadResume(levels, progress))
    }

    /** Interface-only fake: these JVM tests do not need an Android runtime or Mockito. */
    private class MemoryPreferences {
        val values = mutableMapOf<String, Any?>()
        var commits = 0
            private set
        val preferences = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getInt" -> values[args!![0] as String]?.let { it as Int } ?: args[1]
                "getStringSet" -> values[args!![0] as String]?.let { it as Set<*> } ?: args[1]
                "getString" -> values[args!![0] as String]?.let { it as String } ?: args[1]
                "getBoolean" -> values[args!![0] as String]?.let { it as Boolean } ?: args[1]
                "edit" -> editor()
                else -> error("Unexpected preference call: ${method.name}")
            }
        } as SharedPreferences

        private fun editor(): SharedPreferences.Editor {
            val pending = mutableMapOf<String, Any?>()
            return Proxy.newProxyInstance(
                SharedPreferences.Editor::class.java.classLoader, arrayOf(SharedPreferences.Editor::class.java),
            ) { proxy, method, args ->
                when (method.name) {
                    "putInt", "putStringSet", "putString", "putBoolean" -> {
                        pending[args!![0] as String] = args[1]
                        proxy
                    }
                    "commit" -> {
                        values.putAll(pending)
                        commits++
                        true
                    }
                    else -> error("Unexpected editor call: ${method.name}")
                }
            } as SharedPreferences.Editor
        }
    }
}
