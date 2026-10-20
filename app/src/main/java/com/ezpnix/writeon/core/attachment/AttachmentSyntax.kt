package com.ezpnix.writeon.core.attachment

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

    /** Eight lowercase hex characters: short enough to keep the raw body readable. */
    fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(8)

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
     * Brackets and newlines would break the `[caption](...)` shape, so a caption is flattened
     * rather than escaped: this text is a label, not markup.
     */
    fun sanitize(caption: String): String = caption
        .replace('[', '(')
        .replace(']', ')')
        .replace('\n', ' ')
        .trim()
}
