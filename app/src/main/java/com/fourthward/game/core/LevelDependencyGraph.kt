package com.fourthward.game.core

/** Absolute level identity is independent of its position in the route map. */
data class LevelMapNode(val index: Int, val row: Int, val column: Double, val width: Double = .27)
data class LevelMapEdge(val fromIndex: Int, val toIndex: Int)

data class LevelMapWindow(
    val firstRow: Int,
    val nodes: List<LevelMapNode>,
    /** Includes crossing edges whose other end is just outside the viewport. */
    val edges: List<LevelMapEdge>,
    val layout: PalmPanelSpec,
    val visibleRows: Int = VISIBLE_ROWS,
) {
    fun levelAtSlot(slot: Int): Int? = nodes.getOrNull(slot)?.index
    fun levelForAction(action: PalmAction): Int? = levelAtSlot(PalmPanelLayout.levelActions.indexOf(action))

    companion object {
        const val VISIBLE_ROWS = 6
        const val WIDTH_DP = 600
        const val HEIGHT_DP = 680
        const val WIDTH_METERS = .44
        const val NODE_HEIGHT = 68.0 / HEIGHT_DP
        const val BOTTOM_CENTER_Y = 552.0 / HEIGHT_DP
        const val ROW_SPACING = 88.0 / HEIGHT_DP
        const val CONTENT_TOP = 70.0 / HEIGHT_DP
        const val CONTENT_BOTTOM = 592.0 / HEIGHT_DP
        const val CONTROLS_TOP = 615.0 / HEIGHT_DP
        const val CONTROLS_BOTTOM = 660.0 / HEIGHT_DP
        fun centerY(row: Int, firstRow: Int): Double = BOTTOM_CENTER_Y - (row - firstRow) * ROW_SPACING
    }
}

/**
 * The same requirements drive both unlocks and layout. Only drawing uses a transitive
 * reduction: keeping all real gates is essential for old saves with missing clears.
 */
class LevelDependencyGraph(
    levels: List<TutorialLevel>,
    prerequisites: Map<String, Set<String>> = LevelProgress.requirementsForLevels(levels),
) {
    private val indices = levels.mapIndexed { index, level -> level.id to index }.toMap()
    private val requirements: Map<Int, Set<Int>>
    val nodes: List<LevelMapNode>
    val edges: List<LevelMapEdge>
    val totalRows: Int
    val maxFirstRow: Int

    init {
        require(levels.isNotEmpty()) { "The route map needs at least one level" }
        require(indices.size == levels.size && indices.keys.none(String::isBlank)) { "Level IDs must be unique and nonblank" }
        require(prerequisites.keys == indices.keys) { "Every level needs an explicit prerequisite set" }
        require(prerequisites.all { (id, required) -> id !in required && required.all { it in indices } }) {
            "Prerequisites must refer to other known levels"
        }
        requirements = prerequisites.map { (id, required) ->
            indices.getValue(id) to required.map(indices::getValue).toSet()
        }.toMap()

        val rows = mutableMapOf<Int, Int>()
        val ancestors = mutableMapOf<Int, Set<Int>>()
        val visiting = mutableSetOf<Int>()
        fun visit(index: Int) {
            if (index in rows) return
            require(visiting.add(index)) { "Level prerequisites must not contain a cycle" }
            val required = requirements.getValue(index)
            required.forEach(::visit)
            rows[index] = required.maxOfOrNull { rows.getValue(it) + 1 } ?: 0
            ancestors[index] = required + required.flatMap { ancestors.getValue(it) }
            visiting.remove(index)
        }
        levels.indices.forEach(::visit)

        nodes = levels.indices.map { index ->
            val row = rows.getValue(index)
            val peers = levels.indices.filter { rows.getValue(it) == row && levels[it].groupId == levels[index].groupId }
            val column = when (levels[index].groupId) {
                "recognition" -> .28
                "motion" -> .72
                else -> if (peers.size == 1) .5 else .20 + .60 * peers.indexOf(index) / (peers.size - 1)
            }
            val width = when (levels[index].groupId) {
                "recognition", "motion" -> .36
                else -> if (peers.size == 1) .48 else .27
            }
            LevelMapNode(index, row, column, width)
        }.sortedWith(compareBy<LevelMapNode> { it.row }.thenBy { it.column }.thenBy { it.index })
        require(nodes.groupBy { it.row }.values.all { it.size <= 3 }) { "A route row supports up to three parallel levels" }
        edges = levels.indices.flatMap { target ->
            val required = requirements.getValue(target)
            required.filter { candidate ->
                required.none { alternate -> alternate != candidate && candidate in ancestors.getValue(alternate) }
            }.map { LevelMapEdge(it, target) }
        }.sortedWith(compareBy<LevelMapEdge> { rows.getValue(it.fromIndex) }.thenBy { it.fromIndex }.thenBy { it.toIndex })
        totalRows = rows.values.max() + 1
        // Keep the final row reachable even if a future, wider branch fills the nine input slots.
        maxFirstRow = ((totalRows - LevelMapWindow.VISIBLE_ROWS).coerceAtLeast(0) until totalRows)
            .first { first -> nodes.count { it.row >= first } <= PalmPanelLayout.levelActions.size }
    }

    /** Full gates, not the reduced set of visible lines. */
    fun prerequisiteIndices(index: Int): Set<Int> = requirements.getValue(index).toSet()

    fun windowForLevel(index: Int): LevelMapWindow = window(nodes.first { it.index == index }.row - 1)

    fun window(firstRow: Int): LevelMapWindow {
        val start = firstRow.coerceIn(0, maxFirstRow)
        // Never split a parallel row or silently drop its sibling when the input slots fill up.
        val rows = (start until minOf(totalRows, start + LevelMapWindow.VISIBLE_ROWS))
            .takeWhile { row -> nodes.count { it.row in start..row } <= PalmPanelLayout.levelActions.size }
        val last = rows.last()
        val visible = nodes.filter { it.row in start..last }
        val nodesByIndex = nodes.associateBy { it.index }
        val crossingEdges = edges.filter {
            nodesByIndex.getValue(it.fromIndex).row <= last && nodesByIndex.getValue(it.toIndex).row >= start
        }
        val buttons = visible.mapIndexed { slot, node ->
            val y = LevelMapWindow.centerY(node.row, start)
            NormalizedButtonRect(PalmPanelLayout.levelActions[slot],
                node.column - node.width / 2, y - LevelMapWindow.NODE_HEIGHT / 2,
                node.column + node.width / 2, y + LevelMapWindow.NODE_HEIGHT / 2)
        } + listOf(
            NormalizedButtonRect(PalmAction.PREVIOUS_GROUP, .08, LevelMapWindow.CONTROLS_TOP, .33, LevelMapWindow.CONTROLS_BOTTOM),
            NormalizedButtonRect(PalmAction.BACK, .375, LevelMapWindow.CONTROLS_TOP, .625, LevelMapWindow.CONTROLS_BOTTOM),
            NormalizedButtonRect(PalmAction.NEXT_GROUP, .67, LevelMapWindow.CONTROLS_TOP, .92, LevelMapWindow.CONTROLS_BOTTOM),
        )
        return LevelMapWindow(start, visible, crossingEdges,
            PalmPanelSpec(LevelMapWindow.WIDTH_DP, LevelMapWindow.HEIGHT_DP, LevelMapWindow.WIDTH_METERS, buttons), rows.size)
    }
}
