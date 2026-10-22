package com.ezpnix.writeon.presentation.screens.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ezpnix.writeon.domain.model.Attachment
import com.ezpnix.writeon.domain.usecase.NoteUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
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
    private val noteUseCase: NoteUseCase
) : ViewModel() {

    fun attachmentsFlow(type: String): Flow<List<Attachment>> =
        noteUseCase.getLibraryAttachmentsFlow(type)

    /** Moves the attachment to the recycle bin and takes its line out of the owning note. */
    fun trashAttachment(id: String) {
        viewModelScope.launch { noteUseCase.trashAttachment(id) }
    }
}
