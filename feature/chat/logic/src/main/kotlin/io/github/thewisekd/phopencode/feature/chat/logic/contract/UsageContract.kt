package io.github.thewisekd.phopencode.feature.chat.logic.contract

import io.github.thewisekd.phopencode.core.model.ModelRef
import io.github.thewisekd.phopencode.core.model.TokenUsage
import java.math.BigInteger

/**
 * The session's tokens and cost: a bar on the chat, and the `ChatRoute.TokenUsage` detail page
 * (P6.11, P6.12). Both show the same state, each from its own presenter.
 *
 * Where it comes from: [usage] is `Session.totalUsage(messages)` of `ChatSessionState.session`: the
 * server's reported total, kept current by `UsageUpdated`, else the messages added up. [model] is
 * the model of the newest assistant message, else the session's. [contextLimit] is
 * `Model.limit.context` of that model, from `CatalogApi.models` for the session's folder, asked
 * through `ChatSession.call`. The context fill is the newest assistant message's tokens.
 *
 * @property loading Nothing is loaded yet.
 * @property usage Input, output, reasoning, cache read and write, and cost; null when none is known.
 * @property contextLimit Null when the model has none or is not known: then no percentage shows.
 * @property contextTokens The tokens the context holds now.
 * @property contextPercent [contextTokens] of [contextLimit], 0 to 100; null without a limit.
 * @property error The model list could not be loaded; the totals still show.
 */
data class UsageState(
    val loading: Boolean = true,
    val usage: TokenUsage? = null,
    val model: ModelRef? = null,
    val contextLimit: Long? = null,
    val contextTokens: BigInteger? = null,
    val contextPercent: Int? = null,
    val error: ChatFailure? = null,
) {
    /** The context percentage shows only with a limit. */
    val showsContext: Boolean
        get() = contextPercent != null
}

sealed interface UsageIntent {
    /** The bar was tapped. */
    data object OpenDetail : UsageIntent

    /** Loads the model list again after [UsageState.error]. */
    data object Retry : UsageIntent

    /** Leaves the detail page. */
    data object Close : UsageIntent
}

sealed interface UsageEvent {
    /** Opens `ChatRoute.TokenUsage` for the session. */
    data object OpenDetail : UsageEvent

    data object Close : UsageEvent
}

interface UsagePresenter : Presenter<UsageState, UsageIntent, UsageEvent>
