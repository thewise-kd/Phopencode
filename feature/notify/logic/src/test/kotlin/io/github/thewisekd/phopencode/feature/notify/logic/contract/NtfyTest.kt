package io.github.thewisekd.phopencode.feature.notify.logic.contract

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val TOPIC = "Abc_DEF-ghi0123456789xy"

/** The ntfy values the setup keeps: the topic, the server and the subscription link. */
class NtfyTest {
    @Test
    fun `a topic has at least 128 bits in ntfy's own alphabet`() {
        assertEquals(128, NtfyTopic.MIN_BITS)
        assertTrue(NtfyTopic.MIN_LENGTH * NtfyTopic.BITS_PER_CHARACTER >= NtfyTopic.MIN_BITS)
        assertEquals(6, NtfyTopic.BITS_PER_CHARACTER)
        assertEquals(64, NtfyTopic.MAX_LENGTH)
        assertEquals(64, NtfyTopic.ALPHABET.length)
        assertEquals(NtfyTopic.ALPHABET.length, NtfyTopic.ALPHABET.toSet().size)
    }

    @Test
    fun `a topic is read back only when it is strong enough and valid`() {
        assertEquals(TOPIC, NtfyTopic.of(TOPIC)?.reveal())
        assertNotNull(NtfyTopic.of("a".repeat(NtfyTopic.MIN_LENGTH)))
        assertNotNull(NtfyTopic.of("a".repeat(NtfyTopic.MAX_LENGTH)))

        assertNull(NtfyTopic.of(""))
        assertNull(NtfyTopic.of("a".repeat(NtfyTopic.MIN_LENGTH - 1)), "too short for 128 bits")
        assertNull(NtfyTopic.of("a".repeat(NtfyTopic.MAX_LENGTH + 1)), "longer than the service accepts")
        assertNull(NtfyTopic.of(TOPIC.dropLast(1) + "/"))
        assertNull(NtfyTopic.of(TOPIC.dropLast(1) + " "))
        assertNull(NtfyTopic.of(TOPIC.dropLast(1) + "ş"))
    }

    @Test
    fun `a topic never prints itself`() {
        val topic = NtfyTopic.of(TOPIC)!!

        assertFalse(TOPIC in topic.toString())
        assertFalse(TOPIC in NtfySettings(NtfyServer.DEFAULT, topic).toString())
    }

    @Test
    fun `the default server is ntfy_sh over https`() {
        assertEquals("https://ntfy.sh", NtfyServer.DEFAULT.url)
        assertEquals("ntfy.sh", NtfyServer.DEFAULT.host)
        assertEquals(NtfyServer.DEFAULT, NtfyServer.parse("https://ntfy.sh"))
        assertEquals(NtfyServer.DEFAULT, NtfyServer.parse("  https://NTFY.sh/  "))
    }

    @Test
    fun `a server of the user's own is https with a host and maybe a port`() {
        val server = NtfyServer.parse("https://push.example.invalid:8443")

        assertEquals("https://push.example.invalid:8443", server?.url)
        assertEquals("push.example.invalid:8443", server?.host)
        assertNull(NtfyServer.problemOf("https://push.example.invalid:8443"))
    }

    @Test
    fun `a server that is not a plain https origin is refused with its reason`() {
        val refused = mapOf(
            "" to NtfyServerProblem.Blank,
            "   " to NtfyServerProblem.Blank,
            "http://ntfy.sh" to NtfyServerProblem.NotHttps,
            "ntfy.sh" to NtfyServerProblem.NotHttps,
            "ftp://ntfy.sh" to NtfyServerProblem.NotHttps,
            "https://" to NtfyServerProblem.Invalid,
            "https://user:pass@ntfy.sh" to NtfyServerProblem.Invalid,
            "https://ntfy.sh/topic" to NtfyServerProblem.Invalid,
            "https://ntfy.sh?x=1" to NtfyServerProblem.Invalid,
            "https://ntfy.sh#x" to NtfyServerProblem.Invalid,
            "https://ntfy .sh" to NtfyServerProblem.Invalid,
            "https://ntfy.sh:0" to NtfyServerProblem.Invalid,
            "https://ntfy.sh:70000" to NtfyServerProblem.Invalid,
        )

        refused.forEach { (text, problem) ->
            assertEquals(problem, NtfyServer.problemOf(text), text)
            assertNull(NtfyServer.parse(text), text)
        }
    }

    @Test
    fun `the subscription link names the server's host and the topic`() {
        val topic = NtfyTopic.of(TOPIC)!!

        assertEquals("ntfy://ntfy.sh/$TOPIC", NtfyServer.DEFAULT.subscriptionLink(topic))
        assertEquals(
            "ntfy://push.example.invalid:8443/$TOPIC",
            NtfyServer.parse("https://push.example.invalid:8443")!!.subscriptionLink(topic),
        )
    }

    @Test
    fun `a server of the user's own prints masked`() {
        val server = NtfyServer.parse("https://push.example.invalid")!!

        assertFalse("example" in server.toString())
        assertNotEquals(NtfyServer.DEFAULT, server)
    }

    @Test
    fun `the topic and the server are stored under their own fixed references`() {
        assertNotEquals(NotifySecrets.TOPIC, NotifySecrets.SERVER)
        assertEquals("notify-ntfy-topic", NotifySecrets.TOPIC.id)
        assertEquals("notify-ntfy-server", NotifySecrets.SERVER.id)
    }
}
