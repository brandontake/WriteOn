package com.ezpnix.writeon.data.backup

import android.app.Application
import android.content.Context
import android.util.Log
import android.widget.Toast
import com.ezpnix.writeon.R
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ezpnix.writeon.core.util.BackupArchiveCrypto
import com.ezpnix.writeon.core.util.BackupRestoreLock
import com.ezpnix.writeon.core.constant.DatabaseConst
import com.ezpnix.writeon.data.local.database.BackupDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.zip.ZipOutputStream

/**
 * Uploads the notes database to the configured WebDAV server.
 *
 * The worker used to expect a `notes-backup-encrypted.zip` that was already sitting in the cache
 * directory. Nothing in the app ever wrote that file, so the worker returned failure on its first
 * check and cloud backup had never actually uploaded anything. The archive is now built here on
 * demand, from the live database, using the same layout the local encrypted export produces - so a
 * file downloaded from the server can be restored through either path.
 *
 * Credentials come from the same DataStore the settings screen writes to (see [WebDavPrefs]).
 * Before that fix the worker read a plain SharedPreferences file with the same name while the
 * settings screen wrote a DataStore, so the URL always came back empty.
 */
open class WebDAVBackupWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    /**
     * W10/W11: the shared client carries the metadata call timeout and the "never follow a
     * redirect" policy. A bare `OkHttpClient()` had neither. Uploads clone it per transfer with
     * the SYN-7 size-scaled budget instead.
     */
    private val client = WebDavHttp.client()

    /** WorkManager runs on a background thread; Toast must be posted to the main looper. */
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private fun toastOnMain(message: String) {
        mainHandler.post {
            Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Terminal failure with a user-visible outcome (W3): backup results used to exist only in
     * logcat, so a failed run looked identical to a successful one from inside the app.
     */
    private fun failWithToast(
        messageRes: Int = R.string.cloud_backup_failed,
    ): Result {
        toastOnMain(applicationContext.getString(messageRes))
        return Result.failure()
    }

    /**
     * W5: terminal failure when the server exposes neither an ETag nor a Last-Modified for a
     * file that exists. Without any validator a conditional PUT degrades into a bare overwrite
     * of a file this device cannot tell apart from its own - the exact silent data loss the
     * "never silently overwritten" promise exists to prevent. The user has to opt in through
     * "take over the remote" (forceOverwrite) instead.
     */
    private fun failWithNoValidator(): Result {
        Log.e(TAG, "Refusing to overwrite a remote archive that exposes no ETag or Last-Modified")
        toastOnMain(applicationContext.getString(R.string.cloud_backup_no_validator))
        return Result.failure()
    }

    /**
     * W9: retries only outcomes that can clear on their own, and only while attempts remain.
     *
     * Both workers used to ask for another run on *every* transport error or 5xx with no ceiling.
     * WorkManager does not cap attempts itself, so a permanently broken configuration (a deleted
     * collection, a revoked app password behind a 401) kept the unique work chain alive forever -
     * and because the chain is shared, every later backup was appended behind a run that could
     * never succeed.
     */
    private fun retryOrFail(statusCode: Int, detail: String): Result {
        if (WebDavRetry.shouldRetry(statusCode, runAttemptCount)) {
            Log.w(TAG, "$detail (HTTP $statusCode); attempt ${runAttemptCount + 1} of ${WebDavRetry.MAX_RUN_ATTEMPTS + 1}")
            return Result.retry()
        }
        Log.e(TAG, "$detail (HTTP $statusCode); giving up after ${runAttemptCount + 1} attempts")
        return failWithToast()
    }

    /**
     * W11: a 3xx is never a success and never worth repeating.
     *
     * With redirects disabled the worker finally sees the redirect instead of OkHttp replaying the
     * request as a `GET`; repeating it against the same URL produces the same redirect, so this is
     * terminal and the user is told the upload did not happen.
     */
    private fun failWithRedirect(statusCode: Int): Result {
        Log.e(TAG, "Refusing a WebDAV redirect (HTTP $statusCode) instead of following it")
        toastOnMain(applicationContext.getString(R.string.cloud_webdav_redirect_refused))
        return Result.failure()
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val archive = File(applicationContext.cacheDir, BACKUP_FILENAME)
        try {
            val prefs = applicationContext.webDavDataStore.data.first()
            val webdavUrl = prefs[stringPreferencesKey(WebDavPrefs.URL)]
            val username = prefs[stringPreferencesKey(WebDavPrefs.USERNAME)]
            val encryptedPassword = prefs[stringPreferencesKey(WebDavPrefs.PASSWORD)]
            val encryptedPassphrase = prefs[stringPreferencesKey(WebDavPrefs.BACKUP_PASSPHRASE)]

            if (webdavUrl.isNullOrBlank() || username.isNullOrBlank() ||
                encryptedPassword.isNullOrBlank() || encryptedPassphrase.isNullOrBlank()
            ) {
                // Nothing to upload until the WebDAV settings and the archive passphrase are filled
                // in. Retrying would only spin, so this is terminal.
                return@withContext failWithToast()
            }

            // Cleartext http is accepted on purpose (see the note in Cloud.kt and the
            // `usesCleartextTraffic` attribute in the manifest). Do not add a rejection here.

            val aead = WebDavPrefsCipher.aead(applicationContext)
            // T3: the stored Tink ciphertext is unreadable (usually the Keystore was reset).
            // A dedicated message tells the user to re-enter credentials instead of reporting a
            // generic upload failure they would retry forever.
            val password = WebDavPrefsCipher.decryptOrNull(aead, encryptedPassword)
                ?: return@withContext failWithToast(R.string.cloud_credentials_unreadable)
            val passphrase = WebDavPrefsCipher.decryptOrNull(aead, encryptedPassphrase)
                ?: return@withContext failWithToast(R.string.cloud_credentials_unreadable)

            val authorization = Credentials.basic(username, password)
            // SYN-5/SYN-11: the configured address must be an absolute, file-shaped URL. Building
            // it through HttpUrl also percent-encodes non-ASCII and space characters, which is what
            // makes the Destination and If headers built further down legal ASCII.
            val canonicalUrl = try {
                WebDavPublish.requireFileUrl(webdavUrl)
            } catch (e: InvalidWebDavUrlException) {
                Log.e(TAG, "The configured WebDAV URL is unusable: ${e.message}")
                return@withContext failWithToast(
                    if (e.folder) R.string.webdav_url_points_to_folder
                    else R.string.webdav_invalid_url
                )
            }
            // W4 + SYN-4: stage under a name that carries this install's id. The old fixed
            // `<canonical>.uploading` was shared by every device, so the cleanup DELETE of a
            // previous run could remove another device's in-flight upload (or the MOVE could
            // publish it). With the id in the name every cleanup below names only this install's
            // own staging file; a PUT replaces any leftover from an interrupted run anyway.
            val stagingUrl = WebDavPublish.stagingUrl(
                canonicalUrl = canonicalUrl,
                installId = WebDavBackupState.installId(applicationContext)
            )
            val remote = fetchRemote(canonicalUrl, authorization)
            var knownEtag = prefs[stringPreferencesKey(WebDavPrefs.ETAG)]
            // W5: a server without ETags still reports Last-Modified. It is the weaker validator
            // the conditional PUT falls back to when there is no ETag to match on.
            var knownLastModified = prefs[stringPreferencesKey(WebDavPrefs.LAST_MODIFIED)]
            var remoteWritten = prefs[booleanPreferencesKey(WebDavPrefs.REMOTE_WRITTEN)] ?: false
            // R2-01: what a previous run wrote but could not verify. This is a lease on that exact
            // remote file - not an ownership claim - and it is the only thing that lets the next
            // run repair the canonical archive instead of classifying it as a stranger's file.
            var repairEtag = prefs[stringPreferencesKey(WebDavPrefs.REPAIR_ETAG)]
            var repairLastModified = prefs[stringPreferencesKey(WebDavPrefs.REPAIR_LAST_MODIFIED)]
            var repairUrl = prefs[stringPreferencesKey(WebDavPrefs.REPAIR_URL)]
            var repairAttempts = prefs[intPreferencesKey(WebDavPrefs.REPAIR_ATTEMPTS)] ?: 0
            // R3-02: a validator only means something for the URL it was read from. Saving a new
            // server must not let an old lease match a same-named file there.
            if (repairUrl != null && repairUrl != canonicalUrl) {
                Log.w(TAG, "WebDAV URL changed; discarding the repair lease from the old server")
                clearRepairTarget()
                repairEtag = null
                repairLastModified = null
                repairUrl = null
                repairAttempts = 0
            }

            // "Take over the remote" (the conflict card's escape hatch). Without it a device that
            // restored a conflict copy is stuck forever: it cleared its validator, so every later
            // upload sees a stranger's file and keeps making another conflict copy. The user is
            // explicitly choosing this device's notes as the truth, so skip the conflict branch but
            // still send If-Match with whatever is on the server *now* - a change made between our
            // HEAD and our PUT must not be clobbered either.
            val forceOverwrite = inputData.getBoolean(DATA_FORCE_OVERWRITE, false)

            // W8: the file this device last wrote is gone from the server (deleted there, or the
            // whole backup folder was cleaned up). The remembered ETag no longer describes
            // anything, but it was still sent as `If-Match` - and a precondition against a
            // resource that does not exist can never be satisfied, so every run ended in 412 and
            // the canonical file was never recreated. The remembered validators are dropped here
            // and the upload below runs as a plain create instead.
            if (!forceOverwrite && remote is RemoteArchiveState.Missing &&
                (knownEtag != null || knownLastModified != null)
            ) {
                Log.w(
                    TAG,
                    "Remote archive no longer exists; forgetting the stale validator so it can be recreated"
                )
                storeUnverifiedPublication(RemoteArchiveState.Missing)
                knownEtag = null
                knownLastModified = null
                remoteWritten = false
                repairEtag = null
                repairLastModified = null
                repairUrl = null
                repairAttempts = 0
            }

            // SYN-11: a collection answers HEAD 200 like a file but can never be published;
            // writing into it would leave hidden staging files and no usable backup. fetchRemote
            // confirms the resourcetype with PROPFIND, so both the trailing-slash and the
            // slash-less folder spelling are refused before the first PUT.
            if (remote is RemoteArchiveState.Present && remote.isCollection) {
                Log.e(TAG, "The configured WebDAV URL points at a collection, not an archive file")
                return@withContext failWithToast(R.string.webdav_url_points_to_folder)
            }

            val remoteEtag = (remote as? RemoteArchiveState.Present)?.etag

            // SYN-03: the encrypted archive is expensive (portable database copy, zip, full
            // re-encryption of every recording). It is now built at most once, and only after
            // every check that can end the run without uploading anything has run. Before this,
            // a retry of an already-parked conflict still rebuilt and re-encrypted the whole
            // library before discovering there was nothing new to upload.
            var archiveBuilt = false
            suspend fun archiveOnce() {
                if (archiveBuilt) return
                writeEncryptedArchive(archive, passphrase)
                archiveBuilt = true
            }

            // Never let a fresh install (or a post-restore device with no remembered validator)
            // replace a backup it did not write. An ETag we have never seen, or a file that exists
            // while we have no ETag at all, means "someone else owns this file": keep both copies.
            //
            // R2-01: the exception is a file this install published but could not verify. That
            // file is not a stranger's - it is the truncated result of our own failed MOVE/PUT.
            // The repair lease records the exact validator that run saw, so a remote that still
            // matches it may be overwritten conditionally; anything that changed since is another
            // device's file again and takes the conflict path.
            val leaseMatches = WebDavRepairLease.matches(
                remote = remote,
                repairEtag = repairEtag,
                repairLastModified = repairLastModified,
            )
            val remoteMatchesRepair =
                WebDavRepairLease.canRepair(leaseMatches, repairAttempts)
            if (leaseMatches && !remoteMatchesRepair) {
                // R3-02: the lease has driven its allowed repair runs and the remote still is not
                // ours. Stop automatically overwriting it and let the normal conflict rules apply.
                Log.w(
                    TAG,
                    "Repair lease exhausted after $repairAttempts attempts; keeping a conflict copy next"
                )
                clearRepairTarget()
                repairEtag = null
                repairLastModified = null
                repairUrl = null
                repairAttempts = 0
            }
            if (remoteMatchesRepair) {
                Log.w(
                    TAG,
                    "Republishing the canonical archive this install wrote but could not verify " +
                        "(attempt ${repairAttempts + 1} of ${WebDavRepairLease.MAX_REPAIR_ATTEMPTS})"
                )
            }
            val remoteChanged = remoteEtag != null && knownEtag != null &&
                remoteEtag != knownEtag && !remoteMatchesRepair
            val unknownRemote = remote is RemoteArchiveState.Present && knownEtag == null &&
                !remoteWritten && !remoteMatchesRepair
            if ((remoteChanged || unknownRemote) && !forceOverwrite) {
                // SYN-03: a copy for this exact remote state is already parked and still listed.
                // The conflict is unresolved, but there is nothing new to upload, so do not
                // build the archive at all on this retry.
                if (existingConflictCopy(remote) != null) {
                    Log.w(
                        TAG,
                        "A conflict copy for the current remote state is already kept; not " +
                            "building or uploading another one"
                    )
                    toastOnMain(applicationContext.getString(R.string.cloud_conflict_kept))
                    return@withContext Result.failure()
                }
                archiveOnce()
                // Not a plain failure: keepConflictCopy toasts its own outcome ("kept as a
                // conflict copy", or "failed" if even that upload did not land).
                keepConflictCopy(canonicalUrl, authorization, archive, remoteState = remote)
                return@withContext Result.failure()
            }

            // SYN-1: one total decision replaces the old `exists == true` guards. "The server
            // would not tell us" is no longer treated as "there is nothing there": an unreadable
            // remote is refused before any write unless the user explicitly forced the takeover,
            // and the only unconditional MOVE that remains is that forced one. See
            // [WebDavPreconditions.decide].
            var ifNoneMatch = false
            var ifMatch: String? = null
            var ifUnmodifiedSince: String? = null
            // R2-01: for a repair the current remote validator is the truth; a remembered one may
            // describe an older file and can never satisfy If-Match.
            val decisionEtag = if (remoteMatchesRepair) null else knownEtag
            val decisionLastModified = if (remoteMatchesRepair) null else knownLastModified
            when (val publication = WebDavPreconditions.decide(
                remote = remote,
                knownEtag = decisionEtag,
                knownLastModified = decisionLastModified,
                forceOverwrite = forceOverwrite,
            )) {
                CanonicalPublication.Create -> {
                    // Missing: create with `If-None-Match: *` (Overwrite: F on the MOVE) so a
                    // file that appears between the HEAD and the MOVE becomes a conflict copy.
                    ifNoneMatch = true
                }
                is CanonicalPublication.Conditional -> {
                    ifMatch = publication.ifMatch
                    ifUnmodifiedSince = publication.ifUnmodifiedSince
                }
                CanonicalPublication.ForcedOverwrite -> {
                    // The user pressed "overwrite remote": the only path allowed to publish
                    // without a precondition, exactly as before.
                }
                is CanonicalPublication.Refused -> {
                    return@withContext when (publication.reason) {
                        CanonicalPublication.RefusalReason.REMOTE_UNVERIFIED -> {
                            Log.e(
                                TAG,
                                "Refusing to overwrite a remote archive whose state could not be verified"
                            )
                            failWithToast(R.string.webdav_remote_unverified)
                        }
                        CanonicalPublication.RefusalReason.NO_VALIDATOR -> failWithNoValidator()
                        CanonicalPublication.RefusalReason.COLLECTION_URL ->
                            failWithToast(R.string.webdav_url_points_to_folder)
                    }
                }
            }

            // SYN-03: the publication decision is final; this is the one build for both the
            // canonical upload and a first-time conflict copy (the conflict branch above).
            archiveOnce()

            // W4: stage the upload under a temporary name and MOVE it into place. An interrupted
            // upload used to leave a truncated canonical file carrying a fresh ETag, which every
            // later run then judged as "the remote changed" - the canonical file never advanced
            // again. Now an interruption can only ever leave the staging name behind.
            val staged = putArchive(
                url = stagingUrl,
                authorization = authorization,
                archive = archive,
                ifMatch = null,
                ifNoneMatch = false,
                contentMd5 = contentMd5(archive)
            )
            when {
                staged.code in 200..299 -> {
                    // The server acknowledged the bytes; make sure they all landed before the
                    // file is published under the canonical name.
                    val remoteLength = headContentLength(stagingUrl, authorization)
                    if (remoteLength != null && remoteLength != archive.length()) {
                        Log.e(
                            TAG,
                            "Staged upload is incomplete: local=${archive.length()} bytes, remote=$remoteLength"
                        )
                        // A truncated transfer is worth another run, but not an endless one (W9).
                        if (runAttemptCount < WebDavRetry.MAX_RUN_ATTEMPTS) {
                            return@withContext Result.retry()
                        }
                        // Terminal: this staging name is this install's own (SYN-4).
                        deleteRemote(stagingUrl, authorization)
                        return@withContext failWithToast()
                    }
                    publishStagedArchive(
                        stagingUrl = stagingUrl,
                        canonicalUrl = canonicalUrl,
                        authorization = authorization,
                        archive = archive,
                        // SYN-2: the staging PUT's validators are the proof of what the MOVE
                        // published; the post-MOVE HEAD is compared against them, and they are
                        // the fallback when that HEAD fails (SYN-1).
                        expectedEtag = staged.etag,
                        uploadedLastModified = staged.lastModified,
                        ifMatch = ifMatch,
                        ifNoneMatch = ifNoneMatch,
                        ifUnmodifiedSince = ifUnmodifiedSince
                    )
                }
                WebDavRetry.isRedirect(staged.code) -> failWithRedirect(staged.code)
                else -> retryOrFail(staged.code, "WebDAV staging PUT was rejected")
            }
        } catch (e: Exception) {
            Log.e(TAG, "WebDAV backup failed", e)
            failWithToast()
        } finally {
            // The archive holds the whole note database; do not leave it sitting in the cache.
            archive.delete()
        }
    }

    /**
     * SYN-1: one request (HEAD, with a PROPFIND fallback) that says what the server holds.
     *
     * The old version returned `exists = null` for 405/501, any other error and every IOException,
     * and the callers only treated `exists == true` as "there is a file". A server without HEAD -
     * or a HEAD that failed once - therefore turned a file this device had never seen into a bare
     * overwrite. `405`/`501` now mean "HEAD is not implemented", which is exactly what PROPFIND
     * `Depth: 0` answers, and anything still unreadable becomes [RemoteArchiveState.Unknown]
     * instead of "no file".
     *
     * SYN-11: a collection answers `HEAD 200` exactly like a file, and [WebDavPublish.requireFileUrl]
     * can only reject the trailing-slash spelling. A configured URL without that slash that names
     * a folder therefore used to look like a present archive. Every 200 is now followed by the
     * `Depth: 0` PROPFIND probe, so `resourcetype = collection` is refused before any write; the
     * probe also fills in a validator when the HEAD omitted it.
     */
    private fun fetchRemote(url: String, authorization: String): RemoteArchiveState {
        var headUnsupportedCode: Int? = null
        val head: RemoteArchiveState? = try {
            val request = Request.Builder()
                .url(url)
                .head()
                .header("Authorization", authorization)
                .build()
            client.newCall(request).execute().use { response ->
                when {
                    response.code == 404 -> RemoteArchiveState.Missing
                    response.code == 405 || response.code == 501 -> {
                        headUnsupportedCode = response.code
                        null
                    }
                    response.code in 200..299 -> RemoteArchiveState.Present(
                        etag = normalizeEtag(response.header("ETag")),
                        lastModified = response.header("Last-Modified"),
                        contentLength = response.header("Content-Length")?.toLongOrNull()
                    )
                    else -> RemoteArchiveState.Unknown("HEAD rejected with HTTP ${response.code}")
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "Could not read the remote state", e)
            RemoteArchiveState.Unknown("HEAD failed: ${e.message ?: "I/O error"}")
        }
        if (head == null) {
            Log.i(TAG, "HEAD is not supported (HTTP $headUnsupportedCode); asking with PROPFIND")
            return propfindRemote(url, authorization)
        }
        if (head !is RemoteArchiveState.Present) return head
        return when (val dav = propfindRemote(url, authorization)) {
            // A collection always wins: it can never be an archive file, whatever its validators.
            is RemoteArchiveState.Present -> if (dav.isCollection) {
                dav
            } else {
                head.copy(
                    etag = head.etag ?: dav.etag,
                    lastModified = head.lastModified ?: dav.lastModified,
                    contentLength = head.contentLength ?: dav.contentLength,
                )
            }
            // The probe adds information but cannot disprove the HEAD: a server that rejects or
            // mis-shapes PROPFIND still holds the file the HEAD just described.
            RemoteArchiveState.Missing -> head
            is RemoteArchiveState.Unknown -> {
                Log.d(TAG, "PROPFIND probe did not describe the archive: ${dav.reason}")
                head
            }
        }
    }

    /**
     * SYN-1: the `HEAD` fallback for servers that answer 405/501 (or do not expose a usable HEAD).
     *
     * `Depth: 0` asks about the URL itself, so exactly one `DAV:response` comes back. A collection
     * is reported as such instead of being mistaken for a file; the caller refuses it (SYN-11).
     * Any failure here - no DAV body, XML we cannot parse, a rejected PROPFIND - leaves the state
     * [RemoteArchiveState.Unknown], which the publication decision treats as "do not overwrite
     * without a remembered validator or an explicit takeover". When this runs only as the
     * collection probe behind a successful HEAD (SYN-11), [fetchRemote] keeps the HEAD's state
     * instead: the probe adds knowledge, it cannot disprove a 200.
     */
    private fun propfindRemote(url: String, authorization: String): RemoteArchiveState {
        val request = Request.Builder()
            .url(url)
            .method(
                "PROPFIND",
                PROPFIND_BODY.toRequestBody("application/xml; charset=utf-8".toMediaType())
            )
            .header("Authorization", authorization)
            .header("Depth", "0")
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                when {
                    response.code == 404 -> RemoteArchiveState.Missing
                    response.code !in 200..299 ->
                        RemoteArchiveState.Unknown("PROPFIND rejected with HTTP ${response.code}")
                    else -> {
                        val body = response.body?.string()
                        if (body.isNullOrBlank()) {
                            RemoteArchiveState.Unknown("PROPFIND returned no body")
                        } else {
                            // SYN-11: only a multistatus about *this* URL may describe its file.
                            WebDavPropfind.parse(body, url)
                        }
                    }
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "PROPFIND fallback failed", e)
            RemoteArchiveState.Unknown("PROPFIND failed: ${e.message ?: "I/O error"}")
        }
    }

    private fun putArchive(
        url: String,
        authorization: String,
        archive: File,
        ifMatch: String?,
        ifNoneMatch: Boolean,
        ifUnmodifiedSince: String? = null,
        contentMd5: String? = null
    ): UploadResult {
        val builder = Request.Builder()
            .url(url)
            .put(archive.asRequestBody())
            .header("Authorization", authorization)
        if (ifMatch != null) {
            builder.header("If-Match", ifMatch)
        }
        if (ifNoneMatch) {
            builder.header("If-None-Match", "*")
        }
        // W4: the server rejects a body whose digest does not match, so a transfer that arrived
        // corrupted can never be accepted as a good upload.
        if (contentMd5 != null) {
            builder.header("Content-MD5", contentMd5)
        }
        // W5: no ETag to compare, but the server told us when the file last changed.
        if (ifUnmodifiedSince != null) {
            builder.header("If-Unmodified-Since", ifUnmodifiedSince)
        }
        return try {
            // SYN-7: a whole-call ceiling has to scale with the archive, or a large backup on a
            // slow uplink is cut at the same five minutes every time. The connect/read/write
            // timeouts still detect a stalled socket.
            WebDavHttp.clientForTransfer(archive.length()).newCall(builder.build()).execute().use { response ->
                UploadResult(
                    code = response.code,
                    etag = if (response.isSuccessful) normalizeEtag(response.header("ETag")) else null,
                    lastModified = if (response.isSuccessful) response.header("Last-Modified") else null
                )
            }
        } catch (e: IOException) {
            Log.w(TAG, "WebDAV upload failed", e)
            UploadResult(code = 0, etag = null)
        }
    }

    /**
     * Publishes the staged file under the canonical name with a WebDAV `MOVE` (W4).
     *
     * `Destination` is the absolute URL including any query string (the same rule
     * [conflictUrl] follows). SYN-2: the destination precondition arrives as [destinationHeaders]
     * - a tagged `If: <destination> ([etag])` (RFC 4918 §10.4.1) or `Overwrite: F` for a create.
     * Sending `If-Match`/`If-None-Match`/`If-Unmodified-Since` here would be wrong by the spec:
     * those headers are evaluated against the request URI, which is our staging file.
     *
     * @return the HTTP status (0 when the request never completed) together with the validators
     *   the MOVE response carried, if any. SYN-1 keeps those: a server that refuses HEAD after a
     *   successful MOVE can still be recognised as the writer of the published file.
     */
    private fun moveArchive(
        stagingUrl: String,
        destinationUrl: String,
        authorization: String,
        destinationHeaders: Map<String, String>
    ): UploadResult {
        val builder = Request.Builder()
            .url(stagingUrl)
            .method("MOVE", null)
            .header("Authorization", authorization)
            .header("Destination", destinationUrl)
        destinationHeaders.forEach { (name, value) -> builder.header(name, value) }
        return try {
            client.newCall(builder.build()).execute().use { response ->
                UploadResult(
                    code = response.code,
                    etag = if (response.isSuccessful) normalizeEtag(response.header("ETag")) else null,
                    lastModified = if (response.isSuccessful) response.header("Last-Modified") else null
                )
            }
        } catch (e: IOException) {
            Log.w(TAG, "WebDAV MOVE failed", e)
            UploadResult(code = 0, etag = null)
        }
    }

    /**
     * Best-effort DELETE of this install's own staging file once it is redundant: after a direct
     * PUT fallback, and on a terminal failure. SYN-4 makes the name install-scoped, so this can
     * never remove another device's in-flight upload. A 404 is the normal "nothing to delete" case.
     */
    private fun deleteRemote(url: String, authorization: String) {
        try {
            val request = Request.Builder()
                .url(url)
                .header("Authorization", authorization)
                .delete()
                .build()
            client.newCall(request).execute().use { response ->
                Log.d(TAG, "Staging cleanup DELETE -> HTTP ${response.code}")
            }
        } catch (e: IOException) {
            Log.w(TAG, "Could not clean up the staging file", e)
        }
    }

    /** Remote length of [url], or null when the server would not say (refused HEAD, no header, IO error). */
    private fun headContentLength(url: String, authorization: String): Long? = try {
        val request = Request.Builder()
            .url(url)
            .head()
            .header("Authorization", authorization)
            .build()
        client.newCall(request).execute().use { response ->
            when (response.code) {
                in 200..299 -> response.header("Content-Length")?.toLongOrNull()
                else -> {
                    Log.w(TAG, "Could not verify the staged upload: HTTP ${response.code}")
                    null
                }
            }
        }
    } catch (e: IOException) {
        Log.w(TAG, "Could not verify the staged upload length", e)
        null
    }

    /**
     * Takes the staged archive live: `MOVE` it onto the canonical name, then verify and remember
     * the canonical file's validators for the next run.
     *
     * SYN-2: after the MOVE the canonical file is re-HEADed and its ETag compared with
     * [expectedEtag] (the ETag the staging PUT returned). Equal ETags prove the published bytes
     * are the ones this run uploaded; a server that silently ignored a destination `If`/
     * `Overwrite: F` and let another file replace ours is caught here.
     *
     * A *different* ETag is not automatically another file: some WebDAV servers mint a fresh ETag
     * on every MOVE even though the bytes are unchanged. When the same HEAD also reports a
     * `Content-Length` equal to [archive]'s, that is treated as a server-minted ETag for our bytes
     * and the new value is stored, so the next backup stays conditional and converges (SYN-3).
     * Only a mismatch whose size differs or cannot be read is unverified, because then neither
     * ETag nor length identifies our upload; no validator is stored and the next run treats the
     * remote as unknown rather than overwrite it. REG-11: that verdict is final - an unverified
     * publication retries while attempts remain and otherwise keeps the local archive as a
     * conflict copy, but never reports success. When HEAD omits the ETag entirely,
     * `Content-Length` remains the only available check (it cannot tell two same-size files
     * apart).
     *
     * Servers that do not implement `MOVE` (405/501) fall back to the direct conditional PUT
     * this build used before W4, with the same preconditions, and the fallback is logged so a
     * missing MOVE is diagnosable instead of silent.
     *
     * SYN-1: [uploadedLastModified] is the staging PUT's `Last-Modified`. It is stored - together
     * with the MOVE response's validators - when the post-MOVE HEAD could not answer, instead of
     * discarding the only proof of what this run wrote.
     */
    private suspend fun publishStagedArchive(
        stagingUrl: String,
        canonicalUrl: String,
        authorization: String,
        archive: File,
        expectedEtag: String?,
        ifMatch: String?,
        ifNoneMatch: Boolean,
        ifUnmodifiedSince: String?,
        uploadedLastModified: String? = null,
        recoveredFromStaleValidator: Boolean = false
    ): Result {
        val destination = WebDavPublish.destinationPreconditionHeaders(
            destinationUrl = canonicalUrl,
            ifMatch = ifMatch,
            ifNoneMatch = ifNoneMatch,
            ifUnmodifiedSince = ifUnmodifiedSince
        )
        // SYN-2: a Last-Modified-only validator has no destination-side form on a MOVE (the
        // header would guard the staging file). Publish straight onto the canonical URI instead,
        // where If-Unmodified-Since is evaluated against the file it is meant to protect.
        if (destination.directPut) {
            Log.i(
                TAG,
                "Only a Last-Modified validator is available; MOVE cannot precondition the " +
                    "destination, publishing with a direct conditional PUT"
            )
            val result = directConditionalPut(
                canonicalUrl, authorization, archive, ifMatch, ifNoneMatch, ifUnmodifiedSince
            )
            deleteRemote(stagingUrl, authorization)
            return result
        }
        val move = moveArchive(
            stagingUrl = stagingUrl,
            destinationUrl = canonicalUrl,
            authorization = authorization,
            destinationHeaders = destination.headers
        )
        return when {
            move.code in 200..299 -> {
                // A MOVE response often omits validators; re-HEAD the canonical file (the same
                // rule the old post-PUT HEAD followed) so a stale validator is never stored.
                // SYN-2: compare the staging PUT's ETag with the published file. Equal tags prove
                // the bytes are ours; a different tag with the same length is a server-minted
                // ETag for our bytes (see below), while a different/unknown length means the
                // publication cannot be verified. Length alone remains the fallback for HEAD
                // responses that omit the ETag.
                val head = fetchRemote(canonicalUrl, authorization)
                // REG-11: one pure verdict (see [WebDavPublicationVerification]) decides whether
                // the published file is the upload. It used to be advisory: a mismatch still
                // reached `REMOTE_WRITTEN=true` and "backup completed".
                val verification = WebDavPublicationVerification.verify(
                    head = head,
                    expectedEtag = expectedEtag,
                    moveEtag = move.etag,
                    moveLastModified = move.lastModified,
                    uploadedLastModified = uploadedLastModified,
                    localLength = archive.length(),
                )
                logVerification(head, verification)
                if (!verification.verified) {
                    // REG-11: an unverified publication is a failed publication. Forget any
                    // validator and REMOTE_WRITTEN so the next run cannot treat the canonical
                    // file as a verified copy; never delete the canonical (it is the only remote
                    // copy) and never claim completion.
                    storeUnverifiedPublication(
                        head = head,
                        // R4-01: the lease is only established when the HEAD ETag is one this
                        // publication returned. Pass both validators separately: the staging PUT's
                        // proves the bytes, the MOVE's proves the canonical name.
                        uploadEtag = expectedEtag,
                        moveEtag = move.etag,
                        uploadLastModified = uploadedLastModified ?: move.lastModified,
                        localLength = archive.length(),
                        canonicalUrl = canonicalUrl,
                    )
                    if (runAttemptCount < WebDavRetry.MAX_RUN_ATTEMPTS) {
                        return Result.retry()
                    }
                    // Out of attempts: leave this device's archive next to the canonical file as
                    // a visible conflict copy instead of letting a truncated/unknown upload be
                    // the only remote copy.
                    keepConflictCopy(
                        url = canonicalUrl,
                        authorization = authorization,
                        archive = archive,
                        keptMessageRes = R.string.webdav2_publish_unverified,
                        preserveRepairLease = true,
                    )
                    return Result.failure()
                }
                storeVerifiedPublication(
                    verification.storedEtag,
                    verification.storedLastModified,
                )
                // SYN-4: a successful canonical upload proves the remote file is this device's
                // again - it does NOT prove any parked conflict copy was merged. The notices are
                // deliberately left in place; only a restore of a specific copy or the user's
                // "got it" removes one (see [WebDavBackupState]).
                toastOnMain(applicationContext.getString(R.string.cloud_backup_completed))
                Result.success()
            }
            move.code == 405 || move.code == 501 -> {
                Log.w(TAG, "Server rejected MOVE with HTTP ${move.code}; falling back to a direct conditional PUT")
                // The staged copy is about to become redundant; do not leave it in the folder.
                deleteRemote(stagingUrl, authorization)
                directConditionalPut(canonicalUrl, authorization, archive, ifMatch, ifNoneMatch, ifUnmodifiedSince)
            }
            // SYN-2 compatibility path: a server that does not understand a destination-side
            // `If` (or rejects `Overwrite: F` on MOVE) answers 400/412. One direct conditional
            // PUT uses the same precondition where the header is unambiguously about the target;
            // a second 412 there is a real race and becomes a conflict copy as usual.
            (move.code == BAD_REQUEST || move.code == PRECONDITION_FAILED) && destination.retryDirectPutOnReject -> {
                Log.w(
                    TAG,
                    "MOVE rejected the destination precondition with HTTP ${move.code}; retrying once " +
                        "as a direct conditional PUT"
                )
                deleteRemote(stagingUrl, authorization)
                directConditionalPut(canonicalUrl, authorization, archive, ifMatch, ifNoneMatch, ifUnmodifiedSince)
            }
            move.code == PRECONDITION_FAILED -> {
                // W8: a 412 against a file that no longer exists is not a race - no value of a
                // `If-Match` can ever satisfy a precondition about a resource that is gone, which
                // is what made this a permanent loop. Re-check the remote; if it really is
                // missing, publish the already-staged bytes once as a create.
                if (!recoveredFromStaleValidator && forgetValidatorIfRemoteDeleted(canonicalUrl, authorization)) {
                    return publishStagedArchive(
                        stagingUrl = stagingUrl,
                        canonicalUrl = canonicalUrl,
                        authorization = authorization,
                        archive = archive,
                        expectedEtag = expectedEtag,
                        uploadedLastModified = uploadedLastModified,
                        ifMatch = null,
                        ifNoneMatch = true,
                        ifUnmodifiedSince = null,
                        recoveredFromStaleValidator = true
                    )
                }
                // Terminal failure: this staging name is this install's own, so cleaning it up
                // cannot touch another device's upload.
                deleteRemote(stagingUrl, authorization)
                keepConflictCopy(canonicalUrl, authorization, archive)
                Result.failure()
            }
            WebDavRetry.isRedirect(move.code) -> {
                deleteRemote(stagingUrl, authorization)
                failWithRedirect(move.code)
            }
            else -> {
                // A retry re-PUTs the whole archive, so the leftover gains nothing; removing it
                // now also covers the terminal case.
                deleteRemote(stagingUrl, authorization)
                retryOrFail(move.code, "WebDAV MOVE to the canonical name failed")
            }
        }
    }

    /**
     * The pre-W4 publication path: one conditional PUT straight onto the canonical name.
     *
     * It is the fallback for servers that reject `MOVE` (405/501), the publication path when only
     * a Last-Modified validator exists (SYN-2: a MOVE cannot precondition `Destination` with it),
     * and the one-shot compatibility retry when a server answers 400/412 to a destination-side
     * `If`/`Overwrite: F`. Here the precondition headers name the request URI - the canonical
     * file itself - so their RFC meaning is exactly what the backup wants. Success stores both
     * validators; a lost race still becomes a conflict copy.
     */
    private suspend fun directConditionalPut(
        url: String,
        authorization: String,
        archive: File,
        ifMatch: String?,
        ifNoneMatch: Boolean,
        ifUnmodifiedSince: String?,
        recoveredFromStaleValidator: Boolean = false
    ): Result {
        val upload = putArchive(
            url = url,
            authorization = authorization,
            archive = archive,
            ifMatch = ifMatch,
            ifNoneMatch = ifNoneMatch,
            ifUnmodifiedSince = ifUnmodifiedSince,
            // SYN-2: the staging path has always proved the body with Content-MD5; this path
            // published the same bytes without it, so a proxy that truncated a 2xx PUT could be
            // recorded as a good backup.
            contentMd5 = contentMd5(archive)
        )
        return when {
            upload.code in 200..299 -> {
                // SYN-2/REG-11: a 2xx only says the server accepted the request. Re-read the file
                // and run it through the same verdict as the MOVE path - ETag first, length as the
                // fallback - so a proxy that truncated a 2xx PUT cannot be recorded as a good
                // backup. The old direct-PUT check compared only Content-Length, which missed a
                // same-size replacement and accepted a HEAD that exposed no length at all.
                val head = fetchRemote(url, authorization)
                val verification = WebDavPublicationVerification.verify(
                    head = head,
                    expectedEtag = upload.etag,
                    moveEtag = upload.etag,
                    moveLastModified = upload.lastModified,
                    uploadedLastModified = upload.lastModified,
                    localLength = archive.length(),
                )
                logVerification(head, verification)
                if (!verification.verified) {
                    // The canonical file is never deleted (it is the only remote copy), but it is
                    // not this run's archive, so no validator or ownership is recorded and no
                    // success is reported. R2-01: keep the repair lease so the next run may
                    // overwrite exactly this file conditionally.
                    storeUnverifiedPublication(
                        head = head,
                        uploadEtag = upload.etag,
                        // A direct PUT writes the canonical name itself, so its response ETag is
                        // both the byte proof and the destination proof.
                        moveEtag = upload.etag,
                        uploadLastModified = upload.lastModified,
                        localLength = archive.length(),
                        canonicalUrl = url,
                    )
                    if (runAttemptCount < WebDavRetry.MAX_RUN_ATTEMPTS) {
                        return Result.retry()
                    }
                    keepConflictCopy(
                        url = url,
                        authorization = authorization,
                        archive = archive,
                        keptMessageRes = R.string.webdav2_publish_unverified,
                        preserveRepairLease = true,
                    )
                    return Result.failure()
                }
                storeVerifiedPublication(
                    verification.storedEtag,
                    verification.storedLastModified,
                )
                // SYN-4: conflict notices stay; only a restore or an explicit dismissal
                // resolves one.
                toastOnMain(applicationContext.getString(R.string.cloud_backup_completed))
                Result.success()
            }
            upload.code == PRECONDITION_FAILED -> {
                // W8: same stale-validator loop as the MOVE path - recover once if the remote
                // file is genuinely gone, otherwise this really was another device winning.
                if (!recoveredFromStaleValidator && forgetValidatorIfRemoteDeleted(url, authorization)) {
                    return directConditionalPut(
                        url = url,
                        authorization = authorization,
                        archive = archive,
                        ifMatch = null,
                        ifNoneMatch = true,
                        ifUnmodifiedSince = null,
                        recoveredFromStaleValidator = true
                    )
                }
                // Another device won the race between our HEAD and our PUT.
                keepConflictCopy(url, authorization, archive)
                Result.failure()
            }
            WebDavRetry.isRedirect(upload.code) -> failWithRedirect(upload.code)
            else -> retryOrFail(upload.code, "WebDAV backup was rejected")
        }
    }

    /**
     * W8: drops the remembered validators when the canonical file has disappeared from the server.
     *
     * @return true when the caller should retry the publication as a plain create.
     */
    private suspend fun forgetValidatorIfRemoteDeleted(url: String, authorization: String): Boolean {
        val head = fetchRemote(url, authorization)
        if (head !is RemoteArchiveState.Missing) return false
        Log.w(TAG, "Canonical archive is gone; clearing the stale validator and publishing as a new file")
        storeUnverifiedPublication(RemoteArchiveState.Missing)
        return true
    }

    /**
     * The remote changed, so this device's archive must not replace it. It goes next to the
     * canonical file under a date-stamped name instead, which leaves both copies *visible* in the
     * user's WebDAV folder: a silent overwrite cannot be undone, a file they can see can.
     *
     * SYN-3: the remote validator the copy was made for is recorded with it. A retry of the same
     * conflict - the worker returns 412/failure every time the remote disagrees - used to
     * re-encrypt and re-upload the whole archive (every recording included) on each attempt. When
     * the remote still has the same validator and the copy's URL is still listed, the upload is
     * skipped and the conflict stays unresolved (which is the truth). The same dedupe now runs in
     * `doWork` *before* the archive is built (SYN-03); [remoteState] lets the caller share the
     * state it already read instead of issuing another HEAD.
     *
     * [keptMessageRes] names the outcome toast. A conflict copy kept because the publication could
     * not be verified (REG-11) must not say "the remote had changed".
     */
    private suspend fun keepConflictCopy(
        url: String,
        authorization: String,
        archive: File,
        remoteState: RemoteArchiveState? = null,
        keptMessageRes: Int = R.string.cloud_conflict_kept,
        /**
         * R2-01: true when the copy is this install's own unverified publication, not another
         * device's file. The repair lease must survive that decision: the remote is still the
         * truncated file this device wrote, and the next run has to be allowed to repair it
         * conditionally instead of falling into the unknown-remote conflict branch forever.
         */
        preserveRepairLease: Boolean = false,
    ) {
        if (!preserveRepairLease) {
            // A genuine conflict means the canonical file is not this run's unverified upload -
            // another device wrote it. Any repair lease on the old file is dead.
            clearRepairTarget()
        }
        val remote = remoteState ?: fetchRemote(url, authorization)
        val conflictValidator = conflictValidatorFor(remote)
        val existing = existingConflictCopy(remote)
        if (existing != null) {
            Log.w(
                TAG,
                "A conflict copy for the current remote state is already kept at $existing; " +
                    "not uploading another one"
            )
            // Still a conflict: the copy was not merged, it is merely not duplicated.
            toastOnMain(applicationContext.getString(keptMessageRes))
            return
        }
        // SYN-2: conflict copies are published with the same Content-MD5 proof as every other PUT.
        val md5 = contentMd5(archive)
        // W7: the copy is created conditionally. Unconditional, two devices that picked the same
        // millisecond overwrote each other's copy and only the last one survived - the opposite of
        // what a conflict copy is for. `If-None-Match: *` makes the create fail with 412 instead,
        // and that 412 moves the name forward.
        var attempt = 0
        var target = conflictUrl(url, attempt)
        var upload = putArchive(
            target, authorization, archive, ifMatch = null, ifNoneMatch = true, contentMd5 = md5
        )
        while (upload.code == PRECONDITION_FAILED && attempt < MAX_CONFLICT_ATTEMPTS - 1) {
            attempt++
            target = conflictUrl(url, attempt)
            Log.w(TAG, "Conflict copy name was taken; trying $target")
            upload = putArchive(
                target, authorization, archive, ifMatch = null, ifNoneMatch = true, contentMd5 = md5
            )
        }
        if (upload.code in 200..299) {
            // R2-01: the conflict copy is the user's only visible copy in this state, so it gets
            // the same post-write proof as the canonical publication. A 2xx alone can hide a proxy
            // truncation; such a copy must not be announced as kept.
            if (!verifyConflictCopy(target, authorization, archive, upload)) {
                Log.e(TAG, "Conflict copy at $target did not verify; removing the truncated copy")
                deleteRemote(target, authorization)
                toastOnMain(applicationContext.getString(R.string.cloud_backup_failed))
                return
            }
            Log.w(TAG, "Remote backup had changed; this device's copy was kept at $target")
            WebDavBackupState.recordConflict(applicationContext, target, conflictValidator)
            // The conflict card only appears on the next visit to the cloud screen; the toast is
            // what makes "your copy was kept, not overwritten" visible right now (W3).
            toastOnMain(applicationContext.getString(keptMessageRes))
        } else {
            Log.e(TAG, "Remote backup had changed and the conflict copy failed with HTTP " + upload.code)
            // SYN-3: 507 is what repeated full copies eventually hit on a quota-limited server;
            // say so instead of reporting a generic failure.
            toastOnMain(
                applicationContext.getString(
                    if (upload.code == INSUFFICIENT_STORAGE) R.string.webdav_conflict_no_space
                    else R.string.cloud_backup_failed
                )
            )
        }
    }

    /**
     * R2-01: post-write proof for a conflict copy, mirroring the canonical [WebDavPublicationVerification].
     *
     * A `HEAD` that cannot be read is accepted: the copy did upload (2xx plus Content-MD5), and
     * discarding it because the server will not describe it would leave the user without the copy
     * this path exists to preserve.
     */
    private fun verifyConflictCopy(
        url: String,
        authorization: String,
        archive: File,
        upload: UploadResult,
    ): Boolean {
        val head = fetchRemote(url, authorization)
        if (head is RemoteArchiveState.Unknown) {
            Log.w(TAG, "Could not verify the conflict copy; the accepted upload stands")
            return true
        }
        val verdict = WebDavPublicationVerification.verify(
            head = head,
            expectedEtag = upload.etag,
            moveEtag = upload.etag,
            moveLastModified = upload.lastModified,
            uploadedLastModified = null,
            localLength = archive.length(),
        )
        if (!verdict.verified) {
            Log.e(TAG, "Conflict copy verification failed: ${verdict.reason}")
        }
        return verdict.verified
    }

    /**
     * The remote state a parked conflict copy was made for; null when the server gave no validator.
     *
     * R2-01/SYN-03: the local database/WAL signature is part of the fingerprint, so a retry skips
     * the upload only while two things are true - the remote did not change *and* the library did
     * not change. A new note therefore produces a new copy instead of being swallowed by the old
     * conflict's dedupe entry.
     */
    private fun conflictValidatorFor(remote: RemoteArchiveState): String? =
        WebDavBackupState.conflictFingerprint(
            etag = (remote as? RemoteArchiveState.Present)?.etag,
            lastModified = (remote as? RemoteArchiveState.Present)?.lastModified,
            localContent = localContentFingerprint(),
        )

    /**
     * A cheap "did the library change?" token: sizes and mtimes of the live database and its WAL.
     *
     * It runs before the archive is built, so it must not read any note body. A committed note
     * write touches the WAL (or the database after a checkpoint), which is exactly what the dedupe
     * needs to notice; anything finer would require building the expensive archive this check
     * exists to avoid.
     */
    private fun localContentFingerprint(): String {
        return runCatching {
            val database = applicationContext.getDatabasePath(DatabaseConst.NOTES_DATABASE_FILE_NAME)
            val wal = File(database.absolutePath + "-wal")
            "${database.length()}:${database.lastModified()}:${wal.length()}:${wal.lastModified()}"
        }.getOrDefault("unknown")
    }

    /**
     * SYN-03: the still-listed conflict copy for [remote]'s validator, or null.
     *
     * `doWork` calls this before building the archive, so a retry that would only re-park the same
     * conflict does not touch the library; [keepConflictCopy] uses it for the paths that learned
     * about the conflict later (a 412 from the publication itself).
     */
    private fun existingConflictCopy(remote: RemoteArchiveState): String? {
        val validator = conflictValidatorFor(remote) ?: return null
        return WebDavBackupState.conflictUrlForValidator(applicationContext, validator)
    }

    /**
     * REG-11: log why a published archive was (or was not) accepted. The decision itself lives in
     * [WebDavPublicationVerification] so it is unit-testable without a server.
     */
    private fun logVerification(
        head: RemoteArchiveState,
        verification: WebDavPublicationVerification.Verdict,
    ) {
        val headFailure = (head as? RemoteArchiveState.Unknown)?.reason
        when (verification.reason) {
            WebDavPublicationVerification.Reason.ETAG_MATCH,
            WebDavPublicationVerification.Reason.LENGTH_ONLY_MATCH,
            WebDavPublicationVerification.Reason.NO_VALIDATOR_OR_LENGTH -> Unit
            WebDavPublicationVerification.Reason.SERVER_MINTED_ETAG -> Log.w(
                TAG,
                "Published archive ETag differs from the uploaded ETag but the size matches; " +
                    "treating it as a server-minted ETag for our bytes"
            )
            WebDavPublicationVerification.Reason.ETAG_MISMATCH -> Log.w(
                TAG,
                "Published archive ETag does not match the uploaded ETag and its size does not " +
                    "confirm the upload; not remembering a validator for a file this run did not write"
            )
            WebDavPublicationVerification.Reason.LENGTH_MISMATCH -> Log.w(
                TAG,
                "Published archive is not the uploaded archive's length; not remembering a " +
                    "validator for a file this run did not write"
            )
            WebDavPublicationVerification.Reason.HEAD_UNREADABLE_WITH_VALIDATORS -> Log.w(
                TAG,
                "Post-MOVE HEAD failed ($headFailure); keeping the validators returned by the " +
                    "upload instead of forgetting the published file"
            )
            WebDavPublicationVerification.Reason.HEAD_UNREADABLE_WITHOUT_VALIDATORS -> Log.w(
                TAG,
                "Published archive cannot be verified: the post-MOVE HEAD failed ($headFailure) " +
                    "and no validators were returned"
            )
            WebDavPublicationVerification.Reason.MISSING_AFTER_MOVE -> Log.w(
                TAG,
                "MOVE reported success but the canonical archive is missing; not remembering a validator"
            )
        }
    }

    /**
     * R2-01/P2: one transaction for a verified publication.
     *
     * Four separate edits (clear lease, ETag, Last-Modified, REMOTE_WRITTEN) meant a crash or an
     * IOException between them could leave a verified file recorded as "someone else's", which the
     * next run answered with a conflict copy instead of a backup.
     */
    private suspend fun storeVerifiedPublication(etag: String?, lastModified: String?) {
        applicationContext.webDavDataStore.edit { prefs ->
            val etagKey = stringPreferencesKey(WebDavPrefs.ETAG)
            val lastModifiedKey = stringPreferencesKey(WebDavPrefs.LAST_MODIFIED)
            if (etag.isNullOrBlank()) prefs.remove(etagKey) else prefs[etagKey] = etag
            if (lastModified.isNullOrBlank()) {
                prefs.remove(lastModifiedKey)
            } else {
                prefs[lastModifiedKey] = lastModified
            }
            prefs[booleanPreferencesKey(WebDavPrefs.REMOTE_WRITTEN)] = true
            prefs.remove(stringPreferencesKey(WebDavPrefs.REPAIR_ETAG))
            prefs.remove(stringPreferencesKey(WebDavPrefs.REPAIR_LAST_MODIFIED))
            prefs.remove(stringPreferencesKey(WebDavPrefs.REPAIR_URL))
            prefs.remove(intPreferencesKey(WebDavPrefs.REPAIR_ATTEMPTS))
        }
    }

    /**
     * R2-01: one transaction for a publication that could not be verified.
     *
     * The ownership is cleared *and* the repair lease is written together. Written separately, a
     * crash after the clear but before the lease left exactly the "no validator, no ownership, no
     * lease" state that becomes a permanent conflict loop. Only a readable, non-collection remote
     * with a validator can be a repair target; anything else clears the lease.
     */
    private suspend fun storeUnverifiedPublication(
        head: RemoteArchiveState,
        uploadEtag: String? = null,
        moveEtag: String? = null,
        uploadLastModified: String? = null,
        localLength: Long = -1L,
        canonicalUrl: String? = null,
    ) {
        val present = (head as? RemoteArchiveState.Present)?.takeIf { !it.isCollection }
        // R4-01: the lease may only name a remote whose ETag this publication produced (the staging
        // PUT's or the MOVE's). Anything else may be another device's shorter file that appeared
        // between the MOVE and the HEAD; overwriting it would be exactly the silent multi-device
        // loss the lease exists to prevent.
        val establishLease = WebDavRepairLease.shouldEstablish(
            remote = head,
            uploadEtag = uploadEtag,
            moveEtag = moveEtag,
            localLength = localLength,
        )
        val repairEtag = if (establishLease) present?.etag else null
        val repairLastModified = if (establishLease) present?.lastModified else null
        applicationContext.webDavDataStore.edit { prefs ->
            prefs.remove(stringPreferencesKey(WebDavPrefs.ETAG))
            prefs.remove(stringPreferencesKey(WebDavPrefs.LAST_MODIFIED))
            prefs[booleanPreferencesKey(WebDavPrefs.REMOTE_WRITTEN)] = false
            val etagKey = stringPreferencesKey(WebDavPrefs.REPAIR_ETAG)
            val lastModifiedKey = stringPreferencesKey(WebDavPrefs.REPAIR_LAST_MODIFIED)
            val urlKey = stringPreferencesKey(WebDavPrefs.REPAIR_URL)
            val attemptsKey = intPreferencesKey(WebDavPrefs.REPAIR_ATTEMPTS)
            if (establishLease && repairEtag != null) {
                // R4-01: a lease on the same URL continues its attempt budget even when the server
                // minted a new ETag for this failed write; a different target starts a fresh one.
                val attempts = WebDavRepairLease.nextAttempts(
                    previousUrl = prefs[urlKey],
                    canonicalUrl = canonicalUrl,
                    previousAttempts = prefs[attemptsKey] ?: 0,
                )
                prefs[etagKey] = repairEtag
                if (repairLastModified.isNullOrBlank()) {
                    prefs.remove(lastModifiedKey)
                } else {
                    prefs[lastModifiedKey] = repairLastModified
                }
                if (canonicalUrl.isNullOrBlank()) prefs.remove(urlKey) else prefs[urlKey] = canonicalUrl
                prefs[attemptsKey] = attempts
            } else {
                // A remote with no usable proof (longer/equal length, no length, no strong upload
                // ETag, missing) is not a repair target; never leave a stale lease behind.
                prefs.remove(etagKey)
                prefs.remove(lastModifiedKey)
                prefs.remove(urlKey)
                prefs.remove(attemptsKey)
            }
        }
    }

    /** Drops the repair lease once the canonical file is verified (or proven to be someone else's). */
    private suspend fun clearRepairTarget() {
        applicationContext.webDavDataStore.edit { prefs ->
            prefs.remove(stringPreferencesKey(WebDavPrefs.REPAIR_ETAG))
            prefs.remove(stringPreferencesKey(WebDavPrefs.REPAIR_LAST_MODIFIED))
            prefs.remove(stringPreferencesKey(WebDavPrefs.REPAIR_URL))
            prefs.remove(intPreferencesKey(WebDavPrefs.REPAIR_ATTEMPTS))
        }
    }

    /** Weak validators are not valid in If-Match, so they are treated as "no validator". */
    private fun normalizeEtag(raw: String?): String? =
        raw?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("W/") }

    /**
     * `notes-backup-encrypted.zip` becomes `notes-backup-encrypted.conflict-20261020-231500-123.zip`
     * so the copy sits next to the original rather than replacing it. Milliseconds are included
     * because two devices in the same second used to produce the same name.
     *
     * W7: [attempt] is the number of creates already rejected with 412 for that name, so a retry
     * always lands on a name that is still free. SYN-5: the name is inserted through HttpUrl, so
     * a path with spaces or non-ASCII characters stays a valid, percent-encoded URL.
     */
    private fun conflictUrl(url: String, attempt: Int = 0): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
        val suffix = if (attempt == 0) ".conflict-$stamp" else ".conflict-$stamp-${attempt + 1}"
        return WebDavPublish.insertBeforeExtension(url, suffix)
    }

    private data class UploadResult(val code: Int, val etag: String?, val lastModified: String? = null)

    /**
     * `Content-MD5` for [file]: base64(md5(bytes)), streamed so a library full of recordings
     * never has to sit in memory just to be hashed (W4).
     */
    private fun contentMd5(file: File): String {
        val digest = MessageDigest.getInstance("MD5")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return Base64.getEncoder().encodeToString(digest.digest())
    }

    private suspend fun writeEncryptedArchive(target: File, passphrase: String) {
        val portable = File.createTempFile("webdav-portable", ".db", applicationContext.cacheDir)
        try {
            // DAT-8: the portable copy, the attachment rows and the zip built from them are one
            // snapshot and must share the restore mutex - a restore that closes and replaces the
            // database in this window would otherwise produce a "new database + old attachments"
            // archive. The upload itself stays outside the lock: a restore may wait for the
            // snapshot, but not for a slow network.
            BackupRestoreLock.mutex.withLock {
                val provider = BackupDatabase.provider(applicationContext)
                // N-5: take the attachment rows from the portable-copy transaction instead of
                // re-querying the live table after it. A save in between could prune a row whose
                // file was already selected, producing a row without bytes (or vice versa).
                val attachments = provider.writePortableDatabaseCopy(portable)

                val zipFile = File(target.absolutePath + ZIP_TEMP_SUFFIX)
                try {
                    ZipOutputStream(FileOutputStream(zipFile)).use { zip ->
                        // CQ-03: the shared writer owns the member layout; this producer only adds
                        // its freshness manifest as the first entry through the hook.
                        BackupArchiveWriter.write(
                            zip = zip,
                            context = applicationContext,
                            portableDatabase = portable,
                            attachments = attachments,
                            beforeDatabaseEntry = {
                                // W6: freshness manifest as the first entry, so a restore can tell
                                // how old this backup is before it replaces anything. Archives
                                // without one (written by older builds) remain legal - they just
                                // have no recorded age.
                                BackupArchiveCrypto.writeManifestEntry(
                                    zip = zip,
                                    noteCount = countNotes(portable),
                                    attachmentCount = attachments.size
                                )
                            },
                        )
                    }

                    // Stream the archive through the cipher into the target file. The old
                    // readBytes() -> encrypt -> write held the whole archive (database + every
                    // recording) as up to three byte arrays at once.
                    FileOutputStream(target).use { output ->
                        FileInputStream(zipFile).use { zipInput ->
                            BackupArchiveCrypto.encryptStream(zipInput, output, passphrase)
                        }
                    }
                } finally {
                    zipFile.delete()
                }
            }
        } finally {
            portable.delete()
        }
    }

    /**
     * Note count for the manifest (W6), read from the portable copy that is about to be zipped -
     * that copy *is* the archive's content, trashed notes included. A failure here means the
     * copy itself is unusable, so it propagates and the backup fails rather than uploading an
     * archive whose manifest would lie about what it holds.
     */
    private fun countNotes(portable: File): Int =
        android.database.sqlite.SQLiteDatabase.openDatabase(
            portable.absolutePath,
            null,
            android.database.sqlite.SQLiteDatabase.OPEN_READONLY
        ).use { db ->
            db.rawQuery("SELECT COUNT(*) FROM `notes-table`", null).use { cursor ->
                if (cursor.moveToFirst()) cursor.getInt(0) else 0
            }
        }

    private companion object {
        const val TAG = "WebDAVBackupWorker"
        const val BACKUP_FILENAME = "notes-backup-encrypted.zip"
        const val ZIP_TEMP_SUFFIX = ".ziptmp"

        /** SYN-1: the properties a Depth: 0 PROPFIND asks for when HEAD is unavailable. */
        val PROPFIND_BODY = """
            <?xml version="1.0" encoding="utf-8"?>
            <D:propfind xmlns:D="DAV:">
              <D:prop>
                <D:getetag/>
                <D:getlastmodified/>
                <D:getcontentlength/>
                <D:resourcetype/>
              </D:prop>
            </D:propfind>
        """.trimIndent()

        /**
         * W7: how many conflict-copy names to try before giving up.
         *
         * A 412 on the conditional create means the name was already taken; each retry gets a
         * numbered suffix. Four attempts cover a collision that a clock alone cannot explain.
         */
        const val MAX_CONFLICT_ATTEMPTS = 4

        /** WebDAV: the precondition failed, i.e. the remote file changed under us. */
        const val PRECONDITION_FAILED = 412

        /** Some servers answer a MOVE they cannot apply the destination `If` to with a plain 400. */
        const val BAD_REQUEST = 400

        /** SYN-3: WebDAV "insufficient storage"; repeating full conflict copies is what hits it. */
        const val INSUFFICIENT_STORAGE = 507
    }
}

