package com.fourthward.game.vr

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Offline, one-clip-at-a-time lesson speech. All player operations run on the main looper. */
class LessonNarration(context: Context) : AutoCloseable {
    enum class Status { IDLE, PREPARING, PLAYING, PAUSED, COMPLETED, UNAVAILABLE, CLOSED }

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
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
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false

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
            if (!activeRequested) {
                pausePlayer()
                abandonAudioFocus()
            } else if (prepared) updatePlayback(generation.get())
        }
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
        if (!activeRequested) {
            pausePlayer()
            return
        }
        if (!requestAudioFocus(ticket)) {
            publish(Status.PAUSED)
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

    private fun requestAudioFocus(ticket: Long): Boolean {
        if (hasAudioFocus) return true
        val manager = audioManager ?: return true
        if (audioFocusRequest == null) {
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener({ change ->
                    if (closed.get() || generation.get() != ticket || player == null) return@setOnAudioFocusChangeListener
                    when (change) {
                        AudioManager.AUDIOFOCUS_GAIN -> {
                            hasAudioFocus = true
                            if (activeRequested && prepared) updatePlayback(ticket)
                        }
                        AudioManager.AUDIOFOCUS_LOSS,
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                            hasAudioFocus = false
                            pausePlayer()
                        }
                    }
                }, handler)
                .build()
        }
        hasAudioFocus = manager.requestAudioFocus(audioFocusRequest!!) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!hasAudioFocus) {
            errorMessage = "Audio focus unavailable"
            Log.i(TAG, "audio focus unavailable step=$currentStepId; captions remain available")
        }
        return hasAudioFocus
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

    private fun abandonAudioFocus() {
        audioFocusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
        audioFocusRequest = null
        hasAudioFocus = false
    }

    private fun releasePlayer() {
        val previous = player
        player = null
        prepared = false
        previous?.setOnPreparedListener(null)
        previous?.setOnCompletionListener(null)
        previous?.setOnErrorListener(null)
        previous?.release()
        abandonAudioFocus()
    }

    private fun unavailable(reason: String) {
        errorMessage = reason
        Log.w(TAG, "unavailable step=$currentStepId reason=$reason; captions remain available")
        publish(Status.UNAVAILABLE)
    }

    private fun publish(next: Status) {
        if (status != next) Log.i(TAG, "state=$next step=$currentStepId")
        status = next
    }

    private companion object {
        const val TAG = "FourthwardNarration"
        val SAFE_ID = Regex("[a-zA-Z0-9_-]+")
    }
}
