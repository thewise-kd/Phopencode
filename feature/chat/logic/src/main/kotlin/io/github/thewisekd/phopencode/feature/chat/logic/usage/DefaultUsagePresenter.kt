package io.github.thewisekd.phopencode.feature.chat.logic.usage

import io.github.thewisekd.phopencode.core.api.ApiException
import io.github.thewisekd.phopencode.core.api.Location
import io.github.thewisekd.phopencode.core.model.Message
import io.github.thewisekd.phopencode.core.model.Model
import io.github.thewisekd.phopencode.core.model.ModelRef
import io.github.thewisekd.phopencode.core.model.TokenUsage
import io.github.thewisekd.phopencode.core.session.reducer.SessionState
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatAvailability
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatFailure
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatSession
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatSessionState
import io.github.thewisekd.phopencode.feature.chat.logic.contract.UsageEvent
import io.github.thewisekd.phopencode.feature.chat.logic.contract.UsageIntent
import io.github.thewisekd.phopencode.feature.chat.logic.contract.UsagePresenter
import io.github.thewisekd.phopencode.feature.chat.logic.contract.UsageState
import java.math.BigInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * The session's tokens, cost and context fill (P6.11). The chat's bar and the detail page each have one.
 *
 * - Totals: `Session.totalUsage(messages)` of the synced session, which is the server's running total
 *   (`UsageUpdated`) when it reported one, else the messages added up. Both are recomputed from the
 *   whole state each time, never accumulated, so a resync cannot count anything twice.
 * - Model: that of the newest assistant message, else the session's.
 * - Context fill: the newest assistant message that reported usage. What its step sent (input, cache
 *   read and cache write) and what it wrote (output and reasoning) is what the next step carries, as
 *   opencode itself counts it. A message still streaming has no usage yet, so the previous step's fill
 *   stays until it ends.
 * - Limit: `limit.context` of that model in `CatalogApi.models` for the session's folder, asked through
 *   [ChatSession.call] only, once per folder while the chat is live. A missing, zero or negative limit
 *   hides the percentage. The percentage is rounded down and clamped to 0..100.
 * - A failed model list keeps the totals and sets [UsageState.error]. [UsageIntent.Retry] asks again;
 *   so does, once, a failure of a request that started before the chat's latest return to live,
 *   including one still running across the reconnect.
 *
 * Work starts after a `yield()` in [scope]. Nothing here logs.
 *
 * @param session The chat's session; the model list is asked through `ChatSession.call`.
 */
