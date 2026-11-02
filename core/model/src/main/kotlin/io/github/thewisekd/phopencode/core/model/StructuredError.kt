package io.github.thewisekd.phopencode.core.model

/**
 * An error as the server reports it on events and messages.
 *
 * [message] and [responseBody] are the server's or a provider's free text, and a provider may echo a
 * request back, so [toString] leaves them out. Read them only to show them to the user.
 *
 * @property type What failed, for example `provider.transport`. Open: the server may add more.
 * @property status The provider's reply status, if it was given.
 * @property responseBody The provider's raw reply, from `response.body`.
 * @property retryAfterMs How long the provider asked to wait.
 */
data class StructuredError(
    val type: String,
    val message: String? = null,
    val status: Int? = null,
    val responseBody: String? = null,
    val retryAfterMs: Long? = null,
) {
    override fun toString(): String = "StructuredError(type=$type, status=$status)"
}
