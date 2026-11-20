package com.ezpnix.writeon.data.backup

import android.util.Log
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * N-1/N-2: the Vault salt and flashcard deck that belong to one `.bak` database snapshot.
 *
 * The note database is not self-contained any more. A `v3:` row needs the library-wide salt in
 * `notes_prefs`, and the flashcard deck lives in `flashcards_prefs`; neither travels with the
 * `.bak` file that [com.ezpnix.writeon.core.util.DatabaseRestore] keeps beside the live database.
 * Restoring a cross-device archive replaces the salt (and may replace the deck), so the old
 * database in `.bak` and the new prefs no longer describe the same library. Pressing "restore the
 * previous database" then puts the old rows back under the wrong KEK - the safety net itself
 * becomes the data-loss path.
 *
 * This sidecar is written next to `.bak` (`<db>.bak.snapshot`) at the moment the backup is made,
 * so the pair always travels together inside the app. It is **not** an archive member and never
 * leaves the install: the `databases/` domain is excluded from Android backup and device transfer,
 * and the values it stores are the same app-private prefs strings the app already holds.
 *
 * The `dbSha256` stamp is what makes a sidecar safe to use. A kill between the database copy and
 * the sidecar write (or between rotating `.bak` and rewriting the sidecar) leaves a file whose
 * hash does not match the database beside it. [read] reports that as [ReadResult.Mismatch] instead
 * of handing the wrong salt to a restore, so the caller can refuse rather than misdecrypt every
 * `v3:` row.
 */
object SnapshotSidecar {

    /** Sits beside `<db>.bak`; the suffix is ours, so it can never collide with SQLite's. */
    const val SUFFIX = ".snapshot"

    private const val TAG = "SnapshotSidecar"
    private const val VERSION = 1
    private const val KEY_VERSION = "version"
    private const val KEY_HASH = "dbSha256"
    private const val KEY_SALT = "vaultSalt"
    private const val KEY_FLASHCARDS = "flashcards"
    private const val KEY_ATTACHMENTS = "attachmentSnapshot"

    /**
     * The prefs and media state as it was when the snapshot database was live.
     *
     * A null salt/deck is authoritative, not "unknown": [write] always records both fields, so a
     * valid sidecar from a fresh install says "this snapshot had no salt/deck" and the undo must
     * clear whatever the restored archive put there. "Unknown" is represented by no sidecar at all.
     *
     * [attachmentSnapshot] names a directory under
     * [com.ezpnix.writeon.core.attachment.AttachmentSnapshots]. Null means the snapshot predates
     * attachment pairing (or the database had no previous install); the undo then restores only
     * the database and prefs, exactly as before.
     */
    data class Contents(
        val vaultSalt: String?,
        val flashcards: String?,
        val attachmentSnapshot: String? = null,
    )

    sealed class ReadResult {
        data class Found(val contents: Contents) : ReadResult()

        /** No sidecar: a snapshot written before this feature, or a fresh install. */
        object Missing : ReadResult()

        /** The file exists but is corrupt or describes different database bytes. */
        object Mismatch : ReadResult()
    }

    fun fileFor(backup: File): File = File(backup.absolutePath + SUFFIX)

    /**
     * Records [vaultSalt] and [flashcards] as the state belonging to [backup].
     *
     * Returns true when there is nothing to pair (a restore that had no previous database), or
     * when the metadata reached disk. The caller decides whether a false result should abort a
     * restore; it must not proceed while trusting the old sidecar.
     */
    fun write(
        backup: File,
        vaultSalt: String?,
        flashcards: String?,
        attachmentSnapshot: String? = null,
    ): Boolean {
        // A restore on a fresh install legitimately has no `.bak`; there is no undo to protect.
        if (!backup.isFile || backup.length() <= 0L) return true

        val encoded = JsonObject().apply {
            addProperty(KEY_VERSION, VERSION)
            addProperty(KEY_HASH, sha256(backup) ?: return false)
            if (vaultSalt != null) addProperty(KEY_SALT, vaultSalt) else add(KEY_SALT, JsonNull.INSTANCE)
            if (flashcards != null) {
                addProperty(KEY_FLASHCARDS, flashcards)
            } else {
                add(KEY_FLASHCARDS, JsonNull.INSTANCE)
            }
            if (attachmentSnapshot != null) {
                addProperty(KEY_ATTACHMENTS, attachmentSnapshot)
            } else {
                add(KEY_ATTACHMENTS, JsonNull.INSTANCE)
            }
        }.toString().toByteArray(Charsets.UTF_8)

        val target = fileFor(backup)
        val temp = File(target.absolutePath + ".tmp")
        return try {
            FileOutputStream(temp).use { output ->
                output.write(encoded)
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
            runCatching { Log.w(TAG, "Could not record the snapshot sidecar", e) }
            false
        }
    }

    fun read(backup: File): ReadResult {
        val sidecar = fileFor(backup)
        if (!sidecar.isFile) return ReadResult.Missing

        return try {
            val json = JsonParser.parseString(sidecar.readText(Charsets.UTF_8)).asJsonObject
            val version = json.get(KEY_VERSION)?.takeIf { !it.isJsonNull }?.asInt
            val recordedHash = json.get(KEY_HASH)?.takeIf { !it.isJsonNull }?.asString
            val actualHash = sha256(backup)
            if (version != VERSION || recordedHash == null || actualHash == null || recordedHash != actualHash) {
                ReadResult.Mismatch
            } else {
                ReadResult.Found(
                    Contents(
                        vaultSalt = json.stringOrNull(KEY_SALT),
                        flashcards = json.stringOrNull(KEY_FLASHCARDS),
                        attachmentSnapshot = json.stringOrNull(KEY_ATTACHMENTS),
                    )
                )
            }
        } catch (e: Exception) {
            runCatching { Log.w(TAG, "Could not read the snapshot sidecar", e) }
            ReadResult.Mismatch
        }
    }

    /** Best-effort cleanup when a restore fails before `.bak` was ever rewritten. */
    fun delete(backup: File) {
        fileFor(backup).delete()
    }

    private fun JsonObject.stringOrNull(key: String): String? {
        val element = get(key) ?: return null
        return if (element.isJsonNull) null else element.asString
    }

    /** SHA-256 of the whole database file, or null when it cannot be read. */
    internal fun sha256(file: File): String? = try {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    } catch (e: Exception) {
        runCatching { Log.w(TAG, "Could not hash ${file.name}", e) }
        null
    }
}