class DefaultUsagePresenter(
    private val session: ChatSession,
    private val scope: CoroutineScope,
) : UsagePresenter {
    /** The model list of one folder, as far as it got. */
    private sealed interface Catalog {
        data object None : Catalog

        /** @property epoch The [liveEpoch] the request started in. */
        data class Loading(val directory: String?, val epoch: Int) : Catalog

        data class Loaded(val directory: String?, val models: List<Model>) : Catalog

        /** @property epoch The [liveEpoch] the failed request started in. */
        data class Failed(val directory: String?, val failure: ChatFailure, val epoch: Int) : Catalog
    }

    private val mutableState = MutableStateFlow(UsageState())

    override val state: StateFlow<UsageState> = mutableState.asStateFlow()

    private val eventChannel = Channel<UsageEvent>(Channel.BUFFERED)

    override val events: Flow<UsageEvent> = eventChannel.receiveAsFlow()

    private val catalog = MutableStateFlow<Catalog>(Catalog.None)

    /** Touched only in [scope]. */
    private var fetchJob: Job? = null

    /** Touched only in [scope]: whether the last chat state seen was live. */
    private var wasLive = false

    /** Touched only in [scope]: how many times the chat has become live. */
    private var liveEpoch = 0

    init {
        scope.launch {
            yield()
            combine(session.state, catalog, ::Pair).collect { (chat, models) ->
                mutableState.value = render(chat, models)
                fetchIfNeeded(chat, models)
            }
        }
    }

    override fun onIntent(intent: UsageIntent) {
        when (intent) {
            UsageIntent.OpenDetail -> eventChannel.trySend(UsageEvent.OpenDetail)
            UsageIntent.Close -> eventChannel.trySend(UsageEvent.Close)
            UsageIntent.Retry -> scope.launch { retry() }
        }
    }

    private fun retry() {
        val chat = session.state.value
        val synced = chat.session ?: return
        if (catalog.value is Catalog.Loading) return
        if (modelOf(synced) == null) return
        fetch(synced.session.directory)
    }

    private fun fetchIfNeeded(chat: ChatSessionState, current: Catalog) {
        val live = chat.availability == ChatAvailability.Live
        if (live && !wasLive) liveEpoch++
        wasLive = live
        val synced = chat.session ?: return
        if (!live || modelOf(synced) == null) return
        val directory = synced.session.directory
        val needed = when (current) {
            Catalog.None -> true
            is Catalog.Loading -> current.directory != directory
            is Catalog.Loaded -> current.directory != directory
            // A failure of a request started before the latest return to live is asked once more.
            is Catalog.Failed -> current.directory != directory || current.epoch < liveEpoch
        }
        if (needed) fetch(directory)
    }

    private fun fetch(directory: String?) {
        fetchJob?.cancel()
        val epoch = liveEpoch
        val loading = Catalog.Loading(directory, epoch)
        catalog.value = loading
        fetchJob = scope.launch {
            val result = try {
                Catalog.Loaded(directory, session.call { it.catalog.models(directory?.let(::Location)) })
            } catch (e: ApiException) {
                Catalog.Failed(directory, ChatFailure.of(e.error), epoch)
            }
            catalog.compareAndSet(loading, result)
        }
    }

    private fun render(chat: ChatSessionState, current: Catalog): UsageState {
        val synced = chat.session ?: return UsageState(loading = chat.availability.isPending())
        val directory = synced.session.directory
        val model = modelOf(synced)
        val models = (current as? Catalog.Loaded)?.takeIf { it.directory == directory }?.models
        val limit = if (model == null || models == null) {
            null
        } else {
            models.firstOrNull { it.providerId == model.providerId && it.id == model.modelId }
                ?.limit
                ?.context
                ?.takeIf { it > 0 }
        }
        val fill = contextFill(synced.messages)
        val tokens = if (limit != null) fill ?: BigInteger.ZERO else fill
        return UsageState(
            loading = false,
            usage = totals(synced),
            model = model,
            contextLimit = limit,
            contextTokens = tokens,
            contextPercent = limit?.let { percent(tokens ?: BigInteger.ZERO, it) },
            error = (current as? Catalog.Failed)?.takeIf { it.directory == directory }?.failure,
        )
    }

    private fun ChatAvailability.isPending(): Boolean =
        this == ChatAvailability.Waiting || this == ChatAvailability.Live || this == ChatAvailability.Reconnecting

    private fun totals(synced: SessionState): TokenUsage? {
        val counted = synced.messages.any { it is Message.Assistant || it is Message.Compaction }
        if (synced.session.usage == null && !counted) return null
        return synced.session.totalUsage(synced.messages)
    }

    /** The newest assistant message's model; when it has none, the session's, never an older message's. */
    private fun modelOf(synced: SessionState): ModelRef? =
        synced.messages.filterIsInstance<Message.Assistant>().lastOrNull()?.model
            ?: synced.session.model

    private fun contextFill(messages: List<Message>): BigInteger? {
        val usage = messages.lastOrNull { it is Message.Assistant && it.usage != null }
            ?.let { (it as Message.Assistant).usage }
            ?: return null
        return usage.input + usage.cacheRead + usage.cacheWrite + usage.output + usage.reasoning
    }

    private fun percent(tokens: BigInteger, limit: Long): Int {
        val raw = tokens.multiply(HUNDRED).divide(BigInteger.valueOf(limit))
        return raw.max(BigInteger.ZERO).min(HUNDRED).toInt()
    }

    private companion object {
        val HUNDRED: BigInteger = BigInteger.valueOf(100)
    }
}
