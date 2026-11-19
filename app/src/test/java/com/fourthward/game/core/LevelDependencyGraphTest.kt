package com.fourthward.game.core

import org.junit.Assert.*
import org.junit.Test

class LevelDependencyGraphTest {
    private val levels = TutorialLevels.all()
    private val graph = LevelDependencyGraph(levels)
    private val tutorials = listOf(0, 1, 2, 3, 4, 5, 9, 10, 11)
    private val challenges = listOf(6, 7, 8, 12, 13, 14)

    @Test fun stableIndicesAppearExactlyOnceAndAllDependenciesPointUpward() {
        assertEquals(levels.indices.toSet(), graph.nodes.map { it.index }.toSet())
        assertEquals(levels.size, graph.nodes.size)
        val byIndex = graph.nodes.associateBy { it.index }
        graph.edges.forEach { edge ->
            assertTrue(byIndex.getValue(edge.fromIndex).row < byIndex.getValue(edge.toIndex).row)
            assertTrue(edge.fromIndex in graph.prerequisiteIndices(edge.toIndex))
        }
        assertEquals((0..8).toList(), tutorials.map { byIndex.getValue(it).row })
        tutorials.forEach { assertEquals(.22, byIndex.getValue(it).width, 0.0) }
        assertEquals(10, graph.totalRows)
        assertEquals(4, graph.maxFirstRow)
        assertEquals(listOf(0, 1, 2, 3, 6, 7, 8, 4, 5), graph.window(0).nodes.map { it.index })
        assertEquals(8, graph.window(graph.maxFirstRow).nodes.size)
    }

    @Test fun allNineTutorialsStayOnOneLineWithOptionalChallengesBesideTheirSuccessor() {
        val byIndex = graph.nodes.associateBy { it.index }
        tutorials.forEach { assertEquals(.14, byIndex.getValue(it).column, 0.0) }
        tutorials.zipWithNext().forEach { (a, b) -> assertTrue(LevelMapEdge(a, b) in graph.edges) }
        assertEquals(listOf(3, 3, 3), (6..8).map { byIndex.getValue(it).row })
        assertEquals(listOf(.385, .62, .855), (6..8).map { byIndex.getValue(it).column })
        assertEquals(byIndex.getValue(3).row, byIndex.getValue(6).row)
        assertEquals(listOf(9, 9, 9), (12..14).map { byIndex.getValue(it).row })
        challenges.forEach { assertEquals(.21, byIndex.getValue(it).width, 0.0) }
        assertFalse(graph.edges.any { it.fromIndex in challenges })
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
        assertEquals(setOf(0, 1, 2), graph.prerequisiteIndices(3))
        assertEquals(setOf(0, 1, 2), graph.prerequisiteIndices(8))
        assertEquals(tutorials.take(7).toSet(), graph.prerequisiteIndices(10))
        assertEquals(listOf(LevelMapEdge(9, 10)), graph.edges.filter { it.toIndex == 10 })
    }

    @Test fun missingOldClearsCannotBypassTheTutorialPrefixThroughAReducedLine() {
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
        val window = graph.window(2)
        val byIndex = window.nodes.associateBy { it.index }
        assertEquals(listOf(2, 3, 6, 7, 8, 4, 5, 9, 10), window.nodes.map { it.index })
        assertTrue(LevelMapEdge(1, 2) in window.edges) // Continue below this viewport.
        assertTrue(LevelMapEdge(10, 11) in window.edges) // Continue above this viewport.
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
        val controlsY = (LevelMapWindow.CONTROLS_TOP + LevelMapWindow.CONTROLS_BOTTOM) / 2
        assertEquals(PalmAction.PREVIOUS_GROUP, window.layout.hitTest(.205, controlsY))
        assertEquals(PalmAction.BACK, window.layout.hitTest(.5, controlsY))
        assertEquals(PalmAction.NEXT_GROUP, window.layout.hitTest(.795, controlsY))
    }

