package com.ezpnix.writeon.data.backup

import android.content.Context
import android.util.Log
import com.ezpnix.writeon.core.attachment.AttachmentArchive
import com.ezpnix.writeon.core.attachment.AttachmentSnapshots
import com.ezpnix.writeon.core.attachment.InstallGeneration
import com.ezpnix.writeon.core.constant.DatabaseConst
import com.ezpnix.writeon.core.util.DatabaseRestore
import com.ezpnix.writeon.data.local.database.LegacyCaptionBackfillFlag
import com.ezpnix.writeon.data.local.database.LegacyPlaintextBackfillFlag
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * N-1 restart half: the small window between installing the restored database and applying its
 * salt/deck.
 *
 * The database file and SharedPreferences are separate stores, so there is no atomic transaction
 * across them. The `.bak` sidecar now makes the *undo* safe by being written before the swap, but a
 * process kill after the database move and before `VaultSaltArchive`/`FlashcardArchive` ran would
 * still leave the live database under the old salt. This journal is written before the move and
 * records only what is needed to finish that transition:
 *
 * - the hash of the database that is about to be installed (so startup can tell whether the move
 *   happened without trusting a phase flag that a kill can interrupt);
 * - the archive's salt and the sealed deck, if any.
 *
 * On startup [recover] compares the live database bytes with that hash. A match finishes applying
 * the new prefs state; a mismatch means the move never landed, so the live prefs are already the
 * old, correct pair and are left alone. A mismatch deletes the journal; a match deletes it only
 * after **every** apply succeeded (DAT-04). A salt write that fails on a full or read-only prefs
 * file leaves the journal - the only copy of the salt the installed database needs - in place for
 * the next start. It never holds plaintext cards: [FlashcardArchive.sealForRecovery] seals them
 * with the same Keystore key as the live deck.
 *
 * The file lives under `noBackupFilesDir`, so neither Android backup nor a device transfer carries
 * it, and the OS cannot evict it between the crash and the next launch.
 */
object RestoreJournal {

    private const val TAG = "RestoreJournal"
    private const val FILE_NAME = "restore-pending.json"
    private const val VERSION = 1
    private const val KEY_VERSION = "version"
    private const val KEY_HASH = "restoredDbSha256"
    private const val KEY_SALT = "newVaultSalt"
    private const val KEY_SET_SALT = "setVaultSalt"
    private const val KEY_DECK = "newFlashcards"
    private const val KEY_SET_DECK = "setFlashcards"
    private const val KEY_DECK_REQUIRED = "flashcardsRequired"
    private const val KEY_ATTACHMENTS = "attachmentTarget"
    private const val KEY_ATTACHMENTS_DISCARD = "attachmentDiscard"

    /**
     * R2-09: the staging tree this restore extracted its attachments into.
     *
     * The database swap happens before [AttachmentArchive.install]. A kill in between used to leave
     * a live database whose rows point at bytes still sitting in a staging directory that startup
     * cleanup would later delete as abandoned. Recording the directory lets the next start finish
     * the install, and keeps the cleanup away from it while the journal exists.
     */
    private const val KEY_ATTACHMENT_STAGING = "attachmentStaging"

    /**
     * R5-01: the generation token the staged database was stamped with before its hash.
     *
     * `AttachmentArchive.install` reuses this token, so the live file keeps the exact bytes the
     * hash below describes. Recovery compares the token first: a byte change from another cause
     * (a save, a WAL checkpoint) no longer makes a completed move look like "never happened".
     * Older journals have no key and keep the byte comparison.
     */
    private const val KEY_GENERATION = "databaseGeneration"

    private data class Pending(
        val restoredDbSha256: String,
        val newSalt: String?,
        val setSalt: Boolean,
        val newSealedDeck: String?,
        val setDeck: Boolean,
        /**
         * DAT-04: the archive/snapshot carried a deck that could not be sealed for the journal.
         * Unlike [setDeck] with a null deck (the undo's explicit "there was no deck"), this is a
         * failed apply, never a no-op: recovery must keep the journal instead of silently leaving
         * the restored database with the old deck.
         */
        val deckRequired: Boolean,
        val attachmentTarget: String?,
        val attachmentDiscard: String?,
        /** R2-09: attachment staging tree to install if the process died before [AttachmentArchive.install]. */
        val attachmentStaging: String?,
        /** R5-01: the generation token stamped into the database this journal describes. */
        val generationToken: Int?,
    )

