// androidx.security:security-crypto:1.1.0 deprecates EncryptedSharedPreferences/MasterKey as a
// whole and the project pinned that exact API, so the unavoidable deprecation is silenced here
// rather than spreading it through the lock UI.
@file:Suppress("DEPRECATION")

package com.ezpnix.writeon.presentation.lock

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.biometric.BiometricManager
import androidx.glance.appwidget.updateAll
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.ezpnix.writeon.R
import com.ezpnix.writeon.core.attachment.MediaVault
import com.ezpnix.writeon.core.security.EncryptionHelper
import com.ezpnix.writeon.data.local.database.BackupDatabase
import com.ezpnix.writeon.data.local.database.NoteDatabaseProvider
import com.ezpnix.writeon.domain.usecase.NoteUseCase
import com.ezpnix.writeon.presentation.components.markdown.AttachmentRegistry
import com.ezpnix.writeon.presentation.components.markdown.AudioLabelStore
import com.ezpnix.writeon.presentation.screens.edit.NoteNotificationManager
import com.ezpnix.writeon.widget.NotesWidget
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
     * REG-06: whether a Vault passphrase is loaded in memory.
     *
     * The app lock is only one way a Vault session ends. Without an app lock, [onBackground]'s
     * grace timer still drops the passphrase ([evictVaultSession]), and the editor that was showing
     * a Vault note then holds plaintext nobody can save. Screens observe this flow so they can clear
     * what they display at the moment the session dies, not only on the next failed save.
     *
     * It is true while [EncryptionHelper] holds a passphrase, which is set through
     * [beginVaultAttempt] (Home's banner and the editor's unlock dialog) and cleared here.
     */
    private val _vaultSessionActive = MutableStateFlow(!encryptionHelper.isPasswordEmpty())
    val vaultSessionActive: StateFlow<Boolean> = _vaultSessionActive.asStateFlow()

    /**
     * REG-06: true while a screen is showing Vault plaintext.
     *
     * `FLAG_SECURE` used to follow only `screenProtection` or a configured app lock, so an install
     * with a Vault but no app lock exposed the open note to screenshots and the recents thumbnail.
     * The editor publishes its state here and [com.ezpnix.writeon.presentation.theme.LeafNotesTheme]
     * includes it in the flag decision.
     */
    private val _vaultContentVisible = MutableStateFlow(false)
    val vaultContentVisible: StateFlow<Boolean> = _vaultContentVisible.asStateFlow()

    /**
     * How many composed editors currently show Vault plaintext.
     *
     * A counter, not a boolean: during a navigation transition the outgoing editor is still
     * composed when the incoming one acquires, and a plain "set false" from the old destination's
     * dispose would drop `FLAG_SECURE` while the new Vault note is on screen.
     */
    private var vaultContentVisibleCount = 0

    /** Called by an editor when a Vault note's plaintext appears on screen. */
    @Synchronized
    fun acquireVaultContentVisibility() {
        vaultContentVisibleCount++
        _vaultContentVisible.value = true
    }

    /** Called by the same editor when it leaves composition or its note stops being visible. */
    @Synchronized
    fun releaseVaultContentVisibility() {
        vaultContentVisibleCount = (vaultContentVisibleCount - 1).coerceAtLeast(0)
        if (vaultContentVisibleCount == 0) _vaultContentVisible.value = false
    }

    /** The in-memory passphrase that belongs to this session, if any (SEC-02 ownership). */
    private val vaultOwnership = VaultSessionOwnership()

    /**
     * SEC-02/SEC-13: one Vault verification at a time.
     *
     * [EncryptionHelper] holds a single passphrase for the process. When Home's banner and the
     * editor's unlock dialog verify candidates concurrently, one caller's `setPassword` changes the
     * key the other is about to decrypt with, and the loser's failure path can remove a passphrase
     * that just verified. Both callers run their set-verify-keep/restore sequence inside this lock.
     */
    private val vaultUnlockMutex = Mutex()

    /** Runs [block] with the process-wide Vault verification lock held. */
    suspend fun <T> withVaultUnlock(block: suspend () -> T): T =
        vaultUnlockMutex.withLock { block() }

    /**
     * SEC-13: persisted failure throttle for Vault passphrase guesses.
     *
     * Lazy so constructing the lock manager (every cold start) does no extra disk work; the file
     * holds counters and timestamps only, never a passphrase.
     */
    val vaultAttempts: VaultAttemptThrottle by lazy {
        VaultAttemptThrottle(
            store = SharedPreferencesVaultAttemptStore(
                context.getSharedPreferences(VAULT_THROTTLE_PREFS_NAME, Context.MODE_PRIVATE)
            )
        )
    }

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

    /**
     * SEC-02: the Vault passphrase is a credential of its own, so it gets its own timer.
     *
     * The app-lock eviction already drops it, but that path only exists while a credential is
     * configured. Without one, the old [onBackground] returned immediately and an unlocked Vault
     * stayed decrypted until process death - and there is no "lock Vault" action in the UI at all.
     * This timer follows the same main-thread discipline as [evictionJob]: ON_START and the timer
     * body are both lifecycle callbacks on Main, so the cancel and the eviction cannot interleave.
     */
    private var vaultEvictionJob: Job? = null
    private var vaultBackgroundedAt = 0L

    /** Set by [evictVaultSession]; [onForeground] reloads the unsealed rows it hid. */
    private var vaultEvictedInBackground = false

    /**
     * SEC-01: the surfaces that render outside the gate only read the lock state when they render.
     *
     * A launcher widget and a pinned notification are both visible without unlocking, and both
     * captured "no lock" when they were drawn. Enabling a credential or locking therefore has to
     * force a re-render; flipping the flag alone left the old content on screen indefinitely (the
     * widget only refreshed when the note id set changed, the notification never at all).
     */
    private var widgetRefreshJob: Job? = null

    fun anyLockEnabled(): Boolean =
        _biometricEnabled.value || _pinEnabled.value || _patternEnabled.value

    // region biometric

    /**
     * UX-04: biometrics are a convenience on top of an app credential, never the only lock.
     *
     * The switch asks for `BIOMETRIC_STRONG or DEVICE_CREDENTIAL`, so on a device with only a
     * screen lock it succeeds although no fingerprint exists. If the user then removes the device
     * credential there is no authenticator left to satisfy the prompt and no app PIN or pattern to
     * fall back to - the app would be unopenable forever. Refusing the biometric-only state means
     * that combination cannot be created any more; [disableUnusableBiometricLock] is the documented
     * way out for installs that already have it.
     */
    fun setBiometricEnabled(enabled: Boolean) {
        if (enabled && !_pinEnabled.value && !_patternEnabled.value) return
        _biometricEnabled.value = enabled
        prefs.edit().putBoolean(KEY_BIOMETRIC, enabled).apply()
        // SEC-01: a new credential redacts the shade and the launcher immediately. Turning it off
        // only re-renders the widget - no new gate appeared, so the notification may stay.
        if (enabled) NoteNotificationManager.cancelPinnedNotification(context)
        // REG-07: turning off a legacy biometric-only lock must not leave the gate up with no
        // credential that can ever satisfy it.
        if (!enabled && !_pinEnabled.value && !_patternEnabled.value) settleUnlocked()
        refreshWidgets()
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
        redactLockSurfaces()
    }

    fun verifyPin(pin: String): Boolean = verify(KEY_PIN_SALT, KEY_PIN_HASH, pin)

    /**
     * Removes the PIN and, when it was the last non-biometric credential, biometric unlock too.
     *
     * REG-07: `clearPin` used to leave `biometric_enabled` behind, which created exactly the
     * biometric-only state [setBiometricEnabled] refuses to create - and that state can strand the
     * app when the device credential is removed. The switch in Privacy is updated by the same
     * flows, so the UI cannot show "off" while the unusable combination survives on disk.
     *
     * @return true when biometric unlock was turned off as part of this call.
     */
    fun clearPin(): Boolean {
        prefs.edit().remove(KEY_PIN_SALT).remove(KEY_PIN_HASH).apply()
        _pinEnabled.value = false
        val biometricCleared = clearBiometricIfLastCredential()
        // SEC-01: with the last credential gone the widget may show notes again; when biometric was
        // the last one, the pinned notification has to go as well because a new gate disappeared.
        if (biometricCleared) redactLockSurfaces() else refreshWidgets()
        return biometricCleared
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
        redactLockSurfaces()
    }

    fun verifyPattern(pattern: List<Int>): Boolean = verifyPattern(serializePattern(pattern))

    fun verifyPattern(pattern: String): Boolean = verify(KEY_PATTERN_SALT, KEY_PATTERN_HASH, pattern)

    /** Pattern counterpart of [clearPin]; see there for the biometric interaction (REG-07). */
    fun clearPattern(): Boolean {
        prefs.edit().remove(KEY_PATTERN_SALT).remove(KEY_PATTERN_HASH).apply()
        _patternEnabled.value = false
        val biometricCleared = clearBiometricIfLastCredential()
        if (biometricCleared) redactLockSurfaces() else refreshWidgets()
        return biometricCleared
    }

    // endregion

    // region vault session

    /**
     * SEC-02: loads [candidate] for one verification attempt.
     *
     * Returns the passphrase this attempt replaced so a failed attempt can put it back. Callers
     * must hold [withVaultUnlock] around the whole set-verify-settle sequence.
     */
    @Synchronized
    fun beginVaultAttempt(candidate: String): String? {
        val previous = vaultOwnership.begin(candidate)
        encryptionHelper.setPassword(candidate)
        // The candidate is not a session yet: [_vaultSessionActive] stays false when the Vault was
        // locked, so a failed attempt cannot make an open editor clear its plaintext on a transient
        // true -> false. A session that was already active stays active; the attempt either keeps
        // it (success) or restores the previous passphrase (see [endVaultAttempt]).
        if (previous != null) _vaultSessionActive.value = true
        return previous
    }

    /**
     * Settles the attempt started by [beginVaultAttempt].
     *
     * On success the candidate stays loaded. On failure the candidate is dropped - but only if it
     * is still the passphrase this session owns; a concurrent editor unlock that already replaced
     * it is restored from [previous] instead of being wiped (SEC-02). With no previous session the
     * vault simply goes back to locked.
     */
    @Synchronized
    fun endVaultAttempt(candidate: String, previous: String?, success: Boolean) {
        if (success) {
            // An eviction that ran while the decryption was in flight wins: the process no longer
            // holds this passphrase, so the success must not resurrect the session flow without one.
            if (vaultOwnership.owns(candidate)) _vaultSessionActive.value = true
            return
        }
        if (!vaultOwnership.owns(candidate)) return
        if (previous != null && !sameSecret(previous, candidate)) {
            vaultOwnership.restore(previous)
            encryptionHelper.setPassword(previous)
            _vaultSessionActive.value = true
        } else {
            vaultOwnership.clear()
            encryptionHelper.removePassword()
            _vaultSessionActive.value = false
        }
    }

    /**
     * Removes the loaded passphrase only when it is exactly [candidate] (SEC-02).
     *
     * The compare is constant-time, matching the app-lock verifier. It is the narrow API for
     * callers whose failed guess must not touch a session another unlock has since installed.
     */
    @Synchronized
    fun removeVaultPasswordIfMatches(candidate: String): Boolean {
        if (!vaultOwnership.removeIfOwned(candidate)) return false
        encryptionHelper.removePassword()
        _vaultSessionActive.value = false
        return true
    }

    /**
     * Drops the passphrase and every copy derived from it, without touching the app-lock gate.
     *
     * Synchronized so a verification that is loading a guess cannot land between the ownership
     * reset and the [EncryptionHelper] clear.
     */
    @Synchronized
    private fun clearVaultPassword() {
        vaultOwnership.clear()
        encryptionHelper.removePassword()
        _vaultSessionActive.value = false
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
        // vault note could still be decrypted with what is already in memory. The ownership record
        // and the session flow are cleared with it (SEC-02/REG-06).
        clearVaultPassword()
        // SEC-10: audio labels are decrypted for the player cards, so the in-memory map is part of
        // what a lock has to drop. The stored copy is Keystore-sealed (see AudioLabelStore).
        AudioLabelStore.clearInMemory()
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
        // SEC-01: both surfaces rendered before this point still show the note they captured. The
        // widget re-reads `anyLockEnabled()` and the notification is dropped rather than redacted:
        // it carries a note id that most of the shade cannot even open behind the gate.
        redactLockSurfaces()
    }

    /**
     * SEC-01: forces every surface outside the gate to re-render against the current lock state.
     *
     * Cancelling the notification is part of the redaction, not a convenience: its text was
     * rendered when no lock existed, and it stays in the shade, on the lock screen and in any
     * screenshot without an unlock.
     */
    private fun redactLockSurfaces() {
        NoteNotificationManager.cancelPinnedNotification(context)
        refreshWidgets()
    }

    /**
     * Re-renders every Glance widget.
     *
     * The launcher keeps the last composition until it is told otherwise, and the widget only
     * refreshes itself when the note id set changes (UX-06 lives elsewhere). Bursts are expected -
     * ON_STOP, the grace timer and an explicit lock can all land in the same second - so every
     * request replaces the previous one; Glance renders the current state, not a queue of them.
     */
    private fun refreshWidgets() {
        widgetRefreshJob?.cancel()
        widgetRefreshJob = mainScope.launch(Dispatchers.IO) {
            runCatching { NotesWidget().updateAll(context) }
                .onFailure { Log.w(TAG, "Could not refresh the notes widgets", it) }
        }
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
        val hasAppLock = anyLockEnabled()
        // SEC-02: an unlocked Vault is a credential in its own right. The old early return made a
        // Vault unlocked on an app without an app lock stay decrypted until process death, and no
        // UI action could lock it again.
        val hasVaultSession = !encryptionHelper.isPasswordEmpty()
        if (!hasAppLock && !hasVaultSession) return

        val now = SystemClock.elapsedRealtime()
        if (hasAppLock) {
            backgroundedAt = now
            // Read before the flag flips: the grace path only reopens an app that was unlocked when
            // it left, not one that was already showing the lock screen.
            wasUnlockedInBackground = !_isLocked.value
            _isLocked.value = true
            // SEC-01: the gate goes up before the timer; the shade and the launcher must not wait
            // for it.
            redactLockSurfaces()
            scheduleEviction(now)
        } else {
            // The Vault timer runs independently of the app lock, so it needs its own stamp: it
            // must also work when the app lock path above never ran.
            vaultBackgroundedAt = now
            scheduleVaultEviction(now)
        }
    }

    /**
     * Called on ON_START: keep the lock unless the app was unlocked and the grace period held.
     *
     * Returning within the grace period keeps the Vault session: the timer is cancelled and
     * [settleUnlocked] only reopens the gate. Returning after it (or when this visit was never
     * unlocked) evicts now if the timer has not already, and stays locked.
     */
    fun onForeground() {
        val hadBackgroundVisit = backgroundedAt != 0L
        val elapsed = SystemClock.elapsedRealtime() - backgroundedAt
        val withinGrace = wasUnlockedInBackground && hadBackgroundVisit && elapsed in 0..GRACE_PERIOD_MS
        if (withinGrace) {
            cancelPendingEviction()
            settleUnlocked()
        } else if (_isLocked.value && hadBackgroundVisit) {
            // backgroundedAt != 0 keeps a rotation or a cold start from running a pointless
            // eviction: this branch means there was a real ON_STOP this return is closing.
            cancelPendingEviction()
            evictSensitiveState()
        }
        backgroundedAt = 0L
        wasUnlockedInBackground = false

        // SEC-02: same grace decision for the Vault session, whether or not an app lock exists. A
        // camera or file-picker trip ends within the window and keeps its key; a real absence has
        // already evicted (or is evicted here if the timer could not run, e.g. under Doze).
        val vaultStamp = vaultBackgroundedAt
        if (vaultStamp != 0L) {
            cancelPendingVaultEviction()
            if (SystemClock.elapsedRealtime() - vaultStamp !in 0..GRACE_PERIOD_MS) evictVaultSession()
            vaultBackgroundedAt = 0L
        }
        if (vaultEvictedInBackground) {
            vaultEvictedInBackground = false
            // The eviction cleared the list while nothing was visible. Reloading here (on Main,
            // after the gate decision) is what brings the ordinary notes back; the Vault rows stay
            // hidden behind their missing key, which is their locked state.
            if (!_isLocked.value) noteUseCase.observe()
        }
    }

    /**
     * UX-04: recovery for a biometric-only lock whose device credential was removed.
     *
     * The install can be left over from a build that allowed enabling biometrics alone, and the
     * authenticator set (`BIOMETRIC_STRONG or DEVICE_CREDENTIAL`) then has nothing enrolled that
     * could succeed. There is no credential left to protect, so keeping the gate would only make
     * the app unopenable; the unusable lock is turned off and the caller tells the user to set up a
     * PIN or pattern. Returns true only when it actually changed something.
     */
    fun disableUnusableBiometricLock(): Boolean {
        if (!_biometricEnabled.value || _pinEnabled.value || _patternEnabled.value) return false
        _biometricEnabled.value = false
        prefs.edit().putBoolean(KEY_BIOMETRIC, false).apply()
        // No gate is left to show. This is not a security downgrade: nothing could ever pass the
        // prompt, so the alternative is a permanently unusable app.
        settleUnlocked()
        refreshWidgets()
        return true
    }

    /**
     * REG-07: biometric unlock may never be the only credential (see [setBiometricEnabled]).
     *
     * Called after a PIN or pattern was removed. When nothing remains, biometric is persisted off
     * as well and any open gate is reopened - there is no credential left that could unlock it.
     */
    private fun clearBiometricIfLastCredential(): Boolean {
        if (!mustClearBiometricWithLastCredential(
                biometricEnabled = _biometricEnabled.value,
                pinEnabled = _pinEnabled.value,
                patternEnabled = _patternEnabled.value,
            )
        ) {
            return false
        }
        _biometricEnabled.value = false
        prefs.edit().putBoolean(KEY_BIOMETRIC, false).apply()
        // No gate is left to show; keeping it would strand the user behind a prompt that can never
        // succeed once the system credential is gone.
        settleUnlocked()
        return true
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

    /**
     * SEC-02: grace timer for the Vault passphrase itself (see [vaultEvictionJob]).
     *
     * Same stamp discipline as [scheduleEviction]: a job that was already running when the user
     * returned cannot evict a session the return kept. The window is deliberately the same
     * [GRACE_PERIOD_MS]: a camera/album/file-picker round trip is the reason the grace exists, and
     * a longer absence is a decision to leave, whether or not the app lock is configured.
     */
    private fun scheduleVaultEviction(stamp: Long) {
        cancelPendingVaultEviction()
        vaultEvictionJob = mainScope.launch {
            delay(GRACE_PERIOD_MS)
            if (vaultBackgroundedAt == stamp) evictVaultSession()
        }
    }

    private fun cancelPendingVaultEviction() {
        vaultEvictionJob?.cancel()
        vaultEvictionJob = null
    }

    /**
     * Drops the Vault passphrase and everything derived from it, without touching the gate.
     *
     * This is the no-app-lock counterpart of [evictSensitiveState]: there is no lock screen to
     * fall back to, so [_isLocked] stays false and the rows simply revert to their locked state
     * (the list is reloaded by [onForeground]). The clear list is the one the audit asks for; the
     * same caches are dropped because a keystream that can still decrypt a row is exactly what the
     * eviction is meant to remove.
     */
    private fun evictVaultSession() {
        val hadSession = !encryptionHelper.isPasswordEmpty() || _vaultSessionActive.value
        // The flow and the ownership record are cleared even when the passphrase is already gone:
        // REG-06's editor watches the flow, and an eviction that skipped the notification would
        // leave its plaintext on screen.
        clearVaultPassword()
        if (!hadSession) return
        noteUseCase.clearSensitiveState()
        noteDatabaseProvider.clearCryptoCache()
        BackupDatabase.provider(context).clearCryptoCache()
        AttachmentRegistry.clearMaterialized()
        MediaVault.clearCache(context)
        // SEC-10: decrypted audio labels go with the other plaintext caches.
        AudioLabelStore.clearInMemory()
        // SEC-05: a notification posted from a Vault note must not outlive the session that
        // decrypted it. Only a Vault note's notification is dropped; a regular pinned note keeps
        // working on installs without an app lock.
        NoteNotificationManager.cancelVaultPinnedNotification(context)
        // The widget re-reads the note through the now-missing passphrase and falls back to its
        // zero state instead of keeping the decrypted text it last rendered.
        refreshWidgets()
        vaultEvictedInBackground = true
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

        /**
         * SEC-13: the Vault throttle's own store. Counters and timestamps only, so plain private
         * preferences are enough; encrypting them would add a Keystore failure mode to the path
         * whose only job is to keep guesses slow.
         */
        private const val VAULT_THROTTLE_PREFS_NAME = "writeon_vault_throttle"
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

/**
 * SEC-02: the passphrase a verification attempt owns, so a failed guess cannot erase a session.
 *
 * The race this closes: Home's banner and the editor's unlock dialog both write the one passphrase
 * [EncryptionHelper] holds. Home's candidate fails, and its old unconditional `removePassword()`
 * then deletes the passphrase the editor just verified and saved with. Ownership is tracked by
 * value: an attempt may only remove the passphrase it installed, and a failure restores the
 * session that was there before when it belongs to someone else.
 *
 * Pure so the transition is testable without a Context, preferences or a Keystore.
 */
internal class VaultSessionOwnership {

    /** The passphrase this process currently owns, or null when the Vault is locked. */
    var active: String? = null
        private set

    /** Loads [candidate]; returns what was loaded before so a failure can restore it. */
    fun begin(candidate: String): String? {
        val previous = active
        active = candidate
        return previous
    }

    /** True when [candidate] is exactly the passphrase this session holds (constant-time). */
    fun owns(candidate: String): Boolean {
        val current = active ?: return false
        return sameSecret(current, candidate)
    }

    /** Puts [previous] back after a failed attempt. */
    fun restore(previous: String) {
        active = previous
    }

    /**
     * Compare-and-remove: clears the session only when [candidate] is the passphrase it owns.
     *
     * This is the SEC-02 primitive. A failed guess that has already been replaced by another
     * unlock returns false and leaves the newer session untouched.
     */
    fun removeIfOwned(candidate: String): Boolean {
        if (!owns(candidate)) return false
        active = null
        return true
    }

    fun clear() {
        active = null
    }
}

/**
 * Constant-time string comparison for secrets.
 *
 * [MessageDigest.isEqual] is the same primitive the lock verifier uses; both operands are UTF-8
 * bytes of a passphrase, so a timing difference cannot reveal a matching prefix.
 */
internal fun sameSecret(first: String, second: String): Boolean =
    MessageDigest.isEqual(first.toByteArray(Charsets.UTF_8), second.toByteArray(Charsets.UTF_8))

/**
 * REG-07: the credential invariant that makes a biometric-only lock impossible.
 *
 * Pure because [LockManager.clearPin]/[clearPattern] touch encrypted preferences; the rule they
 * have to enforce is only this boolean. Biometric is a convenience on top of a PIN or pattern, so
 * once the last of those is removed biometric has to go with it.
 */
internal fun mustClearBiometricWithLastCredential(
    biometricEnabled: Boolean,
    pinEnabled: Boolean,
    patternEnabled: Boolean,
): Boolean = biometricEnabled && !pinEnabled && !patternEnabled

/** How usable the device's biometric/device-credential authenticator set is (REG-07). */
enum class BiometricAvailability {
    /** The prompt can be shown. */
    AVAILABLE,

    /** A lockout, a busy sensor or a temporary error: keep the lock, offer a retry. */
    TRANSIENT_ERROR,

    /** No usable authenticator will ever appear without the user changing device settings. */
    UNRECOVERABLE,
}

/**
 * REG-07: classifies [BiometricManager.canAuthenticate] instead of treating every non-success as
 * "the device has no credential".
 *
 * The old `canAuthenticate(...) == SUCCESS` check turned a transient `HW_UNAVAILABLE` (sensor busy
 * after an unlock, for example) into `disableUnusableBiometricLock()`, which permanently removed
 * the app lock. Only codes that mean "no enrollment / no hardware at all" may do that; everything
 * else keeps the configured lock and is shown as a retryable message.
 */
fun classifyBiometricAvailability(status: Int): BiometricAvailability = when (status) {
    BiometricManager.BIOMETRIC_SUCCESS -> BiometricAvailability.AVAILABLE
    BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED,
    BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
    // `BiometricManager.BIOMETRIC_ERROR_HW_NOT_PRESENT` is not in the pinned biometric library
    // (1.2.0-alpha05); the platform value is 14. Treated like NO_HARDWARE: no sensor, no prompt.
    BIOMETRIC_ERROR_HW_NOT_PRESENT_PLATFORM -> BiometricAvailability.UNRECOVERABLE
    // HW_UNAVAILABLE, TIMEOUT, LOCKOUT, CANCELED and anything newer: keep the lock.
    else -> BiometricAvailability.TRANSIENT_ERROR
}

/** The platform's `BIOMETRIC_ERROR_HW_NOT_PRESENT`, missing from the pinned library constants. */
private const val BIOMETRIC_ERROR_HW_NOT_PRESENT_PLATFORM = 14

/** Queries the current authenticator status and classifies it. */
fun biometricAvailability(context: Context): BiometricAvailability =
    classifyBiometricAvailability(
        BiometricManager.from(context).canAuthenticate(lockAuthenticators())
    )

/**
 * SEC-13: persistent back-off for Vault passphrase guesses.
 *
 * The app-lock verifier already persists its failure count and an elapsed-realtime deadline, and
 * the Vault had no equivalent: the unlock dialog and Home's banner could be retried as fast as the
 * 120k-iteration PBKDF2 allowed. This mirrors the lock schedule with a shorter first step (the
 * passphrase is long-form text rather than four digits) and the same reboot handling: within a
 * boot elapsed-realtime is authoritative, after one the wall-clock copy is the fallback.
 *
 * The clock and the storage are constructor parameters so the whole schedule is testable without
 * a device (see VaultAttemptThrottleTest).
 */
class VaultAttemptThrottle(
    private val store: VaultAttemptStore,
    private val elapsedNow: () -> Long = { SystemClock.elapsedRealtime() },
    private val wallNow: () -> Long = { System.currentTimeMillis() },
) {

    /** True while the next attempt has to wait. */
    fun isLockedOut(): Boolean = remainingMillis() > 0L

    /** Whole seconds still to wait, 0 when an attempt is allowed; for the inline message. */
    fun remainingSeconds(): Int {
        val remaining = remainingMillis()
        return if (remaining > 0L) ((remaining + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND).toInt() else 0
    }

    /** One rejected passphrase; after [MAX_FAILURES] a wait starts and doubles per lockout. */
    fun registerFailure() {
        val failures = store.getInt(KEY_FAILURES, 0) + 1
        if (failures < MAX_FAILURES) {
            store.apply(listOf(VaultAttemptChange.PutInt(KEY_FAILURES, failures)))
            return
        }
        val lockouts = store.getInt(KEY_LOCKOUTS, 0) + 1
        val waitMs = LOCKOUT_SCHEDULE_MS[minOf(lockouts - 1, LOCKOUT_SCHEDULE_MS.lastIndex)]
        // R3-07: one commit for the whole lockout transition, not five.
        store.apply(
            listOf(
                VaultAttemptChange.PutInt(KEY_FAILURES, 0),
                VaultAttemptChange.PutInt(KEY_LOCKOUTS, lockouts),
                VaultAttemptChange.PutLong(KEY_LOCKOUT_WAIT_MS, waitMs),
                VaultAttemptChange.PutLong(KEY_LOCKOUT_ELAPSED_START, elapsedNow()),
                VaultAttemptChange.PutLong(KEY_LOCKOUT_WALL_START, wallNow()),
            )
        )
    }

    /** Clears the streak after a successful unlock. */
    fun reset() {
        store.apply(
            listOf(
                VaultAttemptChange.Remove(KEY_FAILURES),
                VaultAttemptChange.Remove(KEY_LOCKOUTS),
                VaultAttemptChange.Remove(KEY_LOCKOUT_WAIT_MS),
                VaultAttemptChange.Remove(KEY_LOCKOUT_ELAPSED_START),
                VaultAttemptChange.Remove(KEY_LOCKOUT_WALL_START),
            )
        )
    }

    private fun remainingMillis(): Long {
        val waitMs = store.getLong(KEY_LOCKOUT_WAIT_MS, 0L)
        if (waitMs <= 0L) return 0L
        val elapsedStart = store.getLong(KEY_LOCKOUT_ELAPSED_START, 0L)
        val wallStart = store.getLong(KEY_LOCKOUT_WALL_START, 0L)
        val elapsed = elapsedNow()
        val remaining = if (elapsedStart > 0L && elapsed >= elapsedStart) {
            waitMs - (elapsed - elapsedStart)
        } else {
            // The device rebooted (elapsed reset) or the stamp is missing: the monotonic clock can
            // no longer place the start, so the wall-clock copy decides.
            wallStart + waitMs - wallNow()
        }
        return remaining.coerceAtLeast(0L)
    }

    private companion object {
        const val KEY_FAILURES = "vault_failures"
        const val KEY_LOCKOUTS = "vault_lockouts"
        const val KEY_LOCKOUT_WAIT_MS = "vault_lockout_wait_ms"
        const val KEY_LOCKOUT_ELAPSED_START = "vault_lockout_elapsed_start"
        const val KEY_LOCKOUT_WALL_START = "vault_lockout_wall_start"

        const val MAX_FAILURES = 5
        const val MILLIS_PER_SECOND = 1_000L

        /**
         * Wait after the 5th, 10th, 15th... rejected passphrase: 10s, 1m, 5m, 15m, 30m (capped).
         * The first step is shorter than the app lock's 30s because a Vault passphrase is normally
         * long text, not a four-digit PIN; the growth still makes offline-speed guessing impractical.
         */
        val LOCKOUT_SCHEDULE_MS = longArrayOf(
            10_000L,
            60_000L,
            5 * 60_000L,
            15 * 60_000L,
            30 * 60_000L,
        )
    }
}

/** One atomic change inside a [VaultAttemptStore] batch. */
sealed interface VaultAttemptChange {
    data class PutInt(val key: String, val value: Int) : VaultAttemptChange
    data class PutLong(val key: String, val value: Long) : VaultAttemptChange
    data class Remove(val key: String) : VaultAttemptChange
}

/**
 * Persistence seam for [VaultAttemptThrottle], so tests can use an in-memory map.
 *
 * R3-07: [apply] takes the whole transition as one batch. A lockout crosses several counters
 * (failures, lockouts, wait, two start stamps) and used to issue one `commit()` per key - five
 * synchronous disk writes on the main thread per failed unlock.
 */
interface VaultAttemptStore {
    fun getInt(key: String, defaultValue: Int): Int
    fun getLong(key: String, defaultValue: Long): Long
    fun apply(changes: List<VaultAttemptChange>)
}

/**
 * Production [VaultAttemptStore] over a private SharedPreferences file.
 *
 * R3-07: [commitEditor] is a seam, not configuration. Production always passes `Editor.commit`;
 * the test counts invocations to prove that one transition really is one synchronous disk write
 * (the previous test could only count a fake store's batches).
 */
@SuppressLint("ApplySharedPref")
internal class SharedPreferencesVaultAttemptStore(
    private val prefs: SharedPreferences,
    private val commitEditor: (SharedPreferences.Editor) -> Boolean = { it.commit() },
) : VaultAttemptStore {
    override fun getInt(key: String, defaultValue: Int): Int = prefs.getInt(key, defaultValue)
    override fun getLong(key: String, defaultValue: Long): Long = prefs.getLong(key, defaultValue)

    // SEC-13: commit(), not apply(). A failed guess that is only queued when the process is
    // force-stopped never reaches the file, and the attacker gets a fresh batch of tries. R3-07:
    // the whole transition is one editor and one commit; callers run it on Dispatchers.IO.
    override fun apply(changes: List<VaultAttemptChange>) {
        if (changes.isEmpty()) return
        val editor = prefs.edit()
        changes.forEach { change ->
            when (change) {
                is VaultAttemptChange.PutInt -> editor.putInt(change.key, change.value)
                is VaultAttemptChange.PutLong -> editor.putLong(change.key, change.value)
                is VaultAttemptChange.Remove -> editor.remove(change.key)
            }
        }
        commitEditor(editor)
    }
}
