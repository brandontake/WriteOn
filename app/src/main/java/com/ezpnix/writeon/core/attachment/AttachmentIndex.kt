package com.ezpnix.writeon.core.attachment

import com.ezpnix.writeon.domain.model.Attachment

/**
 * Write/read side of the process-wide attachment id -> row map.
 *
 * The domain layer needs to publish freshly saved attachments and drop the deleted ones, but the
 * registry itself is Compose state (`mutableStateMapOf`) and lives in presentation. This interface
 * is what lets [com.ezpnix.writeon.domain.usecase.NoteUseCase] do its job without depending on a
 * Compose singleton, and lets a test substitute an in-memory map.
 */
interface AttachmentIndex {
    fun put(attachments: Collection<Attachment>)
    fun put(attachment: Attachment)
    fun remove(ids: Collection<String>)
}
