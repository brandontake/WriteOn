// androidx.security:security-crypto:1.1.0 deprecates EncryptedSharedPreferences/MasterKey as a
// whole and the project pinned that exact API, so the unavoidable deprecation is silenced here
// rather than spreading it through the lock UI.
@file:Suppress("DEPRECATION")

package com.ezpnix.writeon.presentation.lock

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.ezpnix.writeon.R
import com.ezpnix.writeon.core.attachment.MediaVault
import com.ezpnix.writeon.core.security.EncryptionHelper
import com.ezpnix.writeon.data.local.database.BackupDatabase
import com.ezpnix.writeon.data.local.database.NoteDatabaseProvider
import com.ezpnix.writeon.domain.usecase.NoteUseCase
import com.ezpnix.writeon.presentation.components.markdown.AttachmentRegistry
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
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
 *
 * SEC-6: "locked" means visibility **and** cache eviction, not just a screen over the content.
 * [lock] also drops the vault password, the derived-key cache, `NoteCrypto`'s plaintext cache and
 * the decrypted note list, so a locked app has nothing decryptable in memory for a heap dump or a
 * surface outside [LockGate] to find; the unlock path reloads the list. SEC-4 adds the decrypted
 * media copies in `cacheDir/media` (players and image loaders need a real path) to that list.
 *
 * V-3 draws the line between hiding and evicting: [onBackground] hides on **every** ON_STOP (so
 * recents never sees a note) but schedules the eviction for after [GRACE_PERIOD_MS]. A camera or
 * file-picker trip, a notification shade pull or any other quick stop is not a user decision to
 * leave, and evicting the vault key for it threw away a session the user never ended: the gate
 * reopened automatically, the vault did not, and a vault note edited across the trip could no
 * longer be saved. Short trips keep the Vault session; only a real absence evicts.
 */
