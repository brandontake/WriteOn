package com.ezpnix.writeon.core.security

// java.util.Base64, not android.util.Base64: the encoder/decoder pair behaves identically for
// the NO_WRAP calls below (standard alphabet, padding, no line breaks) and is what lets this
// class run under plain JVM unit tests instead of only on a device (T2).
import java.util.Base64
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.LinkedHashMap
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Vault encryption for individual notes.
 *
 * Ciphertext layouts:
 *  - current (envelope): `v3:<b64 wrapIv>:<b64 wrappedDek>:<b64 bodyIv>:<b64 body+tag>`
 *  - previous: `v2:<b64 salt>:<b64 iv>:<b64 ciphertext+tag>`
 *  - legacy:  `<b64 iv>:<b64 ciphertext+tag>`
 *
 * PERF-2: the `v2` layout embedded a fresh random salt in every row, so unlocking a library of N
 * vault notes cost N PBKDF2 derivations of 120,000 iterations each - tens of seconds on a
 * mid-range phone for a few hundred notes, and the 512-entry key cache only postponed the cost
 * past that size. The envelope layout pays the expensive derivation once per session: the
 * passphrase unlocks one library-wide KEK, and each note carries its own random DEK that is
 * wrapped with that KEK. Unlocking N notes is now one PBKDF2 plus N body decrypts and N small DEK
 * unwraps, all AES-GCM and all cheap.
 *
 * The salt for that KEK is random per library, not per note, and persists through [VaultKeyStore]
 * (SharedPreferences in the app). Losing it is not recoverable: [decrypt] reports INVALID_DATA for
 * a `v3:` value with no stored salt instead of throwing, but there is nothing to derive the KEK
 * from. That is why the store commits synchronously and why the salt does not travel with a cloud
 * backup (the database is excluded there as well).
 *
 * The legacy layout derived its AES key with a single SHA-256 pass over the password. That is fast,
 * which is the only thing it had going for it: a hash of a short user passphrase is exhaustible in
 * practice, so anyone holding the database file could recover it offline. `v2` raised that to
 * PBKDF2 with a per-note salt; `v3` keeps PBKDF2 (same iteration count) but derives a KEK once.
 *
 * Old rows are upgraded lazily, never on read: [decrypt] only reads `v2`/legacy, and no list
 * emission writes to the database. The next successful save of that note goes through [encrypt],
 * which always seals with `v3`, so opening and saving an old note is the upgrade.
 * [needsEnvelopeMigration] and [isLegacyFormat] expose the check for callers that need to know
 * whether a row is still waiting for that save without forcing one; `NoteUseCase`'s unlock sweep
 * uses the former to re-seal the whole library once per unlock.
 */
