package io.github.thewisekd.phopencode.feature.chat.logic.permission

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.ApiException
import io.github.thewisekd.phopencode.core.api.Location
import io.github.thewisekd.phopencode.core.api.OpencodeClient
import io.github.thewisekd.phopencode.core.api.ServerEvent
import io.github.thewisekd.phopencode.core.model.PermissionDecision
import io.github.thewisekd.phopencode.core.model.PermissionRequest
import io.github.thewisekd.phopencode.core.model.PermissionRequestId
import io.github.thewisekd.phopencode.core.model.SessionId
import io.github.thewisekd.phopencode.core.navigation.ChatRoute
import io.github.thewisekd.phopencode.core.session.sync.SyncPhase
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatAvailability
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatFailure
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatSession
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatSessionState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PermissionCardState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PermissionError
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PermissionIntent
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PermissionPresenter
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PermissionsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * The permission requests of this session and of its subagents, at any depth, as cards (P7.4); the
 * rules are on [PermissionsState].
 *
 * - This session's requests come from `SessionState.permissions` of [session], as its sync keeps
 *   them.
 * - The subagents' come from `InteractionApi.listPermissions(sessionId = null,
 *   location = Location(directory))`, kept to the sessions whose `parentId` chain reaches this one
 *   (walked with `SessionApi.get`, remembered per session), and from `permission.asked` and
 *   `permission.replied` on the event stream. The stream runs inside [ChatSession.call] while the
 *   chat is live and synced; every `ServerEvent.Connected` lists again, and so does
 *   [PermissionIntent.Retry]. When the chat loses its session (another server, the session gone)
 *   the subagents' cards are dropped at once.
 * - Oldest first means first seen first: the requests carry no time.
 * - A reply goes through [ChatSession.call], so only to the bound server, and to the session the
 *   request itself names. It is claimed before it starts, so a card sends one reply at a time. A
 *   reply that worked, or failed with `NotFound` because the server already settled it, removes the
 *   card for good, even while the sync still holds the request; any other failure keeps the card
 *   with [PermissionCardState.error].
 *
 * Nothing runs in the constructor: work starts in [scope] after a `yield()`. Nothing here logs; the
 * requests and the rejection message are user data and print masked.
 *
 * @param route The destination's route.
 * @param scope The destination's scope; work started here stops with it.
 * @param session The destination's session, shared with its other presenters.
 */
class DefaultPermissionPresenter(
    @Suppress("unused") private val route: ChatRoute,
    private val scope: CoroutineScope,
    private val session: ChatSession,
) : PermissionPresenter {
    /** The decision UI of one card, apart from the request. */
    private data class CardUi(
        val confirmingAlways: Boolean = false,
        val rejecting: Boolean = false,
        val rejectMessage: String = "",
        val replying: PermissionDecision? = null,
        val error: PermissionError? = null,
    ) {
        override fun toString(): String =
            "CardUi(confirmingAlways=$confirmingAlways, rejecting=$rejecting, rejectMessage=***, " +
                "replying=$replying, error=$error)"
    }

    /**
     * @property own This session's requests, from its sync.
     * @property children The subagents' requests, from the list and the events.
     * @property settled Replied to from here or by the server; never shown again.
     * @property order The shown requests, first seen first.
     */
    private data class Model(
        val own: List<PermissionRequest> = emptyList(),
        val children: List<PermissionRequest> = emptyList(),
        val ui: Map<PermissionRequestId, CardUi> = emptyMap(),
        val settled: Set<PermissionRequestId> = emptySet(),
        val order: List<PermissionRequestId> = emptyList(),
        val error: ChatFailure? = null,
    ) {
        /** This session's copy of a request comes first and wins over a listed one. */
        val visible: Map<PermissionRequestId, PermissionRequest>
            get() = (own + children).filter { it.id !in settled }.distinctBy { it.id }.associateBy { it.id }

        override fun toString(): String =
            "Model(own=${own.size}, children=${children.size}, settled=${settled.size}, error=$error)"
    }

    /** What the event stream may follow: the chat is live and synced, in [directory]. */
    private data class Watch(val directory: String?)

    private sealed interface Signal {
        data class Event(val event: ServerEvent) : Signal

        data object Relist : Signal
    }

    private val lock = Any()

    private var model = Model()

    /** Whether a session descends from this one, as far as walked; guarded by [lock]. */
    private val descent = HashMap<SessionId, Boolean>()

    private val mutableState = MutableStateFlow(PermissionsState())

    override val state: StateFlow<PermissionsState> = mutableState.asStateFlow()

    override val events: Flow<Nothing> = emptyFlow()

    private val relist = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            yield()
            launch { session.state.collect(::follow) }
            session.state.map(::watchOf).distinctUntilChanged().collectLatest { watch -> if (watch != null) watch(watch) }
        }
    }

    override fun onIntent(intent: PermissionIntent) {
        when (intent) {
            is PermissionIntent.AllowOnce -> reply(intent.requestId, PermissionDecision.Once) { true }
            is PermissionIntent.AllowAlways -> edit(intent.requestId) {
                if (it.replying == null) it.copy(confirmingAlways = true, rejecting = false) else it
            }
            is PermissionIntent.ConfirmAlways -> reply(intent.requestId, PermissionDecision.Always) { it.confirmingAlways }
            is PermissionIntent.CancelAlways -> edit(intent.requestId) { it.copy(confirmingAlways = false) }
            is PermissionIntent.StartReject -> edit(intent.requestId) {
                if (it.replying == null) it.copy(rejecting = true, confirmingAlways = false) else it
            }
            is PermissionIntent.RejectMessageChanged -> edit(intent.requestId) {
                if (it.replying == null) it.copy(rejectMessage = intent.message) else it
            }
            is PermissionIntent.Reject -> reply(intent.requestId, PermissionDecision.Reject) { true }
            is PermissionIntent.CancelReject -> edit(intent.requestId) {
                if (it.replying == null) it.copy(rejecting = false, rejectMessage = "") else it
            }
            is PermissionIntent.DismissError -> edit(intent.requestId) { it.copy(error = null) }
            PermissionIntent.Retry -> relist.trySend(Unit)
        }
    }

    // Following the session

    private fun follow(chat: ChatSessionState) {
        val loaded = chat.session
        change {
            if (loaded == null) {
                // Another server, or the session is gone: nothing listed before may show or be answered.
                synchronized(lock) { descent.clear() }
                it.copy(own = emptyList(), children = emptyList(), error = null)
            } else {
                it.copy(own = loaded.permissions)
            }
        }
    }

    private fun watchOf(chat: ChatSessionState): Watch? {
        val loaded = chat.session ?: return null
        val synced = chat.availability == ChatAvailability.Live && chat.phase == SyncPhase.Live
        return if (synced) Watch(loaded.session.directory) else null
    }

    /**
     * Follows the subagents' requests on the bound server until [watch] changes. A connection that
     * closes ends it until the chat is live again; a stream that fails otherwise waits for a retry.
     */
    private suspend fun watch(watch: Watch) {
        while (true) {
            try {
                session.call { client ->
                    merge(client.events.events(TYPES).map { Signal.Event(it) }, relist.receiveAsFlow().map { Signal.Relist })
                        .collect { signal -> handle(client, watch, signal) }
                }
            } catch (e: ApiException) {
                if (e.error == ApiError.Closed) return
                change { it.copy(error = ChatFailure.of(e.error)) }
            }
            relist.receive()
        }
    }

    private suspend fun handle(client: OpencodeClient, watch: Watch, signal: Signal) {
        try {
            when (signal) {
                Signal.Relist -> list(client, watch)
                is Signal.Event -> when (val event = signal.event) {
                    is ServerEvent.Connected -> list(client, watch)
                    is ServerEvent.PermissionAsked -> asked(client, event.request)
                    is ServerEvent.PermissionReplied -> change {
                        it.copy(children = it.children.filterNot { request -> request.id == event.requestId }, settled = it.settled + event.requestId)
                    }
                    else -> Unit
                }
            }
        } catch (e: ApiException) {
            if (e.error == ApiError.Closed) throw e
            change { it.copy(error = ChatFailure.of(e.error)) }
        }
    }

    private suspend fun list(client: OpencodeClient, watch: Watch) {
        val listed = client.interactions.listPermissions(sessionId = null, location = watch.directory?.let(::Location))
        val kept = listed.filter { it.sessionId != session.sessionId && descends(client, it.sessionId) }
        change { if (loaded()) it.copy(children = kept, error = null) else it }
    }

    private suspend fun asked(client: OpencodeClient, request: PermissionRequest) {
        // This session's own requests come from its sync.
        if (request.sessionId == session.sessionId || !descends(client, request.sessionId)) return
        change {
            if (loaded()) it.copy(children = it.children.filterNot { known -> known.id == request.id } + request) else it
        }
    }

    /**
     * Whether [id] is a subagent session of this one, at any depth, by its `parentId` chain. Only a
     * definite answer is remembered for the sessions walked: the chain met this session, a known
     * session, a root, a missing session or a loop. A walk cut off at [MAX_DEPTH] says "no" this
     * time and remembers nothing, since a session on it may still be a subagent at a shallower depth.
     */
    private suspend fun descends(client: OpencodeClient, id: SessionId): Boolean {
        val walked = mutableListOf<SessionId>()
        var current: SessionId? = id
        var result = false
        var decided = false
        while (current != null && walked.size < MAX_DEPTH) {
            val at: SessionId = current
            if (at == session.sessionId) {
                result = walked.isNotEmpty()
                decided = true
                break
            }
            val known = synchronized(lock) { descent[at] }
            if (known != null) {
                result = known
                decided = true
                break
            }
            if (at in walked) {
                decided = true
                break
            }
            walked += at
            current = try {
                client.sessions.get(at).parentId
            } catch (e: ApiException) {
                if (e.error != ApiError.NotFound) throw e
                null
            }
        }
        // The walk may stop at the limit right on this session: that is a definite "yes".
        if (!decided && current == session.sessionId) {
            result = true
            decided = true
        }
        // A null parent is a root or a missing session: a definite "no".
        if (decided || current == null) synchronized(lock) { walked.forEach { descent[it] = result } }
        return result
    }

    private fun loaded(): Boolean = session.state.value.session != null

    // Replying

    /** Sends [decision] for [id] if the card is shown, free, and [allowed]; one reply at a time. */
    private fun reply(id: PermissionRequestId, decision: PermissionDecision, allowed: (CardUi) -> Boolean) {
        val (request, rejectMessage) = synchronized(lock) {
            val request = model.visible[id] ?: return
            val ui = model.ui[id] ?: CardUi()
            if (ui.replying != null || !allowed(ui)) return
            commit(model.copy(ui = model.ui + (id to ui.copy(replying = decision, error = null))))
            request to ui.rejectMessage
        }
        val message = if (decision == PermissionDecision.Reject) rejectMessage.trim().ifBlank { null } else null
        scope.launch { send(request, decision, message) }
    }

    private suspend fun send(request: PermissionRequest, decision: PermissionDecision, message: String?) {
        val failure = try {
            session.call { client -> client.interactions.replyPermission(request.sessionId, request.id, decision, message) }
            null
        } catch (e: ApiException) {
            ChatFailure.of(e.error)
        }
        change { current ->
            if (failure == null || failure == ChatFailure.NotFound) {
                // Settled, here or before: a second reply cannot work, so the card leaves.
                current.copy(children = current.children.filterNot { it.id == request.id }, settled = current.settled + request.id)
            } else {
                val ui = current.ui[request.id] ?: return@change current
                current.copy(ui = current.ui + (request.id to ui.copy(replying = null, error = PermissionError(decision, failure))))
            }
        }
    }

    // State

    private inline fun edit(id: PermissionRequestId, crossinline next: (CardUi) -> CardUi) {
        change {
            if (id !in it.visible) it else it.copy(ui = it.ui + (id to next(it.ui[id] ?: CardUi())))
        }
    }

    private inline fun change(next: (Model) -> Model) {
        synchronized(lock) { commit(next(model)) }
    }

    /** Keeps the model to what is shown, and publishes it; holds [lock]. */
    private fun commit(next: Model) {
        val visible = next.visible
        val order = next.order.filter { it in visible } + (next.own + next.children).map { it.id }.distinct().filter { it in visible && it !in next.order }
        val ui = next.ui.filterKeys { it in visible }
        model = next.copy(order = order, ui = ui)
        mutableState.value = PermissionsState(
            cards = order.map { id -> card(visible.getValue(id), ui[id] ?: CardUi()) },
            error = next.error,
        )
    }

    private fun card(request: PermissionRequest, ui: CardUi) = PermissionCardState(
        requestId = request.id,
        sessionId = request.sessionId,
        fromSubagent = request.sessionId != session.sessionId,
        action = request.action,
        resources = request.resources,
        message = request.message,
        save = request.save,
        confirmingAlways = ui.confirmingAlways,
        rejecting = ui.rejecting,
        rejectMessage = ui.rejectMessage,
        replying = ui.replying,
        error = ui.error,
    )

    private companion object {
        val TYPES = setOf("permission.asked", "permission.replied")

        /** How far up a `parentId` chain is walked; deeper counts as not a subagent of this one. */
        const val MAX_DEPTH = 32
    }
}
