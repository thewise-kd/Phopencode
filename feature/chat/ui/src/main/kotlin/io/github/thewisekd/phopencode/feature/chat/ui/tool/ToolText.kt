package io.github.thewisekd.phopencode.feature.chat.ui.tool

/** The most lines of an input or output the card draws. */
internal const val TOOL_MAX_LINES = 40

/** The most characters of an input or output the card draws, for output with very long lines. */
internal const val TOOL_MAX_CHARS = 4_000

/**
 * What the card draws of a text: [text], then a note for what was cut.
 *
 * @property hiddenLines Whole lines after [text]; a line cut in the middle counts as shown.
 * @property hiddenChars Characters (UTF-16 units) after [text].
 */
internal data class ShortenedText(val text: String, val hiddenLines: Int, val hiddenChars: Int) {
    val truncated: Boolean get() = hiddenChars > 0
}

/**
 * The start of [text], at most [maxLines] lines and [maxChars] characters, so a card stays a
 * bounded height and never has to scroll vertically. Windows line ends become `\n`, and a trailing
 * line end is not a line of its own. A cut never splits a surrogate pair.
 */
internal fun shortenToolText(
    text: String,
    maxLines: Int = TOOL_MAX_LINES,
    maxChars: Int = TOOL_MAX_CHARS,
): ShortenedText {
    val body = text.replace("\r\n", "\n").trimEnd('\n')
    var cut = body.length
    var newlines = 0
    for (i in body.indices) {
        if (body[i] == '\n') {
            newlines++
            if (newlines == maxLines) {
                cut = i
                break
            }
        }
    }
    cut = minOf(cut, maxChars)
    if (cut in 1 until body.length && body[cut - 1].isHighSurrogate() && body[cut].isLowSurrogate()) cut--
    if (cut >= body.length) return ShortenedText(body, hiddenLines = 0, hiddenChars = 0)
    // A cut right after line ends shows nothing of the line that follows: drop those line ends so
    // only lines with at least one character shown count as shown.
    val shown = body.substring(0, cut).trimEnd('\n')
    val totalLines = body.count { it == '\n' } + 1
    val shownLines = if (shown.isEmpty()) 0 else shown.count { it == '\n' } + 1
    return ShortenedText(shown, hiddenLines = totalLines - shownLines, hiddenChars = body.length - cut)
}
