package io.github.thewisekd.phopencode.core.remote.connection

/**
 * What the platform tells [ConnectionManager] about the device, so that a dropped connection comes
 * back fast: the default network coming and going, and the app coming to the foreground. Every
 * call is cheap, never blocks and may come from any thread.
 *
 * A network is whatever object the platform names it with; it is only compared with `equals`, and
 * never printed or logged.
 */
interface ConnectionSignals {
    /**
     * [network] is now the default network. The first call names the network the device has; a
     * later call with another network is a switch, as from Wi-Fi to mobile data.
     */
    fun networkAvailable(network: Any)

    /**
     * [network] is gone. Ignored when another network has become the default since, as when the
     * loss of the old network comes after its successor.
     */
    fun networkLost(network: Any)

    /** The app came to the foreground: the user is looking at it. */
    fun appForegrounded()
}
