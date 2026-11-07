package io.github.thewisekd.phopencode.feature.chat.logic.actions

import io.github.thewisekd.phopencode.core.api.ApiException
import io.github.thewisekd.phopencode.core.model.Message
import io.github.thewisekd.phopencode.core.model.MessageId
import io.github.thewisekd.phopencode.core.model.SessionStatus
import io.github.thewisekd.phopencode.core.navigation.ChatRoute
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

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
 *   is queued or running, judged from the sync as the answer comes and on every update after: it
 *   ends when its message is `completed` or `failed`, or when it is not queued and the session's
 *   status is idle (see [tracked]), so it never outlives the session's work. No second
 *   compaction is asked for meanwhile.
 * - A fork emits [ActionsEvent.OpenSession] only with the session the server returned.
 * - [ActionsState.directory] is the loaded session's folder, kept as long as the session is kept
 *   (through a reconnect too); a blank one counts as none. [ActionsIntent.OpenFiles] and
 *   [ActionsIntent.OpenGit] emit their event for it, and nothing while it is null.
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
     * @property busySeen The session was busy at or after the server's answer.
     */
    private data class Compaction(val id: MessageId, val busySeen: Boolean = false) {
        override fun toString(): String = "Compaction(busySeen=$busySeen)"
    }

    private data class Model(val state: ActionsState = ActionsState(), val compaction: Compaction? = null) {
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
                if (allowed(current.state, intent.action)) {
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
        }
    }

    override fun toString(): String = synchronized(lock) { "DefaultActionsPresenter($model)" }

    /** Whether [action] may start now: none runs, and no compaction is already on its way. */
    private fun allowed(state: ActionsState, action: SessionAction): Boolean =
        state.canAct && !(action == SessionAction.Compact && state.compacting)

    // Following the session

    private fun follow(chat: ChatSessionState) {
        val enabled = chat.session != null && chat.availability == ChatAvailability.Live
        val directory = chat.session?.session?.directory?.takeIf { it.isNotBlank() }
        change { current ->
            current.withState { copy(enabled = enabled, menuOpen = menuOpen && enabled, directory = directory) }
                .tracked(chat)
        }
    }

    /**
     * [compaction] against the sync of [chat]; drops it once ended, and sets compacting to match.
     * It ended when its message is `completed` or `failed`, or when it is not queued and the
     * session is idle: either nothing of it is left in the sync (never shown, or added and removed
     * already), or the session was busy since and is idle again, which also ends one whose end the
     * message missed. A queued one holds, idle or not: it has not been delivered yet.
     */
    private fun Model.tracked(chat: ChatSessionState): Model {
        val current = compaction ?: return withState { copy(compacting = false) }
        val loaded = chat.session ?: return this
        val message = loaded.messages.firstOrNull { it.id == current.id }
        val queued = loaded.inbox.any { it.id == current.id }
        val idle = loaded.status !is SessionStatus.Running && loaded.status !is SessionStatus.Retrying
        val next = when {
            message is Message.Compaction && message.status in ENDED -> null
            queued -> current.copy(busySeen = current.busySeen || !idle)
            idle && (message == null || current.busySeen) -> null
            else -> current.copy(busySeen = current.busySeen || !idle)
        }
        return copy(compaction = next, state = state.copy(compacting = next != null))
    }

    // Running an action

    private fun confirm() {
        val action = synchronized(lock) {
            val action = model.state.confirming ?: return
            if (!allowed(model.state, action)) return
            commit(model.withState { copy(confirming = null, running = action, error = null, menuOpen = false) })
            action
        }
        scope.launch { run(action) }
    }

    private suspend fun run(action: SessionAction) {
        val id = session.sessionId
        try {
            when (action) {
                SessionAction.Compact -> {
                    val item = session.call { it.sessions.compact(id) }
                    change { current ->
                        current.copy(compaction = Compaction(item.id))
                            .withState { copy(running = null) }
                            .tracked(session.state.value)
                    }
                    eventChannel.send(ActionsEvent.CompactionStarted)
                }
                is SessionAction.Fork -> {
                    val forked = session.call { it.sessions.fork(id, before = action.messageId) }
                    change { it.withState { copy(running = null) } }
                    eventChannel.send(ActionsEvent.OpenSession(forked.id))
                }
            }
        } catch (e: ApiException) {
            val error = ActionError(action, ChatFailure.of(e.error))
            change { it.withState { copy(running = null, error = error) } }
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
