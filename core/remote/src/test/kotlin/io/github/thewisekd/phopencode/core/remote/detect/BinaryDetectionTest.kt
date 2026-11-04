package io.github.thewisekd.phopencode.core.remote.detect

import io.github.thewisekd.phopencode.core.ssh.api.SshException
import io.github.thewisekd.phopencode.core.ssh.quote.ShellQuote
import io.github.thewisekd.phopencode.core.testing.ssh.FakeRemoteExec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest

class BinaryDetectionTest {
    private val exec = FakeRemoteExec()
    private val detector = ServerDetector(exec)

    private val onPath = "/usr/local/bin/opencode"
    private val inHome = "/home/u/.opencode/bin/opencode"

    private fun locate(vararg lines: String) = exec.on(DetectCommands.LOCATE) {
        stdout(lines.joinToString("") { "$it\n" })
        exit(0)
    }

    private fun loginLocate(output: String?) = exec.on(DetectCommands.LOGIN_LOCATE) {
        if (output == null) exit(1) else {
            stdout(output)
            exit(0)
        }
    }

    private fun version(path: String, stdout: String, stderr: String = "", code: Int = 0) =
        exec.on(DetectCommands.version(path)) {
            if (stdout.isNotEmpty()) stdout(stdout)
            if (stderr.isNotEmpty()) stderr(stderr)
            exit(code)
        }

    @Test
    fun `finds opencode on the PATH and reads its version`() = runTest {
        locate("path $onPath", "known $inHome")
        loginLocate("$onPath\n")
        version(onPath, "2.2.5\n")
        assertEquals(
            BinaryLookup.Found(onPath, BinarySource.Path, VersionCheck.Known(OpencodeVersion(2, 2, 5, null))),
            detector.findBinary(),
        )
    }

    @Test
    fun `finds opencode in the home install folder when it is not on any PATH`() = runTest {
        locate("known $inHome")
        loginLocate(null)
        version(inHome, "opencode v2.2.5\n")
        assertEquals(
            BinaryLookup.Found(inHome, BinarySource.KnownLocation, VersionCheck.Known(OpencodeVersion(2, 2, 5, null))),
            detector.findBinary(),
        )
    }

    @Test
    fun `finds opencode on the login PATH below profile noise`() = runTest {
        val custom = "/home/u/tools/bin/opencode"
        locate("known $inHome")
        loginLocate("Welcome to the server\n/home/u/notes\n$custom\n")
        version(custom, "2.3.0\n")
        version(inHome, "2.2.5\n")
        assertEquals(
            BinaryLookup.Found(custom, BinarySource.LoginPath, VersionCheck.Known(OpencodeVersion(2, 3, 0, null))),
            detector.findBinary(),
        )
    }

    @Test
    fun `reports a clear result when opencode is missing`() = runTest {
        locate()
        loginLocate(null)
        assertEquals(BinaryLookup.Missing, detector.findBinary())
    }

    @Test
    fun `missing when the login shell is absent too, which a fake reports as exit 127`() = runTest {
        locate()
        assertEquals(BinaryLookup.Missing, detector.findBinary())
    }

    @Test
    fun `ignores lines that are not absolute opencode paths`() = runTest {
        locate("path opencode", "known /home/u/bin/notopencode", "path /tmp/opencode\u001B[31m", "known", "junk /x/opencode")
        loginLocate("alias opencode='/x/opencode'\n")
        assertEquals(BinaryLookup.Missing, detector.findBinary())
        assertEquals(listOf(DetectCommands.LOCATE, DetectCommands.LOGIN_LOCATE), exec.executions.map { it.command })
    }

    @Test
    fun `reports a version it cannot parse`() = runTest {
        locate("path $onPath")
        version(onPath, "local\n")
        assertEquals(BinaryLookup.Found(onPath, BinarySource.Path, VersionCheck.Unparseable), detector.findBinary())
    }

    @Test
    fun `reads a version printed on stderr`() = runTest {
        locate("path $onPath")
        version(onPath, "", stderr = "2.2.5\n")
        assertEquals(
            BinaryLookup.Found(onPath, BinarySource.Path, VersionCheck.Known(OpencodeVersion(2, 2, 5, null))),
            detector.findBinary(),
        )
    }

    @Test
    fun `reports a failing version command`() = runTest {
        locate("path $onPath")
        version(onPath, "", stderr = "error: cannot execute\n", code = 126)
        assertEquals(BinaryLookup.Found(onPath, BinarySource.Path, VersionCheck.Failed(126, null)), detector.findBinary())
    }

    @Test
    fun `reports a version command that times out`() = runTest {
        locate("path $onPath")
        exec.on(DetectCommands.version(onPath)) { awaitCancellation() }
        assertEquals(BinaryLookup.Found(onPath, BinarySource.Path, VersionCheck.TimedOut), detector.findBinary())
    }

    @Test
    fun `falls back to the next candidate when the first one has no version`() = runTest {
        locate("path $onPath", "known $inHome")
        version(onPath, "", code = 1)
        version(inHome, "2.2.5")
        assertEquals(
            BinaryLookup.Found(inHome, BinarySource.KnownLocation, VersionCheck.Known(OpencodeVersion(2, 2, 5, null))),
            detector.findBinary(),
        )
    }

    @Test
    fun `keeps the first candidate's failure when none has a version`() = runTest {
        locate("path $onPath", "known $inHome")
        version(onPath, "", code = 1)
        version(inHome, "local")
        assertEquals(BinaryLookup.Found(onPath, BinarySource.Path, VersionCheck.Failed(1, null)), detector.findBinary())
    }

    @Test
    fun `tries each path once`() = runTest {
        locate("path $inHome", "known $inHome")
        loginLocate("$inHome\n")
        version(inHome, "", code = 1)
        detector.findBinary()
        assertEquals(1, exec.executions.count { it.command == DetectCommands.version(inHome) })
    }

    @Test
    fun `quotes the path it runs`() = runTest {
        val odd = "/home/u/my \$(touch x) 'tools'/opencode"
        assertTrue(DetectCommands.version(odd).startsWith(ShellQuote.quote(odd) + " "))
        locate("known $odd")
        version(odd, "2.2.5")
        assertEquals(
            BinaryLookup.Found(odd, BinarySource.KnownLocation, VersionCheck.Known(OpencodeVersion(2, 2, 5, null))),
            detector.findBinary(),
        )
    }

    @Test
    fun `reports a lookup that timed out`() = runTest {
        exec.on(DetectCommands.LOCATE) { awaitCancellation() }
        exec.on(DetectCommands.LOGIN_LOCATE) { awaitCancellation() }
        assertEquals(BinaryLookup.Failed(DetectFailure.TimedOut), detector.findBinary())
    }

    @Test
    fun `uses what one lookup found when the other timed out`() = runTest {
        locate("known $inHome")
        exec.on(DetectCommands.LOGIN_LOCATE) { awaitCancellation() }
        version(inHome, "2.2.5")
        assertEquals(
            BinaryLookup.Found(inHome, BinarySource.KnownLocation, VersionCheck.Known(OpencodeVersion(2, 2, 5, null))),
            detector.findBinary(),
        )
    }

    @Test
    fun `a lost connection is not a result`() = runTest {
        exec.disconnect()
        assertFailsWith<SshException.Disconnected> { detector.findBinary() }
    }
}
