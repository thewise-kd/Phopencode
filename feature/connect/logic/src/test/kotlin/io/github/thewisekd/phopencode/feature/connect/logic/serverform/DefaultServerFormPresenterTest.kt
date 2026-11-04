package io.github.thewisekd.phopencode.feature.connect.logic.serverform

import app.cash.turbine.test
import io.github.thewisekd.phopencode.core.model.SecretRef
import io.github.thewisekd.phopencode.core.model.ServerProfile
import io.github.thewisekd.phopencode.core.model.ServerProfileId
import io.github.thewisekd.phopencode.core.model.SshAuth
import io.github.thewisekd.phopencode.core.storage_api.ProfileRepository
import io.github.thewisekd.phopencode.core.storage_api.SecretReadResult
import io.github.thewisekd.phopencode.core.storage_api.SecretStore
import io.github.thewisekd.phopencode.core.storage_api.SecretValue
import io.github.thewisekd.phopencode.core.storage_api.SshKeyEntry
import io.github.thewisekd.phopencode.core.storage_api.SshKeyId
import io.github.thewisekd.phopencode.core.storage_api.SshKeyIndex
import io.github.thewisekd.phopencode.core.testing.storage.FakeProfileRepository
import io.github.thewisekd.phopencode.core.testing.storage.FakeSecretStore
import io.github.thewisekd.phopencode.core.testing.storage.FakeSshKeyIndex
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ServerFormAuthMethod
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ServerFormError
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ServerFormEvent
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ServerFormField
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ServerFormFieldError
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ServerFormIntent
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ServerFormState
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** Example values only: documentation host names and made-up passwords. */
@OptIn(ExperimentalCoroutinesApi::class)
class DefaultServerFormPresenterTest {
    private val fakeProfiles = FakeProfileRepository()
    private val fakeSecrets = FakeSecretStore()
    private val keyIndex = FakeSshKeyIndex()
    private val profiles = ScriptedProfiles(fakeProfiles)
    private val secrets = ScriptedSecrets(fakeSecrets)

    private var nextId = 0
    private val newId: () -> String = { "id-${++nextId}" }

    private val serverId = ServerProfileId("server-1")
    private val oldPasswordRef = SecretRef("old-password")
    private val oldOpencodeRef = SecretRef("old-opencode")
    private val usedAt = Instant.parse("2026-10-01T10:00:00Z")

    private val key = SshKeyEntry(
        id = SshKeyId("key-1"),
        name = "laptop",
        type = "ssh-ed25519",
        fingerprint = "SHA256:AAAA",
        publicKey = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIExample test",
        privateKeyRef = SecretRef("key-1-private"),
        passphraseRef = SecretRef("key-1-passphrase"),
    )

    private val passwordProfile = ServerProfile(
        id = serverId,
        name = "Build box",
        host = "build.example.com",
        port = 2222,
        username = "deploy",
        auth = SshAuth.Password(oldPasswordRef),
        opencodeCredential = oldOpencodeRef,
        lastUsedAt = usedAt,
    )

    private fun TestScope.presenter(id: ServerProfileId? = null, scope: CoroutineScope = backgroundScope) =
        DefaultServerFormPresenter(
            serverId = id,
            profiles = profiles,
            secrets = secrets,
            keyIndex = keyIndex,
            scope = scope,
            newId = newId,
        )

    private suspend fun seedPasswordProfile() {
        fakeProfiles.save(passwordProfile)
        fakeSecrets.put(oldPasswordRef, SecretValue.ofText("old-login-secret"))
        fakeSecrets.put(oldOpencodeRef, SecretValue.ofText("old-opencode-secret"))
    }

    private suspend fun secretText(ref: SecretRef): String? =
        when (val result = fakeSecrets.read(ref)) {
            is SecretReadResult.Found -> result.value.use { it.revealText() }
            else -> null
        }

    private fun DefaultServerFormPresenter.fillValid() {
        onIntent(ServerFormIntent.NameChanged("Build box"))
        onIntent(ServerFormIntent.HostChanged("build.example.com"))
        onIntent(ServerFormIntent.PortChanged("2222"))
        onIntent(ServerFormIntent.UsernameChanged("deploy"))
    }

    // Loading

    @Test
    fun `nothing is read until the scope runs`() = runTest {
        seedPasswordProfile()
        val presenter = presenter(serverId)

        assertEquals(0, profiles.listCalls)
        assertEquals(0, secrets.readCalls)
        assertEquals(ServerFormState(isNew = false), presenter.state.value)

        runCurrent()

        assertEquals(1, profiles.listCalls)
    }

