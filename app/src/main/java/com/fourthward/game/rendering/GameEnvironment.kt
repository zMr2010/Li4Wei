package com.fourthward.game.rendering

import android.util.Log
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.resource.MaterialCullingMode
import com.pico.spatial.core.ecs.resource.MeshModel
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Vector3
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * The Stage's app-owned, opaque virtual environment in all directions.
 *
 * This is an inward-facing sky sphere fixed at the Stage origin, not a camera attachment or
 * window. Its ordinary depth test preserves stereoscopic parallax and foreground geometry.
 * A Full Stage is unbounded; the sky supplies the scenery outside the finite puzzle meshes.
 */
internal class GameEnvironment {
    val root = Entity().apply { setName("FourthwardEnvironment") }
    private var mesh: MeshResource? = null
    private var material: UnlitMaterial? = null
    private var closed = false

    suspend fun prepare() {
        check(!closed)
        if (mesh != null) return
        var preparedMesh: MeshResource? = null
        var preparedMaterial: UnlitMaterial? = null
        var candidate: ModelEntity? = null
        var committed = false
        try {
            withContext(Dispatchers.IO) {
                // Keep ownership outside withContext: cancellation at its return must still
                // dispose resources that have already been created on the rendering backend.
                preparedMesh = MeshResource.createWithMeshModel(skyMesh(), name = "FourthwardSky")
                preparedMesh!!.toGlobal()
                preparedMaterial = UnlitMaterial.create()
                preparedMaterial!!.toGlobal()
                preparedMaterial!!.apply {
                    setBaseColor(Color4.WHITE)
                    setCullingMode(MaterialCullingMode.FRONT)
                    setApplyToneMapping(false)
                    setDepthTest(true)
                    setDepthWrite(false)
                }
            }
            check(!closed)
            candidate = ModelEntity(requireNotNull(preparedMesh), requireNotNull(preparedMaterial))
            candidate.setName("FourthwardSky360")
            root.addChild(candidate)
            mesh = preparedMesh
            material = preparedMaterial
            committed = true
            Log.i("FourthwardEnvironment", "World-fixed opaque 360-degree environment ready")
        } finally {
            if (!committed) {
                candidate?.destroy()
                preparedMaterial?.close()
                preparedMesh?.close()
            }
        }
    }

    fun close() {
        if (closed) return
        closed = true
        root.destroy()
        material?.close()
        material = null
        mesh?.close()
        mesh = null
    }
}

private fun skyMesh(): MeshModel {
    val rings = 24
    val sectors = 48
    val radius = 64f
    val positions = ArrayList<Vector3>((rings + 1) * (sectors + 1))
    val normals = ArrayList<Vector3>((rings + 1) * (sectors + 1))
    val colors = ArrayList<Color4>((rings + 1) * (sectors + 1))
    val indices = ArrayList<Int>(rings * sectors * 6)
    for (ring in 0..rings) {
        val latitude = PI * ring / rings
        val y = cos(latitude).toFloat()
        // A subdued horizon gives the surrounding world a readable vertical orientation.
        // Convert authored sRGB colors to the native material's linear color space.
        val horizon = (1f - abs(y)).pow(5f)
        val pole = if (y >= 0f) intArrayOf(11, 16, 20) else intArrayOf(17, 23, 22)
        val horizonColor = intArrayOf(31, 41, 43)
        fun channel(index: Int): Float {
            val srgb = (pole[index] + (horizonColor[index] - pole[index]) * horizon) / 255f
            return if (srgb <= .04045f) srgb / 12.92f else ((srgb + .055f) / 1.055f).pow(2.4f)
        }
        val color = Color4(channel(0), channel(1), channel(2), 1f)
        for (sector in 0..sectors) {
            val longitude = 2 * PI * sector / sectors
            val normal = Vector3(
                (sin(latitude) * cos(longitude)).toFloat(),
                y,
                (sin(latitude) * sin(longitude)).toFloat(),
            )
            positions += normal * radius
            normals += normal
            colors += color
        }
    }
    for (ring in 0 until rings) for (sector in 0 until sectors) {
        val a = ring * (sectors + 1) + sector
        val b = a + sectors + 1
        // Outward winding, with front-face culling so only the sky's inside is visible.
        if (ring > 0) indices.addAll(listOf(a, a + 1, b))
        if (ring < rings - 1) indices.addAll(listOf(a + 1, b + 1, b))
    }
    return MeshModel(positions, indices, normals = normals, colors = colors)
}
