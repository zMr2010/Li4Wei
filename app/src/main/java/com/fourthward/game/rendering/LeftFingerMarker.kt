package com.fourthward.game.rendering

import android.os.Looper
import com.fourthward.game.core.Vec3
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Vector3
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * A native six-millimetre sphere at the real left INDEX_TIP, in the parent's local tracking
 * coordinates. No projection, predicted pointer, or panel-relative placement is applied.
 * Call update(null) whenever palm controls are hidden or the joint sample is invalid/stale.
 * All public methods are called on the main thread.
 */
class LeftFingerMarker {
    private val prepareLock = Mutex()
    private var owner: Entity? = null
    private var entity: ModelEntity? = null
    private var mesh: MeshResource? = null
    private var material: UnlitMaterial? = null
    private var visible = false
    private var contact = false
    private var closed = false

    suspend fun prepare(parent: Entity) {
        checkMainThread()
        prepareLock.withLock {
            check(!closed) { "LeftFingerMarker is closed" }
            if (entity != null) {
                check(owner === parent) { "Marker is already attached to another parent" }
                return@withLock
            }
            var preparedMesh: MeshResource? = null
            var preparedMaterial: UnlitMaterial? = null
            var preparedEntity: ModelEntity? = null
            var committed = false
            try {
                withContext(Dispatchers.IO) {
                    val shape = MeshResource.createSphere(0.006f)
                    preparedMesh = shape
                    shape.toGlobal()
                    currentCoroutineContext().ensureActive()
                    val surface = UnlitMaterial.create()
                    preparedMaterial = surface
                    surface.toGlobal()
                    surface.setApplyToneMapping(false)
                    surface.setBaseColor(SIGNAL)
                }
                currentCoroutineContext().ensureActive()
                check(!closed) { "LeftFingerMarker closed during preparation" }
                val marker = ModelEntity(checkNotNull(preparedMesh), checkNotNull(preparedMaterial))
                preparedEntity = marker
                marker.setName("LeftIndexTipMarker")
                marker.enabled = false
                check(parent.addChild(marker)) { "Could not attach left fingertip marker" }
                owner = parent
                entity = marker
                mesh = preparedMesh
                material = preparedMaterial
                committed = true
            } finally {
                if (!committed) {
                    preparedEntity?.let { parent.removeChild(it); it.destroy() }
                    try {
                        preparedMaterial?.close()
                    } finally {
                        preparedMesh?.close()
                    }
                }
            }
        }
    }

    /** Null/nonfinite/unrepresentable poses hide the marker instead of synthesizing a location. */
    fun update(position: Vec3?, touching: Boolean = false) {
        checkMainThread()
        val marker = entity ?: return
        if (closed) return
        val valid = position != null && position.x.toFloat().isFinite() &&
            position.y.toFloat().isFinite() && position.z.toFloat().isFinite()
        if (!valid) {
            if (visible) marker.enabled = false
            visible = false
            return
        }
        val point = checkNotNull(position)
        val transform = checkNotNull(marker.components[TransformComponent::class.java])
        transform.position = Vector3(point.x.toFloat(), point.y.toFloat(), point.z.toFloat())
        if (contact != touching) {
            contact = touching
            transform.scaleVector = Vector3(if (touching) 1.25f else 1f)
            material?.setBaseColor(if (touching) PAPER else SIGNAL)
        }
        if (!visible) marker.enabled = true
        visible = true
    }

    /** Cancel/await preparation before disposing the parent scene. */
    fun close() {
        checkMainThread()
        if (closed) return
        closed = true
        entity?.let { owner?.removeChild(it); it.destroy() }
        entity = null
        owner = null
        visible = false
        try {
            material?.close()
        } finally {
            material = null
            mesh?.close()
            mesh = null
        }
    }

    private fun checkMainThread() = check(Looper.myLooper() == Looper.getMainLooper()) {
        "Left fingertip Entity operations must run on the main thread"
    }

    private companion object {
        // Endfield sRGB #FFFA00 / #F2F2F0 converted to linear color space.
        val SIGNAL = Color4(1f, 0.955973f, 0f, 1f)
        val PAPER = Color4(0.887923f, 0.887923f, 0.871367f, 1f)
    }
}
