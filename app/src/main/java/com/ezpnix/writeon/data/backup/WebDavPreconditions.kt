package com.ezpnix.writeon.data.backup

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.util.Locale

/**
 * SYN-1: what the server actually said about the canonical archive.
 *
 * The old worker collapsed every answer into `exists: Boolean?`, and every safety check only
 * fired when that value was `true`. A server that does not implement `HEAD` (405/501), or a
 * `HEAD` that failed transiently, therefore looked exactly like "no remote file" to the
 * precondition builder: no `If-Match`, no `If-None-Match`, and a bare `Overwrite: T` MOVE that
 * replaced whatever another device had just uploaded.
 *
 * Keeping the three outcomes apart is what makes the decision below total: "I could not ask"
 * is not "it is not there".
 */
internal sealed interface RemoteArchiveState {

    /** The server said the file does not exist (404, also from a PROPFIND fallback). */
    object Missing : RemoteArchiveState

    /**
     * The server answered and describes a file (or, [isCollection], a folder).
     *
     * Validators are nullable on purpose: many minimal NAS stacks return `200` for a file but
     * neither an `ETag` nor a `Last-Modified`. A present file without either is still *present* -
     * it just cannot be overwritten safely, which is a refusal, not an unknown.
     */
    data class Present(
        val etag: String? = null,
        val lastModified: String? = null,
        val contentLength: Long? = null,
        val isCollection: Boolean = false,
    ) : RemoteArchiveState

    /**
     * The server could not answer: `HEAD` and the `PROPFIND` fallback both failed, or the request
     * never completed. [reason] is for the log; it is never shown to the user.
     */
    data class Unknown(val reason: String) : RemoteArchiveState
}

/**
 * SYN-1: how the canonical file may be written, derived from [RemoteArchiveState].
 *
 * The only unconditional overwrite is [ForcedOverwrite], and it is reachable only through the
 * user's explicit "overwrite remote" action. Every automatic publication carries a precondition
 * or creates with `If-None-Match: *`.
 */
internal sealed interface CanonicalPublication {

    /** The file is gone: create it, refusing to replace anything that appeared in the meantime. */
    object Create : CanonicalPublication

    /**
     * The file exists and at least one validator is known. Exactly one of the fields is set:
     * `If-Match` wins over the weaker `If-Unmodified-Since`.
     */
    data class Conditional(
        val ifMatch: String?,
        val ifUnmodifiedSince: String?,
    ) : CanonicalPublication

    /** Explicit user takeover with nothing to condition on; never chosen by the automatic path. */
    object ForcedOverwrite : CanonicalPublication

    /** Refused before any write reached the server. */
    data class Refused(val reason: RefusalReason) : CanonicalPublication

    enum class RefusalReason {
        /** The remote state could not be read and no remembered validator exists to fall back on. */
        REMOTE_UNVERIFIED,

        /** The file exists, but the server exposes no ETag and no Last-Modified. */
        NO_VALIDATOR,

        /** The configured URL names a folder, not a file. */
        COLLECTION_URL,
    }
}

/**
 * SYN-1: the one decision about whether and how the canonical archive may be written.
 *
 * The rules, in order:
 *  - a collection is a configuration error, never a file to replace;
 *  - "missing" means create with `If-None-Match: *` (a file that appears in the meantime must
 *    become a conflict copy, not be replaced);
 *  - a present file needs a validator - remembered from our last write, or just read - and is
 *    then overwritten conditionally;
 *  - an unreadable remote is only writable under the user's explicit takeover. `REMOTE_WRITTEN`
 *    and a remembered validator deliberately do not count as proof here: they record that *some*
 *    earlier run uploaded, not that this run knows what is there now, and any write we cannot
 *    condition on the current file is exactly the silent overwrite this decision exists to stop.
 */
internal object WebDavPreconditions {