/**
 * Worker input: overwrite the canonical remote file regardless of the stored validator.
 *
 * The conflict card's "take over the remote" action sets this. Public because the cloud screen
 * enqueues the work with it.
 */
const val DATA_FORCE_OVERWRITE = "force_overwrite"

/**
 * SYN-2/SYN-4: the two naming/precondition rules of the WebDAV publication, split into pure
 * functions so the *header shape* can be tested without a server.
 *
 * The rules are easy to get wrong in a way only a strict server notices:
 *  - `If-Match`, `If-None-Match` and `If-Unmodified-Since` on a `MOVE` describe the **request
 *    URI** (the staging file), not `Destination`. The old code sent them on the MOVE anyway, so
 *    a strict server either failed every publish with 412 or silently left the canonical file
 *    unprotected.
 *  - A destination precondition is expressible only as RFC 4918 §10.4.1
 *    `If: <destination> ([etag])`, or as `Overwrite: F` for "must not exist". A Last-Modified-only
 *    validator has no such form, so publication has to stay on the canonical URL as a conditional
 *    `PUT`.
 */
internal object WebDavPublish {

    /** SYN-4: appended to the file name to make the staging name install-scoped. */
    private const val UPLOADING_SUFFIX = ".uploading-"

    /**
     * What the MOVE must carry, and how to publish when it cannot carry anything.
     *
     * @property headers request headers for the MOVE (`Overwrite`, plus a tagged `If` when an ETag
     *   is known). Empty when [directPut] is set.
     * @property directPut true when the destination must be written by a conditional `PUT` on the
     *   canonical URL instead of a MOVE, because no header can express the precondition.
     * @property retryDirectPutOnReject true when the MOVE used a destination-side precondition, so
     *   a 400/412 from a server that rejects or ignores it may be retried once as a direct PUT.
     */
    internal data class DestinationPrecondition(
        val headers: Map<String, String>,
        val directPut: Boolean,
        val retryDirectPutOnReject: Boolean,
    )

