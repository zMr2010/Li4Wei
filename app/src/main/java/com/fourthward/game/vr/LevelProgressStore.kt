package com.fourthward.game.vr

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.fourthward.game.core.LevelCatalog
import com.fourthward.game.core.LevelProgress
import com.fourthward.game.core.TutorialLevel

/** Stores clears and the most recently entered level by stable ID. */
class LevelProgressStore internal constructor(private val preferences: SharedPreferences) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
    )

    internal data class ResumeLevel(val index: Int, val showLesson: Boolean = true, val returning: Boolean = true)

    private fun savedIds(): Set<String> = try {
        if (preferences.getInt(KEY_SCHEMA, 0) == SCHEMA_VERSION) {
            preferences.getStringSet(KEY_COMPLETED, emptySet())?.toSet().orEmpty()
        } else emptySet()
    } catch (_: ClassCastException) {
        emptySet()
    }

    fun load(levelIds: List<String>): LevelProgress = LevelProgress(levelIds, savedIds())
    fun loadLevels(levels: List<TutorialLevel>): LevelProgress = LevelProgress.forLevels(levels, savedIds())

    internal fun loadResume(levels: List<TutorialLevel>, progress: LevelProgress): ResumeLevel {
        val savedId = try {
            preferences.getString(KEY_LAST_LEVEL, null)
        } catch (_: ClassCastException) {
            null
        }
        val savedIndex = levels.indexOfFirst { it.id == savedId }
        if (progress.isUnlocked(savedIndex)) {
            val showLesson = try {
                preferences.getBoolean(KEY_LAST_LEVEL_LESSON, true)
            } catch (_: ClassCastException) {
                true
            }
            return ResumeLevel(savedIndex, showLesson)
        }
        val tutorials = LevelCatalog(levels).tutorialIndices
        val index = tutorials.firstOrNull { progress.isUnlocked(it) && !progress.isCompleted(it) }
            ?: tutorials.lastOrNull(progress::isUnlocked) ?: 0
        return ResumeLevel(index, returning = progress.completedCount > 0)
    }

    fun save(progress: LevelProgress) {
        val saved = preferences.edit()
            .putInt(KEY_SCHEMA, SCHEMA_VERSION)
            .putStringSet(KEY_COMPLETED, progress.completedIds)
            .commit()
        if (!saved) Log.e(TAG, "Could not save level clears")
    }

    fun saveResume(levelId: String, showLesson: Boolean) {
        // Write the level and its phase together before entering the new scene.
        val saved = preferences.edit()
            .putString(KEY_LAST_LEVEL, levelId)
            .putBoolean(KEY_LAST_LEVEL_LESSON, showLesson)
            .commit()
        if (!saved) Log.e(TAG, "Could not save current level: $levelId")
    }

    private companion object {
        const val TAG = "FourthwardXR/Progress"
        const val PREFERENCES_NAME = "level_progress"
        const val KEY_SCHEMA = "schema_version"
        const val KEY_COMPLETED = "completed_level_ids"
        const val KEY_LAST_LEVEL = "last_level_id"
        const val KEY_LAST_LEVEL_LESSON = "last_level_in_lesson"
        const val SCHEMA_VERSION = 1
    }
}
