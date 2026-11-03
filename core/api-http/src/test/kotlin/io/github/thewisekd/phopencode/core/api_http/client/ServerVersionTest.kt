package io.github.thewisekd.phopencode.core.api_http.client

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.ApiException
import io.github.thewisekd.phopencode.core.api.OpencodeClient
import io.github.thewisekd.phopencode.core.model.DataValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerVersionTest {
    @Test
    fun `a version is major, minor and an optional patch, with an optional v, pre-release and build`() {
        assertEquals(ServerVersion(2, 2, 5), ServerVersion.parse("2.2.5"))
        assertEquals(ServerVersion(2, 2, null), ServerVersion.parse("2.2"))
        assertEquals(ServerVersion(2, 3, 0), ServerVersion.parse("v2.3.0"))
        assertEquals(ServerVersion(2, 2, 0), ServerVersion.parse("2.2.0-beta.1"))
        assertEquals(ServerVersion(2, 2, 0), ServerVersion.parse("2.2.0+build.7"))
        assertEquals(ServerVersion(2, 2, 0), ServerVersion.parse("2.2.0-rc.1+abc"))
        assertEquals(ServerVersion(2, 2, 5), ServerVersion.parse(" 2.2.5\n"))
    }

    @Test
    fun `anything else does not parse`() {
        listOf(
            "", " ", "2", "2.", ".2", "2.x", "x.2", "2.2.x", "2.2.5.1", "local", "dev", "latest", "v", "-2.2",
            "2.-2", "2..2", "2.2.5-", "2.2.5+", "2.2.5 beta", "２.２", "1234567890.0", "2.2.5-${"a".repeat(65)}",
            "2.2.5-<script>", "2.2.5\n2.2.6",
        ).forEach { assertNull(ServerVersion.parse(it), "<$it> must not parse") }
    }

    @Test
    fun `versions compare by number, a missing patch as zero`() {
        fun v(text: String) = checkNotNull(ServerVersion.parse(text))
        assertTrue(v("2.10.0") > v("2.9.9"))
        assertTrue(v("2.2.1") > v("2.2"))
        assertTrue(v("10.0") > v("9.99.99"))
        assertEquals(0, v("2.2").compareTo(v("2.2.0")))
        assertEquals(0, v("2.02.0").compareTo(v("2.2.0")))
    }

    @Test
    fun `the numbers are shown without leading zeros, a v or a suffix`() {
        assertEquals("2.1.9", ServerVersion.parse("v2.01.9-beta+x")?.numbers)
        assertEquals("2.1", ServerVersion.parse("2.1")?.numbers)
    }

    @Test
    fun `the oldest supported version and anything newer pass`() {
        listOf(OpencodeClient.MIN_SERVER_VERSION, "2.2.0", "2.2.5", "2.10.0", "2.99.99", "v2.3", "2.2.0-beta.1").forEach {
            val info = VersionGate.check(it, null)
            assertEquals(it.trim(), info.version)
            assertFalse(info.isNewerMajor, "<$it> is not a newer major")
        }
    }

    @Test
    fun `below 2_2 fails with VersionTooOld, naming the found and required versions`() {
        mapOf("2.1.9" to "2.1.9", "2.1" to "2.1", "1.14.0" to "1.14.0", "0.0.0" to "0.0.0", "v2.01.9-rc" to "2.1.9")
            .forEach { (raw, found) ->
                val error = assertFailsWith<ApiException> { VersionGate.check(raw, null) }.error
                assertEquals(ApiError.VersionTooOld(found, OpencodeClient.MIN_SERVER_VERSION), error, raw)
                assertTrue(error.message.contains(found) && error.message.contains("2.2"), error.message)
            }
    }

    @Test
    fun `a newer major passes with the warning set`() {
        listOf("3.0.0", "3.0", "99.0.0", "v4.1.2-beta").forEach {
            val info = VersionGate.check(it, null)
            assertTrue(info.isNewerMajor, "<$it> is a newer major")
            assertEquals(it, info.version)
        }
    }

    @Test
    fun `an unparseable version fails with VersionTooOld of unknown, and its text appears nowhere`() {
        val hostile = "local build on private-host.example.invalid"
        listOf("local", "", "dev", "2", hostile, "x".repeat(10_000)).forEach { raw ->
            val failure = assertFailsWith<ApiException> { VersionGate.check(raw, null) }
            assertEquals(ApiError.VersionTooOld(VersionGate.UNKNOWN_VERSION), failure.error)
            // A short text such as "2" appears in any message by chance; long ones must not.
            if (raw.length > 3) {
                assertFalse(failure.error.message.contains(raw), "the message must not quote <$raw>")
                assertFalse(failure.toString().contains(raw))
            }
        }
    }

    @Test
    fun `capabilities pass through`() {
        val capabilities = DataValue.Fields(mapOf("persistentPty" to DataValue.Bool(true)))
        assertEquals(capabilities, VersionGate.check("2.2.5", capabilities).capabilities)
    }
}
