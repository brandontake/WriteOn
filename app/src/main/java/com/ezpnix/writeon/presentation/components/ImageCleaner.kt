package com.ezpnix.writeon.presentation.components

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import java.io.File

class GalleryObserver(handler: Handler, private val context: Context) : ContentObserver(handler) {
    override fun onChange(selfChange: Boolean, uri: Uri?) {
        super.onChange(selfChange, uri)
        uri?.let {
            deleteFileIfExists(context, getImageName(uri))
        }
    }
}

/**
 * Watches MediaStore so exported note images are removed once the gallery picks them up.
 *
 * The returned observer must be unregistered by the caller: registering more than once leaves every
 * earlier registration live for the process lifetime, each one holding the context it was given.
 */
fun registerGalleryObserver(context: Context): GalleryObserver {
    val handler = Handler(Looper.getMainLooper())
    val galleryObserver = GalleryObserver(handler, context.applicationContext)
    context.contentResolver.registerContentObserver(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        true,
        galleryObserver
    )
    return galleryObserver
}

fun unregisterGalleryObserver(context: Context, observer: GalleryObserver) {
    runCatching { context.contentResolver.unregisterContentObserver(observer) }
}

fun deleteFileIfExists(context: Context, fileName: String): Boolean {
    val appStorageDir = getExternalStorageDir(context)
    val file = File(appStorageDir, fileName)
    return if (file.exists()) {
        file.delete()
    } else {
        false
    }
}


fun getExternalStorageDir(context: Context): File {
    return context.getExternalFilesDir(null) ?: throw IllegalStateException("External storage directory not found")
}

fun getImageName(uri: Uri?): String {
    return if (uri != null) {
        uri.lastPathSegment?.filter { it.isDigit() }?.takeLast(10) ?: (1000..99999).random().toString()
    } else {
        (1000..99999).random().toString()
    } + ".jpg"
}