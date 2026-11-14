package com.fourthward.game.content

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.SortAsUIElementComponent
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.MaterialCullingMode
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.TextureResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Color4
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Read-only HUD rendered as an actual SDK model, where public depth controls are supported.
 * Camera-relative geometry retains its original binocular depth; only occlusion is disabled.
 * Images are cached by visible state, coalesced and uploaded at most ten times a second off-thread.
 */
internal class HudBitmapLayer(
    private val parent: Entity,
    private val name: String,
    private val logicalWidth: Int,
    private val logicalHeight: Int,
    private val widthMeters: Float,
) {
    var ready by mutableStateOf(false)
        private set
    private data class Request(val key: Any, val draw: (Canvas) -> Unit)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val requests = Channel<Request>(Channel.CONFLATED)
    private var latestKey: Any? = null
    @Volatile private var closed = false
    private var model: ModelEntity? = null
    private var mesh: MeshResource? = null
    private var material: UnlitMaterial? = null
    private var texture: TextureResource? = null

    init {
        scope.launch {
            var lastUploadNanos = 0L
            for (incoming in requests) {
                val elapsed = java.lang.System.nanoTime() - lastUploadNanos
                if (elapsed < 100_000_000L) delay((100_000_000L - elapsed) / 1_000_000L + 1L)
                var request = incoming
                while (true) request = requests.tryReceive().getOrNull() ?: break
                if (closed) break
                val createResources = model == null
                try {
                    // Keep resource ownership explicit across cancellation: even if close() runs
                    // during upload, the main-thread commit below disposes the prepared resources.
                    withContext(NonCancellable + Dispatchers.IO) {
                        val prepared = prepare(request, createResources)
                        try {
                            withContext(NonCancellable + Dispatchers.Main.immediate) {
                                if (!closed) commit(prepared)
                            }
                        } finally {
                            prepared.closeUnlessTransferred()
                        }
                    }
                    lastUploadNanos = java.lang.System.nanoTime()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (latestKey == request.key) latestKey = null
                    Log.e("FourthwardHud", "$name texture update failed", error)
                }
            }
        }
    }

    fun render(key: Any, draw: (Canvas) -> Unit) {
        if (closed || key == latestKey) return
        latestKey = key
        requests.trySend(Request(key, draw))
    }

    private class Prepared(
        val texture: TextureResource,
        val mesh: MeshResource?,
        val material: UnlitMaterial?,
    ) {
        var transferred = false
        fun closeUnlessTransferred() {
            if (!transferred) {
                material?.close()
                mesh?.close()
                texture.close()
            }
        }
    }

    private fun prepare(request: Request, createResources: Boolean): Prepared {
        var nextTexture: TextureResource? = null
        var nextMesh: MeshResource? = null
        var nextMaterial: UnlitMaterial? = null
        try {
            val bitmap = Bitmap.createBitmap(logicalWidth * 2, logicalHeight * 2, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(bitmap)
                canvas.scale(2f, 2f)
                request.draw(canvas)
                nextTexture = TextureResource.create(bitmap)
                nextTexture.toGlobal()
            } finally {
                bitmap.recycle()
            }
            if (createResources) {
                nextMesh = MeshResource.createPlane(widthMeters, widthMeters * logicalHeight / logicalWidth)
                nextMesh.toGlobal()
                nextMaterial = UnlitMaterial.create(BlendingMode.TRANSPARENT)
                nextMaterial.toGlobal()
                nextMaterial.setBaseColor(Color4.WHITE)
                nextMaterial.setCullingMode(MaterialCullingMode.NONE)
                nextMaterial.setDepthTest(false)
                nextMaterial.setDepthWrite(false)
                nextMaterial.setApplyToneMapping(false)
            }
            return Prepared(requireNotNull(nextTexture), nextMesh, nextMaterial)
        } catch (error: Exception) {
            nextMaterial?.close()
            nextMesh?.close()
            nextTexture?.close()
            throw error
        }
    }

    private fun commit(prepared: Prepared) {
        val targetMaterial = material ?: requireNotNull(prepared.material)
        targetMaterial.setBaseColorTexture(prepared.texture)
        var candidate: ModelEntity? = null
        try {
            if (model == null) {
                candidate = ModelEntity(requireNotNull(prepared.mesh), targetMaterial).apply {
                    setName(name)
                    // This component is supported on ModelEntity, unlike AttachmentPanel.
                    components.set(SortAsUIElementComponent(distanceBias = 10f))
                }
                parent.addChild(candidate)
            }
            val previous = texture
            texture = prepared.texture
            mesh = mesh ?: prepared.mesh
            material = targetMaterial
            model = model ?: candidate
            prepared.transferred = true
            if (!ready) Log.i("FourthwardHud", "$name model overlay ready (depthTest=false)")
            ready = true
            runCatching { previous?.close() }
                .onFailure { Log.w("FourthwardHud", "$name old texture disposal failed", it) }
        } catch (error: Exception) {
            candidate?.destroy()
            throw error
        }
    }

    fun close() {
        if (closed) return
        closed = true
        requests.close()
        scope.cancel()
        model?.destroy()
        model = null
        material?.close()
        material = null
        mesh?.close()
        mesh = null
        texture?.close()
        texture = null
        ready = false
    }
}

/** Shared Canvas typography mirrors the Compose indicators and uses Android's CJK fallback. */
internal object HudInk {
    val ivory: Int = Color.rgb(242, 242, 240)
    val signal: Int = Color.rgb(255, 250, 0)
    val success: Int = Color.rgb(154, 227, 177)

    fun card(canvas: Canvas, width: Float, height: Float, radius: Float, alpha: Int) {
        val rect = RectF(0.5f, 0.5f, width - 0.5f, height - 0.5f)
        canvas.drawRoundRect(rect, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(alpha, 25, 25, 25)
        })
        canvas.drawRoundRect(rect, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = faded(ivory, 0.25f)
            style = Paint.Style.STROKE
            strokeWidth = 1f
        })
    }

    fun paint(size: Float, color: Int = ivory, bold: Boolean = false, mono: Boolean = false): TextPaint =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.color = color
            typeface = Typeface.create(if (mono) "monospace" else "sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
        }

    fun text(canvas: Canvas, text: String, x: Float, baseline: Float, paint: TextPaint, maxWidth: Float = Float.POSITIVE_INFINITY) {
        val fitted = if (maxWidth.isFinite()) TextUtils.ellipsize(text, paint, maxWidth.coerceAtLeast(0f), TextUtils.TruncateAt.END).toString() else text
        canvas.drawText(fitted, x, baseline, paint)
    }

    fun lines(canvas: Canvas, text: String, x: Float, top: Float, width: Int, paint: TextPaint, maxLines: Int) {
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
        canvas.save()
        canvas.translate(x, top)
        layout.draw(canvas)
        canvas.restore()
    }

    fun line(canvas: Canvas, x: Float, y: Float, endX: Float, color: Int, thickness: Float = 2f) {
        canvas.drawLine(x, y, endX, y, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            strokeWidth = thickness
            strokeCap = Paint.Cap.ROUND
        })
    }

    fun faded(color: Int, alpha: Float): Int = Color.argb((alpha * 255f).toInt(), Color.red(color), Color.green(color), Color.blue(color))
}
