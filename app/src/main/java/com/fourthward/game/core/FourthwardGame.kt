package com.fourthward.game.core

import kotlin.math.*

data class World4(val boxes: List<Box4>, val spheres: List<Sphere4>)
data class MotionResult(val changed: Boolean, val blocked: Boolean, val completed: Boolean)

/** Player-centered affine sections with one angle and collision-checked locomotion. */
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
    val nextLevelIndex: Int? get() = catalog.nextInRoute(levelIndex)
    val nextLevelUnlocked: Boolean get() = nextLevelIndex?.let(progress::isUnlocked) == true
    val tutorialRouteComplete: Boolean get() = catalog.tutorialIndices.isNotEmpty() &&
        catalog.tutorialIndices.all(progress::isCompleted)
    var motionTimeSeconds: Double = 0.0; private set
    var deathCount: Long = 0; private set
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
        if (observationsComplete) if (nextLevelUnlocked) "按 N 进入下一课，或按 H 回看并列演示。"
            else "按 N 查看关卡路线，或按 H 回看并列演示。"
        else if (abs(sin(frame.angleRadians - level.startAngleRadians)) > .04) "回到起初的切片方向，再比较这轮穿行。"
        else if (abs(frame.signedDistance(level.start)) > .12) "从起点重新观察，可以再看一次物体完整穿过的过程。"
        else level.hint
    } else level.geometryTopic?.let {
        when {
            observationsComplete -> if (nextLevelUnlocked) "按 N 进入下一课，或按 T 重新观察本关。"
                else "本课完成。按 N 返回关卡路线，或按 T 重新观察。"
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
    val playerHeadRadius = 0.12
    // Rejected raw vertical motion moves the rendered frame, but must not drag the feet
    // through the opposite wall. Feet counter this correction in local tracking coordinates.
    private var physicalVerticalCorrection = 0.0
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
        val step = duration / steps
        var changed = false
        repeat(steps) {
            val nextTime = if (level.motionPuzzle != null)
                MotionPuzzles.advanceTime(level, motionTimeSeconds, step, completedCheckpointIds)
                else motionTimeSeconds + step
            if (nextTime <= motionTimeSeconds) return changed
            val next = animatedWorldAt(nextTime) ?: return changed
            if (movingHazardsTouchPlayer(world, next)) {
                die()
                return true
            }
            motionTimeSeconds = nextTime
            animatedWorld = next
            changed = true
            if (level.motionTopic != null) observeMotion(step)
            checkCompletion()
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
        val referencePlane = abs(sin(frame.angleRadians - level.startAngleRadians)) <= .04 &&
            abs(frame.signedDistance(level.start)) <= .12
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

    private fun movingHazardsTouchPlayer(before: World4, after: World4): Boolean {
        val hazards = after.boxes.filter { MotionPuzzles.isHazard(level.motionPuzzle, it) }
        if (hazards.isEmpty()) return false
        val old = before.boxes.associateBy { it.id }
        return hazards.any { box ->
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
                    occupied(it, feet(trackedHead), trackedHead)
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
        val feet = feet(trackedHead)
        var changed = false
        repeat(steps) {
            val candidate = frame.rotated(delta / steps, trackedHead)
            val contacts = solidSections(candidate).filter { occupied(it, feet, trackedHead) }
            if (candidate.world(feet).z < -1e-9 || candidate.world(trackedHead).z < playerHeadRadius ||
                contacts.any { !it.isHazard() }) {
                lastMotionBlocked = true
                checkCompletion()
                return changed
            }
            if (contacts.isNotEmpty()) {
                die()
                return true
            }
            frame = candidate
            changed = true
        }
        checkCompletion()
        return changed
    }

    fun resetAngle() {
        val delta = if (frame.angleRadians > PI) 2.0 * PI - frame.angleRadians else -frame.angleRadians
        rotate(delta)
    }

    /** Compatibility path for ground-only callers; native x/z are slice side/depth. */
    fun movePlanar(deltaLocal: Vec3): MotionResult {
        require(deltaLocal.finite())
        return move(Vec3(deltaLocal.x, 0.0, deltaLocal.z))
    }

    /** Artificial movement within the current slice. Native y is the fixed world Z axis.
     * Translate the section origin rather than the raw XR pose so both eyes and hands retain
     * their physical relationship, and sweep the entire capsule on combined XYZ movement. */
    fun move(deltaLocal: Vec3): MotionResult {
        require(deltaLocal.finite())
        lastMotionBlocked = false
        if (locomotionLocked) return MotionResult(false, false, completed)
        if (completed && level.motionTopic == null) return MotionResult(false, false, completed)
        val distance = deltaLocal.length()
        if (distance <= 1e-12) return MotionResult(false, false, completed)
        val steps = max(1, ceil(distance / 0.04).toInt())
        val requestedStep = deltaLocal * (1.0 / steps)
        var changed = false
        for (index in 0 until steps) {
            val start = feet(trackedHead)
            // VrSceneBuilder draws the shared reference ground at world Z=0. Descending
            // stops there even in lessons without a separate solid floor box.
            val minimumY = max(-playerFootWorld().z, playerHeadRadius + .001 - playerWorld().z)
            val step = requestedStep.copy(y = max(requestedStep.y, minimumY.coerceAtMost(0.0)))
            if (step.y > requestedStep.y + 1e-12) lastMotionBlocked = true
            if (step.lengthSquared() <= 1e-24) break
            val (hazards, walls) = solidSections(frame).partition { it.isHazard() }
            if (sweepBlocked(walls, start, start + step) ||
                headSweepBlocked(walls, trackedHead, trackedHead + step)) {
                lastMotionBlocked = true; break
            }
            if (sweepBlocked(hazards, start, start + step) ||
                headSweepBlocked(hazards, trackedHead, trackedHead + step)) return die()
            frame = SliceFrame(frame.angleRadians,
                frame.origin + frame.right * step.x + frame.up * step.y + frame.forward * step.z)
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
            physicalVerticalCorrection = 0.0
            frame = SliceFrame(frame.angleRadians, spawnFeet - frame.right * localHead.x - frame.forward * localHead.z)
            hasTrackingSample = true
            physicalContact = !fitInitialEyeHeight()
            lastMotionBlocked = physicalContact
            checkCompletion()
            return MotionResult(true, physicalContact, completed)
        }
        val changed = (localHead - trackedHead).lengthSquared() > 1e-16
        val start = feet(trackedHead)
        val delta = localHead - trackedHead
        val horizontal = Vec3(delta.x, 0.0, delta.z)
        val (hazards, walls) = solidSections(frame).partition { it.isHazard() }
        // Keep the existing horizontal response: contact with a wall must still allow a
        // clear crouch/stand movement instead of scaling away all raw vertical motion.
        val bodyFraction = safePhysicalFraction(horizontal) { fraction ->
            sweepBlocked(walls, start, start + horizontal * fraction)
        }
        val headDelta = horizontal * bodyFraction + Vec3(0.0, delta.y, 0.0)
        val startHeight = bodyHeight(trackedHead)
        val headFraction = safePhysicalFraction(headDelta) { fraction ->
            val nextHead = trackedHead + headDelta * fraction
            frame.world(nextHead).z <= playerHeadRadius ||
                headSweepBlocked(walls, trackedHead, nextHead) || walls.any {
                SliceGeometry.intersectsSweptCapsule(it, start, start + horizontal * (bodyFraction * fraction),
                    playerRadius, startHeight, bodyHeight(nextHead))
            }
        }
        val accepted = headDelta * headFraction
        val endHead = trackedHead + accepted
        val endFeet = start + accepted.copy(y = 0.0)
        if (headSweepBlocked(hazards, trackedHead, endHead) || hazards.any {
                SliceGeometry.intersectsSweptCapsule(it, start, endFeet, playerRadius, startHeight, bodyHeight(endHead))
            }) return die(localHead)
        physicalContact = bodyFraction < 1.0 || headFraction < 1.0
        if (physicalContact) {
            val rejected = delta - accepted
            frame = SliceFrame(frame.angleRadians, frame.origin - frame.right * rejected.x -
                frame.up * rejected.y - frame.forward * rejected.z)
            physicalVerticalCorrection -= rejected.y
        }
        trackedHead = localHead
        lastMotionBlocked = physicalContact
        checkCompletion()
        return MotionResult(changed, physicalContact, completed)
    }

    private fun die(localHead: Vec3? = null): MotionResult {
        deathCount++
        restartLevel(localHead)
        return MotionResult(true, false, completed)
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
        physicalVerticalCorrection = 0.0
        val startBasis = SliceFrame(level.startAngleRadians)
        frame = if (locomotionLocked) fixedObservationFrame(startBasis.angleRadians) else
            SliceFrame(startBasis.angleRadians, level.start - startBasis.right * trackedHead.x - startBasis.forward * trackedHead.z)
        completed = false
        physicalContact = false
        lastMotionBlocked = false
        observationStep = 0
        observationHoldSeconds = 0.0
        motionTimeSeconds = 0.0
        motionObservedSeconds = 0.0
        motionWasVisible = false
        motionVisitedMiddle = false
        checkpointIds.clear()
        checkpointPose = null
        hintTier = 0
        missionRevision++
        animatedWorld = animatedWorldAt(0.0)
        if (!locomotionLocked) {
            physicalContact = !fitInitialEyeHeight()
            lastMotionBlocked = physicalContact
        }
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
        physicalVerticalCorrection = 0.0
        frame = SliceFrame(basis.angleRadians, checkpoint.feet -
            basis.right * trackedHead.x - basis.forward * trackedHead.z)
        motionTimeSeconds = checkpoint.motionTime
        animatedWorld = animatedWorldAt(motionTimeSeconds)
        physicalContact = !fitInitialEyeHeight()
        lastMotionBlocked = physicalContact
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

    private fun safePhysicalFraction(delta: Vec3, blockedAt: (Double) -> Boolean): Double {
        if (!blockedAt(1.0)) return 1.0
        val distance = delta.length()
        if (distance <= 1e-12) return 0.0
        // Sweeping each prefix also catches a single tracking sample that crosses a thin
        // wall completely. A small clearance avoids trapping subsequent retreat at contact.
        var safe = 0.0
        var blocked = 1.0
        repeat(28) {
            val middle = (safe + blocked) * 0.5
            if (blockedAt(middle)) blocked = middle
            else safe = middle
        }
        return (safe - 0.001 / distance).coerceAtLeast(0.0)
    }

    private fun solidSections(candidate: SliceFrame): List<BoxSection> = world.boxes.filter { it.solid }.mapNotNull { SliceGeometry.sliceBox(it, candidate) }
    private fun BoxSection.isHazard(): Boolean = MotionPuzzles.isHazard(level.motionPuzzle, source)
    private fun sweepBlocked(sections: List<BoxSection>, start: Vec3, end: Vec3): Boolean = sections.any {
        SliceGeometry.intersectsSweptCapsule(it, start, end, playerRadius, bodyHeight(trackedHead))
    }
    private fun headSweepBlocked(sections: List<BoxSection>, start: Vec3, end: Vec3) = sections.any {
        SliceGeometry.intersectsSweptCapsule(it, sphereFeet(start), sphereFeet(end), playerHeadRadius, 2.0 * playerHeadRadius)
    }
    private fun occupied(section: BoxSection, feet: Vec3, head: Vec3): Boolean =
        SliceGeometry.intersectsCapsule(section, feet, playerRadius, bodyHeight(head)) ||
            SliceGeometry.intersectsCapsule(section, sphereFeet(head), playerHeadRadius, 2.0 * playerHeadRadius)
    private fun sphereFeet(head: Vec3) = head - Vec3(0.0, playerHeadRadius, 0.0)
    private fun bodyHeight(head: Vec3) = max(playerHeight, head.y + physicalVerticalCorrection + playerHeadRadius)
    private fun feet(head: Vec3) = Vec3(head.x, -physicalVerticalCorrection, head.z)

    /** A simulator pose can start above a roof or below the visible Z=0 ground. Fit only its
     * effective eye height to safety; neither the actual pose nor the spawn feet move. */
    private fun fitInitialEyeHeight(): Boolean {
        val sections = solidSections(frame)
        val base = feet(trackedHead)
        val feetZ = frame.world(base).z
        if (feetZ < -1e-9) return false
        val currentHeight = trackedHead.y + physicalVerticalCorrection
        val groundHeight = playerHeadRadius + .001 - feetZ
        val minimum = max(groundHeight, min(currentHeight, playerHeight - playerHeadRadius))
        fun clear(height: Double): Boolean {
            val head = trackedHead + Vec3(0.0, height - currentHeight, 0.0)
            return feetZ + height >= playerHeadRadius && sections.none { occupied(it, base, head) }
        }
        if (clear(currentHeight)) return true
        if (!clear(minimum)) return false
        if (minimum > currentHeight) {
            val correction = minimum - currentHeight
            physicalVerticalCorrection += correction
            frame = SliceFrame(frame.angleRadians, frame.origin + frame.up * correction)
            return true
        }
        if (minimum == currentHeight) return false
        var safe = minimum
        var blocked = currentHeight
        repeat(28) {
            val middle = (safe + blocked) * .5
            if (clear(middle)) safe = middle else blocked = middle
        }
        val correction = (safe - .001).coerceAtLeast(minimum) - currentHeight
        physicalVerticalCorrection += correction
        frame = SliceFrame(frame.angleRadians, frame.origin + frame.up * correction)
        return true
    }
    private fun Vec3.finite() = x.isFinite() && y.isFinite() && z.isFinite()
}
