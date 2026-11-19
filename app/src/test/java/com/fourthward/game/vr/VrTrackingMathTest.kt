package com.fourthward.game.vr

import com.fourthward.game.core.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class VrTrackingMathTest {
    private fun assertVector(expected: Vec3, actual: Vec3, epsilon: Double = 1e-7) {
        assertEquals(expected.x, actual.x, epsilon); assertEquals(expected.y, actual.y, epsilon); assertEquals(expected.z, actual.z, epsilon)
    }

    private fun facing(yaw: Double, pitch: Double = 0.0, roll: Double = 0.0): VrTrackingMath.Pose {
        fun multiply(a: DoubleArray, b: DoubleArray): DoubleArray {
            val av = Vec3(a[0], a[1], a[2]); val bv = Vec3(b[0], b[1], b[2])
            val v = bv * a[3] + av * b[3] + av.cross(bv)
            return doubleArrayOf(v.x, v.y, v.z, a[3] * b[3] - av.dot(bv))
        }
        val q = multiply(multiply(doubleArrayOf(0.0, sin(yaw / 2), 0.0, cos(yaw / 2)),
            doubleArrayOf(sin(pitch / 2), 0.0, 0.0, cos(pitch / 2))),
            doubleArrayOf(0.0, 0.0, sin(roll / 2), cos(roll / 2)))
        return VrTrackingMath.head(floatArrayOf(0f, 1.65f, 0f, q[0].toFloat(), q[1].toFloat(), q[2].toFloat(), q[3].toFloat()))!!
    }

    @Test fun wasdFollowsHorizontalViewAtEveryCardinalHeadingWithoutPitchOrRollDrift() {
        for (yaw in listOf(0.0, PI / 2, PI, -PI / 2)) {
            val right = Vec3(cos(yaw), 0.0, -sin(yaw))
            val forward = Vec3(-sin(yaw), 0.0, -cos(yaw))
            for (pitch in listOf(-PI / 3, 0.0, PI / 3)) for (roll in listOf(0.0, PI / 4)) {
                val head = facing(yaw, pitch, roll)
                assertVector(forward, VrTrackingMath.locomotion(head, 0.0, 1.0))
                assertVector(forward * -1.0, VrTrackingMath.locomotion(head, 0.0, -1.0))
                assertVector(right, VrTrackingMath.locomotion(head, 1.0, 0.0))
                assertVector(right * -1.0, VrTrackingMath.locomotion(head, -1.0, 0.0))
                assertVector(Vec3(0.0, .5, 0.0), VrTrackingMath.locomotion(head, 0.0, 0.0, .5))
            }
            for (pitch in listOf(-PI / 2, PI / 2)) {
                assertVector(forward, VrTrackingMath.locomotion(facing(yaw, pitch), 0.0, 1.0))
            }
        }
    }

    @Test fun startMapIsInFrontOfThePlayerWithinReachAndItsHitPlaneFacesTheirEyes() {
        for (yaw in listOf(0.0, PI / 2, PI, -PI / 2)) {
            val head = facing(yaw, pitch = PI / 4, roll = PI / 6).copy(position = Vec3(2.3, 1.2, -3.7))
            val panel = VrTrackingMath.startPanelPose(head)!!
            val forward = Vec3(-sin(yaw), 0.0, -cos(yaw))
            val right = Vec3(cos(yaw), 0.0, -sin(yaw))
            assertVector(head.position + forward * .56 - right * .19 - Vec3(0.0, .085, 0.0), panel.position)
            val toEyes = head.position - panel.position
            assertVector(toEyes * (1.0 / toEyes.length()), panel.normal)
            assertEquals(0.0, panel.right.y, 1e-9)
            val layout = LevelDependencyGraph(TutorialLevels.all()).window(0, PalmAction.CONTEXT).layout
            for (button in layout.buttons) {
                val x = (button.left + button.right) * .5
                val y = (button.top + button.bottom) * .5
                val target = panel.position + panel.right * ((x - .5) * layout.widthMeters) +
                    panel.up * ((.5 - y) * layout.heightMeters)
                val hit = VrTrackingMath.pointer(target, panel, layout.widthMeters, layout.heightMeters)
                assertEquals(button.action, layout.hitTest(hit.x, hit.y))
                assertTrue((target - head.position).length() < .70)
            }
        }
    }

    @Test fun heldForwardUpdatesWithHeadTurnAndSliceAngleIsAppliedOnlyOnce() {
        val input = InputState().apply { setEnabled(true); key(InputKey.FORWARD, true) }
        val first = input.frame(.05, 0)
        assertVector(Vec3(0.0, 0.0, -.075), VrTrackingMath.locomotion(facing(0.0), first.moveRightMeters, first.moveForwardMeters))
        val next = input.frame(.05, 1)
        val movement = VrTrackingMath.locomotion(facing(PI / 2), next.moveRightMeters, next.moveForwardMeters)
        assertVector(Vec3(-.075, 0.0, 0.0), movement)
        val game = FourthwardGame()
        game.updateTrackedHead(Vec3(0.0, 1.65, 0.0))
        game.rotate(PI / 2)
        game.move(movement)
        val feet = game.playerFootWorld()
        assertEquals(0.0, feet.x, 1e-7)
        assertEquals(-.075, feet.y, 1e-7)
        assertEquals(0.0, feet.w, 1e-7)
        assertEquals(90.0, game.rotationDegrees, 1e-9)
    }

    @Test fun opticalPullFollowsFullViewDirectionIncludingVerticalGazeAndStopsAtTheFloor() {
        val meters = .25
        for (pitch in listOf(-PI / 2, -PI / 4, PI / 4, PI / 2)) {
            val yaw = PI / 3
            val head = facing(yaw, pitch, roll = PI / 6)
            val forward = Vec3(-sin(yaw) * cos(pitch), sin(pitch), -cos(yaw) * cos(pitch))
            assertVector(forward * meters, VrTrackingMath.pullMovement(head, meters))
            assertVector(Vec3(-sin(yaw), 0.0, -cos(yaw)) * meters,
                VrTrackingMath.locomotion(facing(yaw, pitch), 0.0, meters))
        }
        val up = facing(0.0, PI / 2)
        val down = facing(0.0, -PI / 2)
        val game = FourthwardGame()
        game.updateTrackedHead(up.position)
        assertTrue(game.move(VrTrackingMath.pullMovement(up, meters)).changed)
        assertEquals(meters, game.playerFootWorld().z, 1e-7)
        assertTrue(game.move(VrTrackingMath.pullMovement(down, 2 * meters)).blocked)
        assertEquals(0.0, game.playerFootWorld().z, 1e-7)
    }

    @Test fun headTranslationIsUnboundedAndQuaternionIsXyzwWithoutChangingTheGameAngle() {
        val pose = VrTrackingMath.head(floatArrayOf(4.3f, 1.7f, -5.2f, 0f, 0f, 0f, 1f))!!
        assertVector(Vec3(4.3, 1.7, -5.2), pose.position, 3e-7)
        assertVector(Vec3(1.0, 0.0, 0.0), pose.right)
        assertVector(Vec3(0.0, 1.0, 0.0), pose.up)
        assertVector(Vec3(0.0, 0.0, 1.0), pose.normal)
        val game = FourthwardGame()
        game.updateTrackedHead(pose.position)
        val moved = VrTrackingMath.head(floatArrayOf(4.8f, 1.5f, -5.5f, 0f, sin(PI / 4).toFloat(), 0f, cos(PI / 4).toFloat()))!!
        game.updateTrackedHead(moved.position)
        assertEquals(0.0, game.rotationDegrees, 0.0)
        assertEquals(0.5, game.playerFootWorld().x, 3e-7)
        assertEquals(-0.3, game.playerFootWorld().w, 3e-7)
        assertEquals(1.5, game.playerWorld().z, 1e-8)
        assertVector(Vec3(0.0, 0.0, -1.0), VrTrackingMath.horizontalRight(moved)!!)
    }

    @Test fun movingBoxStartsAheadAfterAHeadTurnAndWalkInThePreviousScene() {
        val boxLevel = MotionStudyLevels.all().single { it.motionTopic == MotionTopic.MOVING_BOX }
        val game = FourthwardGame(listOf(boxLevel))
        val head = facing(PI / 2).copy(position = Vec3(1.6, 1.7, -2.4))
        val space = VrTrackingMath.levelSpace(head)
        game.restartLevel(space.localPosition(head.position))
        assertEquals(boxLevel.start, game.playerFootWorld())
        assertVector(Vec3(0.0, 0.0, -1.0), space.localVector(head.normal * -1.0))
        val exhibit = game.frame.local(Vec4(0.0, 0.0, MotionStudies.displayCenter.y, MotionStudies.displayCenter.z))
        val model = space.worldModel
        val rawExhibit = Vec3(
            model[0] * exhibit.x + model[8] * exhibit.z + model[12],
            exhibit.y, model[2] * exhibit.x + model[10] * exhibit.z + model[14],
        )
        assertVector(Vec3(-.8, 1.3, -2.4), rawExhibit, 2e-7)
        val walk = VrTrackingMath.locomotion(head, 0.0, .2)
        game.updateTrackedHead(space.localPosition(head.position + walk))
        assertEquals(-.2, game.playerFootWorld().w, 1e-8)
        val respawn = VrTrackingMath.levelSpace(facing(PI).copy(position = head.position + walk), game.trackedHead)
        assertVector(game.trackedHead, respawn.localPosition(head.position + walk))
    }

    @Test fun nonUnitQuaternionNormalizesAndInvalidHeadDataIsRejected() {
        val pose = VrTrackingMath.head(floatArrayOf(0f, 1.6f, 0f, 0f, 0f, 0f, 3f))!!
        assertEquals(1.0, pose.right.length(), 1e-9)
        assertNull(VrTrackingMath.head(null))
        assertNull(VrTrackingMath.head(FloatArray(6)))
        assertNull(VrTrackingMath.head(FloatArray(7)))
        assertNull(VrTrackingMath.head(floatArrayOf(Float.NaN, 0f, 0f, 0f, 0f, 0f, 1f)))
    }

    private fun joints(mirror: Boolean = false): FloatArray {
        val values = FloatArray(78)
        fun point(index: Int, x: Double, y: Double, z: Double = 0.0) {
            values[index * 3] = (if (mirror) -x else x).toFloat()
            values[index * 3 + 1] = y.toFloat(); values[index * 3 + 2] = z.toFloat()
        }
        point(0, 0.0, 0.032); point(1, 0.0, 0.0)
        point(2, -0.025, 0.014); point(3, -0.047, 0.030); point(4, -0.063, 0.045); point(5, -0.078, 0.057)
        point(6, -0.02, 0.03); point(7, -0.026, 0.065); point(8, -0.029, 0.095); point(9, -0.031, 0.113); point(10, -0.033, 0.130)
        point(11, -0.005, 0.035); point(12, -0.006, 0.070); point(13, -0.006, 0.105); point(14, -0.006, 0.128); point(15, -0.006, 0.145)
        point(16, 0.01, 0.03); point(17, 0.014, 0.065); point(18, 0.016, 0.095); point(19, 0.018, 0.116); point(20, 0.019, 0.132)
        point(21, 0.02, 0.025); point(22, 0.032, 0.052); point(23, 0.038, 0.078); point(24, 0.041, 0.094); point(25, 0.044, 0.106)
        return values
    }

    @Test fun openXrJointIndicesAndChiralityMapBothRealHands() {
        val right = VrTrackingMath.hand(joints(), false)!!
        val left = VrTrackingMath.hand(joints(true), true)!!
        assertVector(Vec3(-0.026, 0.065, 0.0), right.index.base)
        assertVector(Vec3(-0.033, 0.130, 0.0), right.index.tip)
        assertVector(Vec3(0.044, 0.106, 0.0), right.little.tip)
        // Upright fingers and right thumb on the wearer's left expose the back to the wearer.
        assertVector(Vec3(0.0, 0.0, -1.0), right.palmNormal!!)
        assertVector(Vec3(0.0, 0.0, -1.0), left.palmNormal!!)
        for (hand in listOf(right, left)) {
            val state = HandPanelGesture().update(hand, Vec3(0.0, 0.03, 0.6), 0.05)
            assertTrue(state.validTracking && state.isOpen)
            assertFalse(state.isFacingHead)
        }
        assertNull(VrTrackingMath.hand(null, false))
        assertNull(VrTrackingMath.hand(FloatArray(77), false))
        assertNull(VrTrackingMath.hand(FloatArray(78), false))
        val invalid = joints(); invalid[10 * 3] = Float.NaN
        assertNull(VrTrackingMath.hand(invalid, false))
    }

    @Test fun missingDistalOrMetacarpalPositionsStillProvideALiveFingerButMissingTipsCancelInput() {
        val sample = joints()
        for (joint in listOf(4, 6, 9, 11, 14, 16, 19, 21, 24)) sample.fill(Float.NaN, joint * 3, joint * 3 + 3)
        val hand = checkNotNull(VrTrackingMath.hand(sample, false))
        assertNull(hand.index.distal)
        assertVector(Vec3(-.033, .130, 0.0), hand.index.tip)
        for (joint in listOf(15, 18, 25)) sample.fill(Float.NaN, joint * 3, joint * 3 + 3)
        val partial = checkNotNull(VrTrackingMath.hand(sample, false))
        val contact = HandContactGesture()
        assertTrue(checkNotNull(contact.update(partial, .02)).indexExtended)
        val pinched = partial.copy(thumb = partial.thumb.copy(tip = partial.index.tip + Vec3(.020, 0.0, 0.0)))
        assertTrue(checkNotNull(contact.update(pinched, .02)).pinchStarted)
        assertFalse(HandPanelGesture().update(partial, Vec3(0.0, 0.0, .5), .02).validTracking)
        for (tip in listOf(5, 10)) {
            val lost = sample.clone().apply { fill(Float.NaN, tip * 3, tip * 3 + 3) }
            assertNull(VrTrackingMath.hand(lost, false))
        }
    }

    @Test fun palmJointOrientationDefinesTheAnatomicalPalmForBothHands() {
        val half = sin(PI / 4).toFloat()
        // +90 degrees about X puts local -Z along the fingers (+Y) and local -Y toward -Z.
        val orientation = floatArrayOf(half, 0f, 0f, half)
        for (left in listOf(false, true)) {
            val positions = joints(mirror = left)
            val positional = VrTrackingMath.hand(positions, isLeft = left)!!
            val tracked = VrTrackingMath.hand(positions + orientation, isLeft = left)!!
            assertVector(Vec3(0.0, 0.0, -1.0), positional.palmNormal!!)
            assertVector(Vec3(0.0, 0.0, -1.0), tracked.palmNormal!!)
            assertEquals(positional.copy(palmNormal = tracked.palmNormal), tracked)
        }
    }

    @Test fun absentOrInconsistentPalmRotationRetainsValidJointPositionsAndTheirFallbackNormal() {
        val positions = joints()
        val positional = VrTrackingMath.hand(positions, false)!!
        for (invalid in listOf(
            floatArrayOf(), FloatArray(3), FloatArray(4), floatArrayOf(Float.NaN, 0f, 0f, 1f),
            floatArrayOf(0f, 0f, 0f, 3f), floatArrayOf(0f, 0f, 0f, 1f), // -Z misses the fingers
            floatArrayOf(.5f, .5f, -.5f, .5f), // -Z agrees, but -Y points along the palm plane
            floatArrayOf(0f, .70710677f, -.70710677f, 0f), // -Z agrees, but the palm is reversed
        )) {
            assertEquals(positional, VrTrackingMath.hand(positions + invalid, false))
        }
    }

    @Test fun questCapturedLeftHandAndItsRightMirrorAgreeWithTheRecordedMetacarpalAxes() {
        // Meta IWER relaxed.ts, f05f2e4: XYZ translations and the middle metacarpal's
        // orientation, recorded on Quest 3. IWER mirrors the left reference across X for the right.
        val recordedPalm = Vec3(-.000535, -.864864, -.502006) // minus matrix column Y
        for (left in listOf(true, false)) {
            val mirror = if (left) 1 else -1
            val positions = FloatArray(78)
            fun put(joint: Int, x: Float, y: Float, z: Float) {
                floatArrayOf(x * mirror, y, z).copyInto(positions, joint * 3)
            }
            put(1, -.049136f, .002146f, .117020f)  // wrist
            put(7, -.000177f, .038900f, .039073f)  // index MCP
            put(11, -.036278f, .011580f, .085501f) // middle metacarpal base
            put(12, -.021260f, .045873f, .036592f) // middle MCP
            put(22, -.061502f, .032741f, .037051f) // little MCP
            val expected = recordedPalm.copy(x = recordedPalm.x * mirror)
            val fromPositions = VrTrackingMath.hand(positions, left)!!.palmNormal!!
            assertTrue(fromPositions.dot(expected) > .98)
            val rotation = floatArrayOf(.257402f, -.133562f * mirror, -.036228f * mirror, .956344f)
            val fromPose = VrTrackingMath.hand(positions + rotation, left)!!.palmNormal!!
            assertVector(expected, fromPose, .000003)
        }
    }

    @Test fun bridgeSynthesizedPalmIsRestoredToTheMiddleMetacarpalWhileNativeCentersArePreserved() {
        val positions = joints()
        fun joint(index: Int) = Vec3(positions[index * 3].toDouble(), positions[index * 3 + 1].toDouble(),
            positions[index * 3 + 2].toDouble())
        fun withPalm(p: Vec3) = positions.clone().also {
            floatArrayOf(p.x.toFloat(), p.y.toFloat(), p.z.toFloat()).copyInto(it)
        }
        val wrist = joint(1)
        val metacarpal = joint(11)
        val mcp = joint(12)
        val center = (metacarpal + mcp) * .5
        val approximated = (wrist + metacarpal) * .5
        assertVector(center, VrTrackingMath.hand(withPalm(approximated), false)!!.palm)
        val nativeCenter = center + Vec3(.001, .002, .004)
        assertVector(nativeCenter, VrTrackingMath.hand(withPalm(nativeCenter), false)!!.palm)
    }

    @Test fun billboardPanelUsesSameMetricCoordinatesAsPhysicalPoke() {
        val head = VrTrackingMath.head(floatArrayOf(0.4f, 1.6f, 0.6f, 0f, 0f, 0f, 1f))!!
        val panel = VrTrackingMath.panelPose(Vec3(0.25, 1.3, 0.1), head)!!
        assertEquals(0.0, panel.right.dot(panel.up), 1e-9)
        assertEquals(0.0, panel.up.dot(panel.normal), 1e-9)
        assertVector(panel.normal, panel.right.cross(panel.up))
        val button = PalmPanelLayout.buttons.first()
        val x = (button.left + button.right) / 2
        val y = (button.top + button.bottom) / 2
        fun tip(depth: Double) = panel.position + panel.right * ((x - 0.5) * PalmPanelLayout.WIDTH_METERS) +
            panel.up * ((0.5 - y) * PalmPanelLayout.HEIGHT_METERS) + panel.normal * depth
        val approach = VrTrackingMath.pointer(tip(0.04), panel)
        val contact = VrTrackingMath.pointer(tip(0.004), panel)
        assertEquals(x, contact.x, 1e-9); assertEquals(y, contact.y, 1e-9)
        assertEquals(0.004, contact.depthMeters, 1e-9)
        val poke = PalmPoke()
        assertNull(poke.update(approach.x, approach.y, approach.depthMeters, true, 0.02).action)
        assertEquals(PalmAction.RESET_ANGLE, poke.update(contact.x, contact.y, contact.depthMeters, true, 0.02).action)
        assertNull(poke.update(contact.x, contact.y, contact.depthMeters, true, 0.02).action)
    }

    @Test fun lookingStraightUpKeepsAStableHorizontalAxisWithoutManufacturedTheta() {
        val pose = VrTrackingMath.head(floatArrayOf(0f, 1.6f, 0f, sin(PI / 4).toFloat(), 0f, 0f, cos(PI / 4).toFloat()))!!
        assertVector(Vec3(1.0, 0.0, 0.0), VrTrackingMath.horizontalRight(pose)!!)
    }

    @Test fun everyMenuTargetMatchesItsRenderedMetricSizeIncludingTheScaledLessonDock() {
        val head = VrTrackingMath.head(floatArrayOf(0.4f, 1.6f, 0.6f, 0f, 0f, 0f, 1f))!!
        val panel = VrTrackingMath.panelPose(Vec3(0.25, 1.3, 0.1), head)!!
        for (layout in listOf(PalmPanelLayout.game, PalmPanelLayout.levels, PalmPanelLayout.lessonMenu)) {
            for (scale in listOf(1.0, 1.6)) for (button in layout.buttons) {
                val x = (button.left + button.right) / 2
                val y = (button.top + button.bottom) / 2
                fun hit(depth: Double): PalmPointerPosition {
                    val tip = panel.position + panel.right * ((x - .5) * layout.widthMeters * scale) +
                        panel.up * ((.5 - y) * layout.heightMeters * scale) + panel.normal * depth
                    return VrTrackingMath.pointer(tip, panel, layout.widthMeters * scale, layout.heightMeters * scale)
                }
                val approach = hit(.04)
                val contact = hit(.004)
                assertEquals(x, contact.x, 1e-9)
                assertEquals(y, contact.y, 1e-9)
                assertEquals(.004, contact.depthMeters, 1e-9)
                val poke = PalmPoke()
                poke.update(approach.x, approach.y, approach.depthMeters, true, .02, layout)
                assertEquals(button.action, poke.update(contact.x, contact.y, contact.depthMeters, true, .02, layout).action)
            }
        }
    }
}
