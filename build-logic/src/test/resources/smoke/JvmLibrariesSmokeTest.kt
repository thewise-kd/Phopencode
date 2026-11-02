import app.cash.turbine.test
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.forward.AcceptAllForwardingFilter
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.io.InputStream
import java.net.InetAddress
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.assertContains
import kotlin.test.assertEquals

@Serializable
private data class Sample(val name: String)

/** MockWebServer keeps the connection open, so a read to end of stream would never return. */
private fun InputStream.readUntil(marker: String): String {
    val received = StringBuilder()
    val buffer = ByteArray(1024)
    while (marker !in received) {
        val count = read(buffer)
        check(count >= 0) { "the stream ended after: $received" }
        received.append(String(buffer, 0, count))
    }
    return received.toString()
}

@Timeout(60)
class JvmLibrariesSmokeTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `serialization compiler plugin and runtime agree`() {
        assertEquals(Sample("a"), json.decodeFromString<Sample>("""{"name":"a","extra":1}"""))
    }

    @Test
    fun `turbine reads a flow under coroutines test`() = runTest {
        flowOf(1, 2).test {
            assertEquals(1, awaitItem())
            assertEquals(2, awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun `koin resolves a definition`() {
        val koin = koinApplication { modules(module { single { Sample("koin") } }) }.koin
        assertEquals("koin", koin.get<Sample>().name)
    }

    @Test
    fun `okhttp reads server sent events from mockwebserver3`() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse.Builder()
                    .addHeader("Content-Type", "text/event-stream")
                    .body(": heartbeat\n\ndata: hello\n\n")
                    .build(),
            )
            server.start()
            val received = CompletableFuture<String>()
            val listener = object : EventSourceListener() {
                override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                    received.complete(data)
                }

                override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                    received.completeExceptionally(t ?: IllegalStateException("failed: $response"))
                }
            }
            val client = OkHttpClient()
            val source = EventSources.createFactory(client)
                .newEventSource(Request.Builder().url(server.url("/event")).build(), listener)
            assertEquals("hello", received.get(30, TimeUnit.SECONDS))
            source.cancel()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    @Test
    fun `sshj talks to MINA over SFTP and a direct-tcpip channel`() = runBlocking {
        val directory = Files.createTempDirectory("smoke")
        Files.writeString(directory.resolve("marker.txt"), "marker")
        val server = SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"
            port = 0
            keyPairProvider = SimpleGeneratorHostKeyProvider(directory.resolve("host.ser"))
            setPasswordAuthenticator { user, password, _ -> user == "user" && password == "generated" }
            subsystemFactories = listOf(SftpSubsystemFactory())
            forwardingFilter = AcceptAllForwardingFilter.INSTANCE
        }
        server.start()
        try {
            MockWebServer().use { web ->
                web.enqueue(MockResponse.Builder().body("through the tunnel").build())
                web.start()
                SSHClient().use { ssh ->
                    ssh.addHostKeyVerifier(PromiscuousVerifier())
                    ssh.connect(InetAddress.getByName("127.0.0.1"), server.port)
                    ssh.authPassword("user", "generated")

                    ssh.newSFTPClient().use { sftp ->
                        assertContains(sftp.ls(directory.toString()).map { it.name }, "marker.txt")
                    }

                    ssh.newDirectConnection("127.0.0.1", web.port).use { channel ->
                        channel.outputStream.write("GET / HTTP/1.1\r\nHost: tunnel\r\n\r\n".toByteArray())
                        channel.outputStream.flush()
                        assertContains(channel.inputStream.readUntil("through the tunnel"), "through the tunnel")
                    }
                }
            }
        } finally {
            server.stop(true)
        }
    }
}
