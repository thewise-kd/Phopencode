package io.github.thewisekd.phopencode.core.remote.notify

import io.github.thewisekd.phopencode.core.ssh.api.SftpClient
import io.github.thewisekd.phopencode.core.ssh.api.SftpFailure
import io.github.thewisekd.phopencode.core.ssh.api.SftpWriteOptions
import io.github.thewisekd.phopencode.core.ssh.api.SshException
import io.github.thewisekd.phopencode.core.storage_api.SecretValue
import io.github.thewisekd.phopencode.core.testing.ssh.FakeCommand
import io.github.thewisekd.phopencode.core.testing.ssh.FakeRemoteExec
import io.github.thewisekd.phopencode.core.testing.ssh.FakeRemoteFiles
import io.github.thewisekd.phopencode.core.testing.ssh.FakeSftpClient
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The installer against core/testing's fake exec and SFTP, sharing one fake file tree, so each
 * command's effect on the server can be played: `mktemp` makes the private folder, `rm` removes
 * it, uninstall.sh removes the config.
 */
class NotifyInstallerTest {
    private val files = FakeRemoteFiles()
    private val exec = FakeRemoteExec()
    private val sftpClients = mutableListOf<FakeSftpClient>()
    private val bundle = mapOf(
        NotifyHelperFile.Service to "#!/usr/bin/env python3\nPK fake archive".encodeToByteArray(),
        NotifyHelperFile.Install to "#!/bin/sh\n# fake install.sh\n".encodeToByteArray(),
        NotifyHelperFile.Uninstall to "#!/bin/sh\n# fake uninstall.sh\n".encodeToByteArray(),
    )
    private val installer = NotifyInstaller(
        exec = exec,
        tempName = { tempName },
        openSftp = { FakeSftpClient("/", files).also { sftpClients += it } },
        files = { file -> ByteArrayInputStream(bundle.getValue(file)) },
    )

    // Obvious fake values, built at runtime: none is a real secret or address.
    private val password = "fake-password-" + List(8) { ('a'..'z').random() }.joinToString("")
    private val topic = "fakeTopic" + List(20) { ('A'..'Z').random() }.joinToString("")
    private val ntfyServer = "https://ntfy.example.invalid"
    private val serverUrl = "http://127.0.0.1:4096"
    private val target = NtfyTarget(ntfyServer, topic)

    private val tempName = "phopencode-notify.Ab3dE6gH"
    private val temp = "/tmp/$tempName"
    private val configBase = "/home/fake-user/.config"
    private val configDir = "$configBase/phopencode"
    private val configFile = "$configDir/notifyd.json"

    private fun access() = OpencodeAccess(serverUrl, SecretValue.ofText(password))

    private fun commands() = exec.executions.map { it.command }

    private fun givenProbe(python: String = "3.11", base: String = configBase) =
        exec.on(NotifyCommands.PROBE) {
            stdout("python $python\nconfig $base\n")
            exit(0)
        }

    private fun givenTemp(path: String = temp) = exec.on(NotifyCommands.makeTemp(tempName)) {
        if (path.startsWith("/") && path != "/") runCatching { files.addDirectory(path) }
        stdout("$path\n")
        exit(0)
    }

    private fun givenRemoveTemp() = exec.on(NotifyCommands.removeTemp(tempName)) {
        if (files.exists(temp)) FakeSftpClient("/", files).delete(temp, recursive = true)
        exit(0)
    }

    private fun givenConfigDir() = exec.on(NotifyCommands.makeConfigDir(configDir)) {
        files.addDirectory(configDir)
        exit(0)
    }

    private fun givenUninstall(code: Int = 0, line: String = "uninstalled") = exec.on(NotifyCommands.uninstall(temp)) {
        if (files.exists(configFile)) FakeSftpClient("/", files).delete(configFile)
        stdout("$line\n")
        exit(code)
    }

    private fun givenInstall(code: Int = 0, line: String = "installed systemd", script: FakeCommand? = null) =
        exec.on(NotifyCommands.install(temp), script ?: {
            if (code == 0) stdout("$line\n") else stderr("install.sh: error: fake failure\n")
            exit(code)
        })

    private fun givenStatus(line: String, code: Int = 0) = exec.on(NotifyCommands.status(temp)) {
        stdout("$line\n")
        exit(code)
    }

