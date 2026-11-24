package com.ezpnix.writeon.presentation.screens.settings.model

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.ezpnix.writeon.BuildConfig
import com.ezpnix.writeon.R
import com.ezpnix.writeon.data.local.database.NoteCrypto
import com.ezpnix.writeon.data.repository.BackupResult
import com.ezpnix.writeon.data.repository.ImportExportRepository
import com.ezpnix.writeon.data.repository.SettingsRepositoryImpl
import com.ezpnix.writeon.domain.model.Settings
import com.ezpnix.writeon.domain.usecase.ImportExportUseCase
import com.ezpnix.writeon.domain.usecase.ImportResult
import com.ezpnix.writeon.domain.usecase.NoteUseCase
import com.ezpnix.writeon.domain.usecase.SettingsUseCase
import com.ezpnix.writeon.data.backup.BackupWorker
import com.ezpnix.writeon.presentation.lock.LockManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import androidx.compose.ui.res.stringResource

/**
 * SEC-09: the settings screen's in-memory secrets, and when they must go.
 *
 * This ViewModel holds the password typed for a local backup export/import (and, through the
 * methods below, hands out the decrypted scratchpad and flashcard decks). None of those may outlive
 * the session that was allowed to see them: an app lock or a Vault eviction - the latter also
 * happens on installs without an app lock - has to drop them. The persisted settings and the
 * stored ciphertext are deliberately untouched; only the process-lifetime copies are cleared.
 *
 * The rule is a tiny class rather than an inline `if` so it is testable without constructing the
 * Hilt ViewModel, which needs a Context, WorkManager and the whole note stack.
 */
internal class SettingsPlaintextState {

    /** The backup password; non-null only while a prompt's answer is still usable. */
    var backupPassword: String? = null

    /**
     * True when the plaintext copies must be dropped: the gate is up, or the Vault passphrase
     * that authorized decryption is gone. A fresh ViewModel on an install without a Vault sees
     * `false` here, so initialization alone never discards anything.
     */
    fun shouldClear(isLocked: Boolean, vaultSessionActive: Boolean): Boolean =
        isLocked || !vaultSessionActive

