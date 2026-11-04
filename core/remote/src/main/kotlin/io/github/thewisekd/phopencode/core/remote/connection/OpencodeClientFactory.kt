package io.github.thewisekd.phopencode.core.remote.connection

import io.github.thewisekd.phopencode.core.api.OpencodeClient
import io.github.thewisekd.phopencode.core.api_http.base.BasicCredentials
import io.github.thewisekd.phopencode.core.api_http.base.HttpLogger
import io.github.thewisekd.phopencode.core.api_http.client.HttpOpencodeClient
import io.github.thewisekd.phopencode.core.storage_api.SecretValue
import javax.net.SocketFactory

/** Makes the API client of one connection; [Default] is the HTTP client over the session's tunnel. */
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

    /**
     * [HttpOpencodeClient] with every request and the event stream on [sockets], so `127.0.0.1`
     * is the SSH server's loopback and the password only travels inside the tunnel. It keeps only
     * the ready `Authorization` value, not [password], and logs nothing. Closing the client does not
     * close [sockets] or the session.
     */
    companion object Default : OpencodeClientFactory {
        override fun create(baseUrl: String, password: SecretValue, sockets: SocketFactory): OpencodeClient =
            HttpOpencodeClient.create(
                baseUrl = baseUrl,
                credentials = BasicCredentials(password.revealText()),
                logger = HttpLogger.None,
                socketFactory = sockets,
            )
    }
}