    @Test
    fun `a new form loads the keys and starts empty`() = runTest {
        keyIndex.save(key)
        val presenter = presenter()

        runCurrent()

        val state = presenter.state.value
        assertFalse(state.loading)
        assertTrue(state.isNew)
        assertEquals(listOf(key), state.keys)
        assertEquals("", state.host)
        assertEquals("22", state.port)
        assertEquals(ServerFormAuthMethod.Password, state.authMethod)
        assertNull(state.error)
        assertEquals(0, profiles.listCalls)
    }

    @Test
    fun `the key list follows the index while the form is open`() = runTest {
        val presenter = presenter()
        runCurrent()
        assertEquals(emptyList(), presenter.state.value.keys)

        keyIndex.save(key)
        runCurrent()

        assertEquals(listOf(key), presenter.state.value.keys)
    }

    @Test
    fun `editing fills the form from the saved server`() = runTest {
        seedPasswordProfile()
        val presenter = presenter(serverId)

        runCurrent()

        val state = presenter.state.value
        assertFalse(state.loading)
        assertFalse(state.isNew)
        assertEquals("Build box", state.name)
        assertEquals("build.example.com", state.host)
        assertEquals("2222", state.port)
        assertEquals("deploy", state.username)
        assertEquals(ServerFormAuthMethod.Password, state.authMethod)
        assertTrue(state.storedPassword)
        assertFalse(state.passwordEntered)
        assertTrue(state.storedOpencodePassword)
        assertNull(state.error)
    }

    @Test
    fun `a stored password that cannot be read is not offered as stored`() = runTest {
        fakeProfiles.save(passwordProfile)
        fakeSecrets.seedUnreadable(oldPasswordRef)
        val presenter = presenter(serverId)

        runCurrent()

        assertFalse(presenter.state.value.storedPassword)
        assertFalse(presenter.state.value.storedOpencodePassword)
        assertNull(presenter.state.value.error)
    }

    @Test
    fun `editing a key login selects its key`() = runTest {
        keyIndex.save(key)
        fakeProfiles.save(passwordProfile.copy(auth = SshAuth.PrivateKey(key.privateKeyRef, key.passphraseRef)))
        val presenter = presenter(serverId)

        runCurrent()

        assertEquals(ServerFormAuthMethod.Key, presenter.state.value.authMethod)
        assertEquals(key.id, presenter.state.value.selectedKey)
        assertFalse(presenter.state.value.storedPassword)
    }

    @Test
    fun `a server that no longer exists says so`() = runTest {
        val presenter = presenter(serverId)

        runCurrent()

        assertEquals(ServerFormError.NotFound, presenter.state.value.error)
        assertFalse(presenter.state.value.loading)
    }

    @Test
    fun `storage that cannot be read fails the load`() = runTest {
        fakeProfiles.unavailable = true
        val presenter = presenter(serverId)

        runCurrent()

        assertEquals(ServerFormError.LoadFailed, presenter.state.value.error)
        assertFalse(presenter.state.value.loading)
    }

    @Test
    fun `a missing or unloaded server is never saved`() = runTest {
        val presenter = presenter(serverId)
        runCurrent()
        presenter.fillValid()
        presenter.onIntent(ServerFormIntent.PasswordChanged("new-login-secret"))

        presenter.onIntent(ServerFormIntent.Save)
        runCurrent()

        assertEquals(0, profiles.saveCalls)
        assertEquals(0, fakeSecrets.entryCount)
    }

    // Validation

    @Test
    fun `saving an empty form marks the required fields and writes nothing`() = runTest {
        val presenter = presenter()
        runCurrent()
        presenter.onIntent(ServerFormIntent.PortChanged(""))

        presenter.onIntent(ServerFormIntent.Save)
        runCurrent()

        assertEquals(
            mapOf(
                ServerFormField.Host to ServerFormFieldError.Required,
                ServerFormField.Port to ServerFormFieldError.Required,
                ServerFormField.Username to ServerFormFieldError.Required,
                ServerFormField.Password to ServerFormFieldError.Required,
            ),
            presenter.state.value.fieldErrors,
        )
        assertFalse(presenter.state.value.saving)
        assertEquals(0, profiles.saveCalls)
        assertEquals(0, fakeSecrets.entryCount)
    }

