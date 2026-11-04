package io.github.thewisekd.phopencode.core.markdown

import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

/**
 * Which link targets may leave the app. Markdown comes from the server and the model, so a
 * target is untrusted: only an absolute `http` or `https` URL with a host may open.
 */
internal object LinkPolicy {
    private val ALLOWED_SCHEMES = setOf("http", "https")

    /**
     * The exact text to open for [raw], or null when it must not open. Non-ASCII characters
     * come back percent-encoded, so the text the dialog shows is the text that opens and no
     * invisible or direction-changing character can hide in it.
     */
    fun openableTarget(raw: String): String? {
        val trimmed = raw.trim()
        val uri = try {
            URI(trimmed)
        } catch (_: URISyntaxException) {
            return null
        }
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        return when {
            scheme !in ALLOWED_SCHEMES -> null
            uri.isOpaque || uri.host.isNullOrEmpty() -> null
            else -> scheme + uri.toASCIIString().substring(uri.scheme.length)
        }
    }

    /** [raw] for display: control and formatting characters become `\uXXXX`, so all of it shows. */
    fun visibleForm(raw: String): String = buildString {
        for (char in raw) {
            if (char.isVisible()) append(char) else append("\\u%04X".format(Locale.ROOT, char.code))
        }
    }

    private fun Char.isVisible(): Boolean = when (Character.getType(this).toByte()) {
        Character.CONTROL, Character.FORMAT, Character.SURROGATE, Character.PRIVATE_USE, Character.UNASSIGNED,
        Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR,
        -> false
        else -> true
    }
}
