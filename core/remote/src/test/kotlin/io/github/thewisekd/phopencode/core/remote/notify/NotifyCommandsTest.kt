package io.github.thewisekd.phopencode.core.remote.notify

import io.github.thewisekd.phopencode.core.ssh.quote.ShellQuote
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The fixed shell text, run by a local `sh` the way the login shell on the server would get it:
 * one command line. Only temporary folders are used: HOME, XDG_CONFIG_HOME and TMPDIR point into
 * one, so the real user's files are never touched.
 */
class NotifyCommandsTest {
    private val sandbox: File = Files.createTempDirectory("notify-commands").toFile()
    private val home = File(sandbox, "home").apply { mkdirs() }
    private val tmp = File(sandbox, "tmp").apply { mkdirs() }

    @AfterTest
    fun cleanUp() {
        sandbox.deleteRecursively()
    }

    private class Run(val stdout: String, val exit: Int)

    private fun run(command: String, env: Map<String, String> = emptyMap()): Run {
        val builder = ProcessBuilder("/bin/sh", "-c", command).redirectErrorStream(false)
        builder.environment().apply {
            clear()
            put("PATH", System.getenv("PATH") ?: "/usr/bin:/bin")
            put("HOME", home.absolutePath)
            put("TMPDIR", tmp.absolutePath)
            putAll(env)
        }
        val process = builder.start()
        process.outputStream.close()
        val stdout = process.inputStream.readBytes().decodeToString()
        assertTrue(process.waitFor(30, TimeUnit.SECONDS))
        return Run(stdout, process.exitValue())
    }

    private fun mode(file: File): String = PosixFilePermissions.toString(Files.getPosixFilePermissions(file.toPath()))

    @Test
    fun `the probe reports python and the config base the scripts use`() {
        val plain = run(NotifyCommands.PROBE)
        val probe = assertNotNull(NotifyCommands.parseProbe(plain.stdout))
        assertEquals(0, plain.exit)
        assertNotNull(probe.python, "this machine has python3")
        assertEquals(home.absolutePath + "/.config", probe.configBase)

        val xdg = File(sandbox, "xdg").absolutePath
        assertEquals(xdg, NotifyCommands.parseProbe(run(NotifyCommands.PROBE, mapOf("XDG_CONFIG_HOME" to xdg)).stdout)?.configBase)
        // A relative XDG_CONFIG_HOME is ignored, as by install.sh and config.py.
        assertEquals(
            home.absolutePath + "/.config",
            NotifyCommands.parseProbe(run(NotifyCommands.PROBE, mapOf("XDG_CONFIG_HOME" to "rel")).stdout)?.configBase,
        )
    }

    @Test
    fun `the probe tells a missing python3`() {
        val bin = File(sandbox, "bin").apply { mkdirs() }
        listOf("sh", "printf").forEach { tool ->
            val path = run("command -v $tool").stdout.trim()
            if (path.startsWith("/")) Files.createSymbolicLink(File(bin, tool).toPath(), File(path).toPath())
        }

        val probe = NotifyCommands.parseProbe(run(NotifyCommands.PROBE, mapOf("PATH" to bin.absolutePath)).stdout)

        assertNotNull(probe)
        assertNull(probe.python)
        assertEquals(home.absolutePath + "/.config", probe.configBase)
    }

    private fun newName() = NotifyCommands.newTempName(SecureRandom())

    @Test
    fun `the temporary folder is private, under TMPDIR, named on the phone, and removed by name`() {
        val name = newName()
        val made = run(NotifyCommands.makeTemp(name))
        val path = assertNotNull(NotifyCommands.parseTemp(made.stdout, name))

        assertEquals(0, made.exit)
        assertEquals(File(tmp, name).absolutePath, path)
        assertEquals("rwx------", mode(File(path)))

        File(path, "file").writeText("x")
        assertEquals(0, run(NotifyCommands.removeTemp(name)).exit)
        assertFalse(File(path).exists())
        assertTrue(tmp.exists())
    }

    @Test
    fun `an existing name is never used, making fails and leaves it as it is`() {
        val name = newName()
        val existing = File(tmp, name).apply { mkdirs() }
        File(existing, "theirs").writeText("x")
        val link = newName()
        Files.createSymbolicLink(File(tmp, link).toPath(), sandbox.toPath())

        val made = run(NotifyCommands.makeTemp(name))
        val linked = run(NotifyCommands.makeTemp(link))

        assertEquals(NotifyCommands.TEMP_EXISTS, made.exit)
        assertEquals(NotifyCommands.TEMP_EXISTS, linked.exit)
        assertNull(NotifyCommands.parseTemp(made.stdout, name))
        assertTrue(File(existing, "theirs").exists())
        assertTrue(Files.isSymbolicLink(File(tmp, link).toPath()))
    }

