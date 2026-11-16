package io.github.thewisekd.phopencode.feature.connect.logic.progress

import io.github.thewisekd.phopencode.core.remote.connection.DiagnosticText
import java.util.Collections
import java.util.IdentityHashMap

/**
 * The failure detail of the connection screen, built only while diagnostics are on, that is in a
 * debuggable build (P9.38). It first names the root cause, the last link of the failure's chain,
 * as `Root cause: <class>: <message>`; then, after a blank line, the whole chain in the form of
 * [DiagnosticText.chain]: each link as its class and message, `Caused by: ` before every link but
 * the first, with at most [MAX_FRAMES] frames per link and at most [DiagnosticText.MAX_LINKS]
 * links. At most [MAX_LENGTH] characters; a longer text is cut and ends with [TRUNCATED].
 *
 * Unlike the folder picker's detail and the device log, this text must not show where or as whom
 * the app connects. It is built here from the throwables themselves, so that what came from a
 * message and what came from a real [StackTraceElement] are never confused:
 * - every message, with its class name, goes through [redact] whole, with no exemption: a line in
 *   a message that looks like a frame is masked like any other text;
 * - of a frame, the class and method part goes through [redact] too; its source location is
 *   written from the element's own fields and left readable only when it is `(Native Method)`,
 *   `(Unknown Source)`, or a plain source file name ([SOURCE_FILE]) with its line that holds
 *   neither the host nor the user name anywhere, whatever the case; it is masked otherwise.
 * Nothing else reaches the text: `Caused by: `, `at`, `... N more` and `... more causes` are fixed
 * text and counts, and cutting the text only removes from its end.
 * The assembled text then goes through [DiagnosticText.redact] once more as a whole, and is cut.
 * Patterns are a net, not a guarantee, and they mask some harmless text too; no code hands a
 * credential to this text.
 */
internal object ProgressDebugDetail {
    const val MAX_FRAMES = 5
    const val MAX_LENGTH = 8 * 1024
    const val TRUNCATED = "\n... (truncated)"

    private const val MASK = DiagnosticText.MASK
    private const val ROOT_HEADING = "Root cause: "
    private const val INDENT = "    "

    /** A root deeper than this is not looked for; the chain stops far earlier anyway. */
    private const val MAX_DEPTH = 64

    /** What may stand right next to a name and still leave it a whole name: not part of a word. */
    private const val NAME_CHAR = "-A-Za-z0-9_"

    private val IPV6_CANDIDATE = Regex(
        """(?<![0-9A-Za-z_:.])(?:[0-9A-Fa-f]{0,4}:){2,7}(?:(?:\d{1,3}\.){3}\d{1,3}|[0-9A-Fa-f]{0,4})""" +
            """(?:%[0-9A-Za-z_.-]+)?(?![0-9A-Za-z_:])""",
    )
    private val IPV4 = Regex("""(?<![0-9.])(?:\d{1,3}\.){3}\d{1,3}(?![0-9])""")

    /**
     * A name and a port: a bracketed address, or a name holding a letter or a mask left by an
     * earlier step, so that a time such as `12:34` stays. Possessive, so a long word is read once.
     */
    private val HOST_PORT = Regex("""\[[^\]\s]*]:\d{1,5}(?![0-9])|(?<![$NAME_CHAR.*])([$NAME_CHAR.*]++):\d{1,5}(?![0-9])""")

    /** A frame's file name that is shown as it is: a plain source file name, no path, no port. */
    private val SOURCE_FILE = Regex("""[A-Za-z0-9_$-]+\.(?:kt|java|kts|scala|groovy)""")

    /** The redacted text for [failure], whose connect named [host] and [user]; null without a failure. */
    fun of(failure: Throwable?, host: String?, user: String?): String? {
        if (failure == null) return null
        val text = buildString {
            append(ROOT_HEADING).append(redact(headline(rootOf(failure)), host, user))
            append("\n\n")
            appendChain(failure, host, user)
        }.trimEnd()
        return cap(DiagnosticText.redact(text))
    }