class EncryptionHelper(
    private val mutableVaultPassword: StringBuilder,
    // The app binds the SharedPreferences-backed store; the default keeps the constructor usable
    // from JVM tests and one-off previews without dragging a Context in.
    private val keyStore: VaultKeyStore = InMemoryVaultKeyStore(),
) {

    /**
     * How many PBKDF2 derivations this instance has performed (PERF-2 test seam).
     *
     * The whole point of the envelope layout is that this stays at 1 for a session no matter how
     * many notes are encrypted or decrypted. A test can assert that directly instead of timing a
     * KDF. Internal: it is diagnostic surface, not part of the vault API.
     */
    internal var pbkdf2Derivations: Int = 0
        private set

    fun isPasswordEmpty(): Boolean {
        return mutableVaultPassword.isEmpty()
    }

    fun removePassword() {
        mutableVaultPassword.setLength(0)
        clearDerivedKeys()
    }

    fun setPassword(newPassword: String) {
        mutableVaultPassword.setLength(0)
        mutableVaultPassword.append(newPassword)
        clearDerivedKeys()
    }

    /**
     * Seals [data] with the currently loaded vault passphrase.
     *
     * Always the `v3` envelope: one 32-byte DEK encrypts the body, and the library KEK encrypts the
     * DEK. A fresh DEK and fresh IVs per call mean two saves of identical text never produce
     * identical ciphertext, and a leaked DEK opens exactly one note body.
     *
     * Fails fast when no passphrase is loaded. Encrypting with an empty passphrase used to succeed
     * silently and produce a value [decrypt] can never read back, because every read short-circuits
     * to [DecryptionResult.LOADING] while the vault is empty - the note simply vanished from the
     * list. Callers must check [isPasswordEmpty] first and keep the note in plain text instead.
     */
    fun encrypt(data: String): String {
        check(!mutableVaultPassword.isEmpty()) {
            "Refusing to encrypt with an empty vault passphrase"
        }
        // Salt first: on the very first vault write this persists the salt the KEK is derived
        // from, and the row is only written after that value is durable.
        val kek = envelopeKey(envelopeSalt())
        val dek = SecretKeySpec(newBytes(DEK_BYTES), "AES")
        val bodyIv = newBytes(IV_BYTES)
        val bodyCipher = gcm(Cipher.ENCRYPT_MODE, dek, bodyIv, data.toByteArray(StandardCharsets.UTF_8))
        val wrapIv = newBytes(IV_BYTES)
        val wrappedDek = gcm(Cipher.ENCRYPT_MODE, kek, wrapIv, dek.encoded)
        return listOf(
            ENVELOPE_PREFIX,
            Base64.getEncoder().encodeToString(wrapIv),
            Base64.getEncoder().encodeToString(wrappedDek),
            Base64.getEncoder().encodeToString(bodyIv),
            Base64.getEncoder().encodeToString(bodyCipher),
        ).joinToString(SEPARATOR)
    }

    fun decrypt(data: String): Pair<String?, DecryptionResult> {
        if (mutableVaultPassword.isEmpty()) return Pair(null, DecryptionResult.LOADING)
        if (data.isBlank()) return Pair(null, DecryptionResult.BLANK_DATA)

        return try {
            val parts = data.split(SEPARATOR)
            when (parts.firstOrNull()) {
                ENVELOPE_PREFIX -> decryptEnvelope(parts)
                FORMAT_PREFIX -> decryptV2(parts)
                else -> decryptLegacy(parts)
            }
        } catch (e: Exception) {
            // A GCM tag failure under the wrong passphrase (or a truncated row) lands here. Never
            // propagate: the vault lock screen reports a password problem instead of crashing.
            Pair(null, DecryptionResult.BAD_PASSWORD)
        }
    }

    /**
     * `v3` read path: salt -> KEK -> unwrap DEK -> open body.
     *
     * A missing salt is INVALID_DATA, not an exception and not BAD_PASSWORD: no passphrase can fix
     * it, and reporting it as a password error would send the user retyping forever.
     */
    private fun decryptEnvelope(parts: List<String>): Pair<String?, DecryptionResult> {
        if (parts.size != ENVELOPE_PARTS) return Pair(null, DecryptionResult.INVALID_DATA)
        val salt = keyStore.salt() ?: return Pair(null, DecryptionResult.INVALID_DATA)
        val wrapIv = Base64.getDecoder().decode(parts[1])
        val wrappedDek = Base64.getDecoder().decode(parts[2])
        val bodyIv = Base64.getDecoder().decode(parts[3])
        val bodyCipher = Base64.getDecoder().decode(parts[4])
        val dek = SecretKeySpec(gcm(Cipher.DECRYPT_MODE, envelopeKey(salt), wrapIv, wrappedDek), "AES")
        val plain = gcm(Cipher.DECRYPT_MODE, dek, bodyIv, bodyCipher)
        return Pair(String(plain, StandardCharsets.UTF_8), DecryptionResult.SUCCESS)
    }

    /** `v2` read path, unchanged: per-row salt, one PBKDF2 per distinct salt via the cache. */
    private fun decryptV2(parts: List<String>): Pair<String?, DecryptionResult> {
        if (parts.size != V2_PARTS) return Pair(null, DecryptionResult.INVALID_DATA)
        val salt = Base64.getDecoder().decode(parts[1])
        val ivBytes = Base64.getDecoder().decode(parts[2])
        val encryptedBytes = Base64.getDecoder().decode(parts[3])
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            deriveKey(salt),
            GCMParameterSpec(TAG_LENGTH_BITS, ivBytes)
        )
        return Pair(String(cipher.doFinal(encryptedBytes), StandardCharsets.UTF_8), DecryptionResult.SUCCESS)
    }

    private fun decryptLegacy(parts: List<String>): Pair<String?, DecryptionResult> {
        // Notes written before the PBKDF2 change. `Base64.DEFAULT` wrapped the original output
        // across lines, so the surrounding whitespace has to go before decoding.
        if (parts.size != 2) return Pair(null, DecryptionResult.INVALID_DATA)
        val ivBytes = Base64.getDecoder().decode(parts[0].filterNot { it.isWhitespace() })
        val encryptedBytes = Base64.getDecoder().decode(parts[1].filterNot { it.isWhitespace() })
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            deriveLegacyKey(mutableVaultPassword.toString()),
            GCMParameterSpec(TAG_LENGTH_BITS, ivBytes)
        )
        return Pair(String(cipher.doFinal(encryptedBytes), StandardCharsets.UTF_8), DecryptionResult.SUCCESS)
    }

    /** True when [stored] is a `v3:` envelope value. Pure marker check; never decrypts or writes. */
    fun isEnvelopeFormat(stored: String): Boolean =
        stored.startsWith("$ENVELOPE_PREFIX$SEPARATOR")

    /**
     * True when [stored] is a vault value that still needs re-sealing into the `v3` envelope:
     * anything sealed by an earlier build (`v2:` or the pre-PBKDF2 shape).
     *
     * Pure marker check, like [isLegacyFormat]: no decrypt, no cache touch, no write. Callers must
     * not use this from a list read to trigger a re-encryption there - that would rewrite the whole
     * library on every emission. The unlock sweep is the one place it belongs.
     */
    fun needsEnvelopeMigration(stored: String): Boolean =
        stored.isNotBlank() && !isEnvelopeFormat(stored)

    /**
     * True when [stored] is the pre-PBKDF2 shape (`<b64 iv>:<b64 ciphertext>`, one SHA-256 pass for
     * the key) - or is not a sealed vault value at all.
     *
     * Pure marker check. Kept for callers that ask the narrow question; the vault sweep uses
     * [needsEnvelopeMigration], which also covers the `v2:` rows the envelope upgrade has to
     * re-seal. A `v3:` value is not legacy, so this is not simply "anything not `v2:`" any more.
     */
    fun isLegacyFormat(stored: String): Boolean {
        if (stored.isBlank()) return false
        return !isEnvelopeFormat(stored) && !stored.startsWith("$FORMAT_PREFIX$SEPARATOR")
    }

    private fun newBytes(size: Int): ByteArray =
        ByteArray(size).apply { SecureRandom().nextBytes(this) }

    /**
     * The library salt: persisted on first use, then reused by every session.
     *
     * Deriving the KEK from a fresh salt while a second salt is being persisted would leave the
     * first note sealed under a key nothing can reproduce, so salt creation and the KEK lookup are
     * serialized on this instance.
     */
    @Synchronized
    private fun envelopeSalt(): ByteArray {
        keyStore.salt()?.let { return it }
        val salt = newBytes(SALT_BYTES)
        keyStore.setSalt(salt)
        return salt
    }

    /**
     * The KEK for the current passphrase, derived once and kept for the session.
     *
     * Cleared by [setPassword] and [removePassword], so a locked vault holds no key material and a
     * password change can never keep opening rows under the old one.
     */
    @Synchronized
    private fun envelopeKey(salt: ByteArray): SecretKey {
        val cached = cachedKek
        val cachedSalt = cachedKekSalt
        if (cached != null && cachedSalt != null && cachedSalt.contentEquals(salt)) return cached
        val key = pbkdf2(mutableVaultPassword.toString(), salt)
        cachedKek = key
        cachedKekSalt = salt
        return key
    }

    private fun gcm(mode: Int, key: SecretKey, iv: ByteArray, input: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(mode, key, GCMParameterSpec(TAG_LENGTH_BITS, iv))
        return cipher.doFinal(input)
    }

    private fun clearDerivedKeys() {
        // The v2 cache is shared by every helper in the process and the unlock sweep now decrypts
        // on IO while the list collector decrypts on Default, so the map needs the guard.
        synchronized(derivedKeyCacheLock) { derivedKeys.clear() }
        cachedKek = null
        cachedKekSalt = null
    }

    /**
     * Derives the per-note AES key used by the `v2` read path.
     *
     * PBKDF2 is deliberately expensive, and every `v2` note carries its own salt, so the results are
     * kept in a small LRU cache keyed by salt. Decrypting a screen full of pre-envelope notes
     * otherwise pays the full derivation cost once per note on every keystroke of the note list.
     * New writes never come through here.
     */
    private fun deriveKey(salt: ByteArray): SecretKey {
        val cacheKey = salt.toList()
        synchronized(derivedKeyCacheLock) {
            derivedKeys[cacheKey]?.let { return it }
        }

        val key = pbkdf2(mutableVaultPassword.toString(), salt)
        synchronized(derivedKeyCacheLock) { derivedKeys[cacheKey] = key }
        return key
    }

    private fun pbkdf2(password: String, salt: ByteArray): SecretKey {
        pbkdf2Derivations++
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_SIZE_BITS)
        return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
    }

    /** The pre-PBKDF2 key derivation, kept only to read notes written by earlier versions. */
    private fun deriveLegacyKey(password: String): SecretKey {
        val sha256 = MessageDigest.getInstance("SHA-256")
        return SecretKeySpec(sha256.digest(password.toByteArray(StandardCharsets.UTF_8)), "AES")
    }

    /** Session KEK cache. Instance state, unlike [derivedKeys]: a KEK is only valid for this helper. */
    private var cachedKek: SecretKey? = null
    private var cachedKekSalt: ByteArray? = null

    private companion object {
        const val ENVELOPE_PREFIX = "v3"
        const val FORMAT_PREFIX = "v2"
        const val SEPARATOR = ":"
        const val ENVELOPE_PARTS = 5
        const val V2_PARTS = 4
        const val SALT_BYTES = 16
        const val DEK_BYTES = 32
        const val IV_BYTES = 12
        const val TAG_LENGTH_BITS = 128
        const val KEY_SIZE_BITS = 256

        /**
         * Iterations for the vault KEK.
         *
         * Lower than the screen lock's 600_000 because the same KDF output is reused for the whole
         * library instead of being paid per note; 120,000 is the OWASP floor for
         * PBKDF2-HMAC-SHA256 and is still far beyond what a single SHA-256 pass gives an attacker.
         */
        const val PBKDF2_ITERATIONS = 120_000

        val derivedKeys = object : LinkedHashMap<List<Byte>, SecretKey>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<List<Byte>, SecretKey>): Boolean =
                size > MAX_CACHED_KEYS
        }

        /** Guards [derivedKeys]; see [deriveKey] for why the map is touched from several threads. */
        val derivedKeyCacheLock = Any()

        /**
         * Upper bound on the per-salt derived-key cache.
         *
         * This now only serves the `v2` read path while a library is being migrated, but the
         * reasoning still holds: a library larger than the cap would pay the full 120k-iteration
         * derivation again for every old note that scrolled out of the window. 512 entries hold the
         * working set of a large library; a [SecretKey] is a few dozen bytes plus the boxed salt
         * key, so even a full cache stays well below a megabyte. The map is access-ordered, so a
         * library beyond that still evicts the least recently used derivation instead of growing
         * without bound.
         */
        const val MAX_CACHED_KEYS = 512
    }
}

enum class DecryptionResult {
    EMPTY,
    SUCCESS,
    INVALID_DATA,
    BLANK_DATA,
    BAD_PASSWORD,
    LOADING
}
