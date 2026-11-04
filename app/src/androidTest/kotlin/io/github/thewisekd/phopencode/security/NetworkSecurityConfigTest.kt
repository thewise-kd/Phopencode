package io.github.thewisekd.phopencode.security

import android.security.NetworkSecurityPolicy
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The app's network security config, as the platform applied it to this process. The HTTP client
 * reaches `opencode serve` at http://127.0.0.1 through the SSH tunnel; that is the only plain
 * text traffic allowed, and the tunnel itself is encrypted by SSH.
 */
@RunWith(AndroidJUnit4::class)
class NetworkSecurityConfigTest {
    private val policy = NetworkSecurityPolicy.getInstance()

    @Test
    fun cleartextIsOffByDefault() {
        assertFalse(policy.isCleartextTrafficPermitted)
    }

    @Test
    fun cleartextIsAllowedToTheTunnelAddress() {
        assertTrue(policy.isCleartextTrafficPermitted("127.0.0.1"))
    }

    @Test
    fun cleartextIsRefusedEverywhereElse() {
        val others = listOf(
            "localhost",
            "127.0.0.2",
            "::1",
            "10.0.2.2",
            "192.0.2.1",
            "example.com",
            "127.0.0.1.example.com",
        )
        others.forEach { host ->
            assertFalse("cleartext to $host", policy.isCleartextTrafficPermitted(host))
        }
    }
}
