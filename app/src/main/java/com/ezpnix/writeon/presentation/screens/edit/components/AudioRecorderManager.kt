package com.ezpnix.writeon.presentation.screens.edit.components

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AudioRecorderManager(private val context: Context) {
    private var recorder: MediaRecorder? = null
    var currentOutputFile: File? = null
        private set
    var isRecording: Boolean = false
        private set

    fun startRecording(): Boolean {
        if (isRecording) return false
        return try {
            val audioDir = File(context.filesDir, "audios").apply {
                if (!exists()) mkdirs()
            }
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault()).format(Date())
            val file = File(audioDir, "REC_$timeStamp.m4a")
            currentOutputFile = file

            val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            mr.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                // 64 kbps mono is transparent for speech and halves both the stored file and the
                // encoder work compared with the previous 128 kbps stereo-capable profile.
                setAudioEncodingBitRate(64000)
                setAudioSamplingRate(44100)
                setAudioChannels(1)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }

            recorder = mr
            isRecording = true
            true
        } catch (e: Exception) {
            Log.e("AudioRecorderManager", "Failed to start recording", e)
            cleanup()
            false
        }
    }

    fun stopRecording(): File? {
        if (!isRecording) return null
        val file = currentOutputFile
        val active = recorder
        recorder = null
        isRecording = false
        currentOutputFile = null

        if (active == null) {
            file?.delete()
            return null
        }

        // stop() throws when the clip is too short to contain a single frame, which is what happens
        // every time somebody taps record and then stop immediately. reset() and release() used to
        // share that try block, so the common case skipped both and leaked the MediaRecorder while
        // leaving the microphone open - the privacy indicator stayed lit and the capture session
        // kept holding the audio hardware until the process died.
        var hasAudio = true
        try {
            active.stop()
        } catch (e: Exception) {
            Log.e("AudioRecorderManager", "Error stopping recorder", e)
            hasAudio = false
        }
        runCatching { active.reset() }
            .onFailure { Log.e("AudioRecorderManager", "Error resetting recorder", it) }
        runCatching { active.release() }
            .onFailure { Log.e("AudioRecorderManager", "Error releasing recorder", it) }

        if (!hasAudio) {
            file?.delete()
            return null
        }
        return file
    }

    fun cancelRecording() {
        val active = recorder
        recorder = null
        isRecording = false
        val discarded = currentOutputFile
        currentOutputFile = null
        discarded?.delete()
        if (active == null) return

        // Same shape as stopRecording: reset() can throw on a recorder that is already in the error
        // state, and it used to take release() down with it, leaving the microphone capturing.
        runCatching { active.reset() }
            .onFailure { Log.e("AudioRecorderManager", "Error resetting recorder", it) }
        runCatching { active.release() }
            .onFailure { Log.e("AudioRecorderManager", "Error releasing recorder", it) }
    }

    private fun cleanup() {
        try {
            recorder?.release()
        } catch (_: Exception) {}
        recorder = null
        isRecording = false
        currentOutputFile?.delete()
        currentOutputFile = null
    }

    companion object {
        private val AUDIO_TAG_REGEX = Regex("""\?audio\(([^)]+)\)""")

        /**
         * Extracts all audio file paths embedded via ?audio(path) inside Markdown text.
         */
        fun extractAudioPaths(text: String): Set<String> {
            return AUDIO_TAG_REGEX.findAll(text)
                .map { it.groupValues[1].trim() }
                .filter { it.isNotEmpty() }
                .toSet()
        }

        /**
         * Safely deletes audio files on disk that belong to the app's internal audios directory.
         */
        fun deleteAudioFiles(paths: Collection<String>, context: Context) {
            val audioDir = File(context.filesDir, "audios").canonicalPath
            for (path in paths) {
                try {
                    val file = File(path)
                    // Security guard: ensure the file is strictly inside context.filesDir/audios/
                    if (file.exists() && file.canonicalPath.startsWith(audioDir)) {
                        file.delete()
                    }
                } catch (t: Throwable) {
                    Log.e("AudioRecorderManager", "Failed to delete audio file: $path", t)
                }
            }
        }
    }
}