    fun decide(
        remote: RemoteArchiveState,
        knownEtag: String?,
        knownLastModified: String?,
        forceOverwrite: Boolean,
    ): CanonicalPublication {
        if (remote is RemoteArchiveState.Present && remote.isCollection) {
            return CanonicalPublication.Refused(CanonicalPublication.RefusalReason.COLLECTION_URL)
        }

        if (forceOverwrite) {
            return when (remote) {
                RemoteArchiveState.Missing -> CanonicalPublication.Create
                is RemoteArchiveState.Present -> {
                    // Takeover still conditions on what is on the server *now* when the server
                    // told us: a change between this check and the write must fail the write,
                    // not be clobbered. A remembered validator is only a fallback for the
                    // fields the response omitted.
                    val etag = remote.etag ?: knownEtag
                    val lastModified =
                        if (etag != null) null else remote.lastModified ?: knownLastModified
                    when {
                        etag != null -> CanonicalPublication.Conditional(etag, null)
                        lastModified != null -> CanonicalPublication.Conditional(null, lastModified)
                        else -> CanonicalPublication.ForcedOverwrite
                    }
                }
                // The server would not say what is there. "Overwrite remote" is the user's
                // explicit answer to that question; it is the only unconditional write left.
                is RemoteArchiveState.Unknown -> CanonicalPublication.ForcedOverwrite
            }
        }

        return when (remote) {
            RemoteArchiveState.Missing -> CanonicalPublication.Create
            is RemoteArchiveState.Present -> {
                val etag = knownEtag ?: remote.etag
                val lastModified = if (etag != null) null else knownLastModified ?: remote.lastModified
                when {
                    etag != null -> CanonicalPublication.Conditional(etag, null)
                    lastModified != null -> CanonicalPublication.Conditional(null, lastModified)
                    else -> CanonicalPublication.Refused(CanonicalPublication.RefusalReason.NO_VALIDATOR)
                }
            }
            is RemoteArchiveState.Unknown ->
                // "I could not read it" never authorises a write, whatever earlier runs remember:
                // the user has to choose the takeover explicitly.
                CanonicalPublication.Refused(CanonicalPublication.RefusalReason.REMOTE_UNVERIFIED)
        }
    }
}

/**
 * R2-01: whether the current remote file is the one a failed publication left behind.
 *
 * A verified upload stores its validator as ownership. An *unverified* one must not do that - the
 * bytes may be a truncation - but forgetting every trace made the next run treat the same file as a
 * stranger's and park a conflict copy forever (canonical never repaired, new notes never uploaded).
 * The repair lease is the middle ground: it names only the exact remote state this install saw at
 * the failed write, and authorises one conditional re-publication *if that state is unchanged*.
 * Anything the file changed to since - or any other remote - is back to the normal conflict rule.
 */
internal object WebDavRepairLease {

    /**
     * R3-02: how many times a lease may drive an automatic repair before it is abandoned.
     *
     * Each attempt re-reads the remote and conditionally overwrites the exact file this install
     * wrote. A lease that keeps failing is either a permanently broken server or a remote that is
     * no longer ours; either way the normal conflict-copy rules must take over.
     */
    const val MAX_REPAIR_ATTEMPTS = 3

    /**
     * R3-02/R4-01: whether a failed publication may create a repair lease at all.
     *
     * Requirements, all of which narrow the "this is my own truncated write" claim:
     * - the remote is a readable file;
     * - its length is *shorter* than the archive we uploaded (the truncation shape this exists
     *   for - a remote that is longer or equal is not explained by a dropped tail);
     * - the post-write HEAD's ETag is one this publication itself produced - the staging PUT's or
     *   the MOVE's. R4-01: length alone is not proof. Another device can write a shorter archive
     *   between the MOVE and the HEAD, and recording *that* file's ETag as a lease made the next
     *   run conditionally overwrite a stranger's backup. When the server gives us no ETag of our
     *   own to compare (or mints one we never saw), the file cannot be distinguished from a
     *   foreign write and no lease is created - the normal conflict-copy rule applies instead.
     */
    fun shouldEstablish(
        remote: RemoteArchiveState,
        uploadEtag: String?,
        moveEtag: String?,
        localLength: Long,
    ): Boolean {
        val present = remote as? RemoteArchiveState.Present ?: return false
        if (present.isCollection) return false
        val remoteLength = present.contentLength ?: return false
        if (remoteLength >= localLength) return false
        val headEtag = strongEtag(present.etag) ?: return false
        return headEtag == strongEtag(uploadEtag) || headEtag == strongEtag(moveEtag)
    }

