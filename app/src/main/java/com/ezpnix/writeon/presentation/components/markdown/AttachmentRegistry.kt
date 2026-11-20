package com.ezpnix.writeon.presentation.components.markdown

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import com.ezpnix.writeon.core.attachment.AttachmentIndex
import com.ezpnix.writeon.core.attachment.AttachmentSyntax
import com.ezpnix.writeon.core.attachment.MediaVault
import com.ezpnix.writeon.domain.model.Attachment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * id -> file path for every attachment this process knows about.
 *
 * `mutableStateMapOf` on purpose: the editor shows a picture the moment it is picked, before its
 * row exists in the database, and a note list that loads attachments after the first frame
 * recomposes itself when the map fills in.
 *
 * This is a cache, never the source of truth - the `attachments` table is. A missing entry simply
 * renders as a placeholder until `NoteUseCase` refreshes the registry.
 *
 * ## SEC-4: encrypted media needs a second, asynchronous hop
 *
 * Attachments are encrypted at rest on write ([MediaVault.encryptInPlace]), while every renderer,
 * player and share sheet needs a real file path. [resolve]/[playablePath] therefore answer with the
 * stored path for a plaintext (legacy) file and with a decrypted cache copy for an encrypted one.
 * Decryption is blocking IO, so it never runs on the calling frame: the first call starts one
 * background materialisation and returns null, the caller draws its placeholder, and the completed
 * path lands in [materialized] - a `mutableStateMapOf`, so the frame that asked recomposes by
 * itself. That map is plaintext metadata and must be dropped when the app locks
 * ([clearMaterialized], called from `LockManager.evictSensitiveState`).
 *
 * Without [initialize] the registry keeps its pre-SEC-4 behaviour (the stored path is returned
 * unchanged). That is deliberate: only encrypted media needs the vault, so a preview, a widget or a
 * JVM test that never goes through `App.onCreate` still renders plaintext attachments.
 */
object AttachmentRegistry : AttachmentIndex {

    private val entries = mutableStateMapOf<String, Attachment>()

    /** Stored path -> the decrypted copy that was made for it. Written from the IO dispatcher. */
    private val materialized = mutableStateMapOf<String, Materialized>()

    /**
     * Stored path -> "is a `WMV1` container". Sniffed once per path by a header read that never
     * loads (or creates) the key, so scrolling a long note does not reopen every file per frame.
     */
    private val encrypted = ConcurrentHashMap<String, Boolean>()

    /**
     * Paths that could not be decrypted (missing key, corrupt frame, no cache space). Unlike a
     * not-yet-ready path, retrying on every frame would achieve nothing, so the placeholder stays.
     */
    private val unavailable = ConcurrentHashMap.newKeySet<String>()

    /** Paths with a materialisation in flight, so two frames start one decrypt, not two. */
    private val materializing = ConcurrentHashMap.newKeySet<String>()

    /**
     * Bumped by [clearMaterialized]. A decrypt that was already running when the app locked must
     * not publish its plaintext copy back into the cache the lock just emptied.
     */
    private val cacheEpoch = AtomicLong(0)

    /**
     * Set from `App.onCreate`; null until then (and in JVM tests / previews), which keeps the
     * legacy plaintext path behaviour.
     */
    @Volatile
    private var appContext: Context? = null

    /** Every job does blocking IO, so this is IO and not Main; a supervisor keeps one failure local. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * The cache copy plus the source's size/mtime, so [put] can notice a restore that installed
     * different bytes at the same `attachments/<id>.<ext>` path.
     */
    private data class Materialized(
        val cachePath: String,
        val size: Long,
        val lastModified: Long,
    )

    /** Wires the vault's cache directory into [resolve]; call once from `App.onCreate`. */
    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    override fun put(attachments: Collection<Attachment>) {
        attachments.forEach { put(it) }
    }

    override fun put(attachment: Attachment) {
        entries[attachment.id] = attachment
        // A row can be published again after a restore repaired its file, so a path that failed
        // earlier gets another attempt instead of keeping a permanent placeholder.
        unavailable.remove(attachment.path)
        dropReplacedMaterialization(attachment.path)
    }

    override fun remove(ids: Collection<String>) {
        ids.forEach { id ->
            val removed = entries.remove(id) ?: return@forEach
            forget(removed.path)
        }
    }

    fun attachment(id: String): Attachment? = entries[id]

