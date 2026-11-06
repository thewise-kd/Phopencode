package io.github.thewisekd.phopencode.feature.connect.logic.folder

import io.github.thewisekd.phopencode.core.ssh.api.SshException

/**
 * A short technical detail of a failure for the screen: the class names of the failure and its
 * causes, joined with ` <- `, at most [MAX_LINKS] of them. Project classes are named simply, with
 * their outer class (`SshException.Sftp`); library and JDK classes by their full name. An
 * [SshException.Sftp] adds its failure, as in `SshException.Sftp(Failed)`.
 *
 * No message is ever read, since one can quote a path, a host, a user name or a server's reply;
 * the one exception is core/ssh's redacted wrapper, whose message is by construction the class
 * name of the failure it stands in for, and which is used only when it has the shape of a class
 * name.
 */
internal object FailureDetail {
    const val MAX_LINKS = 5

    private const val PROJECT_PACKAGE = "io.github.thewisekd.phopencode."

    /** core/ssh's `RedactedFailure`; internal there, so it is known by name. */
    private const val REDACTED_CLASS = "io.github.thewisekd.phopencode.core.ssh.connect.RedactedFailure"

    /** Named when its message does not have the shape of a class name. */
    private const val REDACTED_FALLBACK = "RedactedFailure"

    /** At most this long, a JVM class name that is longer is not believed. */
    private const val MAX_NAME_LENGTH = 200

    private val CLASS_NAME = Regex("""[\p{L}_$][\p{L}\p{N}_$]*(\.[\p{L}_$][\p{L}\p{N}_$]*)*""")

    /** @param redactedClass The redacted wrapper's class name; tests replace it. */
    fun of(failure: Throwable, redactedClass: String = REDACTED_CLASS): String =
        generateSequence(failure) { current -> current.cause?.takeIf { it !== current } }
            .take(MAX_LINKS)
            .joinToString(" <- ") { link(it, redactedClass) }

    private fun link(failure: Throwable, redactedClass: String): String {
        val type = failure.javaClass.name
        val name = if (type == redactedClass) {
            failure.message?.takeIf { it.length <= MAX_NAME_LENGTH && CLASS_NAME.matches(it) }
                ?.let(::shown) ?: REDACTED_FALLBACK
        } else {
            shown(type)
        }
        return if (failure is SshException.Sftp) "$name(${failure.failure.name})" else name
    }

    /** [type], a JVM class name, as shown: simply for a project class, in full otherwise. */
    private fun shown(type: String): String =
        if (type.startsWith(PROJECT_PACKAGE)) type.substringAfterLast('.').replace('$', '.') else type
}
