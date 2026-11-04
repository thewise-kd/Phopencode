package io.github.thewisekd.phopencode.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.Provider
import java.security.Security
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The general replacement behind [BouncyCastle], on the device's real provider table, with
 * providers of a name nothing else uses so that the app's own providers stay as they are.
 */
@RunWith(AndroidJUnit4::class)
class ProviderReplacementTest {
    @After
    fun removeTestProviders() {
        Security.removeProvider(NAME)
    }

    @Test
    fun replacesAProviderOfAnotherClassInItsPlace() {
        Security.insertProviderAt(Stripped(), 2)
        val before = names()

        val installed = ProviderReplacement.replace(NAME, Full::class.java, ::Full)

        assertSame(installed, Security.getProvider(NAME))
        assertEquals("the order of the providers is kept", before, names())
        assertEquals(1, names().count { it == NAME })
    }

    @Test
    fun addsTheProviderLastWhenTheNameIsFree() {
        val installed = ProviderReplacement.replace(NAME, Full::class.java, ::Full)

        assertSame(installed, Security.getProviders().last())
    }

    @Test
    fun keepsAProviderThatIsAlreadyOfTheWantedClass() {
        val first = ProviderReplacement.replace(NAME, Full::class.java, ::Full)
        val before = names()

        val second = ProviderReplacement.replace(NAME, Full::class.java) { error("must not create another provider") }

        assertSame(first, second)
        assertEquals(before, names())
    }

    @Test
    fun concurrentCallsInstallOneProvider() {
        Security.insertProviderAt(Stripped(), 1)
        val threads = 8
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val results = (1..threads).map {
                pool.submit<Provider> {
                    start.await()
                    ProviderReplacement.replace(NAME, Full::class.java, ::Full)
                }
            }
            start.countDown()
            val installed = results.map { it.get(30, TimeUnit.SECONDS) }

            // Providers are Hashtables that compare by content, so this compares identities.
            assertTrue(installed.all { it === installed.first() })
            assertSame(installed.first(), Security.getProvider(NAME))
            assertEquals(1, names().count { it == NAME })
        } finally {
            pool.shutdownNow()
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesAReplacementWithAnotherName() {
        ProviderReplacement.replace(NAME, Other::class.java, ::Other)
    }

    private fun names(): List<String> = Security.getProviders().map { it.name }

    private class Stripped : Provider(NAME, 1.0, "stand-in for a stripped provider")

    private class Full : Provider(NAME, 2.0, "stand-in for the full provider")

    private class Other : Provider("PhopencodeOtherTestProvider", 1.0, "a provider of another name")

    private companion object {
        const val NAME = "PhopencodeTestProvider"
    }
}
