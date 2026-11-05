package io.github.thewisekd.phopencode.feature.chat.logic.revert

import io.github.thewisekd.phopencode.core.api.ApiException
import io.github.thewisekd.phopencode.core.model.FileDiff
import io.github.thewisekd.phopencode.core.model.MessageId
import io.github.thewisekd.phopencode.core.model.SessionRevert
import io.github.thewisekd.phopencode.core.navigation.ChatRoute
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatAvailability
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatFailure
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatSession
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatSessionState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.RevertAction
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.RevertError
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.RevertEvent
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.RevertIntent
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.RevertPresenter
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.RevertState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.StagedRevert
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
 * The diff of the route's turn and its two-step revert (P7.12); the rules are on [RevertState].
 *
 * - The diff loads through [ChatSession.call] the first time the chat is
 *   [ChatAvailability.Live], and again on [RevertIntent.Retry] after a failed load. What is held
 *   is bounded by [TurnDiffBounds], and so are the files of a staged revert.
 * - [RevertState.staged] is always the sync's word: `SessionState.session.revert` of [session],
 *   bounded. Nothing here hides a revert the sync shows, or shows one it does not.
 * - One step at a time: a step is claimed before it starts, and only runs when [RevertState]
 *   allows it ([RevertState.canRevert], [RevertState.canConfirm], [RevertState.canCancel]). A
 *   failed step sets [RevertState.error], changes nothing, and frees the actions at once. A commit
 *   or cancel is sent only if, as its call starts, the sync still shows the revert it was claimed
 *   for (see [run] for the limit the API leaves).
 * - A step that worked stays [RevertState.pending] while it settles: until the sync's revert has
 *   changed since the step was claimed, or the chat stops being live and connected. That blocks
 *   the actions, never the data, so a second tap cannot act on what the sync has not reported yet.
 * - Leaving the page, by [RevertIntent.Close] or by the screen going away, leaves a staged revert
 *   staged on the server: nothing is undone or made final without the user asking. The chat's
 *   session keeps showing it, and this page, opened again, can confirm or cancel it.
 *
 * Nothing runs in the constructor: work starts in [scope] after a `yield()`. Nothing here logs;
 * the paths and patches are user data and print as counts.
 *
 * @param route The destination's route; a [ChatRoute.TurnDiff] names the turn.
 * @param scope The destination's scope; work started here stops with it.
 * @param session The destination's session, shared with its other presenters.
 */
