package com.fourthward.game.core

data class LevelGroup(val id: String, val title: String, val levelIndices: List<Int>) {
    fun levelAtRow(row: Int): Int? = levelIndices.getOrNull(row)
}

/** Display groups never change the absolute order used by saved progression. */
class LevelCatalog(levels: List<TutorialLevel>) {
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
    fun chapterComplete(groupIndex: Int, progress: LevelProgress): Boolean =
        groups[groupIndex].levelIndices.all(progress::isCompleted)
}
