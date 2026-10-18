package com.ezpnix.writeon.presentation.components.markdown

import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

object GlobalAudioPlayer {
    private var mediaPlayer: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())

    var currentPlayingPath by mutableStateOf<String?>(null)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    var progress by mutableFloatStateOf(0f)
        private set
    var durationMs by mutableStateOf(0)
        private set
    var currentPositionMs by mutableStateOf(0)
        private set

    private val progressUpdater = object : Runnable {
        override fun run() {
            mediaPlayer?.let { player ->
                if (isPlaying) {
                    try {
                        val current = player.currentPosition
                        val total = player.duration
                        currentPositionMs = current
                        durationMs = total
                        progress = if (total > 0) current.toFloat() / total.toFloat() else 0f
                        handler.postDelayed(this, 100)
                    } catch (e: Exception) {
                        Log.e("GlobalAudioPlayer", "Progress update error", e)
                    }
                }
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
                        GlobalAudioPlayer.isPlaying = false
                        progress = 0f
                        currentPositionMs = 0
                        handler.removeCallbacks(progressUpdater)
                    }
                    start()
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
        try {
            mediaPlayer?.start()
            isPlaying = true
            handler.post(progressUpdater)
        } catch (e: Exception) {
            Log.e("GlobalAudioPlayer", "Failed to resume playback", e)
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
