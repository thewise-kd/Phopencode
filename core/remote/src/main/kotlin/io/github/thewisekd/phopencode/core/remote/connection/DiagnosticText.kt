package io.github.thewisekd.phopencode.core.remote.connection

import java.util.Collections
import java.util.IdentityHashMap

/**
 * The text of diagnostics (P9.25), shared by the device log ([ConnectionEndLog.JavaLogging]) and
 * the folder picker's debug detail, so both show a failure the same way and redact it the same way.
 *
 * [chain] writes a failure's chain: each link as its class and message, with up to [MAX_FRAMES]
 * stack frames, at most [MAX_LINKS] links. [redact] masks credentials by pattern. Whoever shows or
 * logs such text redacts all of it, frames included, and never hands a raw `Throwable` on.
 *
 * What the text may hold: server addresses, user names and paths, from the messages it quotes. That
 * is accepted: diagnostics are on only in a debuggable build, and the text stays on the user's own
 * device, on its screen and in its log, and is never sent anywhere. What it must not hold: a
 * password or a private key. The app's own code never puts a credential into an exception
 * message, and no credential is handed to this code. As defence in depth against a library that
 * quotes one, [redact] turns each of these into [MASK], keeping the key or scheme in front where
 * there is one:
 * - an `Authorization` header's value, to the end of its line;
 * - the credential after `Basic` or `Bearer`;
 * - the `user:password` userinfo of a URL, to the last `@` before the host;
 * - the value of a key that contains `password`, `passwd`, `pwd`, `secret`, `token` or
 *   `passphrase`, written `key=value`, `key: value` or JSON style: a quoted value to its closing
 *   quote, an unquoted one to the next whitespace, whatever punctuation it holds;
 * - a private key block, PEM or OpenSSH, from `-----BEGIN … PRIVATE KEY-----` to its `-----END …-----`
 *   line, or to the end of the text when that is missing;
 * - a base64 run of [MIN_BASE64_RUN] or more characters with digits and both letter cases, unless
 *   slashes split it into short parts, as in a path.
 * Patterns are a net, not a guarantee; they may also mask a harmless value that looks like one.
 */
object DiagnosticText {
    const val MASK = "***"
    const val MAX_FRAMES = 15
    const val MAX_LINKS = 10

    /** At least this long, a base64 run is taken for a credential. */
    const val MIN_BASE64_RUN = 40

    /** A slash-separated part at least this long marks a base64 run rather than a path. */
    private const val MIN_BASE64_PART = 20

    private const val INDENT = "    "

    private val PRIVATE_KEY = Regex(
        """-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----.*?(?:-----END [A-Z0-9 ]*-----|\z)""",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val AUTHORIZATION = Regex("""(?i)(\b(?:proxy-)?authorization\b["']?\s*[:=]\s*)[^\r\n]*""")
    private val SCHEME_CREDENTIAL = Regex("""(?i)(\b(?:basic|bearer)\s+)[A-Za-z0-9._~+/=-]+""")
    /** The password runs to the last `@` before the host: it may hold `@` itself. */
    private val URL_USERINFO = Regex("""(\b[A-Za-z][A-Za-z0-9+.-]*://)[^\s/@:]*:[^\s/]*@""")

    /**
     * A quoted value to its closing quote, past escaped quotes; an unterminated one to the end of
     * its line; an unquoted one to the next whitespace, so no punctuation ends the mask early.
     */
    private val KEY_VALUE = Regex(
        """(?i)(["']?[\w-]*(?:password|passwd|pwd|secret|token|passphrase)[\w-]*["']?\s*[:=]\s*)""" +
            """("(?:[^"\\\r\n]|\\.)*"|'(?:[^'\\\r\n]|\\.)*'|["'][^\r\n]*|\S+)""",
    )
    private val BASE64_RUN = Regex("""[A-Za-z0-9+/_-]{$MIN_BASE64_RUN,}={0,2}""")

    /** [failure]'s chain, unredacted, each line ending in a newline; a cause cycle is followed once. */
    fun chain(failure: Throwable): String = buildString {
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        var link: Throwable? = failure
        var shown = 0
        while (link != null && seen.add(link)) {
            if (shown == MAX_LINKS) {
                append("... more causes\n")
                break
            }
            if (shown > 0) append("Caused by: ")
            append(link.javaClass.name)
            link.message?.let { append(": ").append(it) }
            append('\n')
            val frames = link.stackTrace
            frames.take(MAX_FRAMES).forEach { append(INDENT).append("at ").append(it).append('\n') }
            if (frames.size > MAX_FRAMES) append(INDENT).append("... ${frames.size - MAX_FRAMES} more\n")
            shown++
            link = link.cause
        }
    }

    /**
     * [text] with every credential pattern masked. Private keys first, since their body would
     * otherwise be cut up by the narrower patterns; base64 runs last, for what the others left.
     */
    fun redact(text: String): String {
        var masked = PRIVATE_KEY.replace(text, MASK)
        masked = AUTHORIZATION.replace(masked) { it.groupValues[1] + MASK }
        masked = SCHEME_CREDENTIAL.replace(masked) { it.groupValues[1] + MASK }
        masked = URL_USERINFO.replace(masked) { it.groupValues[1] + MASK + "@" }
        masked = KEY_VALUE.replace(masked) { it.groupValues[1] + MASK }
        return BASE64_RUN.replace(masked) { if (looksLikeBase64(it.value)) MASK else it.value }
    }

    /** Digits and both letter cases, and not a path: no slash, or a part between slashes that is long. */
    private fun looksLikeBase64(run: String): Boolean =
        run.any(Char::isDigit) && run.any(Char::isUpperCase) && run.any(Char::isLowerCase) &&
            run.split('/').any { it.length >= MIN_BASE64_PART }
}