    /**
     * Builds the publication plan for the canonical file.
     *
     * Only a known ETag produces a tagged `If`: that is the one RFC 4918 form in which a MOVE can
     * name a precondition about `Destination`. `ifNoneMatch` becomes `Overwrite: F`, which every
     * WebDAV server understands for "do not replace"; a 412 then means the file appeared.
     * `ifUnmodifiedSince` alone is deliberately *not* translated - see [DestinationPrecondition.directPut].
     */
    internal fun destinationPreconditionHeaders(
        destinationUrl: String,
        ifMatch: String?,
        ifNoneMatch: Boolean,
        ifUnmodifiedSince: String?,
    ): DestinationPrecondition {
        // SYN-5: OkHttp rejects header values outside 0x20..0x7e and RFC 4918 wants an absolute
        // URI, so a path with spaces or CJK characters used to throw while the MOVE was built.
        // Encoding through HttpUrl makes the `Destination`/`If` values legal ASCII.
        val destination = encodedUrl(destinationUrl)
        return when {
            ifMatch != null -> DestinationPrecondition(
                headers = mapOf(
                    "Overwrite" to "T",
                    "If" to "<$destination> ([${quoteEtag(ifMatch)}])",
                ),
                directPut = false,
                retryDirectPutOnReject = true,
            )
            ifNoneMatch -> DestinationPrecondition(
                headers = mapOf("Overwrite" to "F"),
                directPut = false,
                retryDirectPutOnReject = true,
            )
            ifUnmodifiedSince != null -> DestinationPrecondition(
                // A MOVE would evaluate the header against the staging file, so it expresses
                // nothing about the destination; the canonical PUT does.
                headers = emptyMap(),
                directPut = true,
                retryDirectPutOnReject = false,
            )
            else -> DestinationPrecondition(
                // SYN-1: unreachable for an automatic upload - the publication decision refuses
                // an unknown/folder remote and creates with Overwrite: F when the file is gone.
                // Only the user's explicit "overwrite remote" reaches this branch.
                headers = mapOf("Overwrite" to "T"),
                directPut = false,
                retryDirectPutOnReject = false,
            )
        }
    }

