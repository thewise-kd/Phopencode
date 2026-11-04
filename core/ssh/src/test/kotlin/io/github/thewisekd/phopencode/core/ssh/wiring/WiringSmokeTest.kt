package io.github.thewisekd.phopencode.core.ssh.wiring

import io.github.thewisekd.phopencode.core.model.FileEntryType
import io.github.thewisekd.phopencode.core.ssh.api.ExecRequest
import io.github.thewisekd.phopencode.core.ssh.api.HostKeyDecision
import io.github.thewisekd.phopencode.core.ssh.api.HostKeyVerifier
import io.github.thewisekd.phopencode.core.ssh.connect.SshjConnector
import io.github.thewisekd.phopencode.core.ssh.connect.password
import io.github.thewisekd.phopencode.core.ssh.quote.ShellQuote
import io.github.thewisekd.phopencode.core.ssh.tunnel.LOOPBACK
import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.Timeout

/** The default [SshjConnector] end to end: a command, files, HTTP through the tunnel, then close. */
@Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class WiringSmokeTest {
    private val closeables = mutableListOf<AutoCloseable>()

    private fun <T : AutoCloseable> T.closeLater(): T = also { closeables += it }

    @AfterTest
    fun tearDown() {
        closeables.asReversed().forEach { runCatching { it.close() } }
    }

    @Test
    fun `the default connector runs a command, moves files, tunnels HTTP and leaves nothing behind`() = runBlocking {
        withTimeout(90.seconds) {
            val server = WiringServer().closeLater()
            val web = MockWebServer().closeLater().apply { start(LOOPBACK, 0) }
            val work = server.home.resolve("çalışma alanı").createDirectories()
            work.resolve("merhaba.txt").writeText("merhaba dünya")

            val session = SshjConnector().connect(server.target, password(), ACCEPT).closeLater()

            // A command
            val result = session.exec.run(ExecRequest("cat " + ShellQuote.quote(work.resolve("merhaba.txt").toString())))
            assertEquals(0, result.exitCode)
            assertEquals("merhaba dünya", result.stdout.decodeToString())

            // Files, over one SFTP channel
            val sftp = session.openSftp(work.toString())
            val listed = sftp.list("").associate { it.name to it.type }
            assertEquals(mapOf<String, FileEntryType>("merhaba.txt" to FileEntryType.File), listed)
            assertEquals("merhaba dünya", sftp.read("merhaba.txt", 1024).decodeToString())
            sftp.writeAtomic("yeni dosya.txt", ByteArrayInputStream("yazıldı".encodeToByteArray()))
            assertEquals("yazıldı", work.resolve("yeni dosya.txt").readText())
            assertContentEquals("yazıldı".encodeToByteArray(), sftp.read("yeni dosya.txt", 1024))
            assertEquals(1, server.openSubsystems(), "Reads and writes share one SFTP channel")

            // HTTP through the tunnel
            web.enqueue(MockResponse.Builder().body("tünelden").build())
            val http = OkHttpClient.Builder()
                .socketFactory(session.socketFactory())
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build()
            try {
                http.newCall(Request.Builder().url("http://127.0.0.1:${web.port}/global/health").build()).execute().use {
                    assertEquals(200, it.code)
                    assertEquals("tünelden", it.body.string())
                }
                assertEquals(1, server.tunnelsOpened())
                // OkHttp keeps the connection pooled: closing the session must close it anyway.
                assertTrue(server.openChannels() >= 2, "An SFTP channel and a tunnel are open")

                session.close()
            } finally {
                http.dispatcher.executorService.shutdown()
            }

            assertTrue(!session.connected.value)
            eventually("no channel stays open on the server") { server.openChannels() == 0 }
            eventually("no SFTP subsystem stays open") { server.openSubsystems() == 0 }
            eventually("the server session ended") { server.activeSessions() == 0 }
            eventually("no sshj or phopencode thread is left") { leftoverThreads().isEmpty() }
            assertEquals(emptyList(), server.survivors(), "Processes left running")
        }
    }

    private companion object {
        val ACCEPT = HostKeyVerifier { _, _ -> HostKeyDecision.Accept }

        fun leftoverThreads(): List<String> = Thread.getAllStackTraces().keys
            .filter { it.isAlive && (it.name.startsWith("sshj-") || it.name.startsWith("phopencode-")) }
            .map { it.name }

        suspend fun eventually(what: String, within: Duration = 10.seconds, condition: () -> Boolean) {
            val started = TimeSource.Monotonic.markNow()
            while (!condition()) {
                if (started.elapsedNow() > within) throw AssertionError("Not within $within: $what (threads: ${leftoverThreads()})")
                delay(20.milliseconds)
            }
        }
    }
}