    fun file(context: Context): File = File(context.noBackupFilesDir, FILE_NAME)

    /**
     * R2-09: staging trees the pending restore still needs.
     *
     * Startup cleanup runs right after [recover]; when recovery failed (a full disk, a read-only
     * prefs file) the journal survives and names the directory the next attempt must install from.
     * The cleanup's age gate alone would delete it on any launch more than ten minutes later.
     */
    fun pendingStagingDirectories(context: Context): Set<String> {
        val journal = file(context)
        if (!journal.isFile) return emptySet()
        val staging = read(journal)?.attachmentStaging ?: return emptySet()
        return setOfNotNull(
            stagingDirectoryWithinRoot(context, staging)?.let {
                runCatching { it.canonicalPath }.getOrNull()
            }
        )
    }

    /**
     * The journal is app-private, but it is still parsed input: only a direct child of this
     * install's attachment staging root may be installed or protected. Anything else would let a
     * corrupted or hand-edited record move files out of an unrelated directory.
     */
    private fun stagingDirectoryWithinRoot(context: Context, recorded: String): File? {
        val candidate = runCatching { File(recorded).canonicalFile }.getOrNull() ?: return null
        val root = runCatching { AttachmentArchive.stagingRoot(context).canonicalFile }.getOrNull()
            ?: return null
        val inside = candidate.path.startsWith(root.path + File.separator)
        return candidate.takeIf { inside && it.isDirectory }
    }

    /**
     * Writes the transition record. Called after the archive has been validated and its attachment
     * snapshot captured, but before Room is closed. A failure must abort the restore: without the
     * journal a kill would be unrecoverable, which is the state this object exists to prevent.
     */
    fun write(
        context: Context,
        restoredDb: File,
        newSalt: String?,
        newDeckSealed: String?,
        setSalt: Boolean = newSalt != null,
        setDeck: Boolean = newDeckSealed != null,
        deckRequired: Boolean = false,
        attachmentTarget: String? = null,
        attachmentDiscard: String? = null,
        attachmentStaging: String? = null,
        /**
         * R5-01: stamp the staged database before hashing it. The two restore flows pass `true`:
         * `AttachmentArchive.install` runs after the swap and would otherwise stamp the live file,
         * changing the bytes this journal's hash describes and making a completed restore look
         * like one that never moved. The snapshot undo passes `false` - its `.bak` is validated
         * against a sidecar hash that a stamp would invalidate, and it never runs
         * `AttachmentArchive.install`.
         */
        stampGeneration: Boolean = false,
    ): Boolean {
        val generationToken = if (stampGeneration) {
            val token = InstallGeneration.stamp(restoredDb)
            // A real database that cannot be stamped would be changed *after* this hash by a
            // later install, reopening the exact window this parameter exists to close. Abort the
            // restore before the swap instead; test fixtures are not SQLite and need no stamp.
            if (token == null && InstallGeneration.isSqliteDatabase(restoredDb)) return false
            token
        } else {
            null
        }
        val hash = SnapshotSidecar.sha256(restoredDb) ?: return false
        val json = JsonObject().apply {
            addProperty(KEY_VERSION, VERSION)
            addProperty(KEY_HASH, hash)
            if (generationToken != null) {
                addProperty(KEY_GENERATION, generationToken)
            }
            addProperty(KEY_SET_SALT, setSalt)
            addProperty(KEY_SET_DECK, setDeck)
            if (newSalt != null) addProperty(KEY_SALT, newSalt) else add(KEY_SALT, JsonNull.INSTANCE)
            if (newDeckSealed != null) {
                addProperty(KEY_DECK, newDeckSealed)
            } else {
                add(KEY_DECK, JsonNull.INSTANCE)
            }
            addProperty(KEY_DECK_REQUIRED, deckRequired)
            if (attachmentTarget != null) {
                addProperty(KEY_ATTACHMENTS, attachmentTarget)
            } else {
                add(KEY_ATTACHMENTS, JsonNull.INSTANCE)
            }
            if (attachmentDiscard != null) {
                addProperty(KEY_ATTACHMENTS_DISCARD, attachmentDiscard)
            } else {
                add(KEY_ATTACHMENTS_DISCARD, JsonNull.INSTANCE)
            }
            if (attachmentStaging != null) {
                addProperty(KEY_ATTACHMENT_STAGING, attachmentStaging)
            } else {
                add(KEY_ATTACHMENT_STAGING, JsonNull.INSTANCE)
            }
        }.toString().toByteArray(Charsets.UTF_8)

        val target = file(context)
        val temp = File(target.absolutePath + ".tmp")
        return try {
            FileOutputStream(temp).use { output ->
                output.write(json)
                output.fd.sync()
            }
            Files.move(
                temp.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
            true
        } catch (e: Exception) {
            temp.delete()
            runCatching { Log.w(TAG, "Could not write the restore journal", e) }
            false
        }
    }

    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }

