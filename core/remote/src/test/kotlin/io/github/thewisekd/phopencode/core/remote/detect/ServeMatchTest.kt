package io.github.thewisekd.phopencode.core.remote.detect

import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The scan's process matchers, run by this machine's own `sh`, `tr` and `awk` on argument lists
 * as `/proc/<pid>/cmdline` and `ps -o pid= -o args=` give them.
 */
class ServeMatchTest {
    /** Whether [DetectCommands.PROC_MATCH] accepts [args] read from a NUL-separated cmdline. */
    private fun procMatches(vararg args: String): Boolean {
        val cmdline = args.joinToString("") { "$it\u0000" }.encodeToByteArray()
        val process = ProcessBuilder("sh", "-c", DetectCommands.PROC_MATCH).redirectErrorStream(true).start()
        process.outputStream.use { it.write(cmdline) }
        process.inputStream.readAllBytes()
        check(process.waitFor(10, TimeUnit.SECONDS)) { "Matcher hung" }
        return process.exitValue() == 0
    }

    /** The process IDs [DetectCommands.PS_MATCH] prints for [lines] of `ps` output. */
    private fun psMatches(vararg lines: String): List<String> {
        val process = ProcessBuilder("sh", "-c", DetectCommands.PS_MATCH).start()
        process.outputStream.use { it.write(lines.joinToString("") { "$it\n" }.encodeToByteArray()) }
        val output = process.inputStream.readAllBytes().decodeToString()
        check(process.waitFor(10, TimeUnit.SECONDS)) { "Matcher hung" }
        return output.lines().filter { it.isNotEmpty() }
    }

    private val servers = listOf(
        listOf("opencode", "serve"),
        listOf("/home/u/.opencode/bin/opencode", "serve", "--hostname", "127.0.0.1", "--port", "4096"),
        listOf("opencode", "--print-logs", "serve"),
        listOf("opencode", "--log-level", "DEBUG", "serve"),
        listOf("opencode", "--log-level=DEBUG", "serve"),
        listOf("node", "/usr/lib/node_modules/opencode-ai/bin/opencode", "serve"),
        listOf("/usr/bin/bun", "/home/u/.bun/bin/opencode", "serve"),
        listOf("/usr/bin/python3", "/tmp/fake/opencode", "serve"),
        listOf("/tmp/x/opencode", "serve"),
    )

    private val others = listOf(
        listOf("opencode", "run", "serve"),
        listOf("opencode", "run", "--", "serve"),
        listOf("opencode", "run", "--model", "serve"),
        listOf("opencode", "--log-level", "serve"),
        listOf("opencode"),
        listOf("opencode", "--version"),
        listOf("opencode", "server"),
        listOf("opencode-wrapper", "serve"),
        listOf("vim", "opencode", "serve"),
        listOf("sh", "-c", "opencode serve"),
        listOf("node", "--inspect", "/x/opencode", "serve"),
        listOf("serve", "opencode"),
        listOf("opencode", "x\nserve"),
        listOf("opencode\nserve"),
        listOf("/tmp/a b/opencode serve helper"),
        listOf("/tmp/opencode serve helper"),
        listOf("/tmp/opencode serve helper", "--port", "4096"),
        listOf("/usr/bin/python3", "/tmp/a b/opencode serve helper", "serve"),
    )

    @Test
    fun `proc matcher accepts serve only as the opencode subcommand`() {
        servers.forEach { assertEquals(true, procMatches(*it.toTypedArray()), "$it") }
        others.forEach { assertEquals(false, procMatches(*it.toTypedArray()), "$it") }
    }

    @Test
    fun `ps matcher accepts serve only as the opencode subcommand`() {
        // ps joins arguments with spaces, so it sees an argument with spaces as several words.
        val lines = (servers + others).filter { args -> args.none { '\n' in it } }
        val expected = lines.indices.filter { lines[it] in servers }.map { (it + 100).toString() }
        assertEquals(
            expected,
            psMatches(*lines.mapIndexed { index, args -> "  ${index + 100} " + args.joinToString(" ") }.toTypedArray()),
        )
    }

    @Test
    fun `the proc scan reports that it could read its own process`() {
        val script = "pids=''\nfound=''\n" + DetectCommands.PROC_SCAN + "\nprintf 'found=%s\\n' \"\$found\"\n"
        val process = ProcessBuilder("sh", "-c", script).redirectErrorStream(true).start()
        process.outputStream.close()
        val text = process.inputStream.readAllBytes().decodeToString()
        check(process.waitFor(60, TimeUnit.SECONDS)) { "Scan hung" }
        assertEquals("H proc\nfound=1\n", text.lines().filter { !it.startsWith("P ") }.joinToString("\n"))
    }
}
