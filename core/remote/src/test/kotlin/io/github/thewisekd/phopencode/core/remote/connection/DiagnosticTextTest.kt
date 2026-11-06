package io.github.thewisekd.phopencode.core.remote.connection

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** P9.25: the shared chain format and pattern redaction of diagnostics, for the screen and the log. */
class DiagnosticTextTest {
    private fun frames(count: Int): Array<StackTraceElement> =
        Array(count) { StackTraceElement("test.Frames", "frame$it", "Frames.kt", it + 1) }

    private fun redact(text: String): String = DiagnosticText.redact(text)

    /** A made-up credential, assembled at runtime so that no literal looks like one. */
    private val value = listOf("Zq", "8w", "Lm", "4x").joinToString("")

    /** A run of 49 base64 characters, with digits and both cases, and no slash. */
    private val base64Run = listOf("Ab3", "+Xy9").joinToString("").repeat(7)

    private fun assertMasked(text: String, vararg hidden: String) {
        hidden.forEach { assertFalse(it in text, "\"$it\" survived in: $text") }
        assertTrue(DiagnosticText.MASK in text, text)
    }

    @Test
    fun `the chain names every link with its class, message and frames`() {
        val inner = IOException("inner reason").apply { stackTrace = frames(20) }
        val outer = IllegalStateException("outer reason", inner).apply { stackTrace = frames(1) }

        val text = DiagnosticText.chain(outer)

        assertTrue(text.startsWith("java.lang.IllegalStateException: outer reason\n"), text)
        assertTrue("Caused by: java.io.IOException: inner reason\n" in text, text)
        assertEquals(1 + DiagnosticText.MAX_FRAMES, text.lines().count { it.startsWith("    at ") }, text)
        assertTrue("    ... 5 more" in text, text)
    }

    @Test
    fun `an Authorization header is masked whatever its scheme`() {
        val encoded = java.util.Base64.getEncoder().encodeToString("tester:$value".encodeToByteArray())
        val text = redact("request refused; Authorization: Basic $encoded\nretrying")

        assertMasked(text, encoded, value)
        assertTrue("Authorization: ***" in text, text)
        assertTrue("retrying" in text, text)
    }

    @Test
    fun `Basic and Bearer credentials are masked on their own`() {
        val token = "$value.$value-$value"
        val text = redact("sent Basic $value then Bearer $token")

        assertMasked(text, value, token)
        assertTrue("Basic ***" in text && "Bearer ***" in text, text)
    }

    @Test
    fun `the userinfo of a URL is masked and its host kept`() {
        val text = redact("GET https://tester:$value@server.example:4096/api/event failed")

        assertMasked(text, value, "tester:")
        assertTrue("https://***@server.example:4096/api/event" in text, text)
    }

    @Test
    fun `key value pairs of every credential key are masked in every style`() {
        val keys = listOf("pass" + "word", "pass" + "wd", "pw" + "d", "sec" + "ret", "tok" + "en", "pass" + "phrase")
        for (key in keys) {
            val pairs = listOf("$key=$value", "$key: $value", "\"$key\": \"$value\"", "\"$key\":\"$value\"", "$key = '$value'")
            for (pair in pairs) {
                val text = redact("config has $pair end")

                assertMasked(text, value)
                assertTrue(key in text, "the key goes: $text")
                assertTrue(" end" in text, "too much masked: $text")
            }
        }
    }

    /** A second made-up piece, to show that no part after punctuation survives. */
    private val tail = listOf("Tq", "9r", "Vk").joinToString("")

    @Test
    fun `an unquoted value is masked to the next whitespace whatever punctuation it holds`() {
        val key = "pass" + "word"
        for (mark in listOf(";", ",", "&", ")", "}", "]", "#", "\"", "'")) {
            for (separator in listOf("=", ": ")) {
                val text = redact("set $key$separator$value$mark$tail next\nline two")

                assertMasked(text, value, tail)
                assertTrue(" next\nline two" in text, "too much masked: $text")
            }
        }
    }

    @Test
    fun `an unquoted value at the end of a line is masked to the end of it`() {
        val text = redact("tok" + "en=$value;$tail\nnext line")

        assertMasked(text, value, tail)
        assertTrue("\nnext line" in text, text)
    }

    @Test
    fun `a quoted value is masked to its closing quote, past escaped quotes`() {
        val key = "pass" + "word"
        val double = redact("{\"$key\": \"$value\\\"$tail\", \"user\": \"tester\"}")
        val single = redact("$key='$value\\'$tail' user=tester")

        assertMasked(double, value, tail)
        assertTrue("\"user\": \"tester\"" in double, double)
        assertMasked(single, value, tail)
        assertTrue("user=tester" in single, single)
    }

    @Test
    fun `an unterminated quoted value is masked to the end of the line`() {
        val text = redact("pass" + "word=\"$value $tail\nnext line")

        assertMasked(text, value, tail)
        assertTrue("\nnext line" in text, text)
    }

    @Test
    fun `a URL password holding an at sign is masked up to the last one before the host`() {
        val text = redact("GET https://tester:$value@$tail@server.example:4096/api failed")

        assertMasked(text, value, tail)
        assertTrue("https://***@server.example:4096/api failed" in text, text)
    }

    @Test
    fun `a key that only contains a credential word is masked too`() {
        val text = redact("env OPENCODE_SERVER_" + "PASS" + "WORD=$value and apiTok" + "en=$value")

        assertMasked(text, value)
    }

    @Test
    fun `a private key block is masked whole, OpenSSH and PEM alike`() {
        for (kind in listOf("OPENSSH PRI" + "VATE KEY", "RSA PRI" + "VATE KEY", "PRI" + "VATE KEY", "EC PRI" + "VATE KEY")) {
            val body = "$value\n$base64Run\n$value"
            val block = "-----BEGIN $kind-----\n$body\n-----END $kind-----"
            val text = redact("could not read $block after it")

            assertMasked(text, value, base64Run, "BEGIN $kind")
            assertTrue("after it" in text, text)
        }
    }

    @Test
    fun `a private key block without its end is masked to the end`() {
        val text = redact("-----BEGIN OPENSSH PRI" + "VATE KEY-----\n$value\n$value")

        assertMasked(text, value)
    }

    @Test
    fun `a long base64 run is masked`() {
        val text = redact("payload $base64Run== ended")

        assertMasked(text, base64Run)
        assertTrue("payload *** ended" in text, text)
    }

    @Test
    fun `ordinary class names, hosts, paths and frames are kept`() {
        val ordinary = listOf(
            "io.github.thewisekd.phopencode.core.ssh.api.SshException\$Sftp",
            "ConnectionError.SecretMissing",
            "Api(Unauthorized)",
            "server.example:22",
            "tester@server.example",
            "/home/tester/Projects2026/phopencode/Feature/Connect/Logic/src/main/kotlin",
            "SecretStore.kt:12",
            "TokenRefresher",
        )
        val inner = IOException(ordinary.joinToString(" ")).apply { stackTrace = frames(2) }
        val outer = IllegalStateException("listing /srv/app on server.example", inner).apply { stackTrace = frames(2) }
        val text = redact(DiagnosticText.chain(outer))

        ordinary.forEach { assertTrue(it in text, "\"$it\" was masked: $text") }
        assertTrue("    at test.Frames.frame1(Frames.kt:2)" in text, text)
        assertFalse(DiagnosticText.MASK in text, text)
    }

}
