package com.ezpnix.writeon.presentation.screens.settings.model

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
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
import com.ezpnix.writeon.presentation.screens.settings.BackupWorker
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import androidx.compose.ui.res.stringResource

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val backup: ImportExportRepository,
    private val settingsUseCase: SettingsUseCase,
    val noteUseCase: NoteUseCase,
    private val importExportUseCase: ImportExportUseCase,
    private val settingsRepository: SettingsRepositoryImpl,
    @ApplicationContext private val context: Context,
    private val settingsPreferences: SettingsPreferences,
    private val noteCrypto: NoteCrypto,
) : ViewModel() {

    private companion object {
        const val TAG = "SettingsViewModel"
    }

    private val _savedNote = MutableStateFlow("")
    val savedNote: String get() = _savedNote.value

    val databaseUpdate = mutableStateOf(false)
    var password: String? = null

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
        _settings.value = _settings.value.copy(termsOfService = accepted)
        viewModelScope.launch {
            settingsRepository.saveTermsOfService(accepted)
        }
    }

    fun update(newSettings: Settings) {
        _settings.value = newSettings.copy()
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
    fun saveNote(note: String) {
        val stored = runCatching { noteCrypto.seal(note) }.getOrNull()
        if (stored == null) {
            // Never fall back to plaintext: failing the save is recoverable, leaking the text is not.
            showToast(context.getString(R.string.error), context)
            return
        }
        sharedPreferences.edit().putString("note", stored).apply()
    }

    fun loadNote(): String {
        val stored = sharedPreferences.getString("note", "") ?: ""
        return noteCrypto.openOrNull(stored) ?: ""
    }

    fun updatePlaceholder(newText: String) {
        viewModelScope.launch {
            settingsPreferences.savePlaceholder(newText)
            _dynamicPlaceholder.value = newText
        }
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
            val result = backup.exportBackup(uri, password)
            handleBackupResult(result, context)
            databaseUpdate.value = true
        }
    }

    fun onImportBackup(uri: Uri, context: Context) {
        viewModelScope.launch {
            val result = backup.importBackup(uri, password)
            handleBackupResult(result, context)
            databaseUpdate.value = true
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
        val result = backup.importBackup(uri, password)
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
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
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
        val json = flashPrefs.getString("flashcards_key", null)
        if (json == null) {
            flashcardsUnreadable = false
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
            // Keep a copy of the unreadable payload: overwriting the key is exactly the failure
            // mode this branch exists to prevent, so the bytes must survive somewhere recoverable.
            runCatching {
                flashPrefs.edit().putString("flashcards_key_corrupt", json).apply()
            }
            emptyList()
        }
    }

    fun saveFlashcards(newList: List<Flashcard>) {
        // The stored deck could not be parsed, and this write is the empty list the failed load
        // produced: saving it would turn "unreadable" into "gone". A non-empty list means the user
        // is deliberately building a new deck, which is allowed (the old payload stays under
        // flashcards_key_corrupt either way).
        if (flashcardsUnreadable && newList.isEmpty()) {
            Log.w(TAG, "Refusing to overwrite unreadable flashcards with an empty list")
            return
        }
        val json = gson.toJson(newList, listType)
        flashPrefs.edit()
            .putString("flashcards_key", json)
            .apply()
        flashcardsUnreadable = false
    }

    val version: String = BuildConfig.VERSION_NAME
    val build: String = BuildConfig.BUILD_TYPE
}