    /** DAT-04: what one [recover] pass did, so the startup caller can log a failed transition. */
    sealed interface RecoverResult {
        /** No journal existed; the normal launch. */
        data object NoJournal : RecoverResult

        /** The journal was read, handled and removed. */
        data object Completed : RecoverResult

        /** The recorded database move never happened (or the journal was unreadable). */
        data object Discarded : RecoverResult

        /**
         * The database move landed but at least one required apply did not. The journal is kept
         * and the next start retries; [stage] names the half that failed for the log.
         *
         * R3-05: [missingStaging] is carried alongside the failure instead of being hidden by it.
         * A salt/deck failure and a gone attachment staging tree commonly happen together (full
         * disk), and the caller still has to run the D7 reconciliation for the missing media.
         */
        data class Failed(val stage: String, val missingStaging: String? = null) : RecoverResult

        /**
         * R2-09/P3: the journal named an attachment staging tree that no longer exists.
         *
         * The restored database is live and may reference bytes that were never installed. There is
         * nothing left to install, so the journal is removed, but the caller must run the D7
         * reconciliation instead of treating this as a clean completion.
         */
        data class MissingAttachments(val staging: String) : RecoverResult
    }

    /**
     * Finishes or discards a restore interrupted by a process kill.
     *
     * Runs from `App.onCreate` before any screen can observe the database. The hash decision is
     * deliberately byte-for-byte: restore closes Room and replaces the file in one atomic move, so
     * before the next Room open the live file is either the old database or exactly the staged one.
     *
     * DAT-04: the journal is deleted only when the move is confirmed **and** every apply returned
     * true. It used to be deleted unconditionally, so a salt commit that failed (storage full,
     * read-only prefs) took the only copy of the new salt with it and orphaned every restored
     * `v3:` row for good. A failed apply now keeps the journal, returns [RecoverResult.Failed] and
     * retries on the next start; the pending salt is never overwritten here.
     */
    fun recover(context: Context): RecoverResult = recoverWith(context)

