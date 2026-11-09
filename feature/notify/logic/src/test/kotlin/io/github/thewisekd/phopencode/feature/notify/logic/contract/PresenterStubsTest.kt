package io.github.thewisekd.phopencode.feature.notify.logic.contract

import io.github.thewisekd.phopencode.core.api.OpencodeClient
import io.github.thewisekd.phopencode.core.model.SecretRef
import io.github.thewisekd.phopencode.core.storage_api.SecretReadResult
import io.github.thewisekd.phopencode.core.storage_api.SecretStore
import io.github.thewisekd.phopencode.core.storage_api.SecretValue
import io.github.thewisekd.phopencode.feature.notify.logic.pending.DefaultPendingPresenter
import io.github.thewisekd.phopencode.feature.notify.logic.setup.DefaultSetupPresenter
import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest

/**
 * Every presenter is built with the constructor the contract fixes, as `NotifyModule` builds it, and
 * shows its screen's initial state before anything has run. The logic tasks fill them in; these hold
 * for the filled-in ones too. Every dependency fails if called: nothing may be read, stored, asked
 * of the server or drawn from the random source before the scope runs.
 */
class PresenterStubsTest {
    private val secrets = object : SecretStore {
        override suspend fun put(ref: SecretRef, value: SecretValue) = error("Not called before the scope runs")

        override suspend fun read(ref: SecretRef): SecretReadResult = error("Not called before the scope runs")

        override suspend fun delete(ref: SecretRef) = error("Not called before the scope runs")

        override suspend fun deleteAll() = error("Not called before the scope runs")
    }

    private val service = object : NotifyService {
        override suspend fun status(): NotifyServiceStatus = error("Not called before the scope runs")

        override suspend fun install(settings: NtfySettings) = error("Not called before the scope runs")

        override suspend fun remove() = error("Not called before the scope runs")

        override suspend fun sendTest(settings: NtfySettings) = error("Not called before the scope runs")
    }

    private val random = object : SecureRandom() {
        override fun nextBytes(bytes: ByteArray) = error("Not called before the scope runs")
    }

    private val connection = object : NotifyConnection {
        override val generations: Flow<Long?> = flowOf(null)

        override suspend fun <T> call(block: suspend (OpencodeClient, Long) -> T): T = error("Not called before the scope runs")
    }

    @Test
    fun `the setup starts loading with the default server`() = runTest {
        val presenter: SetupPresenter =
            DefaultSetupPresenter(secrets = secrets, service = service, random = random, scope = backgroundScope)

        assertEquals(SetupState(), presenter.state.value)
    }

    @Test
    fun `the pending list starts loading`() = runTest {
        val presenter: PendingPresenter = DefaultPendingPresenter(connection = connection, scope = backgroundScope)

        assertEquals(PendingState(), presenter.state.value)
    }
}