    @Test
    fun `invalid host, port and user are marked invalid`() = runTest {
        val presenter = presenter()
        runCurrent()
        presenter.onIntent(ServerFormIntent.HostChanged("ssh://example.com"))
        presenter.onIntent(ServerFormIntent.PortChanged("70000"))
        presenter.onIntent(ServerFormIntent.UsernameChanged("-oProxyCommand=x"))
        presenter.onIntent(ServerFormIntent.PasswordChanged("new-login-secret"))

        presenter.onIntent(ServerFormIntent.Save)
        runCurrent()

        assertEquals(
            mapOf(
                ServerFormField.Host to ServerFormFieldError.Invalid,
                ServerFormField.Port to ServerFormFieldError.Invalid,
                ServerFormField.Username to ServerFormFieldError.Invalid,
            ),
            presenter.state.value.fieldErrors,
        )
        assertEquals(0, profiles.saveCalls)
        assertEquals(0, fakeSecrets.entryCount)
    }

    @Test
    fun `editing a field clears its error only`() = runTest {
        val presenter = presenter()
        runCurrent()
        presenter.onIntent(ServerFormIntent.Save)
        runCurrent()

        presenter.onIntent(ServerFormIntent.HostChanged("example.com"))

        val errors = presenter.state.value.fieldErrors
        assertFalse(ServerFormField.Host in errors)
        assertEquals(ServerFormFieldError.Required, errors[ServerFormField.Username])
    }

    @Test
    fun `key login without a key needs one`() = runTest {
        val presenter = presenter()
        runCurrent()
        presenter.fillValid()
        presenter.onIntent(ServerFormIntent.AuthMethodChanged(ServerFormAuthMethod.Key))

        presenter.onIntent(ServerFormIntent.Save)
        runCurrent()

        assertEquals(mapOf(ServerFormField.Key to ServerFormFieldError.Required), presenter.state.value.fieldErrors)
        assertEquals(0, profiles.saveCalls)
    }

    @Test
    fun `key login with a key gone from the index is invalid`() = runTest {
        keyIndex.save(key)
        val presenter = presenter()
        runCurrent()
        presenter.fillValid()
        presenter.onIntent(ServerFormIntent.AuthMethodChanged(ServerFormAuthMethod.Key))
        presenter.onIntent(ServerFormIntent.KeySelected(key.id))
        keyIndex.delete(key.id)

        presenter.onIntent(ServerFormIntent.Save)
        runCurrent()

        assertEquals(mapOf(ServerFormField.Key to ServerFormFieldError.Invalid), presenter.state.value.fieldErrors)
        assertEquals(0, profiles.saveCalls)
    }

    // Saving

    @Test
    fun `a new server with a password saves the password only to the secret store`() = runTest {
        val presenter = presenter()
        runCurrent()
        presenter.events.test {
            presenter.fillValid()
            presenter.onIntent(ServerFormIntent.PasswordChanged("new-login-secret"))
            assertTrue(presenter.state.value.passwordEntered)

            presenter.onIntent(ServerFormIntent.Save)
            runCurrent()

            val saved = fakeProfiles.list().single()
            assertEquals(ServerFormEvent.Saved(saved.id), awaitItem())
            assertEquals(
                ServerProfile(
                    id = saved.id,
                    name = "Build box",
                    host = "build.example.com",
                    port = 2222,
                    username = "deploy",
                    auth = SshAuth.Password((saved.auth as SshAuth.Password).secretRef),
                ),
                saved,
            )
            assertEquals("new-login-secret", secretText((saved.auth as SshAuth.Password).secretRef))
            assertEquals(1, fakeSecrets.entryCount)
            assertFalse("new-login-secret" in saved.toString())
            assertFalse("new-login-secret" in presenter.state.value.toString())
            assertFalse(presenter.state.value.saving)
        }
    }

    @Test
    fun `a blank name takes the host`() = runTest {
        val presenter = presenter()
        runCurrent()
        presenter.fillValid()
        presenter.onIntent(ServerFormIntent.NameChanged("   "))
        presenter.onIntent(ServerFormIntent.HostChanged(" build.example.com "))
        presenter.onIntent(ServerFormIntent.UsernameChanged(" deploy "))
        presenter.onIntent(ServerFormIntent.PasswordChanged("new-login-secret"))

        presenter.onIntent(ServerFormIntent.Save)
        runCurrent()

        val saved = fakeProfiles.list().single()
        assertEquals("build.example.com", saved.name)
        assertEquals("build.example.com", saved.host)
        assertEquals("deploy", saved.username)
    }

    @Test
    fun `the secret written is wiped once stored`() = runTest {
        val presenter = presenter()
        runCurrent()
        presenter.fillValid()
        presenter.onIntent(ServerFormIntent.PasswordChanged("new-login-secret"))

        presenter.onIntent(ServerFormIntent.Save)
        runCurrent()

        val written = secrets.written.single()
        assertFailsWith<IllegalStateException> { written.revealBytes() }
    }

