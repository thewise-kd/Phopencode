package io.github.thewisekd.phopencode.core.storage_api

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

class KnownHostsTest {
    private val endpoint = HostEndpoint("sentinel-host.invalid", 2222)
    private val approved = HostKeyFingerprint("SHA256:sentinel-approved")
    private val other = HostKeyFingerprint("SHA256:sentinel-other")

    private fun storeWith(fingerprint: HostKeyFingerprint?) = object : KnownHostsStore {
        override suspend fun approved(endpoint: HostEndpoint) = fingerprint

        override suspend fun approve(endpoint: HostEndpoint, fingerprint: HostKeyFingerprint) = unused()

        override suspend fun replace(
            endpoint: HostEndpoint,
            expected: HostKeyFingerprint,
            replacement: HostKeyFingerprint,
        ) = unused()

        override suspend fun delete(endpoint: HostEndpoint) = unused()

        private fun unused(): Nothing = error("not part of this test")
    }

    @Test
    fun `a host with no approved key is unknown`() = runTest {
        assertEquals(HostKeyCheck.Unknown, storeWith(null).check(endpoint, approved))
    }

    @Test
    fun `the approved key is trusted`() = runTest {
        val store = storeWith(HostKeyFingerprint("SHA256:sentinel-approved"))

        assertEquals(HostKeyCheck.Trusted, store.check(endpoint, approved))
    }

    @Test
    fun `a different key is a change and names the approved key`() = runTest {
        assertEquals(HostKeyCheck.Changed(approved), storeWith(approved).check(endpoint, other))
    }

    @Test
    fun `fingerprints compare exactly, because base64 is case-sensitive`() = runTest {
        val store = storeWith(HostKeyFingerprint("SHA256:abc"))

        assertEquals(
            HostKeyCheck.Changed(HostKeyFingerprint("SHA256:abc")),
            store.check(endpoint, HostKeyFingerprint("SHA256:ABC")),
        )
    }

    @Test
    fun `the same server under another letter case is the same endpoint`() {
        val mixed = HostEndpoint("Sentinel-Host.invalid", 22)
        val lower = HostEndpoint("sentinel-host.invalid", 22)

        assertEquals(lower, mixed)
        assertEquals(lower.hashCode(), mixed.hashCode())
        assertEquals("sentinel-host.invalid", mixed.host)
    }

    @Test
    fun `lower-casing a host does not depend on the device locale`() {
        val saved = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("tr-TR"))
        try {
            assertEquals("ilk-host.invalid", HostEndpoint("ILK-HOST.invalid", 22).host)
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun `another port is another endpoint`() {
        assertNotEquals(HostEndpoint("sentinel-host.invalid", 22), HostEndpoint("sentinel-host.invalid", 2222))
    }

    @Test
    fun `an endpoint keeps its port`() {
        assertEquals(2222, endpoint.port)
    }

    @Test
    fun `nothing here prints a host or a fingerprint`() {
        val texts = listOf(
            endpoint,
            approved,
            HostKeyCheck.Changed(approved),
            HostKeyUpdate.Rejected(approved),
            listOf(endpoint to approved),
        ).map { it.toString() }

        texts.forEach { assertFalse(it.contains("sentinel"), it) }
    }

    @Test
    fun `a rejected replacement may have no current key`() {
        assertNull(HostKeyUpdate.Rejected(null).current)
    }
}
