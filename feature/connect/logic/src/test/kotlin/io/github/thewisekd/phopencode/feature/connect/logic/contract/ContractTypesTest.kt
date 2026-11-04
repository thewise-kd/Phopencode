package io.github.thewisekd.phopencode.feature.connect.logic.contract

import io.github.thewisekd.phopencode.core.model.FileEntry
import io.github.thewisekd.phopencode.core.model.FileEntryType
import io.github.thewisekd.phopencode.core.model.ServerProfileId
import io.github.thewisekd.phopencode.core.storage_api.HostKeyFingerprint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContractTypesTest {
    // Assembled at runtime, so that the source holds no secret-shaped literal.
    private val secret = listOf("hidden", "value", "42").joinToString("-")
    private val host = listOf("build", "example", "org").joinToString(".")

    private fun assertMasked(value: Any, vararg hidden: String) {
        val printed = value.toString()
        hidden.forEach { assertFalse(it in printed, "$printed shows a hidden value") }
    }

    @Test
    fun `screens start loading with nothing to show`() {
        with(ServerListState()) {
            assertTrue(loading)
            assertTrue(servers.isEmpty())
            assertNull(pendingDelete)
            assertNull(error)
        }
        with(ServerFormState(isNew = true)) {
            assertTrue(loading)
            assertEquals("", host)
            assertEquals("22", port)
            assertEquals(ServerFormAuthMethod.Password, authMethod)
            assertFalse(passwordEntered)
            assertFalse(storedPassword)
            assertTrue(fieldErrors.isEmpty())
            assertFalse(saving)
            assertNull(error)
        }
        with(KeysState()) {
            assertTrue(loading)
            assertTrue(keys.isEmpty())
            assertFalse(busy)
            assertFalse(importAwaitingPassphrase)
            assertNull(pendingDelete)
            assertNull(error)
        }
        with(HostKeyState()) {
            assertTrue(loading)
            assertNull(request)
            assertFalse(deciding)
            assertNull(error)
        }
        with(FolderState()) {
            assertTrue(loading)
            assertNull(path)
            assertTrue(entries.isEmpty())
            assertTrue(recent.isEmpty())
            assertNull(error)
        }
        with(ProgressState()) {
            assertEquals(ProgressStep.Ssh, step)
            assertNull(error)
            assertTrue(actions.isEmpty())
            assertFalse(askingServerPassword)
        }
    }

    @Test
    fun `the folder picker goes up from anywhere but the root`() {
        assertFalse(FolderState().canGoUp)
        assertFalse(FolderState(path = "/").canGoUp)
        assertTrue(FolderState(path = "/srv").canGoUp)
        assertTrue(FolderState(path = "/srv/çalışma alanı").canGoUp)
    }

    @Test
    fun `the connection steps run in order`() {
        assertEquals(
            listOf(ProgressStep.Ssh, ProgressStep.HostApproval, ProgressStep.Server, ProgressStep.Api, ProgressStep.Connected),
            ProgressStep.entries,
        )
    }

    @Test
    fun `intents that carry a secret do not print it`() {
        assertMasked(ServerFormIntent.PasswordChanged(secret), secret)
        assertMasked(ServerFormIntent.OpencodePasswordChanged(secret), secret)
        assertMasked(KeysIntent.Generate(name = "laptop", passphrase = secret), secret)
        assertMasked(KeysIntent.SubmitPassphrase(secret), secret)
        assertMasked(ProgressIntent.SubmitServerPassword(secret), secret)
        val content = secret.encodeToByteArray()
        assertMasked(KeysIntent.Import(name = "id_ed25519", content = content), secret, content.contentToString())
    }

    @Test
    fun `intents and states that carry an address or an account do not print it`() {
        assertMasked(ServerFormIntent.HostChanged(host), host)
        assertMasked(ServerFormIntent.UsernameChanged("deploy-user"), "deploy-user")
        assertMasked(ServerFormIntent.NameChanged("Work box"), "Work box")
        assertMasked(
            ServerFormState(isNew = false, name = "Work box", host = host, username = "deploy-user"),
            host,
            "Work box",
            "deploy-user",
        )
        assertMasked(ProgressState(serverName = "Work box"), "Work box")
    }

    @Test
    fun `secret intents compare by content`() {
        assertEquals(ServerFormIntent.PasswordChanged(secret), ServerFormIntent.PasswordChanged(secret))
        assertEquals(ProgressIntent.SubmitServerPassword(secret), ProgressIntent.SubmitServerPassword(secret))
    }

    @Test
    fun `a host key request shows the fingerprint, and the approved one when it changed`() {
        val presented = HostKeyFingerprint("SHA256:presented")
        val approved = HostKeyFingerprint("SHA256:approved")

        val first: HostKeyRequest = HostKeyRequest.FirstSeen(keyType = "ssh-ed25519", fingerprint = presented)
        val changed: HostKeyRequest = HostKeyRequest.Changed(keyType = "ssh-ed25519", fingerprint = presented, approved = approved)

        assertEquals(presented, first.fingerprint)
        assertEquals(presented, changed.fingerprint)
        assertEquals(approved, (changed as HostKeyRequest.Changed).approved)
        assertMasked(changed, presented.sha256, approved.sha256)
    }

    @Test
    fun `the active server starts empty and follows the last connect`() {
        val active = ActiveServer()
        assertNull(active.id.value)

        active.set(ServerProfileId("server-1"))
        assertEquals(ServerProfileId("server-1"), active.id.value)

        active.set(null)
        assertNull(active.id.value)
    }

    @Test
    fun `folder state lists entries as given`() {
        val entry = FileEntry(path = "/srv/app", type = FileEntryType.Directory)
        assertEquals(listOf(entry), FolderState(path = "/srv", entries = listOf(entry)).entries)
    }
}
