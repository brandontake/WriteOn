package com.ezpnix.writeon.data.backup

import android.content.Context
import android.util.Log
import com.ezpnix.writeon.core.security.SharedPreferencesVaultKeyStore
import com.ezpnix.writeon.core.security.VaultKeyStore
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * PERF-2 follow-up: carries the library-wide envelope salt through every portable archive.
 *
 * A `v3:` note is opened with a KEK derived from the passphrase and the salt in [VaultKeyStore].
 * The database travels inside the archive but the salt lives in `notes_prefs`, which Android's own
 * backup and device transfer deliberately exclude - so restoring the database on another install
 * left every envelope row undecryptable. This is the missing member, alongside [FlashcardArchive]:
 * **plain Base64 text** inside an archive that is already encrypted. A Keystore seal would be
 * unreadable on the destination device, exactly like a sealed flashcard deck.
 *
 * Missing is a supported no-op. Archives written before this member (and every pre-v3 library)
 * carry no envelope ciphertext, so keeping the destination's current salt is harmless; [restoreFromStaging]
 * only replaces it when the member is present and valid.
 */
object VaultSaltArchive {

    /** Archive member name. Absent in older archives; that is a supported no-op. */
    const val ENTRY_NAME = "vault-key-salt"

    private const val TAG = "VaultSaltArchive"

    /**
     * Adds [ENTRY_NAME] when this install has a salt. An untouched install writes nothing, which
     * keeps its archives byte-for-byte like the ones older builds produced.
     */
    fun writeToZip(context: Context, zip: ZipOutputStream) {
        writeSaltEntry(zip, SharedPreferencesVaultKeyStore(context).encodedSalt())
    }

    /** The zip half, split out so the entry format is testable without a Context. */
    internal fun writeSaltEntry(zip: ZipOutputStream, encoded: String?) {
        if (encoded == null) return
        zip.putNextEntry(ZipEntry(ENTRY_NAME))
        zip.write(encoded.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    /** The current encoded salt, captured before a restore so a rollback can put it back. */
    fun read(context: Context): String? = SharedPreferencesVaultKeyStore(context).encodedSalt()

    /**
     * The salt member extracted into [stagingDir], if the archive has one.
     *
     * Read-only: the normal restore still validates and applies it through [restoreFromStaging].
     * The restart journal uses this to remember which salt belongs to the database it is about to
     * install, so a kill between the move and the apply can be finished at startup.
     */
    fun readStaged(stagingDir: File): String? = runCatching {
        File(stagingDir, ENTRY_NAME)
            .takeIf { it.isFile }
            ?.readText(Charsets.UTF_8)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    /**
     * Structural check used before the journal/database swap, so an invalid member fails while the
     * live database is still untouched instead of relying on the post-swap rollback.
     */
    fun isValidEncoded(encoded: String): Boolean = runCatching {
        java.util.Base64.getDecoder().decode(encoded).size >= 8
    }.getOrDefault(false)

    /**
     * Applies a salt extracted from an archive.
     *
     * Returns true only when a valid value replaced the current salt. A missing member is a no-op
     * false; so is a malformed one, in which case the store is left exactly as it was so the caller
     * can roll the database swap back instead of installing a `v3:` database under the wrong KEK.
     */
    fun restoreFromStaging(context: Context, stagingDir: File): Boolean =
        restoreFromStaging(stagingDir, SharedPreferencesVaultKeyStore(context))

    internal fun restoreFromStaging(stagingDir: File, store: VaultKeyStore): Boolean {
        val staged = File(stagingDir, ENTRY_NAME)
        if (!staged.isFile) return false

        val encoded = try {
            staged.readText(Charsets.UTF_8).trim()
        } catch (e: Exception) {
            // Log is a stub on the plain-JVM test runtime; a failed log must not turn a rejected
            // salt into a crash on the restore path.
            runCatching {
                Log.w(TAG, "Could not read the archived vault salt; keeping the current salt", e)
            }
            return false
        }
        if (encoded.isEmpty()) return false

        val applied = store.setEncodedSalt(encoded)
        if (!applied) {
            runCatching {
                Log.w(TAG, "Archived vault salt is not a valid salt; keeping the current salt")
            }
        }
        return applied
    }

    /**
     * Applies an exact prefs value, including "there was no salt".
     *
     * This is the strict variant the snapshot undo needs: a null salt is a real state to restore,
     * not a missing member, and a failed commit must be reported instead of leaving the database
     * and the salt describing different KEKs.
     */
    fun applyEncoded(context: Context, encoded: String?): Boolean =
        applyEncoded(SharedPreferencesVaultKeyStore(context), encoded)

    internal fun applyEncoded(store: VaultKeyStore, encoded: String?): Boolean =
        if (encoded == null) store.clearSalt() else store.setEncodedSalt(encoded)

    /**
     * Puts the salt captured before a restore back, so the rolled-back database and the live salt
     * describe the same KEK.
     *
     * A null [old] clears the salt: that install had never written one, and leaving the archive's
     * value behind would make its plaintext rows look envelope-sealed to the next session.
     */
    fun restoreEncoded(context: Context, old: String?) {
        restoreEncoded(SharedPreferencesVaultKeyStore(context), old)
    }

    internal fun restoreEncoded(store: VaultKeyStore, old: String?) {
        applyEncoded(store, old)
    }
}
