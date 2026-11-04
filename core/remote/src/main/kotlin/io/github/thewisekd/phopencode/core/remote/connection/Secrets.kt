package io.github.thewisekd.phopencode.core.remote.connection

import io.github.thewisekd.phopencode.core.model.SecretRef
import io.github.thewisekd.phopencode.core.model.SshAuth
import io.github.thewisekd.phopencode.core.ssh.api.SshCredential
import io.github.thewisekd.phopencode.core.storage_api.SecretReadResult
import io.github.thewisekd.phopencode.core.storage_api.SecretStore
import io.github.thewisekd.phopencode.core.storage_api.SecretValue
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** The secret under [ref], or null when there is none. Owned by the caller. */
internal suspend fun SecretStore.readOrNull(ref: SecretRef): SecretValue? {
    val result = try {
        read(ref)
    } catch (_: IOException) {
        throw ConnectionException(ConnectionError.StorageUnavailable)
    }
    return when (result) {
        is SecretReadResult.Found -> result.value
        SecretReadResult.NotFound -> null
        SecretReadResult.Unreadable -> throw ConnectionException(ConnectionError.SecretUnreadable)
    }
}

private suspend fun SecretStore.require(ref: SecretRef): SecretValue =
    readOrNull(ref) ?: throw ConnectionException(ConnectionError.SecretMissing)

/** Reads the login secrets of [auth] into a credential the caller wipes after one connect. */
internal suspend fun SecretStore.sshCredential(auth: SshAuth): SshCredential = when (auth) {
    is SshAuth.Password -> SshCredential.Password(require(auth.secretRef).use { it.chars() })
    is SshAuth.PrivateKey -> {
        val key = require(auth.keyRef).use { it.chars() }
        try {
            val passphrase = auth.passphraseRef?.let { ref -> require(ref).use { it.chars() } }
            SshCredential.PrivateKey(key, passphrase)
        } catch (failure: Throwable) {
            key.fill('\u0000')
            throw failure
        }
    }
}

/** UTF-8 to characters without a `String`; every intermediate buffer is wiped. */
private fun SecretValue.chars(): CharArray {
    val bytes = revealBytes()
    var buffer: CharBuffer? = null
    try {
        buffer = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
        return CharArray(buffer.remaining()).also { buffer.get(it) }
    } catch (_: CharacterCodingException) {
        throw ConnectionException(ConnectionError.SecretUnreadable)
    } finally {
        bytes.fill(0)
        buffer?.let { if (it.hasArray()) it.array().fill('\u0000') }
    }
}
