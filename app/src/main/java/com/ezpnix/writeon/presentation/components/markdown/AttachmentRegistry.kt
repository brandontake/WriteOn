package com.ezpnix.writeon.presentation.components.markdown

import androidx.compose.runtime.mutableStateMapOf
import com.ezpnix.writeon.core.attachment.AttachmentSyntax
import com.ezpnix.writeon.domain.model.Attachment

/**
 * id -> file path for every attachment this process knows about.
 *
 * `mutableStateMapOf` on purpose: the editor shows a picture the moment it is picked, before its
 * row exists in the database, and a note list that loads attachments after the first frame
 * recomposes itself when the map fills in.
 *
 * This is a cache, never the source of truth - the `attachments` table is. A missing entry simply
 * renders as a placeholder until `NoteUseCase` refreshes the registry.
 */
object AttachmentRegistry {

    private val entries = mutableStateMapOf<String, Attachment>()

    fun put(attachments: Collection<Attachment>) {
        attachments.forEach { entries[it.id] = it }
    }

    fun put(attachment: Attachment) {
        entries[attachment.id] = attachment
    }

    fun remove(ids: Collection<String>) {
        ids.forEach { entries.remove(it) }
    }

    fun attachment(id: String): Attachment? = entries[id]

    fun path(id: String): String? = entries[id]?.path

    /**
     * Turns an element target (`attachment://<id>`, or a 1.x absolute path that has not been
     * migrated yet) into something the image/audio renderers can open. Null means the id is known
     * but its row has not been loaded yet.
     */
    fun resolve(target: String): String? {
        val id = AttachmentSyntax.idOf(target) ?: return target
        return entries[id]?.path
    }
}
