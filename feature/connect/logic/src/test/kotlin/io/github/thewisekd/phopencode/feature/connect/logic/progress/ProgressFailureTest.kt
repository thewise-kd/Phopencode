package io.github.thewisekd.phopencode.feature.connect.logic.progress

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.remote.connection.ConnectionError
import io.github.thewisekd.phopencode.core.remote.connection.ServerStartFailure
import io.github.thewisekd.phopencode.core.remote.detect.DetectFailure
import io.github.thewisekd.phopencode.core.storage_api.HostKeyFingerprint
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ProgressAction.EditServer
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ProgressAction.EnterServerPassword
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ProgressAction.Retry
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ProgressAction.ReviewHostKey
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ProgressError
import kotlin.test.Test
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ProgressStep
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProgressFailureTest {
    private fun check(error: ConnectionError, expected: ProgressError, vararg actions: Any) {
        val failure = progressFailure(error)
        assertEquals(expected, failure.error, "$error")
        assertEquals(actions.toSet(), failure.actions, "$error")
    }

    @Test
    fun `profile and secret problems send the user to the server form`() {
        check(ConnectionError.InvalidProfile, ProgressError.InvalidProfile, EditServer, Retry)
        check(ConnectionError.SecretMissing, ProgressError.SecretMissing, EditServer, Retry)
        check(ConnectionError.SecretUnreadable, ProgressError.SecretUnreadable, EditServer, Retry)
        check(ConnectionError.AuthenticationFailed, ProgressError.AuthenticationFailed, EditServer, Retry)
        check(ConnectionError.Unreachable, ProgressError.Unreachable, EditServer, Retry)
        check(ConnectionError.Timeout, ProgressError.Timeout, EditServer, Retry)
    }

    @Test
    fun `passing problems can be retried`() {
        check(ConnectionError.StorageUnavailable, ProgressError.StorageUnavailable, Retry)
        check(ConnectionError.Disconnected, ProgressError.Disconnected, Retry)
        check(ConnectionError.ServerLost, ProgressError.ServerLost, Retry)
        check(ConnectionError.ServerUnresponsive, ProgressError.ServerUnresponsive, Retry)
        check(ConnectionError.ServerUnusable, ProgressError.ServerUnusable, Retry)
        check(ConnectionError.OpencodeMissing, ProgressError.OpencodeMissing, Retry)
        check(ConnectionError.Unexpected(), ProgressError.Unexpected, Retry)
        DetectFailure.entries.forEach {
            check(ConnectionError.ServerDetectionFailed(it), ProgressError.ServerDetectionFailed, Retry)
        }
        ServerStartFailure.entries.forEach {
            check(ConnectionError.ServerStartFailed(it), ProgressError.ServerStartFailed, Retry)
        }
    }

    @Test
    fun `no server on the profile's port sends the user to the server form and keeps the port`() {
        check(ConnectionError.ServerNotOnPort(4096), ProgressError.ServerNotOnPort, EditServer, Retry)
        val failure = progressFailure(ConnectionError.ServerNotOnPort(4096))
        assertEquals(4096, failure.port)
        assertEquals(ProgressStep.Server, failure.step)
        assertNull(progressFailure(ConnectionError.ServerUnusable).port)
    }

    @Test
    fun `host key problems`() {
        check(ConnectionError.HostKeyRejected(SERVER_KEY), ProgressError.HostKeyRejected, Retry)
        check(
            ConnectionError.HostKeyChanged(OTHER_KEY, HostKeyFingerprint(SERVER_KEY.fingerprintSha256)),
            ProgressError.HostKeyChanged,
            ReviewHostKey,
            Retry,
        )
    }

    @Test
    fun `a foreign server's password is asked for`() {
        check(ConnectionError.ServerPasswordUnknown, ProgressError.ServerPasswordUnknown, EnterServerPassword, Retry)
        check(ConnectionError.Api(ApiError.Unauthorized), ProgressError.ServerPasswordRejected, EnterServerPassword, Retry)
        assertTrue(progressFailure(ConnectionError.ServerPasswordUnknown).asksPassword)
        assertTrue(progressFailure(ConnectionError.Api(ApiError.Unauthorized)).asksPassword)
        assertTrue(!progressFailure(ConnectionError.Unreachable).asksPassword)
    }

    @Test
    fun `an error names the step it comes from when only one can cause it`() {
        assertEquals(ProgressStep.Ssh, progressFailure(ConnectionError.AuthenticationFailed).step)
        assertEquals(ProgressStep.Server, progressFailure(ConnectionError.ServerUnresponsive).step)
        assertEquals(ProgressStep.Server, progressFailure(ConnectionError.ServerPasswordUnknown).step)
        assertEquals(ProgressStep.Api, progressFailure(ConnectionError.Api(ApiError.Unauthorized)).step)
        assertNull(progressFailure(ConnectionError.StorageUnavailable).step)
        assertNull(progressFailure(ConnectionError.SecretUnreadable).step)
        assertNull(progressFailure(ConnectionError.Unexpected()).step)
    }

    @Test
    fun `api problems`() {
        check(ConnectionError.Api(ApiError.VersionTooOld("1.0")), ProgressError.ServerVersionTooOld, Retry)
        listOf(
            ApiError.Forbidden,
            ApiError.NotFound,
            ApiError.Conflict(),
            ApiError.BadRequest(),
            ApiError.Server(500),
            ApiError.Network,
            ApiError.Timeout,
            ApiError.MalformedResponse,
            ApiError.Closed,
            ApiError.Unexpected(),
        ).forEach { check(ConnectionError.Api(it), ProgressError.ApiFailed, Retry) }
    }
}
