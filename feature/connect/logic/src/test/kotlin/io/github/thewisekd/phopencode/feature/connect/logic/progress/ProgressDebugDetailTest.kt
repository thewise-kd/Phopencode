package io.github.thewisekd.phopencode.feature.connect.logic.progress

import io.github.thewisekd.phopencode.core.remote.connection.DiagnosticText
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** P9.38: the debug detail of the connection screen, its form and its redaction. */
class ProgressDebugDetailTest {
    private fun frames(count: Int): Array<StackTraceElement> =
        Array(count) { StackTraceElement("test.Frames", "frame$it", "Frames.kt", it + 1) }

    private fun <T : Throwable> T.framed(count: Int = 2): T = apply { stackTrace = frames(count) }

    /** Made-up sentinels, assembled at runtime; none is a real host, address, user or secret. */
    private val host = "sentinel-" + "box.example"
    private val user = "zq" + "user"
    private val ipv4 = listOf(203, 0, 113, 77).joinToString(".")
    private val otherIpv4 = listOf(198, 51, 100, 9).joinToString(".")
    private val ipv6 = "2001:db8:" + ":4a1f"
    private val linkLocal = "fe80:" + ":1"
    private val otherHost = "other-" + "relay.internal"
    private val secret = listOf("Zq", "8w", "Lm", "4x", "Pk").joinToString("")
    private val serverPassword = "srv" + "pass" + "-" + "77"

    private fun detail(failure: Throwable?): String? = ProgressDebugDetail.of(failure, host, user)

    private fun assertHidden(text: String, vararg hidden: String) {
        hidden.forEach { assertFalse(it.lowercase() in text.lowercase(), "\"$it\" survived in:\n$text") }
    }

    @Test
    fun `no failure gives no detail`() {
        assertNull(detail(null))
    }

    @Test
    fun `the root cause comes first, then every link with its class and message`() {
        val root = ConnectException("Connection refused").framed()
        val middle = IOException("SSH connection failed", root).framed()
        val outer = IllegalStateException("connect failed", middle).framed()

        val text = detail(outer)!!

        assertTrue(text.startsWith("Root cause: java.net.ConnectException: Connection refused\n"), text)
        assertTrue("java.lang.IllegalStateException: connect failed\n" in text, text)
        assertTrue("Caused by: java.io.IOException: SSH connection failed\n" in text, text)
        assertTrue("Caused by: java.net.ConnectException: Connection refused\n" in text, text)
        assertTrue("    at test.Frames.frame0(Frames.kt:1)" in text, text)
    }

    @Test
    fun `a failure without a cause is its own root`() {
        val text = detail(SocketTimeoutException("Read timed out").framed())!!

        assertTrue(text.startsWith("Root cause: java.net.SocketTimeoutException: Read timed out\n"), text)
    }

    @Test
    fun `a cause cycle still has a root and ends`() {
        val first = IOException("first").framed()
        val second = IOException("second", first).framed()
        first.initCause(second)

        val text = detail(second)!!

        assertTrue(text.startsWith("Root cause: java.io.IOException: first\n"), text)
    }

    @Test
    fun `each link shows a limited number of frames`() {
        val inner = IOException("inner").framed(20)
        val text = detail(IllegalStateException("outer", inner).framed(30))!!

        assertEquals(2 * ProgressDebugDetail.MAX_FRAMES, text.lines().count { it.startsWith("    at ") }, text)
        assertTrue("    ... ${30 - ProgressDebugDetail.MAX_FRAMES} more" in text, text)
        assertTrue("    ... ${20 - ProgressDebugDetail.MAX_FRAMES} more" in text, text)
    }

    @Test
    fun `a long detail is cut and says so`() {
        val text = detail(IOException("x".repeat(3 * ProgressDebugDetail.MAX_LENGTH)).framed())!!

        assertEquals(ProgressDebugDetail.MAX_LENGTH, text.length)
        assertTrue(text.endsWith(ProgressDebugDetail.TRUNCATED), text)
    }

