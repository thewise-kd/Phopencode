package io.github.thewisekd.phopencode.feature.connect.logic.folder

import io.github.thewisekd.phopencode.core.remote.connection.ConnectionError
import io.github.thewisekd.phopencode.core.remote.connection.ConnectionState

/**
 * A short technical detail of why a connection ended, for the folder picker's
 * [io.github.thewisekd.phopencode.feature.connect.logic.contract.FolderError.NotConnected]: the
 * [ConnectionError]'s simple type name, with the [ConnectionError.Api]'s own `ApiError` type in
 * brackets, as in `Api(Unauthorized)`; then, for [ConnectionError.Unexpected] with a cause, the
 * cause's class chain as [FailureDetail] builds it, as in
 * `Unexpected <- java.lang.NoSuchMethodError`.
 *
 * [ofState] adds the connection states without an error that still leave no connection.
 *
 * Only type names: no value an error carries (a port, a host key, a server's text) and no message
 * is ever read.
 */
internal object ConnectionDetail {
    private const val SEPARATOR = " <- "

    /**
     * The detail for `FolderError.NotConnected` while the connection is in [state]: the error of
     * [ConnectionState.Error] as [of] names it; exactly `Idle` or `Closed` for those states, since a
     * connection that ends there without an error is worth naming (P9.24); null while a connect or
     * reconnect is under way, or while connected.
     */
    fun ofState(state: ConnectionState): String? = when (state) {
        is ConnectionState.Error -> of(state.error)
        ConnectionState.Idle -> IDLE
        ConnectionState.Closed -> CLOSED
        is ConnectionState.Ssh,
        is ConnectionState.HostApproval,
        is ConnectionState.Server,
        is ConnectionState.Api,
        is ConnectionState.Reconnecting,
        is ConnectionState.Connected,
        -> null
    }

    fun of(error: ConnectionError): String {
        val name = typeName(error)
        val type = if (error is ConnectionError.Api) "$name(${typeName(error.error)})" else name
        val cause = (error as? ConnectionError.Unexpected)?.cause
        return if (cause == null) type else type + SEPARATOR + FailureDetail.of(cause)
    }

    private fun typeName(value: Any): String = value.javaClass.simpleName

    const val IDLE = "Idle"
    const val CLOSED = "Closed"
}
