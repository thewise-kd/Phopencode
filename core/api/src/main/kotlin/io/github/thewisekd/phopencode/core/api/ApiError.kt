package io.github.thewisekd.phopencode.core.api

/**
 * Why a call failed, as a type the UI can turn into a localized text.
 *
 * [message] is English text for logs, and is what the printed forms show. It is safe to log: the
 * only server values in it are a status number or a version. Free server text goes in `detail`,
 * which neither [message] nor [toString] ever includes, because a server may echo a request back,
 * and with it a credential or a file's content. Read `detail` only to show it to the user.
 *
 * `status` is the plain number of a server reply; no HTTP type crosses this interface.
 */
sealed class ApiError(val message: String) {
    final override fun toString(): String = "ApiError.${javaClass.simpleName}: $message"

    /** The password or pairing token was refused. */
    data object Unauthorized : ApiError("The server refused the credentials.")

    data object Forbidden : ApiError("The server does not allow this action.")

    /** The caller knows what it asked for, so this does not repeat a path or an ID. */
    data object NotFound : ApiError("The server has no such item.")

    /** The request clashes with the current state, for example a session that is busy. */
    data class Conflict(val detail: String? = null) :
        ApiError("The request conflicts with the current state on the server.")

    /** The server refused the request's content. */
    data class BadRequest(val detail: String? = null) : ApiError("The server refused the request.")

    /** The server failed: status 500 and above. */
    data class Server(val status: Int, val detail: String? = null) :
        ApiError("The server failed with status $status.")

    /** The server could not be reached, or the connection dropped. */
    data object Network : ApiError("The server could not be reached.")

    data object Timeout : ApiError("The server did not answer in time.")

    /** @property found The version the server reported, parsed: this one appears in [message]. */
    data class VersionTooOld(
        val found: String,
        val required: String = OpencodeClient.MIN_SERVER_VERSION,
    ) : ApiError("The server runs opencode $found, but this app needs $required or newer.")

    /** The reply could not be understood. A decoder's text quotes the reply, so none is kept here. */
    data object MalformedResponse : ApiError("The server sent a reply this app cannot read.")

    /** The call came after [OpencodeClient.close]. Retrying cannot help: it takes a new client. */
    data object Closed : ApiError("This client has been closed.")

    /** Any other failure. [status] is null when there was no server reply. */
    data class Unexpected(val status: Int? = null, val detail: String? = null) : ApiError(
        if (status == null) "An unexpected error occurred." else "The server replied with unexpected status $status.",
    )
}

/**
 * Carries an [ApiError] out of a call; see [OpencodeClient].
 *
 * It has no cause on purpose: stack traces print the cause's message, and a low-level exception can
 * quote a URL, a host or a piece of the reply.
 */
class ApiException(val error: ApiError) : Exception(error.message)
