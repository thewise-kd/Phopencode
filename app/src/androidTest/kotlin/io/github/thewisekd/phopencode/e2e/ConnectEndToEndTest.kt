package io.github.thewisekd.phopencode.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.thewisekd.phopencode.core.api.OpencodeClient
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Connect, end to end: SSH from the emulator to the runner, host key approval, detection of the
 * running `opencode serve`, its password, and the API and version check through the SSH tunnel.
 * The steps themselves are checked in [E2eApp.connect].
 */
@RunWith(AndroidJUnit4::class)
class ConnectEndToEndTest {
    private lateinit var app: E2eApp

    @Before
    fun setUp() = runBlocking {
        app = E2eApp(E2eEnvironment.fromArguments())
        app.setUp()
    }

    @After
    fun tearDown() = runBlocking {
        if (::app.isInitialized) app.tearDown()
    }

    @Test
    fun connectsThroughSshHostKeyApprovalServerDetectionAndTheApiCheck() = runBlocking<Unit> {
        val connected = app.connect()

        assertEquals(app.profileId, connected.server.profileId)
        assertEquals(app.endpoint, connected.server.endpoint)
        assertFalse(connected.info.isVersionUnknown)
        assertTrue(connected.info.version, atLeast(connected.info.version, OpencodeClient.MIN_SERVER_VERSION))

        // The API answers through the tunnel on the connection that stands.
        assertEquals(connected.info.version, connected.client.serverInfo().version)
    }

    private fun atLeast(version: String, minimum: String): Boolean {
        val have = version.removePrefix("v").split('.', '-').mapNotNull(String::toIntOrNull)
        val need = minimum.split('.').map(String::toInt)
        for (i in need.indices) {
            val part = have.getOrElse(i) { 0 }
            if (part != need[i]) return part > need[i]
        }
        return true
    }
}