    private fun givenSendTest(code: Int = 0) = exec.on(NotifyCommands.sendTest(temp)) {
        exit(code)
    }

    private fun givenServer() {
        givenProbe()
        givenTemp()
        givenRemoveTemp()
        givenConfigDir()
        givenUninstall()
        givenInstall()
    }

    private fun configJson(path: String): JsonObject =
        Json.parseToJsonElement(files.content(path)!!.decodeToString()) as JsonObject

    // install

    @Test
    fun `install checks python, uploads to a private folder, reinstalls, writes the 0600 config and starts`() = runTest {
        givenServer()
        var uploads: Map<String, Pair<ByteArray, Int>> = emptyMap()
        givenInstall(script = {
            uploads = files.names(temp).associateWith { name -> files.content("$temp/$name")!! to files.mode("$temp/$name") }
            assertEquals(SftpWriteOptions.OWNER_ONLY, files.mode(configFile))
            stdout("installed cron\n")
            exit(0)
        })

        installer.install(access(), target)

        assertEquals(
            listOf(
                NotifyCommands.PROBE,
                NotifyCommands.makeTemp(tempName),
                NotifyCommands.uninstall(temp),
                NotifyCommands.makeConfigDir(configDir),
                NotifyCommands.install(temp),
                NotifyCommands.removeTemp(tempName),
            ),
            commands(),
        )
        assertEquals(setOf("notifyd.pyz", "install.sh", "uninstall.sh"), uploads.keys)
        NotifyHelperFile.entries.forEach { file ->
            val (content, mode) = uploads.getValue(file.fileName)
            assertContentEquals(bundle.getValue(file), content)
            assertEquals(SftpWriteOptions.OWNER_ONLY, mode)
        }
        assertEquals(SftpWriteOptions.OWNER_ONLY, files.mode(configFile))
        val config = configJson(configFile)
        assertEquals(setOf("server_url", "username", "password", "ntfy_server", "ntfy_topic"), config.keys)
        assertEquals(serverUrl, config.getValue("server_url").jsonPrimitive.content)
        assertEquals("opencode", config.getValue("username").jsonPrimitive.content)
        assertEquals(password, config.getValue("password").jsonPrimitive.content)
        assertEquals(ntfyServer, config.getValue("ntfy_server").jsonPrimitive.content)
        assertEquals(topic, config.getValue("ntfy_topic").jsonPrimitive.content)
        assertFalse(files.exists(temp), "the uploads are deleted")
        assertTrue(sftpClients.isNotEmpty())
    }

    @Test
    fun `the config is written atomically over an older one, keeping 0600`() = runTest {
        givenServer()
        files.addDirectory(configDir)
        files.addFile(configFile, "old".encodeToByteArray(), mode = 0b110_100_100)
        // An uninstall that leaves the file, so the write must replace it.
        exec.on(NotifyCommands.uninstall(temp)) {
            stdout("uninstalled\n")
            exit(0)
        }

        installer.install(access(), target)

        assertEquals(SftpWriteOptions.OWNER_ONLY, files.mode(configFile))
        assertEquals(topic, configJson(configFile).getValue("ntfy_topic").jsonPrimitive.content)
        assertEquals(setOf("notifyd.json"), files.names(configDir), "no temporary file is left")
    }

    @Test
    fun `install fails with python missing before anything is written`() = runTest {
        for (python in listOf("missing", "broken")) {
            givenServer()
            givenProbe(python = python)

            val failure = assertFailsWith<NotifyInstallException> { installer.install(access(), target) }

            assertEquals(NotifyInstallFailure.PythonMissing, failure.failure)
        }
        assertEquals(listOf(NotifyCommands.PROBE, NotifyCommands.PROBE), commands())
        assertFalse(files.exists("/tmp"))
        assertFalse(files.exists(configBase))
        assertTrue(sftpClients.isEmpty())
    }

    @Test
    fun `python older than 3_9 is too old, and versions compare as numbers`() = runTest {
        for (python in listOf("3.8", "2.7", "3.0")) {
            givenProbe(python = python)
            val failure = assertFailsWith<NotifyInstallException> { installer.install(access(), target) }
            assertEquals(NotifyInstallFailure.PythonTooOld, failure.failure)
        }
        assertTrue(commands().all { it == NotifyCommands.PROBE })
        assertTrue(sftpClients.isEmpty())

        for (python in listOf("3.9", "3.10", "3.13", "4.0")) {
            givenServer()
            givenProbe(python = python)
            installer.install(access(), target)
        }
    }

