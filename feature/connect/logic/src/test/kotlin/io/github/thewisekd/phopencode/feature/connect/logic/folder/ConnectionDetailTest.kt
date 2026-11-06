package io.github.thewisekd.phopencode.feature.connect.logic.folder

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.remote.connection.ConnectionError
import io.github.thewisekd.phopencode.core.remote.connection.ConnectionState
import io.github.thewisekd.phopencode.core.remote.detect.DetectFailure
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

class ConnectionDetailTest {
    @Test
    fun `an error without parts is named simply`() {
        assertEquals("Unexpected", ConnectionDetail.of(ConnectionError.Unexpected()))
        assertEquals("AuthenticationFailed", ConnectionDetail.of(ConnectionError.AuthenticationFailed))
    }

    @Test
    fun `an API error names its API error`() {
        assertEquals("Api(Unauthorized)", ConnectionDetail.of(ConnectionError.Api(ApiError.Unauthorized)))
        assertEquals("Api(Server)", ConnectionDetail.of(ConnectionError.Api(ApiError.Server(500, PRIVATE_TEXT))))
    }

    @Test
    fun `an error with values names only its type`() {
        assertEquals("ServerNotOnPort", ConnectionDetail.of(ConnectionError.ServerNotOnPort(PRIVATE_PORT)))
        assertEquals(
            "ServerDetectionFailed",
            ConnectionDetail.of(ConnectionError.ServerDetectionFailed(DetectFailure.entries.first())),
        )
    }

    @Test
    fun `a cause adds its class chain and never its message`() {
        val cause = IllegalStateException("$PRIVATE_TEXT at $PRIVATE_PORT", IOException(PRIVATE_TEXT))

        val detail = ConnectionDetail.of(ConnectionError.Unexpected(cause))

        assertEquals("Unexpected <- java.lang.IllegalStateException <- java.io.IOException", detail)
        assertFalse(PRIVATE_TEXT in detail)
        assertFalse(PRIVATE_PORT.toString() in detail)
    }

    @Test
    fun `the cause chain keeps to the FailureDetail limit`() {
        val cause = (1..10).fold<Int, Throwable>(IOException("root")) { inner, _ -> RuntimeException(inner) }

        val detail = ConnectionDetail.of(ConnectionError.Unexpected(cause))

        assertEquals("Unexpected <- " + FailureDetail.of(cause), detail)
        assertEquals(FailureDetail.MAX_LINKS, FailureDetail.of(cause).split(" <- ").size)
    }

    @Test
    fun `Idle and Closed are named exactly`() {
        assertEquals("Idle", ConnectionDetail.ofState(ConnectionState.Idle))
        assertEquals("Closed", ConnectionDetail.ofState(ConnectionState.Closed))
    }

    @Test
    fun `an error state is named as its error`() {
        assertEquals(
            "Api(Unauthorized)",
            ConnectionDetail.ofState(ConnectionState.Error(ConnectionError.Api(ApiError.Unauthorized))),
        )
        assertEquals(
            "Unexpected <- java.io.IOException",
            ConnectionDetail.ofState(ConnectionState.Error(ConnectionError.Unexpected(IOException(PRIVATE_TEXT)))),
        )
    }

    @Test
    fun `states under way have no detail`() {
        assertNull(ConnectionDetail.ofState(ConnectionState.Ssh()))
        assertNull(ConnectionDetail.ofState(ConnectionState.Server(1)))
        assertNull(ConnectionDetail.ofState(ConnectionState.Api()))
        assertNull(ConnectionDetail.ofState(ConnectionState.Reconnecting(1, 1.seconds, ConnectionError.Disconnected)))
    }

    private companion object {
        /** Assembled at runtime, so that no literal looks like a real address. */
        val PRIVATE_TEXT = "tester@" + SECRET_HOST + ":/home/tester"
        const val PRIVATE_PORT = 48213
    }
}
