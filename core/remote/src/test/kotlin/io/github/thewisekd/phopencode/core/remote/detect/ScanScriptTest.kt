package io.github.thewisekd.phopencode.core.remote.detect

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Parts of the scan script run by this machine's `sh`, with fake `ps`, `ss` and `lsof` placed
 * first on `PATH`. A fake sees `$wide` (`-ww` was given) and `$kind` (`comm` or `args`).
 */
class ScanScriptTest {
    private val bin: Path = Files.createTempDirectory("phopencode-fake-tools")

    @AfterTest
    fun tearDown() {
        bin.toFile().deleteRecursively()
    }

    private fun fake(name: String, body: String) {
        val file = bin.resolve(name)
        file.writeText(
            "#!/bin/sh\n" +
                "case \" \$* \" in *\" -ww \"*) wide=1 ;; *) wide= ;; esac\n" +
                "case \"\$*\" in *comm=*) kind=comm ;; *) kind=args ;; esac\n" +
                body + "\n",
        )
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwx------"))
    }

    private fun run(script: String): String {
        val builder = ProcessBuilder("sh", "-c", script).redirectErrorStream(true)
        builder.environment()["PATH"] = "$bin:/usr/bin:/bin"
        val process = builder.start()
        process.outputStream.close()
        val text = process.inputStream.readAllBytes().decodeToString()
        check(process.waitFor(30, TimeUnit.SECONDS)) { "Script hung" }
        return text
    }

    private fun psScan(): String =
        run("pids=''\nfound=''\n" + DetectCommands.PS_SCAN + "\nprintf 'pids=%s found=%s\\n' \"\$(echo \$pids)\" \"\$found\"\n")

    private fun listenScan(pids: String): String = run("pids='$pids'\n" + DetectCommands.LISTEN_SCAN + "\n")

    @Test
    fun `ps finds a server run directly as opencode`() {
        fake(
            "ps",
            """
            if [ "${'$'}kind" = comm ]; then printf '  10 opencode\n  12 opencode\n'; exit 0; fi
            printf '  10 /tmp/x/opencode serve\n  12 opencode run serve\n'
            """.trimIndent(),
        )
        assertEquals("H ps\npids=10 found=1\n", psScan())
    }

    @Test
    fun `ps never takes a program named opencode serve for a server`() {
        // Linux names the program by its file name, macOS by its path; both hold the space.
        listOf("opencode serve", "/tmp/opencode serve").forEach { comm ->
            fake(
                "ps",
                """
                if [ "${'$'}kind" = comm ]; then printf '  10 /tmp/x/opencode\n  11 $comm\n  12 node\n'; exit 0; fi
                printf '  10 /tmp/x/opencode serve\n  11 /tmp/opencode serve\n  12 node /x/opencode serve\n'
                """.trimIndent(),
            )
            assertEquals("H ps\npids=10 found=1\n", psScan(), comm)
        }
    }

    @Test
    fun `ps asks for unlimited width so a long command keeps its subcommand`() {
        fake(
            "ps",
            """
            if [ "${'$'}kind" = comm ]; then printf '  10 opencode\n'; exit 0; fi
            if [ -n "${'$'}wide" ]; then printf '  10 /tmp/x/opencode serve\n'; else printf '  10 /tmp/x/openco\n'; fi
            """.trimIndent(),
        )
        assertEquals("H ps\npids=10 found=1\n", psScan())
    }

    @Test
    fun `ps without the wide flag still works`() {
        fake(
            "ps",
            """
            if [ -n "${'$'}wide" ]; then echo 'ps: illegal option' >&2; exit 1; fi
            if [ "${'$'}kind" = comm ]; then printf '  10 opencode\n'; exit 0; fi
            printf '  10 /tmp/x/opencode serve\n'
            """.trimIndent(),
        )
        assertEquals("H ps\npids=10 found=1\n", psScan())
    }

    @Test
    fun `a failing ps is reported as failed, not as no servers`() {
        fake("ps", "printf '  10 /tmp/x/opencode serve\\n'\nexit 1")
        assertEquals("X ps\npids= found=\n", psScan())
        fake("ps", "if [ \"${'$'}kind\" = comm ]; then exit 1; fi\nprintf '  10 /tmp/x/opencode serve\\n'")
        assertEquals("X ps\npids= found=\n", psScan())
    }

    @Test
    fun `this machine's ps runs the scan`() {
        Files.delete(bin)
        Files.createDirectories(bin)
        assertTrue(psScan().startsWith("H ps\n"))
    }

    @Test
    fun `a failing ss is not a listener tool`() {
        fake("ss", "printf 'LISTEN 0 5 127.0.0.1:4096 0.0.0.0:*\\n'\nexit 1")
        fake("lsof", "exit 127")
        val output = listenScan("123")
        assertTrue("X ss\n" in output, output)
        assertTrue("H ss" !in output && "S " !in output, output)
    }

    @Test
    fun `a working ss is a listener tool`() {
        fake("ss", "printf 'LISTEN 0 5 127.0.0.1:4096 0.0.0.0:* users:((\"opencode\",pid=123,fd=3))\\n'")
        fake("lsof", "exit 127")
        val output = listenScan("123")
        assertTrue("H ss\nS LISTEN 0 5 127.0.0.1:4096" in output, output)
    }

    @Test
    fun `a failing lsof is not a listener tool`() {
        fake("ss", "exit 1")
        fake("lsof", "echo 'lsof: status error on /proc: Permission denied' >&2\nexit 1")
        val output = listenScan("123")
        assertTrue("X lsof\n" in output, output)
        assertTrue("H lsof" !in output && "L " !in output, output)
    }

    @Test
    fun `lsof that found nothing still ran`() {
        fake("ss", "exit 1")
        fake("lsof", "exit 1")
        assertTrue("H lsof\n" in listenScan("123"))
    }

    @Test
    fun `a working lsof reports its fields`() {
        fake("ss", "exit 1")
        fake("lsof", "printf 'p123\\nf3\\nn127.0.0.1:4096\\n'")
        val output = listenScan("123")
        assertTrue("H lsof\nL p123\nL f3\nL n127.0.0.1:4096\n" in output, output)
    }
}