    @Test fun welcomeRouteOpensAtTheSavedLevelAndOffersStartAtTheUsualReturnPosition() {
        for (index in levels.indices) {
            val welcome = graph.windowForLevel(index, PalmAction.CONTEXT)
            val route = graph.windowForLevel(index)
            assertEquals(route.nodes, welcome.nodes)
            assertTrue(welcome.nodes.any { it.index == index })
            assertEquals(route.edges, welcome.edges)
            val y = (LevelMapWindow.CONTROLS_TOP + LevelMapWindow.CONTROLS_BOTTOM) / 2
            assertEquals(PalmAction.CONTEXT, welcome.layout.hitTest(.50, y))
            assertEquals(PalmAction.BACK, route.layout.hitTest(.50, y))
            for (map in listOf(welcome, route)) {
                assertEquals(PalmAction.TOGGLE_THEME, map.layout.hitTest(.60, .04))
                assertNull(map.levelForAction(PalmAction.TOGGLE_THEME))
            }
        }
    }

    @Test fun denserOptionalRowsRemainWholeAndWithinInputCapacity() {
        val requirements = LevelProgress.requirementsForLevels(levels).toMutableMap()
        // Two adjacent four-node rows exceed nine slots across six layers.
        (12..14).forEach { requirements[levels[it].id] = tutorials.take(4).map { levels[it].id }.toSet() }
        val parallel = LevelDependencyGraph(levels, requirements)
        val challengeNodes = parallel.nodes.filter { it.index in 6..8 }
        assertEquals(listOf(3, 3, 3), challengeNodes.map { it.row })
        assertEquals(listOf(.385, .62, .855), challengeNodes.map { it.column })
        assertTrue(challengeNodes.all { it.width == .21 })
        assertEquals(listOf(3, 6, 7, 8), parallel.window(3).nodes.take(4).map { it.index })
        assertFalse(parallel.edges.any { it.fromIndex in 6..8 && it.toIndex in 6..8 })
        val windows = (0..parallel.maxFirstRow).map(parallel::window)
        assertTrue(windows.all { it.nodes.size <= 9 })
        assertTrue(windows.any { it.visibleRows < LevelMapWindow.VISIBLE_ROWS })
        assertEquals(levels.indices.toSet(), windows.flatMap { it.nodes }.map { it.index }.toSet())
        assertTrue(windows.last().nodes.any { it.row == parallel.totalRows - 1 })
        windows.forEach { window ->
            assertTrue(window.visibleRows in 1..LevelMapWindow.VISIBLE_ROWS)
            window.nodes.groupBy { it.row }.forEach { (row, included) ->
                assertEquals(parallel.nodes.filter { it.row == row }, included)
            }
        }
        assertFalse(parallel.edges.any { it.fromIndex in challenges })
    }

    @Test fun changingViewportRequiresAFreshFrontToPanelPoke() {
        val detector = PalmPoke()
        val first = graph.window(0)
        val y = LevelMapWindow.BOTTOM_CENTER_Y
        detector.update(.14, y, .05, true, .016, first.layout)
        assertEquals(PalmAction.LEVEL_1, detector.update(.14, y, .004, true, .016, first.layout).action)
        val second = graph.window(1)
        assertNull(detector.update(.14, y, .004, true, .016, second.layout).action)
        detector.update(.14, y, .05, true, .016, second.layout)
        assertEquals(PalmAction.LEVEL_1, detector.update(.14, y, .004, true, .016, second.layout).action)
        assertEquals(1, second.levelForAction(PalmAction.LEVEL_1))
    }

    @Test fun lockedGraphNodeCannotBePoked() {
        val detector = PalmPoke()
        val window = graph.window(0)
        val disabled = setOf(PalmAction.LEVEL_2, PalmAction.LEVEL_3)
        val y = LevelMapWindow.centerY(1, 0)
        detector.update(.14, y, .05, true, .016, window.layout, disabled)
        val contact = detector.update(.14, y, .004, true, .016, window.layout, disabled)
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
