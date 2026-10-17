// androidx.security:security-crypto:1.1.0 deprecates EncryptedSharedPreferences/MasterKey as a
// whole and the project pinned that exact API, so the unavoidable deprecation is silenced here
// rather than spreading it through the lock UI.
@file:Suppress("DEPRECATION")

package com.ezpnix.writeon.presentation.lock

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Base64
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

    private val prefs: SharedPreferences by lazy {
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
    }

    private val random = SecureRandom()

    private val _biometricEnabled = MutableStateFlow(prefs.getBoolean(KEY_BIOMETRIC, false))
    val biometricEnabled: StateFlow<Boolean> = _biometricEnabled.asStateFlow()

    private val _pinEnabled = MutableStateFlow(prefs.getString(KEY_PIN_HASH, null) != null)
    val pinEnabled: StateFlow<Boolean> = _pinEnabled.asStateFlow()

    private val _patternEnabled = MutableStateFlow(prefs.getString(KEY_PATTERN_HASH, null) != null)
    val patternEnabled: StateFlow<Boolean> = _patternEnabled.asStateFlow()

    private val _lockoutUntil = MutableStateFlow(prefs.getLong(KEY_LOCKOUT_UNTIL, 0L))
    val lockoutUntil: StateFlow<Long> = _lockoutUntil.asStateFlow()

    /** True while the lock screen must cover the app; also true on a cold start if a lock is set. */
    private val _isLocked = MutableStateFlow(anyLockEnabled())
    val isLocked: StateFlow<Boolean> = _isLocked.asStateFlow()

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

    fun isLockedOut(): Boolean = lockoutUntil.value > System.currentTimeMillis()

    /** Seconds the user still has to wait, 0 when not locked out; for the inline message. */
    fun lockoutSecondsRemaining(): Int {
        val remaining = lockoutUntil.value - System.currentTimeMillis()
        return if (remaining > 0) ((remaining + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND).toInt() else 0
    }

    private fun registerFailure() {
        val failures = prefs.getInt(KEY_FAILURES, 0) + 1
        if (failures >= MAX_FAILURES) {
            val until = System.currentTimeMillis() + LOCKOUT_MS
            prefs.edit().putInt(KEY_FAILURES, 0).putLong(KEY_LOCKOUT_UNTIL, until).apply()
            _lockoutUntil.value = until
        } else {
            prefs.edit().putInt(KEY_FAILURES, failures).apply()
        }
    }

    private fun resetFailures() {
        prefs.edit().putInt(KEY_FAILURES, 0).putLong(KEY_LOCKOUT_UNTIL, 0L).apply()
        _lockoutUntil.value = 0L
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

        private const val PREFS_NAME = "writeon_lock"
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
        private const val KEY_LOCKOUT_UNTIL = "lockout_until"

        private const val MAX_FAILURES = 5
        private const val LOCKOUT_MS = 30_000L
        private const val MILLIS_PER_SECOND = 1_000L

        /** Re-lock only when the app stayed in the background longer than this. */
        private const val GRACE_PERIOD_MS = 20_000L

        fun serializePattern(cells: List<Int>): String = cells.joinToString(PATTERN_SEPARATOR)

        fun parsePattern(serialized: String): List<Int> =
            serialized.split(PATTERN_SEPARATOR).mapNotNull { it.toIntOrNull() }
    }
}
