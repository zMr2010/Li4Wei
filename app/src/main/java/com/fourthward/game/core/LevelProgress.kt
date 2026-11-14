package com.fourthward.game.core

/** Persistent clears are separate from the current attempt's completion state. */
class LevelProgress(
    levelIds: List<String>, completedIds: Set<String> = emptySet(),
    prerequisites: Map<String, Set<String>>? = null,
) {
    private val orderedIds = levelIds.toList()
    private val requirements = prerequisites?.mapValues { it.value.toSet() }
    private val cleared = linkedSetOf<String>()

    init {
        require(orderedIds.all { it.isNotBlank() }) { "Level IDs must not be blank" }
        require(orderedIds.distinct().size == orderedIds.size) { "Level IDs must be unique" }
        // A corrupt or stale save must not unlock a level through a missing prerequisite.
        if (requirements == null) cleared.addAll(orderedIds.takeWhile { it in completedIds })
        else {
            require(requirements.keys == orderedIds.toSet())
            require(requirements.all { (id, required) -> id !in required && required.all { it in orderedIds } })
            // Old saves used a different presentation order. Keep their known clears,
            // including previously completed recognition lessons, by stable ID.
            cleared.addAll(orderedIds.filter { it in completedIds })
        }
    }

    val completedCount: Int get() = cleared.size

    /** Copy both on input and output; callers cannot mutate progression behind its guards. */
    val completedIds: Set<String> get() = cleared.toSet()

    fun isUnlocked(index: Int): Boolean = index in orderedIds.indices &&
        (if (requirements == null) index <= cleared.size else
            orderedIds[index] in cleared || requirements.getValue(orderedIds[index]).all { it in cleared })

    fun isCompleted(index: Int): Boolean = index in orderedIds.indices && orderedIds[index] in cleared

    /** Returns true exactly once for each newly cleared, unlocked level. */
    fun recordCompletion(index: Int): Boolean = isUnlocked(index) && cleared.add(orderedIds[index])

    companion object {
        /** Shared by progression and its map. Display edges may omit transitive requirements. */
        fun requirementsForLevels(levels: List<TutorialLevel>): Map<String, Set<String>> {
            val groups = levels.groupBy { it.groupId }
            val movement = groups["movement"].orEmpty().map { it.id }.toSet()
            return groups.flatMap { (group, entries) ->
                entries.mapIndexed { index, level ->
                    val chapterGate = if (group in setOf("recognition", "motion")) movement else emptySet()
                    level.id to (chapterGate + if (index > 0) setOf(entries[index - 1].id) else emptySet())
                }
            }.toMap()
        }

        fun forLevels(levels: List<TutorialLevel>, completedIds: Set<String> = emptySet()): LevelProgress =
            LevelProgress(levels.map { it.id }, completedIds, requirementsForLevels(levels))
    }
}
