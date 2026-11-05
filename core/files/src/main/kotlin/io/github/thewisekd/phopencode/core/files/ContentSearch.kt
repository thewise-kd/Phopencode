package io.github.thewisekd.phopencode.core.files

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * What a content search looks for. It prints no part of [text].
 *
 * @property text One line: no NUL and no line break. With [regex], `rg` reads it as a Rust regex
 *   and `grep` as a POSIX extended one; the two differ in details.
 * @property maxResults At most this many matches in all.
 * @property maxMatchesPerFile At most this many matches in one file.
 * @property maxOutputBytes The search's output is cut at this many bytes on the server.
 * @property maxLineChars Longer lines are shortened to this many characters.
 * @property timeout For the whole search.
 */
data class ContentQuery(
    val text: String,
    val regex: Boolean = false,
    val ignoreCase: Boolean = false,
    val maxResults: Int = DEFAULT_MAX_RESULTS,
    val maxMatchesPerFile: Int = DEFAULT_MAX_MATCHES_PER_FILE,
    val maxOutputBytes: Long = DEFAULT_MAX_OUTPUT_BYTES,
    val maxLineChars: Int = DEFAULT_MAX_LINE_CHARS,
    val timeout: Duration = DEFAULT_TIMEOUT,
) {
    init {
        require(isValidText(text)) { "The search text is empty or not one line" }
        require(maxResults in 1..MAX_RESULTS) { "The result limit is out of range" }
        require(maxMatchesPerFile >= 1) { "The per-file limit must be positive" }
        require(maxOutputBytes in 1..MAX_OUTPUT_BYTES) { "The output limit is out of range" }
        require(maxLineChars >= 1) { "The line limit must be positive" }
        require(timeout.isPositive() && timeout.isFinite()) { "The timeout must be positive" }
    }

    override fun toString(): String =
        "ContentQuery(***, regex=$regex, ignoreCase=$ignoreCase, maxResults=$maxResults)"

    companion object {
        const val DEFAULT_MAX_RESULTS: Int = 200
        const val MAX_RESULTS: Int = 5000
        const val DEFAULT_MAX_MATCHES_PER_FILE: Int = 50
        const val DEFAULT_MAX_OUTPUT_BYTES: Long = 1024L * 1024
        const val MAX_OUTPUT_BYTES: Long = 16L * 1024 * 1024
        const val DEFAULT_MAX_LINE_CHARS: Int = 500
        val DEFAULT_TIMEOUT: Duration = 30.seconds

        /** Whether [text] can be searched: not empty, one line, no NUL, no half surrogate pair. */
        fun isValidText(text: String): Boolean =
            text.isNotEmpty() &&
                text.none { it == '\u0000' || it == '\n' || it == '\r' } &&
                text.indices.all { index ->
                    val char = text[index]
                    when {
                        char.isHighSurrogate() -> text.getOrNull(index + 1)?.isLowSurrogate() == true
                        char.isLowSurrogate() -> text.getOrNull(index - 1)?.isHighSurrogate() == true
                        else -> true
                    }
                }
    }
}

/**
 * One matching line. It prints neither the path nor the text.
 *
 * @property path Absolute, below the searched folder. A name that is not valid UTF-8 shows its bad
 *   bytes as U+FFFD, and then cannot be opened by this path.
 * @property line 1-based.
 * @property text The line without its line break, shortened to [ContentQuery.maxLineChars].
 */
data class ContentMatch(val path: String, val line: Int, val text: String) {
    override fun toString(): String = "ContentMatch(line=$line)"
}

enum class SearchTool {
    Ripgrep,
    Grep,
}

/**
 * @property truncated Whether any limit cut the results: the total, the per-file limit (reached in
 *   at least one file), the line length (a line was shortened) or the output bytes. There may be
 *   more matches, or longer lines, than shown.
 * @property incomplete Whether the tool failed after some matches: an error such as a folder it
 *   could not read, a crash, or death by a signal other than the expected SIGPIPE once the output
 *   cap was full. Without any match such a failure is [FileError.SearchFailed] instead.
 */
class ContentSearchResult(
    val matches: List<ContentMatch>,
    val tool: SearchTool,
    val truncated: Boolean,
    val incomplete: Boolean,
) {
    override fun toString(): String =
        "ContentSearchResult(${matches.size} matches, tool=$tool, truncated=$truncated, incomplete=$incomplete)"
}