    /**
     * The stored `attachments.path` value - the file on disk, encrypted or not. A renderer or a
     * player wants [playablePath] instead; this one is for callers that need the durable name
     * (renaming, deleting, writing a backup).
     */
    fun path(id: String): String? = entries[id]?.path

    /**
     * The path a renderer can open right now: the stored path for a plaintext (legacy) file, a
     * decrypted cache copy for an encrypted one, or null while that copy is still being made.
     *
     * Null means "draw a placeholder and ask again", never "the attachment is missing": a known
     * attachment whose file cannot be decrypted also returns null ([unavailable]).
     */
    fun playablePath(id: String): String? = entries[id]?.path?.let { playable(it) }

    /**
     * Turns an element target (`attachment://<id>`, or a 1.x absolute path that has not been
     * migrated yet) into something the image/audio renderers can open. Null means the id is known
     * but its row has not been loaded yet, or its decryption has not finished.
     */
    fun resolve(target: String): String? {
        val id = AttachmentSyntax.idOf(target) ?: return target
        return playablePath(id)
    }

    /**
     * A path whose bytes can be read right now, **blocking**: callers must be on `Dispatchers.IO`.
     *
     * This is the one-off variant of [playablePath] for exports and shares: it decrypts on the
     * calling thread instead of scheduling, returns a plaintext path unchanged, and null when an
     * encrypted file cannot be opened (wrong/missing key, no cache space) or does not exist. Null
     * is never "use the encrypted file": a save would then write ciphertext into the user's
     * document and look successful.
     */
    fun openablePath(path: String): String? {
        val context = appContext ?: return File(path).takeIf { it.isFile }?.absolutePath
        return MediaVault.materialize(context, path)
    }

    /**
     * Drops every decrypted path *and* the plaintext cache directory; called when the app locks.
     *
     * The classification is cleared too: a restore can replace a path's bytes while the process
     * lives, and a stale "was plaintext" answer must not skip decryption after that. A decrypt that
     * was already in flight is told to throw its result away, so it cannot repopulate the cache the
     * lock has just emptied.
     */
    fun clearMaterialized() {
        cacheEpoch.incrementAndGet()
        materialized.clear()
        unavailable.clear()
        encrypted.clear()
        appContext?.let { MediaVault.clearCache(it) }
    }

    // -----------------------------------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------------------------------

    private fun playable(path: String): String? {
        if (path.isBlank()) return null

        materialized[path]?.let { ready ->
            // The OS may evict cacheDir at any time: a missing copy is re-made below.
            if (File(ready.cachePath).isFile) return ready.cachePath
            materialized.remove(path)
        }

        val context = appContext ?: return path
        // Plaintext, legacy, missing or unreadable: the stored path is what the callers always got.
        if (!isEncrypted(context, path)) return path
        if (path in unavailable) return null

        if (materializing.add(path)) {
            scope.launch { materialize(context, path) }
        }
        // Not ready yet: the caller's placeholder is correct for this frame.
        return null
    }

    private fun materialize(context: Context, path: String) {
        val epoch = cacheEpoch.get()
        try {
            // Captured before the decrypt: a concurrent replace must not bless the old content.
            val source = File(path)
            val size = source.length()
            val lastModified = source.lastModified()

            val ready = MediaVault.materialize(context, path)
            if (epoch != cacheEpoch.get()) {
                // The app locked while this copy was being made. Publishing it would undo the
                // eviction, so the file is removed instead - the next frame re-materialises it.
                if (ready != null && ready != path) MediaVault.deleteCached(context, path)
                return
            }
            if (ready == null) {
                unavailable += path
            } else {
                materialized[path] = Materialized(ready, size, lastModified)
            }
        } finally {
            materializing.remove(path)
        }
    }

    private fun isEncrypted(context: Context, path: String): Boolean =
        encrypted.getOrPut(path) { MediaVault.isEncrypted(context, File(path)) }

    /**
     * A restore installs bytes at the same `attachments/<id>.<ext>` path, so a materialisation made
     * before it would otherwise keep serving the previous picture. The cache name already encodes
     * size and mtime, so comparing them is enough to know the copy is stale.
     */
    private fun dropReplacedMaterialization(path: String) {
        val ready = materialized[path] ?: return
        val source = File(path)
        if (!source.isFile || source.length() != ready.size || source.lastModified() != ready.lastModified) {
            forget(path)
        }
    }

    private fun forget(path: String) {
        materialized.remove(path)
        unavailable.remove(path)
        encrypted.remove(path)
    }
}