    /**
     * [recover] with the two prefs writes injectable, so a failing vault store can be exercised
     * without a read-only SharedPreferences file. Production always uses the defaults.
     */
    internal fun recoverWith(
        context: Context,
        applySalt: ((Context, String?) -> Boolean)? = null,
        applyDeck: ((Context, String?) -> Boolean)? = null,
    ): RecoverResult {
        val saltApplier: (Context, String?) -> Boolean = applySalt ?: { target, encoded ->
            VaultSaltArchive.applyEncoded(target, encoded)
        }
        val deckApplier: (Context, String?) -> Boolean =
            applyDeck ?: { target, sealed -> restoreDeck(target, sealed) }
        val journal = file(context)
        if (!journal.isFile) return RecoverResult.NoJournal
        val pending = read(journal)
        if (pending == null) {
            runCatching { journal.delete() }
            return RecoverResult.Discarded
        }

        val liveDb = context.getDatabasePath(DatabaseConst.NOTES_DATABASE_FILE_NAME)
        // R5-01: the generation token is the primary identity. The hash can change under a live
        // database (a save, a checkpoint) and a stamp changes it by design; the token survives
        // both, so a completed move is no longer mistaken for one that never happened. Journals
        // from older builds have no token and keep the byte comparison they were written for.
        val liveToken = InstallGeneration.read(liveDb)
        val moved = when {
            pending.generationToken != null && liveToken != null ->
                pending.generationToken == liveToken
            // R6-03: a token-bearing journal whose live token cannot be read must not fall back
            // to a byte hash that a stamp or a later save can invalidate. Keep the journal and
            // retry on the next launch instead of discarding the deck/salt apply.
            pending.generationToken != null -> {
                runCatching {
                    Log.e(TAG, "Restore journal generation could not be read; keeping the journal")
                }
                return RecoverResult.Failed("database generation")
            }
            else -> SnapshotSidecar.sha256(liveDb) == pending.restoredDbSha256
        }
        var missingStaging: String? = null
        if (moved) {
            // REG-01: the restored database is live from this point, and a portable restore puts
            // plaintext rows/captions back on disk. The flags must be cleared *before* any apply
            // can fail: otherwise a failed salt/deck/attachment apply kept the journal but left
            // the install-wide "already sealed" markers set, and every later launch trusted them
            // against a database that was never swept. The journal is still kept for the retry,
            // but the next launch's normal backfill must run regardless of that retry's outcome.
            LegacyPlaintextBackfillFlag.reset(context)
            LegacyCaptionBackfillFlag.reset(context)
            // Every required apply runs before the decision: a failure in one half must not keep
            // the other from being finished, and the journal stays if any of them failed.
            var failedStage: String? = null
            if (pending.setSalt && !saltApplier(context, pending.newSalt)) {
                failedStage = "vault salt"
                runCatching {
                    Log.w(TAG, "Could not apply the restored vault salt; keeping the journal")
                }
            }
            // DAT-04: a deck the journal marks as required but cannot provide (the archive
            // carried one that could not be sealed before the move) is a failed apply, never the
            // undo's explicit "there was no deck" no-op. Applying it through restoreDeck would
            // clear the current deck as if the snapshot had none.
            val deckApplied = when {
                pending.deckRequired && pending.newSealedDeck == null -> false
                pending.setDeck -> deckApplier(context, pending.newSealedDeck)
                else -> true
            }
            if (!deckApplied) {
                if (failedStage == null) failedStage = "flashcard deck"
                runCatching {
                    Log.w(TAG, "Could not apply the restored flashcard deck; keeping the journal")
                }
            }
            if (pending.attachmentTarget != null &&
                !AttachmentSnapshots.install(context, pending.attachmentTarget)
            ) {
                if (failedStage == null) failedStage = "attachment tree"
                runCatching { Log.w(TAG, "Recovered attachment tree could not be installed") }
            }
            // R2-09: the database move happened, but the process died before the archive's own
            // attachments reached `filesDir`. Rebuild the plan from the staging tree - the live
            // database was already rewritten to point at exactly these names - and install it.
            // Only then may the staging tree be deleted; a failure keeps the journal for a retry.
            val staging = pending.attachmentStaging
            if (staging != null) {
                val stagingDir = stagingDirectoryWithinRoot(context, staging)
                if (stagingDir == null) {
                    // R2-09/P3: the journal names a tree that is gone (cleanup, manual wipe, a
                    // failed earlier launch). Nothing can be installed; the live database may
                    // reference bytes that never landed, so the caller must reconcile.
                    missingStaging = staging
                    runCatching {
                        Log.e(TAG, "Recorded attachment staging is gone; restored rows may be dangling")
                    }
                } else if (AttachmentArchive.hasStagedAttachments(stagingDir)) {
                    val installed = runCatching {
                        AttachmentArchive.installStagedAttachments(context, stagingDir)
                    }.onFailure { error ->
                        runCatching { Log.w(TAG, "Recovered attachment staging could not be installed", error) }
                    }.getOrNull()
                    if (installed == null) {
                        if (failedStage == null) failedStage = "attachment staging"
                    } else if (!AttachmentArchive.hasPendingInstall(context)) {
                        // The replay owns nothing any more; this tree is plaintext extraction state.
                        runCatching { stagingDir.deleteRecursively() }
                    }
                }
            }
            // N-6: `attachmentDiscard` is the pre-undo tree. In the normal undo it becomes the
            // *new* `.bak`'s paired tree, so deleting it here just because a kill landed after
            // rotation would break the next undo. The sidecar is the authority: keep the tree
            // when it names it, drop it only when it is genuinely unreferenced.
            val discard = pending.attachmentDiscard
            if (discard != null && !isCurrentBackupAttachment(context, discard)) {
                AttachmentSnapshots.delete(context, discard)
            }
            val missing = missingStaging
            val failure = failedStage
            if (failure != null) {
                // The restored database is live but at least one side store still describes the
                // previous state. Keeping the journal is the whole point: it is the only durable
                // record of what has to be applied before the next Room open. The backfill flags
                // were already cleared above, so the restored plaintext is swept on this launch
                // even while the journal keeps retrying. The missing staging tree rides along so
                // the caller still reconciles the media it could not install.
                return RecoverResult.Failed(failure, missing)
            }
        } else {
            // The atomic move never happened: the live database is still the old one, its
            // salt/deck are already the live prefs values, and the current attachment tree is
            // live. The redo snapshot captured for the failed attempt is not needed.
            AttachmentSnapshots.delete(context, pending.attachmentDiscard)
        }
        if (!runCatching { journal.delete() }.getOrDefault(false)) {
            // Completed work, but the record survives; the next start replays idempotently.
            runCatching { Log.w(TAG, "Could not remove the finished restore journal") }
        }
        val missingAfter = missingStaging
        if (moved && missingAfter != null) {
            return RecoverResult.MissingAttachments(missingAfter)
        }
        return if (moved) RecoverResult.Completed else RecoverResult.Discarded
    }

