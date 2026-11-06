package io.github.thewisekd.phopencode.feature.connect.logic.folder

import io.github.thewisekd.phopencode.core.remote.connection.ConnectionError

/**
 * A short technical detail of why a connection ended, for the folder picker's
 * [io.github.thewisekd.phopencode.feature.connect.logic.contract.FolderError.NotConnected]: the
 * [ConnectionError]'s simple type name, with the [ConnectionError.Api]'s own `ApiError` type in
 * brackets, as in `Api(Unauthorized)`; then, for [ConnectionError.Unexpected] with a cause, the
 * cause's class chain as [FailureDetail] builds it, as in
 * `Unexpected <- java.lang.NoSuchMethodError`.
 *
 * Only type names: no value an error carries (a port, a host key, a server's text) and no message
 * is ever read.
 */
internal object ConnectionDetail {
    private const val SEPARATOR = " <- "

    fun of(error: ConnectionError): String {
        val name = typeName(error)
        val type = if (error is ConnectionError.Api) "$name(${typeName(error.error)})" else name
        val cause = (error as? ConnectionError.Unexpected)?.cause
        return if (cause == null) type else type + SEPARATOR + FailureDetail.of(cause)
    }

    private fun typeName(value: Any): String = value.javaClass.simpleName
}
