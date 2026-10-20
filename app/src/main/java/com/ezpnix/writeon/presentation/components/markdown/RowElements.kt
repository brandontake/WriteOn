package com.ezpnix.writeon.presentation.components.markdown

import com.ezpnix.writeon.core.attachment.AttachmentSyntax

sealed interface MarkdownElement {
    fun render(builder: StringBuilder)
}

data class Heading(val level: Int, val text: String) : MarkdownElement {
    override fun render(builder: StringBuilder) {
        builder.append("#".repeat(level)).append(" $text\n\n")
    }
}

data class CheckboxItem(val text: String, val checked: Boolean, val lineNumber: Int) : MarkdownElement {
    override fun render(builder: StringBuilder) {
        builder.append("[${if (checked) "X" else " "}] $text\n")
    }
}

data class Quote(val level: Int, val text: String) : MarkdownElement {
    override fun render(builder: StringBuilder) {
        builder.append("> ${text}\n")
    }
}

data class ListItem(val text: String) : MarkdownElement {
    override fun render(builder: StringBuilder) {
        builder.append("- ${text}\n")
    }
}

data class CodeBlock(val code: String, val isEnded: Boolean = false, val firstLine : String) : MarkdownElement {
    override fun render(builder: StringBuilder) {
        builder.append("```")
        isEnded.let {
            builder.append(it)
        }
        builder.append("\n$code\n```\n")
    }
}

data class NormalText(val text: String) : MarkdownElement {
    override fun render(builder: StringBuilder) {
        builder.append("$text\n\n")
    }
}

/**
 * A picture. [photoUri] is either `attachment://<id>` (2.0) or a legacy absolute path (1.x); the
 * renderers ask [AttachmentRegistry] to turn it into a file path.
 */
data class ImageInsertion(val photoUri: String) : MarkdownElement {
    override fun render(builder: StringBuilder) {
        val id = AttachmentSyntax.idOf(photoUri)
        if (id != null) {
            builder.append("![${""}](${AttachmentSyntax.SCHEME}$id)\n\n")
        } else {
            builder.append("!($photoUri)\n\n")
        }
    }
}

/** A recording; the same target rules as [ImageInsertion] apply. */
data class AudioInsertion(val audioUri: String) : MarkdownElement {
    override fun render(builder: StringBuilder) {
        val id = AttachmentSyntax.idOf(audioUri)
        if (id != null) {
            val caption = AttachmentRegistry.attachment(id)?.caption.orEmpty()
            builder.append("${AttachmentSyntax.audio(id, caption)}\n\n")
        } else {
            builder.append("?audio($audioUri)\n\n")
        }
    }
}


data object HorizontalRule : MarkdownElement {
    override fun render(builder: StringBuilder) {
        builder.append("---\n\n")
    }
}