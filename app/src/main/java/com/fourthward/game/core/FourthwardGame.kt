package com.fourthward.game.core

import kotlin.math.*

data class World4(val boxes: List<Box4>, val spheres: List<Sphere4>)
data class MotionResult(val changed: Boolean, val blocked: Boolean, val completed: Boolean)

/** Player-centered affine sections with one angle and planar, collision-checked locomotion. */
class FourthwardGame(
    private val levels: List<TutorialLevel> = TutorialLevels.all(),
    val progress: LevelProgress = LevelProgress.forLevels(levels),
    private val onProgressChanged: (LevelProgress) -> Unit = {},
) {
    var levelIndex: Int = 0; private set
    val level: TutorialLevel get() = levels[levelIndex]
    val totalLevels: Int get() = levels.size
    private var animatedWorld: World4? = null
    val world: World4 get() = animatedWorld ?: level.world
    private val catalog by lazy { LevelCatalog(levels) }
    val nextLevelIndex: Int? get() = catalog.nextWithinChapter(levelIndex)
    var motionTimeSeconds: Double = 0.0; private set
    var motionBlocked: Boolean = false; private set
    private var motionObservedSeconds = 0.0
    private var motionWasVisible = false
    private var motionVisitedMiddle = false
    val motionObservationComplete: Boolean get() = level.motionTopic != null && observationStep >= 3
    val locomotionLocked: Boolean get() = level.geometryTopic != null
    var frame: SliceFrame = SliceFrame(); private set
    val rotationDegrees: Double get() = Math.toDegrees(frame.angleRadians)
    var trackedHead: Vec3 = Vec3(0.0, 1.65, 0.0); private set
    var completed: Boolean = false; private set
    var lastMotionBlocked: Boolean = false; private set
    var physicalContact: Boolean = false; private set
    var observationStep: Int = 0; private set
    val observationCount: Int get() = if (level.motionTopic != null) 3 else
        level.geometryTopic?.let { GeometryExhibits.observations(it).size } ?: 0
    val observationsComplete: Boolean get() = observationStep >= observationCount
    val observationInstruction: String? get() = if (level.motionTopic != null) when (observationStep) {
        0 -> "观察物体进入截面时，最先出现的部分。"
        1 -> "继续观察，比较截面的大小、位置和形状。"
        2 -> "看看物体离开时的变化，与刚才的出现过程作比较。"
        else -> "这一轮运动观察完成，可以继续走动观察。"
    } else level.geometryTopic?.let {
        if (observationsComplete) "三个方向观察完成，已通过本关。"
        else GeometryExhibits.observations(it)[observationStep].instruction
    }
    val observationHint: String? get() = if (level.motionTopic != null) {
        if (observationsComplete) "按 N 继续，或按 H 回看并列演示。"
        else if (abs(sin(frame.angleRadians)) > .04) "用「θ归零」回到起初的切片方向，再比较这轮穿行。"
        else if (abs(frame.signedDistance(Vec4.ZERO)) > .12) "从起点重新观察，可以再看一次物体完整穿过的过程。"
        else level.hint
    } else level.geometryTopic?.let {
        when {
            observationsComplete -> if (nextLevelIndex != null) "按 N 进入下一关，或按 T 重新观察本关。"
                else "本章已完成。按 N 返回章节地图，或按 T 重新观察。"
            else -> GeometryExhibits.observations(it)[observationStep].hint
        }
    }
    private var observationHoldSeconds = 0.0
    private val checkpointIds = linkedSetOf<String>()
    val completedCheckpointIds: Set<String> get() = checkpointIds.toSet()
    val hasCheckpoint: Boolean get() = checkpointPose != null
    val checkpointTitle: String? get() = checkpointPose?.title
    val goalReady: Boolean get() = level.expedition?.checkpoints?.all { it.id in checkpointIds } ?: true
    var hintTier: Int = 0; private set
    var missionRevision: Int = 0; private set
    val missionInstruction: String? get() = currentMissionStage()?.instruction
    val missionHint: String? get() = if (hintTier == 0) null else currentMissionStage()?.hints?.get(hintTier - 1)
    private data class CheckpointPose(val title: String, val feet: Vec4, val angle: Double, val motionTime: Double)
    private var checkpointPose: CheckpointPose? = null
    val playerRadius = 0.22
    val playerHeight = 1.7
    private var hasTrackingSample = false
    private var observationEyeHeight = trackedHead.y

    init {
        require(levels.isNotEmpty())
        restartLevel()
    }

    /** The virtual head point. Geometry studies preserve its entry height and spawn position. */
    fun playerWorld(localHead: Vec3 = trackedHead): Vec4 = frame.world(localHead)
    fun playerFootWorld(): Vec4 = if (locomotionLocked) level.start else frame.world(feet(trackedHead))
    fun sections(): List<BoxSection> = world.boxes.mapNotNull { SliceGeometry.sliceBox(it, frame) }
    fun visibleSpheres(): List<SphereSection> = world.spheres
        .filter { (level.geometryTopic == null && level.motionTopic == null) || it.id != level.goal.id }
        .mapNotNull { SliceGeometry.sliceSphere(it, frame) }

    /** Time moves 4D geometry, never the camera. Menus and focus loss freeze this clock. */
    fun tickWorld(dt: Double, active: Boolean = true): Boolean {
        if (!active || !dt.isFinite() || dt <= 0.0 || (level.motionTopic == null && level.motionPuzzle == null)) return false
        val duration = dt.coerceAtMost(.1)
        val steps = max(1, ceil(duration / .02).toInt())
        var changed = false
        motionBlocked = false
        repeat(steps) {
            if (!motionBlocked) {
                val nextTime = if (level.motionPuzzle != null)
                    MotionPuzzles.advanceTime(level, motionTimeSeconds, duration / steps, completedCheckpointIds)
                    else motionTimeSeconds + duration / steps
                val next = animatedWorldAt(nextTime)
                if (nextTime > motionTimeSeconds && next != null) {
                    if (movingSolidsTouchPlayer(world, next)) motionBlocked = true
                    else {
                        motionTimeSeconds = nextTime
                        animatedWorld = next
                        changed = true
                        if (level.motionTopic != null) observeMotion(duration / steps)
                        checkCompletion()
                    }
                }
            }
        }
        return changed
    }

    private fun animatedWorldAt(time: Double): World4? = when {
        level.motionTopic != null -> MotionStudies.world(level.motionTopic!!, time)
        level.motionPuzzle != null -> MotionPuzzles.world(level, time, completedCheckpointIds)
        else -> null
    }

    private fun observeMotion(dt: Double) {
        if (observationsComplete) return
        val phase = motionTimeSeconds % MotionStudies.passageSeconds / MotionStudies.passageSeconds
        // Free X/W walking and head motion preserve this plane. Changing the section is
        // allowed too, but cannot masquerade as the source object's taught motion.
        val referencePlane = abs(sin(frame.angleRadians)) <= .04 && abs(frame.signedDistance(Vec4.ZERO)) <= .12
        val small: Boolean
        val central: Boolean
        if (level.motionTopic == MotionTopic.MOVING_BOX) {
            val section = sections().firstOrNull { it.source.id == "motion-box" }
            small = section?.vertices?.size == 6
            central = section?.vertices?.size == 12
        } else {
            val section = visibleSpheres().firstOrNull { it.source.id == "motion-sphere" }
            val ratio = section?.let { it.radius / it.source.radius } ?: 0.0
            small = ratio in .12.. .85
            central = ratio >= .93
        }
        val matches = referencePlane && when (observationStep) {
            0 -> phase in .18.. .43 && small
            1 -> phase in .43.. .57 && central
            2 -> motionWasVisible && motionVisitedMiddle && phase in .57.. .84 && small
            else -> false
        }
        if (!matches) { motionObservedSeconds = 0.0; return }
        motionObservedSeconds += dt
        if (motionObservedSeconds + 1e-9 >= .25) {
            observationStep++
            if (observationStep == 1) motionWasVisible = true
            if (observationStep == 2) motionVisitedMiddle = true
            motionObservedSeconds = 0.0
        }
    }

    private fun movingSolidsTouchPlayer(before: World4, after: World4): Boolean {
        val old = before.boxes.associateBy { it.id }
        return after.boxes.filter { it.solid }.any { box ->
            val previous = old[box.id] ?: box
            val delta = box.center - previous.center
            if (delta.lengthSquared() < 1e-20) false else {
                // All moving solids translate with fixed size/basis. This enclosing sweep
                // is conservative even when the movement arrives from outside the slice.
                require(box.basis == previous.basis && box.halfExtents == previous.halfExtents)
                val swept = box.copy(center = (box.center + previous.center) * .5,
                    halfExtents = box.halfExtents + Vec4(abs(delta.dot(box.basis[0])) / 2,
                        abs(delta.dot(box.basis[1])) / 2, abs(delta.dot(box.basis[2])) / 2,
                        abs(delta.dot(box.basis[3])) / 2))
                SliceGeometry.sliceBox(swept, frame)?.let {
                    SliceGeometry.intersectsCapsule(it, feet(trackedHead), playerRadius, playerHeight)
                } ?: false
            }
        }
    }

    /** Observation advances only on deliberate active-frame holds, never rotation substeps. */
    fun tickObservation(dt: Double, active: Boolean = true): Boolean {
        val topic = level.geometryTopic ?: return false
        if (observationsComplete || completed) return false
        if (!active || !dt.isFinite() || dt <= 0.0 ||
            !GeometryExhibits.matches(topic, observationStep, world, frame, playerFootWorld(), level.start)) {
            observationHoldSeconds = 0.0
            return false
        }
        observationHoldSeconds += dt.coerceAtMost(.1)
        if (observationHoldSeconds + 1e-9 < .8) return false
        observationStep++
        observationHoldSeconds = 0.0
        checkCompletion()
        return true
    }

    fun rotate(deltaRadians: Double): Boolean {
        require(deltaRadians.isFinite())
        lastMotionBlocked = false
        val delta = deltaRadians % (2.0 * PI)
        if (abs(delta) <= 1e-12) return false
        if (locomotionLocked) {
            frame = fixedObservationFrame(frame.angleRadians + delta)
            checkCompletion()
            return true
        }
        val steps = max(1, ceil(abs(delta) / (PI / 180.0)).toInt())
        var changed = false
        repeat(steps) {
            if (!lastMotionBlocked) {
                val candidate = frame.rotated(delta / steps, trackedHead)
                if (canOccupy(candidate, feet(trackedHead))) {
                    frame = candidate
                    changed = true
                } else lastMotionBlocked = true
            }
        }
        checkCompletion()
        return changed
    }

    fun resetAngle() {
        val delta = if (frame.angleRadians > PI) 2.0 * PI - frame.angleRadians else -frame.angleRadians
        rotate(delta)
    }

    /** Native x/z are the slice's side/depth directions. Input y is ignored: no jump/fly axis. */
    fun movePlanar(deltaLocal: Vec3): MotionResult {
        require(deltaLocal.finite())
        lastMotionBlocked = false
        if (locomotionLocked) return MotionResult(false, false, completed)
        if (completed && level.motionTopic == null) return MotionResult(false, false, completed)
        val distance = hypot(deltaLocal.x, deltaLocal.z)
        if (distance <= 1e-12) return MotionResult(false, false, completed)
        val steps = max(1, ceil(distance / 0.04).toInt())
        val step = Vec3(deltaLocal.x / steps, 0.0, deltaLocal.z / steps)
        var changed = false
        for (index in 0 until steps) {
            val start = feet(trackedHead)
            if (sweepBlocked(frame, start, start + step)) { lastMotionBlocked = true; break }
            frame = frame.movedPlanar(step.x, step.z)
            changed = true
            checkCompletion()
            if (completed && level.motionTopic == null) break
        }
        return MotionResult(changed, lastMotionBlocked, completed)
    }

    /** Keep the actual XR pose, but offset the slice origin by any rejected walking distance.
     * Native rendering still uses the real stereo eye poses: translating the scene cancels
     * blocked head translation without changing head rotation, eye separation, or hand poses.
     * Every raw delta is consumed, so retreating from contact works immediately. */
    fun updateTrackedHead(localHead: Vec3): MotionResult {
        require(localHead.finite())
        if (locomotionLocked) {
            val firstSample = !hasTrackingSample
            val changed = firstSample || localHead != trackedHead
            if (firstSample) observationEyeHeight = localHead.y
            // Keep live tracking for the real eye/hand poses, while translating the entire
            // rendered slice to cancel all raw XYZ motion around the fixed observation point.
            trackedHead = localHead
            hasTrackingSample = true
            if (changed) frame = fixedObservationFrame(frame.angleRadians)
            physicalContact = false
            lastMotionBlocked = false
            checkCompletion()
            return MotionResult(changed, false, completed)
        }
        if (!hasTrackingSample) {
            val spawnFeet = playerFootWorld()
            trackedHead = localHead
            frame = SliceFrame(frame.angleRadians, spawnFeet - frame.right * localHead.x - frame.forward * localHead.z)
            hasTrackingSample = true
            physicalContact = !canOccupy(frame, feet(localHead))
            lastMotionBlocked = physicalContact
            checkCompletion()
            return MotionResult(true, physicalContact, completed)
        }
        val changed = (localHead - trackedHead).lengthSquared() > 1e-16
        val start = feet(trackedHead)
        val delta = feet(localHead) - start
        val fraction = safePhysicalFraction(start, delta)
        physicalContact = fraction < 1.0
        if (physicalContact) {
            // W(newHead) = W(oldHead) + basis * (fraction * rawDelta).
            val rejected = delta * (1.0 - fraction)
            frame = frame.movedPlanar(-rejected.x, -rejected.z)
        }
        trackedHead = localHead
        lastMotionBlocked = physicalContact
        checkCompletion()
        return MotionResult(changed, physicalContact, completed)
    }

    /** A lesson may have moved the user freely. Adopt its final raw pose directly instead
     * of treating that displacement as gameplay movement through the previous goal. */
    fun restartLevel(localHead: Vec3? = null) {
        if (localHead != null) {
            require(localHead.finite())
            trackedHead = localHead
            hasTrackingSample = true
        }
        observationEyeHeight = trackedHead.y
        frame = if (locomotionLocked) fixedObservationFrame(0.0) else
            SliceFrame(0.0, level.start - Vec4.X * trackedHead.x - Vec4.W * trackedHead.z)
        completed = false
        physicalContact = false
        lastMotionBlocked = false
        observationStep = 0
        observationHoldSeconds = 0.0
        motionTimeSeconds = 0.0
        motionBlocked = false
        motionObservedSeconds = 0.0
        motionWasVisible = false
        motionVisitedMiddle = false
        checkpointIds.clear()
        checkpointPose = null
        hintTier = 0
        missionRevision++
        animatedWorld = animatedWorldAt(0.0)
        checkCompletion()
    }

    /** Resume at the last physically reached landmark, rebased to the current raw XR pose. */
    fun retryAtCheckpoint(localHead: Vec3? = null): Boolean {
        val checkpoint = checkpointPose
        if (completed || level.expedition == null || checkpoint == null) {
            restartLevel(localHead)
            return false
        }
        if (localHead != null) {
            require(localHead.finite())
            trackedHead = localHead
            hasTrackingSample = true
        }
        val basis = SliceFrame(checkpoint.angle)
        frame = SliceFrame(basis.angleRadians, checkpoint.feet -
            basis.right * trackedHead.x - basis.forward * trackedHead.z)
        motionTimeSeconds = checkpoint.motionTime
        animatedWorld = animatedWorldAt(motionTimeSeconds)
        motionBlocked = false
        physicalContact = false
        lastMotionBlocked = false
        hintTier = 0
        missionRevision++
        checkCompletion()
        return true
    }

    /** Hints are revealed deliberately, one tier at a time for the current objective. */
    fun requestHint(): Boolean {
        val stage = currentMissionStage() ?: return false
        if (completed || hintTier >= stage.hints.size) return false
        hintTier++
        missionRevision++
        return true
    }

    fun nextLevel(): Boolean {
        if (!completed) return false
        return nextLevelIndex?.let { selectLevel(it) } ?: false
    }

    /** All entry points, including keyboard shortcuts, share the same unlock guard. */
    fun selectLevel(index: Int): Boolean {
        if (index !in levels.indices || !progress.isUnlocked(index)) return false
        levelIndex = index
        restartLevel()
        return true
    }

    private fun checkCompletion() {
        if (completed) return
        checkMissionCheckpoints()
        val objectiveComplete = if (level.geometryTopic != null || level.motionTopic != null) observationsComplete else
            goalReady && SliceGeometry.sliceSphere(level.goal, frame) != null &&
                (playerFootWorld() - level.goal.center).length() <= level.goal.radius + 1e-9
        if (objectiveComplete) {
            completed = true
            if (progress.recordCompletion(levelIndex)) onProgressChanged(progress)
        }
    }

    private fun currentMissionStage(): MissionStage? = level.expedition?.stages?.get(checkpointIds.size)

    private fun checkMissionCheckpoints() {
        val mission = level.expedition ?: return
        val pending = mission.checkpoints.filter { it.id !in checkpointIds }
        val eligible = if (mission.ordered) pending.take(1) else pending
        val feet = playerFootWorld()
        for (checkpoint in eligible) {
            if (SliceGeometry.sliceSphere(checkpoint.volume, frame) == null ||
                (feet - checkpoint.volume.center).length() > checkpoint.volume.radius + 1e-9) continue
            checkpointIds += checkpoint.id
            // Save the reached point rather than the landmark center: it is the point whose
            // movement/collision checks succeeded and does not pull the player through a wall.
            checkpointPose = CheckpointPose(checkpoint.title, feet, frame.angleRadians, motionTimeSeconds)
            hintTier = 0
            missionRevision++
        }
    }

    private fun fixedObservationFrame(angle: Double): SliceFrame {
        val basis = SliceFrame(angle)
        val fixedHead = level.start + Vec4.Z * observationEyeHeight
        return SliceFrame(basis.angleRadians, fixedHead - basis.right * trackedHead.x -
            basis.up * trackedHead.y - basis.forward * trackedHead.z)
    }

    private fun safePhysicalFraction(start: Vec3, delta: Vec3): Double {
        val sections = solidSections(frame)
        if (!sweepBlocked(sections, start, start + delta)) return 1.0
        val distance = delta.length()
        if (distance <= 1e-12) return 0.0
        // Sweeping each prefix also catches a single tracking sample that crosses a thin
        // wall completely. A small clearance avoids trapping subsequent retreat at contact.
        var safe = 0.0
        var blocked = 1.0
        repeat(28) {
            val middle = (safe + blocked) * 0.5
            if (sweepBlocked(sections, start, start + delta * middle)) blocked = middle
            else safe = middle
        }
        return (safe - 0.001 / distance).coerceAtLeast(0.0)
    }

    private fun solidSections(candidate: SliceFrame): List<BoxSection> = world.boxes.filter { it.solid }.mapNotNull { SliceGeometry.sliceBox(it, candidate) }
    private fun canOccupy(candidate: SliceFrame, feet: Vec3): Boolean = solidSections(candidate).none {
        SliceGeometry.intersectsCapsule(it, feet, playerRadius, playerHeight)
    }
    private fun sweepBlocked(candidate: SliceFrame, start: Vec3, end: Vec3): Boolean = sweepBlocked(solidSections(candidate), start, end)
    private fun sweepBlocked(sections: List<BoxSection>, start: Vec3, end: Vec3): Boolean = sections.any {
        SliceGeometry.intersectsSweptCapsule(it, start, end, playerRadius, playerHeight)
    }
    private fun feet(head: Vec3) = Vec3(head.x, 0.0, head.z)
    private fun Vec3.finite() = x.isFinite() && y.isFinite() && z.isFinite()
}