    @Test
    fun `hosts, addresses, user names and secrets in realistic chains never survive`() {
        val unknownHost = UnknownHostException("${host.uppercase()}: Name or service not known").framed()
        val refused = ConnectException(
            "Failed to connect to $host/$ipv4:22 (port 22) from /$otherIpv4 (port 50122): Connection refused",
        ).framed()
        refused.initCause(unknownHost)
        val sshj = IOException(
            "Exhausted available authentication methods for $user@$host; " +
                "known_hosts at /home/${user.uppercase()}/.ssh/known_hosts; " +
                "via [$linkLocal%wlan0]:22, $ipv6 and $otherHost:2222",
            refused,
        ).framed()
        val api = IllegalStateException(
            "GET http://opencode:$serverPassword@127.0.0.1:4096/session failed; " +
                "password=$secret; Authorization: Basic $secret\n" +
                "-----BEGIN OPENSSH PRIVATE KEY-----\n$secret\n-----END OPENSSH PRIVATE KEY-----",
            sshj,
        ).framed()

        val text = detail(api)!!

        assertHidden(text, host, user, ipv4, otherIpv4, ipv6, linkLocal, otherHost, "127.0.0.1", secret, serverPassword)
        assertHidden(text, ":2222", ":4096", ":22 ")
        assertTrue(DiagnosticText.MASK in text, text)
        // What the user needs stays readable.
        assertTrue("java.net.UnknownHostException" in text, text)
        assertTrue("Root cause: java.net.UnknownHostException: ***: Name or service not known" in text, text)
        assertTrue("Connection refused" in text, text)
        assertTrue("Exhausted available authentication methods" in text, text)
        assertTrue("/home/***/.ssh/known_hosts" in text, text)
        assertTrue("    at test.Frames.frame1(Frames.kt:2)" in text, text)
    }

    @Test
    fun `the host and user name are masked whole words only, whatever their case`() {
        val text = ProgressDebugDetail.redact("ace@Srv.Example tracer srv.example.org ACE replace", "srv.example", "ace")

        assertEquals("***@*** tracer ***.org *** replace", text)
    }

    @Test
    fun `a user name inside a URL is masked`() {
        val text = ProgressDebugDetail.redact("sftp://tester@files/home/tester/x", host = null, user = "tester")

        assertHidden(text, "tester")
    }

    @Test
    fun `the host and user name are taken literally`() {
        val text = ProgressDebugDetail.redact("a.b+c and axb+c", host = "a.b+c", user = null)

        assertEquals("*** and axb+c", text)
    }

    @Test
    fun `a blank host or user name masks nothing`() {
        assertEquals("plain text", ProgressDebugDetail.redact("plain text", host = "", user = " "))
    }

    @Test
    fun `times and versions in a message are kept`() {
        val text = "at 12:34:56 with kotlin 2.1.0 and Caused by: x"

        assertEquals(text, ProgressDebugDetail.redact(text, host = null, user = null))
    }

    @Test
    fun `the unspecified IPv6 address is masked alone, with a port and with a zone`() {
        val text = ProgressDebugDetail.redact("bound to :: and [::]:22, zone ::%eth0", host = null, user = null)

        assertEquals("bound to *** and ***, zone ***", text)
    }

    @Test
    fun `Kotlin member references and times are not taken for IPv6`() {
        val text = "see Foo::bar, String::length and ::run at 12:34:56"

        assertEquals(text, ProgressDebugDetail.redact(text, host = null, user = null))
    }

    @Test
    fun `frame-like lines quoted in a multi-line message never keep an endpoint`() {
        val remote = "evil" + ".example"
        val relay = "relay" + ".kt"
        val inner = IOException("upstream said:\n    at $remote:8080\n\tat x.Y.z($remote:8080)").framed()
        val failure = IllegalStateException(
            "server replied:\n    at a.b.C.run($relay:9090)\n    at a.b.C.run(Main.kt:12)\nend",
            inner,
        ).framed()

        val text = detail(failure)!!

        assertHidden(text, remote, relay, "8080", "9090", "Main.kt:12")
        assertTrue("    at ***\n" in text, text)
        assertTrue("server replied:\n    at a.b.C.run(***)\n" in text, text)
        // The real frames of both links keep their location.
        assertTrue("    at test.Frames.frame0(Frames.kt:1)\n    at test.Frames.frame1(Frames.kt:2)" in text, text)
        assertEquals(4, Regex("""\(Frames\.kt:\d\)""").findAll(text).count(), text)
    }