    @Test
    fun `an answer the probe cannot read fails as a step before anything is written`() = runTest {
        for (base in listOf("", "relative/.config", "/home/with space/.config", "/home/x/../y")) {
            givenProbe(base = base)
            val failure = assertFailsWith<NotifyInstallException> { installer.install(access(), target) }
            assertEquals(NotifyInstallFailure.StepFailed, failure.failure)
        }
        exec.on(NotifyCommands.PROBE) { exit(1) }
        assertEquals(
            NotifyInstallFailure.StepFailed,
            assertFailsWith<NotifyInstallException> { installer.install(access(), target) }.failure,
        )
        assertTrue(commands().all { it == NotifyCommands.PROBE })
        assertTrue(sftpClients.isEmpty())
    }

    @Test
    fun `a printed path that is not the folder asked for is refused, and only that folder is removed`() = runTest {
        for (path in listOf("relative/$tempName", "/", "/tmp/other.Ab3dE6gH", "/tmp/../$tempName", "")) {
            givenProbe()
            givenTemp(path)

            val failure = assertFailsWith<NotifyInstallException> { installer.install(access(), target) }

            assertEquals(NotifyInstallFailure.StepFailed, failure.failure)
        }
        val removals = commands().filter { "rm -rf" in it }
        assertEquals(List(5) { NotifyCommands.removeTemp(tempName) }, removals)
        assertTrue(sftpClients.isEmpty())
    }

    @Test
    fun `a cancellation after the folder was made, before the answer, still removes that folder`() = runTest {
        givenProbe()
        givenRemoveTemp()
        val created = CompletableDeferred<Unit>()
        exec.on(NotifyCommands.makeTemp(tempName)) {
            files.addDirectory(temp)
            created.complete(Unit)
            awaitCancellation()
        }

        val job = launch { installer.install(access(), target) }
        created.await()
        job.cancel()
        job.join()

        assertTrue(job.isCancelled)
        assertEquals(NotifyCommands.removeTemp(tempName), commands().last())
        assertFalse(files.exists(temp))
        assertTrue(sftpClients.isEmpty())
    }

    @Test
    fun `a timeout after the folder was made still removes that folder`() = runTest {
        givenProbe()
        givenRemoveTemp()
        exec.on(NotifyCommands.makeTemp(tempName)) {
            files.addDirectory(temp)
            awaitCancellation()
        }

        assertFailsWith<SshException.Timeout> { installer.remove() }

        assertEquals(NotifyCommands.removeTemp(tempName), commands().last())
        assertFalse(files.exists(temp))
    }

    @Test
    fun `an existing folder of that name fails the call and is neither used nor removed`() = runTest {
        givenProbe()
        givenRemoveTemp()
        files.addFile("$temp/theirs", "x".encodeToByteArray())
        exec.on(NotifyCommands.makeTemp(tempName)) { exit(NotifyCommands.TEMP_EXISTS) }

        val failure = assertFailsWith<NotifyInstallException> { installer.install(access(), target) }

        assertEquals(NotifyInstallFailure.StepFailed, failure.failure)
        assertEquals(listOf(NotifyCommands.PROBE, NotifyCommands.makeTemp(tempName)), commands())
        assertEquals(setOf("theirs"), files.names(temp))
        assertTrue(sftpClients.isEmpty())
    }

    @Test
    fun `a temporary folder the server refuses to make fails as a step and runs nothing more`() = runTest {
        givenProbe()
        exec.on(NotifyCommands.makeTemp(tempName)) { exit(3) }

        assertEquals(NotifyInstallFailure.StepFailed, assertFailsWith<NotifyInstallException> { installer.install(access(), target) }.failure)
        assertEquals(NotifyInstallFailure.StepFailed, assertFailsWith<NotifyInstallException> { installer.remove() }.failure)

        assertEquals(listOf(NotifyCommands.PROBE, NotifyCommands.makeTemp(tempName), NotifyCommands.makeTemp(tempName)), commands())
        assertTrue(sftpClients.isEmpty())
    }

