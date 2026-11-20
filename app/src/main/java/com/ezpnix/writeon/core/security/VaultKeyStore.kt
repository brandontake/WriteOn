package com.ezpnix.writeon.core.security

import android.content.Context
import android.util.Base64

/**
 * Persistence for the library-wide envelope salt (PERF-2).
 *
 * The vault used to carry a random salt inside every ciphertext, which is why unlocking N notes
 * cost N PBKDF2 derivations. The envelope layout has one salt for the whole library instead, and
 * it has to survive process death: the KEK derived from the passphrase is only reproducible while
 * that salt exists. A store that forgets it silently turns every `v3:` row into garbage.
 *
 * Deliberately tiny and Android-free at the interface: [EncryptionHelper] can be exercised on a
 * plain JVM, and the app binds the SharedPreferences-backed implementation in `DataModule`.
 */
interface VaultKeyStore {
    /** The persisted salt, or null before the first vault write on this install. */
    fun salt(): ByteArray?

    /** Persists [salt] so every later session derives the same KEK. */
    fun setSalt(salt: ByteArray)

    /**
     * PERF-2: the salt in the portable form an archive carries.
     *
     * `java.util.Base64` rather than `android.util.Base64` so the archive layer and its JVM tests
     * agree on the exact text. Both encoders are no-wrap standard Base64, so this remains the
     * same bytes [SharedPreferencesVaultKeyStore] already stores.
     */
    fun encodedSalt(): String? = salt()?.let { java.util.Base64.getEncoder().encodeToString(it) }

    /**
     * Applies a salt read from an archive.
     *
     * A malformed or truncated value must never replace a working salt: the only copy of every
     * `v3:` row's KEK would be replaced by something no session can reproduce. The 8-byte floor
     * rules out a shortened prefix while still accepting the 16 bytes this app writes.
     *
     * @return true only when [setSalt] accepted a decodable, long-enough value.
     */
    fun setEncodedSalt(encoded: String): Boolean {
        val decoded = try {
            java.util.Base64.getDecoder().decode(encoded)
        } catch (e: IllegalArgumentException) {
            return false
        }
        if (decoded.size < MIN_ENCODED_SALT_BYTES) return false
        setSalt(decoded)
        return true
    }

    /**
     * Removes the persisted salt, for the rollback half of a restore.
     *
     * When the database swap is undone the salt has to be undone with it: a previous install that
     * had never written a salt must not be left holding the archive's, or its plaintext rows would
     * look envelope-sealed to the next session.
     *
     * @return false only when a persistent store could not commit the removal. Callers that are
     *   restoring a snapshot treat that as a failed undo; in-memory stores always succeed.
     */
    fun clearSalt(): Boolean
}

/** The shortest payload that can still be a real KDF salt rather than a truncated prefix. */
private const val MIN_ENCODED_SALT_BYTES = 8

/**
 * Android implementation: the same `notes_prefs` file the rest of the app already uses.
 *
 * `backup_rules.xml` and `data_extraction_rules.xml` exclude the whole `sharedpref` domain, so this
 * salt never reaches cloud backup or a device transfer - and neither does the database, which is
 * excluded from those paths too. The app's own portable backup therefore carries the salt as an
 * explicit archive member (`data.backup.VaultSaltArchive`) next to the database; a `v3:` row
 * restored on another device stays readable only because that member travels with it.
 */
class SharedPreferencesVaultKeyStore(context: Context) : VaultKeyStore {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun salt(): ByteArray? {
        val encoded = prefs.getString(KEY_ENVELOPE_SALT, null) ?: return null
        // A corrupt entry is reported as "no salt" instead of throwing: the read path then fails
        // closed with INVALID_DATA, which hides the rows rather than crashing the note list.
        return try {
            Base64.decode(encoded, Base64.NO_WRAP)
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    override fun setSalt(salt: ByteArray) {
        // commit(), not apply(): the first vault write derives its KEK from this salt, and an
        // async write that loses the race with process death would leave rows no later session can
        // open. This runs once per install, so the blocking disk write is not on a hot path.
        val stored = prefs.edit()
            .putString(KEY_ENVELOPE_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .commit()
        // Sealing a note under a salt that was never written is permanent data loss, so the save
        // has to fail here rather than succeed into an unreadable row.
        check(stored) { "Could not persist the vault envelope salt" }
    }

    /**
     * The restore path's variant: false instead of an exception.
     *
     * A failed commit here means the archive's salt is not live, so the caller must roll its
     * database swap back rather than report a restore that cannot be opened. `commit()` for the
     * same reason as [setSalt]: the cloud path reports success (and restarts the process) right
     * after this call, and an `apply()` lost to that kill would leave no salt behind.
     */
    override fun setEncodedSalt(encoded: String): Boolean {
        val decoded = try {
            java.util.Base64.getDecoder().decode(encoded)
        } catch (e: IllegalArgumentException) {
            return false
        }
        if (decoded.size < MIN_ENCODED_SALT_BYTES) return false
        return prefs.edit()
            .putString(KEY_ENVELOPE_SALT, Base64.encodeToString(decoded, Base64.NO_WRAP))
            .commit()
    }

    override fun clearSalt(): Boolean {
        // commit(): the rollback that calls this is already on the restore path's IO thread, and
        // it is immediately followed by putting the old database back. A lost clear would leave a
        // salt the restored plaintext install never had.
        return prefs.edit().remove(KEY_ENVELOPE_SALT).commit()
    }

    private companion object {
        const val PREFS_NAME = "notes_prefs"
        const val KEY_ENVELOPE_SALT = "vault_envelope_salt"
    }
}

/**
 * Non-persistent store for JVM tests and the constructor default.
 *
 * Every instance starts empty, which is exactly what an untouched install looks like. Tests that
 * need two helper instances to agree - "unlock in a later session still decrypts" - pass the same
 * instance to both. Never bind this in the app: process death would lose the salt and with it every
 * `v3:` note.
 */
class InMemoryVaultKeyStore(initialSalt: ByteArray? = null) : VaultKeyStore {

    private var storedSalt: ByteArray? = initialSalt?.copyOf()

    @Synchronized
    override fun salt(): ByteArray? = storedSalt?.copyOf()

    @Synchronized
    override fun setSalt(salt: ByteArray) {
        storedSalt = salt.copyOf()
    }

    @Synchronized
    override fun clearSalt(): Boolean {
        storedSalt = null
        return true
    }
}
