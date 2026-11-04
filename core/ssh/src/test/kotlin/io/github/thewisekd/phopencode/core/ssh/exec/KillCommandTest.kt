package io.github.thewisekd.phopencode.core.ssh.exec

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Timeout

/**
 * The kill command runs in whatever shell the server's account has, so it is run here in every
 * POSIX shell this machine has, against a real process group with a child: killing only the
 * group's leader would leave the child running.
 */
@Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class KillCommandTest {
    @Test
    fun `sh ends the whole group`() = endsGroup(listOf("sh"), "/bin/sh")

    @Test
    fun `dash ends the whole group`() = endsGroup(listOf("dash"), "/usr/bin/dash", "/bin/dash")

    @Test
    fun `bash in POSIX mode ends the whole group`() = endsGroup(listOf("bash", "--posix"), "/usr/bin/bash", "/bin/bash")

    @Test
    fun `bash ends the whole group`() = endsGroup(listOf("bash"), "/usr/bin/bash", "/bin/bash")

    @Test
    fun `busybox ash ends the whole group`() = endsGroup(listOf("busybox", "ash"), "/usr/bin/busybox", "/bin/busybox")

    private fun endsGroup(shell: List<String>, vararg installedAt: String) {
        assumeTrue(installedAt.any { Files.isExecutable(Path.of(it)) }, "${shell.first()} is not installed")
        // Like sshd: a shell leading its own session and group, with a child in that group.
        val leader = ProcessBuilder("setsid", "sh", "-c", "sleep 300 & echo \$!; wait")
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        val group = leader.pid()
        try {
            val child = leader.inputStream.bufferedReader().readLine().trim().toLong()
            assertTrue(LocalProcesses.alive(child), "The child must be running before the kill")

            val kill = ProcessBuilder(shell + listOf("-c", ProcessHeader.killCommand(group)))
                .redirectErrorStream(true)
                .start()
            assertTrue(kill.waitFor(10, TimeUnit.SECONDS), "kill did not finish")

            assertTrue(leader.waitFor(10, TimeUnit.SECONDS), "The group's leader survived")
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (LocalProcesses.alive(child) && System.nanoTime() < deadline) Thread.sleep(20)
            assertTrue(!LocalProcesses.alive(child), "The child survived: only the leader was killed")
        } finally {
            runCatching { ProcessBuilder("kill", "-s", "KILL", "--", "-$group").start().waitFor() }
            leader.destroyForcibly()
        }
    }
}