    /**
     * Message text with every mask applied, nothing exempt: the credential patterns of
     * [DiagnosticText.redact]; then [host] and [user] wherever they stand as whole names, whatever
     * their case; then every IPv6 and IPv4 literal; then every `host:port`. A blank [host] or
     * [user] masks nothing.
     */
    fun redact(text: String, host: String?, user: String?): String {
        var masked = DiagnosticText.redact(text)
        listOfNotNull(host, user)
            .map(String::trim)
            .filter(String::isNotEmpty)
            // The longer first, so that a user name inside the host does not split the host.
            .sortedByDescending(String::length)
            .forEach { name ->
                val whole = Regex("(?<![$NAME_CHAR])${Regex.escape(name)}(?![$NAME_CHAR])", RegexOption.IGNORE_CASE)
                masked = whole.replace(masked, MASK)
            }
        masked = IPV6_CANDIDATE.replace(masked) { if (isIpv6(it.value)) MASK else it.value }
        masked = IPV4.replace(masked, MASK)
        return HOST_PORT.replace(masked) { if (isHostPort(it)) MASK else it.value }
    }

    /** The chain as [DiagnosticText.chain] writes it, each part masked by where it came from. */
    private fun StringBuilder.appendChain(failure: Throwable, host: String?, user: String?) {
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        var link: Throwable? = failure
        var shown = 0
        while (link != null && seen.add(link)) {
            if (shown == DiagnosticText.MAX_LINKS) {
                append("... more causes\n")
                break
            }
            if (shown > 0) append("Caused by: ")
            append(redact(headline(link), host, user)).append('\n')
            val frames = link.stackTrace
            frames.take(MAX_FRAMES).forEach { append(INDENT).append("at ").append(frame(it, host, user)).append('\n') }
            if (frames.size > MAX_FRAMES) append(INDENT).append("... ${frames.size - MAX_FRAMES} more\n")
            shown++
            link = link.cause
        }
    }

    private fun headline(link: Throwable): String =
        link.javaClass.name + (link.message?.let { ": $it" } ?: "")

    /**
     * [element] as [StackTraceElement.toString] writes it: its class and method part masked as
     * text, its source location rebuilt from its fields and kept only when it is a known form.
     */
    private fun frame(element: StackTraceElement, host: String?, user: String?): String {
        val file = element.fileName
        val location = when {
            element.isNativeMethod -> "Native Method"
            file == null -> "Unknown Source"
            element.lineNumber >= 0 -> "$file:${element.lineNumber}"
            else -> file
        }
        val written = element.toString()
        val suffix = "($location)"
        val prefix = if (written.endsWith(suffix)) {
            written.removeSuffix(suffix)
        } else {
            "${element.className}.${element.methodName}"
        }
        val trusted = element.isNativeMethod || file == null ||
            (SOURCE_FILE.matches(file) && !namesIdentity(file, host, user))
        val shownLocation = if (trusted) location else MASK
        return redact(prefix, host, user) + "(" + shownLocation + ")"
    }

    /**
     * Whether [file] holds [host] or [user] anywhere, whatever the case. Stricter than the
     * whole-name mask of [redact] on purpose: a location is shown only when it cannot hold them.
     */
    private fun namesIdentity(file: String, host: String?, user: String?): Boolean =
        listOfNotNull(host, user)
            .map(String::trim)
            .filter(String::isNotEmpty)
            .any { file.contains(it, ignoreCase = true) }

    /**
     * `::` itself, the unspecified address; or two colons or more, a hex digit, and either `::` or
     * all eight groups (six before an IPv4 tail).
     */
    private fun isIpv6(candidate: String): Boolean {
        val address = candidate.substringBefore('%')
        val colons = address.count { it == ':' }
        val full = if ('.' in address) colons >= 6 else colons >= 7
        // `::` alone is the unspecified address; next to a word, as in `Foo::bar`, it never gets here.
        return address == "::" || (colons >= 2 && address.any { it.isLetterOrDigit() } && ("::" in address || full))
    }

    private fun isHostPort(match: MatchResult): Boolean {
        val name = match.groups[1]?.value ?: return true
        return name.any { it.isLetter() || it == '*' }
    }

    /** The last link of [failure]'s chain; in a cycle, the last link before it comes round. */
    private fun rootOf(failure: Throwable): Throwable {
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        seen.add(failure)
        var link = failure
        while (true) {
            val next = link.cause
            if (next == null || seen.size >= MAX_DEPTH || !seen.add(next)) return link
            link = next
        }
    }

    private fun cap(text: String): String =
        if (text.length <= MAX_LENGTH) text else text.take(MAX_LENGTH - TRUNCATED.length) + TRUNCATED
}
