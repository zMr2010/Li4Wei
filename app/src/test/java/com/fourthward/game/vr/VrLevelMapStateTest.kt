package com.fourthward.game.vr

import com.fourthward.game.core.LevelDependencyGraph
import com.fourthward.game.core.LevelMapWindow
import com.fourthward.game.core.TutorialLevels
import org.junit.Assert.*
import org.junit.Test

class VrLevelMapStateTest {
    private fun state(nodes: List<VrMapNode>, edges: List<VrMapEdge>, firstRow: Int = 0) =
        VrLevelMapState(nodes, edges, firstRow, 10, 0, 15)

    @Test fun edgesStopAtTheSameButtonEdgesUsedByPhysicalHitTesting() {
        val map = state(listOf(VrMapNode(0, 0, .5, true, true), VrMapNode(1, 1, .5, false, true)), listOf(VrMapEdge(0, 1)))
        val segment = map.segments().single()
        assertEquals(LevelMapWindow.centerY(0, 0) - LevelMapWindow.NODE_HEIGHT / 2, segment.y1, 1e-12)
        assertEquals(LevelMapWindow.centerY(1, 0) + LevelMapWindow.NODE_HEIGHT / 2, segment.y2, 1e-12)
        assertTrue(segment.y1 > segment.y2)
        assertTrue(segment.available)
        assertFalse(segment.completed)
    }

    @Test fun optionalChallengeBranchesDoNotInterruptOrRejoinTheTutorialLine() {
        val map = state(listOf(VrMapNode(2, 0, .14, true, true), VrMapNode(3, 1, .14, true, true),
            VrMapNode(6, 1, .385, false, true), VrMapNode(7, 1, .62, false, true),
            VrMapNode(8, 1, .855, false, true)),
            listOf(VrMapEdge(2, 3), VrMapEdge(2, 6), VrMapEdge(2, 7), VrMapEdge(2, 8)))
        val paths = map.segments().groupBy { it.toIndex }
        assertEquals(setOf(3, 6, 7, 8), paths.keys)
        assertEquals(1, paths.getValue(3).size)
        (6..8).forEach { assertEquals(3, paths.getValue(it).size) }
        assertTrue(paths.getValue(3).all { it.completed })
        (6..8).forEach { assertTrue(paths.getValue(it).all { path -> path.available && !path.completed }) }
        assertEquals(.14, paths.getValue(3).last().x2, 1e-12)
        assertEquals(listOf(.385, .62, .855), (6..8).map { paths.getValue(it).last().x2 })
        assertTrue(map.segments().all { it.fromIndex == 2 })
    }

    @Test fun incomingAndOutgoingRoutesClipWithoutLeakingIntoHeaderOrControls() {
        val nodes = (0..8).map { VrMapNode(it, it, .5, false, false) }
        val edges = (0..7).map { VrMapEdge(it, it + 1) }
        val segments = state(nodes, edges, firstRow = 1).segments()
        assertEquals((0..6).toSet(), segments.map { it.fromIndex }.toSet())
        assertEquals(VrLevelMapState.BOTTOM, segments.first().y1, 1e-12)
        assertEquals(VrLevelMapState.TOP, segments.last().y2, 1e-12)
        assertTrue(segments.all { it.y1 in VrLevelMapState.TOP..VrLevelMapState.BOTTOM &&
            it.y2 in VrLevelMapState.TOP..VrLevelMapState.BOTTOM })
        assertTrue(segments.all { !it.completed && !it.available })
    }

    @Test fun lastTutorialClearWithoutItsPrefixDoesNotLightTheNextTutorialRoute() {
        val map = state(listOf(VrMapNode(2, 0, .14, true, true), VrMapNode(3, 1, .14, false, false)),
            listOf(VrMapEdge(2, 3)))
        assertTrue(map.segments().all { !it.available && !it.completed })
    }

    @Test fun capacityLimitedWindowDoesNotDrawConnectionsForItsHiddenUpperRows() {
        val map = VrLevelMapState((0..7).map { VrMapNode(it, it, .5, true, true) },
            (0..6).map { VrMapEdge(it, it + 1) }, 0, 8, 8, 8, visibleRows = 4)
        assertTrue(map.clipTop > VrLevelMapState.TOP)
        assertEquals((0..3).toSet(), map.segments().map { it.fromIndex }.toSet())
        assertEquals(map.clipTop, map.segments().last().y2, 1e-12)
        assertTrue(map.segments().all { it.y1 >= map.clipTop && it.y2 >= map.clipTop })
    }

    @Test fun everyRealWindowKeepsVisibleNodesAndRoutesAligned() {
        val graph = LevelDependencyGraph(TutorialLevels.all())
        for (row in 0..graph.maxFirstRow) {
            val window = graph.window(row)
            val map = state(graph.nodes.map { VrMapNode(it.index, it.row, it.column, true, true, it.width) },
                window.edges.map { VrMapEdge(it.fromIndex, it.toIndex) }, row)
            window.nodes.forEachIndexed { slot, node ->
                val button = window.layout.buttons[slot]
                assertEquals(map.centerY(node.row), (button.top + button.bottom) / 2, 1e-12)
                assertEquals(node.width, button.width, 1e-12)
            }
            assertTrue(map.segments().all { it.y1 >= it.y2 })
        }
    }
    @Test fun atlasHoldsTheLargestMapAndEveryOverlayWithoutOverlap() {
        val graph = LevelDependencyGraph(TutorialLevels.all())
        val maxButtons = (0..graph.maxFirstRow).maxOf { graph.window(it).layout.buttons.size }
        assertEquals(maxButtons, VrUiAtlas.buttons.size)
        VrUiAtlas.all.forEach { region ->
            assertTrue(region.x >= 0 && region.y >= 0)
            assertTrue(region.x + region.width <= 2048 && region.y + region.height <= 1024)
        }
        VrUiAtlas.all.forEachIndexed { index, a ->
            VrUiAtlas.all.drop(index + 1).forEach { b ->
                val overlap = a.x < b.x + b.width && b.x < a.x + a.width &&
                    a.y < b.y + b.height && b.y < a.y + a.height
                assertFalse("Atlas regions overlap: $a / $b", overlap)
            }
        }
    }

}
