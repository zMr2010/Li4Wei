package com.fourthward.game.core

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs

class ExpeditionLevelsTest {
    private val levels = ExpeditionLevels.all()
    private fun game(index: Int) = FourthwardGame(listOf(levels[index]))

    private fun move(game: FourthwardGame, side: Double = 0.0, depth: Double = 0.0) {
        val result = game.movePlanar(Vec3(side, 0.0, depth))
        assertFalse("Blocked at ${game.playerFootWorld()} while moving ($side,$depth)", result.blocked)
    }

    private fun angle(game: FourthwardGame, degrees: Double) {
        game.rotate(Math.toRadians(degrees) - game.frame.angleRadians)
        assertFalse("Rotation blocked at ${game.playerFootWorld()}", game.lastMotionBlocked)
        assertEquals(degrees, game.rotationDegrees, 1e-7)
    }

    @Test fun definitionsHaveStableIdsRealCheckpointVolumesAndThreeHintsPerStage() {
        assertEquals(listOf("crossed-portals", "hidden-gallery", "beacon-home"), levels.map { it.id })
        for ((index, level) in levels.withIndex()) {
            assertEquals("movement", level.groupId)
            assertEquals("移动", level.groupTitle)
            assertNull(level.geometryTopic)
            val mission = level.expedition!!
            assertEquals(listOf(1, 2, 2)[index], mission.checkpoints.size)
            assertEquals(listOf(2, 3, 3)[index], mission.stages.size)
            assertTrue(mission.stages.all { it.hints.size == 3 })
            assertEquals(mission.checkpoints.map { it.volume } + level.goal, level.world.spheres)
            assertEquals(level.world.boxes.size, level.world.boxes.map { it.id }.distinct().size)
            assertTrue(level.world.boxes.filterNot { it.solid }.all { it.colorArgb != null })
        }
        assertTrue(levels[0].expedition!!.ordered)
        assertTrue(levels[1].expedition!!.ordered)
        assertFalse(levels[2].expedition!!.ordered)
    }

    @Test fun bothCrossedDoorsAreTraversableByWalkingAndChangingDirectionInTheMiddleRoom() {
        val game = game(0)
        assertFalse(game.locomotionLocked)
        angle(game, 90.0)
        move(game, side = 1.4)
        move(game, depth = -2.8)
        assertEquals(setOf("amber-door"), game.completedCheckpointIds)
        assertTrue(game.goalReady)
        assertEquals("穿过青色门廊，到达出口。", game.missionInstruction)
        assertFalse(game.level.world.spheres.any { it.id == "cyan-door" })
        assertFalse(game.completed)
        angle(game, 0.0)
        move(game, side = 2.4)
        move(game, depth = -2.6)
        assertEquals(setOf("amber-door"), game.completedCheckpointIds)
        assertFalse(game.completed)
        move(game, depth = -.8)
        assertTrue(game.completed)
    }

    @Test fun crossedDoorsHaveDisjointAngularConesEvenForAPointPlayerAtAnyFixedAngle() {
        // Both gates have Y in [.8,2]. A line through the spawn that intersects the first
        // aperture and has positive X must have Y/X >= .8/.6. A line intersecting the
        // second must have Y/X <= 2/1.8. Negative X/rays cannot reach the second at Y>0.
        // This strict gap proves every fixed orientation fails, including unsampled angles.
        val firstRight = levels[0].world.boxes.single { it.id == "amber-gate-right" }
        val firstLower = levels[0].world.boxes.single { it.id == "amber-gate-lower" }
        val secondLeft = levels[0].world.boxes.single { it.id == "cyan-gate-left" }
        val secondUpper = levels[0].world.boxes.single { it.id == "cyan-gate-upper" }
        val firstMaxX = firstRight.center.x - firstRight.halfExtents.x
        val firstMinY = firstLower.center.y + firstLower.halfExtents.y
        val secondMinX = secondLeft.center.x + secondLeft.halfExtents.x
        val secondMaxY = secondUpper.center.y - secondUpper.halfExtents.y
        assertTrue(firstMaxX > 0 && firstMinY > 0 && secondMinX > 0)
        assertTrue(firstMinY / firstMaxX > secondMaxY / secondMinX)
    }

