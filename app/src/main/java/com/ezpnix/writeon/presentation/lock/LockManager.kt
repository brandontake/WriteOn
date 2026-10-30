// androidx.security:security-crypto:1.1.0 deprecates EncryptedSharedPreferences/MasterKey as a
// whole and the project pinned that exact API, so the unavoidable deprecation is silenced here
// rather than spreading it through the lock UI.
@file:Suppress("DEPRECATION")

package com.ezpnix.writeon.presentation.lock

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for the app lock (biometric / PIN / pattern).
 *
 * The PIN and the pattern are never stored in plaintext and never leave this class: each credential
 * is stretched with PBKDF2WithHmacSHA256 over a fresh 16 byte [SecureRandom] salt, and only the
 * salt plus the derived key (base64) are persisted inside [EncryptedSharedPreferences].
 *
 * There is intentionally no recovery path: if the user forgets the credential, clearing the app
 * data is the only way back in. Do not add a reset/backdoor escape hatch here.
 */
@Singleton
class LockManager @Inject constructor(@ApplicationContext private val context: Context) {

    /**
     * Encrypted lock storage with a last-resort fallback.
     *
     * The master key lives in the device Keystore. After a device transfer, a restored auto backup
     * or a Keystore reset, the preferences file is present but cannot be decrypted;
     * EncryptedSharedPreferences.create used to throw here, and because this happens during
     * ViewModel construction the app crashed on every launch. The unreadable file is deleted and
     * recreated first; if even that fails, a separate plain-preferences file keeps the app usable.
     * That fallback still stores only PBKDF2 hashes and salts, never the PIN or pattern itself.
     */
    private val prefs: SharedPreferences by lazy {
        encryptedPreferences() ?: fallbackPreferences()
    }

    private fun encryptedPreferences(): SharedPreferences? {
        createEncryptedPreferences()?.let { return it }

        Log.e(TAG, "Lock preferences could not be decrypted; resetting them")
        context.deleteSharedPreferences(PREFS_NAME)
        return createEncryptedPreferences().also {
            if (it == null) Log.e(TAG, "Encrypted lock storage unavailable; using fallback prefs")
        }
    }

