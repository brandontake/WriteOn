package com.ezpnix.writeon.presentation.components.markdown

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import com.ezpnix.writeon.core.attachment.AttachmentArchive
import com.ezpnix.writeon.core.attachment.AttachmentFiles
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
     * PERF-12: short-lived answers to "does the decrypted copy still exist?".
     *
     * `playable` runs on every composition of every preview, and the split preview recomposes on
     * each debounce tick. A `File.isFile` per call is a disk stat on the UI thread; the answer only
     * has to be fresh within a couple of seconds, because a cache copy is either produced by this
     * class or evicted by the OS. The cache is invalidated explicitly whenever this class writes,
     * removes or clears a copy, and the decrypt path still checks the source before using one.
     */
    private val cacheFileExists = FileExistenceCache()

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

    /** PERF-12/R2-02: last observed source stat, trusted for the same window as the file cache. */
    private data class SourceStamp(
        val size: Long,
        val lastModified: Long,
        val checkedAt: Long,
    )

    private val sourceStamps = ConcurrentHashMap<String, SourceStamp>()

    /** Wires the vault's cache directory into [resolve]; call once from `App.onCreate`. */
    fun initialize(context: Context) {
        appContext = context.applicationContext
        // The allowlist depends on this Context's roots. Tests (and a restore-driven process that
        // re-initialises) may ask again with a different sandbox, so no answer is carried over.
        allowedLegacyPaths.clear()
        rejectedLegacyPaths.clear()
        allowedRowPaths.clear()
        rejectedRowPaths.clear()
        allowedOpenablePaths.clear()
        rejectedOpenablePaths.clear()
        // PERF-12/R2-02: every other cache is keyed by a path whose bytes may belong to the
        // previous Context (or to a file the legacy migration has since encrypted). A fresh
        // initialisation must not inherit "plaintext" or "unavailable" answers across processes.
        materialized.clear()
        unavailable.clear()
        encrypted.clear()
        cacheFileExists.clear()
        sourceStamps.clear()
    }

    override fun put(attachments: Collection<Attachment>) {
        attachments.forEach { put(it) }
    }

    override fun put(attachment: Attachment) {
        entries[attachment.id] = attachment
        // A row can be published again after a restore repaired its file, so a path that failed
        // earlier gets another attempt instead of keeping a permanent placeholder.
        unavailable.remove(attachment.path)
        // PERF-12: a re-published row may describe bytes that just changed on disk.
        cacheFileExists.invalidate(attachment.path)
        // R2-02: in-place encryption (the startup migration, or a save that seals a legacy file)
        // changes what the path contains. A cached "this one is plaintext" answer would keep
        // handing the raw ciphertext to Coil/MediaPlayer.
        encrypted.remove(attachment.path)
        sourceStamps.remove(attachment.path)
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
     * The path a renderer can open right now: the canonical stored path for a plaintext (legacy)
     * file, a decrypted cache copy for an encrypted one, or null while that copy is still being
     * made.
     *
     * Null means "draw a placeholder and ask again", never "the attachment is missing": a known
     * attachment whose file cannot be decrypted also returns null ([unavailable]), and a row whose
     * stored path is not inside this app's media roots is refused outright (REG-05).
     */
    fun playablePath(id: String): String? = entries[id]?.path?.let { playable(it) }

    /**
     * Turns an element target (`attachment://<id>`, or a 1.x absolute path that has not been
     * migrated yet) into something the image/audio renderers can open. Null means the id is known
     * but its row has not been loaded yet, or its decryption has not finished.
     *
     * SEC-06: a legacy path is **not** passed through any more. The old branch returned the string
     * untouched, and `ImageAttachmentBlock`/`AudioAttachmentBlock` hand it to Coil/MediaPlayer, so
     * a note that arrived through a share, a TXT import or a restore could make rendering request
     * `https://attacker/...` (or read a `file:`/`content:` URI) with no user action. Only a
     * canonical path inside one of this app's media roots is resolved now; everything else returns
     * null, which both blocks already render as their standard placeholder.
     */
    fun resolve(target: String): String? {
        val id = AttachmentSyntax.idOf(target)
        if (id != null) return playablePath(id)
        return resolveLegacyPath(target)
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
        // REG-05: an `attachments.path` row is not more trustworthy than a legacy body path - it
        // can arrive through a restore or a crafted database. The decrypted cache copy handed back
        // by a player is the one additional root that is legitimate here.
        val readable = canonicalRowPath(path, allowMediaCache = true) ?: return null
        val context = appContext ?: return File(readable).takeIf { it.isFile }?.absolutePath
        return MediaVault.materialize(context, readable)
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
        cacheFileExists.clear()
        sourceStamps.clear()
        appContext?.let { MediaVault.clearCache(it) }
    }

    /**
     * R2-02: drops the plaintext/ciphertext classification after a pass that rewrote files in place.
     *
     * The startup [com.ezpnix.writeon.core.attachment.LegacyMediaMigration] seals legacy media on a
     * background thread, while a screen may already have sniffed the same file as "plaintext" and
     * cached that answer forever. Without this, `playable` kept returning the raw path, which is now
     * a `WMV1` container: the image failed to load and "save to gallery" could copy ciphertext out.
     * Decrypted cache copies are dropped with the answers, and a decrypt that is still running is
     * told not to publish into the cache this clear just emptied.
     */
    fun clearEncryptionClassification() {
        clearMaterialized()
    }

    // -----------------------------------------------------------------------------------------
    // SEC-06: legacy path allowlist
    // -----------------------------------------------------------------------------------------

    /** Legacy targets already accepted; canonicalisation then runs once per path, not per frame. */
    private val allowedLegacyPaths = ConcurrentHashMap<String, String>()

    /** Legacy targets already refused (remote scheme, content URI, traversal, outside the roots). */
    private val rejectedLegacyPaths = ConcurrentHashMap.newKeySet<String>()

    /**
     * REG-05: the same allowlist for `attachment://<id>` row paths, which the old code returned
     * untouched. Canonicalisation is one disk stat per unique path, so it runs once here instead of
     * once per frame; a rejected path is remembered with the same one-time cost. The `openable`
     * pair is separate because it legitimately accepts one more root (`cacheDir/media`).
     */
    private val allowedRowPaths = ConcurrentHashMap<String, String>()
    private val rejectedRowPaths = ConcurrentHashMap.newKeySet<String>()
    private val allowedOpenablePaths = ConcurrentHashMap<String, String>()
    private val rejectedOpenablePaths = ConcurrentHashMap.newKeySet<String>()

    /**
     * Canonical path for a stored attachment row, or null when it is not one of this app's media
     * files. [allowMediaCache] is only for [openablePath]: the player receives the decrypted
     * `cacheDir/media` copy that was itself produced by [playable] and must be able to re-open it.
     */
    private fun canonicalRowPath(path: String, allowMediaCache: Boolean = false): String? {
        if (path.isBlank()) return null
        val allowed = if (allowMediaCache) allowedOpenablePaths else allowedRowPaths
        val rejected = if (allowMediaCache) rejectedOpenablePaths else rejectedRowPaths
        allowed[path]?.let { return it }
        if (path in rejected) return null
        val context = appContext
        val extraRoots = if (allowMediaCache && context != null) {
            listOf(File(context.cacheDir, MediaVault.CACHE_DIRECTORY))
        } else {
            emptyList()
        }
        val resolved = canonicalMediaPath(context, path, extraRoots)
        if (resolved == null) {
            rejected += path
        } else {
            allowed[path] = resolved
        }
        return resolved
    }

    private fun resolveLegacyPath(target: String): String? {
        allowedLegacyPaths[target]?.let { return it }
        if (target in rejectedLegacyPaths) return null
        val resolved = canonicalMediaPath(appContext, target)
        if (resolved == null) {
            rejectedLegacyPaths += target
        } else {
            allowedLegacyPaths[target] = resolved
        }
        return resolved
    }

    // -----------------------------------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------------------------------

    private fun playable(path: String): String? {
        if (path.isBlank()) return null
        // REG-05: the stored `attachments.path` is validated exactly like a legacy body target.
        // Without this, a constructed or restored row could hand `https:`, `content:`, a `..`
        // traversal or a path outside the media roots to Coil/MediaPlayer. Null renders the
        // standard placeholder.
        val readable = canonicalRowPath(path) ?: return null

        materialized[readable]?.let { ready ->
            // PERF-12: the OS may evict cacheDir at any time, so the copy is re-checked; the
            // short-lived cache makes that check a field read instead of one stat per composition.
            // R2-02: the source is re-checked on the same TTL, because an in-place encryption (the
            // legacy migration) can change the bytes under a cache copy that still exists.
            if (cacheFileExists.isFile(ready.cachePath) && sourceUnchanged(readable, ready)) {
                return ready.cachePath
            }
            forget(readable)
        }

        val context = appContext ?: return readable
        // Plaintext, legacy, missing or unreadable: the canonical stored path is handed back.
        // R2-02: the canonical path is what gets sniffed and decrypted, not the raw row spelling.
        if (!isEncrypted(context, readable)) return readable
        if (readable in unavailable) return null

        if (materializing.add(readable)) {
            scope.launch { materialize(context, readable) }
        }
        // Not ready yet: the caller's placeholder is correct for this frame.
        return null
    }

    /**
     * PERF-12/R2-02: whether [readable] still has the size/mtime the cached copy was built from.
     *
     * A stat per composition would be its own cost, so the answer is trusted for the same short
     * window as [cacheFileExists]; a replace or in-place encryption is therefore visible within a
     * couple of seconds even when the caller never re-published the row.
     */
    private fun sourceUnchanged(readable: String, ready: Materialized): Boolean {
        val now = System.currentTimeMillis()
        val cached = sourceStamps[readable]
        if (cached != null && now >= cached.checkedAt &&
            now - cached.checkedAt < FILE_EXISTENCE_TTL_MS
        ) {
            return cached.size == ready.size && cached.lastModified == ready.lastModified
        }
        val source = File(readable)
        val size = source.length()
        val lastModified = source.lastModified()
        sourceStamps[readable] = SourceStamp(size, lastModified, now)
        return size == ready.size && lastModified == ready.lastModified
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
                // PERF-12: the file this entry describes was just written; drop any stale answer
                // so the next caller observes the new copy.
                cacheFileExists.invalidate(ready)
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
        // PERF-12: the decrypted copy is what the existence cache is keyed on, so forget it with
        // the entry that named it.
        materialized.remove(path)?.let { cacheFileExists.invalidate(it.cachePath) }
        unavailable.remove(path)
        encrypted.remove(path)
        sourceStamps.remove(path)
    }
}