    @Test
    fun `install script exit codes map to python failures or a failed step, and the config is removed`() = runTest {
        val cases = mapOf(
            3 to NotifyInstallFailure.PythonMissing,
            4 to NotifyInstallFailure.PythonTooOld,
            1 to NotifyInstallFailure.StepFailed,
            7 to NotifyInstallFailure.StepFailed,
            9 to NotifyInstallFailure.StepFailed,
            10 to NotifyInstallFailure.StepFailed,
            12 to NotifyInstallFailure.StepFailed,
            15 to NotifyInstallFailure.StepFailed,
        )
        for ((code, expected) in cases) {
            givenServer()
            givenInstall(code = code)

            val failure = assertFailsWith<NotifyInstallException> { installer.install(access(), target) }

            assertEquals(expected, failure.failure, "exit $code")
            assertFalse(files.exists(configFile), "exit $code leaves no config")
            assertFalse(files.exists(temp), "exit $code leaves no uploads")
        }
    }

    @Test
    fun `an exit 0 without the result line is not taken as installed`() = runTest {
        givenServer()
        givenInstall(line = "something else")

        val failure = assertFailsWith<NotifyInstallException> { installer.install(access(), target) }

        assertEquals(NotifyInstallFailure.StepFailed, failure.failure)
    }

    @Test
    fun `reinstall accepts an uninstall that could not read the crontab, and stops on any other failure`() = runTest {
        givenServer()
        givenUninstall(code = 4)
        installer.install(access(), target)
        assertTrue(NotifyCommands.install(temp) in commands())

        for (code in listOf(1, 2, 3, 5, 8, 12, 13, 15)) {
            exec.executions.size.let { before ->
                givenServer()
                givenUninstall(code = code, line = if (code == 3) "uninstalled still-running" else "uninstalled unknown")

                val failure = assertFailsWith<NotifyInstallException> { installer.install(access(), target) }

                assertEquals(NotifyInstallFailure.StepFailed, failure.failure, "exit $code")
                val ran = commands().drop(before)
                assertFalse(NotifyCommands.install(temp) in ran, "exit $code never starts")
                assertFalse(NotifyCommands.makeConfigDir(configDir) in ran, "exit $code writes no config")
                assertEquals(NotifyCommands.removeTemp(tempName), ran.last())
                assertFalse(files.exists(configFile))
            }
        }
    }

    @Test
    fun `an upload failure passes the SSH failure on and still removes the uploads`() = runTest {
        givenServer()
        val installer = NotifyInstaller(
            exec = exec,
            tempName = { tempName },
            openSftp = { FakeSftpClient("/", files).also { it.failNext(SshException.Sftp(SftpFailure.PermissionDenied)) } },
            files = { file -> ByteArrayInputStream(bundle.getValue(file)) },
        )

        assertFailsWith<SshException.Sftp> { installer.install(access(), target) }

        assertEquals(NotifyCommands.removeTemp(tempName), commands().last())
        assertFalse(files.exists(temp))
        assertFalse(NotifyCommands.install(temp) in commands())
    }

    @Test
    fun `a bundle that cannot be read fails as a step`() = runTest {
        givenServer()
        val installer = NotifyInstaller(
            exec = exec,
            tempName = { tempName },
            openSftp = { FakeSftpClient("/", files) },
            files = { throw java.io.IOException("fake missing resource") },
        )

        val failure = assertFailsWith<NotifyInstallException> { installer.install(access(), target) }

        assertEquals(NotifyInstallFailure.StepFailed, failure.failure)
        assertFalse(files.exists(temp))
    }

    /**
     * An SFTP client whose config write commits and then fails with what [after] makes, as a
     * real client can when the call is cancelled or the channel breaks right after the rename.
     */
    private fun failingAfterConfigWrite(after: () -> Throwable) = NotifyInstaller(
        exec = exec,
        tempName = { tempName },
        openSftp = {
            val inner = FakeSftpClient("/", files)
            object : SftpClient by inner {
                override suspend fun writeAtomic(
                    path: String,
                    source: InputStream,
                    options: SftpWriteOptions,
                    onProgress: (Long) -> Unit,
                ) {
                    inner.writeAtomic(path, source, options, onProgress)
                    if (path == configFile) {
                        assertTrue(files.exists(configFile), "the write has committed")
                        throw after()
                    }
                }
            }
        },
        files = { file -> ByteArrayInputStream(bundle.getValue(file)) },
    )

