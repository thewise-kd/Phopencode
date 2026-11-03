package io.github.thewisekd.phopencode.core.ssh.connect

import java.net.InetAddress

/**
 * Turns a host name into an address. A lookup blocks and cannot be interrupted, so the connector
 * runs it where a timeout can walk away from it.
 */
internal fun interface HostResolver {
    /** @throws java.net.UnknownHostException */
    fun resolve(host: String): InetAddress

    companion object {
        val System = HostResolver { InetAddress.getByName(it) }
    }
}
