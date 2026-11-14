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
    /** Records of21floats: column-major model16, UV left/top/right/bottom4, depthTest1. */
    @JvmField val uiQuads: FloatArray = floatArrayOf(),
)
