package io.github.thewisekd.phopencode.feature.chat.logic.composer

import io.github.thewisekd.phopencode.core.api.ApiException
import io.github.thewisekd.phopencode.core.api.OpencodeClient
import io.github.thewisekd.phopencode.core.api.Prompt
import io.github.thewisekd.phopencode.core.model.SessionStatus
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatAvailability
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatFailure
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatSession
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatSessionState
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ComposerAction
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ComposerError
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ComposerEvent
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ComposerIntent
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ComposerPresenter
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ComposerState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PromptAttachment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * The composer of one chat (P6.5): the draft, sending it, and stopping the running work.
 *
 * - [ComposerState.enabled] while the chat's availability is `Live` with the session loaded, and
 *   [ComposerState.running] while its status is running or retrying.
 * - **Send** hands the trimmed draft to `MessageApi.prompt` through [ChatSession.call], so only to
 *   the server the chat is bound to. While the session runs, [ComposerState.delivery] goes along
 *   (steer interjects, queue waits for the end of the running work); while idle no delivery is sent
 *   and the server's default applies. One tap sends once: [ComposerState.sending] is set before the
 *   call starts, and a Send while it is set is ignored. Nothing is retried, since a prompt is not
 *   safe to repeat. The draft cannot change while the prompt is on its way and is cleared only once
 *   the server accepted it, with [ComposerEvent.Sent]; a failed send keeps it and shows the error.
 * - **Attachments** go along as the prompt's files, agents and skills, by kind and in the order
 *   attached; one already held is not added again. Like the draft, they cannot change while a
 *   prompt is on its way, and are cleared only once the server accepted it.
 * - **Stop** calls `SessionApi.stop` through [ChatSession.call], once, only while something runs.
 *
 * Nothing runs in the constructor: following the session starts after a `yield()`. Nothing here
 * logs; the draft is user data and [ComposerState] prints it masked.
 *
 * @param session The chat's session; prompts and stops go through `ChatSession.call`, so only to
 *   the server the chat is bound to.
 * @param scope The screen's scope; the work started here stops with it.
 */
class DefaultComposerPresenter(
    private val session: ChatSession,
    private val scope: CoroutineScope,
) : ComposerPresenter {
    private val mutableState = MutableStateFlow(ComposerState())

    override val state: StateFlow<ComposerState> = mutableState.asStateFlow()

    private val eventChannel = Channel<ComposerEvent>(Channel.UNLIMITED)

    override val events: Flow<ComposerEvent> = eventChannel.receiveAsFlow()

    init {
        scope.launch {
            yield()
            session.state.collect(::follow)
        }
    }

    override fun onIntent(intent: ComposerIntent) {
        when (intent) {
            is ComposerIntent.DraftChanged -> mutableState.update { if (it.sending) it else it.copy(draft = intent.draft) }
            is ComposerIntent.Attach -> mutableState.update {
                if (it.sending || intent.attachment in it.attachments) {
                    it
                } else {
                    it.copy(attachments = it.attachments + intent.attachment)
                }
            }
            is ComposerIntent.Detach -> mutableState.update {
                if (it.sending) it else it.copy(attachments = it.attachments - intent.attachment)
            }
            is ComposerIntent.DeliveryChanged -> mutableState.update { it.copy(delivery = intent.delivery) }
            ComposerIntent.Send -> send()
            ComposerIntent.Stop -> stop()
            ComposerIntent.DismissError -> mutableState.update { it.copy(error = null) }
        }
    }

    private fun follow(chat: ChatSessionState) {
        val loaded = chat.session
        mutableState.update {
            it.copy(
                enabled = chat.availability == ChatAvailability.Live && loaded != null,
                running = loaded?.status.let { status -> status == SessionStatus.Running || status is SessionStatus.Retrying },
            )
        }
    }

    private fun send() {
        val claimed = claim({ it.canSend }) { it.copy(sending = true, error = null) } ?: return
        val attached = claimed.attachments
        val prompt = Prompt(
            text = claimed.draft.trim(),
            files = attached.filterIsInstance<PromptAttachment.File>().map { it.path },
            agents = attached.filterIsInstance<PromptAttachment.Agent>().map { it.id },
            skills = attached.filterIsInstance<PromptAttachment.Skill>().map { it.name },
            delivery = claimed.delivery.takeIf { claimed.running },
        )
        scope.launch {
            val failure = attempt { client -> client.messages.prompt(session.sessionId, prompt) }
            mutableState.update {
                if (failure == null) {
                    it.copy(draft = "", attachments = emptyList(), sending = false)
                } else {
                    it.copy(sending = false, error = ComposerError(ComposerAction.Send, failure))
                }
            }
            // Unlimited, so this never fails while the presenter lives: no accepted prompt goes unannounced.
            if (failure == null) eventChannel.trySend(ComposerEvent.Sent)
        }
    }

    private fun stop() {
        claim({ it.canStop }) { it.copy(stopping = true, error = null) } ?: return
        scope.launch {
            val failure = attempt { client -> client.sessions.stop(session.sessionId) }
            mutableState.update {
                it.copy(stopping = false, error = failure?.let { reason -> ComposerError(ComposerAction.Stop, reason) } ?: it.error)
            }
        }
    }

    /**
     * Sets [next] if [allowed] holds, atomically, so that two taps cannot both start the work.
     * Returns the state the work starts from, or null when it is not allowed.
     */
    private inline fun claim(allowed: (ComposerState) -> Boolean, next: (ComposerState) -> ComposerState): ComposerState? {
        while (true) {
            val current = mutableState.value
            if (!allowed(current)) return null
            if (mutableState.compareAndSet(current, next(current))) return current
        }
    }

    /** Runs [block] on the bound server; null when it succeeded, else why it failed. */
    private suspend fun attempt(block: suspend (OpencodeClient) -> Unit): ChatFailure? =
        try {
            session.call(block)
            null
        } catch (e: ApiException) {
            ChatFailure.of(e.error)
        }
}
