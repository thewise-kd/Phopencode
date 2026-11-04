package io.github.thewisekd.phopencode.core.remote.connection

import io.github.thewisekd.phopencode.core.api.OpencodeClient
import io.github.thewisekd.phopencode.core.storage_api.SecretValue
import javax.net.SocketFactory

/** Makes the API client of one connection. */
fun interface OpencodeClientFactory {
    /**
     * A client for the server at [baseUrl], as seen from the SSH server, for example
     * `http://127.0.0.1:4096`. Every connection it makes must go through [sockets], the SSH
     * session's tunnel: the address means nothing on the phone. Nothing should be sent yet.
     *
     * @param password The `opencode serve` password, for HTTP Basic. The manager closes it after
     *   this returns, so the client must keep its own copy, never this value.
     */
    fun create(baseUrl: String, password: SecretValue, sockets: SocketFactory): OpencodeClient
}
