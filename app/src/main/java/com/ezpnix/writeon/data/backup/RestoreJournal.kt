package com.ezpnix.writeon.data.backup

import android.content.Context
import android.util.Log
import com.ezpnix.writeon.core.attachment.AttachmentSnapshots
import com.ezpnix.writeon.core.constant.DatabaseConst
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
 * old, correct pair and are left alone. The journal is deleted either way. It never holds plaintext
 * cards: [FlashcardArchive.sealForRecovery] seals them with the same Keystore key as the live deck.
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
    private const val KEY_ATTACHMENTS = "attachmentTarget"
    private const val KEY_ATTACHMENTS_DISCARD = "attachmentDiscard"

    private data class Pending(
        val restoredDbSha256: String,
        val newSalt: String?,
        val setSalt: Boolean,
        val newSealedDeck: String?,
        val setDeck: Boolean,
        val attachmentTarget: String?,
        val attachmentDiscard: String?,
    )

    fun file(context: Context): File = File(context.noBackupFilesDir, FILE_NAME)

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
        attachmentTarget: String? = null,
        attachmentDiscard: String? = null,
    ): Boolean {
        val hash = SnapshotSidecar.sha256(restoredDb) ?: return false
        val json = JsonObject().apply {
            addProperty(KEY_VERSION, VERSION)
            addProperty(KEY_HASH, hash)
            addProperty(KEY_SET_SALT, setSalt)
            addProperty(KEY_SET_DECK, setDeck)
            if (newSalt != null) addProperty(KEY_SALT, newSalt) else add(KEY_SALT, JsonNull.INSTANCE)
            if (newDeckSealed != null) {
                addProperty(KEY_DECK, newDeckSealed)
            } else {
                add(KEY_DECK, JsonNull.INSTANCE)
            }
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

    /**
     * Finishes or discards a restore interrupted by a process kill.
     *
     * Runs from `App.onCreate` before any screen can observe the database. The hash decision is
     * deliberately byte-for-byte: restore closes Room and replaces the file in one atomic move, so
     * before the next Room open the live file is either the old database or exactly the staged one.
     */
    fun recover(context: Context) {
        val journal = file(context)
        if (!journal.isFile) return
        val pending = read(journal)
        if (pending == null) {
            runCatching { journal.delete() }
            return
        }

        val liveDb = context.getDatabasePath(DatabaseConst.NOTES_DATABASE_FILE_NAME)
        val moved = SnapshotSidecar.sha256(liveDb) == pending.restoredDbSha256
        if (moved) {
            if (pending.setSalt) {
                VaultSaltArchive.applyEncoded(context, pending.newSalt)
            }
            if (pending.setDeck) {
                if (pending.newSealedDeck == null) {
                    // An authoritative "the snapshot had no deck" from the undo sidecar.
                    FlashcardArchive.restoreRaw(context, null)
                } else {
                    val json = FlashcardArchive.openForRecovery(pending.newSealedDeck)
                    if (json != null) {
                        FlashcardArchive.restoreRaw(context, pending.newSealedDeck)
                    } else {
                        runCatching {
                            Log.w(TAG, "Recovered flashcard deck could not be opened; keeping the current deck")
                        }
                    }
                }
            }
            if (pending.attachmentTarget != null &&
                !AttachmentSnapshots.install(context, pending.attachmentTarget)
            ) {
                runCatching { Log.w(TAG, "Recovered attachment tree could not be installed") }
            } else {
                AttachmentSnapshots.delete(context, pending.attachmentDiscard)
            }
            // The restored database may carry plaintext rows/captions again, exactly like the
            // normal path: the next launch must sweep it instead of trusting the replaced DB's
            // marker.
            LegacyPlaintextBackfillFlag.reset(context)
            LegacyCaptionBackfillFlag.reset(context)
        } else {
            // The atomic move never happened: the live database is still the old one, its
            // salt/deck are already the live prefs values, and the current attachment tree is
            // live. The redo snapshot captured for the failed attempt is not needed.
            AttachmentSnapshots.delete(context, pending.attachmentDiscard)
        }
        runCatching { journal.delete() }
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
            attachmentTarget = json.stringOrNull(KEY_ATTACHMENTS),
            attachmentDiscard = json.stringOrNull(KEY_ATTACHMENTS_DISCARD),
        )
    }.getOrNull()

    private fun JsonObject.stringOrNull(key: String): String? {
        val element = get(key) ?: return null
        return if (element.isJsonNull) null else element.asString
    }
}