    /**
     * R4-01: the attempt budget is per URL, not per `(URL, ETag)`.
     *
     * A server that mints a fresh ETag on every write made each failed publication look like a
     * brand-new target, so [MAX_REPAIR_ATTEMPTS] never fired and the loop could overwrite the same
     * canonical file forever. The URL identifies the repair target; the ETag is only ever the
     * exact state a single attempt observed.
     */
    fun nextAttempts(
        previousUrl: String?,
        canonicalUrl: String?,
        previousAttempts: Int,
    ): Int =
        if (previousUrl != null && previousUrl == canonicalUrl) previousAttempts + 1 else 1

    /** An entity tag usable in `If-Match`; a weak validator identifies nothing. */
    private fun strongEtag(raw: String?): String? =
        raw?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("W/") }

    fun matches(
        remote: RemoteArchiveState,
        repairEtag: String?,
        repairLastModified: String?,
    ): Boolean {
        val present = remote as? RemoteArchiveState.Present ?: return false
        if (present.isCollection) return false
        // An ETag is the strong identity of the file. When the lease has one, Last-Modified must
        // not be allowed to match on its own: a second device writing within the same second keeps
        // the timestamp but changes the entity tag, and that file is not ours to overwrite.
        if (repairEtag != null) return present.etag == repairEtag
        return repairLastModified != null && present.lastModified == repairLastModified
    }

    /**
     * R5-06: whether the lease may drive an automatic repair on this run.
     *
     * Pure so the worker's cap branch is testable without WorkManager: a matching lease is only
     * actionable while the URL-scoped attempt budget has room, and an exhausted lease falls back
     * to the normal conflict-copy rule.
     */
    fun canRepair(leaseMatches: Boolean, attempts: Int): Boolean =
        leaseMatches && attempts < MAX_REPAIR_ATTEMPTS
}

/**
 * REG-11: whether the file now under the canonical name is the one this run uploaded.
 *
 * A `MOVE` (or direct `PUT`) that answers 2xx only says the server accepted the request. The
 * post-write check used to be advisory: a mismatching ETag or a shorter file still reached
 * `Result.success()` and `REMOTE_WRITTEN=true`, so the next restore could discover a truncation
 * with no usable fallback. The verdict below is the one the worker acts on: [Verdict.verified]
 * false must become a retry or a failed/conflict-copy outcome, never "backup complete".
 *
 * The comparisons match the SYN-2/SYN-1 rules the worker has always applied:
 *  - equal ETags prove our bytes;
 *  - a different ETag is only a server-minted tag for our bytes when the MOVE response itself
 *    reported that same tag. R5-05: length equality alone is not proof - another device writing a
 *    same-size archive between the MOVE and the HEAD looks identical - so without the MOVE's own
 *    validator the publication stays unverified and the normal conflict-copy rule applies;
 *  - without any ETag the length is the only check, and an unreadable post-write HEAD still keeps
 *    the upload's own validators instead of forgetting the published file;
 *  - a readable HEAD with neither an ETag nor a length proves nothing (R5-05): it is unverified
 *    rather than an ownership record for a file this run cannot identify.
 */
internal object WebDavPublicationVerification {

    enum class Reason {
        /** The canonical HEAD returned the ETag the upload returned. */
        ETAG_MATCH,

        /**
         * A different ETag, but the MOVE response itself reported that tag: the file HEAD sees is
         * the one this run's MOVE produced (a server that mints tags per write).
         */
        SERVER_MINTED_ETAG,

        /** A different ETag with no proof that the file is this run's upload. */
        ETAG_MISMATCH,

        /** No ETag to compare, but the reported length is not the archive's length. */
        LENGTH_MISMATCH,

        /** No ETag, the length was readable and equal to the archive's. */
        LENGTH_ONLY_MATCH,

        /** Present, but neither an ETag nor a length: nothing identifies the bytes. */
        NO_VALIDATOR_OR_LENGTH,

        /** The post-write HEAD failed, but the upload's validators still describe our bytes. */
        HEAD_UNREADABLE_WITH_VALIDATORS,

        /** The post-write HEAD failed and no validator was returned by any request. */
        HEAD_UNREADABLE_WITHOUT_VALIDATORS,

