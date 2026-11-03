package io.github.thewisekd.phopencode.core.api_http.base

import okhttp3.Credentials

/**
 * How the app logs in to `opencode serve`: HTTP Basic, with the server's fixed user name and the
 * server password. A pairing token from `opencode pair` goes where the password goes.
 *
 * Only the ready `Authorization` value is kept. Printing never shows it, nor its length.
 */
class BasicCredentials(password: String, username: String = DEFAULT_USERNAME) {
    /** The `Authorization` header value. UTF-8, so a password outside ASCII reaches the server intact. */
    internal val headerValue: String = Credentials.basic(username, password, Charsets.UTF_8)

    override fun toString(): String = "BasicCredentials(***)"

    companion object {
        /** `opencode serve` has a fixed user name. */
        const val DEFAULT_USERNAME: String = "opencode"
    }
}