    @Test
    fun `a new server with a key writes no secret`() = runTest {
        keyIndex.save(key)
        val presenter = presenter()
        runCurrent()
        presenter.fillValid()
        presenter.onIntent(ServerFormIntent.PasswordChanged("typed-then-switched"))
        presenter.onIntent(ServerFormIntent.AuthMethodChanged(ServerFormAuthMethod.Key))
        presenter.onIntent(ServerFormIntent.KeySelected(key.id))

        presenter.onIntent(ServerFormIntent.Save)
        runCurrent()

        val saved = fakeProfiles.list().single()
        assertEquals(SshAuth.PrivateKey(key.privateKeyRef, key.passphraseRef), saved.auth)
        assertEquals(0, fakeSecrets.entryCount)
    }

    @Test
    fun `editing without a new password keeps the stored one`() = runTest {
        seedPasswordProfile()
        val presenter = presenter(serverId)
        runCurrent()
        presenter.onIntent(ServerFormIntent.NameChanged("Renamed"))

        presenter.onIntent(ServerFormIntent.Save)
        runCurrent()

        assertEquals(listOf(passwordProfile.copy(name = "Renamed")), fakeProfiles.list())
        assertEquals("old-login-secret", secretText(oldPasswordRef))
        assertEquals("old-opencode-secret", secretText(oldOpencodeRef))
        assertEquals(2, fakeSecrets.entryCount)
    }

    @Test
    fun `editing with a new password stores it anew and drops the old one`() = runTest {
        seedPasswordProfile()
        val presenter = presenter(serverId)
        runCurrent()
        presenter.onIntent(ServerFormIntent.PasswordChanged("new-login-secret"))

        presenter.onIntent(ServerFormIntent.Save)
        runCurrent()

        val saved = fakeProfiles.list().single()
        val ref = (saved.auth as SshAuth.Password).secretRef
        assertTrue(ref != oldPasswordRef)
        assertEquals(passwordProfile.copy(auth = SshAuth.Password(ref)), saved)
        assertEquals("new-login-secret", secretText(ref))
        assertNull(secretText(oldPasswordRef))
        assertEquals(2, fakeSecrets.entryCount)
    }

    @Test
    fun `an unreadable stored password must be entered again`() = runTest {
        fakeProfiles.save(passwordProfile.copy(opencodeCredential = null))
        fakeSecrets.seedUnreadable(oldPasswordRef)
        val presenter = presenter(serverId)
        runCurrent()

        presenter.onIntent(ServerFormIntent.Save)
        runCurrent()

        assertEquals(ServerFormFieldError.Required, presenter.state.value.fieldErrors[ServerFormField.Password])
        assertEquals(0, profiles.saveCalls)
    }

    @Test
    fun `switching an edited server to a key drops its stored password`() = runTest {
        seedPasswordProfile()
        keyIndex.save(key)
        val presenter = presenter(serverId)
        runCurrent()
        presenter.onIntent(ServerFormIntent.AuthMethodChanged(ServerFormAuthMethod.Key))
        presenter.onIntent(ServerFormIntent.KeySelected(key.id))

        presenter.onIntent(ServerFormIntent.Save)
        runCurrent()

        assertEquals(
            listOf(passwordProfile.copy(auth = SshAuth.PrivateKey(key.privateKeyRef, key.passphraseRef))),
            fakeProfiles.list(),
        )
        assertNull(secretText(oldPasswordRef))
        assertEquals("old-opencode-secret", secretText(oldOpencodeRef))
    }

    @Test
    fun `an edited key login never deletes the key's secrets`() = runTest {
        keyIndex.save(key)
        fakeSecrets.put(key.privateKeyRef, SecretValue.ofText("not-a-real-key"))
        fakeProfiles.save(passwordProfile.copy(auth = SshAuth.PrivateKey(key.privateKeyRef), opencodeCredential = null))
        val presenter = presenter(serverId)
        runCurrent()
        presenter.onIntent(ServerFormIntent.AuthMethodChanged(ServerFormAuthMethod.Password))
        presenter.onIntent(ServerFormIntent.PasswordChanged("new-login-secret"))

        presenter.onIntent(ServerFormIntent.Save)
        runCurrent()

        assertIs<SshAuth.Password>(fakeProfiles.list().single().auth)
        assertEquals("not-a-real-key", secretText(key.privateKeyRef))
    }

