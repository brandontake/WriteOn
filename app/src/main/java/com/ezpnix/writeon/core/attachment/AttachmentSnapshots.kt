package com.ezpnix.writeon.core.attachment

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The attachment half of a `"restore previous database"` snapshot.
 *
 * The database `.bak` records paths into `filesDir/attachments` and `filesDir/audios`, but those
 * bytes are not part of it. Before this object existed, undoing a restore put the old rows back
 * while the files still belonged to the archive that had just been restored - images and
 * recordings came back as "missing" even though the database claimed they were there.
 *
 * A snapshot is captured immediately before a restore touches anything and is tied to the `.bak`
 * by [com.ezpnix.writeon.data.backup.SnapshotSidecar]. Files are hard-linked where the filesystem
 * allows it (the normal case: both trees live under the app's internal data directory), so a
 * snapshot costs directory entries rather than a second copy of every recording. `Files.createLink`
 * falls back to a byte copy on filesystems that refuse links; the operation is all-or-nothing, and
 * the restore is refused if the snapshot cannot be completed.
 *
 * **The hard link relies on one invariant:** attachment bytes are immutable after creation. Every
 * writer creates a sibling and replaces the directory entry - picker/share copies, new recordings,
 * restore installs and `MediaVault.encryptInPlace` all do - so the snapshot keeps the old inode.
 * Code that truncates or rewrites an existing attachment in place would mutate the snapshot too;
 * any such path must replace the file instead (or stop using this object's link fast path).
 *
 * Snapshots are deliberately **not** encrypted with a second key: they contain the same `WMV1`
 * ciphertext (or a legacy plaintext file) as the live attachment. Restoring a snapshot moves bytes
 * back unchanged, so the media key and the reader's plaintext fallback behave exactly as before.
 * The decrypted `cacheDir/media` copies are cleared after a swap.
 *
 * Only the newest snapshot is kept. It is the one paired with the one `.bak` the app offers; older
 * snapshots describe databases that no longer exist and are pruned on the next successful restore.
 */
object AttachmentSnapshots {

    /** Durable root: `noBackupFilesDir`, like the install journal, so the OS cannot evict it. */
    const val ROOT_DIRECTORY = "attachment-snapshots"

    private const val TAG = "AttachmentSnapshots"
    private const val ID_PREFIX = "snapshot-"
    private const val ID_PATTERN = "^snapshot-[A-Za-z0-9-]+$"

    /** The two app-owned media roots a database row can point at. */
    private val ROOTS = listOf("attachments", "audios")

    fun root(context: Context): File = File(context.noBackupFilesDir, ROOT_DIRECTORY)

    fun directory(context: Context, id: String): File = File(root(context), id)

    /**
     * Captures every file under the two live roots into a new snapshot.
     *
     * @return the snapshot id, or null when it could not be completed. Callers must abort the
     *   restore on null: a restore without a paired attachment snapshot would leave the undo
     *   database pointing at files it cannot bring back.
     */
    fun capture(context: Context, id: String = newId()): String? {
        if (!id.matches(Regex(ID_PATTERN))) return null
        val root = root(context).apply { mkdirs() }
        val partial = File(root, "$id.partial")
        val target = File(root, id)
        return try {
            partial.deleteRecursively()
            partial.mkdirs()
            ROOTS.forEach { name ->
                val sourceDir = File(context.filesDir, name)
                val targetDir = File(partial, name).apply { mkdirs() }
                sourceDir.listFiles().orEmpty()
                    .filter { it.isFile }
                    .forEach { source -> linkOrCopy(source, File(targetDir, source.name)) }
            }
            target.deleteRecursively()
            if (!moveDirectory(partial, target)) {
                throw IOException("Could not publish attachment snapshot $id")
            }
            id
        } catch (e: Exception) {
            log("Could not capture attachment snapshot", e)
            partial.deleteRecursively()
            target.deleteRecursively()
            null
        }
    }

    /** True when [id] names a complete snapshot (the partial directory is never visible here). */
    fun exists(context: Context, id: String?): Boolean =
        id != null && directory(context, id).isDirectory

    /**
     * Replaces the live media trees with [id], keeping the snapshot intact so the caller can delete
     * it only after the database rotation succeeded.
     *
     * The live trees are moved aside first (same filesystem, so a rename), then the snapshot is
     * linked into their place. Any failure restores the moved-aside trees before returning false;
     * the caller then rolls the database/prefs back too.
     */
    fun install(context: Context, id: String): Boolean {
        if (!id.matches(Regex(ID_PATTERN))) return false
        val snapshot = directory(context, id)
        if (!snapshot.isDirectory) return false

        val root = root(context).apply { mkdirs() }
        val swap = File(root, ".swap-$id-${System.currentTimeMillis()}")
        val movedAside = ArrayList<Pair<File, File>>()
        val liveDirs = ArrayList<File>()

        return try {
            swap.mkdirs()
            ROOTS.forEach { name ->
                val live = File(context.filesDir, name)
                val parked = File(swap, name)
                if (live.exists()) {
                    if (!moveDirectory(live, parked)) {
                        throw IOException("Could not park live $name")
                    }
                    movedAside += live to parked
                }
                if (!live.mkdirs() && !live.isDirectory) {
                    throw IOException("Could not recreate $name")
                }
                liveDirs += live
                val source = File(snapshot, name)
                if (source.isDirectory) {
                    source.listFiles().orEmpty()
                        .filter { it.isFile }
                        .forEach { file -> linkOrCopy(file, File(live, file.name)) }
                }
            }
            // MediaVault serves decrypted copies from cacheDir; they describe the files just
            // replaced, so they must not survive the swap even if a caller forgets to lock.
            runCatching { MediaVault.clearCache(context) }
            swap.deleteRecursively()
            true
        } catch (e: Exception) {
            log("Could not install attachment snapshot $id", e)
            // Drop the half-built live trees, then put the originals back in reverse order.
            liveDirs.asReversed().forEach { runCatching { it.deleteRecursively() } }
            movedAside.asReversed().forEach { (live, parked) ->
                runCatching {
                    live.deleteRecursively()
                    if (!moveDirectory(parked, live)) {
                        log("Could not put $live back after a failed snapshot swap", null)
                    }
                }
            }
            swap.deleteRecursively()
            false
        }
    }

    /** Best-effort removal; a missing directory is already the desired end state. */
    fun delete(context: Context, id: String?) {
        if (id == null || !id.matches(Regex(ID_PATTERN))) return
        runCatching { directory(context, id).deleteRecursively() }
    }

    /**
     * Keeps [keepId] and removes every other complete, partial or interrupted swap directory.
     *
     * Called after a restore or undo has published the new sidecar, so anything else belongs to a
     * database that no longer exists. A null [keepId] leaves the tree empty (a restore on a fresh
     * install has no `.bak` to pair with).
     */
    fun pruneExcept(context: Context, keepId: String?) {
        val keep = keepId?.takeIf { it.matches(Regex(ID_PATTERN)) }
        root(context).listFiles().orEmpty().forEach { entry ->
            if (entry.name == keep) return@forEach
            if (entry.name.startsWith(ID_PREFIX) ||
                entry.name.startsWith(".swap-") ||
                entry.name.endsWith(".partial")
            ) {
                runCatching { entry.deleteRecursively() }
            }
        }
    }

    fun newId(): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
        return "$ID_PREFIX$stamp-${System.nanoTime() % 1_000_000}"
    }

    /**
     * Hard-links [source] to [target], falling back to a synced copy.
     *
     * A hard link is what makes the snapshot cheap; the copy fallback is for filesystems that do
     * not support links from the app's data directory. Both leave the source untouched.
     */
    private fun linkOrCopy(source: File, target: File) {
        target.delete()
        target.parentFile?.mkdirs()
        try {
            Files.createLink(target.toPath(), source.toPath())
            return
        } catch (e: Exception) {
            // Expected on some filesystems; fall through to the portable path.
        }
        try {
            FileInputStream(source).use { input ->
                FileOutputStream(target).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
        } catch (e: Exception) {
            target.delete()
            throw IOException("Could not snapshot ${source.name}", e)
        }
    }

    /**
     * Moves a directory, preferring an atomic rename on the same filesystem.
     *
     * `copyRecursively` is the fallback for a provider-backed or emulated directory where rename
     * crosses a mount; only files are copied, matching the flat layout these roots have.
     */
    private fun moveDirectory(source: File, target: File): Boolean {
        target.deleteRecursively()
        target.parentFile?.mkdirs()
        return try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
            true
        } catch (e: Exception) {
            runCatching {
                target.mkdirs()
                source.listFiles().orEmpty().filter { it.isFile }.forEach { file ->
                    file.copyTo(File(target, file.name), overwrite = true)
                }
                source.deleteRecursively()
                target.isDirectory
            }.getOrDefault(false)
        }
    }

    private fun log(message: String, error: Exception?) {
        // Log is a stub on the plain-JVM test runtime; a failed log must not turn a snapshot
        // failure into another exception.
        runCatching { if (error == null) Log.w(TAG, message) else Log.w(TAG, message, error) }
    }
}
