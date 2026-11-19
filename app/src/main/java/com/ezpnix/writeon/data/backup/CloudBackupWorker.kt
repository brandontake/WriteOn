package com.ezpnix.writeon.data.backup

import android.app.Application
import android.content.Context
import android.util.Log
import android.widget.Toast
import com.ezpnix.writeon.R
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ezpnix.writeon.core.attachment.AttachmentArchive
import com.ezpnix.writeon.core.constant.DatabaseConst
import com.ezpnix.writeon.core.util.BackupArchiveCrypto
import com.ezpnix.writeon.data.local.database.BackupDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
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
     * W10/W11: the shared client carries the call timeout and the "never follow a redirect"
     * policy. A bare `OkHttpClient()` had neither.
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

            writeEncryptedArchive(archive, passphrase)

            val authorization = Credentials.basic(username, password)
            // W4: clear the staging file an interrupted previous run left behind - best-effort,
            // before anything else touches the server.
            val stagingUrl = uploadingUrl(webdavUrl)
            deleteRemote(stagingUrl, authorization)
            val remote = fetchRemote(webdavUrl, authorization)
            var knownEtag = prefs[stringPreferencesKey(WebDavPrefs.ETAG)]
            // W5: a server without ETags still reports Last-Modified. It is the weaker validator
            // the conditional PUT falls back to when there is no ETag to match on.
            var knownLastModified = prefs[stringPreferencesKey(WebDavPrefs.LAST_MODIFIED)]
            var remoteWritten = prefs[booleanPreferencesKey(WebDavPrefs.REMOTE_WRITTEN)] ?: false

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
            if (!forceOverwrite && remote.exists == false &&
                (knownEtag != null || knownLastModified != null)
            ) {
                Log.w(
                    TAG,
                    "Remote archive no longer exists; forgetting the stale validator so it can be recreated"
                )
                storeEtag(null)
                storeLastModified(null)
                storeRemoteWritten(false)
                knownEtag = null
                knownLastModified = null
                remoteWritten = false
            }

            // Never let a fresh install (or a post-restore device with no remembered validator)
            // replace a backup it did not write. An ETag we have never seen, or a file that exists
            // while we have no ETag at all, means "someone else owns this file": keep both copies.
            val remoteChanged = remote.etag != null && knownEtag != null && remote.etag != knownEtag
            val unknownRemote = remote.exists == true && knownEtag == null && !remoteWritten
            if ((remoteChanged || unknownRemote) && !forceOverwrite) {
                // Not a plain failure: keepConflictCopy toasts its own outcome ("kept as a
                // conflict copy", or "failed" if even that upload did not land).
                keepConflictCopy(webdavUrl, authorization, archive)
                return@withContext Result.failure()
            }

            // W5: with neither a remembered nor a reported validator there is nothing at all to
            // compare against. On a server that reports no ETag this used to end in
            // `storeEtag(null)` + a bare, silent overwrite of a file we cannot tell apart from
            // our own. Refuse terminally instead; "take over the remote" is the explicit way to
            // say this device's notes win. (When the server does report a validator, the upload
            // below stays conditional on it - that is never a bare PUT.)
            if (!forceOverwrite && remote.exists == true && knownEtag == null &&
                knownLastModified == null && remote.etag == null && remote.lastModified == null
            ) {
                return@withContext failWithNoValidator()
            }

            // Preconditions for the canonical file, sent on the MOVE itself and on the direct
            // PUT fallback (RFC preconditions apply to every method; a server that ignores them
            // on MOVE is exactly the server that falls back to the conditional PUT anyway).
            // Remembered validators win; the values from the HEAD above only bootstrap a device
            // that has none stored (an upgrade, or a server that started reporting them) and
            // still guard the window between that HEAD and the upload.
            // Under force, the precondition is the *current* remote validator (create if absent).
            val ifNoneMatch = !forceOverwrite && knownEtag == null && knownLastModified == null && !remoteWritten
            val ifMatch = when {
                forceOverwrite -> remote.etag
                ifNoneMatch -> null
                knownEtag != null -> knownEtag
                else -> remote.etag
            }
            val ifUnmodifiedSince = when {
                forceOverwrite || ifNoneMatch || ifMatch != null -> null
                knownLastModified != null -> knownLastModified
                else -> remote.lastModified
            }

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
                        return@withContext failWithToast()
                    }
                    publishStagedArchive(
                        stagingUrl = stagingUrl,
                        canonicalUrl = webdavUrl,
                        authorization = authorization,
                        archive = archive,
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

    private data class RemoteState(val exists: Boolean?, val etag: String?, val lastModified: String? = null)

    /**
     * One HEAD tells us whether the file on the server is still the one we uploaded last time,
     * whether it exists at all, and - for servers that offer no ETag - when it last changed.
     * `exists = null` means the server would not tell us.
     */
    private fun fetchRemote(url: String, authorization: String): RemoteState = try {
        val request = Request.Builder()
            .url(url)
            .head()
            .header("Authorization", authorization)
            .build()
        client.newCall(request).execute().use { response ->
            when {
                response.code == 404 -> RemoteState(exists = false, etag = null)
                response.code == 405 || response.code == 501 -> RemoteState(exists = null, etag = null)
                response.code in 200..299 -> RemoteState(
                    exists = true,
                    etag = normalizeEtag(response.header("ETag")),
                    lastModified = response.header("Last-Modified")
                )
                else -> RemoteState(exists = null, etag = null)
            }
        }
    } catch (e: IOException) {
        // An unreachable server fails the PUT too; "unknown" simply means "no precondition".
        Log.w(TAG, "Could not read the remote ETag", e)
        RemoteState(exists = null, etag = null)
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
            client.newCall(builder.build()).execute().use { response ->
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
     * [conflictUrl] follows), and `Overwrite: T` lets the server replace the canonical file in
     * one atomic step on its side - the canonical name is either the old complete archive or
     * the new one, never a partial upload.
     *
     * The canonical-file preconditions travel with the MOVE as well: `Overwrite: T` is
     * unconditional on its own, so without them the happy path would silently replace a file
     * another device had just won (the exact race the conditional PUT used to catch with a
     * 412 -> conflict copy, which is still how a server that rejects this header is handled).
     *
     * @return the HTTP status, or 0 when the request never completed.
     */
    private fun moveArchive(
        stagingUrl: String,
        destinationUrl: String,
        authorization: String,
        ifMatch: String?,
        ifNoneMatch: Boolean,
        ifUnmodifiedSince: String?
    ): Int {
        val builder = Request.Builder()
            .url(stagingUrl)
            .method("MOVE", null)
            .header("Authorization", authorization)
            .header("Destination", destinationUrl)
            .header("Overwrite", "T")
        if (ifMatch != null) {
            builder.header("If-Match", ifMatch)
        }
        if (ifNoneMatch) {
            builder.header("If-None-Match", "*")
        }
        if (ifUnmodifiedSince != null) {
            builder.header("If-Unmodified-Since", ifUnmodifiedSince)
        }
        return try {
            client.newCall(builder.build()).execute().use { response -> response.code }
        } catch (e: IOException) {
            Log.w(TAG, "WebDAV MOVE failed", e)
            0
        }
    }

    /**
     * Best-effort DELETE used to clear a stale staging file before an upload and to drop the
     * staging copy when the server cannot MOVE it. A 404 is the normal "nothing to delete" case.
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
     * Takes the staged archive live: `MOVE` it onto the canonical name, then remember the
     * canonical file's validators for the next run.
     *
     * Servers that do not implement `MOVE` (405/501) fall back to the direct conditional PUT
     * this build used before W4, with the same preconditions, and the fallback is logged so a
     * missing MOVE is diagnosable instead of silent.
     */
    private suspend fun publishStagedArchive(
        stagingUrl: String,
        canonicalUrl: String,
        authorization: String,
        archive: File,
        ifMatch: String?,
        ifNoneMatch: Boolean,
        ifUnmodifiedSince: String?,
        recoveredFromStaleValidator: Boolean = false
    ): Result {
        val move = moveArchive(
            stagingUrl = stagingUrl,
            destinationUrl = canonicalUrl,
            authorization = authorization,
            ifMatch = ifMatch,
            ifNoneMatch = ifNoneMatch,
            ifUnmodifiedSince = ifUnmodifiedSince
        )
        return when {
            move in 200..299 -> {
                // A MOVE response often omits validators; re-HEAD the canonical file (the same
                // rule the old post-PUT HEAD followed) so a stale validator is never stored.
                val head = fetchRemote(canonicalUrl, authorization)
                storeEtag(head.etag)
                storeLastModified(head.lastModified)
                storeRemoteWritten(true)
                // W18: the canonical file is this device's again, so the parked-copy notices no
                // longer describe an unresolved conflict and must not outlive it.
                WebDavBackupState.recordSuccess(applicationContext)
                toastOnMain(applicationContext.getString(R.string.cloud_backup_completed))
                Result.success()
            }
            move == 405 || move == 501 -> {
                Log.w(TAG, "Server rejected MOVE with HTTP $move; falling back to a direct conditional PUT")
                // The staged copy is about to become redundant; do not leave it in the folder.
                deleteRemote(stagingUrl, authorization)
                directConditionalPut(canonicalUrl, authorization, archive, ifMatch, ifNoneMatch, ifUnmodifiedSince)
            }
            move == PRECONDITION_FAILED -> {
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
                        ifMatch = null,
                        ifNoneMatch = true,
                        ifUnmodifiedSince = null,
                        recoveredFromStaleValidator = true
                    )
                }
                keepConflictCopy(canonicalUrl, authorization, archive)
                Result.failure()
            }
            WebDavRetry.isRedirect(move) -> failWithRedirect(move)
            else -> retryOrFail(move, "WebDAV MOVE to the canonical name failed")
        }
    }

    /**
     * The pre-W4 publication path: one conditional PUT straight onto the canonical name. It is
     * the fallback for servers that reject `MOVE` (405/501), and the only path those servers
     * ever take. Success stores both validators; a lost race still becomes a conflict copy.
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
            ifUnmodifiedSince = ifUnmodifiedSince
        )
        return when {
            upload.code in 200..299 -> {
                // A PUT response often omits validators. Re-HEAD instead of storing the values
                // read before the upload: a stale ETag made every later run look like a conflict,
                // so the remote file never advanced again.
                val head = if (upload.etag == null || upload.lastModified == null) {
                    fetchRemote(url, authorization)
                } else {
                    null
                }
                storeEtag(upload.etag ?: head?.etag)
                storeLastModified(upload.lastModified ?: head?.lastModified)
                storeRemoteWritten(true)
                // W18: the canonical file is this device's again, so the parked-copy notices no
                // longer describe an unresolved conflict and must not outlive it.
                WebDavBackupState.recordSuccess(applicationContext)
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
        if (head.exists != false) return false
        Log.w(TAG, "Canonical archive is gone; clearing the stale validator and publishing as a new file")
        storeEtag(null)
        storeLastModified(null)
        storeRemoteWritten(false)
        return true
    }

    /**
     * The remote changed, so this device's archive must not replace it. It goes next to the
     * canonical file under a date-stamped name instead, which leaves both copies *visible* in the
     * user's WebDAV folder: a silent overwrite cannot be undone, a file they can see can.
     */
    private fun keepConflictCopy(url: String, authorization: String, archive: File) {
        // W7: the copy is created conditionally. Unconditional, two devices that picked the same
        // millisecond overwrote each other's copy and only the last one survived - the opposite of
        // what a conflict copy is for. `If-None-Match: *` makes the create fail with 412 instead,
        // and that 412 moves the name forward.
        var attempt = 0
        var target = conflictUrl(url, attempt)
        var upload = putArchive(target, authorization, archive, ifMatch = null, ifNoneMatch = true)
        while (upload.code == PRECONDITION_FAILED && attempt < MAX_CONFLICT_ATTEMPTS - 1) {
            attempt++
            target = conflictUrl(url, attempt)
            Log.w(TAG, "Conflict copy name was taken; trying $target")
            upload = putArchive(target, authorization, archive, ifMatch = null, ifNoneMatch = true)
        }
        if (upload.code in 200..299) {
            Log.w(TAG, "Remote backup had changed; this device's copy was kept at $target")
            WebDavBackupState.recordConflict(applicationContext, target)
            // The conflict card only appears on the next visit to the cloud screen; the toast is
            // what makes "your copy was kept, not overwritten" visible right now (W3).
            toastOnMain(applicationContext.getString(R.string.cloud_conflict_kept))
        } else {
            Log.e(TAG, "Remote backup had changed and the conflict copy failed with HTTP " + upload.code)
            toastOnMain(applicationContext.getString(R.string.cloud_backup_failed))
        }
    }

    private suspend fun storeEtag(etag: String?) {
        applicationContext.webDavDataStore.edit { prefs ->
            val key = stringPreferencesKey(WebDavPrefs.ETAG)
            if (etag.isNullOrBlank()) prefs.remove(key) else prefs[key] = etag
        }
    }

    /**
     * Remembers the canonical file's Last-Modified (W5): on a server that offers no ETag it is
     * the only validator the next conditional PUT can compare against.
     */
    private suspend fun storeLastModified(lastModified: String?) {
        applicationContext.webDavDataStore.edit { prefs ->
            val key = stringPreferencesKey(WebDavPrefs.LAST_MODIFIED)
            if (lastModified.isNullOrBlank()) prefs.remove(key) else prefs[key] = lastModified
        }
    }

    private suspend fun storeRemoteWritten(written: Boolean) {
        applicationContext.webDavDataStore.edit { prefs ->
            prefs[booleanPreferencesKey(WebDavPrefs.REMOTE_WRITTEN)] = written
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
     * always lands on a name that is still free.
     */
    private fun conflictUrl(url: String, attempt: Int = 0): String {
        val queryIndex = url.indexOf('?')
        val base = if (queryIndex >= 0) url.substring(0, queryIndex) else url
        val query = if (queryIndex >= 0) url.substring(queryIndex) else ""
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
        val suffix = if (attempt == 0) stamp else "$stamp-${attempt + 1}"
        val dot = base.lastIndexOf('.')
        val slash = base.lastIndexOf('/')
        return if (dot > slash) {
            base.substring(0, dot) + ".conflict-" + suffix + base.substring(dot) + query
        } else {
            base + ".conflict-" + suffix + query
        }
    }

    private data class UploadResult(val code: Int, val etag: String?, val lastModified: String? = null)

    /**
     * `notes-backup-encrypted.zip` becomes `notes-backup-encrypted.zip.uploading`, keeping any
     * query string (the same rule [conflictUrl] follows). The staging name is what an
     * interrupted upload leaves behind, so the canonical file is only ever written by a MOVE
     * that had already verified the staged bytes (W4).
     */
    private fun uploadingUrl(url: String): String {
        val queryIndex = url.indexOf('?')
        val base = if (queryIndex >= 0) url.substring(0, queryIndex) else url
        val query = if (queryIndex >= 0) url.substring(queryIndex) else ""
        return base + UPLOADING_SUFFIX + query
    }

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
            val provider = BackupDatabase.provider(applicationContext)
            provider.writePortableDatabaseCopy(portable)
            val attachments = provider.attachmentDao().getAll()

            val zipFile = File(target.absolutePath + ZIP_TEMP_SUFFIX)
            try {
                ZipOutputStream(FileOutputStream(zipFile)).use { zip ->
                    // W6: freshness manifest as the first entry, so a restore can tell how old
                    // this backup is before it replaces anything. Archives without one (written
                    // by older builds) remain legal - they just have no recorded age.
                    BackupArchiveCrypto.writeManifestEntry(
                        zip = zip,
                        noteCount = countNotes(portable),
                        attachmentCount = attachments.size
                    )
                    zip.putNextEntry(ZipEntry(DatabaseConst.NOTES_DATABASE_FILE_NAME))
                    FileInputStream(portable).use { db -> db.copyTo(zip) }
                    zip.closeEntry()
                    AttachmentArchive.writeTo(zip, attachments)
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

        /** Appended to the canonical file name to form the W4 staging name. */
        const val UPLOADING_SUFFIX = ".uploading"

        /**
         * W7: how many conflict-copy names to try before giving up.
         *
         * A 412 on the conditional create means the name was already taken; each retry gets a
         * numbered suffix. Four attempts cover a collision that a clock alone cannot explain.
         */
        const val MAX_CONFLICT_ATTEMPTS = 4

        /** WebDAV: the precondition failed, i.e. the remote file changed under us. */
        const val PRECONDITION_FAILED = 412
    }
}

/**
 * Worker input: overwrite the canonical remote file regardless of the stored validator.
 *
 * The conflict card's "take over the remote" action sets this. Public because the cloud screen
 * enqueues the work with it.
 */
const val DATA_FORCE_OVERWRITE = "force_overwrite"