    @Test
    fun `an opencode password is stored with a new reference`() = runTest {
        val presenter = presenter()
        runCurrent()
        presenter.fillValid()
        presenter.onIntent(ServerFormIntent.PasswordChanged("new-login-secret"))
        presenter.onIntent(ServerFormIntent.OpencodePasswordChanged("new-opencode-secret"))
        assertTrue(presenter.state.value.opencodePasswordEntered)

        presenter.onIntent(ServerFormIntent.Save)
        runCurrent()

        val saved = fakeProfiles.list().single()
        assertEquals("new-opencode-secret", secretText(saved.opencodeCredential!!))
        assertEquals("new-login-secret", secretText((saved.auth as SshAuth.Password).secretRef))
    }

    @Test
    fun `clearing the opencode password removes it on save`() = runTest {
        seedPasswordProfile()
        val presenter = presenter(serverId)
        runCurrent()

        presenter.onIntent(ServerFormIntent.ClearOpencodePassword)
        assertFalse(presenter.state.value.storedOpencodePassword)
        assertEquals("old-opencode-secret", secretText(oldOpencodeRef))
        presenter.onIntent(ServerFormIntent.Save)
        runCurrent()

        assertEquals(listOf(passwordProfile.copy(opencodeCredential = null)), fakeProfiles.list())
        assertNull(secretText(oldOpencodeRef))
        assertEquals("old-login-secret", secretText(oldPasswordRef))
    }

    @Test
    fun `an empty password field forgets what was typed`() = runTest {
        val presenter = presenter()
        runCurrent()
        presenter.fillValid()
        presenter.onIntent(ServerFormIntent.PasswordChanged("typed"))
        presenter.onIntent(ServerFormIntent.PasswordChanged(""))

        assertFalse(presenter.state.value.passwordEntered)
        presenter.onIntent(ServerFormIntent.Save)
        runCurrent()

        assertEquals(ServerFormFieldError.Required, presenter.state.value.fieldErrors[ServerFormField.Password])
    }

    // Failed saves

    @Test
    fun `a failed secret write saves no profile and leaves no secret`() = runTest {
        seedPasswordProfile()
        val presenter = presenter(serverId)
        runCurrent()
        presenter.onIntent(ServerFormIntent.PasswordChanged("new-login-secret"))
        presenter.onIntent(ServerFormIntent.OpencodePasswordChanged("new-opencode-secret"))
        secrets.failPutNumber = 2

        presenter.onIntent(ServerFormIntent.Save)
        runCurrent()

        assertEquals(ServerFormError.SaveFailed, presenter.state.value.error)
        assertFalse(presenter.state.value.saving)
        assertEquals(listOf(passwordProfile), fakeProfiles.list())
        assertEquals("old-login-secret", secretText(oldPasswordRef))
        assertEquals("old-opencode-secret", secretText(oldOpencodeRef))
        assertEquals(2, fakeSecrets.entryCount)
    }

    @Test
    fun `a failed profile write keeps the old profile and its secrets`() = runTest {
        seedPasswordProfile()
        val presenter = presenter(serverId)
        runCurrent()
        presenter.onIntent(ServerFormIntent.PasswordChanged("new-login-secret"))
        presenter.onIntent(ServerFormIntent.ClearOpencodePassword)
        profiles.failSave = true

        presenter.events.test {
            presenter.onIntent(ServerFormIntent.Save)
            runCurrent()
            expectNoEvents()
        }

        assertEquals(ServerFormError.SaveFailed, presenter.state.value.error)
        assertEquals(listOf(passwordProfile), fakeProfiles.list())
        assertEquals("old-login-secret", secretText(oldPasswordRef))
        assertEquals("old-opencode-secret", secretText(oldOpencodeRef))
        assertEquals(2, fakeSecrets.entryCount)
        // The typed password is still there for another try.
        assertTrue(presenter.state.value.passwordEntered)
    }

    @Test
    fun `a failed profile write that cannot be checked keeps the new secrets`() = runTest {
        seedPasswordProfile()
        val presenter = presenter(serverId)
        runCurrent()
        presenter.onIntent(ServerFormIntent.PasswordChanged("new-login-secret"))
        profiles.failSave = true
        profiles.failList = true

        presenter.onIntent(ServerFormIntent.Save)
        runCurrent()

        assertEquals(ServerFormError.SaveFailed, presenter.state.value.error)
        assertEquals(listOf(passwordProfile), fakeProfiles.list())
        // The old secret stays for the old profile; the new one is an orphan, never a missing target.
        assertEquals("old-login-secret", secretText(oldPasswordRef))
        assertEquals(3, fakeSecrets.entryCount)
    }

