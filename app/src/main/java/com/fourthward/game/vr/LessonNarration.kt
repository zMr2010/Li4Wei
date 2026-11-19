package com.fourthward.game.vr

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Offline, one-clip-at-a-time lesson speech. All player operations run on the main looper. */
class LessonNarration(context: Context, private val onSpeaking: (Boolean) -> Unit) : AutoCloseable {
    enum class Status { IDLE, PREPARING, PLAYING, PAUSED, COMPLETED, UNAVAILABLE, CLOSED }

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val generation = AtomicLong(0L)
    private val closed = AtomicBoolean(false)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_GAME)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    @Volatile var status: Status = Status.IDLE; private set
    @Volatile var currentStepId: String? = null; private set
    @Volatile var errorMessage: String? = null; private set
    @Volatile private var activeRequested = false

    // Main-looper-owned fields. A generation check rejects callbacks from superseded clips.
    private var player: MediaPlayer? = null
    private var prepared = false
    private var audioAllowed = false

    fun play(stepId: String) {
        if (closed.get()) return
        val ticket = generation.incrementAndGet()
        onMain {
            if (closed.get() || generation.get() != ticket) return@onMain
            releasePlayer()
            currentStepId = stepId
            errorMessage = null
            if (!SAFE_ID.matches(stepId)) {
                unavailable("Invalid narration id")
                return@onMain
            }
            publish(Status.PREPARING)
            try {
                val next = MediaPlayer()
                player = next
                next.setAudioAttributes(attributes)
                next.setOnPreparedListener { ready ->
                    if (!owns(ready, ticket)) return@setOnPreparedListener
                    prepared = true
                    Log.i(TAG, "prepared step=$stepId durationMs=${ready.duration}")
                    updatePlayback(ticket)
                }
                next.setOnCompletionListener { complete ->
                    if (!owns(complete, ticket)) return@setOnCompletionListener
                    releasePlayer()
                    publish(Status.COMPLETED)
                }
                next.setOnErrorListener { failed, what, extra ->
                    if (owns(failed, ticket)) {
                        releasePlayer()
                        unavailable("Media error $what/$extra")
                    }
                    true
                }
                appContext.assets.openFd("narration/$stepId.m4a").use { asset ->
                    next.setDataSource(asset.fileDescriptor, asset.startOffset, asset.length)
                }
                next.prepareAsync()
            } catch (error: Exception) {
                releasePlayer()
                unavailable(error.message ?: error.javaClass.simpleName)
            }
        }
    }

    /** XR/app focus loss pauses the current sentence; regaining focus resumes that sentence. */
    fun setActive(active: Boolean) {
        if (closed.get()) return
        if (activeRequested == active) return
        activeRequested = active
        onMain {
            if (closed.get()) return@onMain
            if (prepared) updatePlayback(generation.get())
        }
    }

    /** The soundtrack owns system audio focus and leaves the startup call clear of tutorial speech. */
    fun setAudioAllowed(allowed: Boolean) = onMain {
        if (closed.get() || audioAllowed == allowed) return@onMain
        audioAllowed = allowed
        if (prepared) updatePlayback(generation.get())
    }

    /** Entering a playable level or changing to another screen releases the pending sentence. */
    fun stop() {
        if (closed.get()) return
        val ticket = generation.incrementAndGet()
        onMain {
            if (closed.get() || generation.get() != ticket) return@onMain
            releasePlayer()
            currentStepId = null
            errorMessage = null
            publish(Status.IDLE)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        activeRequested = false
        generation.incrementAndGet()
        onMain {
            releasePlayer()
            currentStepId = null
            errorMessage = null
            publish(Status.CLOSED)
        }
    }

    private fun owns(candidate: MediaPlayer, ticket: Long): Boolean =
        !closed.get() && generation.get() == ticket && candidate === player

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == handler.looper) action() else handler.post { action() }
    }

    private fun updatePlayback(ticket: Long) {
        val current = player ?: return
        if (!owns(current, ticket) || !prepared) return
        if (!activeRequested || !audioAllowed) {
            pausePlayer()
            return
        }
        try {
            if (!current.isPlaying) current.start()
            errorMessage = null
            publish(Status.PLAYING)
        } catch (error: IllegalStateException) {
            releasePlayer()
            unavailable(error.message ?: "Cannot start narration")
        }
    }

    private fun pausePlayer() {
        val current = player ?: return
        if (!prepared) return
        try {
            if (current.isPlaying) current.pause()
            publish(Status.PAUSED)
        } catch (error: IllegalStateException) {
            releasePlayer()
            unavailable(error.message ?: "Cannot pause narration")
        }
    }

    private fun releasePlayer() {
        val previous = player
        player = null
        prepared = false
        previous?.setOnPreparedListener(null)
        previous?.setOnCompletionListener(null)
        previous?.setOnErrorListener(null)
        previous?.release()
    }

    private fun unavailable(reason: String) {
        errorMessage = reason
        Log.w(TAG, "unavailable step=$currentStepId reason=$reason; captions remain available")
        publish(Status.UNAVAILABLE)
    }

    private fun publish(next: Status) {
        if (status != next) Log.i(TAG, "state=$next step=$currentStepId")
        if ((status == Status.PLAYING) != (next == Status.PLAYING)) onSpeaking(next == Status.PLAYING)
        status = next
    }

    private companion object {
        const val TAG = "FourthwardNarration"
        val SAFE_ID = Regex("[a-zA-Z0-9_-]+")
    }
}
