package io.github.thewisekd.phopencode.feature.preview.logic.service

import io.github.thewisekd.phopencode.core.remote.connection.ConnectionManager
import io.github.thewisekd.phopencode.core.remote.connection.NotConnectedException
import io.github.thewisekd.phopencode.core.remote.preview.ListeningPortLister
import io.github.thewisekd.phopencode.core.remote.preview.PortListFailure
import io.github.thewisekd.phopencode.core.remote.preview.PortListing
import io.github.thewisekd.phopencode.core.ssh.api.RemoteExec
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewException
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewFailure
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewPort
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewPortList
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewPortsSource
import java.net.URI
import java.net.URISyntaxException
import kotlin.coroutines.cancellation.CancellationException

/** Lists the listening ports through one command runner; [ListeningPortLister] in the app, a fake in tests. */
fun interface PortLister {
    suspend fun list(exec: RemoteExec, opencodePort: Int?): PortListing
}

/**
 * [PreviewPortsSource] on the server [connection] stands on: P13.4's [ListeningPortLister], run
 * through the command runner of the connection that stands when the call starts
 * ([ConnectionManager.withConnection]), with that connection's opencode port marked.
 *
 * - No connection is [PreviewFailure.NotConnected]; a connection that ends or is replaced while the
 *   listing runs is [PreviewFailure.ConnectionLost]. A listing never moves to a newer connection.
 * - Every [PortListFailure] maps to the [PreviewFailure] of the same name; anything else is
 *   [PreviewFailure.Unknown]. Cancellation is never wrapped, and no failure keeps a cause.
 * - The opencode port is the port of [io.github.thewisekd.phopencode.core.remote.connection.BoundConnection.opencode]'s
 *   address. The connection manager sets that for every connection its `ServerResolver` makes,
 *   since the resolver always says where the accepted password is stored. Should it ever be
 *   missing, nothing is marked; opening that port is still refused, because the manager's
 *   [ConnectionManager.openPreviewTunnel] checks the port itself.
 *
 * Runs nothing but the fixed listing: no process is started, stopped or signalled, and no `sudo`.
 */
class ConnectionPreviewPorts(
    private val connection: ConnectionManager,
    private val lister: PortLister = PortLister { exec, opencodePort -> ListeningPortLister(exec).list(opencodePort) },
) : PreviewPortsSource {

    override suspend fun list(): PreviewPortList {
        var started = false
        val listing = try {
            connection.withConnection { bound ->
                started = true
                lister.list(bound.exec, opencodePortOf(bound.opencode?.baseUrl))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: NotConnectedException) {
            throw PreviewException(if (started) PreviewFailure.ConnectionLost else PreviewFailure.NotConnected)
        } catch (_: Exception) {
            throw PreviewException(PreviewFailure.Unknown)
        }
        return when (listing) {
            is PortListing.Listed -> PreviewPortList.Listed(listing.ports.map { PreviewPort(it.port, it.isOpencode) })
            PortListing.Unsupported -> PreviewPortList.Unsupported
            is PortListing.Failed -> throw PreviewException(listing.reason.toFailure())
        }
    }

    override fun toString(): String = "ConnectionPreviewPorts"
}

/** Every [PortListFailure] as the [PreviewFailure] the preview screens word. */
internal fun PortListFailure.toFailure(): PreviewFailure = when (this) {
    PortListFailure.TimedOut -> PreviewFailure.TimedOut
    PortListFailure.OutputTooLarge -> PreviewFailure.OutputTooLarge
    PortListFailure.CommandFailed -> PreviewFailure.CommandFailed
    PortListFailure.ConnectionLost -> PreviewFailure.ConnectionLost
}

/** The TCP port of the opencode server's [baseUrl], a loopback URL; null when it names none. */
internal fun opencodePortOf(baseUrl: String?): Int? {
    if (baseUrl == null) return null
    val port = try {
        URI(baseUrl).port
    } catch (_: URISyntaxException) {
        return null
    }
    return port.takeIf { it in 1..MAX_TCP_PORT }
}

private const val MAX_TCP_PORT = 65535
