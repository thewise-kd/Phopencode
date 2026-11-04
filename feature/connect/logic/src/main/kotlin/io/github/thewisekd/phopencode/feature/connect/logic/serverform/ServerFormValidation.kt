package io.github.thewisekd.phopencode.feature.connect.logic.serverform

import io.github.thewisekd.phopencode.feature.connect.logic.contract.ServerFormFieldError

/** A field's text, checked: its normalized value, or why it was refused. */
internal sealed interface Checked<out T> {
    data class Valid<T>(val value: T) : Checked<T>

    data class Invalid(val error: ServerFormFieldError) : Checked<Nothing>
}

private val REQUIRED = Checked.Invalid(ServerFormFieldError.Required)
private val INVALID = Checked.Invalid(ServerFormFieldError.Invalid)

private const val MAX_HOST_NAME_LENGTH = 253
private const val MAX_LABEL_LENGTH = 63
private const val MAX_USERNAME_LENGTH = 32
private const val IPV4_PARTS = 4
private const val IPV4_PART_MAX = 255
private const val IPV6_GROUPS = 8
private const val IPV6_GROUP_DIGITS = 4
private const val MAX_PORT = 65535

/** A DNS label: ASCII letters, digits and inner hyphens. Punycode (`xn--`) names pass as typed. */
private val LABEL = Regex("[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?")
private val ASCII_DIGITS = Regex("[0-9]+")
private val PORT_DIGITS = Regex("[0-9]{1,5}")

/**
 * The POSIX portable file name characters, which `useradd` and sshd accept everywhere. A leading
 * hyphen is refused so that a user name can never be read as an `ssh` option, and a leading dot so
 * that it is never a hidden path.
 */
private val USERNAME = Regex("[A-Za-z0-9_][A-Za-z0-9._-]*")

/**
 * A host name, an IPv4 address in dotted decimal or an IPv6 literal; never a URL, a `host:port`, a
 * `user@host` or a path. Spaces around it are dropped, and so are brackets around an IPv6 literal.
 * IPv6 zone IDs (`fe80::1%wlan0`) are refused.
 */
internal fun checkHost(text: String): Checked<String> {
    val trimmed = text.trim()
    val bracketed = trimmed.length >= 2 && trimmed.startsWith('[') && trimmed.endsWith(']')
    val host = if (bracketed) trimmed.substring(1, trimmed.length - 1) else trimmed
    if (host.isEmpty()) return REQUIRED
    val valid = when {
        bracketed || ':' in host -> isIpv6(host)
        else -> isIpv4(host) || isHostName(host)
    }
    return if (valid) Checked.Valid(host) else INVALID
}

/** A TCP port, 1 to 65535, in ASCII digits only. */
internal fun checkPort(text: String): Checked<Int> {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return REQUIRED
    if (!PORT_DIGITS.matches(trimmed)) return INVALID
    val port = trimmed.toInt()
    return if (port in 1..MAX_PORT) Checked.Valid(port) else INVALID
}

/** A login name in the POSIX portable set, at most 32 characters and not only digits. */
internal fun checkUsername(text: String): Checked<String> {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return REQUIRED
    val valid = trimmed.length <= MAX_USERNAME_LENGTH &&
        USERNAME.matches(trimmed) &&
        !ASCII_DIGITS.matches(trimmed)
    return if (valid) Checked.Valid(trimmed) else INVALID
}

/**
 * Labels of 1 to 63 characters, 253 in all, with one optional trailing dot. A last label made of
 * digits only is refused: no top-level domain is numeric, so `192.0.2` or `999` is a mistyped
 * address rather than a name.
 */
private fun isHostName(host: String): Boolean {
    val name = host.removeSuffix(".")
    if (name.isEmpty() || name.length > MAX_HOST_NAME_LENGTH) return false
    val labels = name.split('.')
    return labels.all { it.length <= MAX_LABEL_LENGTH && LABEL.matches(it) } &&
        !ASCII_DIGITS.matches(labels.last())
}

/** Four decimal parts of 0 to 255, without leading zeros, which some resolvers read as octal. */
private fun isIpv4(host: String): Boolean {
    val parts = host.split('.')
    return parts.size == IPV4_PARTS && parts.all { part ->
        ASCII_DIGITS.matches(part) &&
            part.length <= 3 &&
            (part == "0" || !part.startsWith('0')) &&
            part.toInt() <= IPV4_PART_MAX
    }
}

/** RFC 4291 text form: eight hex groups, one `::` at most, and an optional IPv4 tail. */
private fun isIpv6(host: String): Boolean {
    val lastColon = host.lastIndexOf(':')
    if (lastColon < 0) return false
    val tail = host.substring(lastColon + 1)
    // An IPv4 tail stands for the last two groups.
    val groupsText = if ('.' in tail) {
        if (!isIpv4(tail)) return false
        host.substring(0, lastColon + 1) + "0:0"
    } else {
        host
    }
    val halves = groupsText.split("::")
    return when (halves.size) {
        1 -> hexGroups(groupsText)?.size == IPV6_GROUPS
        2 -> {
            val left = hexGroups(halves[0]) ?: return false
            val right = hexGroups(halves[1]) ?: return false
            left.size + right.size < IPV6_GROUPS
        }
        else -> false
    }
}

/** The colon-separated groups of 1 to 4 hex digits, or null if one is not; empty text has none. */
private fun hexGroups(text: String): List<String>? {
    if (text.isEmpty()) return emptyList()
    val groups = text.split(':')
    return groups.takeIf { all -> all.all { it.length in 1..IPV6_GROUP_DIGITS && it.all(::isHexDigit) } }
}

private fun isHexDigit(c: Char): Boolean = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'
