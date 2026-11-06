package io.github.thewisekd.phopencode.core.git.parse

/**
 * Git output that does not match the format a parser expects: malformed, truncated, or produced
 * with other arguments than the parser documents.
 *
 * The message names what was expected and [offset], the byte position in the input where parsing
 * stopped. It never contains bytes of the input, so it is safe to log.
 */
public class GitParseException(
    /** What went wrong, without any input content. */
    public val reason: String,
    /** The byte offset in the parsed input where the problem was found. */
    public val offset: Int,
) : Exception("$reason at byte $offset")
