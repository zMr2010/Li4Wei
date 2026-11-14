package com.fourthward.game.core

import org.junit.Assert.*
import org.junit.Test

class LevelDependencyGraphTest {
    private val levels = TutorialLevels.all()
    private val graph = LevelDependencyGraph(levels)
    private val movement = listOf(0, 1, 2, 6, 7, 8)

    @Test fun stableIndicesAppearExactlyOnceAndAllDependenciesPointUpward() {
        assertEquals(levels.indices.toSet(), graph.nodes.map { it.index }.toSet())
        assertEquals(levels.size, graph.nodes.size)
        val byIndex = graph.nodes.associateBy { it.index }
        graph.edges.forEach { edge ->
            assertTrue(byIndex.getValue(edge.fromIndex).row < byIndex.getValue(edge.toIndex).row)
            assertTrue(edge.fromIndex in graph.prerequisiteIndices(edge.toIndex))
        }
        assertEquals((0..5).toList(), movement.map { byIndex.getValue(it).row })
        movement.forEach { assertEquals(.48, byIndex.getValue(it).width, 0.0) }
        assertEquals(12, graph.totalRows)
        assertEquals(9, graph.maxFirstRow)
    }

    @Test fun independentChaptersBranchAtTheSameDepthAndRemainInTheirOwnLanes() {
        val byIndex = graph.nodes.associateBy { it.index }
        assertEquals(byIndex.getValue(3).row, byIndex.getValue(9).row)
        (3..5).forEach { assertEquals(.28, byIndex.getValue(it).column, 0.0) }
        (9..14).forEach { assertEquals(.72, byIndex.getValue(it).column, 0.0) }
        (3..5).forEach { assertEquals(.36, byIndex.getValue(it).width, 0.0) }
        (9..14).forEach { assertEquals(.36, byIndex.getValue(it).width, 0.0) }
        assertTrue(LevelMapEdge(8, 3) in graph.edges)
        assertTrue(LevelMapEdge(8, 9) in graph.edges)
        assertFalse(graph.edges.any { it.fromIndex in 3..5 && it.toIndex in 9..14 })
        assertFalse(graph.edges.any { it.fromIndex in 9..14 && it.toIndex in 3..5 })
        assertEquals(14, graph.edges.size)
    }

    @Test fun reducedLinesPreserveTheReachabilityOfEveryActualGate() {
        fun ancestors(index: Int): Set<Int> {
            val direct = graph.edges.filter { it.toIndex == index }.map { it.fromIndex }.toSet()
            return direct + direct.flatMap(::ancestors)
        }
        levels.indices.forEach { index ->
            assertTrue(ancestors(index).containsAll(graph.prerequisiteIndices(index)))
        }
        assertEquals(movement.toSet(), graph.prerequisiteIndices(3))
        assertEquals(movement.toSet() + 9, graph.prerequisiteIndices(10))
        assertEquals(listOf(LevelMapEdge(9, 10)), graph.edges.filter { it.toIndex == 10 })
    }

    @Test fun missingOldClearsCannotBypassFullChapterGateThroughAReducedLine() {
        val oldIds = setOf(levels[8].id, levels[3].id)
        val progress = LevelProgress.forLevels(levels, oldIds)
        assertTrue(progress.isUnlocked(3)) // Previously completed levels remain replayable.
        assertFalse(progress.isUnlocked(4))
        assertFalse(progress.isUnlocked(9))
        assertFalse(progress.recordCompletion(9))
        assertEquals(oldIds, progress.completedIds)
        val reloaded = LevelProgress.forLevels(levels, progress.completedIds)
        assertFalse(reloaded.isUnlocked(9))
    }

    @Test fun eachViewportSlotSelectsItsActualVisibleNodeAndSharesTheHitRect() {
        val seen = mutableSetOf<Int>()
        for (row in 0..graph.maxFirstRow) {
            val window = graph.window(row)
            assertTrue(window.nodes.size <= 9)
            window.nodes.forEachIndexed { slot, node ->
                seen.add(node.index)
                val button = window.layout.buttons[slot]
                val x = (button.left + button.right) / 2
                val y = (button.top + button.bottom) / 2
                assertEquals(node.index, window.levelAtSlot(slot))
                assertEquals(node.index, window.levelForAction(window.layout.hitTest(x, y)!!))
                assertEquals(LevelMapWindow.centerY(node.row, row), y, 1e-10)
                window.layout.buttons.filter { it !== button }.forEach { other ->
                    assertFalse(other.contains(x, y))
                }
            }
            assertNull(window.levelAtSlot(-1))
            assertNull(window.levelAtSlot(window.nodes.size))
            assertNull(window.levelForAction(PalmAction.BACK))
        }
        assertEquals(levels.indices.toSet(), seen)
    }

