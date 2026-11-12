package io.github.thewisekd.phopencode.feature.preview.logic.contract

/**
 * Why a preview step failed, as few cases as the screens word differently. The service ports
 * ([PreviewPortsSource], [PreviewTunnelSource], [PreviewBridgeFactory]) fail with
 * [PreviewException]; P13.7 maps every error of the port listing (P13.4) and of the connection
 * manager's preview tunnel (P13.6) to one of these.
 *
 * Carries no text: no host name, path, command output or address.
 */
enum class PreviewFailure {
    /** No connection stands, or the one in use was closed. Going back to the connection fixes it. */
    NotConnected,

    /** The SSH connection ended or failed while the step ran. */
    ConnectionLost,

    /** Listing the ports took longer than allowed. */
    TimedOut,

    /** Listing the ports printed more than is read. */
    OutputTooLarge,

    /** The listing command ran and failed. */
    CommandFailed,

    /** The port is the opencode server's own; the preview never opens it. */
    OpencodePort,

    /** Nothing takes connections on the port: the server refused the tunnel's channel. */
    NothingListens,

    /** The bridge on the phone's loopback could not be opened. */
    BridgeFailed,

    Unknown,
}

/**
 * What the preview's service ports throw. The message names the [failure] only, never a value from
 * the server, the page or the phone, and no cause is chained, whose message could carry one; so it
 * is safe to log.
 */
class PreviewException(val failure: PreviewFailure) : Exception("Preview failure: $failure")