    /**
     * RFC 7232 entity-tags are quoted. Servers are supposed to return them that way, but the
     * `If` grammar has no tolerance for a bare token, so an unquoted value is repaired here.
     */
    internal fun quoteEtag(etag: String): String {
        val trimmed = etag.trim()
        return if (trimmed.length >= 2 && trimmed.startsWith('"') && trimmed.endsWith('"')) {
            trimmed
        } else {
            "\"$trimmed\""
        }
    }

    /**
     * SYN-5: the ASCII form of a configured URL, or a typed failure.
     *
     * Everything that becomes a request URL or a header value goes through here so the raw user
     * input is never concatenated into a `Destination`/`If` header.
     */
    internal fun encodedUrl(rawUrl: String): String =
        rawUrl.toHttpUrlOrNull()?.toString()
            ?: throw InvalidWebDavUrlException(folder = false, url = rawUrl)

    /**
     * SYN-11: a URL that names a folder (no final path segment) can never be a backup file.
     *
     * Returns the percent-encoded form of [rawUrl] so the worker performs every request against
     * the same canonical spelling.
     */
    internal fun requireFileUrl(rawUrl: String): String {
        val parsed = rawUrl.toHttpUrlOrNull()
            ?: throw InvalidWebDavUrlException(folder = false, url = rawUrl)
        if (parsed.pathSegments.lastOrNull().isNullOrBlank()) {
            throw InvalidWebDavUrlException(folder = true, url = rawUrl)
        }
        return parsed.toString()
    }