    @Test
    fun `a config whose write committed is deleted when cancellation follows before the start`() = runTest {
        givenServer()

        assertFailsWith<CancellationException> {
            failingAfterConfigWrite { CancellationException("fake") }.install(access(), target)
        }

        assertFalse(files.exists(configFile), "no password is left on the server")
        assertFalse(NotifyCommands.install(temp) in commands())
        assertFalse(files.exists(temp))
    }

    @Test
    fun `a config whose write committed is deleted when a failure follows before the start`() = runTest {
        givenServer()

        assertFailsWith<SshException.Disconnected> {
            failingAfterConfigWrite { SshException.Disconnected() }.install(access(), target)
        }

        assertFalse(files.exists(configFile), "no password is left on the server")
        assertFalse(NotifyCommands.install(temp) in commands())
    }

    @Test
    fun `a cancelled coroutine still deletes the committed config`() = runTest {
        givenServer()
        val installer = failingAfterConfigWrite {
            // Cancel the caller itself, as a real cancellation arriving after the rename would.
            CancellationException("fake")
        }
        val job = launch { installer.install(access(), target) }
        job.join()

        assertTrue(job.isCancelled)
        assertFalse(files.exists(configFile))
    }

    @Test
    fun `cancelling during the start still removes the uploads`() = runTest {
        givenServer()
        val started = CompletableDeferred<Unit>()
        givenInstall(script = {
            started.complete(Unit)
            awaitCancellation()
        })

        val job = launch { installer.install(access(), target) }
        started.await()
        job.cancel()
        job.join()

        assertTrue(job.isCancelled)
        assertEquals(NotifyCommands.removeTemp(tempName), commands().last())
        assertFalse(files.exists(temp))
    }

    @Test
    fun `cancellation passes through as it is`() = runTest {
        givenServer()
        val installer = NotifyInstaller(
            exec = exec,
            tempName = { tempName },
            openSftp = { throw CancellationException("fake") },
            files = { file -> ByteArrayInputStream(bundle.getValue(file)) },
        )

        assertFailsWith<CancellationException> { installer.install(access(), target) }
        assertEquals(NotifyCommands.removeTemp(tempName), commands().last())
    }

    // status

    @Test
    fun `status runs install_sh --status from a private folder and reads its line`() = runTest {
        val cases = mapOf(
            "status systemd running" to NotifyInstallStatus.Running,
            "status cron running" to NotifyInstallStatus.Running,
            "status none running" to NotifyInstallStatus.Running,
            "status systemd stopped" to NotifyInstallStatus.Stopped,
            "status cron stopped" to NotifyInstallStatus.Stopped,
            "status none stopped" to NotifyInstallStatus.NotInstalled,
        )
        givenTemp()
        givenRemoveTemp()
        for ((line, expected) in cases) {
            var uploaded = emptySet<String>()
            exec.on(NotifyCommands.status(temp)) {
                uploaded = files.names(temp)
                stdout("profile noise\n$line\n")
                exit(0)
            }

            assertEquals(expected, installer.status(), line)
            assertEquals(setOf("install.sh"), uploaded)
            assertFalse(files.exists(temp))
        }
        assertTrue(NotifyCommands.PROBE !in commands(), "status needs no python")
    }

    @Test
    fun `a status nobody can tell, or a failed status run, is an error`() = runTest {
        givenTemp()
        givenRemoveTemp()
        for (line in listOf("status none unknown", "status unknown stopped", "status systemd unknown")) {
            givenStatus(line)
            assertEquals(NotifyInstallFailure.StatusUnknown, assertFailsWith<NotifyInstallException> { installer.status() }.failure)
        }
        for ((line, code) in listOf("status systemd running" to 1, "garbage" to 0, "" to 0)) {
            givenStatus(line, code)
            assertEquals(NotifyInstallFailure.StepFailed, assertFailsWith<NotifyInstallException> { installer.status() }.failure)
        }
    }

    // remove

    @Test
    fun `remove runs uninstall_sh from a private folder and accepts exit 0 and 4`() = runTest {
        givenTemp()
        givenRemoveTemp()
        for (code in listOf(0, 4)) {
            var uploaded = emptySet<String>()
            exec.on(NotifyCommands.uninstall(temp)) {
                uploaded = files.names(temp)
                stdout("uninstalled\n")
                exit(code)
            }

            installer.remove()

            assertEquals(setOf("uninstall.sh"), uploaded)
            assertFalse(files.exists(temp))
        }
        assertEquals(
            listOf(NotifyCommands.makeTemp(tempName), NotifyCommands.uninstall(temp), NotifyCommands.removeTemp(tempName)),
            commands().take(3),
        )
    }

