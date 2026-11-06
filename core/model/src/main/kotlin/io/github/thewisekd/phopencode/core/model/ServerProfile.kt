package io.github.thewisekd.phopencode.core.model

import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@JvmInline
value class ServerProfileId(val value: String)

/**
 * Points at a secret in `SecretStore`. Holds the entry's ID, never the secret. Printing is masked
 * anyway, so that a log line says nothing about how secrets are named or how many exist.
 */
@Serializable
@JvmInline
value class SecretRef(val id: String) {
    override fun toString(): String = "SecretRef(***)"
}

/** How to log in over SSH. Holds references only; the secrets stay in `SecretStore`. */
@Serializable
sealed interface SshAuth {
    @Serializable
    @SerialName("password")
    data class Password(val secretRef: SecretRef) : SshAuth

    @Serializable
    @SerialName("privateKey")
    data class PrivateKey(val keyRef: SecretRef, val passphraseRef: SecretRef? = null) : SshAuth
}

/**
 * A saved server. This is safe to store in plain files: it contains no secrets, by construction.
 * Values are not validated here, because a profile read back from storage must not crash; the
 * server form validates input.
 *
 * @property opencodeCredential The `opencode serve` password, for a server this app did not start.
 * @property lastUsedAt For ordering the server list.
 * @property opencodePort The loopback port of the `opencode serve` to use on the server. Null leaves
 *   it to automatic detection. When set, no server on another port is used, and a server this app
 *   starts listens on it. Profiles stored before this field existed read as null.
 */
@Serializable
data class ServerProfile(
    val id: ServerProfileId,
    val name: String,
    val host: String,
    val port: Int = DEFAULT_SSH_PORT,
    val username: String,
    val auth: SshAuth,
    val opencodeCredential: SecretRef? = null,
    val lastUsedAt: Instant? = null,
    val opencodePort: Int? = null,
) {
    /** Leaves out address, account and display name: logs and crash reports must not carry them. */
    override fun toString(): String = "ServerProfile(id=${id.value}, auth=$auth)"

    companion object {
        const val DEFAULT_SSH_PORT: Int = 22
    }
}