    /**
     * Appends [suffix] to the file name of [rawUrl], keeping the query string and the
     * percent-encoding discipline of [stagingUrl]. Used for the install-scoped staging name.
     */
    internal fun appendToFileName(rawUrl: String, suffix: String): String =
        rebuildWithFileName(rawUrl) { fileName -> fileName + suffix }

    /**
     * Inserts [suffix] before the extension of [rawUrl]'s file name, so
     * `notes.zip` becomes `notes.conflict-20261120.zip` and keeps a usable extension. Used for
     * the date-stamped conflict copies; also HttpUrl-based (SYN-5).
     */
    internal fun insertBeforeExtension(rawUrl: String, suffix: String): String =
        rebuildWithFileName(rawUrl) { fileName ->
            val dot = fileName.lastIndexOf('.')
            if (dot > 0) fileName.substring(0, dot) + suffix + fileName.substring(dot)
            else fileName + suffix
        }

    /**
     * `<canonical>.uploading-<installId>`, with the query string kept. Two installs therefore
     * never share a staging name, and this install can delete its own leftovers without touching
     * another device's upload. SYN-5: the insertion happens on HttpUrl path segments, so spaces
     * and non-ASCII names are encoded exactly once.
     */
    internal fun stagingUrl(canonicalUrl: String, installId: String): String =
        appendToFileName(canonicalUrl, UPLOADING_SUFFIX + installId)