    @Test
    fun `remove fails when notifyd may still run or the scripts refuse`() = runTest {
        givenTemp()
        givenRemoveTemp()
        for (code in listOf(1, 2, 3, 5, 8, 12, 13, 15)) {
            givenUninstall(code = code, line = "uninstalled still-running")
            val failure = assertFailsWith<NotifyInstallException> { installer.remove() }
            assertEquals(NotifyInstallFailure.StepFailed, failure.failure, "exit $code")
            assertFalse(files.exists(temp))
        }
    }

    // sendTest

    @Test
    fun `sendTest runs notifyd --send-test with a temporary 0600 config that is removed afterwards`() = runTest {
        givenProbe()
        givenTemp()
        givenRemoveTemp()
        var config: JsonObject? = null
        var mode = 0
        var uploaded = emptySet<String>()
        exec.on(NotifyCommands.sendTest(temp)) {
            uploaded = files.names(temp)
            config = configJson("$temp/send-test.json")
            mode = files.mode("$temp/send-test.json")
            exit(0)
        }

        installer.sendTest(target)

        assertEquals(listOf(NotifyCommands.PROBE, NotifyCommands.makeTemp(tempName), NotifyCommands.sendTest(temp), NotifyCommands.removeTemp(tempName)), commands())
        assertEquals(setOf("notifyd.pyz", "send-test.json"), uploaded)
        assertEquals(SftpWriteOptions.OWNER_ONLY, mode)
        val sent = config!!
        assertEquals(topic, sent.getValue("ntfy_topic").jsonPrimitive.content)
        assertEquals(ntfyServer, sent.getValue("ntfy_server").jsonPrimitive.content)
        assertFalse(files.exists(temp))
        assertFalse(files.exists(configBase), "the service config is not touched")
    }

    @Test
    fun `sendTest maps a refused notification and other failures`() = runTest {
        givenProbe()
        givenTemp()
        givenRemoveTemp()
        givenSendTest(code = 3)
        assertEquals(NotifyInstallFailure.NtfyRejected, assertFailsWith<NotifyInstallException> { installer.sendTest(target) }.failure)
        for (code in listOf(1, 2, 4, 127)) {
            givenSendTest(code = code)
            assertEquals(NotifyInstallFailure.StepFailed, assertFailsWith<NotifyInstallException> { installer.sendTest(target) }.failure)
        }
        givenProbe(python = "missing")
        assertEquals(NotifyInstallFailure.PythonMissing, assertFailsWith<NotifyInstallException> { installer.sendTest(target) }.failure)
        givenProbe(python = "3.8")
        assertEquals(NotifyInstallFailure.PythonTooOld, assertFailsWith<NotifyInstallException> { installer.sendTest(target) }.failure)
        assertFalse(files.exists(temp))
    }

    // secrets

    @Test
    fun `secrets never reach a command line, stdin, a printout or an exception message`() = runTest {
        givenServer()
        installer.install(access(), target)
        givenInstall(code = 9)
        val failure = assertFailsWith<NotifyInstallException> { installer.install(access(), target) }
        givenSendTest(code = 3)
        val refused = assertFailsWith<NotifyInstallException> { installer.sendTest(target) }

        exec.executions.forEach { execution ->
            assertFalse(password in execution.command)
            assertFalse(topic in execution.command)
            assertEquals(null, execution.stdin, "no stdin is sent")
            assertFalse(password in execution.toString())
        }
        listOf(access().toString(), target.toString(), failure.toString(), refused.toString(), installer.toString())
            .forEach { text ->
                assertFalse(password in text, text)
                assertFalse(topic in text, text)
                assertFalse(ntfyServer in text, text)
            }
        assertEquals(null, failure.cause)
    }

    @Test
    fun `the installer leaves the access open for its owner, whose close wipes the password`() = runTest {
        givenServer()
        val access = access()
        installer.install(access, target)
        assertEquals(password, access.password.revealText())
        access.close()

        assertFailsWith<IllegalStateException> { access.password.revealText() }
    }
}