/**
 * PERF-12: a tiny time-to-live cache for `File(path).isFile`.
 *
 * [AttachmentRegistry.playable] asked the filesystem on every composition. Attachment previews
 * recompose on each debounce tick and on scroll, so the same answer was re-read dozens of times a
 * second while it can only change when this process (or the OS cache eviction) touches the file.
 * A 1.5 s window keeps a display correct for any user-visible delay while turning the repeated
 * calls into a map lookup; explicit invalidation covers the writes this process performs.
 *
 * [now] and [exists] are injectable so the policy - including a clock that steps backwards - is
 * pinned by a plain JVM test without Robolectric or a real file.
 */
internal class FileExistenceCache(
    private val ttlMillis: Long = FILE_EXISTENCE_TTL_MS,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val exists: (String) -> Boolean = { File(it).isFile },
) {
    private data class Entry(val exists: Boolean, val checkedAt: Long)

    private val entries = ConcurrentHashMap<String, Entry>()

    fun isFile(path: String): Boolean {
        val checkedAt = now()
        val cached = entries[path]
        // A negative age means the clock moved backwards; treat it as expired rather than trust
        // an answer of unknowable age.
        if (cached != null && checkedAt >= cached.checkedAt &&
            checkedAt - cached.checkedAt < ttlMillis
        ) {
            return cached.exists
        }
        return exists(path).also { entries[path] = Entry(it, checkedAt) }
    }

    fun invalidate(path: String) {
        entries.remove(path)
    }

    fun clear() {
        entries.clear()
    }
}