    private fun rebuildWithFileName(rawUrl: String, rename: (String) -> String): String {
        val parsed = rawUrl.toHttpUrlOrNull()
            ?: throw InvalidWebDavUrlException(folder = false, url = rawUrl)
        val segments = parsed.pathSegments.toMutableList()
        val fileName = segments.lastOrNull()
        if (fileName.isNullOrBlank()) {
            throw InvalidWebDavUrlException(folder = true, url = rawUrl)
        }
        segments[segments.lastIndex] = rename(fileName)
        return rebuild(parsed, segments).toString()
    }

    private fun rebuild(parsed: HttpUrl, segments: List<String>): HttpUrl {
        val builder = HttpUrl.Builder()
            .scheme(parsed.scheme)
            .host(parsed.host)
            .port(parsed.port)
            .encodedQuery(parsed.encodedQuery)
        if (parsed.username.isNotEmpty()) builder.username(parsed.username)
        if (parsed.password.isNotEmpty()) builder.password(parsed.password)
        segments.forEach { builder.addPathSegment(it) }
        return builder.build()
    }
}

/**
 * SYN-5/SYN-11: a configured WebDAV address that cannot be turned into a request URL, or that
 * names a collection. [folder] lets the worker choose the "this is a folder" message without
 * parsing the message text.
 */
internal class InvalidWebDavUrlException(
    val folder: Boolean,
    url: String,
) : IllegalArgumentException(
    if (folder) "WebDAV URL points at a collection, not a file: $url" else "Invalid WebDAV URL: $url"
)