    private fun createEncryptedPreferences(): SharedPreferences? = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Exception) {
        Log.e(TAG, "Could not open encrypted lock preferences", e)
        null
    }

    private fun fallbackPreferences(): SharedPreferences =
        context.getSharedPreferences(FALLBACK_PREFS_NAME, Context.MODE_PRIVATE)

    private val random = SecureRandom()

    private val _biometricEnabled = MutableStateFlow(prefs.getBoolean(KEY_BIOMETRIC, false))
    val biometricEnabled: StateFlow<Boolean> = _biometricEnabled.asStateFlow()

    private val _pinEnabled = MutableStateFlow(prefs.getString(KEY_PIN_HASH, null) != null)
    val pinEnabled: StateFlow<Boolean> = _pinEnabled.asStateFlow()

    private val _patternEnabled = MutableStateFlow(prefs.getString(KEY_PATTERN_HASH, null) != null)
    val patternEnabled: StateFlow<Boolean> = _patternEnabled.asStateFlow()

    /**
     * Elapsed-realtime deadline of the current lockout, 0 when not locked out.
     *
     * [android.os.SystemClock.elapsedRealtime] is monotonic and unaffected by the user changing the
     * clock, which the old `System.currentTimeMillis()` deadline was not: setting the clock forward
     * skipped any lockout instantly, turning a four-digit PIN's 10,000 combinations into an
     * afternoon of guessing.
     *
     * It is also *persisted* (see [restoreLockoutFromPrefs]) so killing the process does not reset
     * it. An in-memory-only deadline meant a force-stop handed out five free tries on every cold
     * start, which is cheaper to exploit than changing the clock.
     */
    private var lockoutDeadlineElapsed = 0L

    /** True while the lock screen must cover the app; also true on a cold start if a lock is set. */
    private val _isLocked = MutableStateFlow(anyLockEnabled())
    val isLocked: StateFlow<Boolean> = _isLocked.asStateFlow()

    private val _lockoutUntil = MutableStateFlow(0L)
    val lockoutUntil: StateFlow<Long> = _lockoutUntil.asStateFlow()

    init {
        restoreLockoutFromPrefs()
    }

    /** Monotonic timestamp of the last ON_STOP and whether the app was unlocked at that moment. */
    private var backgroundedAt = 0L
    private var wasUnlockedInBackground = false

    fun anyLockEnabled(): Boolean =
        _biometricEnabled.value || _pinEnabled.value || _patternEnabled.value

    // region biometric

    fun setBiometricEnabled(enabled: Boolean) {
        _biometricEnabled.value = enabled
        prefs.edit().putBoolean(KEY_BIOMETRIC, enabled).apply()
    }

    // endregion

    // region pin

    fun setPin(pin: String) {
        val salt = newSalt()
        prefs.edit()
            .putString(KEY_PIN_SALT, encode(salt))
            .putString(KEY_PIN_HASH, encode(hash(pin, salt)))
            .apply()
        _pinEnabled.value = true
    }

    fun verifyPin(pin: String): Boolean = verify(KEY_PIN_SALT, KEY_PIN_HASH, pin)

    fun clearPin() {
        prefs.edit().remove(KEY_PIN_SALT).remove(KEY_PIN_HASH).apply()
        _pinEnabled.value = false
    }

    // endregion

    // region pattern

    fun setPattern(pattern: List<Int>) = setPattern(serializePattern(pattern))

    fun setPattern(pattern: String) {
        val salt = newSalt()
        prefs.edit()
            .putString(KEY_PATTERN_SALT, encode(salt))
            .putString(KEY_PATTERN_HASH, encode(hash(pattern, salt)))
            .apply()
        _patternEnabled.value = true
    }

    fun verifyPattern(pattern: List<Int>): Boolean = verifyPattern(serializePattern(pattern))

    fun verifyPattern(pattern: String): Boolean = verify(KEY_PATTERN_SALT, KEY_PATTERN_HASH, pattern)

    fun clearPattern() {
        prefs.edit().remove(KEY_PATTERN_SALT).remove(KEY_PATTERN_HASH).apply()
        _patternEnabled.value = false
    }

    // endregion

    // region lock state

    fun lock() {
        // Locking without any credential would strand the user on the lock screen.
        if (anyLockEnabled()) _isLocked.value = true
    }

    fun unlock() {
        _isLocked.value = false
    }

    /** Called on ON_STOP: hide the content immediately so recents never shows the notes. */
    fun onBackground() {
        backgroundedAt = SystemClock.elapsedRealtime()
        wasUnlockedInBackground = !_isLocked.value
        lock()
    }

    /** Called on ON_START: keep the lock unless the app was unlocked and the grace period held. */
    fun onForeground() {
        val elapsed = SystemClock.elapsedRealtime() - backgroundedAt
        if (wasUnlockedInBackground && elapsed in 0..GRACE_PERIOD_MS) _isLocked.value = false
        backgroundedAt = 0L
        wasUnlockedInBackground = false
    }

    // endregion

    // region brute force throttle

    fun isLockedOut(): Boolean = lockoutDeadlineElapsed > SystemClock.elapsedRealtime()

    /** Seconds the user still has to wait, 0 when not locked out; for the inline message. */
    fun lockoutSecondsRemaining(): Int {
        val remaining = lockoutDeadlineElapsed - SystemClock.elapsedRealtime()
        return if (remaining > 0) ((remaining + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND).toInt() else 0
    }

    /**
     * One failed attempt, with a wait that doubles each time the user burns through a lockout.
     *
     * A flat 30 seconds per five failures makes a four-digit PIN brute-forceable in an afternoon,
     * because an attacker gets ten thousand free tries a day. The schedule now grows
     * 30s -> 1m -> 5m -> 15m -> 30m (capped) per lockout, and the failure count that decides the
     * step survives reboots. The wait itself is measured on [SystemClock.elapsedRealtime] (so
     * changing the clock no longer skips it) and is persisted alongside a wall-clock copy, so a
     * force-stop cannot hand out a fresh batch of tries either (see [restoreLockoutFromPrefs]).
     */
    private fun registerFailure() {
        val failures = prefs.getInt(KEY_FAILURES, 0) + 1
        if (failures >= MAX_FAILURES) {
            val lockouts = prefs.getInt(KEY_LOCKOUTS, 0) + 1
            val waitMs = LOCKOUT_SCHEDULE_MS[minOf(lockouts - 1, LOCKOUT_SCHEDULE_MS.lastIndex)]
            val elapsedStart = SystemClock.elapsedRealtime()
            val wallStart = System.currentTimeMillis()
            lockoutDeadlineElapsed = elapsedStart + waitMs
            _lockoutUntil.value = wallStart + waitMs
            prefs.edit()
                .putInt(KEY_FAILURES, 0)
                .putInt(KEY_LOCKOUTS, lockouts)
                .putLong(KEY_LOCKOUT_WAIT_MS, waitMs)
                .putLong(KEY_LOCKOUT_ELAPSED_START, elapsedStart)
                .putLong(KEY_LOCKOUT_WALL_START, wallStart)
                .apply()
        } else {
            prefs.edit().putInt(KEY_FAILURES, failures).apply()
        }
    }

    private fun resetFailures() {
        prefs.edit()
            .putInt(KEY_FAILURES, 0)
            .putInt(KEY_LOCKOUTS, 0)
            .remove(KEY_LOCKOUT_WAIT_MS)
            .remove(KEY_LOCKOUT_ELAPSED_START)
            .remove(KEY_LOCKOUT_WALL_START)
            .apply()
        lockoutDeadlineElapsed = 0L
        _lockoutUntil.value = 0L
    }

    /**
     * Restores an in-flight lockout after a process death or a reboot.
     *
     * [registerFailure] persists the wait duration plus two start stamps. Within the same boot the
     * monotonic [SystemClock.elapsedRealtime] is authoritative: it cannot be skipped by changing the
     * system clock or by force-stopping the app. After a reboot (elapsed resets, so `elapsedNow <
     * elapsedStart`) we fall back to the wall-clock stamp - weaker, but the failure count survived,
     * so the next lockout in the sequence is longer. A wait that already expired while the app was
     * dead clears its own markers rather than leaving them to confuse the next failure.
     */
    private fun restoreLockoutFromPrefs() {
        val waitMs = prefs.getLong(KEY_LOCKOUT_WAIT_MS, 0L)
        if (waitMs <= 0L) return
        val elapsedStart = prefs.getLong(KEY_LOCKOUT_ELAPSED_START, 0L)
        val wallStart = prefs.getLong(KEY_LOCKOUT_WALL_START, 0L)
        val elapsedNow = SystemClock.elapsedRealtime()

        val remaining = if (elapsedStart > 0L && elapsedNow >= elapsedStart) {
            waitMs - (elapsedNow - elapsedStart)
        } else {
            wallStart + waitMs - System.currentTimeMillis()
        }

        if (remaining > 0L) {
            lockoutDeadlineElapsed = elapsedNow + remaining
            _lockoutUntil.value = System.currentTimeMillis() + remaining
        } else {
            prefs.edit()
                .remove(KEY_LOCKOUT_WAIT_MS)
                .remove(KEY_LOCKOUT_ELAPSED_START)
                .remove(KEY_LOCKOUT_WALL_START)
                .apply()
        }
    }

    // endregion

    private fun verify(saltKey: String, hashKey: String, value: String): Boolean {
        if (isLockedOut()) return false
        val salt = prefs.getString(saltKey, null)?.let { decode(it) } ?: return false
        val expected = prefs.getString(hashKey, null) ?: return false
        val actual = encode(hash(value, salt))
        // Constant time comparison, both operands are base64 of a 256 bit key.
        val matches = MessageDigest.isEqual(expected.toByteArray(), actual.toByteArray())
        if (matches) resetFailures() else registerFailure()
        return matches
    }

    private fun hash(value: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(value.toCharArray(), salt, ITERATIONS, KEY_LENGTH_BITS)
        return try {
            SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun newSalt(): ByteArray = ByteArray(SALT_BYTES).also { random.nextBytes(it) }

    private fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun decode(value: String): ByteArray = Base64.decode(value, Base64.NO_WRAP)

    companion object {
        /** Cell indices 0..8 row-major, serialized as e.g. "0-1-2-5-8". */
        const val PATTERN_SEPARATOR = "-"
        const val MIN_PIN_LENGTH = 4
        const val MAX_PIN_LENGTH = 6
        const val MIN_PATTERN_CELLS = 4

        private const val TAG = "LockManager"
        private const val PREFS_NAME = "writeon_lock"
        private const val FALLBACK_PREFS_NAME = "writeon_lock_fallback"
        private const val ALGORITHM = "PBKDF2WithHmacSHA256"
        /** PBKDF2-HMAC-SHA256 iterations, at the current OWASP minimum for this KDF. */
        private const val ITERATIONS = 600_000
        private const val KEY_LENGTH_BITS = 256
        private const val SALT_BYTES = 16

        private const val KEY_BIOMETRIC = "biometric_enabled"
        private const val KEY_PIN_SALT = "pin_salt"
        private const val KEY_PIN_HASH = "pin_hash"
        private const val KEY_PATTERN_SALT = "pattern_salt"
        private const val KEY_PATTERN_HASH = "pattern_hash"
        private const val KEY_FAILURES = "failed_attempts"

        /** How many lockouts the user has already burned through; decides the current wait step. */
        private const val KEY_LOCKOUTS = "lockout_count"

        /** In-flight lockout, persisted so a force-stop cannot reset it (see restoreLockoutFromPrefs). */
        private const val KEY_LOCKOUT_WAIT_MS = "lockout_wait_ms"
        private const val KEY_LOCKOUT_ELAPSED_START = "lockout_elapsed_start"
        private const val KEY_LOCKOUT_WALL_START = "lockout_wall_start"

        private const val MAX_FAILURES = 5
        private const val MILLIS_PER_SECOND = 1_000L

        /**
         * Wait after the 5th, 10th, 15th... consecutive failures. Doubling per lockout is what makes
         * "try every 4-digit PIN" cost weeks instead of an afternoon; the cap keeps a user who
         * genuinely forgot their PIN from waiting forever between the first and second try.
         */
        private val LOCKOUT_SCHEDULE_MS = longArrayOf(
            30_000L,        // 1st lockout
            60_000L,        // 2nd
            5 * 60_000L,    // 3rd
            15 * 60_000L,   // 4th
            30 * 60_000L    // 5th and beyond
        )

        /** Re-lock only when the app stayed in the background longer than this. */
        private const val GRACE_PERIOD_MS = 20_000L

        fun serializePattern(cells: List<Int>): String = cells.joinToString(PATTERN_SEPARATOR)

        fun parsePattern(serialized: String): List<Int> =
            serialized.split(PATTERN_SEPARATOR).mapNotNull { it.toIntOrNull() }
    }
}
