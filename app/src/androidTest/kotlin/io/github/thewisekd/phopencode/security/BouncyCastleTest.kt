package io.github.thewisekd.phopencode.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.KeyPairGenerator
import java.security.Security
import java.security.Signature
import javax.crypto.KeyAgreement
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Android registers a stripped BouncyCastle under the name `BC`, without the curves sshj needs.
 * The app replaces it at start, before any SSH work; these run in the app's process after that.
 */
@RunWith(AndroidJUnit4::class)
class BouncyCastleTest {
    @Test
    fun theAppStartReplacedTheStrippedProvider() {
        val provider = Security.getProvider(BouncyCastleProvider.PROVIDER_NAME)

        assertTrue("BC is ${provider?.javaClass?.name}", provider is BouncyCastleProvider)
        assertEquals(1, Security.getProviders().count { it.name == BouncyCastleProvider.PROVIDER_NAME })
    }

    @Test
    fun installingAgainChangesNothing() {
        val before = Security.getProviders().toList()

        val installed = BouncyCastle.install()

        assertSame(Security.getProvider(BouncyCastleProvider.PROVIDER_NAME), installed)
        // Providers are Hashtables that compare by content, so this compares identities.
        val after = Security.getProviders().toList()
        assertEquals(before.size, after.size)
        assertTrue(before.zip(after).all { (old, new) -> old === new })
    }

    @Test
    fun ed25519SignsAndVerifiesThroughBc() {
        val keys = KeyPairGenerator.getInstance("Ed25519", BC).generateKeyPair()
        val message = "phopencode".toByteArray()

        val signature = Signature.getInstance("Ed25519", BC).run {
            initSign(keys.private)
            update(message)
            sign()
        }
        val valid = Signature.getInstance("Ed25519", BC).run {
            initVerify(keys.public)
            update(message)
            verify(signature)
        }

        assertTrue(valid)
    }

    @Test
    fun x25519AgreesThroughBc() {
        val generator = KeyPairGenerator.getInstance("X25519", BC)
        val alice = generator.generateKeyPair()
        val bob = generator.generateKeyPair()

        fun secret(own: java.security.KeyPair, peer: java.security.KeyPair): ByteArray =
            KeyAgreement.getInstance("X25519", BC).run {
                init(own.private)
                doPhase(peer.public, true)
                generateSecret()
            }

        assertArrayEquals(secret(alice, bob), secret(bob, alice))
    }

    private companion object {
        const val BC = BouncyCastleProvider.PROVIDER_NAME
    }
}
