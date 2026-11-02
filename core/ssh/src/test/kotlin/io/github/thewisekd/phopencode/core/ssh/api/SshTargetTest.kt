package io.github.thewisekd.phopencode.core.ssh.api

import io.github.thewisekd.phopencode.core.model.SecretRef
import io.github.thewisekd.phopencode.core.model.ServerProfile
import io.github.thewisekd.phopencode.core.model.ServerProfileId
import io.github.thewisekd.phopencode.core.model.SshAuth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class SshTargetTest {
    private val host = "sentinel-host.invalid"
    private val user = "sentinel-user"

    @Test
    fun `toString leaves out address and account`() {
        val text = SshTarget(host, 2222, user).toString()
        assertFalse("sentinel" in text, text)
    }

    @Test
    fun `a profile gives its address, port and account`() {
        val profile = ServerProfile(
            id = ServerProfileId("profile-1"),
            name = "name",
            host = host,
            port = 2222,
            username = user,
            auth = SshAuth.Password(SecretRef("ref")),
        )
        assertEquals(SshTarget(host, 2222, user), SshTarget.from(profile))
    }

    @Test
    fun `invalid values are rejected without repeating them`() {
        listOf(
            { SshTarget(" ", 22, user) },
            { SshTarget(host, 22, " ") },
            { SshTarget(host, 0, user) },
            { SshTarget(host, 65536, user) },
        ).forEach { create ->
            val failure = assertFailsWith<IllegalArgumentException> { create() }
            assertFalse("sentinel" in failure.message.orEmpty())
        }
    }

    @Test
    fun `the port range is inclusive`() {
        assertEquals(1, SshTarget(host, 1, user).port)
        assertEquals(65535, SshTarget(host, 65535, user).port)
    }

    @Test
    fun `options reject durations that cannot work`() {
        listOf(Duration.ZERO, (-1).seconds, Duration.INFINITE).forEach { duration ->
            assertFailsWith<IllegalArgumentException> { SshConnectOptions(connectTimeout = duration) }
            assertFailsWith<IllegalArgumentException> { SshConnectOptions(keepAliveInterval = duration) }
        }
        assertFailsWith<IllegalArgumentException> { SshConnectOptions(keepAliveMaxMissed = 0) }
    }

    @Test
    fun `keep-alive is on by default and can be switched off`() {
        assertNotNull(SshConnectOptions().keepAliveInterval)
        assertNull(SshConnectOptions(keepAliveInterval = null).keepAliveInterval)
    }
}
