package com.ezpnix.writeon.presentation.screens.library

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ezpnix.writeon.domain.model.Attachment
import com.ezpnix.writeon.domain.usecase.NoteUseCase
import com.ezpnix.writeon.presentation.lock.LockManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Backs the drawer's Library section.
 *
 * Both drawer rows lead to the same screen; the route argument picks the attachment type, and the
 * use case hands out every live attachment of that type across the notes. Vault notes and notes
 * that already sit in the recycle bin stay out (see `NoteUseCase.libraryNoteIds`), so the screen
 * only ever shows what it may also edit.
 */
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val noteUseCase: NoteUseCase,
    private val lockManager: LockManager,
) : ViewModel() {

    fun attachmentsFlow(type: String): Flow<List<Attachment>> =
        noteUseCase.getLibraryAttachmentsFlow(type)

    /**
     * Moves the attachment to the recycle bin and takes its line out of the owning note.
     *
     * R5-03: a vault note's body is decrypted and sealed again here. Both the unlock lock and the
     * exception guard are what keep a Vault eviction (or a concurrent candidate passphrase) from
     * crashing the screen or writing the row with the wrong key.
     */
    fun trashAttachment(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                lockManager.withVaultUnlock { noteUseCase.trashAttachment(id) }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "Could not move the attachment to the recycle bin", error)
            }
        }
    }

    private companion object {
        const val TAG = "LibraryViewModel"
    }
}