    @Test
    fun `a profile write that throws after writing counts as saved`() = runTest {
        seedPasswordProfile()
        val presenter = presenter(serverId)
        runCurrent()
        presenter.onIntent(ServerFormIntent.PasswordChanged("new-login-secret"))
        profiles.throwAfterSave = true

        presenter.events.test {
            presenter.onIntent(ServerFormIntent.Save)
            runCurrent()
            assertEquals(ServerFormEvent.Saved(serverId), awaitItem())
        }

        val ref = (fakeProfiles.list().single().auth as SshAuth.Password).secretRef
        assertEquals("new-login-secret", secretText(ref))
        assertNull(presenter.state.value.error)
    }

    @Test
    fun `a retry after a committed write whose read-back failed leaves one profile`() = runTest {
        val presenter = presenter()
        runCurrent()
        presenter.fillValid()
        presenter.onIntent(ServerFormIntent.PasswordChanged("new-login-secret"))
        profiles.throwAfterSave = true
        profiles.failList = true

        presenter.onIntent(ServerFormIntent.Save)
        runCurrent()
        assertEquals(ServerFormError.SaveFailed, presenter.state.value.error)
        profiles.throwAfterSave = false
        profiles.failList = false

        presenter.onIntent(ServerFormIntent.Save)
        runCurrent()

        val saved = fakeProfiles.list().single()
        assertEquals("new-login-secret", secretText((saved.auth as SshAuth.Password).secretRef))
    }

    @Test
    fun `a read-back changed by another writer keeps the new secrets it refers to`() = runTest {
        seedPasswordProfile()
        val presenter = presenter(serverId)
        runCurrent()
        presenter.onIntent(ServerFormIntent.PasswordChanged("new-login-secret"))
        presenter.onIntent(ServerFormIntent.OpencodePasswordChanged("new-opencode-secret"))
        val later = Instant.parse("2026-10-02T10:00:00Z")
        profiles.storedVersion = { it.copy(name = "Renamed elsewhere", lastUsedAt = later) }
        profiles.throwAfterSave = true

        presenter.events.test {
            presenter.onIntent(ServerFormIntent.Save)
            runCurrent()
            assertEquals(ServerFormEvent.Saved(serverId), awaitItem())
        }

        val stored = fakeProfiles.list().single()
        assertEquals("new-login-secret", secretText((stored.auth as SshAuth.Password).secretRef))
        assertEquals("new-opencode-secret", secretText(stored.opencodeCredential!!))
        assertNull(presenter.state.value.error)
    }

    @Test
    fun `a cancelled save whose read-back was changed by another writer keeps its secrets`() = runTest {
        seedPasswordProfile()
        val screen = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
        val presenter = presenter(serverId, scope = screen)
        runCurrent()
        presenter.onIntent(ServerFormIntent.PasswordChanged("new-login-secret"))
        profiles.storedVersion = { it.copy(lastUsedAt = Instant.parse("2026-10-02T10:00:00Z")) }
        profiles.afterSaveGate = CompletableDeferred()

        presenter.onIntent(ServerFormIntent.Save)
        runCurrent()
        screen.cancel()
        runCurrent()

        val stored = fakeProfiles.list().single()
        assertEquals("new-login-secret", secretText((stored.auth as SshAuth.Password).secretRef))
    }

    @Test
    fun `a failed cleanup of old secrets still saves`() = runTest {
        seedPasswordProfile()
        val presenter = presenter(serverId)
        runCurrent()
        presenter.onIntent(ServerFormIntent.PasswordChanged("new-login-secret"))
        secrets.failDelete = true

        presenter.events.test {
            presenter.onIntent(ServerFormIntent.Save)
            runCurrent()
            assertEquals(ServerFormEvent.Saved(serverId), awaitItem())
        }
        assertNull(presenter.state.value.error)
    }

    @Test
    fun `dismissing the error clears it`() = runTest {
        val presenter = presenter()
        runCurrent()
        presenter.fillValid()
        presenter.onIntent(ServerFormIntent.PasswordChanged("new-login-secret"))
        fakeSecrets.unavailable = true
        presenter.onIntent(ServerFormIntent.Save)
        runCurrent()
        assertEquals(ServerFormError.SaveFailed, presenter.state.value.error)

        presenter.onIntent(ServerFormIntent.DismissError)

        assertNull(presenter.state.value.error)
    }

