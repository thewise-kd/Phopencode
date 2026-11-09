package io.github.thewisekd.phopencode.feature.notify.logic.contract

import io.github.thewisekd.phopencode.core.model.SecretRef
import java.net.URI
import java.net.URISyntaxException

/**
 * The ntfy topic the notification service publishes to and the user subscribes to in the ntfy app.
 * Anyone who knows it can read the notifications, so it is a secret: it lives in `SecretStore`
 * under [NotifySecrets.TOPIC], never in a log, a route, an exception message or a command line,
 * and it prints masked. `equals` stays the identity one, as `SecretValue`'s does; compare
 * [reveal]ed text where a comparison is really needed.
 *
 * P11.9 makes a new one from a `SecureRandom`: [MIN_LENGTH] or more characters drawn uniformly from
 * [ALPHABET], which gives at least [MIN_BITS] bits. [of] refuses anything weaker, so a topic read
 * back from storage is as strong as a new one.
 */
class NtfyTopic private constructor(private val value: String) {
    /** The topic itself, to put in the service's config or in the subscription link. */
    fun reveal(): String = value

    override fun toString(): String = "NtfyTopic(***)"

    companion object {
        /** The least randomness a topic carries. */
        const val MIN_BITS: Int = 128

        /** What one character of [ALPHABET] carries: it has 64 characters. */
        const val BITS_PER_CHARACTER: Int = 6

        /** 22 characters carry 132 bits, the first length at or above [MIN_BITS]. */
        const val MIN_LENGTH: Int = (MIN_BITS + BITS_PER_CHARACTER - 1) / BITS_PER_CHARACTER

        /** The longest topic the notification service accepts (`helper/notifyd/config.py`). */
        const val MAX_LENGTH: Int = 64

        /** The characters ntfy and the service accept in a topic, base64url's. */
        const val ALPHABET: String = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

        /** [text] as a topic, or null when it is too short, too long, or has another character. */
        fun of(text: String): NtfyTopic? {
            if (text.length !in MIN_LENGTH..MAX_LENGTH) return null
            if (!text.all { it in ALPHABET }) return null
            return NtfyTopic(text)
        }
    }
}

/** Why a typed ntfy server was not taken. */
enum class NtfyServerProblem {
    Blank,

    /** Not `https://`: the service sends over HTTPS only. */
    NotHttps,

    /**
     * Not a plain origin: no host, a port out of range, credentials, a path, a query or a
     * fragment. ntfy serves topics at the root, and credentials must not sit in a stored URL.
     */
    Invalid,
}

/**
 * The ntfy server the service publishes to: an `https` origin with a host and maybe a port. The
 * default is [DEFAULT], `https://ntfy.sh` (decision D5); the user can choose their own. The choice
 * lives in `SecretStore` under [NotifySecrets.SERVER], since a server of the user's own is an
 * address that must not leak; for the same reason it prints masked, unless it is [DEFAULT].
 *
 * @property url The normalized origin, for example `https://ntfy.sh`, with no trailing slash and
 *   the host in lower case; the default port 443 is left out.
 * @property host The host, with `:port` when [url] has one: what the subscription link names.
 */
class NtfyServer private constructor(val url: String, val host: String) {
    /** The link the ntfy app subscribes with: `ntfy://<host>/<topic>`. It holds the topic. */
    fun subscriptionLink(topic: NtfyTopic): String = "ntfy://$host/${topic.reveal()}"

    override fun equals(other: Any?): Boolean = other is NtfyServer && other.url == url

    override fun hashCode(): Int = url.hashCode()

    override fun toString(): String = if (url == DEFAULT_URL) "NtfyServer(default)" else "NtfyServer(***)"

    companion object {
        private const val DEFAULT_URL = "https://ntfy.sh"
        private const val HTTPS_PORT = 443
        private const val MAX_PORT = 65535

        val DEFAULT: NtfyServer = NtfyServer(DEFAULT_URL, "ntfy.sh")

        /** [text], trimmed, as a server, or null when [problemOf] finds a problem. */
        fun parse(text: String): NtfyServer? = read(text).first

        /** Why [text] is not a server, or null when [parse] takes it. */
        fun problemOf(text: String): NtfyServerProblem? = read(text).second

        private fun read(text: String): Pair<NtfyServer?, NtfyServerProblem?> {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return null to NtfyServerProblem.Blank
            val scheme = trimmed.substringBefore("://", missingDelimiterValue = "")
            if (!scheme.equals("https", ignoreCase = true)) return null to NtfyServerProblem.NotHttps
            val uri = try {
                URI(trimmed)
            } catch (_: URISyntaxException) {
                return null to NtfyServerProblem.Invalid
            }
            val host = uri.host?.lowercase()
            val plain = !host.isNullOrEmpty() &&
                uri.rawUserInfo == null &&
                uri.rawQuery == null &&
                uri.rawFragment == null &&
                (uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") &&
                (uri.port == -1 || uri.port in 1..MAX_PORT)
            if (!plain) return null to NtfyServerProblem.Invalid
            val authority = if (uri.port == -1 || uri.port == HTTPS_PORT) host else "$host:${uri.port}"
            return NtfyServer("https://$authority", authority) to null
        }
    }
}

/** What the service is installed with and what a test notification goes to. Prints masked. */
data class NtfySettings(val server: NtfyServer, val topic: NtfyTopic) {
    override fun toString(): String = "NtfySettings(server=$server, topic=***)"
}

/**
 * Where the setup keeps its values in `SecretStore`: one topic and one server for the app, used on
 * every server the service is installed on, so the user subscribes once. Each value is stored as
 * UTF-8 text ([NtfyTopic.reveal], [NtfyServer.url]). Deleting all data (`SecretStore.deleteAll`)
 * removes them; the next setup makes a new topic.
 */
object NotifySecrets {
    val TOPIC: SecretRef = SecretRef("notify-ntfy-topic")

    val SERVER: SecretRef = SecretRef("notify-ntfy-server")
}
