package com.fourthward.game.vr

import android.graphics.Bitmap
import androidx.annotation.Keep

/** JNI-owned rendering input. Null buffers retain the previous GPU mesh/texture. */
@Keep
class VrRenderFrame(
    /** Non-indexed triangles: x,y,z,r,g,b,a for every vertex, in OpenXR world coordinates. */
    @JvmField val worldVertices: FloatArray? = null,
    /** Canvas ARGB8888 atlas; native blending must account for Android premultiplied alpha. */
    @JvmField val uiBitmap: Bitmap? = null,
    /** Foreground quads: column-major model (16), UV bounds (4), premultiplied opacity (1). */
    @JvmField val uiQuads: FloatArray = floatArrayOf(),
    /** Independent triangle stream. Empty clears it, null retains the GPU buffer. */
    @JvmField val effectsVertices: FloatArray? = null,
    /** Maps a new world mesh and its effects into XR tracking space. Null selects identity. */
    @JvmField val worldModel: FloatArray? = null,
    /** For the opaque welcome exhibit, effectsVertices stores W in its alpha lane.
     * The 4D matrix and center/focal vector are shared by both eye draws. */
    @JvmField val exhibitProjection: FloatArray? = null,
    @JvmField val lightEnvironment: Boolean = true,
)
