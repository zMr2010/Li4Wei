package com.fourthward.game.vr

import com.fourthward.game.core.LevelDependencyGraph
import com.fourthward.game.core.LevelMapWindow
import com.fourthward.game.core.TutorialLevels
import org.junit.Assert.*
import org.junit.Test

class VrLevelMapStateTest {
    private fun state(nodes: List<VrMapNode>, edges: List<VrMapEdge>, firstRow: Int = 0) =
        VrLevelMapState(nodes, edges, firstRow, 12, 0, 15)

    @Test fun edgesStopAtTheSameButtonEdgesUsedByPhysicalHitTesting() {
        val map = state(listOf(VrMapNode(0, 0, .5, true, true), VrMapNode(1, 1, .5, false, true)), listOf(VrMapEdge(0, 1)))
        val segment = map.segments().single()
        assertEquals(LevelMapWindow.centerY(0, 0) - LevelMapWindow.NODE_HEIGHT / 2, segment.y1, 1e-12)
        assertEquals(LevelMapWindow.centerY(1, 0) + LevelMapWindow.NODE_HEIGHT / 2, segment.y2, 1e-12)
        assertTrue(segment.y1 > segment.y2)
        assertTrue(segment.available)
        assertFalse(segment.completed)
    }

    @Test fun forkRoutesAreSeparateAndDoNotJoinIndependentChildren() {
        val map = state(listOf(VrMapNode(8, 0, .5, true, true), VrMapNode(3, 1, .28, true, true),
            VrMapNode(9, 1, .72, false, true)), listOf(VrMapEdge(8, 3), VrMapEdge(8, 9)))
        val paths = map.segments().groupBy { it.toIndex }
        assertEquals(setOf(3, 9), paths.keys)
        assertTrue(paths.values.all { it.size == 3 })
        assertTrue(paths.getValue(3).all { it.completed })
        assertTrue(paths.getValue(9).all { it.available && !it.completed })
        assertEquals(.28, paths.getValue(3).last().x2, 1e-12)
        assertEquals(.72, paths.getValue(9).last().x2, 1e-12)
        assertFalse(map.segments().any { it.fromIndex == 3 || it.fromIndex == 9 })
    }

    @Test fun incomingAndOutgoingRoutesClipWithoutLeakingIntoHeaderOrControls() {
        val nodes = (0..5).map { VrMapNode(it, it, .5, false, false) }
        val edges = (0..4).map { VrMapEdge(it, it + 1) }
        val segments = state(nodes, edges, firstRow = 1).segments()
        assertEquals(setOf(0, 1, 2, 3), segments.map { it.fromIndex }.toSet())
        assertEquals(VrLevelMapState.BOTTOM, segments.first().y1, 1e-12)
        assertEquals(VrLevelMapState.TOP, segments.last().y2, 1e-12)
        assertTrue(segments.all { it.y1 in VrLevelMapState.TOP..VrLevelMapState.BOTTOM &&
            it.y2 in VrLevelMapState.TOP..VrLevelMapState.BOTTOM })
        assertTrue(segments.all { !it.completed && !it.available })
    }

    @Test fun gateStillLooksLockedWhenAnOldSaveHasOnlyTheLastMovementClear() {
        val map = state(listOf(VrMapNode(8, 0, .5, true, true), VrMapNode(9, 1, .72, false, false)),
            listOf(VrMapEdge(8, 9)))
        assertTrue(map.segments().all { !it.available && !it.completed })
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
    @Test fun atlasHoldsNineNodesThreeControlsAndEveryExistingOverlayWithoutOverlap() {
        assertEquals(12, VrUiAtlas.buttons.size)
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
