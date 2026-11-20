package com.ezpnix.writeon.presentation.components

import android.net.Uri

/**
 * The file name a picked picture is copied to inside the attachment directory.
 *
 * This used to live in `ImageCleaner.kt` next to a `ContentObserver` that watched the whole
 * MediaStore and deleted a same-named `.jpg` from the external files dir on every gallery change -
 * including files this app had never written. That observer was removed (DAT-15), so only the naming
 * helper survives: the picker hands back a content URI with no usable display name, and the last ten
 * digits of its path keep the copied file distinct per pick. The random fallback only exists so two
 * URIs without digits do not overwrite each other.
 */
fun getImageName(uri: Uri?): String {
    val digits = uri?.lastPathSegment?.filter { it.isDigit() }?.takeLast(10)
    val name = digits ?: (1000..99999).random().toString()
    return "$name.jpg"
}