    /** Drops the password; returns true when there was one. */
    fun clear(): Boolean {
        val hadPassword = backupPassword != null
        backupPassword = null
        return hadPassword
    }
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val backup: ImportExportRepository,
    private val settingsUseCase: SettingsUseCase,
    private val noteUseCase: NoteUseCase,
    private val importExportUseCase: ImportExportUseCase,
    private val settingsRepository: SettingsRepositoryImpl,
    @ApplicationContext private val context: Context,
    private val settingsPreferences: SettingsPreferences,
    private val noteCrypto: NoteCrypto,
    private val lockManager: LockManager,
) : ViewModel() {

    private companion object {
        const val TAG = "SettingsViewModel"

        /**
         * URLs the user already accepted the cleartext warning for (SEC-2).
         *
         * A string set rather than a boolean because the acknowledgement is per server: pointing
         * the same field at a different LAN address is a new decision and must ask again.
         */
        const val CLEARTEXT_ACK_KEY = "webdav_cleartext_ack"
    }

    private val _savedNote = MutableStateFlow("")
    val savedNote: String get() = _savedNote.value

    val databaseUpdate = mutableStateOf(false)

    /** SEC-09: plaintext the settings screen may be holding; cleared when its session ends. */
    private val plaintext = SettingsPlaintextState()

    /**
     * The backup password typed for a local export/import.
     *
     * SEC-09: stored in [plaintext] rather than as a bare field so the lock/Vault-eviction observer
     * below clears it in one place. The persisted settings are never touched - a locked app simply
     * has no in-memory password until the user types one again.
     */
    var password: String?
        get() = plaintext.backupPassword
        set(value) {
            plaintext.backupPassword = value
        }

    /**
     * R2-05: lets the settings screens close what they show when the session that authorized it
     * ends. The ViewModel's own fields are cleared by the observer in `init`; a composable's local
     * state (the password field, the scratchpad text, the loaded deck) has to observe the same two
     * flows, otherwise only the dead fields were ever cleared (SEC-09).
     */
    val vaultSessionActive: StateFlow<Boolean> = lockManager.vaultSessionActive

    /** R2-05: the app-lock half of the same rule; a credential gate also ends a settings session. */
    val appLocked: StateFlow<Boolean> = lockManager.isLocked

    /** U10: local zip export/import is long enough to need a visible in-progress state. */
    var localBackupRunning by mutableStateOf(false)
        private set

    /**
     * SEC-03: the destination whose export was refused because a password was required.
     *
     * The repository returns a typed [BackupResult.Error.passwordRequired] instead of blaming the
     * file. The screen observes this event and re-opens the password dialog for exactly that URI,
     * so the export completes on the second attempt instead of failing silently behind a toast.
     * Null means no export is waiting for a password.
     */
    private val _passwordRequiredExportUri = MutableStateFlow<Uri?>(null)
    val passwordRequiredExportUri: StateFlow<Uri?> = _passwordRequiredExportUri.asStateFlow()

    /** Clears the SEC-03 event once the screen has opened the password dialog for it. */
    fun consumePasswordRequiredExport() {
        _passwordRequiredExportUri.value = null
    }

    /** T4: Tools only needs the note side-effects of opening settings, not the use case itself. */
    fun refreshNotes() {
        noteUseCase.observe()
    }

    private val _dynamicPlaceholder = MutableStateFlow(context.getString(R.string.app_subtitle))
    val dynamicPlaceholder: StateFlow<String> = _dynamicPlaceholder.asStateFlow()

    private val sharedPreferences: SharedPreferences =
        context.getSharedPreferences("notes_prefs", Context.MODE_PRIVATE)

    private val _settings = mutableStateOf(Settings())
    var settings: State<Settings> = _settings

    init {
        viewModelScope.launch {
            // Apply the persisted snapshot first, then the values that live in their own preference
            // file. Both collectors used to sit *after* the termsOfService.collect {} below, which
            // never returns, so they never ran at all: the column count the user had picked was
            // replaced by the default on every launch (and the home subtitle never loaded).
            _settings.value = settingsUseCase.loadSettingsFromRepository()
            _settings.value = _settings.value.copy(
                columnsCount = settingsPreferences.columnsCount.first()
            )
            _dynamicPlaceholder.value = settingsPreferences.dynamicPlaceholder.first()
            if (_settings.value.autoBackupEnabled) {
                startAutoBackup(context)
            }

            // Keep observing so changes made while the app is running are reflected immediately.
            launch {
                settingsPreferences.columnsCount.collect { count ->
                    _settings.value = _settings.value.copy(columnsCount = count)
                }
            }
            launch {
                settingsPreferences.dynamicPlaceholder.collect { placeholder ->
                    _dynamicPlaceholder.value = placeholder
                }
            }
            settingsRepository.getTermsOfService().collect { termsAccepted ->
                _settings.value = _settings.value.copy(termsOfService = termsAccepted)
            }
        }
    }

    private val _visibleFabItems = MutableStateFlow<Set<String>>(emptySet())
    val visibleFabItems: StateFlow<Set<String>> = _visibleFabItems.asStateFlow()

    init {
        viewModelScope.launch {
            settingsPreferences.visibleFabItems.collect { set ->
                _visibleFabItems.value = set
            }
        }
    }



    fun updateVisibleFabItems(newSet: Set<String>) {
        viewModelScope.launch {
            settingsPreferences.saveVisibleFabItems(newSet)
        }
    }


    fun updateTermsOfService(accepted: Boolean) {
        viewModelScope.launch { acceptTermsOfService(accepted) }
    }

    /**
     * Persists the first-run acceptance and suspends until the dedicated DataStore write lands.
     *
     * TermsScreen awaits this before navigating to Home, so a process death immediately after the
     * tap cannot lose the acceptance. The bulk settings writer no longer touches this key.
     *
     * R2-11: a failed write is reported instead of being swallowed. Claiming success and navigating
     * would show the Terms again on the next cold start, which looks like the old "it pops up
     * randomly" bug.
     *
     * @return true only when the acceptance is on disk.
     */
    suspend fun acceptTermsOfService(accepted: Boolean = true): Boolean {
        val persisted = settingsRepository.saveTermsOfService(accepted)
        if (persisted) {
            _settings.value = _settings.value.copy(termsOfService = accepted)
        }
        return persisted
    }

    fun update(newSettings: Settings) {
        // The bulk writer deliberately does not persist termsOfService; keep the live accepted
        // flag from being visually reset by an unrelated settings change.
        _settings.value = newSettings.copy(termsOfService = _settings.value.termsOfService)
        viewModelScope.launch {
            settingsUseCase.saveSettingsToRepository(newSettings)
            if (newSettings.autoBackupEnabled) {
                startAutoBackup(context)
            } else {
                stopAutoBackup(context)
            }
        }
    }

    fun setColumnsCount(count: Int) {
        viewModelScope.launch {
            settingsPreferences.saveColumnsCount(count)
            _settings.value = _settings.value.copy(columnsCount = count)
        }
    }

    fun updateFontSize(size: Float) {
        _settings.value = _settings.value.copy(fontSize = size)
        viewModelScope.launch {
            settingsUseCase.saveSettingsToRepository(_settings.value)
        }
    }

    /**
     * The scratchpad shares the note table's Keystore key, so it is encrypted at rest exactly like
     * a note body. It used to be written into SharedPreferences as plain text, which made the
     * device backup of that file readable and defeated the database encryption. A value written
     * before this change has no `w1:` marker, so [NoteCrypto.openOrNull] still returns it and the
     * next save upgrades it in place.
     */
    fun saveNote(note: String): Boolean {
        // DAT-10: the stored value exists but could not be decrypted. Writing here would replace
        // the only copy of the user's text with a fresh ciphertext of whatever is on screen, so
        // the save is refused until the value is deliberately dealt with. Returns false so the
        // caller does not show its "saved" confirmation for a write that never happened.
        if (scratchpadUnreadable) {
            Log.w(TAG, "Refusing to overwrite an unreadable scratchpad value")
            return false
        }
        val stored = runCatching { noteCrypto.seal(note) }.getOrNull()
        if (stored == null) {
            // Never fall back to plaintext: failing the save is recoverable, leaking the text is not.
            showToast(context.getString(R.string.error), context)
            return false
        }
        sharedPreferences.edit().putString("note", stored).apply()
        return true
    }

    /**
     * True once [loadNote] found a stored scratchpad value it could not decrypt (DAT-10).
     *
     * An empty scratchpad and an undecryptable one used to be the same thing - `""` - so the next
     * save silently overwrote the original ciphertext with new text and the old content was gone.
     * The screen shows an explanation and disables saving while this is set; the stored bytes are
     * left untouched, and a copy is parked under `note_corrupt` in case the key ever comes back.
     */
    var scratchpadUnreadable by mutableStateOf(false)
        private set

    fun loadNote(): String {
        val stored = sharedPreferences.getString("note", "") ?: ""
        // Nothing stored, or a value written before sealing: the latter is returned as-is by
        // openOrNull, which is why no `w1:` test is needed here.
        if (stored.isBlank()) {
            scratchpadUnreadable = false
            return ""
        }
        val opened = noteCrypto.openOrNull(stored)
        if (opened == null) {
            Log.e(TAG, "Scratchpad ciphertext could not be opened; leaving it in place")
            runCatching {
                sharedPreferences.edit().putString("note_corrupt", stored).apply()
            }
            scratchpadUnreadable = true
            return ""
        }
        scratchpadUnreadable = false
        return opened
    }

    fun updatePlaceholder(newText: String) {
        viewModelScope.launch {
            settingsPreferences.savePlaceholder(newText)
            _dynamicPlaceholder.value = newText
        }
    }

    /**
     * SEC-2: whether [url] may be used without showing the cleartext warning again.
     *
     * Returns true for anything that is not `http://` - https never needs the warning - and for an
     * http URL the user has already accepted. The gate itself lives in the Cloud screen, right in
     * front of every path that saves the config or starts a transfer, because that is the only
     * place that can show a dialog and wait for the answer.
     */
    fun isCleartextWebDavAcknowledged(url: String): Boolean {
        val key = cleartextAckKey(url) ?: return true
        return sharedPreferences.getStringSet(CLEARTEXT_ACK_KEY, emptySet())?.contains(key) == true
    }

    /** Remembers that the user accepted the cleartext warning for [url] (SEC-2). */
    fun acknowledgeCleartextWebDav(url: String) {
        val key = cleartextAckKey(url) ?: return
        val acknowledged = sharedPreferences
            .getStringSet(CLEARTEXT_ACK_KEY, emptySet())
            .orEmpty()
            .toMutableSet()
        if (!acknowledged.add(key)) return
        sharedPreferences.edit().putStringSet(CLEARTEXT_ACK_KEY, acknowledged).apply()
    }

    /**
     * Normalises an http URL for the acknowledgement set, or returns null for a scheme that needs
     * no warning. Only the trailing slash is normalised: two different paths on the same host are
     * two different servers as far as the user is concerned, so they each get their own warning.
     */
    private fun cleartextAckKey(url: String): String? {
        val trimmed = url.trim()
        if (!trimmed.startsWith("http://", ignoreCase = true)) return null
        return trimmed.trimEnd('/')
    }

    private fun startAutoBackup(context: Context) {
        val backupRequest = PeriodicWorkRequestBuilder<BackupWorker>(1, TimeUnit.DAYS).build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "AutoBackup",
            // KEEP, not REPLACE. This runs on every SettingsModel construction and after every
            // settings change, and REPLACE cancels and re-creates the work each time - so the one
            // day period restarted long before it could elapse and the automatic backup never
            // actually fired. It also wrote to the WorkManager database on every toggle.
            ExistingPeriodicWorkPolicy.KEEP,
            backupRequest
        )
    }

    private fun stopAutoBackup(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork("AutoBackup")
    }

    fun onExportBackup(uri: Uri, context: Context) {
        viewModelScope.launch {
            localBackupRunning = true
            try {
                val result = backup.exportBackup(uri, password)
                if (result is BackupResult.Error && result.passwordRequired) {
                    // SEC-03: the policy refused before touching the destination. Publish the URI
                    // so Cloud.kt and Privacy.kt can ask for the password and retry the same file.
                    // The toast stays as the fallback for any entry point that does not observe
                    // the event (and for a screen that is not composed when it fires).
                    _passwordRequiredExportUri.value = uri
                    showToast(result.message.ifBlank { context.getString(R.string.error) }, context)
                } else {
                    handleBackupResult(result, context)
                    databaseUpdate.value = true
                }
            } finally {
                localBackupRunning = false
            }
        }
    }

    fun onImportBackup(uri: Uri, context: Context) {
        viewModelScope.launch {
            localBackupRunning = true
            try {
                val result = backup.importBackup(uri, password)
                handleBackupResult(result, context)
                databaseUpdate.value = true
            } finally {
                localBackupRunning = false
            }
        }
    }

    /**
     * Imports a backup and suspends until it is done, reporting whether it succeeded.
     *
     * The fire-and-forget [onImportBackup] returns before the archive has been decrypted,
     * extracted and swapped in - which for an archive carrying recordings is seconds, not the
     * one second the caller used to wait before restarting the process. A restart that lands
     * mid-restore either reports success for a restore that never finished or installs the
     * database without its attachments. The caller uses this to restart only on a real success.
     */
    suspend fun importBackupAndWait(uri: Uri, context: Context): Boolean {
        localBackupRunning = true
        val result = try {
            backup.importBackup(uri, password)
        } finally {
            localBackupRunning = false
        }
        handleBackupResult(result, context)
        databaseUpdate.value = true
        return result is BackupResult.Success
    }

    /**
     * The daily automatic backups this device has written, newest first.
     *
     * They are encrypted with a key bound to this install (see [com.ezpnix.writeon.core.util.AutoBackupCrypto]),
     * so the list is only useful here - but "here" is exactly where a corrupted or emptied database
     * needs fixing.
     */
    fun autoBackupFiles(): List<File> {
        val folder = File(context.filesDir, "BackupFolder")
        return folder.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".zip.enc") }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
    }

    /**
     * Restores the chosen automatic backup and restarts the app so nothing keeps a handle on the
     * replaced database. The restart only happens after the restore reported success.
     */
    fun onRestoreAutoBackup(file: File, context: Context, onFinished: (Boolean) -> Unit) {
        viewModelScope.launch {
            val result = backup.importAutoBackup(file)
            handleBackupResult(result, context)
            databaseUpdate.value = true
            onFinished(result is BackupResult.Success)
        }
    }

    private fun getLocaleListFromXml(context: Context): LocaleListCompat {
        val tagsList = mutableListOf<CharSequence>()
        try {
            val xpp: XmlPullParser = context.resources.getXml(R.xml.locales_config)
            while (xpp.eventType != XmlPullParser.END_DOCUMENT) {
                if (xpp.eventType == XmlPullParser.START_TAG && xpp.name == "locale") {
                    tagsList.add(xpp.getAttributeValue(0))
                }
                xpp.next()
            }
        } catch (e: XmlPullParserException) {
            Log.w(TAG, "Could not parse locales_config", e)
        } catch (e: IOException) {
            Log.w(TAG, "Could not read locales_config", e)
        }
        // An unreadable resource still yields a usable (possibly empty) list rather than throwing
        // out of the settings screen.
        return LocaleListCompat.forLanguageTags(tagsList.joinToString(","))
    }

    fun getSupportedLanguages(context: Context): Map<String, String> {
        val localeList = getLocaleListFromXml(context)
        val map = mutableMapOf<String, String>()
        for (a in 0 until localeList.size()) {
            localeList[a]?.let { map[it.getDisplayName(it)] = it.toLanguageTag() }
        }
        return map
    }

    private fun handleBackupResult(result: BackupResult, context: Context) {
        when (result) {
            is BackupResult.Success -> showToast(context.getString(R.string.backup_successful), context)
            // The message is carried through (e.g. the auto-backup "could not be decrypted, wrong
            // device" text); showing only a generic "error" threw that away and left a user
            // restoring a device-bound archive with no clue why it failed.
            is BackupResult.Error ->
                showToast(result.message.ifBlank { context.getString(R.string.error) }, context)
            BackupResult.BadPassword -> showToast(context.getString(R.string.database_restore_error), context)
        }
    }

    private fun handleImportResult(result: ImportResult, context: Context) {
        when (result.successful) {
            result.total -> showToast(context.getString(R.string.file_import_success), context)
            0 -> showToast(context.getString(R.string.file_import_error), context)
            else -> showToast(context.getString(R.string.file_import_partial_error), context)
        }
    }

    private fun showToast(message: String, context: Context) {
        // Callers include the flashcard save, which now runs on Dispatchers.IO; Toast must be
        // created on a looper thread.
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    private var flashcardStorage = mutableListOf<Flashcard>()
    private val flashPrefs: SharedPreferences =
        context.getSharedPreferences("flashcards_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val listType = TypeToken.getParameterized(List::class.java, Flashcard::class.java).type

    /**
     * True while the stored flashcards could not be parsed.
     *
     * An empty list and an unreadable list used to look the same, so one JSON error made the next
     * save persist the empty list over the user's whole deck. With this flag the save path refuses
     * to wipe what it could not read.
     */
    private var flashcardsUnreadable = false

    fun loadFlashcards(): List<Flashcard> {
        val stored = flashPrefs.getString("flashcards_key", null)
        if (stored == null) {
            flashcardsUnreadable = false
            return emptyList()
        }
        // SEC-4: the deck is sealed with the same Keystore-backed [NoteCrypto] as a note body, but
        // a deck written by an older build has no `w1:` marker and openOrNull hands it back
        // unchanged - so this single call reads both forms. A sealed value that cannot be opened
        // (key reset, deck copied from another install) returns null and falls into the
        // protection below instead of being reported as an empty deck.
        val json = noteCrypto.openOrNull(stored)
        if (json == null) {
            Log.e(TAG, "Stored flashcards could not be decrypted; keeping them out of the save path")
            flashcardsUnreadable = true
            // Keep a copy of the unreadable payload: overwriting the key is exactly the failure
            // mode this branch exists to prevent, so the bytes must survive somewhere recoverable.
            runCatching {
                flashPrefs.edit().putString("flashcards_key_corrupt", stored).apply()
            }
            return emptyList()
        }
        return try {
            @Suppress("UNCHECKED_CAST")
            val parsed = gson.fromJson<List<Flashcard>>(json, listType) as? List<Flashcard>
            if (parsed == null) throw IllegalStateException("flashcards JSON did not decode to a list")
            flashcardsUnreadable = false
            parsed
        } catch (e: Exception) {
            Log.e(TAG, "Could not parse stored flashcards; keeping them out of the save path", e)
            flashcardsUnreadable = true
            runCatching {
                flashPrefs.edit().putString("flashcards_key_corrupt", stored).apply()
            }
            emptyList()
        }
    }

    /**
     * PERF-14: serialise/seal/write the deck off the main thread.
     *
     * A save snapshots the whole deck; on a large deck Gson + Keystore + Base64 used to run inside
     * the click handler. Requests are serialised by [flashcardSaveMutex] and the generation check
     * lets an older queued snapshot be skipped - every snapshot is the complete deck, so writing
     * only the newest one loses nothing.
     */
    fun saveFlashcards(newList: List<Flashcard>) {
        val generation = flashcardSaveGeneration.incrementAndGet()
        viewModelScope.launch(Dispatchers.IO) {
            flashcardSaveMutex.withLock {
                if (generation == flashcardSaveGeneration.get()) persistFlashcards(newList)
            }
        }
    }

    /**
     * Awaitable variant for a screen that is about to navigate away (back press).
     *
     * R4-06: it reports whether the deck really reached storage. The Flashcard screen used to
     * navigate regardless, so a Keystore/seal failure silently threw away the last deck edit.
     * A skipped snapshot (a newer complete one is queued) counts as success: that snapshot holds
     * everything this one did and more.
     */
    suspend fun saveFlashcardsAndWait(newList: List<Flashcard>): Boolean {
        val generation = flashcardSaveGeneration.incrementAndGet()
        return withContext(Dispatchers.IO) {
            flashcardSaveMutex.withLock {
                if (generation == flashcardSaveGeneration.get()) persistFlashcards(newList) else true
            }
        }
    }

    private fun persistFlashcards(newList: List<Flashcard>): Boolean {
        // The stored deck could not be parsed, and this write is the empty list the failed load
        // produced: saving it would turn "unreadable" into "gone". A non-empty list means the user
        // is deliberately building a new deck, which is allowed (the old payload stays under
        // flashcards_key_corrupt either way).
        if (flashcardsUnreadable && newList.isEmpty()) {
            Log.w(TAG, "Refusing to overwrite unreadable flashcards with an empty list")
            return true
        }
        // R5-06: every step can fail - Gson serialisation, the Keystore seal, the prefs write.
        // Any of them must reach the caller as "not saved" so the Flashcard screen stays put
        // instead of navigating away from the only copy of the last edit.
        return try {
            val json = gson.toJson(newList, listType)
            // SEC-4: never fall back to plaintext. A failed seal leaves the stored deck as it
            // was, which is recoverable; writing readable JSON to disk is not.
            val sealed = noteCrypto.seal(json)
            // R6-05: apply() cannot report a disk-full/commit failure. Commit and treat false as
            // "not saved" so the Flashcard screen stays on the only copy of the last edit.
            val written = flashPrefs.edit()
                .putString("flashcards_key", sealed)
                .commit()
            if (!written) {
                Log.e(TAG, "Could not write the flashcards deck; keeping the stored deck untouched")
                showToast(context.getString(R.string.restore_fix_flashcards_save_failed), context)
                return false
            }
            flashcardsUnreadable = false
            true
        } catch (e: Exception) {
            Log.e(TAG, "Could not seal or store the flashcards; keeping the stored deck untouched", e)
            showToast(context.getString(R.string.restore_fix_flashcards_save_failed), context)
            false
        }
    }

    private val flashcardSaveMutex = Mutex()
    private val flashcardSaveGeneration = AtomicLong()

    /**
     * SEC-09: drops the in-memory plaintext when the app locks or the Vault session ends.
     *
     * Both observations are needed. `isLocked` covers an app credential; `vaultSessionActive`
     * covers the no-app-lock install whose Vault passphrase is evicted on background. An eviction
     * while a settings screen is open used to leave the typed backup password and any decrypted
     * text alive in this ViewModel - a heap dump could still read it after the gate went up.
     *
     * Clearing `password` also makes the next export re-prompt: the repository reports
     * `passwordRequired` again, and both Cloud.kt and Privacy.kt re-open the dialog for that URI.
     * Nothing persisted is cleared; only these process-lifetime copies. The block sits at the end
     * of the class so every field it clears is already initialized when the first emission lands.
     */
    init {
        viewModelScope.launch {
            lockManager.isLocked.collect { locked ->
                clearPlaintextAfterSessionEnd(locked, lockManager.vaultSessionActive.value)
            }
        }
        viewModelScope.launch {
            lockManager.vaultSessionActive.collect { active ->
                clearPlaintextAfterSessionEnd(lockManager.isLocked.value, active)
            }
        }
    }

    private fun clearPlaintextAfterSessionEnd(isLocked: Boolean, vaultSessionActive: Boolean) {
        if (!plaintext.shouldClear(isLocked, vaultSessionActive)) return
        plaintext.clear()
        // The scratchpad and the deck are read through loadNote()/loadFlashcards() rather than
        // cached here, but these two fields are the places any future caching would land; clearing
        // them keeps the "no decrypted copy survives the session" rule true if they are used.
        flashcardStorage = mutableListOf()
        _savedNote.value = ""
    }

    val version: String = BuildConfig.VERSION_NAME
    val build: String = BuildConfig.BUILD_TYPE
}
