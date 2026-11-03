package io.github.thewisekd.phopencode.core.ssh.connect

/**
 * Stands in for a library exception as a cause. It keeps the type and the stack trace, which is
 * what debugging needs, and drops the message, which can quote a host, a user name or a server's
 * reply. Library messages are not under this module's control, so none of them is passed on.
 */
internal class RedactedFailure private constructor(type: String, cause: Throwable?) : Exception(type, cause) {
    override fun fillInStackTrace(): Throwable = this

    companion object {
        private const val MAX_DEPTH = 8

        fun of(failure: Throwable?): RedactedFailure? = copy(failure, 0)

        private fun copy(failure: Throwable?, depth: Int): RedactedFailure? {
            if (failure == null || depth >= MAX_DEPTH) return null
            if (failure is RedactedFailure) return failure
            return RedactedFailure(failure.javaClass.name, copy(failure.cause, depth + 1)).apply {
                stackTrace = failure.stackTrace
            }
        }
    }
}
