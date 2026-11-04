package io.github.thewisekd.phopencode.feature.connect.logic.contract

import io.github.thewisekd.phopencode.core.storage_api.SshKeyEntry
import io.github.thewisekd.phopencode.core.storage_api.SshKeyId

enum class KeysError {
    LoadFailed,
    GenerateFailed,

    /** The file is not a private key, or is damaged. */
    ImportMalformed,

    /** A real key file of a type or encryption the app does not read. */
    ImportUnsupported,

    WrongPassphrase,

    /** A saved server still logs in with the key, so it was not deleted. */
    KeyInUse,

    /** The key or its secrets could not be written. */
    SaveFailed,

    DeleteFailed,
}

/**
 * The user's SSH keys (P4.8, P4.9). The private half is never in the state, and never leaves
 * `SecretStore` except to log in.
 *
 * @property keys By name, as `SshKeyIndex` sorts them; each shows its type and fingerprint.
 * @property busy A key is being generated, imported or deleted.
 * @property importAwaitingPassphrase An imported key is encrypted and waits for
 *   [KeysIntent.SubmitPassphrase] or [KeysIntent.CancelImport].
 * @property pendingDelete The key whose deletion waits for the user's confirmation.
 */
data class KeysState(
    val loading: Boolean = true,
    val keys: List<SshKeyEntry> = emptyList(),
    val busy: Boolean = false,
    val importAwaitingPassphrase: Boolean = false,
    val pendingDelete: SshKeyEntry? = null,
    val error: KeysError? = null,
)

sealed interface KeysIntent {
    /**
     * Generates an ed25519 key. [passphrase] null or empty stores it without one; the key is
     * encrypted in `SecretStore` either way.
     */
    data class Generate(val name: String, val passphrase: String? = null) : KeysIntent {
        override fun toString(): String = "Generate(passphrase=${if (passphrase == null) "null" else "***"})"
    }

    /**
     * A private key file the user picked. The presenter owns [content] from here on and wipes it;
     * the screen keeps no copy. Not a data class: comparing would compare the key.
     *
     * @property name The key's name in the list, for example the file name.
     */
    class Import(val name: String, val content: ByteArray) : KeysIntent {
        override fun toString(): String = "Import(***)"
    }

    /** The passphrase of the key waiting in [KeysState.importAwaitingPassphrase]. */
    data class SubmitPassphrase(val passphrase: String) : KeysIntent {
        override fun toString(): String = "SubmitPassphrase(***)"
    }

    /** Drops the key waiting for its passphrase, and wipes it. */
    data object CancelImport : KeysIntent

    /** Copies the public key line of [id]; never the private key. */
    data class CopyPublicKey(val id: SshKeyId) : KeysIntent

    /** Asks for confirmation; nothing is deleted yet. */
    data class RequestDelete(val id: SshKeyId) : KeysIntent

    /** Deletes [KeysState.pendingDelete] and its secrets, unless a saved server logs in with it. */
    data object ConfirmDelete : KeysIntent

    data object CancelDelete : KeysIntent

    data object DismissError : KeysIntent

    data object Close : KeysIntent
}

sealed interface KeysEvent {
    /** @property publicKey One OpenSSH public key line, `<type> <base64> [comment]`. */
    data class CopyPublicKey(val publicKey: String) : KeysEvent

    data object Close : KeysEvent
}

interface KeysPresenter : Presenter<KeysState, KeysIntent, KeysEvent>