    /**
     * Applies a journal-sealed deck, including the "the snapshot had no deck" case.
     *
     * A deck this install cannot open is a failure rather than a silent keep: returning true here
     * would let the caller delete the journal while the restored database's cards are still the
     * old ones.
     */
    private fun restoreDeck(context: Context, sealed: String?): Boolean {
        if (sealed == null) return FlashcardArchive.restoreRaw(context, null)
        if (FlashcardArchive.openForRecovery(sealed) == null) {
            runCatching {
                Log.w(TAG, "Recovered flashcard deck could not be opened; keeping the current deck")
            }
            return false
        }
        return FlashcardArchive.restoreRaw(context, sealed)
    }

    /**
     * True when the sidecar beside the current `.bak` names [attachmentSnapshot].
     *
     * The undo rotates the redo tree into that sidecar before clearing its journal; a kill in
     * between leaves this as the only way to know the directory is still live state.
     */
    private fun isCurrentBackupAttachment(context: Context, attachmentSnapshot: String): Boolean {
        val liveDb = context.getDatabasePath(DatabaseConst.NOTES_DATABASE_FILE_NAME)
        val backup = DatabaseRestore.backupFile(liveDb)
        val contents = (SnapshotSidecar.read(backup) as? SnapshotSidecar.ReadResult.Found)
            ?.contents
        return contents?.attachmentSnapshot == attachmentSnapshot
    }

    private fun read(journal: File): Pending? = runCatching {
        val json = JsonParser.parseString(journal.readText(Charsets.UTF_8)).asJsonObject
        if (json.get(KEY_VERSION)?.asInt != VERSION) return@runCatching null
        val hash = json.stringOrNull(KEY_HASH) ?: return@runCatching null
        Pending(
            restoredDbSha256 = hash,
            newSalt = json.stringOrNull(KEY_SALT),
            setSalt = json.get(KEY_SET_SALT)?.asBoolean ?: (json.stringOrNull(KEY_SALT) != null),
            newSealedDeck = json.stringOrNull(KEY_DECK),
            setDeck = json.get(KEY_SET_DECK)?.asBoolean ?: (json.stringOrNull(KEY_DECK) != null),
            deckRequired = json.get(KEY_DECK_REQUIRED)?.asBoolean ?: false,
            attachmentTarget = json.stringOrNull(KEY_ATTACHMENTS),
            attachmentDiscard = json.stringOrNull(KEY_ATTACHMENTS_DISCARD),
            attachmentStaging = json.stringOrNull(KEY_ATTACHMENT_STAGING),
            generationToken = json.get(KEY_GENERATION)
                ?.takeUnless { it.isJsonNull }
                ?.asInt,
        )
    }.getOrNull()

    private fun JsonObject.stringOrNull(key: String): String? {
        val element = get(key) ?: return null
        return if (element.isJsonNull) null else element.asString
    }
}
