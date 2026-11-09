package io.github.thewisekd.phopencode.feature.notify.logic.contract

import io.github.thewisekd.phopencode.core.storage_api.SecretReadResult
import io.github.thewisekd.phopencode.core.storage_api.SecretStore

/**
 * The notification setup, `NotifyRoute.Setup` (P11.9 logic, P11.10 screen): the ntfy topic and
 * server, the subscription link for the ntfy app, the notification service on the server of the
 * active connection, and a test notification.
 *
 * The user needs the ntfy app on the phone: the service publishes fixed text to the topic on the
 * ntfy server, and the app shows it. The screen explains that and opens [subscriptionLink] with the
 * system (the ntfy app handles `ntfy://`); when nothing handles it, the screen says the app is
 * missing.
 *
 * Rules the logic keeps:
 * - Loading reads [NotifySecrets.TOPIC] and [NotifySecrets.SERVER] from [SecretStore]. When there
 *   is no topic, or it is [SecretReadResult.Unreadable] or not a valid [NtfyTopic], a new one is
 *   made from the presenter's `SecureRandom` ([NtfyTopic.MIN_LENGTH] characters of
 *   [NtfyTopic.ALPHABET]) and stored. No stored server, or one that does not parse, is
 *   [NtfyServer.DEFAULT]. Then the service's status is read ([SetupStep.CheckStatus]).
 * - The topic and the link never reach a log, an event, a route or an exception message; the state
 *   prints them masked. Each `SecretValue` read is closed once used.
 * - [SetupIntent.EditServer] edits [serverText] and sets [serverProblem]; [SetupIntent.SaveServer]
 *   stores a valid edit and updates [server] and [subscriptionLink]. A service already installed
 *   keeps the old server until it is installed again.
 * - [SetupIntent.Install], [SetupIntent.Remove] and [SetupIntent.SendTest] run through
 *   [NotifyService] with the stored [server] and topic, one at a time ([operation]); install and
 *   remove read the status again afterwards. None runs while [busy].
 *
 * @property server What is stored.
 * @property serverText What the server field shows; follows [server] until the user types.
 * @property serverProblem Why [serverText] cannot be saved; null when it can.
 * @property subscriptionLink `ntfy://<host>/<topic>` for the stored server and topic; null until
 *   they are loaded. Holds the topic, so it prints masked.
 * @property status The service on the server; null until read, or when reading it failed.
 * @property operation The step running now, if any.
 * @property testSent The last test notification was taken by the ntfy server; cleared when another
 *   step starts.
 * @property error The last step failed, and why.
 */
data class SetupState(
    val loading: Boolean = true,
    val server: NtfyServer = NtfyServer.DEFAULT,
    val serverText: String = NtfyServer.DEFAULT.url,
    val serverProblem: NtfyServerProblem? = null,
    val subscriptionLink: String? = null,
    val status: NotifyServiceStatus? = null,
    val operation: SetupStep? = null,
    val testSent: Boolean = false,
    val error: SetupError? = null,
) {
    /** Loading, or a step is running: no other step may start. */
    val busy: Boolean
        get() = loading || operation != null

    /** The server field differs from what is stored. */
    val serverEdited: Boolean
        get() = serverText.trim() != server.url

    override fun toString(): String =
        "SetupState(loading=$loading, server=$server, serverText=***, serverProblem=$serverProblem, " +
            "subscriptionLink=${if (subscriptionLink == null) "null" else "***"}, status=$status, " +
            "operation=$operation, testSent=$testSent, error=$error)"
}

/** A step of the setup, to say what is running or what failed. */
enum class SetupStep {
    /** Reading, or making and storing, the topic and the server. */
    Load,

    SaveServer,

    CheckStatus,

    Install,

    Remove,

    SendTest,
}

/** [step] failed because of [reason]. */
data class SetupError(val step: SetupStep, val reason: NotifyFailure)

sealed interface SetupIntent {
    /** The server field edited. Prints masked: it may name the user's own server. */
    data class EditServer(val text: String) : SetupIntent {
        override fun toString(): String = "EditServer(***)"
    }

    /** Stores the edited server when it is valid. */
    data object SaveServer : SetupIntent

    /** Stores [NtfyServer.DEFAULT]. */
    data object ResetServer : SetupIntent

    /** Reads the service's status again. */
    data object RefreshStatus : SetupIntent

    data object Install : SetupIntent

    data object Remove : SetupIntent

    data object SendTest : SetupIntent

    /** Clears [SetupState.error]. */
    data object DismissError : SetupIntent

    data object Close : SetupIntent
}

sealed interface SetupEvent {
    data object Close : SetupEvent
}

interface SetupPresenter : Presenter<SetupState, SetupIntent, SetupEvent>