class DefaultRevertPresenter(
    private val route: ChatRoute,
    private val scope: CoroutineScope,
    private val session: ChatSession,
) : RevertPresenter {
    /**
     * The step claimed in [RevertState.pending].
     *
     * @property changed The sync's revert changed since the step was claimed.
     * @property settling The step worked, and waits for [changed].
     */
    private data class Step(val changed: Boolean = false, val settling: Boolean = false)

    /** @property loadingNow A load is on its way. */
    private data class Model(
        val state: RevertState = RevertState(),
        val step: Step? = null,
        val loadingNow: Boolean = false,
    ) {
        override fun toString(): String = "Model(state=$state, step=$step, loadingNow=$loadingNow)"
    }

    private val turn: MessageId? = (route as? ChatRoute.TurnDiff)?.messageId?.let(::MessageId)

    private val lock = Any()

    private var model = Model()

    private val mutableState = MutableStateFlow(RevertState())

    override val state: StateFlow<RevertState> = mutableState.asStateFlow()

    private val eventChannel = Channel<RevertEvent>(Channel.BUFFERED)

    override val events: Flow<RevertEvent> = eventChannel.receiveAsFlow()

    init {
        scope.launch {
            yield()
            if (turn == null) {
                change { it.copy(state = it.state.copy(loading = false, loadError = ChatFailure.NotFound)) }
                return@launch
            }
            var started = false
            session.state.collect { chat ->
                follow(chat)
                if (!started && chat.availability == ChatAvailability.Live) {
                    started = true
                    load(turn)
                }
            }
        }
    }

    override fun onIntent(intent: RevertIntent) {
        when (intent) {
            RevertIntent.Revert -> step(RevertAction.Stage) { it.canRevert }
            RevertIntent.Confirm -> step(RevertAction.Commit) { it.canConfirm }
            RevertIntent.Cancel -> step(RevertAction.Cancel) { it.canCancel }
            RevertIntent.Retry -> if (turn != null) retry(turn)
            RevertIntent.DismissError -> change { it.copy(state = it.state.copy(error = null)) }
            RevertIntent.Close -> eventChannel.trySend(RevertEvent.Close)
        }
    }

    // Following the session

    private fun follow(chat: ChatSessionState) {
        val loaded = chat.session
        val enabled = loaded != null && chat.availability == ChatAvailability.Live
        val synced = loaded?.session?.revert?.let(::staged)
        change { current ->
            val step = current.step?.let { if (synced != current.state.staged) it.copy(changed = true) else it }
            val settled = step != null && step.settling && (step.changed || !enabled)
            current.copy(
                state = current.state.copy(
                    enabled = enabled,
                    staged = synced,
                    pending = if (settled) null else current.state.pending,
                ),
                step = if (settled) null else step,
            )
        }
    }

    /**
     * The bounded form of the sync's revert. Nothing of [revert] is kept here: the sync holds it,
     * and drops it when the revert is cleared.
     */
    private fun staged(revert: SessionRevert): StagedRevert =
        StagedRevert(revert.messageId, TurnDiffBounds.bound(revert.files), thisTurn = revert.messageId == turn)

    // Loading the diff

    private fun retry(turn: MessageId) {
        synchronized(lock) {
            if (model.loadingNow || model.state.loadError == null) return
            commit(model.copy(loadingNow = true, state = model.state.copy(loading = true, loadError = null)))
        }
        scope.launch { fetch(turn) }
    }

    private fun load(turn: MessageId) {
        synchronized(lock) {
            if (model.loadingNow) return
            commit(model.copy(loadingNow = true))
        }
        scope.launch { fetch(turn) }
    }

    private suspend fun fetch(turn: MessageId) {
        val result: Result<List<FileDiff>> = try {
            Result.success(TurnDiffBounds.bound(session.call { it.messages.turnDiff(session.sessionId, from = turn) }))
        } catch (e: ApiException) {
            Result.failure(e)
        }
        change { current ->
            val files = result.getOrNull()
            val failure = (result.exceptionOrNull() as? ApiException)?.let { ChatFailure.of(it.error) }
            current.copy(
                loadingNow = false,
                state = current.state.copy(
                    loading = false,
                    files = files ?: current.state.files,
                    loadError = failure,
                ),
            )
        }
    }

    // Steps

    private inline fun step(action: RevertAction, allowed: (RevertState) -> Boolean) {
        val turn = turn ?: return
        val target = synchronized(lock) {
            if (!allowed(model.state)) return
            commit(model.copy(state = model.state.copy(pending = action, error = null), step = Step()))
            // The revert a commit or cancel was asked for; null for a stage.
            model.state.staged?.messageId
        }
        scope.launch { run(action, turn, target) }
    }

    /**
     * Runs a claimed step. A commit or cancel is sent only if the sync still shows the revert it
     * was claimed for, checked on the session's own state as the call starts: `commitRevert` and `cancelRevert` name no
     * revert, so the server acts on whatever is staged then. A revert replaced in between frees
     * the step with no call and no error, and the page shows the new one.
     *
     * Known limit: a revert replaced on the server after that check, while the request is on its
     * way, is still committed or dropped; only the server could refuse it, and the API gives it
     * nothing to compare.
     */
    private suspend fun run(action: RevertAction, turn: MessageId, target: MessageId?) {
        val id = session.sessionId
        val sent = try {
            when (action) {
                RevertAction.Stage -> session.call { it.messages.stageRevert(id, turn) }.let { true }
                RevertAction.Commit -> session.call { client ->
                    if (stillStaged(target, thisTurn = true)) client.messages.commitRevert(id).let { true } else false
                }
                RevertAction.Cancel -> session.call { client ->
                    if (stillStaged(target, thisTurn = false)) client.messages.cancelRevert(id).let { true } else false
                }
            }
        } catch (e: ApiException) {
            val error = RevertError(action, ChatFailure.of(e.error))
            change { it.copy(state = it.state.copy(pending = null, error = error), step = null) }
            return
        }
        if (!sent) {
            change { it.copy(state = it.state.copy(pending = null), step = null) }
            return
        }
        change { current ->
            val step = current.step ?: Step()
            // The sync may have caught up before the answer came: then there is nothing to wait for.
            if (step.changed || !current.state.enabled) {
                current.copy(state = current.state.copy(pending = null), step = null)
            } else {
                current.copy(step = step.copy(settling = true))
            }
        }
        if (action == RevertAction.Commit) eventChannel.send(RevertEvent.Reverted)
    }

    /**
     * Whether the sync still shows the revert of [target], for this turn if [thisTurn], and the chat
     * is live. Reads [session]'s state itself, not the model, which follows it a step behind.
     */
    private fun stillStaged(target: MessageId?, thisTurn: Boolean): Boolean {
        val chat = session.state.value
        val revert = chat.session?.session?.revert
        return target != null &&
            chat.availability == ChatAvailability.Live &&
            revert?.messageId == target &&
            (!thisTurn || revert.messageId == turn)
    }

    // State

    private inline fun change(next: (Model) -> Model) {
        synchronized(lock) { commit(next(model)) }
    }

    /** Publishes [next]; holds [lock]. */
    private fun commit(next: Model) {
        model = next
        mutableState.value = next.state
    }
}
