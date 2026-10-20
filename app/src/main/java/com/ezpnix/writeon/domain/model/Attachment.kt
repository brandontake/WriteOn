package com.ezpnix.writeon.domain.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A file (image or voice recording) that a note body points at.
 *
 * The body itself only stores `attachment://<id>`; the path lives here. That indirection is the
 * whole point of 2.0: renaming, moving or re-labelling a file no longer has to rewrite the note
 * text, and a note deleted from the trash can find its files without regex-scanning every
 * description for private `!(...)` / `?audio(...)` syntax.
 *
 * Files are still written to the same folders as before (images in the app-specific external
 * files dir, recordings in `filesDir/audios`); this table is metadata, not a new storage root.
 */
@Entity(
    tableName = "attachments",
    indices = [Index(value = ["note-id"])]
)
data class Attachment(
    @PrimaryKey
    @ColumnInfo(name = "attachment-id")
    val id: String,

    /**
     * Owning note. Zero means "created by an editor that has not saved yet"; the editor claims
     * those rows by assigning the real id once the note exists (see `assignNote`).
     */
    @ColumnInfo(name = "note-id")
    val noteId: Int = 0,

    @ColumnInfo(name = "type")
    val type: String,

    @ColumnInfo(name = "path")
    val path: String,

    /**
     * Optional caption. Images ignore it; recordings show it (falling back to the file name, then
     * to the generic string resource) through `AudioLabelStore`.
     */
    @ColumnInfo(name = "caption")
    val caption: String = "",

    @ColumnInfo(name = "created_at")
    val createdAt: Long = 0L
) {

    companion object {
        const val TYPE_IMAGE = "image"
        const val TYPE_AUDIO = "audio"
    }
}
