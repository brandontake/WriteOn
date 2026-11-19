package com.ezpnix.writeon.core.attachment

import com.ezpnix.writeon.domain.model.Attachment
import java.util.UUID

/**
 * The one place that knows how a 2.0 note body points at a file.
 *
 * A body stores `![](attachment://<id>)` for an image and `[caption](attachment://<id>)` for a
 * recording. The id is opaque; the path lives in the `attachments` table. Both are plain Markdown,
 * so a note rendered by any other Markdown tool shows a broken image link instead of leaking a
 * private `!(/data/...)` token.
 */
object AttachmentSyntax {

    const val SCHEME = "attachment://"

    /** An `attachment://<id>` reference anywhere in a body, used to find orphaned rows on save. */
    private val ID_IN_BODY = Regex("""attachment://([A-Za-z0-9_-]+)""")

    /** A whole line that is nothing but an image attachment. */
    val IMAGE_LINE = Regex("""^!\[[^\]]*]\(attachment://([A-Za-z0-9_-]+)\)$""")

    /** A whole line that is nothing but a recording attachment. */
    val AUDIO_LINE = Regex("""^\[([^\]]*)]\(attachment://([A-Za-z0-9_-]+)\)$""")

    /**
     * A random 128-bit id as 32 lowercase hex characters (D13).
     *
     * The old id was eight hex characters - 32 bits - which the birthday paradox turns into a
     * ~25% collision chance once a heavy user reaches 50k attachments. `attachments.attachment-id`
     * is the primary key with `OnConflictStrategy.REPLACE`, so a collision does not fail loudly:
     * the later row silently overwrites the earlier one, both note bodies then point at the same
     * id, and deleting one "attachment" deletes the other's file. 128 bits pushes that probability
     * down to ~10^-21 at the same library size.
     *
     * The format stays a subset of what every consumer already accepts: `[A-Za-z0-9_-]` in
     * [ID_IN_BODY], [IMAGE_LINE] and [AUDIO_LINE], a plain filename segment for
     * `attachments/<id>.<ext>`, and TEXT in SQLite - so ids minted before this change (8 hex
     * chars) still parse and render exactly as they did, and no migration is needed.
     */
    fun newId(): String = UUID.randomUUID().toString().replace("-", "")

    fun isAttachment(target: String): Boolean = target.startsWith(SCHEME)

    fun idOf(target: String): String? =
        if (!isAttachment(target)) {
            null
        } else {
            target.removePrefix(SCHEME).takeIf { it.isNotBlank() }
        }

    fun image(target: String): String = "![]($SCHEME$target)"

    fun audio(id: String, caption: String): String = "[${sanitize(caption)}]($SCHEME$id)"

    /** Every attachment id the body still references; used to prune stale rows. */
    fun idsIn(body: String): Set<String> =
        ID_IN_BODY.findAll(body).map { it.groupValues[1] }.toSet()

    /**
     * The body with every reference to [id] removed.
     *
     * A line that is nothing but the attachment (the shape every insert produces) disappears whole,
     * so deleting from the library or a rendering leaves no blank half-line behind. A reference
     * that was pasted into the middle of a sentence loses just the Markdown token.
     */
    fun removeId(body: String, id: String): String {
        if (id !in idsIn(body)) return body
        val kept = body.split('\n').filterNot { line ->
            val trimmed = line.trim()
            IMAGE_LINE.matchEntire(trimmed)?.groupValues?.getOrNull(1) == id ||
                AUDIO_LINE.matchEntire(trimmed)?.groupValues?.getOrNull(2) == id
        }
        return kept.joinToString("\n")
            .replace(Regex("""!\[[^\]]*]\($SCHEME${Regex.escape(id)}\)"""), "")
            .replace(Regex("""\[[^\]]*]\($SCHEME${Regex.escape(id)}\)"""), "")
    }

    /** The body line that points at an attachment again; used when restoring from the trash. */
    fun lineFor(id: String, type: String, caption: String): String =
        if (type == Attachment.TYPE_AUDIO) audio(id, caption) else image(id)

    /**
     * Brackets and newlines would break the `[caption](...)` shape, so a caption is flattened
     * rather than escaped: this text is a label, not markup.
     */
    fun sanitize(caption: String): String = caption
        .replace('[', '(')
        .replace(']', ')')
        .replace('\n', ' ')
        .trim()
}
