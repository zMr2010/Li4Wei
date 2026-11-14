package com.fourthward.game.core

data class LevelGroup(val id: String, val title: String, val levelIndices: List<Int>) {
    fun levelAtRow(row: Int): Int? = levelIndices.getOrNull(row)
}

/** Display groups never change the absolute order used by saved progression. */
class LevelCatalog(private val levels: List<TutorialLevel>) {
    /** Tutorials form one route across chapters; missions are optional branches. */
    val tutorialIndices: List<Int> = levels.indices.filter { levels[it].expedition == null }
    val groups: List<LevelGroup> = levels.indices.groupBy { levels[it].groupId }.map { (id, indices) ->
        require(indices.size in 1..6) { "Each chapter has up to six levels" }
        require(indices.all { levels[it].groupTitle == levels[indices.first()].groupTitle })
        LevelGroup(id, levels[indices.first()].groupTitle, indices.toList())
    }
    init { require(groups.isNotEmpty()) }
    fun groupForLevel(index: Int): Int = groups.indexOfFirst { index in it.levelIndices }.also { require(it >= 0) }
    fun localNumber(index: Int): Int = groups[groupForLevel(index)].levelIndices.indexOf(index) + 1
    fun levelCode(index: Int): String = "${groupForLevel(index) + 1}-${localNumber(index)}"
    fun nextWithinChapter(index: Int): Int? = groups[groupForLevel(index)].levelIndices.getOrNull(localNumber(index))
    fun isTutorial(index: Int): Boolean = index in tutorialIndices
    fun nextInRoute(index: Int): Int? = tutorialIndices.indexOf(index).takeIf { it >= 0 }
        ?.let { tutorialIndices.getOrNull(it + 1) }

    /** Full prefixes also protect later unlocks when an older save contains missing clears. */
    fun requiredTutorialIndices(index: Int): List<Int> {
        require(index in levels.indices)
        val tutorialPosition = tutorialIndices.indexOf(index)
        if (tutorialPosition >= 0) return tutorialIndices.take(tutorialPosition)
        val anchor = tutorialIndices.lastOrNull { levels[it].groupId == levels[index].groupId }
            ?: return emptyList()
        return tutorialIndices.take(tutorialIndices.indexOf(anchor) + 1)
    }
    fun chapterComplete(groupIndex: Int, progress: LevelProgress): Boolean =
        groups[groupIndex].levelIndices.all(progress::isCompleted)
}
