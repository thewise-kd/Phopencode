package io.github.thewisekd.phopencode.feature.preview.logic.transport

import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewBridge
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * One bridge's per-session secret: [BYTES] bytes from a [SecureRandom], URL-safe Base64 without
 * padding. The WebView presents it once in the entry URL's [PARAM] and from then on in the
 * [cookieName] cookie. Every comparison takes the same time whatever the candidate holds, and the
 * secret never prints.
 */
internal class BridgeSecret private constructor(private val value: ByteArray, val cookieName: String) {
    /** The secret as it goes into the entry URL and the cookie. */
    val encoded: String
        get() = String(value, Charsets.US_ASCII)

    /** In constant time for a candidate of the secret's length, which is public. */
    fun matches(candidate: String): Boolean = MessageDigest.isEqual(value, candidate.toByteArray(Charsets.UTF_8))

    /**
     * Whether [request] may pass: some cookie named [cookieName] or some [PARAM] in its query
     * holds the secret. Every candidate is compared, whether or not an earlier one matched.
     */
    fun check(request: RequestHead): Access {
        val (path, query) = splitTarget(request.target)
        val pairs = query?.split('&').orEmpty()
        val inUrl = pairs.filter { it.substringBefore('=') == PARAM }.map { it.substringAfter('=', "") }
        val inCookies = cookiePairs(request.headers).filter { it.first == cookieName }.map { it.second.orEmpty() }
        var granted = false
        (inUrl + inCookies).forEach { granted = matches(it) or granted }
        if (!granted) return Access.Denied
        if (inUrl.isEmpty()) return Access.Granted(withoutSecret = null)
        val rest = pairs.filter { it.substringBefore('=') != PARAM }
        return Access.Granted(withoutSecret = if (rest.isEmpty()) path else "$path?${rest.joinToString("&")}")
    }

    /**
     * The `Cookie` value to forward, or null when none is left. Cookies are not scoped by port, so
     * the WebView sends this bridge the cookies of every other bridge on `127.0.0.1` too: every
     * cookie named with [COOKIE_PREFIX] is dropped, not only this bridge's.
     */
    fun forwardedCookies(headers: List<Header>): String? =
        cookiePairs(headers).filter { !isBridgeCookie(it.first) }
            .joinToString("; ") { (name, value) -> if (value == null) name else "$name=$value" }
            .ifEmpty { null }

    /** Whether a `Set-Cookie` value from the dev server names a bridge's cookie, this one's or another's. */
    fun setsBridgeCookie(setCookie: String): Boolean = isBridgeCookie(setCookie.substringBefore(';').substringBefore('=').trim())

    /** The `Set-Cookie` value that hands the secret to the WebView. */
    fun setCookie(): String = "$cookieName=$encoded; Path=/; HttpOnly; SameSite=Strict"

    override fun toString(): String = "BridgeSecret(***)"

    sealed interface Access {
        data object Denied : Access

        /** [withoutSecret] is the target without the secret when the URL carried it, else null. */
        class Granted(val withoutSecret: String?) : Access
    }

    companion object {
        const val PARAM = "phopencode_secret"
        const val BYTES = 32

        /** Every bridge's cookie name starts with this; the bridge port follows. */
        const val COOKIE_PREFIX = "phopencode_bridge_"

        /** Case-insensitive, so a dev server cannot slip a near-identical name past the filter. */
        fun isBridgeCookie(name: String): Boolean = name.startsWith(COOKIE_PREFIX, ignoreCase = true)

        init {
            check(BYTES * Byte.SIZE_BITS >= PreviewBridge.MIN_SECRET_BITS)
        }

        /** A new secret for the bridge listening on [bridgePort]. */
        fun draw(random: SecureRandom, bridgePort: Int): BridgeSecret {
            val bytes = ByteArray(BYTES).also(random::nextBytes)
            val encoded = Base64.getUrlEncoder().withoutPadding().encode(bytes)
            bytes.fill(0)
            return BridgeSecret(encoded, "$COOKIE_PREFIX$bridgePort")
        }

        /** The path and the query, without `?`; null when there is no query. */
        fun splitTarget(target: String): Pair<String, String?> {
            val mark = target.indexOf('?')
            return if (mark < 0) target to null else target.substring(0, mark) to target.substring(mark + 1)
        }

        /** Every `name=value` pair of every `Cookie` field; the value is null for a bare name. */
        private fun cookiePairs(headers: List<Header>): List<Pair<String, String?>> =
            headers.values("Cookie").flatMap { it.split(';') }.map { it.trim() }.filter { it.isNotEmpty() }.map { pair ->
                val equals = pair.indexOf('=')
                if (equals < 0) pair to null else pair.substring(0, equals).trim() to pair.substring(equals + 1).trim()
            }
    }
}