        /** The MOVE reported success but the canonical file is not there. */
        MISSING_AFTER_MOVE,
    }

    data class Verdict(
        val verified: Boolean,
        /** The validator to remember when [verified]; always null otherwise. */
        val storedEtag: String?,
        val storedLastModified: String?,
        val reason: Reason,
    )

    fun verify(
        head: RemoteArchiveState,
        expectedEtag: String?,
        moveEtag: String?,
        moveLastModified: String?,
        uploadedLastModified: String?,
        localLength: Long,
    ): Verdict {
        val expected = normalizeEtag(expectedEtag)
        val move = normalizeEtag(moveEtag)
        // SYN-2 kept: the staging PUT's ETag is preferred as-is; a weak value it exposes is not
        // replaced by the MOVE's tag just because that one happens to be strong.
        val uploaded = normalizeEtag(expectedEtag ?: moveEtag)
        return when (head) {
            is RemoteArchiveState.Present -> {
                val headEtag = normalizeEtag(head.etag)
                val headLength = head.contentLength
                when {
                    uploaded != null && headEtag != null -> when {
                        uploaded == headEtag -> Verdict(
                            verified = true,
                            storedEtag = headEtag,
                            storedLastModified =
                                head.lastModified ?: moveLastModified ?: uploadedLastModified,
                            reason = Reason.ETAG_MATCH,
                        )
                        // R5-05: a different tag with the archive's length used to be accepted as
                        // a server-minted tag. It is proof only when the MOVE response itself
                        // reported the destination's tag; otherwise a same-size file written by
                        // another device in the MOVE->HEAD window is indistinguishable.
                        headLength != null && headLength == localLength &&
                            move != null && headEtag == move -> Verdict(
                            verified = true,
                            storedEtag = headEtag,
                            storedLastModified =
                                head.lastModified ?: moveLastModified ?: uploadedLastModified,
                            reason = Reason.SERVER_MINTED_ETAG,
                        )
                        else -> Verdict(
                            verified = false,
                            storedEtag = null,
                            storedLastModified = null,
                            reason = Reason.ETAG_MISMATCH,
                        )
                    }
                    headLength != null && headLength != localLength -> Verdict(
                        verified = false,
                        storedEtag = null,
                        storedLastModified = null,
                        reason = Reason.LENGTH_MISMATCH,
                    )
                    // R5-05: a present file with neither validator nor length says nothing about
                    // its bytes. Recording ownership here let the next run condition on a file
                    // this run never identified.
                    headLength == null -> Verdict(
                        verified = false,
                        storedEtag = null,
                        storedLastModified = null,
                        reason = Reason.NO_VALIDATOR_OR_LENGTH,
                    )
                    // R6-02: a HEAD tag this run cannot tie to its own upload is not proof of
                    // ownership. Length only proves the size; never remember a foreign tag.
                    headEtag != null -> Verdict(
                        verified = false,
                        storedEtag = null,
                        storedLastModified = null,
                        reason = Reason.ETAG_MISMATCH,
                    )
                    else -> Verdict(
                        verified = true,
                        storedEtag = null,
                        storedLastModified =
                            head.lastModified ?: moveLastModified ?: uploadedLastModified,
                        reason = Reason.LENGTH_ONLY_MATCH,
                    )
                }
            }
            is RemoteArchiveState.Unknown -> {
                val fallbackEtag = move ?: expected
                val fallbackLastModified = moveLastModified ?: uploadedLastModified
                if (fallbackEtag == null && fallbackLastModified == null) {
                    Verdict(
                        verified = false,
                        storedEtag = null,
                        storedLastModified = null,
                        reason = Reason.HEAD_UNREADABLE_WITHOUT_VALIDATORS,
                    )
                } else {
                    Verdict(
                        verified = true,
                        storedEtag = fallbackEtag,
                        storedLastModified = fallbackLastModified,
                        reason = Reason.HEAD_UNREADABLE_WITH_VALIDATORS,
                    )
                }
            }
            RemoteArchiveState.Missing -> Verdict(
                verified = false,
                storedEtag = null,
                storedLastModified = null,
                reason = Reason.MISSING_AFTER_MOVE,
            )
        }
    }