    @Test fun visiblePrerequisitesSitBelowTheirSuccessorsAndCrossingLinesAreRetained() {
        val window = graph.window(5)
        val byIndex = window.nodes.associateBy { it.index }
        assertEquals(listOf(8, 3, 9, 4, 10), window.nodes.map { it.index })
        assertTrue(LevelMapEdge(7, 8) in window.edges) // Continue below this viewport.
        assertTrue(LevelMapEdge(4, 5) in window.edges) // Continue above this viewport.
        assertFalse(LevelMapEdge(0, 1) in window.edges)
        window.edges.filter { it.fromIndex in byIndex && it.toIndex in byIndex }.forEach {
            assertTrue(LevelMapWindow.centerY(byIndex.getValue(it.fromIndex).row, window.firstRow) >
                LevelMapWindow.centerY(byIndex.getValue(it.toIndex).row, window.firstRow))
        }
    }

    @Test fun navigationClampsAtBothEndsAndFocusKeepsTheLevelVisible() {
        assertEquals(0, graph.window(Int.MIN_VALUE).firstRow)
        assertEquals(graph.maxFirstRow, graph.window(Int.MAX_VALUE).firstRow)
        levels.indices.forEach { index -> assertTrue(graph.windowForLevel(index).nodes.any { it.index == index }) }
        val window = graph.window(0)
        assertEquals(PalmAction.PREVIOUS_GROUP, window.layout.hitTest(.205, .895))
        assertEquals(PalmAction.BACK, window.layout.hitTest(.5, .895))
        assertEquals(PalmAction.NEXT_GROUP, window.layout.hitTest(.795, .895))
    }

    @Test fun parallelMovementChallengesUseOneRowWithoutIntroducingFalseDependencies() {
        val requirements = LevelProgress.requirementsForLevels(levels).toMutableMap()
        listOf(6, 7, 8).forEach { requirements[levels[it].id] = setOf(levels[2].id) }
        val parallel = LevelDependencyGraph(levels, requirements)
        val challengeNodes = parallel.nodes.filter { it.index in 6..8 }
        assertEquals(listOf(3, 3, 3), challengeNodes.map { it.row })
        assertEquals(listOf(.20, .50, .80), challengeNodes.map { it.column })
        assertTrue(challengeNodes.all { it.width == .27 })
        assertEquals(listOf(6, 7, 8), parallel.window(3).nodes.take(3).map { it.index })
        assertFalse(parallel.edges.any { it.fromIndex in 6..8 && it.toIndex in 6..8 })
        listOf(3, 9).forEach { target ->
            assertEquals(setOf(6, 7, 8), parallel.edges.filter { it.toIndex == target }.map { it.fromIndex }.toSet())
        }
    }

    @Test fun changingViewportRequiresAFreshFrontToPanelPoke() {
        val detector = PalmPoke()
        val first = graph.window(0)
        detector.update(.5, .70, .05, true, .016, first.layout)
        assertEquals(PalmAction.LEVEL_1, detector.update(.5, .70, .004, true, .016, first.layout).action)
        val second = graph.window(1)
        assertNull(detector.update(.5, .70, .004, true, .016, second.layout).action)
        detector.update(.5, .70, .05, true, .016, second.layout)
        assertEquals(PalmAction.LEVEL_1, detector.update(.5, .70, .004, true, .016, second.layout).action)
        assertEquals(1, second.levelForAction(PalmAction.LEVEL_1))
    }

    @Test fun lockedGraphNodeCannotBePoked() {
        val detector = PalmPoke()
        val window = graph.window(0)
        val disabled = setOf(PalmAction.LEVEL_2, PalmAction.LEVEL_3)
        detector.update(.5, .47, .05, true, .016, window.layout, disabled)
        val contact = detector.update(.5, .47, .004, true, .016, window.layout, disabled)
        assertNull(contact.action)
        assertNull(contact.hoveredAction)
    }

    @Test(expected = IllegalArgumentException::class)
    fun cyclesAreRejectedBeforeProducingMisleadingRows() {
        val requirements = LevelProgress.requirementsForLevels(levels).toMutableMap()
        requirements[levels[0].id] = setOf(levels[1].id)
        LevelDependencyGraph(levels, requirements)
    }

    @Test(expected = IllegalArgumentException::class)
    fun unknownPrerequisitesAreRejectedInsteadOfSilentlyUnlockingANode() {
        val requirements = LevelProgress.requirementsForLevels(levels).toMutableMap()
        requirements[levels[0].id] = setOf("missing")
        LevelDependencyGraph(levels, requirements)
    }
}