    @Test
    fun `names are unpredictable and of the shape both scripts take`() {
        val names = List(50) { newName() }

        assertEquals(50, names.toSet().size)
        names.forEach { assertTrue(Regex("phopencode-notify\\.[A-Za-z0-9]{16}").matches(it), it) }
        listOf("other.Ab3dE6gH", "phopencode-notify.", "phopencode-notify.a/b", "phopencode-notify.a b").forEach { bad ->
            assertEquals(2, run(NotifyCommands.makeTemp(bad)).exit, bad)
            assertEquals(2, run(NotifyCommands.removeTemp(bad)).exit, bad)
        }
        assertEquals(emptyList(), tmp.list()!!.toList())
    }

    @Test
    fun `a TMPDIR the parser would refuse makes no folder and prints nothing`() {
        val spaced = File(sandbox, "with space").apply { mkdirs() }
        val dotted = File(sandbox, "dots").apply { mkdirs() }
        listOf(spaced.absolutePath, dotted.absolutePath + "/../dots", dotted.absolutePath + "//x", "/").forEach { tmpdir ->
            val name = newName()
            val made = run(NotifyCommands.makeTemp(name), mapOf("TMPDIR" to tmpdir))

            assertTrue(made.exit != 0, tmpdir)
            assertNull(NotifyCommands.parseTemp(made.stdout, name), tmpdir)
            assertEquals(3, run(NotifyCommands.removeTemp(name), mapOf("TMPDIR" to tmpdir)).exit, tmpdir)
        }
        assertEquals(emptyList(), spaced.list()!!.toList(), "no folder is left behind")
        assertEquals(emptyList(), dotted.list()!!.toList(), "no folder is left behind")
    }

    @Test
    fun `the config folder is made private, its parents too`() {
        val dir = File(home, ".config/phopencode")

        assertEquals(0, run(NotifyCommands.makeConfigDir(dir.absolutePath)).exit)

        assertEquals("rwx------", mode(dir))
        assertEquals("rwx------", mode(dir.parentFile))
        dir.setReadable(true, false)
        assertEquals(0, run(NotifyCommands.makeConfigDir(dir.absolutePath)).exit)
        assertEquals("rwx------", mode(dir), "an existing one is made private again")
    }

    @Test
    fun `the script commands quote every path and use no sudo, kill or stop`() {
        val temp = "/tmp/phopencode-notify.Ab3dE6gH"
        assertEquals(ShellQuote.join(listOf("sh", "$temp/install.sh", "--status")), NotifyCommands.status(temp))
        assertEquals(ShellQuote.join(listOf("sh", "$temp/install.sh", "$temp/notifyd.pyz")), NotifyCommands.install(temp))
        assertEquals(ShellQuote.join(listOf("sh", "$temp/uninstall.sh")), NotifyCommands.uninstall(temp))
        assertEquals(
            ShellQuote.join(listOf("python3", "$temp/notifyd.pyz", "--send-test", "--config", "$temp/send-test.json")),
            NotifyCommands.sendTest(temp),
        )

        val all = listOf(
            NotifyCommands.PROBE,
            NotifyCommands.makeTemp("phopencode-notify.Ab3dE6gH"),
            NotifyCommands.makeConfigDir("/x"),
            NotifyCommands.status(temp),
            NotifyCommands.install(temp),
            NotifyCommands.uninstall(temp),
            NotifyCommands.sendTest(temp),
            NotifyCommands.removeTemp("phopencode-notify.Ab3dE6gH"),
        ).joinToString("\n")
        listOf("sudo", "kill", "systemctl", "reboot", "shutdown").forEach { word -> assertFalse(word in all, word) }
    }

    @Test
    fun `only a private folder of the expected shape is taken as the temporary one`() {
        val name = "phopencode-notify.Ab3dE6gH"
        assertEquals("/tmp/$name", NotifyCommands.parseTemp("noise\n/tmp/$name\n", name))
        assertEquals("/var/t_1/$name", NotifyCommands.parseTemp("/var/t_1/$name\n", name))
        assertNull(NotifyCommands.parseTemp("/tmp/$name\n", "phopencode-notify.Zz9yY8xX"), "another name")
        assertNull(NotifyCommands.parseTemp("/tmp/other\n", "other"), "a name of another shape")
        listOf(
            "",
            "/",
            "relative/phopencode-notify.Ab3dE6gH",
            "/tmp/other.Ab3dE6gH",
            "/tmp/../phopencode-notify.Ab3dE6gH",
            "/tmp/./phopencode-notify.Ab3dE6gH",
            "//phopencode-notify.Ab3dE6gH",
            "/tmp/phopencode-notify.Ab3d",
            "/tmp/phopencode-notify.Ab3dE6gH/x",
            "/t mp/phopencode-notify.Ab3dE6gH",
        ).forEach { assertNull(NotifyCommands.parseTemp("$it\n", name), it) }
    }
}
