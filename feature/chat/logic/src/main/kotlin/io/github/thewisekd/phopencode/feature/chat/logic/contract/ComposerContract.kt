package io.github.thewisekd.phopencode.feature.chat.logic.contract

import io.github.thewisekd.phopencode.core.api.PromptDelivery

/**
 * Writing and sending a prompt, and stopping the running work (P6.5, P6.6).
 *
 * Where it comes from: [running] from `SessionState.status` of the chat's [ChatSession]; [enabled]
 * while its availability is `Live` with the session loaded. Sending is `MessageApi.prompt` with the
 * draft as `Prompt.text`, through `ChatSession.call`; while the session runs, [delivery] goes along
 * as `Prompt.delivery` (steer interjects, queue waits for the end), else the server's default. The
 * draft is cleared only once the server accepted the prompt: a failed send keeps it. Stopping is
 * `SessionApi.stop`.
 *
 * @property draft The text as typed. Prints masked.
 * @property delivery How a prompt sent while running is handed over.
 * @property enabled The session is loaded and its server connected.
 * @property running The session's work is running.
 * @property sending A prompt is on its way; the draft waits.
 * @property stopping A stop is on its way.
 * @property error The last send or stop failed.
 */
data class ComposerState(
    val draft: String = "",
    val delivery: PromptDelivery = PromptDelivery.Steer,
    val enabled: Boolean = false,
    val running: Boolean = false,
    val sending: Boolean = false,
    val stopping: Boolean = false,
    val error: ComposerError? = null,
) {
    /** Whether [ComposerIntent.Send] would send; while running it sends with [delivery]. */
    val canSend: Boolean
        get() = enabled && !sending && draft.isNotBlank()

    /** Whether [ComposerIntent.Stop] would stop anything. */
    val canStop: Boolean
        get() = enabled && running && !stopping

    override fun toString(): String =
        "ComposerState(draft=***, delivery=$delivery, enabled=$enabled, running=$running, sending=$sending, " +
            "stopping=$stopping, error=$error)"
}

enum class ComposerAction {
    Send,
    Stop,
}

data class ComposerError(val action: ComposerAction, val reason: ChatFailure)

sealed interface ComposerIntent {
    data class DraftChanged(val draft: String) : ComposerIntent {
        override fun toString(): String = "DraftChanged(***)"
    }

    data class DeliveryChanged(val delivery: PromptDelivery) : ComposerIntent

    /** Sends [ComposerState.draft], trimmed. */
    data object Send : ComposerIntent

    data object Stop : ComposerIntent

    data object DismissError : ComposerIntent
}

sealed interface ComposerEvent {
    /** The server accepted a prompt; the chat brings the timeline back to the newest item. */
    data object Sent : ComposerEvent
}

interface ComposerPresenter : Presenter<ComposerState, ComposerIntent, ComposerEvent>