    @Test
    fun `a second save while saving is ignored`() = runTest {
        val presenter = presenter()
        runCurrent()
        presenter.fillValid()
        presenter.onIntent(ServerFormIntent.PasswordChanged("new-login-secret"))
        val gate = CompletableDeferred<Unit>()
        profiles.saveGate = gate

        presenter.onIntent(ServerFormIntent.Save)
        runCurrent()
        assertTrue(presenter.state.value.saving)
        presenter.onIntent(ServerFormIntent.Save)
        runCurrent()
        gate.complete(Unit)
        runCurrent()

        assertEquals(1, profiles.saveCalls)
        assertEquals(1, fakeSecrets.entryCount)
        assertFalse(presenter.state.value.saving)
    }

    @Test
    fun `passwords typed while a save runs are kept for the next save`() = runTest {
        val presenter = presenter()
        runCurrent()
        presenter.fillValid()
        presenter.onIntent(ServerFormIntent.PasswordChanged("first-login-secret"))
        presenter.onIntent(ServerFormIntent.OpencodePasswordChanged("first-opencode-secret"))
        val gate = CompletableDeferred<Unit>()
        profiles.saveGate = gate

        presenter.onIntent(ServerFormIntent.Save)
        runCurrent()
        assertTrue(presenter.state.value.saving)
        presenter.onIntent(ServerFormIntent.PasswordChanged("second-login-secret"))
        presenter.onIntent(ServerFormIntent.OpencodePasswordChanged("second-opencode-secret"))
        gate.complete(Unit)
        runCurrent()

        val first = fakeProfiles.list().single()
        assertEquals("first-login-secret", secretText((first.auth as SshAuth.Password).secretRef))
        assertEquals("first-opencode-secret", secretText(first.opencodeCredential!!))
        assertTrue(presenter.state.value.passwordEntered)
        assertTrue(presenter.state.value.opencodePasswordEntered)

        profiles.saveGate = null
        presenter.onIntent(ServerFormIntent.Save)
        runCurrent()

        val second = fakeProfiles.list().single()
        assertEquals(first.id, second.id)
        assertEquals("second-login-secret", secretText((second.auth as SshAuth.Password).secretRef))
        assertEquals("second-opencode-secret", secretText(second.opencodeCredential!!))
        assertFalse(presenter.state.value.passwordEntered)
        assertFalse(presenter.state.value.opencodePasswordEntered)
        assertEquals(2, fakeSecrets.entryCount)
    }

    @Test
    fun `a password typed right after save is not part of that save and stays entered`() = runTest {
        val presenter = presenter()
        runCurrent()
        presenter.fillValid()
        presenter.onIntent(ServerFormIntent.PasswordChanged("first-login-secret"))
        presenter.onIntent(ServerFormIntent.OpencodePasswordChanged("first-opencode-secret"))

        presenter.onIntent(ServerFormIntent.Save)
        presenter.onIntent(ServerFormIntent.PasswordChanged("second-login-secret"))
        presenter.onIntent(ServerFormIntent.OpencodePasswordChanged("second-opencode-secret"))
        runCurrent()

        val saved = fakeProfiles.list().single()
        assertEquals("first-login-secret", secretText((saved.auth as SshAuth.Password).secretRef))
        assertEquals("first-opencode-secret", secretText(saved.opencodeCredential!!))
        assertTrue(presenter.state.value.passwordEntered)
        assertTrue(presenter.state.value.opencodePasswordEntered)
    }

    @Test
    fun `a password typed while old secrets are deleted stays entered`() = runTest {
        seedPasswordProfile()
        val presenter = presenter(serverId)
        runCurrent()
        presenter.onIntent(ServerFormIntent.PasswordChanged("first-login-secret"))
        presenter.onIntent(ServerFormIntent.OpencodePasswordChanged("first-opencode-secret"))
        val gate = CompletableDeferred<Unit>()
        secrets.deleteGate = gate

        presenter.onIntent(ServerFormIntent.Save)
        runCurrent()
        assertEquals(1, profiles.saveCalls)
        presenter.onIntent(ServerFormIntent.PasswordChanged("second-login-secret"))
        presenter.onIntent(ServerFormIntent.OpencodePasswordChanged("second-opencode-secret"))
        gate.complete(Unit)
        runCurrent()

        assertTrue(presenter.state.value.passwordEntered)
        assertTrue(presenter.state.value.opencodePasswordEntered)
        assertTrue(presenter.state.value.storedOpencodePassword)
        assertNull(secretText(oldPasswordRef))
    }

