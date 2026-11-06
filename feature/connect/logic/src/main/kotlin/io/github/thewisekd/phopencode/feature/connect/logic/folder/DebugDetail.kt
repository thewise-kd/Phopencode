package io.github.thewisekd.phopencode.feature.connect.logic.folder

import io.github.thewisekd.phopencode.core.remote.connection.DiagnosticText

/**
 * The detailed failure text of the folder picker, built only while diagnostics are on, that is in a
 * debuggable build (P9.25). Unlike [FailureDetail] and [ConnectionDetail], which name classes only,
 * this one reads messages and stack frames: under `Failure:`, the failure's chain, each link as its
 * class and message with up to [MAX_FRAMES] frames, at most [MAX_LINKS] links; then, under
 * `Connection last ended:`, the connection manager's trace of why the connection last ended, in the
 * same form. At most [MAX_LENGTH] characters; a longer text is cut and ends with [TRUNCATED].
 *
 * The chain and the redaction are [DiagnosticText]'s, shared with the device log: the whole text,
 * messages, `toString` output and frames alike, is redacted by pattern before it is cut, so no part
 * of a match survives at the edge. It may hold server addresses, user names and paths, which stay
 * on the device; never a password or a key, which no code here puts into a message and which the
 * patterns mask as defence in depth (see [DiagnosticText]).
 */
internal object DebugDetail {
    const val MAX_LENGTH = 8 * 1024
    const val MAX_FRAMES = DiagnosticText.MAX_FRAMES
    const val MAX_LINKS = DiagnosticText.MAX_LINKS
    const val MASK = DiagnosticText.MASK
    const val TRUNCATED = "\n... (truncated)"

    private const val FAILURE_HEADING = "Failure:\n"
    private const val LAST_END_HEADING = "Connection last ended:\n"

    /**
     * The text for [failure], the failure the picker shows, and [lastEnd], the connection's last
     * end, redacted (see the class KDoc); null when both are null.
     */
    fun of(failure: Throwable?, lastEnd: Throwable?): String? {
        if (failure == null && lastEnd == null) return null
        val text = buildString {
            if (failure != null) {
                append(FAILURE_HEADING)
                append(DiagnosticText.chain(failure))
            }
            if (lastEnd != null) {
                if (isNotEmpty()) append('\n')
                append(LAST_END_HEADING)
                append(DiagnosticText.chain(lastEnd))
            }
        }.trimEnd()
        return cap(DiagnosticText.redact(text))
    }

    private fun cap(text: String): String =
        if (text.length <= MAX_LENGTH) text else text.take(MAX_LENGTH - TRUNCATED.length) + TRUNCATED
}
