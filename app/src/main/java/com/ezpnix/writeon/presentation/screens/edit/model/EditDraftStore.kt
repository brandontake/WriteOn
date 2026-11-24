package com.ezpnix.writeon.presentation.screens.edit.model

import android.content.Context
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * PERF-05: the directory under `noBackupFilesDir` holding one draft file per note id.
 *
 * Shared with [EditDraftStore.clearFor] and [EditViewModel] so a permanent delete and the editor
 * can never disagree about where the drafts live.
 */
internal const val DRAFT_DIRECTORY = "edit-drafts"

/**
 * PERF-05: unsaved editor text kept in the app's private files instead of `SavedStateHandle`.
 *
 * The draft used to be written into the handle, which NavController serialises into the Activity's
 * saved-instance `Bundle`. A 300-500k character body is several hundred kilobytes of UTF-16 and
 * crossed the Binder budget when the process was backgrounded or the screen rotated - the exact
 * notes this editor is for crashed with `TransactionTooLargeException`. The handle now carries no
 * note text at all; each draft lives in its own file under `noBackupFilesDir/edit-drafts`.
 *
 * One file per note id, written to a `.tmp` and renamed into place, so a process death mid-write
 * leaves either a complete earlier draft or the previous one - never a half file. A draft larger
 * than [MAX_BODY_BYTES] is not stored at all: a truncated draft silently losing the tail of a note
 * is worse than the old "no draft for this session" behaviour, so [write] clears the old file and
 * reports false instead.
 *
 * The store is deliberately Context-free and Android-free: the caller passes the directory, and a
 * plain JVM test can drive every branch with a temporary folder.
 */
internal class EditDraftStore(private val directory: File) {

    /** The text of one unsaved draft. */
    data class Draft(val name: String, val body: String)

    /**
     * Writes [name]/[body] for [noteId], or clears the slot when the payload exceeds the cap.
     *
     * @return true when the draft is on disk.
     */
    fun write(noteId: Int, name: String, body: String): Boolean {
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        if (nameBytes.size > MAX_NAME_BYTES || bodyBytes.size > MAX_BODY_BYTES) {
            // Keep no old draft either: it describes text the user has since replaced, and
            // restoring it would look like the newer (too large) text was never typed.
            clear(noteId)
            return false
        }

        val target = fileFor(noteId)
        val temp = File(directory, "${target.name}.tmp")
        return try {
            directory.mkdirs()
            DataOutputStream(FileOutputStream(temp).buffered()).use { out ->
                out.writeInt(MAGIC)
                out.writeInt(VERSION)
                out.writeInt(noteId)
                out.writeInt(nameBytes.size)
                out.write(nameBytes)
                out.writeInt(bodyBytes.size)
                out.write(bodyBytes)
            }
            // Rename is atomic within the same directory; deleting first is only needed because
            // some file systems (Windows, which runs the JVM tests) refuse to rename over a file.
            target.delete()
            // PERF-05: a failed rename used to leave the `.tmp` behind forever, and `clear()` only
            // knows the canonical `.tmp` name this session computes - a leftover from an older
            // build/id could accumulate. The temp is useless once the rename failed.
            if (temp.renameTo(target)) true else {
                temp.delete()
                false
            }
        } catch (e: Exception) {
            // A draft is a convenience; a failing file system must not take the editor down.
            temp.delete()
            false
        }
    }

    /**
     * Reads the draft for [noteId], or null when there is none or the file is not one this class
     * wrote. A corrupted file is deleted so it cannot be retried forever.
     */
    fun read(noteId: Int): Draft? {
        val file = fileFor(noteId)
        if (!file.isFile) return null
        // The corrupted file is deleted only after the stream has closed: Windows refuses to delete
        // a file that is still open, which left a corrupt draft in place forever.
        val draft = try {
            DataInputStream(FileInputStream(file).buffered()).use { input ->
                when {
                    input.readInt() != MAGIC -> null
                    input.readInt() != VERSION -> null
                    input.readInt() != noteId -> null
                    else -> {
                        val nameLength = input.readInt()
                        if (nameLength !in 0..MAX_NAME_BYTES) {
                            null
                        } else {
                            val nameBytes = ByteArray(nameLength)
                            input.readFully(nameBytes)
                            val bodyLength = input.readInt()
                            if (bodyLength !in 0..MAX_BODY_BYTES) {
                                null
                            } else {
                                val bodyBytes = ByteArray(bodyLength)
                                input.readFully(bodyBytes)
                                // Trailing bytes mean the file is not exactly what [write] produced.
                                if (input.read() != -1) {
                                    null
                                } else {
                                    Draft(
                                        name = nameBytes.toString(Charsets.UTF_8),
                                        body = bodyBytes.toString(Charsets.UTF_8)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            null
        }
        if (draft == null) discard(file)
        return draft
    }

    /** Removes the draft for [noteId]; safe when none exists. */
    fun clear(noteId: Int) {
        discard(fileFor(noteId))
        discard(File(directory, "${fileFor(noteId).name}.tmp"))
    }

    /**
     * PERF-05: writes the draft only while [isEncryptedNow] still says it is a plain note.
     *
     * The debounce and the actual write are separated by a dispatcher hop, and the vault flag can
     * flip in that window. Re-checking here - on the IO thread, immediately before the file is
     * created - means a note that became a vault note cannot leave its plaintext behind. Any draft
     * an earlier session wrote is deleted rather than kept.
     *
     * @return true only when the draft is on disk.
     */
    fun writeIfStillAllowed(
        noteId: Int,
        name: String,
        body: String,
        isEncryptedNow: () -> Boolean,
    ): Boolean {
        if (!draftAllowed(isEncryptedNow())) {
            clear(noteId)
            return false
        }
        return write(noteId, name, body)
    }

    private fun fileFor(noteId: Int): File = File(directory, "$noteId.draft")

    private fun discard(file: File) {
        if (file.exists()) file.delete()
    }

    companion object {
        /**
         * PERF-05: drops the draft of permanently deleted [noteId].
         *
         * Permanent delete runs from Home and the recycle bin, where no editor exists to clear its
         * own draft. Safe when no draft exists. Blocking file IO: callers keep it on
         * [kotlinx.coroutines.Dispatchers.IO].
         */
        fun clearFor(context: Context, noteId: Int) {
            EditDraftStore(File(context.noBackupFilesDir, DRAFT_DIRECTORY)).clear(noteId)
        }

        /** "WDN1" - rejects unrelated files left in the directory by anything else. */
        private const val MAGIC = 0x57444E31
        private const val VERSION = 1

        /** ~1 MB of UTF-8 body text: the payload bound the audit asks for. */
        private const val MAX_BODY_BYTES = 1024 * 1024
        private const val MAX_NAME_BYTES = 64 * 1024
    }
}

/**
 * SEC-7: a vault note's plaintext never reaches a draft file.
 *
 * Kept as a named predicate so the rule is visible at the call site and testable without the
 * ViewModel: an encrypted note gets no persisted draft, exactly as it never got a SavedStateHandle
 * one.
 */
internal fun draftAllowed(isEncrypted: Boolean): Boolean = !isEncrypted