    @Test
    fun `clearing the opencode password while a save runs still applies to the next save`() = runTest {
        seedPasswordProfile()
        val presenter = presenter(serverId)
        runCurrent()
        presenter.onIntent(ServerFormIntent.OpencodePasswordChanged("new-opencode-secret"))
        val gate = CompletableDeferred<Unit>()
        profiles.saveGate = gate

        presenter.onIntent(ServerFormIntent.Save)
        runCurrent()
        presenter.onIntent(ServerFormIntent.ClearOpencodePassword)
        gate.complete(Unit)
        runCurrent()

        assertFalse(presenter.state.value.storedOpencodePassword)
        profiles.saveGate = null
        presenter.onIntent(ServerFormIntent.Save)
        runCurrent()

        assertNull(fakeProfiles.list().single().opencodeCredential)
        assertEquals(1, fakeSecrets.entryCount)
    }

    @Test
    fun `a save cancelled before the profile is written leaves no new secret`() = runTest {
        seedPasswordProfile()
        val screen = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
        val presenter = presenter(serverId, scope = screen)
        runCurrent()
        presenter.onIntent(ServerFormIntent.PasswordChanged("new-login-secret"))
        presenter.onIntent(ServerFormIntent.OpencodePasswordChanged("new-opencode-secret"))
        profiles.saveGate = CompletableDeferred()

        presenter.onIntent(ServerFormIntent.Save)
        runCurrent()
        assertEquals(4, fakeSecrets.entryCount)
        screen.cancel()
        runCurrent()

        assertEquals(listOf(passwordProfile), fakeProfiles.list())
        assertEquals("old-login-secret", secretText(oldPasswordRef))
        assertEquals("old-opencode-secret", secretText(oldOpencodeRef))
        assertEquals(2, fakeSecrets.entryCount)
    }

    @Test
    fun `a save cancelled after the profile is written keeps its secrets`() = runTest {
        seedPasswordProfile()
        val screen = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
        val presenter = presenter(serverId, scope = screen)
        runCurrent()
        presenter.onIntent(ServerFormIntent.PasswordChanged("new-login-secret"))
        profiles.afterSaveGate = CompletableDeferred()

        presenter.onIntent(ServerFormIntent.Save)
        runCurrent()
        screen.cancel()
        runCurrent()

        val saved = fakeProfiles.list().single()
        assertEquals("new-login-secret", secretText((saved.auth as SshAuth.Password).secretRef))
    }

    // Navigation

    @Test
    fun `cancel closes and manage keys opens keys`() = runTest {
        val presenter = presenter()
        presenter.events.test {
            presenter.onIntent(ServerFormIntent.ManageKeys)
            presenter.onIntent(ServerFormIntent.Cancel)
            assertEquals(ServerFormEvent.OpenKeys, awaitItem())
            assertEquals(ServerFormEvent.Close, awaitItem())
        }
    }
}

/** Counts reads and fails writes on demand, around the shared fake. */
private class ScriptedProfiles(private val fake: FakeProfileRepository) : ProfileRepository {
    var listCalls = 0
    var saveCalls = 0
    var failSave = false
    var throwAfterSave = false
    var failList = false
    var saveGate: CompletableDeferred<Unit>? = null
    var afterSaveGate: CompletableDeferred<Unit>? = null

    /** What is really stored instead of the profile given, as if another writer changed it meanwhile. */
    var storedVersion: (ServerProfile) -> ServerProfile = { it }

    override fun observe(): Flow<List<ServerProfile>> = fake.observe()

    override suspend fun list(): List<ServerProfile> {
        listCalls++
        if (failList) throw IOException("list failed")
        return fake.list()
    }

    override suspend fun save(profile: ServerProfile) {
        saveCalls++
        saveGate?.await()
        if (failSave) throw IOException("save failed")
        fake.save(storedVersion(profile))
        afterSaveGate?.await()
        if (throwAfterSave) throw IOException("save reported failure")
    }

    override suspend fun delete(id: ServerProfileId) = fake.delete(id)
}

/** Counts reads, keeps every value written, and fails one write or every delete on demand. */
private class ScriptedSecrets(private val fake: FakeSecretStore) : SecretStore {
    var readCalls = 0
    var putCalls = 0
    var failPutNumber: Int? = null
    var failDelete = false
    var deleteGate: CompletableDeferred<Unit>? = null
    val written = mutableListOf<SecretValue>()

    override suspend fun put(ref: SecretRef, value: SecretValue) {
        putCalls++
        written += value
        if (putCalls == failPutNumber) throw IOException("put failed")
        fake.put(ref, value)
    }

    override suspend fun read(ref: SecretRef): SecretReadResult {
        readCalls++
        return fake.read(ref)
    }

    override suspend fun delete(ref: SecretRef) {
        deleteGate?.await()
        if (failDelete) throw IOException("delete failed")
        fake.delete(ref)
    }

    override suspend fun deleteAll() = fake.deleteAll()
}
