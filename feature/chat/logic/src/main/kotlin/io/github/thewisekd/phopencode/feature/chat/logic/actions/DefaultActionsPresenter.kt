package io.github.thewisekd.phopencode.feature.chat.logic.actions

import io.github.thewisekd.phopencode.core.api.ApiException
import io.github.thewisekd.phopencode.core.api.CreateSessionRequest
import io.github.thewisekd.phopencode.core.api.Location
import io.github.thewisekd.phopencode.core.model.Message
import io.github.thewisekd.phopencode.core.model.MessageId
import io.github.thewisekd.phopencode.core.model.SessionStatus
import io.github.thewisekd.phopencode.core.navigation.ChatRoute
import io.github.thewisekd.phopencode.core.session.sync.SyncPhase
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatAvailability
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatFailure
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatSession
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatSessionState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.ActionError
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.ActionsEvent
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.ActionsIntent
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.ActionsPresenter
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.ActionsState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SessionAction
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * How long an accepted compaction the sync has not shown yet holds [ActionsState.compacting] at
 * most, so a hold whose event was missed (an item queued and cancelled between two updates the
 * presenter saw) cannot stick. Its event comes within seconds while the sync is live.
 */
internal val UNSEEN_COMPACTION_LIMIT: Duration = 60.seconds

/**
 * The session's actions (P7.14): compact, fork, a turn's changes; the rules are on [ActionsState].
 *
 * - Only a [ChatRoute.Chat] acts, and only while its session is loaded and [ChatAvailability.Live]:
 *   that is [ActionsState.enabled].
 * - Compact and fork ask first: [ActionsIntent.Request] closes the menu and sets
 *   [ActionsState.confirming]; [ActionsIntent.Confirm] claims [ActionsState.running] and sends it
 *   through [ChatSession.call]. One action at a time: nothing is asked or sent while one runs,
 *   and a Confirm the state does not allow keeps the question and sends nothing.
 * - A failed call sets [ActionsState.error], frees the actions, and leaves the chat where it is.
 * - A compaction the server accepted emits [ActionsEvent.CompactionStarted] and holds
 *   [ActionsState.compacting] while that compaction (named by the inbox item the server returned)
 *   is queued or running, judged from the sync as the answer comes and on every update after.
 *   Until the sync has shown it (its inbox item or its `Message.Compaction`, seen while the call
 *   ran or after), it holds even while the session is idle, as its event may simply come later;
 *   it ends unseen when the session was busy (from when the call was sent) and is idle again,
 *   when a resync of the loaded session holds no trace of it, when the chat is no longer live, or
 *   at the latest after [UNSEEN_COMPACTION_LIMIT]. Once seen it ends when its
 *   message is `completed` or `failed`, or when it is not queued and the session's status is
 *   idle (see [tracked]), so it never outlives the session's work. No second compaction is asked
 *   for meanwhile.
 * - A fork emits [ActionsEvent.OpenSession] only with the session the server returned.
 * - [ActionsIntent.NewSession] asks nothing first: while an action could start and the folder is
 *   known, it claims [ActionsState.running] and creates a session there through [ChatSession.call],
 *   with the folder as its only setting; then [ActionsEvent.OpenSession] with the new session.
 * - [ActionsState.undoTurn] and [ActionsState.redoTurn] follow the loaded session's messages and
 *   staged revert (`Session.revert`). A staged revert of a message not among those held offers no
 *   turn to undo: it may already cover all of them.
 * - [ActionsState.directory] is the loaded session's folder, kept as long as the session is kept
 *   (through a reconnect too); a blank one counts as none. [ActionsIntent.OpenFiles] and
 *   [ActionsIntent.OpenGit] emit their event for it, and nothing while it is null.
 * - [ActionsIntent.OpenPreview] closes the menu and emits [ActionsEvent.OpenPreview] only while
 *   [ActionsState.enabled]: the preview reaches the server through the live connection.
 *
 * Nothing runs in the constructor: work starts in [scope] after a `yield()`. Nothing here logs,
 * and the state prints no message IDs.
 *
 * Known limit: a compaction is followed only by the presenter that asked for it; one started
 * elsewhere, or before this chat was opened, does not set [ActionsState.compacting].
 *
 * @param route The destination's route.
 * @param scope The destination's scope; work started here stops with it.
 * @param session The destination's session, shared with its other presenters.
 */
