package io.github.thewisekd.phopencode.core.storage_api

import io.github.thewisekd.phopencode.core.model.SecretRef

/**
 * Encrypted storage for secrets. Profiles keep a [SecretRef]; the secret itself lives only here.
 *
 * Every function throws [java.io.IOException] when storage cannot be reached right now. A failure
 * that retrying cannot cure is not an exception: see [SecretReadResult.Unreadable].
 */
interface SecretStore {
    /**
     * Stores [value] under [ref], replacing any entry there, also an unreadable one. The caller
     * keeps ownership of [value] and closes it.
     */
    suspend fun put(ref: SecretRef, value: SecretValue)

    /** The entry under [ref]. The caller owns a returned value and closes it. */
    suspend fun read(ref: SecretRef): SecretReadResult

    /** Removes the entry under [ref], also an unreadable one. Does nothing if there is none. */
    suspend fun delete(ref: SecretRef)

    /** Removes every entry, also unreadable ones. */
    suspend fun deleteAll()
}

sealed interface SecretReadResult {
    data class Found(val value: SecretValue) : SecretReadResult

    /** No entry exists under this reference. */
    data object NotFound : SecretReadResult

    /**
     * An entry exists but can never be decrypted, typically because the installation that wrote it
     * was removed and its Keystore key went with it. The user must enter the secret again.
     */
    data object Unreadable : SecretReadResult
}
