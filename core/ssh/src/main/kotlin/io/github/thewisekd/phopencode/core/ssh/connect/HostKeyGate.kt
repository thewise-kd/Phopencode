package io.github.thewisekd.phopencode.core.ssh.connect

import io.github.thewisekd.phopencode.core.ssh.api.HostKey
import java.security.PublicKey
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import kotlinx.coroutines.CompletableDeferred
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.transport.verification.HostKeyVerifier as SshjHostKeyVerifier

/**
 * Holds sshj's handshake at the host key until the connecting coroutine has asked the app's
 * verifier. sshj calls [verify] on its reader thread, which blocks there; nothing else can happen
 * on the connection meanwhile, so no credential can leave before [accept].
 *
 * One gate serves one connection. A re-key later on the same connection must present the key that
 * was accepted, or the connection fails.
 */
internal class HostKeyGate : SshjHostKeyVerifier {
    /** Completes with the key the server presented first. */
    val presented = CompletableDeferred<HostKey>()

    private val decision = CompletableFuture<Boolean>()

    @Volatile
    private var accepted: HostKey? = null

    override fun verify(hostname: String?, port: Int, key: PublicKey): Boolean {
        val hostKey = runCatching { hostKeyOf(key) }.getOrNull() ?: return false
        accepted?.let { return it == hostKey }
        if (!presented.complete(hostKey)) return false
        return try {
            decision.get()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        } catch (_: ExecutionException) {
            false
        }
    }

    /** No stored algorithms: the store is the app's, and a mismatch is the app verifier's call. */
    override fun findExistingAlgorithms(hostname: String?, port: Int): List<String> = emptyList()

    fun accept(key: HostKey) {
        accepted = key
        decision.complete(true)
    }

    /** Lets a blocked handshake fail. Safe to call at any time, also after [accept]. */
    fun reject() {
        decision.complete(false)
    }

    companion object {
        /** The SSH wire form, which is what `HostKey` stores. */
        fun hostKeyOf(key: PublicKey): HostKey =
            HostKey(KeyType.fromKey(key).toString(), Buffer.PlainBuffer().putPublicKey(key).compactData)
    }
}