/** How long one existence answer is trusted. Well below any UI-visible eviction delay. */
internal const val FILE_EXISTENCE_TTL_MS = 1_500L

/**
 * SEC-06: the canonical path [target] may name, or null when it is not a local file this app owns.
 *
 * - Any URI scheme (`http:`, `https:`, `file:`, `content:`, ...) is refused outright: legacy note
 *   bodies only ever contained plain absolute paths, and a scheme is what turns rendering into a
 *   network request or a read outside the app sandbox.
 * - A path outside `filesDir/attachments`, `filesDir/audios` and the app-specific external files
 *   roots is refused. The check uses `canonicalPath` plus a prefix ending in a separator, so
 *   `attachments/../../shared_prefs/x.xml`, a sibling whose name merely starts with the root name,
 *   and symlinks that resolve elsewhere all fall outside.
 * - [extraRoots] are added for the one caller that may legitimately receive a decrypted cache copy
 *   (see `AttachmentRegistry.openablePath`); the legacy/row policies pass none.
 * - A null [context] is the documented "registry was never initialised" mode (previews, plain JVM
 *   tests). The scheme check still applies; the root check cannot run without a Context.
 */
internal fun canonicalMediaPath(
    context: Context?,
    target: String,
    extraRoots: List<File> = emptyList(),
): String? {
    val trimmed = target.trim()
    if (trimmed.isEmpty()) return null
    if (hasUriScheme(trimmed)) return null
    val file = File(trimmed)
    if (!file.isAbsolute) return null
    val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return null
    val roots = if (context != null) mediaRoots(context) + extraRoots else extraRoots
    if (roots.isEmpty()) return canonical.path
    return canonical.path.takeIf { path ->
        roots.any { root -> path.startsWith(root.path + File.separator) }
    }
}

/** True for `scheme:...`; a POSIX path starts with `/`, so its leading separator wins. */
private fun hasUriScheme(target: String): Boolean {
    val colon = target.indexOf(':')
    if (colon <= 0) return false
    val separator = target.indexOfAny(charArrayOf('/', '\\'))
    if (separator != -1 && colon > separator) return false
    // A single letter before the colon is a Windows drive in the JVM test environment, not a URI
    // scheme (no real scheme is one character, and device paths are POSIX).
    return colon > 1 || !target[0].isLetter()
}

/** The roots [canonicalMediaPath] may return a file from: today's storage and the 2.0.x one. */
private fun mediaRoots(context: Context): List<File> = buildList {
    add(File(context.filesDir, AttachmentArchive.DIRECTORY))
    add(File(context.filesDir, AttachmentFiles.AUDIO_DIRECTORY))
    // 2.0.x put picked and shared pictures in the app-specific external directory (and recordings
    // in an `audios` folder below it). Those files were never migrated, so they stay renderable.
    runCatching { context.getExternalFilesDir(null) }.getOrNull()?.let { external ->
        add(external)
        add(File(external, AttachmentFiles.AUDIO_DIRECTORY))
    }
}.mapNotNull { runCatching { it.canonicalFile }.getOrNull() }