@Singleton
class LockManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val encryptionHelper: EncryptionHelper,
    private val noteUseCase: NoteUseCase,
    // V-5: `NoteCrypto`'s plaintext cache is process-wide (companion-level), so clearing it here
    // reaches the Hilt provider, the backup seam and SettingsViewModel's injected instance alike.
    // The provider is still injected because it owns the eviction entry point, not the cache.
    private val noteDatabaseProvider: NoteDatabaseProvider,
) {

    /**
     * Encrypted lock storage with a last-resort fallback.
     *
     * The master key lives in the device Keystore. After a device transfer, a restored auto backup
     * or a Keystore reset, the preferences file is present but cannot be decrypted;
     * EncryptedSharedPreferences.create used to throw here, and because this happens during
     * ViewModel construction the app crashed on every launch. The unreadable file is deleted and
     * recreated first; if even that fails, a separate plain-preferences file keeps the app usable.
     * That fallback still stores only PBKDF2 hashes and salts, never the PIN or pattern itself.
     * When that reset destroys a stored lock it is no longer silent: the user is told (see
     * [notifyLockReset]), because a lock that vanishes without a word leaves them believing the
     * notes are still protected.
     */
    private val prefs: SharedPreferences by lazy {
        encryptedPreferences() ?: fallbackPreferences()
    }

    private fun encryptedPreferences(): SharedPreferences? {
        val store = createEncryptedPreferences()
        // Only stat the file when the store failed, so the happy path pays nothing extra.
        val storedLockFileExists = store == null && lockStoreFileExists()

        // A7: this branch used to delete the stored credential with only a logcat line, so a
        // Keystore hiccup switched the app lock off while the user still thought it was on.
        //
        // Trade-off - why delete rather than keep the unreadable file: the credential in there
        // can never be verified again (every read goes through the fallback store, which holds
        // nothing), so "keeping" it would at best be the same silent switch-off with a stale
        // file, and at worst - if anything ever treated the file's existence as "a lock is
        // configured" - a lock screen whose PIN can never match, which would lock the user out
        // of the app for good (this class intentionally has no recovery path). Deleting and
        // telling the user loses the lock but visibly, and it can be set up again.
        val action = planLockReset(
            encryptedStoreAvailable = store != null,
            storedLockFileExists = storedLockFileExists,
        )
        if (action == LockResetAction.KEEP) return store

        Log.w(TAG, "Lock preferences could not be decrypted; resetting them (storedState=$storedLockFileExists)")
        context.deleteSharedPreferences(PREFS_NAME)
        if (action == LockResetAction.DELETE_AND_NOTIFY) notifyLockReset()
        return createEncryptedPreferences().also {
            if (it == null) Log.e(TAG, "Encrypted lock storage unavailable; using fallback prefs")
        }
    }

    /** Whether lock state exists on disk, i.e. whether resetting it destroys something. */
    private fun lockStoreFileExists(): Boolean =
        File(context.applicationInfo.dataDir, "shared_prefs/$PREFS_NAME.xml").exists()

    /**
     * A7: makes the reset visible instead of silent - a trilingual Toast plus [Log.w].
     *
     * Posted to the main looper because [prefs] is initialized lazily and may be first touched
     * from any thread; Toast must be created on a thread with a Looper. [prefs] is a lazy on a
     * singleton, so this fires at most once per process - and only when a file was actually
     * discarded: after the first reset the file is gone, so a persistent Keystore fault stays
     * quiet instead of toasting on every launch.
     */
    private fun notifyLockReset() {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, context.getString(R.string.lock_reset_warning), Toast.LENGTH_LONG).show()
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

    /**
     * Runs the grace timer on the main thread ([Dispatchers.Main.immediate]).
     *
     * V-3: the timer and [onForeground]'s cancel must be ordered against each other. Both are
     * lifecycle callbacks on Main, so Main also serialises the "still backgrounded?" check: either
     * the return cancels the job in time and the Vault session survives, or the job has already
     * evicted and the return that follows finds the deadline passed. A timer on Default could
     * evict in the window between a successful grace return and its cancellation.
     */
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Pending grace eviction for the current background visit; null when none is scheduled. */
    private var evictionJob: Job? = null

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

    /**
     * Locks the app and, when a credential is configured, evicts what the lock is meant to hide.
     *
     * SEC-6: the flag alone only covers the UI. A locked app that still holds the vault password,
     * the derived keys and decrypted note bodies is a locked *screen*, not locked content: a heap
     * dump, a debugger or any surface outside [LockGate] would still find plaintext. The semantics
     * are therefore "visibility + cache eviction" - this method evicts at once (see [onBackground]
     * for the grace-delayed variant), and [unlock]/[onForeground]'s grace path loads the list back.
     *
     * Locking without any credential would strand the user on the lock screen, so that case only
     * leaves the state alone.
     */
    fun lock() {
        if (!anyLockEnabled()) return
        // Explicit locks (cold start, "lock now") are a decision rather than a visit: no grace,
        // no timer, and any timer a background visit left behind has nothing left to do.
        cancelPendingEviction()
        _isLocked.value = true
        evictSensitiveState()
    }

    /**
     * Drops everything decryptable. Idempotent on purpose: the explicit lock path, the grace timer
     * and a return after the deadline can all reach it, and more than one of them fires per lock.
     */
    private fun evictSensitiveState() {
        // Stop the producers first: a collector cancelled after the list is cleared could still
        // deliver one last emission and put decrypted rows back into memory.
        noteUseCase.clearSensitiveState()
        // Vault passphrase plus its derived-key LRU (EncryptionHelper). Without this a "locked"
        // vault note could still be decrypted with what is already in memory.
        encryptionHelper.removePassword()
        // `w1:` plaintext cache in NoteCrypto. V-5 moved that cache into the class' companion, so
        // this one call covers the list's DAO, Settings' flashcards/scratchpad and the migration
        // wrapper. The backup seam is cleared as well for the instance it may have created.
        noteDatabaseProvider.clearCryptoCache()
        BackupDatabase.provider(context).clearCryptoCache()
        // SEC-4: images and recordings are encrypted at rest, but playing or showing one writes a
        // plaintext copy into `cacheDir/media`. That copy is exactly what a heap/dump or a file
        // browser outside the lock gate could read, so it goes with the rest of the eviction.
        // The registry goes first: it also invalidates a decrypt that is still running, so that
        // copy cannot be published into the directory the lock is about to delete.
        AttachmentRegistry.clearMaterialized()
        MediaVault.clearCache(context)
    }

    fun unlock() {
        settleUnlocked()
    }

    /**
     * Leaves the locked state and reloads the list that [evictSensitiveState] just dropped.
     *
     * Called from both real unlocks (PIN, pattern, biometric) and the grace-period return in
     * [onForeground]: the latter never asks for a credential, so without this the list would stay
     * empty after a visit the lock had already evicted. [NoteUseCase.observe] is idempotent and
     * starts from a cleared store, so it is a fresh load here, not a no-op.
     */
    private fun settleUnlocked() {
        if (!_isLocked.value) return
        _isLocked.value = false
        noteUseCase.observe()
    }

    /**
     * Called on ON_STOP: hides the content at once and evicts only after the grace period (V-3).
     *
     * Recents privacy does not wait - the gate goes up before the timer, and `FLAG_SECURE` (see
     * [LockGate]) blocks the thumbnail as well. What must wait is the eviction. SEC-6 originally
     * evicted on every ON_STOP, which includes a camera/album/file-picker launch or a pulled-down
     * notification shade; the gate then reopened automatically on return, but the vault password
     * and `NoteCrypto` cache did not come back, so a vault note edited across the trip could no
     * longer be saved and its new text was lost. [scheduleEviction] keeps the session for those
     * short trips and drops it only when the user is really gone.
     */
    fun onBackground() {
        if (!anyLockEnabled()) return
        backgroundedAt = SystemClock.elapsedRealtime()
        // Read before the flag flips: the grace path only reopens an app that was unlocked when it
        // left, not one that was already showing the lock screen.
        wasUnlockedInBackground = !_isLocked.value
        _isLocked.value = true
        scheduleEviction(backgroundedAt)
    }

    /**
     * Called on ON_START: keep the lock unless the app was unlocked and the grace period held.
     *
     * Returning within the grace period keeps the Vault session: the timer is cancelled and
     * [settleUnlocked] only reopens the gate. Returning after it (or when this visit was never
     * unlocked) evicts now if the timer has not already, and stays locked.
     */
    fun onForeground() {
        val elapsed = SystemClock.elapsedRealtime() - backgroundedAt
        val withinGrace = wasUnlockedInBackground &&
            backgroundedAt != 0L &&
            elapsed in 0..GRACE_PERIOD_MS
        if (withinGrace) {
            cancelPendingEviction()
            settleUnlocked()
        } else if (_isLocked.value && backgroundedAt != 0L) {
            // backgroundedAt != 0 keeps a rotation or a cold start from running a pointless
            // eviction: this branch means there was a real ON_STOP this return is closing.
            cancelPendingEviction()
            evictSensitiveState()
        }
        backgroundedAt = 0L
        wasUnlockedInBackground = false
    }

    /**
     * Starts the grace timer for the background visit that began at [stamp].
     *
     * Fires only if that visit is still current and the app is still locked - a genuine absence.
     * The stamp check is the belt to [cancelPendingEviction]'s braces: it also covers a job that
     * started before the cancel was observed, and a visit that ended without ON_START.
     */
    private fun scheduleEviction(stamp: Long) {
        cancelPendingEviction()
        evictionJob = mainScope.launch {
            delay(GRACE_PERIOD_MS)
            if (backgroundedAt == stamp && _isLocked.value) evictSensitiveState()
        }
    }

    private fun cancelPendingEviction() {
        evictionJob?.cancel()
        evictionJob = null
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

        /**
         * How long a background visit may last before it counts as leaving (V-3).
         *
         * The gate hides on every ON_STOP; this window decides whether the return auto-reopens it
         * and whether the eviction kept the vault session. Long enough for a camera picker or a
         * notification-shade pull, short enough that a real switch to another app still locks.
         */
        private const val GRACE_PERIOD_MS = 20_000L

        fun serializePattern(cells: List<Int>): String = cells.joinToString(PATTERN_SEPARATOR)

        fun parsePattern(serialized: String): List<Int> =
            serialized.split(PATTERN_SEPARATOR).mapNotNull { it.toIntOrNull() }
    }
}

/** What to do when the encrypted lock store cannot be opened (A7). */
enum class LockResetAction {
    /** The store opened fine; nothing is deleted and nobody is told. */
    KEEP,

    /** Reset with no stored file to lose - nothing to announce, log only. */
    DELETE_QUIETLY,

    /** A stored lock file is about to be discarded; the user must be told. */
    DELETE_AND_NOTIFY,
}

/**
 * A7 decision: what happens when [LockManager] cannot open its encrypted store.
 *
 * Pure so the policy is testable without a device (see LockResetPolicyTest). The three cases:
 * an available store is simply kept; a failed open with no file on disk deletes a nothing and
 * stays quiet (fresh install, Keystore unavailable before anything was written); a failed open
 * that would destroy an existing lock file deletes it *and* announces it - that combination is
 * the whole point of A7, which existed because the third case used to look exactly like the
 * second in logcat and nowhere else.
 */
fun planLockReset(encryptedStoreAvailable: Boolean, storedLockFileExists: Boolean): LockResetAction = when {
    encryptedStoreAvailable -> LockResetAction.KEEP
    storedLockFileExists -> LockResetAction.DELETE_AND_NOTIFY
    else -> LockResetAction.DELETE_QUIETLY
}
