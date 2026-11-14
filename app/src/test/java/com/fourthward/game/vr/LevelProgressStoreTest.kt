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

    /** Interface-only fake: these JVM tests do not need an Android runtime or Mockito. */
    private class MemoryPreferences {
        val values = mutableMapOf<String, Any?>()
        val preferences = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getInt" -> values[args!![0] as String]?.let { it as Int } ?: args[1]
                "getStringSet" -> values[args!![0] as String]?.let { it as Set<*> } ?: args[1]
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
                    "putInt", "putStringSet" -> { pending[args!![0] as String] = args[1]; proxy }
                    "apply" -> { values.putAll(pending); null }
                    else -> error("Unexpected editor call: ${method.name}")
                }
            } as SharedPreferences.Editor
        }
    }
}
