package io.github.thewisekd.phopencode.feature.preview.logic.console

import io.github.thewisekd.phopencode.feature.preview.logic.contract.ConsoleEntry
import io.github.thewisekd.phopencode.feature.preview.logic.contract.ConsoleKind
import io.github.thewisekd.phopencode.feature.preview.logic.contract.ConsoleLevel
import io.github.thewisekd.phopencode.feature.preview.logic.contract.ConsoleState
import io.github.thewisekd.phopencode.feature.preview.logic.transport.BridgeSecret

/**
 * Builds the console's entries from what the WebView reports (P13.12). Every text and source loses
 * any [BridgeSecret.PARAM] pair, so the bridge's secret never reaches the console (P13.14), and is
 * then cut at [ConsoleState.MAX_TEXT_LENGTH] characters; a line below 1 and an empty source are left
 * out. Load errors, HTTP errors and refused requests are [ConsoleLevel.Error], whether they hit the
 * main frame or a sub-resource. Nothing here is logged.
 */
object ConsoleEntries {
    /** The page's own `console.*` call. */
    fun message(level: ConsoleLevel, text: String, source: String?, line: Int?): ConsoleEntry =
        bounded(ConsoleEntry(ConsoleKind.Console, level, text, source, line))

    /**
     * The main frame or a sub-resource at [url] failed to load; [description] is the WebView's
     * reason (for example `net::ERR_FAILED`). Without one, the text is the address.
     */
    fun loadError(url: String, description: String?): ConsoleEntry {
        val text = description?.takeIf { it.isNotBlank() } ?: url
        return bounded(ConsoleEntry(ConsoleKind.LoadError, ConsoleLevel.Error, text, url, line = null))
    }

    /**
     * The main frame or a sub-resource at [url] answered with HTTP [status]; the text is
     * `HTTP <status>` and the [reason] phrase, if any.
     *
     * @throws IllegalArgumentException if [status] is outside 100..599.
     */
    fun httpError(url: String, status: Int, reason: String?): ConsoleEntry {
        require(status in 100..599) { "Not an HTTP status" }
        val phrase = reason?.trim().orEmpty()
        val text = if (phrase.isEmpty()) "HTTP $status" else "HTTP $status $phrase"
        return bounded(ConsoleEntry(ConsoleKind.HttpError, ConsoleLevel.Error, text, url, line = null))
    }

    /** The page asked for [url], off the preview origin, and it was refused; the text is the address. */
    fun refused(url: String): ConsoleEntry =
        bounded(ConsoleEntry(ConsoleKind.RefusedRequest, ConsoleLevel.Error, url, source = null, line = null))

    /**
     * The level for the name of a WebView `ConsoleMessage.MessageLevel` (`TIP`, `LOG`, `WARNING`,
     * `ERROR`, `DEBUG`); a name it does not know is [ConsoleLevel.Log].
     */
    fun levelOf(webViewLevel: String): ConsoleLevel =
        when (webViewLevel) {
            "DEBUG" -> ConsoleLevel.Debug
            "TIP" -> ConsoleLevel.Info
            "WARNING" -> ConsoleLevel.Warning
            "ERROR" -> ConsoleLevel.Error
            else -> ConsoleLevel.Log
        }

    /**
     * [entry] with the bridge's secret taken out of its text and source, both then cut, an empty
     * source and a line below 1 left out.
     */
    internal fun bounded(entry: ConsoleEntry): ConsoleEntry {
        val text = cut(withoutSecret(entry.text))
        val source = entry.source?.takeIf { it.isNotEmpty() }?.let { cut(withoutSecret(it)) }
        val line = entry.line?.takeIf { it >= 1 }
        return if (text === entry.text && source === entry.source && line == entry.line) {
            entry
        } else {
            entry.copy(text = text, source = source, line = line)
        }
    }

    /**
     * What a secret's value is made of: [BridgeSecret] is URL-safe Base64 without padding. Only
     * these go, so a quote, comma or bracket after the address, as in logged JSON, stays.
     */
    private const val SECRET_VALUE = "[A-Za-z0-9_-]*"

    /** A `?` or `&` query pair holding the bridge's secret, and the `&` after it, if any. */
    private val SECRET_PAIR = Regex("""([?&])${BridgeSecret.PARAM}=$SECRET_VALUE(&?)""")

    /** The secret's parameter anywhere else, with its value. */
    private val SECRET_ANYWHERE = Regex("""${BridgeSecret.PARAM}=$SECRET_VALUE""")

    /**
     * [text] without any [BridgeSecret.PARAM] pair: the bridge's first URL carries the secret, and
     * the WebView reports that URL in errors, sources and the page's own `location`. Only the pair
     * goes; the rest of the query, and the `?` while something follows it, stay. The same instance
     * when there is nothing to take out.
     */
    private fun withoutSecret(text: String): String {
        if (!text.contains(BridgeSecret.PARAM)) return text
        // Pairs side by side share a separator, so one pass may leave the next: repeat until none.
        var rest = text
        do {
            val before = rest
            rest = SECRET_PAIR.replace(rest) { match ->
                val (separator, next) = match.destructured
                if (next.isEmpty()) "" else separator
            }
        } while (rest != before)
        return SECRET_ANYWHERE.replace(rest, "")
    }

    /** [text] cut at [ConsoleState.MAX_TEXT_LENGTH] characters, never between a surrogate pair. */
    private fun cut(text: String): String {
        val max = ConsoleState.MAX_TEXT_LENGTH
        if (text.length <= max) return text
        val end = if (text[max - 1].isHighSurrogate() && text[max].isLowSurrogate()) max - 1 else max
        return text.substring(0, end)
    }
}