    @Test fun initialCrossedDoorSliceIsClosedAcrossTheEntireFirstWall() {
        // At Y=0 the first aperture is absent. One full-width solid rectangle separates
        // spawn from the waypoint and exit, and the outer shell closes both possible side routes.
        val firstWall = levels[0].world.boxes.filter { it.id.startsWith("amber-gate") }
        for (x in listOf(-1.2, -.6, 0.0, .6, 2.4, 3.6)) {
            assertTrue(firstWall.any { contains(it, Vec4(x, 0.0, 1.0, -2.0)) })
        }
        val game = game(0)
        assertTrue(game.movePlanar(Vec3(0.0, 0.0, -6.2)).blocked)
        assertTrue(game.playerFootWorld().w > -2.0)
        assertFalse(game.completed)
        assertTrue(game.completedCheckpointIds.isEmpty())
    }

    @Test fun hiddenGalleryRequiresTheFarConnectorAndReturnsThroughTheOtherCorridor() {
        val blocked = game(1)
        angle(blocked, 90.0)
        assertTrue(blocked.movePlanar(Vec3(1.5, 0.0, 0.0)).blocked)
        assertTrue(blocked.playerFootWorld().y < .65)

        val game = game(1)
        move(game, depth = -4.7)
        angle(game, 90.0)
        move(game, side = 1.5)
        assertEquals(setOf("connector"), game.completedCheckpointIds)
        angle(game, 0.0)
        move(game, side = 1.8)
        angle(game, 90.0)
        move(game, side = -1.5)
        assertEquals(setOf("connector", "return-corridor"), game.completedCheckpointIds)
        angle(game, 0.0)
        move(game, depth = 4.5)
        assertTrue(game.completed)
    }

    @Test fun hiddenGalleryCannotCrossDividerAtAnyDepthInTheInitialSlice() {
        val divider = levels[1].world.boxes.single { it.id == "corridor-divider" }
        val back = levels[1].world.boxes.single { it.id == "boundary-w-low" }
        val front = levels[1].world.boxes.single { it.id == "boundary-w-high" }
        // Convex full-height divider meets both end walls; there is no W end to round.
        for (w in listOf(back.center.w, front.center.w)) for (z in listOf(0.0, 1.7)) {
            assertTrue(contains(divider, Vec4(.9, 0.0, z, w)))
        }
        for (depth in listOf(0.0, -2.0, -4.7, -6.0)) {
            val game = game(1)
            move(game, depth = depth)
            assertTrue(game.movePlanar(Vec3(1.8, 0.0, 0.0)).blocked)
            assertTrue(game.playerFootWorld().x < .8)
            assertFalse(game.completed)
        }
    }

    @Test fun beaconRouteVisitsBothRoomsThenReturnsToTheStartingEntrance() {
        val game = game(2)
        assertFalse(game.completed)
        move(game, side = 1.4)
        move(game, depth = -4.8)
        assertEquals(setOf("amber-beacon"), game.completedCheckpointIds)
        move(game, depth = 4.0)
        move(game, side = -2.8)
        angle(game, 90.0)
        move(game, side = 1.4)
        move(game, depth = -4.0)
        assertEquals(setOf("amber-beacon", "cyan-beacon"), game.completedCheckpointIds)
        assertFalse(game.completed)
        move(game, depth = 4.0)
        move(game, side = -1.4)
        angle(game, 0.0)
        move(game, side = 1.4)
        assertFalse(game.completed)
        move(game, depth = .8)
        assertTrue(game.completed)
    }

    @Test fun beaconRoomsCanAlsoBeVisitedInTheReverseOrder() {
        val game = game(2)
        move(game, side = -1.4)
        angle(game, 90.0)
        move(game, side = 1.4)
        move(game, depth = -4.8)
        assertEquals(setOf("cyan-beacon"), game.completedCheckpointIds)
        move(game, depth = 4.0)
        move(game, side = -1.4)
        angle(game, 0.0)
        move(game, side = 2.8)
        move(game, depth = -4.0)
        assertTrue(game.goalReady)
        assertFalse(game.completed)
        move(game, depth = 4.0)
        move(game, side = -1.4)
        move(game, depth = .8)
        assertTrue(game.completed)
    }

