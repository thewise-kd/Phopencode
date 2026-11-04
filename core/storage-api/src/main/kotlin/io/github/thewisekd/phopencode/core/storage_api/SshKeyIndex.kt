package io.github.thewisekd.phopencode.core.storage_api

import io.github.thewisekd.phopencode.core.model.SecretRef
import kotlinx.coroutines.flow.Flow

@JvmInline
value class SshKeyId(val value: String)

/**
 * What the app knows about one SSH key pair, without its private half. The private key lives only in
 * [SecretStore] under [privateKeyRef], and its passphrase, if it has one, under [passphraseRef]; a
 * profile that logs in with this key holds the same references.
 *
 * [publicKey] must be one OpenSSH public key line, and a private key file (PEM or OpenSSH armor,
 * PuTTY `.ppk`) is refused by its header, so that a mix-up between the two halves cannot write a
 * private key into the index file. Only the structure is checked, never the comment's words. No
 * other value is validated: an entry read back from storage must not crash, and the index reader
 * leaves out an entry this check refuses.
 *
 * Printing leaves out the name, fingerprint and public key: they can identify the user.
 *
 * @property type The OpenSSH key type, for example `ssh-ed25519` or `ssh-rsa`.
 * @property fingerprint The public key's SHA-256 fingerprint in OpenSSH form, `SHA256:<base64>`.
 * @property publicKey The public key in OpenSSH `authorized_keys` form, `<type> <base64> [comment]`.
 */
data class SshKeyEntry(
    val id: SshKeyId,
    val name: String,
    val type: String,
    val fingerprint: String,
    val publicKey: String,
    val privateKeyRef: SecretRef,
    val passphraseRef: SecretRef? = null,
) {
    init {
        // The messages say nothing about the value: it may be a private key.
        require(PRIVATE_KEY_HEADERS.none { publicKey.startsWith(it) }) { "The public key is a private key file" }
        require(PUBLIC_KEY_LINE.matches(publicKey)) { "The public key is not one OpenSSH public key line" }
    }

    override fun toString(): String = "SshKeyEntry(id=${id.value}, type=$type)"

    private companion object {
        /**
         * The headers private key files start with: PEM and OpenSSH armor (`-----BEGIN … PRIVATE
         * KEY-----`) and PuTTY's `.ppk` files. The line check refuses them too; these name them.
         */
        val PRIVATE_KEY_HEADERS = listOf("-----BEGIN", "PuTTY-User-Key-File-", "Private-Lines:")

        /**
         * `<type> <base64 blob>[ <comment>]` on one line. Private key files span several lines, and a
         * header joined onto one line is not a type followed by base64. The comment may say anything.
         */
        val PUBLIC_KEY_LINE = Regex("""[A-Za-z0-9][A-Za-z0-9@._+-]* [A-Za-z0-9+/]+={0,2}( [^\r\n]*)?""")
    }
}

/**
 * The index of the user's SSH keys, so they can be listed: [SecretStore] cannot list what it holds.
 * The index holds no private key material, only [SecretRef]s to it. Lists are always sorted by
 * [BY_NAME]. Writes throw [java.io.IOException] when storage cannot be written.
 *
 * `SettingsRepository.deleteAllData` clears the index together with the secrets it refers to.
 */
interface SshKeyIndex {
    /** Emits the current list on collection, then again after every change. */
    fun observe(): Flow<List<SshKeyEntry>>

    suspend fun list(): List<SshKeyEntry>

    /** The entry with this ID, or `null` if there is none. */
    suspend fun get(id: SshKeyId): SshKeyEntry?

    /** Adds [entry], or replaces the one with the same ID. */
    suspend fun save(entry: SshKeyEntry)

    /**
     * Removes the entry with this ID, if any. The secrets it refers to stay in [SecretStore]; the
     * caller deletes them, after checking that no profile still logs in with the key.
     */
    suspend fun delete(id: SshKeyId)

    companion object {
        /** By name, then by ID, so that keys with one name keep a stable order. */
        val BY_NAME: Comparator<SshKeyEntry> = compareBy<SshKeyEntry> { it.name }.thenBy { it.id.value }
    }
}
