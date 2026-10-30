package com.ezpnix.writeon.domain.repository

import android.net.Uri
import com.ezpnix.writeon.domain.model.Note

/**
 * Turns a shared/imported file into a note.
 *
 * The domain layer needs "give me the note this Uri holds" without depending on the concrete
 * repository (which is a data-layer class with ContentResolver and MediaStore imports); a test
 * substitutes a fake that returns a fixed [Note].
 */
interface NoteFileReader {
    fun importFile(uri: Uri): Note
}
