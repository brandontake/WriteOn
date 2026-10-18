package com.ezpnix.writeon.presentation.components.markdown

import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

object GlobalAudioPlayer {
    /**
     * How often the progress ticker refreshes, in milliseconds.
     *
     * A voice memo player has no use for sub-quarter-second progress. Dropping from 100 ms to
     * 250 ms cuts the main-thread snapshot writes and recompositions by 60% for the whole time a
     * clip is playing, which is exactly the window where a notes app should be cheapest.
     */
    private const val PROGRESS_INTERVAL_MS = 250L

    private var mediaPlayer: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())

    var currentPlayingPath by mutableStateOf<String?>(null)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    var progress by mutableFloatStateOf(0f)
        private set
    var durationMs by mutableIntStateOf(0)
        private set
    var currentPositionMs by mutableIntStateOf(0)
        private set

    /**
     * Playback speed, persisted for the lifetime of the process so picking 1.5x once applies to
     * every clip played afterwards — a user who listens to voice memos at 1.5x does not want to
     * set it again for each recording.
     */
    var speed by mutableFloatStateOf(1f)
        private set

    private val progressUpdater = object : Runnable {
        override fun run() {
            val player = mediaPlayer ?: return
            if (!isPlaying) return
            try {
                val current = player.currentPosition
                val total = player.duration
                currentPositionMs = current
                durationMs = total
                progress = if (total > 0) current.toFloat() / total.toFloat() else 0f
                // 4 Hz is ample for a progress bar. Rescheduling at 10 Hz rewrote five snapshot
                // states on the main thread ten times a second for the whole length of the clip.
                handler.postDelayed(this, PROGRESS_INTERVAL_MS)
            } catch (e: Exception) {
                // The player was released or errored underneath us; drop the ticker instead of
                // spinning on it.
                Log.e("GlobalAudioPlayer", "Progress update error", e)
            }
        }
    }

    fun playOrPause(filePath: String) {
        val file = File(filePath)
        if (!file.exists()) {
            Log.e("GlobalAudioPlayer", "Audio file does not exist: $filePath")
            return
        }

        if (currentPlayingPath == filePath) {
            // Toggle pause / resume
            if (isPlaying) {
                pause()
            } else {
                resume()
            }
        } else {
            // Play a new audio file
            stop()
            try {
                mediaPlayer = MediaPlayer().apply {
                    setDataSource(filePath)
                    prepare()
                    setOnCompletionListener {
                        // Release the decoder as soon as the clip ends. Holding on to a finished
                        // MediaPlayer keeps its native buffers and codec alive for the rest of the
                        // process lifetime; stop() also clears the ticker and the shared state.
                        stop()
                    }
                    // Without an error listener an unplayable file left isPlaying = true for ever,
                    // so the ticker kept rescheduling itself on the main looper with nothing to
                    // report: a permanent 10 Hz wake-up for the rest of the process lifetime.
                    setOnErrorListener { _, what, extra ->
                        Log.e("GlobalAudioPlayer", "Playback error what=$what extra=$extra")
                        stop()
                        true
                    }
                    start()
                    // A fresh MediaPlayer always starts at 1x, so the user's chosen speed has to
                    // be re-applied after every start().
                    applySpeed(this, speed)
                }
                currentPlayingPath = filePath
                isPlaying = true
                durationMs = mediaPlayer?.duration ?: 0
                handler.post(progressUpdater)
            } catch (e: Exception) {
                Log.e("GlobalAudioPlayer", "Failed to start playback for $filePath", e)
                stop()
            }
        }
    }

    fun pause() {
        try {
            mediaPlayer?.pause()
            isPlaying = false
            handler.removeCallbacks(progressUpdater)
        } catch (e: Exception) {
            Log.e("GlobalAudioPlayer", "Failed to pause playback", e)
        }
    }

    fun resume() {
        // stop() leaves mediaPlayer null, so resuming a finished clip must not flip isPlaying on and
        // start a ticker that has nothing behind it.
        val player = mediaPlayer ?: return
        try {
            player.start()
            isPlaying = true
            handler.post(progressUpdater)
        } catch (e: Exception) {
            Log.e("GlobalAudioPlayer", "Failed to resume playback", e)
            stop()
        }
    }

    /** The speeds offered by the player card, in the order the button cycles them. */
    val speedOptions = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

    /**
     * Changes the playback rate. Applied to the live player when one exists, and remembered for the
     * next clip because a fresh [MediaPlayer] always starts at 1x.
     *
     * Named setPlaybackSpeed rather than setSpeed: the synthesised setter of [speed] already owns
     * the JVM signature setSpeed(F)V.
     */
    fun setPlaybackSpeed(value: Float) {
        speed = value.coerceIn(speedOptions.first(), speedOptions.last())
        mediaPlayer?.let { applySpeed(it, speed) }
    }

    private fun applySpeed(player: MediaPlayer, value: Float) {
        try {
            player.playbackParams = player.playbackParams.setSpeed(value)
        } catch (e: Exception) {
            // Some codecs reject speed changes; keep playing at the previous rate rather than
            // tearing the player down for a cosmetic feature.
            Log.e("GlobalAudioPlayer", "Failed to set playback speed to $value", e)
        }
    }

    fun seekTo(ratio: Float) {
        try {
            mediaPlayer?.let { player ->
                val targetMs = (player.duration * ratio.coerceIn(0f, 1f)).toInt()
                player.seekTo(targetMs)
                currentPositionMs = targetMs
                progress = ratio.coerceIn(0f, 1f)
            }
        } catch (e: Exception) {
            Log.e("GlobalAudioPlayer", "Failed to seek", e)
        }
    }

    fun stop() {
        handler.removeCallbacks(progressUpdater)
        try {
            mediaPlayer?.apply {
                if (isPlaying) {
                    stop()
                }
                reset()
                release()
            }
        } catch (e: Exception) {
            Log.e("GlobalAudioPlayer", "Error stopping player", e)
        } finally {
            mediaPlayer = null
            currentPlayingPath = null
            isPlaying = false
            progress = 0f
            durationMs = 0
            currentPositionMs = 0
        }
    }
}
