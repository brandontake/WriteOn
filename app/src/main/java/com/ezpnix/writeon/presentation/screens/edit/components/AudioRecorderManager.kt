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
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
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
                setAudioEncodingBitRate(128000)
                setAudioSamplingRate(44100)
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
        try {
            recorder?.apply {
                stop()
                reset()
                release()
            }
        } catch (e: Exception) {
            Log.e("AudioRecorderManager", "Error stopping recorder", e)
            file?.delete()
            return null
        } finally {
            recorder = null
            isRecording = false
            currentOutputFile = null
        }
        return file
    }

    fun cancelRecording() {
        if (isRecording) {
            try {
                recorder?.apply {
                    reset()
                    release()
                }
            } catch (e: Exception) {
                Log.e("AudioRecorderManager", "Error cancelling recorder", e)
            } finally {
                recorder = null
                isRecording = false
                currentOutputFile?.delete()
                currentOutputFile = null
            }
        }
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
