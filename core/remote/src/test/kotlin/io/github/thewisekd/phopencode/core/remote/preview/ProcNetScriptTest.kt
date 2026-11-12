package io.github.thewisekd.phopencode.core.remote.preview

import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The `/proc/net` fallback run by this machine's `sh`, on table files in a temporary folder. */
class ProcNetScriptTest {
    private val dir: Path = Files.createTempDirectory("phopencode-procnet")
    private val tcp = dir.resolve("tcp")
    private val tcp6 = dir.resolve("tcp6")
    private val littleEndian = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN

    /** The byte order line the script prints on this machine. */
    private val order = if (littleEndian) "E 0001\n" else "E 0100\n"

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    private fun run(path: String? = null): Pair<Int, String> {
        val builder = ProcessBuilder("/bin/sh", "-c", PortListCommands.procNetScript(tcp.toString(), tcp6.toString()))
        builder.redirectError(ProcessBuilder.Redirect.DISCARD)
        if (path != null) builder.environment()["PATH"] = path
        val process = builder.start()
        process.outputStream.close()
        val text = process.inputStream.readAllBytes().decodeToString()
        check(process.waitFor(30, TimeUnit.SECONDS)) { "Script hung" }
        return process.exitValue() to text
    }

    private val v4 = """
        |  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode
        |   0: 0100007F:1F90 00000000:0000 0A 00000000:00000000 00:00000000 00000000  1000        0 12345 1 0000000000000000 100 0 0 10 0
        |   1: 0100007F:1F90 0100007F:9C40 01 00000000:00000000 00:00000000 00000000  1000        0 12346 1 0000000000000000 20 4 30 10 -1
        |   2: 00000000:0016 00000000:0000 0A 00000000:00000000 00:00000000 00000000     0        0 222 1 0000000000000000 100 0 0 10 0
    """.trimMargin()

    private val v6 = """
        |  sl  local_address                         remote_address                        st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode
        |   0: 00000000000000000000000001000000:0277 00000000000000000000000000000000:0000 0A 00000000:00000000 00:00000000 00000000     0        0 333 1 0000000000000000 100 0 0 10 0
    """.trimMargin()

    @Test
    fun `prints the listening rows of both tables`() {
        tcp.writeText(v4 + "\n")
        tcp6.writeText(v6 + "\n")
        assertEquals(0 to order + "4 0100007F:1F90\n4 00000000:0016\n6 00000000000000000000000001000000:0277\n", run())
    }

    @Test
    fun `one table is enough`() {
        tcp.writeText(v4 + "\n")
        assertEquals(0 to order + "4 0100007F:1F90\n4 00000000:0016\n", run())
    }

    @Test
    fun `without od the order is empty and nothing is read`() {
        tcp.writeText(v4 + "\n")
        val bin = Files.createDirectory(dir.resolve("bin"))
        for (tool in listOf("awk", "tr")) {
            val found = listOf("/usr/bin/$tool", "/bin/$tool").map { Path.of(it) }.first { Files.isExecutable(it) }
            Files.createSymbolicLink(bin.resolve(tool), found)
        }
        val (exit, output) = run(path = bin.toString())
        assertEquals(0 to "E \n4 0100007F:1F90\n4 00000000:0016\n", exit to output)
        assertEquals(null, ListenTable.fromProcNet(output))
    }

    @Test
    fun `no table is unsupported`() {
        assertEquals(PortListCommands.NO_TABLES to order, run())
    }

    @Test
    fun `the parsed output of a real table run`() {
        tcp.writeText(v4 + "\n")
        tcp6.writeText(v6 + "\n")
        // The sample tables are in little-endian order, as this machine's kernel would print them.
        val expected = if (littleEndian) setOf(8080, 22, 631) else setOf(22)
        assertEquals(expected, ListenTable.fromProcNet(run().second))
    }
}
