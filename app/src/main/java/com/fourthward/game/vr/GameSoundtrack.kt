package com.fourthward.game.vr

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/** A single XR session owns both music and lesson audio focus. Prepare local assets off the render thread. */
class GameSoundtrack(context: Context, skipStartup: Boolean, private val allowSpeech: (Boolean) -> Unit) : AutoCloseable {
    private val assets = context.applicationContext.assets
    private val audio = context.getSystemService(AudioManager::class.java)
    private val thread = HandlerThread("Li4WeiMusic").apply { start() }
    private val handler = Handler(thread.looper)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_GAME)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()
    private var request: AudioFocusRequest? = null
    private val closed = AtomicBoolean(false)
    @Volatile private var requestedActive = false
    private var hasFocus = false
    private var active = false
    private var speaking = false
    private var loaded = false
    private var intro: MediaPlayer? = null
    private var loop: MediaPlayer? = null
    @Volatile var startupPlayed = skipStartup
        private set

    fun setActive(value: Boolean) {
        if (closed.get()) return
        requestedActive = value
        handler.post {
            if (closed.get()) return@post
            if (active != requestedActive) {
                active = requestedActive
                if (active) {
                    acquireFocus()
                } else {
                    request?.let { audio?.abandonAudioFocusRequest(it) }
                    request = null
                    hasFocus = false
                }
            }
            updatePlayback()
        }
    }

    fun setSpeaking(value: Boolean) {
        if (closed.get()) return
        handler.post {
            speaking = value
            updateLoopVolume()
        }
    }

    private fun acquireFocus() {
        val manager = audio
        if (manager == null) {
            hasFocus = true
            return
        }
        lateinit var current: AudioFocusRequest
        current = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setAcceptsDelayedFocusGain(true)
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener({ change ->
                if (!closed.get() && request === current) {
                    hasFocus = change == AudioManager.AUDIOFOCUS_GAIN
                    updatePlayback()
                }
            }, handler)
            .build()
        request = current
        hasFocus = manager.requestAudioFocus(current) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun prepare(path: String): MediaPlayer? {
        val player = MediaPlayer()
        try {
            player.setAudioAttributes(attributes)
            assets.openFd("music/$path").use { file ->
                player.setDataSource(file.fileDescriptor, file.startOffset, file.length)
            }
            player.prepare()
            player.setOnErrorListener { failed, what, extra ->
                Log.w(TAG, "$path playback error $what/$extra")
                if (intro === failed || loop === failed) {
                    discard(failed)
                    updatePlayback()
                }
                true
            }
            return player
        } catch (error: Exception) {
            player.release()
            Log.w(TAG, "Could not prepare $path", error)
            return null
        }
    }

    private fun discard(player: MediaPlayer) {
        if (intro === player) intro = null
        if (loop === player) loop = null
        player.setOnErrorListener(null)
        player.setOnCompletionListener(null)
        player.release()
    }

    private fun load() {
        loaded = true
        loop = prepare("section_loop.ogg")?.apply { isLooping = true }
        updateLoopVolume()
        if (startupPlayed) return
        intro = prepare("Li4Wei_intro.wav")?.apply {
            setVolume(NORMAL_GAIN, NORMAL_GAIN)
            setOnCompletionListener { finished ->
                if (intro === finished) {
                    // The 24-bit PCM call and Vorbis loop need independent Android audio output tracks.
                    discard(finished)
                    Log.i(TAG, "Startup complete")
                    updatePlayback()
                }
            }
        }
    }

    private fun updatePlayback() {
        if (active && hasFocus && !loaded && !closed.get()) load()
        // Preparing a file can span a focus change or teardown request on another thread.
        val playing = requestedActive && hasFocus && !closed.get()
        if (playing) {
            val player = intro ?: loop
            if (player != null && !player.isPlaying) {
                try {
                    player.start()
                } catch (error: IllegalStateException) {
                    Log.w(TAG, "Cannot start music clip", error)
                    discard(player)
                    updatePlayback()
                    return
                }
                if (player === intro) startupPlayed = true
                Log.i(TAG, if (player === intro) "Playing startup" else "Playing instrumental loop")
            }
        } else {
            intro?.let { if (it.isPlaying) it.pause() }
            loop?.let { if (it.isPlaying) it.pause() }
        }
        allowSpeech(playing && intro == null)
    }

    private fun updateLoopVolume() {
        val gain = if (speaking) 0.22f else NORMAL_GAIN
        loop?.setVolume(gain, gain)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        requestedActive = false
        handler.post {
            intro?.let(::discard)
            loop?.let(::discard)
            request?.let { audio?.abandonAudioFocusRequest(it) }
            request = null
            thread.quitSafely()
        }
    }

    private companion object {
        const val TAG = "Li4WeiMusic"
        const val NORMAL_GAIN = 0.64f
    }
}
