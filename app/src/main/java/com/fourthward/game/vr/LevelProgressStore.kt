package com.fourthward.game.vr

import android.content.Context
import android.content.SharedPreferences
import com.fourthward.game.core.LevelProgress
import com.fourthward.game.core.TutorialLevel

/** Stores stable level IDs, independently of the current run or selected level. */
class LevelProgressStore internal constructor(private val preferences: SharedPreferences) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
    )

    private fun savedIds(): Set<String> = try {
            if (preferences.getInt(KEY_SCHEMA, 0) == SCHEMA_VERSION) {
                // SharedPreferences owns its returned set; never retain or modify it.
                preferences.getStringSet(KEY_COMPLETED, emptySet())?.toSet().orEmpty()
            } else emptySet()
        } catch (_: ClassCastException) {
            emptySet()
        }

    fun load(levelIds: List<String>): LevelProgress = LevelProgress(levelIds, savedIds())
    fun loadLevels(levels: List<TutorialLevel>): LevelProgress = LevelProgress.forLevels(levels, savedIds())

    fun save(progress: LevelProgress) {
        preferences.edit()
            .putInt(KEY_SCHEMA, SCHEMA_VERSION)
            .putStringSet(KEY_COMPLETED, progress.completedIds)
            .apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "level_progress"
        const val KEY_SCHEMA = "schema_version"
        const val KEY_COMPLETED = "completed_level_ids"
        const val SCHEMA_VERSION = 1
    }
}