    /** Weak validators cannot satisfy `If-Match`, so they count as "no validator". */
    private fun normalizeEtag(raw: String?): String? =
        raw?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("W/") }
}

/**
 * SYN-11: parsing of a `Depth: 0` PROPFIND answer.
 *
 * A multistatus describes resources by `DAV:href`; nothing guarantees that the one response in a
 * `Depth: 0` body is the URL that was asked about (a proxy, a rewritten base path or a server
 * that answers a parent collection can all produce a different one). Treating such a body as the
 * requested file let its ETag/length stand in for a resource this run never read. The href is
 * therefore matched - after percent-encoding and trailing-slash normalisation - before any
 * property is turned into [RemoteArchiveState.Present].
 */
internal object WebDavPropfind {

    fun parse(xml: String, requestUrl: String): RemoteArchiveState = try {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(StringReader(xml))
        var sawResponse = false
        var sawHref = false
        // Properties are collected per DAV:response, so a server that ignores Depth: 0 and sends
        // several resources cannot lend another file's ETag to the one that was requested.
        var responseHref: String? = null
        var etag: String? = null
        var lastModified: String? = null
        var contentLength: Long? = null
        var isCollection = false
        var matched: RemoteArchiveState? = null
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            val event = parser.eventType
            if (event != XmlPullParser.START_TAG && event != XmlPullParser.END_TAG) continue
            // Namespace processing is left off, so a prefixed name arrives as "D:response";
            // comparing the local part covers both prefixed and unprefixed bodies.
            val tag = parser.name?.substringAfter(':')?.lowercase(Locale.US) ?: continue
            if (event == XmlPullParser.END_TAG) {
                if (tag != "response") continue
                val href = responseHref
                if (href != null && hrefMatchesRequest(requestUrl, href)) {
                    matched = if (isCollection) {
                        RemoteArchiveState.Present(isCollection = true)
                    } else {
                        RemoteArchiveState.Present(
                            etag = etag,
                            lastModified = lastModified,
                            contentLength = contentLength,
                        )
                    }
                }
                continue
            }
            when (tag) {
                "response" -> {
                    sawResponse = true
                    responseHref = null
                    etag = null
                    lastModified = null
                    contentLength = null
                    isCollection = false
                }
                "href" -> if (responseHref == null) {
                    val value = parser.nextText().trim().takeIf { it.isNotEmpty() }
                    responseHref = value
                    sawHref = sawHref || value != null
                }
                "getetag" -> etag = normalizeEtag(parser.nextText())
                "getlastmodified" -> lastModified =
                    parser.nextText().trim().takeIf { it.isNotEmpty() }
                "getcontentlength" -> contentLength = parser.nextText().trim().toLongOrNull()
                "collection" -> isCollection = true
            }
        }
        val found = matched
        when {
            !sawResponse -> RemoteArchiveState.Unknown("PROPFIND response had no DAV:response")
            !sawHref -> RemoteArchiveState.Unknown("PROPFIND response had no DAV:href")
            found == null ->
                RemoteArchiveState.Unknown("PROPFIND response described a different resource than $requestUrl")
            else -> found
        }
    } catch (e: Exception) {
        RemoteArchiveState.Unknown("PROPFIND response could not be parsed")
    }

    /**
     * True when [href] names the resource in [requestUrl].
     *
     * Hrefs may be absolute or root-relative, encode the same path differently, and a collection
     * normally carries a trailing slash the configured file URL does not. Those spellings are
     * normalised through `HttpUrl`; host and port must still agree, and the query is ignored
     * because a signed URL's token is not part of the resource's identity.
     */
    internal fun hrefMatchesRequest(requestUrl: String, href: String): Boolean {
        val request = requestUrl.toHttpUrlOrNull() ?: return false
        val target = request.resolve(href.trim()) ?: return false
        if (!request.host.equals(target.host, ignoreCase = true)) return false
        if (request.port != target.port) return false
        return withoutTrailingEmptySegment(request.pathSegments) ==
            withoutTrailingEmptySegment(target.pathSegments)
    }

    private fun withoutTrailingEmptySegment(segments: List<String>): List<String> =
        if (segments.lastOrNull() == "") segments.dropLast(1) else segments

    private fun normalizeEtag(raw: String?): String? =
        raw?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("W/") }
}
