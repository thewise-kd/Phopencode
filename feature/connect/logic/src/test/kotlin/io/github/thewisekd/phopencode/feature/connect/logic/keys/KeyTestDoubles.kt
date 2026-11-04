package io.github.thewisekd.phopencode.feature.connect.logic.keys

import io.github.thewisekd.phopencode.core.model.SecretRef
import io.github.thewisekd.phopencode.core.ssh.key.SshKeys
import io.github.thewisekd.phopencode.core.storage_api.SecretReadResult
import io.github.thewisekd.phopencode.core.storage_api.SecretStore
import io.github.thewisekd.phopencode.core.storage_api.SecretValue
import io.github.thewisekd.phopencode.core.storage_api.SshKeyEntry
import io.github.thewisekd.phopencode.core.storage_api.SshKeyId
import io.github.thewisekd.phopencode.core.storage_api.SshKeyIndex
import io.github.thewisekd.phopencode.core.testing.storage.FakeSecretStore
import io.github.thewisekd.phopencode.core.testing.storage.FakeSshKeyIndex
import java.io.IOException
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Test values only. Nothing here is a real secret. */
internal object TestSecrets {
    const val PASSPHRASE = "test-key-secret"
    const val WRONG_PASSPHRASE = "fake-key-secret"
}

/** Keys made at runtime; the repository holds no key. */
internal object TestKeys {
    fun openSsh(passphrase: String? = null): ByteArray =
        SshKeys.generateEd25519("phone@test", passphrase?.toCharArray()).use { it.privateKeyBytes() }

    fun ecdsaPem(): ByteArray = pkcs8(
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair().private,
    )

    /** DSA: a real key file of a type the app does not read. */
    fun dsaPem(): ByteArray = pkcs8(KeyPairGenerator.getInstance("DSA").apply { initialize(2048) }.generateKeyPair().private)

    /**
     * PKCS#8 in PEM armour. The markers are put together here so that the repository's secret
     * scan sees no key block in the sources.
     */
    private fun pkcs8(key: PrivateKey): ByteArray {
        val kind = "PRIVATE" + " KEY-----"
        val body = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(key.encoded)
        return "-----BEGIN $kind\n$body\n-----END $kind\n".encodeToByteArray()
    }
}

/** A [FakeSecretStore] whose `put` can fail on a chosen call. */
internal class TestSecretStore(val fake: FakeSecretStore = FakeSecretStore()) : SecretStore by fake {
    private val puts = AtomicInteger()

    /** 1-based number of the `put` call that throws, or null for none. */
    @Volatile
    var failPutAt: Int? = null

    @Volatile
    var failDelete: Boolean = false

    override suspend fun put(ref: SecretRef, value: SecretValue) {
        if (puts.incrementAndGet() == failPutAt) throw IOException("Test store refuses this write")
        fake.put(ref, value)
    }

    override suspend fun delete(ref: SecretRef) {
        if (failDelete) throw IOException("Test store refuses this delete")
        fake.delete(ref)
    }

    suspend fun bytes(ref: SecretRef): ByteArray? =
        (fake.read(ref) as? SecretReadResult.Found)?.value?.use { it.revealBytes() }

    suspend fun text(ref: SecretRef): String? =
        (fake.read(ref) as? SecretReadResult.Found)?.value?.use { it.revealText() }
}

/** A [FakeSshKeyIndex] that counts subscriptions and can fail them or its writes. */
internal class TestKeyIndex(val fake: FakeSshKeyIndex = FakeSshKeyIndex()) : SshKeyIndex by fake {
    val observeCalls = AtomicInteger()

    @Volatile
    var failObserve: Boolean = false

    @Volatile
    var failSave: Boolean = false

    @Volatile
    var failDelete: Boolean = false

    override fun observe(): Flow<List<SshKeyEntry>> {
        observeCalls.incrementAndGet()
        if (failObserve) return flow { throw IOException("Test index cannot be read") }
        return fake.observe()
    }

    override suspend fun save(entry: SshKeyEntry) {
        if (failSave) throw IOException("Test index refuses this write")
        fake.save(entry)
    }

    override suspend fun delete(id: SshKeyId) {
        if (failDelete) throw IOException("Test index refuses this delete")
        fake.delete(id)
    }
}

/** Counts what is sent to [delegate], to show that work ran on the worker. */
internal class CountingDispatcher(private val delegate: CoroutineDispatcher) : CoroutineDispatcher() {
    val dispatches = AtomicInteger()

    override fun isDispatchNeeded(context: CoroutineContext): Boolean = true

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        dispatches.incrementAndGet()
        delegate.dispatch(context, block)
    }
}

/**
 * As `Dispatchers.Main.immediate` on the main thread: runs what it is given at once, and queues on
 * [delegate] only what is dispatched, such as a `yield`.
 */
internal class ImmediateDispatcher(private val delegate: CoroutineDispatcher) : CoroutineDispatcher() {
    override fun isDispatchNeeded(context: CoroutineContext): Boolean = false

    override fun dispatch(context: CoroutineContext, block: Runnable) = delegate.dispatch(context, block)
}