class DefaultActionsPresenter(
    private val route: ChatRoute,
    private val scope: CoroutineScope,
    private val session: ChatSession,
) : ActionsPresenter {
    /**
     * The compaction this presenter started.
     *
     * @property id The inbox item the server returned, which is also its message's ID.
     * @property busySeen The session was busy since the call was sent: as it was sent, while it ran,
     *   at the server's answer, or after.
     * @property seen The sync has shown it: its inbox item or its message.
     * @property resyncing While not yet [seen], the sync left [SyncPhase.Live] since the answer: the
     *   next live state is a fresh fetch of the session.
     */
    private data class Compaction(
        val id: MessageId,
        val busySeen: Boolean = false,
        val seen: Boolean = false,
        val resyncing: Boolean = false,
    ) {
        override fun toString(): String = "Compaction(busySeen=$busySeen, seen=$seen, resyncing=$resyncing)"
    }

    /**
     * @property traces While a compaction call runs, the inbox items and compaction messages the
     *   sync showed since it started; null otherwise. Lets an event that beat the answer count.
     * @property busyWhileCalling While a compaction call runs, the session was busy as it was sent
     *   or since: a compaction that ran and ended before the answer still ends once idle.
     */
    private data class Model(
        val state: ActionsState = ActionsState(),
        val compaction: Compaction? = null,
        val traces: Set<MessageId>? = null,
        val busyWhileCalling: Boolean = false,
    ) {
        override fun toString(): String =
            "Model(enabled=${state.enabled}, menuOpen=${state.menuOpen}, confirming=${state.confirming != null}, " +
                "running=${state.running != null}, compacting=${state.compacting}, error=${state.error?.reason})"
    }

    private val acts = route is ChatRoute.Chat

    private val lock = Any()

    private var model = Model()

    private val mutableState = MutableStateFlow(ActionsState())

    override val state: StateFlow<ActionsState> = mutableState.asStateFlow()

    private val eventChannel = Channel<ActionsEvent>(Channel.UNLIMITED)

    override val events: Flow<ActionsEvent> = eventChannel.receiveAsFlow()

    init {
        scope.launch {
            yield()
            if (!acts) return@launch
            session.state.collect(::follow)
        }
    }

    override fun onIntent(intent: ActionsIntent) {
        when (intent) {
            ActionsIntent.OpenMenu -> change { if (it.state.enabled) it.withState { copy(menuOpen = true) } else it }
            ActionsIntent.CloseMenu -> change { it.withState { copy(menuOpen = false) } }
            is ActionsIntent.Request -> change { current ->
                if (intent.action != SessionAction.NewSession && allowed(current.state, intent.action)) {
                    current.withState { copy(menuOpen = false, confirming = intent.action) }
                } else {
                    current.withState { copy(menuOpen = false) }
                }
            }
            ActionsIntent.Confirm -> confirm()
            ActionsIntent.Cancel -> change { it.withState { copy(confirming = null) } }
            is ActionsIntent.ShowTurnChanges -> if (acts) eventChannel.trySend(ActionsEvent.OpenTurnDiff(intent.messageId))
            ActionsIntent.DismissError -> change { it.withState { copy(error = null) } }
            ActionsIntent.OpenFiles -> state.value.directory?.let { eventChannel.trySend(ActionsEvent.OpenFiles(it)) }
            ActionsIntent.OpenGit -> state.value.directory?.let { eventChannel.trySend(ActionsEvent.OpenGit(it)) }
            ActionsIntent.NewSession -> newSession()
            ActionsIntent.OpenPreview -> openPreview()
        }
    }

    override fun toString(): String = synchronized(lock) { "DefaultActionsPresenter($model)" }

    /** Whether [action] may start now: none runs, and no compaction is already on its way. */
    private fun allowed(state: ActionsState, action: SessionAction): Boolean =
        state.canAct && !(action == SessionAction.Compact && state.compacting)

    /** Closes the menu and opens the preview, only while the session is loaded and connected. */
    private fun openPreview() {
        val enabled = synchronized(lock) {
            commit(model.withState { copy(menuOpen = false) })
            model.state.enabled
        }
        if (enabled) eventChannel.trySend(ActionsEvent.OpenPreview)
    }

    // Following the session

    private fun follow(chat: ChatSessionState) {
        val enabled = chat.session != null && chat.availability == ChatAvailability.Live
        val directory = chat.session?.session?.directory?.takeIf { it.isNotBlank() }
        val staged = chat.session?.session?.revert?.messageId
        val messages = chat.session?.messages.orEmpty()
        // The prompts still shown: those before the staged revert's, all of them without one. A
        // revert whose message is not among those held may reach back past all of them, so none is
        // known to be safe to undo: nothing is offered, and /redo still opens the staged revert.
        val prompts = when {
            staged != null && messages.none { it.id == staged } -> emptyList()
            else -> messages.filterIsInstance<Message.User>().takeWhile { it.id != staged }
        }
        change { current ->
            current.withState {
                copy(
                    enabled = enabled,
                    menuOpen = menuOpen && enabled,
                    directory = directory,
                    undoTurn = prompts.lastOrNull()?.id,
                    redoTurn = staged,
                )
            }.traced(chat).tracked(chat)
        }
    }

    /**
     * Adds what [chat] shows of compactions to [Model.traces], and whether the session is busy to
     * [Model.busyWhileCalling], while a compaction call runs.
     */
    private fun Model.traced(chat: ChatSessionState): Model {
        val known = traces ?: return this
        val loaded = chat.session ?: return this
        val shown = loaded.inbox.map { it.id } + loaded.messages.filterIsInstance<Message.Compaction>().map { it.id }
        val busy = busyWhileCalling || loaded.status.busy
        if (known.containsAll(shown) && busy == busyWhileCalling) return this
        return copy(traces = known + shown, busyWhileCalling = busy)
    }

    /**
     * [compaction] against the sync of [chat]; drops it once ended, and sets compacting to match.
     * One the sync has not shown yet is judged by [unseen], and ends at once when the chat is no
     * longer live. Once shown, it ended when its message is `completed` or `failed`, or when it is
     * not queued and the session is idle: either nothing of it is left in the sync (added and
     * removed already), or the session was busy since and is idle again, which also ends one whose
     * end the message missed. A queued one holds, idle or not: it has not been delivered yet.
     */
    private fun Model.tracked(chat: ChatSessionState): Model {
        var current = compaction ?: return withState { copy(compacting = false) }
        if (!current.seen) {
            val loaded = chat.session
            if (loaded == null || chat.availability != ChatAvailability.Live) return ended()
            val shown = loaded.inbox.any { it.id == current.id } ||
                loaded.messages.any { it is Message.Compaction && it.id == current.id }
            if (!shown) return copy(compaction = unseen(current, chat, loaded.status)).compactingMatches()
            current = current.copy(seen = true)
        }
        val loaded = chat.session ?: return copy(compaction = current)
        val message = loaded.messages.firstOrNull { it.id == current.id }
        val queued = loaded.inbox.any { it.id == current.id }
        val idle = loaded.status !is SessionStatus.Running && loaded.status !is SessionStatus.Retrying
        val next = when {
            message is Message.Compaction && message.status in ENDED -> null
            queued -> current.copy(busySeen = current.busySeen || !idle)
            idle && (message == null || current.busySeen) -> null
            else -> current.copy(busySeen = current.busySeen || !idle)
        }
        return copy(compaction = next).compactingMatches()
    }

    /**
     * [current], not yet shown by the sync of [chat], against that sync: null once it ended. It
     * holds through idle updates, since its event may only be late; it ends once the session was
     * busy and is idle again, or once a resync that started after the answer is live with no trace
     * of it. Nothing is judged while a resync runs.
     */
    private fun unseen(current: Compaction, chat: ChatSessionState, status: SessionStatus): Compaction? {
        val idle = status !is SessionStatus.Running && status !is SessionStatus.Retrying
        return when {
            chat.phase != SyncPhase.Live -> current.copy(resyncing = true)
            current.resyncing -> null
            idle && current.busySeen -> null
            else -> current.copy(busySeen = current.busySeen || !idle)
        }
    }

    private val SessionStatus.busy: Boolean
        get() = this is SessionStatus.Running || this is SessionStatus.Retrying

    private fun Model.ended(): Model = copy(compaction = null).compactingMatches()

    private fun Model.compactingMatches(): Model = withState { copy(compacting = compaction != null) }

    // Running an action

    private fun confirm() {
        val action = synchronized(lock) {
            val action = model.state.confirming ?: return
            if (!allowed(model.state, action)) return
            val watched = if (action == SessionAction.Compact) {
                // Busy as it is sent counts: the session may be idle again before the answer.
                model.copy(traces = emptySet(), busyWhileCalling = session.state.value.session?.status?.busy == true)
            } else {
                model
            }
            commit(watched.withState { copy(confirming = null, running = action, error = null, menuOpen = false) })
            action
        }
        scope.launch { run(action) }
    }

    private fun newSession() {
        if (!acts) return
        val directory = synchronized(lock) {
            val directory = model.state.directory
            if (!model.state.canAct || directory == null) return
            commit(model.withState { copy(running = SessionAction.NewSession, error = null, menuOpen = false) })
            directory
        }
        scope.launch { run(SessionAction.NewSession, directory) }
    }

    /** Runs a claimed [action]; [directory] is where a new session goes. */
    private suspend fun run(action: SessionAction, directory: String? = null) {
        val id = session.sessionId
        try {
            when (action) {
                SessionAction.Compact -> {
                    val item = session.call { it.sessions.compact(id) }
                    change { current ->
                        val seen = current.traces.orEmpty().contains(item.id)
                        val compaction = Compaction(item.id, busySeen = current.busyWhileCalling, seen = seen)
                        current.copy(compaction = compaction, traces = null, busyWhileCalling = false)
                            .withState { copy(running = null) }
                            .tracked(session.state.value)
                    }
                    eventChannel.send(ActionsEvent.CompactionStarted)
                    scope.launch { endUnseen(item.id) }
                }
                is SessionAction.Fork -> {
                    val forked = session.call { it.sessions.fork(id, before = action.messageId) }
                    change { it.withState { copy(running = null) } }
                    eventChannel.send(ActionsEvent.OpenSession(forked.id))
                }
                SessionAction.NewSession -> {
                    val request = CreateSessionRequest(location = directory?.let(::Location))
                    val created = session.call { it.sessions.create(request) }
                    change { it.withState { copy(running = null) } }
                    eventChannel.send(ActionsEvent.OpenSession(created.id))
                }
            }
        } catch (e: ApiException) {
            val error = ActionError(action, ChatFailure.of(e.error))
            change { current ->
                current.copy(traces = null, busyWhileCalling = false).withState { copy(running = null, error = error) }
            }
        }
    }

    /** Ends the compaction [id] if the sync still has not shown it after [UNSEEN_COMPACTION_LIMIT]. */
    private suspend fun endUnseen(id: MessageId) {
        delay(UNSEEN_COMPACTION_LIMIT)
        change { current ->
            val compaction = current.compaction
            if (compaction != null && compaction.id == id && !compaction.seen) current.ended() else current
        }
    }

    // State

    private inline fun Model.withState(next: ActionsState.() -> ActionsState): Model = copy(state = state.next())

    private inline fun change(next: (Model) -> Model) {
        synchronized(lock) { commit(next(model)) }
    }

    /** Publishes [next]; holds [lock]. */
    private fun commit(next: Model) {
        model = next
        mutableState.value = next.state
    }

    private companion object {
        /** The server's word for a compaction that ended: `Message.Compaction.status`. */
        val ENDED = setOf("completed", "failed")
    }
}
