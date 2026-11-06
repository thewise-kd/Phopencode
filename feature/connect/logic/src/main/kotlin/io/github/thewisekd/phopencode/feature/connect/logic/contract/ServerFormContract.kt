package io.github.thewisekd.phopencode.feature.connect.logic.contract

import io.github.thewisekd.phopencode.core.model.ServerProfile
import io.github.thewisekd.phopencode.core.model.ServerProfileId
import io.github.thewisekd.phopencode.core.storage_api.SshKeyEntry
import io.github.thewisekd.phopencode.core.storage_api.SshKeyId

enum class ServerFormAuthMethod { Password, Key }

enum class ServerFormField { Name, Host, Port, Username, Password, Key, OpencodePort }

enum class ServerFormFieldError {
    /** Empty, or only spaces. */
    Required,

    /** Not a host name or address, not a port in 1..65535, or not a user name SSH accepts. */
    Invalid,
}

enum class ServerFormError {
    /** The server to edit, or the keys, could not be read. */
    LoadFailed,

    /** The server to edit no longer exists. */
    NotFound,

    /** The server or one of its secrets could not be written; nothing was saved. */
    SaveFailed,
}

/**
 * Adding or editing a server (P4.6, P4.7). Field values are the text as typed; the presenter
 * validates on [ServerFormIntent.Save].
 *
 * Secrets never enter the state: the screen keeps the password fields' text and sends it with
 * [ServerFormIntent.PasswordChanged], and the state only says whether one was entered. Printing
 * leaves out the name, host and user, as [ServerProfile] does.
 *
 * @property isNew A new server, or an existing one being edited.
 * @property storedPassword The server already has a saved login password; leaving the field empty
 *   keeps it.
 * @property keys The keys to log in with, from the key index.
 * @property selectedKey The key for [ServerFormAuthMethod.Key].
 * @property storedOpencodePassword The server has a saved `opencode serve` password, for a server
 *   this app did not start.
 * @property opencodePort The loopback port of the `opencode serve` to use, as typed; empty leaves it
 *   to automatic detection.
 * @property fieldErrors Filled by the last save attempt; empty when the form is valid.
 */
data class ServerFormState(
    val isNew: Boolean,
    val loading: Boolean = true,
    val name: String = "",
    val host: String = "",
    val port: String = ServerProfile.DEFAULT_SSH_PORT.toString(),
    val username: String = "",
    val authMethod: ServerFormAuthMethod = ServerFormAuthMethod.Password,
    val passwordEntered: Boolean = false,
    val storedPassword: Boolean = false,
    val keys: List<SshKeyEntry> = emptyList(),
    val selectedKey: SshKeyId? = null,
    val opencodePasswordEntered: Boolean = false,
    val storedOpencodePassword: Boolean = false,
    val opencodePort: String = "",
    val fieldErrors: Map<ServerFormField, ServerFormFieldError> = emptyMap(),
    val saving: Boolean = false,
    val error: ServerFormError? = null,
) {
    override fun toString(): String =
        "ServerFormState(isNew=$isNew, loading=$loading, authMethod=$authMethod, " +
            "fieldErrors=$fieldErrors, saving=$saving, error=$error)"
}

sealed interface ServerFormIntent {
    data class NameChanged(val name: String) : ServerFormIntent {
        override fun toString(): String = "NameChanged(***)"
    }

    data class HostChanged(val host: String) : ServerFormIntent {
        override fun toString(): String = "HostChanged(***)"
    }

    data class PortChanged(val port: String) : ServerFormIntent

    /** The `opencode serve` port as typed; empty means automatic detection. */
    data class OpencodePortChanged(val port: String) : ServerFormIntent

    data class UsernameChanged(val username: String) : ServerFormIntent {
        override fun toString(): String = "UsernameChanged(***)"
    }

    data class AuthMethodChanged(val method: ServerFormAuthMethod) : ServerFormIntent

    /** The login password as typed. Empty clears what was entered, not the saved one. */
    data class PasswordChanged(val password: String) : ServerFormIntent {
        override fun toString(): String = "PasswordChanged(***)"
    }

    data class KeySelected(val id: SshKeyId) : ServerFormIntent

    /** The `opencode serve` password as typed. Empty clears what was entered, not the saved one. */
    data class OpencodePasswordChanged(val password: String) : ServerFormIntent {
        override fun toString(): String = "OpencodePasswordChanged(***)"
    }

    /** Removes the saved `opencode serve` password on save. */
    data object ClearOpencodePassword : ServerFormIntent

    /** Opens key management, to add a key to log in with. */
    data object ManageKeys : ServerFormIntent

    /** Validates, writes the secrets to `SecretStore`, then the profile. */
    data object Save : ServerFormIntent

    data object Cancel : ServerFormIntent

    data object DismissError : ServerFormIntent
}

sealed interface ServerFormEvent {
    data class Saved(val id: ServerProfileId) : ServerFormEvent

    data object OpenKeys : ServerFormEvent

    data object Close : ServerFormEvent
}

interface ServerFormPresenter : Presenter<ServerFormState, ServerFormIntent, ServerFormEvent>
