package io.github.thewisekd.phopencode.core.api_http.base

/**
 * Receives one line per HTTP exchange: the method, the path, the status or the kind of failure,
 * and the time taken.
 *
 * A line never holds a header, a query, a body, the host or a server's text. Headers carry the
 * password; queries carry folder names and search text; bodies of `/api/provider`, `/api/model`
 * and `/api/config` carry API keys in clear. That holds for every logger, so an implementation may
 * write lines anywhere.
 */
fun interface HttpLogger {
    fun log(line: String)

    companion object {
        /** Drops every line. */
        val None: HttpLogger = HttpLogger { }
    }
}