    @Test fun returningAfterOnlyTheVisibleBeaconDoesNotPassTheMission() {
        val game = game(2)
        move(game, side = 1.4)
        move(game, depth = -4.8)
        move(game, depth = 4.8)
        move(game, side = -1.4)
        assertEquals(0.0, game.rotationDegrees, 1e-8)
        assertEquals(0.0, game.playerFootWorld().length(), 1e-7)
        assertFalse(game.goalReady)
        assertFalse(game.completed)
        assertEquals(setOf("amber-beacon"), game.completedCheckpointIds)
    }

    @Test fun everyBuildingHasEightClosedOuterFacesWithOverlappingEdges() {
        for (level in levels) {
            val shell = level.world.boxes.filter { it.id.startsWith("boundary-") }
            assertEquals(8, shell.size)
            val lower = (0..3).map { axis -> shell.minOf { it.center[axis] - it.halfExtents[axis] } }
            val upper = (0..3).map { axis -> shell.maxOf { it.center[axis] + it.halfExtents[axis] } }
            for (axis in 0..3) for (side in 0..1) {
                // The face is the convex hull of these 8 corners. A single solid box
                // contains all of them, proving continuity over the entire outer face.
                val varying = (0..3).filter { it != axis }
                val corners = (0 until 8).map { mask ->
                    val p = DoubleArray(4) { lower[it] }
                    p[axis] = if (side == 0) lower[axis] else upper[axis]
                    varying.forEachIndexed { bit, coordinate ->
                        p[coordinate] = if (mask and (1 shl bit) == 0) lower[coordinate] else upper[coordinate]
                    }
                    Vec4(p[0], p[1], p[2], p[3])
                }
                assertTrue("${level.id}: face $axis/$side is open",
                    shell.any { wall -> wall.solid && corners.all { contains(wall, it) } })
            }
        }
    }

    @Test fun physicalAndKeyboardMovementCannotEscapeTheOuterShell() {
        for (index in levels.indices) for (degrees in listOf(0.0, 90.0)) for (direction in listOf(-1.0, 1.0)) {
            val game = game(index)
            game.updateTrackedHead(Vec3(0.0, 1.65, 0.0))
            angle(game, degrees)
            assertTrue(game.updateTrackedHead(Vec3(direction * 30.0, 1.65, 0.0)).blocked)
            val point = game.playerFootWorld()
            val axis = if (degrees == 0.0) 0 else 1
            val boundary = levels[index].world.boxes.single { it.id == "boundary-${if (axis == 0) "x" else "y"}-${if (direction < 0) "low" else "high"}" }
            assertTrue(direction * point[axis] < direction * boundary.center[axis])
            assertFalse(game.completed)
        }
        for (index in levels.indices) for (direction in listOf(-1.0, 1.0)) {
            val game = game(index)
            assertTrue(game.movePlanar(Vec3(0.0, 0.0, direction * 30.0)).blocked)
            assertFalse(game.completed)
        }
    }

    @Test fun coloredLandmarksArePhysicalSectionsRatherThanAlwaysVisibleProjections() {
        val amberLintel = levels[0].world.boxes.single { it.id == "amber-landmark-lintel" }
        assertFalse(amberLintel.solid)
        assertNotNull(amberLintel.colorArgb)
        assertNull(SliceGeometry.sliceBox(amberLintel, SliceFrame()))
        assertNotNull(SliceGeometry.sliceBox(amberLintel, SliceFrame(PI / 2)))
        val cyanLintel = levels[0].world.boxes.single { it.id == "cyan-landmark-lintel" }
        assertNull(SliceGeometry.sliceBox(cyanLintel, SliceFrame(PI / 2)))
        assertNotNull(SliceGeometry.sliceBox(cyanLintel, SliceFrame(0.0, Vec4(0.0, 1.4, 0.0, 0.0))))
    }

    private fun contains(box: Box4, point: Vec4): Boolean = box.basis.indices.all { axis ->
        abs((point - box.center).dot(box.basis[axis])) <= box.halfExtents[axis] + 1e-9
    }
}