    @Test
    fun `only a real frame with a plain source file keeps its location`() {
        val failure = IOException("boom")
        failure.stackTrace = arrayOf(
            StackTraceElement("a.b.ProgressDebugDetail", "of", "ProgressDebugDetail.kt", 42),
            StackTraceElement(null, "java.base", null, "java.net.Socket", "connect", "Socket.java", 633),
            StackTraceElement("jdk.internal.Foo", "bar", null, -2),
            StackTraceElement("a.B", "c", null, -1),
        )
        val forged = IOException("forged")
        forged.stackTrace = arrayOf(
            StackTraceElement("a.B", "d", "$host:8080", 3),
            StackTraceElement("relay.example:7000.C", "run", "C.kt", 3),
            StackTraceElement("a.B", "e", "/home/$user/src/B.kt", 5),
        )
        failure.initCause(forged)

        val text = detail(failure)!!

        assertTrue("    at a.b.ProgressDebugDetail.of(ProgressDebugDetail.kt:42)\n" in text, text)
        assertTrue("    at java.base/java.net.Socket.connect(Socket.java:633)\n" in text, text)
        assertTrue("    at jdk.internal.Foo.bar(Native Method)\n" in text, text)
        assertTrue("    at a.B.c(Unknown Source)\n" in text, text)
        assertTrue("    at a.B.d(***)\n" in text, text)
        assertTrue("    at ***.C.run(C.kt:3)\n" in text, text)
        assertTrue("    at a.B.e(***)" in text, text)
        assertHidden(text, host, user, "relay.example", "8080", "7000")
    }

    @Test
    fun `a user name that names a source file hides that frame's location and class`() {
        val failure = IOException("connect failed")
        failure.stackTrace = arrayOf(
            StackTraceElement(null, "java.base", null, "java.net.Socket", "connect", "Socket.java", 633),
            StackTraceElement("a.b.Other", "run", "Other.kt", 12),
        )

        val text = ProgressDebugDetail.of(failure, host = "h" + ".example", user = "socket")!!

        assertFalse("socket" in text.lowercase(), text)
        assertTrue("    at java.base/java.net.***.connect(***)\n" in text, text)
        assertTrue("    at a.b.Other.run(Other.kt:12)" in text, text)
    }

    @Test
    fun `a host that is a file stem hides that frame's location and class`() {
        val failure = IOException("connect failed")
        failure.stackTrace = arrayOf(
            StackTraceElement("com.x.Relay", "open", "Relay.kt", 7),
            StackTraceElement("com.x.Helper", "open", "RelayHelper.kt", 8),
            StackTraceElement("a.b.Other", "run", "Other.kt", 12),
        )

        val text = ProgressDebugDetail.of(failure, host = "RELAY", user = "nobody")!!

        assertTrue("    at com.x.***.open(***)\n" in text, text)
        assertTrue("    at com.x.Helper.open(***)\n" in text, text)
        assertTrue("    at a.b.Other.run(Other.kt:12)" in text, text)
        assertFalse("relay" in text.lowercase(), text)
    }

    @Test
    fun `IPv6 literals in every common form are masked`() {
        val forms = listOf(
            "2001:db8:0:0:0:0:0:1",
            "2001:db8::1",
            "::1",
            "[fe80::1%wlan0]:22",
            "::ffff:192.0.2.1",
            "FE80::ABCD",
            "::",
            "[::]:22",
            "::%eth0",
        )
        for (form in forms) {
            val text = ProgressDebugDetail.redact("to $form now", host = null, user = null)
            assertEquals(false, text.contains(Regex("[0-9A-Fa-f]{1,4}:[0-9A-Fa-f]")) || "::" in text, "$form -> $text")
            assertTrue(text.startsWith("to ") && text.endsWith(" now"), text)
        }
    }
}
