package io.github.thewisekd.phopencode.feature.connect.logic.keys

import app.cash.turbine.test
import io.github.thewisekd.phopencode.core.model.SecretRef
import io.github.thewisekd.phopencode.core.model.ServerProfile
import io.github.thewisekd.phopencode.core.model.ServerProfileId
import io.github.thewisekd.phopencode.core.model.SshAuth
import io.github.thewisekd.phopencode.core.ssh.key.GeneratedKey
import io.github.thewisekd.phopencode.core.ssh.key.SshKeyException
import io.github.thewisekd.phopencode.core.ssh.key.SshKeys
import io.github.thewisekd.phopencode.core.ssh.key.SshPublicKey
import io.github.thewisekd.phopencode.core.storage_api.SecretValue
import io.github.thewisekd.phopencode.core.storage_api.SshKeyEntry
import io.github.thewisekd.phopencode.core.storage_api.SshKeyId
import io.github.thewisekd.phopencode.core.testing.storage.FakeProfileRepository
import io.github.thewisekd.phopencode.feature.connect.logic.contract.KeysError
import io.github.thewisekd.phopencode.feature.connect.logic.contract.KeysEvent
import io.github.thewisekd.phopencode.feature.connect.logic.contract.KeysIntent
import io.github.thewisekd.phopencode.feature.connect.logic.contract.KeysState
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class DefaultKeysPresenterTest {
    private val secrets = TestSecretStore()
    private val index = TestKeyIndex()
    private val profiles = FakeProfileRepository()
    private var ids = 0

    private fun TestScope.presenter(
        scope: CoroutineScope = backgroundScope,
        worker: CoroutineDispatcher = StandardTestDispatcher(testScheduler),
    ) = DefaultKeysPresenter(
        keyIndex = index,
        secrets = secrets,
        profiles = profiles,
        scope = scope,
        newId = { "id-${++ids}" },
        worker = worker,
    )

    private fun TestScope.loaded(): DefaultKeysPresenter = presenter().also { runCurrent() }

    /** An index entry with its secret, as a generated key leaves them. */
    private suspend fun seedKey(id: String, name: String, passphrase: String? = null): SshKeyEntry {
        val generated = SshKeys.generateEd25519("", passphrase?.toCharArray())
        val entry = generated.use {
            val privateRef = SecretRef("$id-private")
            SecretValue.ofBytes(it.privateKeyBytes()).use { value -> secrets.fake.put(privateRef, value) }
            val passphraseRef = passphrase?.let { text ->
                SecretRef("$id-passphrase").also { ref -> SecretValue.ofText(text).use { value -> secrets.fake.put(ref, value) } }
            }
            SshKeyEntry(
                id = SshKeyId(id),
                name = name,
                type = it.publicKey.type,
                fingerprint = it.publicKey.fingerprintSha256,
                publicKey = it.publicKey.authorizedKeysLine(),
                privateKeyRef = privateRef,
                passphraseRef = passphraseRef,
            )
        }
        index.fake.save(entry)
        return entry
    }

    private fun profileUsing(entry: SshKeyEntry) = ServerProfile(
        id = ServerProfileId("server-1"),
        name = "Test server",
        host = "server.invalid",
        username = "key-test-user",
        auth = SshAuth.PrivateKey(entry.privateKeyRef, entry.passphraseRef),
    )

    private fun ByteArray.isWiped() = all { it == 0.toByte() }

    // Loading

    @Test
    fun `loading starts in the scope, never in the constructor`() = runTest {
        // As Main.immediate: the scope runs what it is given at once, until it suspends.
        val immediate = CoroutineScope(backgroundScope.coroutineContext + ImmediateDispatcher(StandardTestDispatcher(testScheduler)))
        seedKey("a", "Laptop")

        val presenter = presenter(scope = immediate)

        assertEquals(KeysState(), presenter.state.value)
        assertEquals(0, index.observeCalls.get())

        runCurrent()
        assertFalse(presenter.state.value.loading)
        assertEquals(listOf("Laptop"), presenter.state.value.keys.map { it.name })
    }

    @Test
    fun `keys are listed by name and follow the index`() = runTest {
        seedKey("b", "Phone")
        seedKey("a", "Laptop")
        val presenter = loaded()

        assertEquals(listOf("Laptop", "Phone"), presenter.state.value.keys.map { it.name })

        seedKey("c", "Desktop")
        runCurrent()
        assertEquals(listOf("Desktop", "Laptop", "Phone"), presenter.state.value.keys.map { it.name })
    }

    @Test
    fun `an index that cannot be read shows LoadFailed`() = runTest {
        index.failObserve = true
        val presenter = loaded()

        assertFalse(presenter.state.value.loading)
        assertEquals(KeysError.LoadFailed, presenter.state.value.error)
    }

    // Generate

    @Test
    fun `generate saves the private key in SecretStore and the public half in the index`() = runTest {
        val presenter = loaded()

        presenter.onIntent(KeysIntent.Generate("Phone key"))
        assertTrue(presenter.state.value.busy)
        runCurrent()

        val state = presenter.state.value
        assertFalse(state.busy)
        assertNull(state.error)
        val entry = state.keys.single()
        assertEquals(entry, index.fake.list().single())
        assertEquals("Phone key", entry.name)
        assertEquals("ssh-ed25519", entry.type)
        assertNull(entry.passphraseRef)
        val public = SshPublicKey.parse(entry.publicKey)
        assertEquals(public.fingerprintSha256, entry.fingerprint)

        val file = assertNotNull(secrets.bytes(entry.privateKeyRef))
        val info = SshKeys.read(file)
        assertFalse(info.encrypted)
        assertEquals(public, info.publicKey)
        assertEquals(1, secrets.fake.entryCount)
    }

    @Test
    fun `generate with a passphrase encrypts the key and keeps the passphrase`() = runTest {
        val presenter = loaded()

        presenter.onIntent(KeysIntent.Generate("Phone key", TestSecrets.PASSPHRASE))
        runCurrent()

        val entry = presenter.state.value.keys.single()
        val file = assertNotNull(secrets.bytes(entry.privateKeyRef))
        assertFailsWith<SshKeyException.PassphraseRequired> { SshKeys.read(file) }
        val info = SshKeys.read(file, TestSecrets.PASSPHRASE.toCharArray())
        assertTrue(info.encrypted)
        assertEquals(SshPublicKey.parse(entry.publicKey), info.publicKey)
        assertEquals(TestSecrets.PASSPHRASE, secrets.text(assertNotNull(entry.passphraseRef)))
    }

    @Test
    fun `an empty passphrase stores the key without one`() = runTest {
        val presenter = loaded()

        presenter.onIntent(KeysIntent.Generate("Phone key", ""))
        runCurrent()

        val entry = presenter.state.value.keys.single()
        assertNull(entry.passphraseRef)
        assertFalse(SshKeys.read(assertNotNull(secrets.bytes(entry.privateKeyRef))).encrypted)
        assertEquals(1, secrets.fake.entryCount)
    }

    @Test
    fun `generation runs on the worker`() = runTest {
        val worker = CountingDispatcher(StandardTestDispatcher(testScheduler))
        val presenter = presenter(worker = worker)
        runCurrent()

        presenter.onIntent(KeysIntent.Generate("Phone key"))
        runCurrent()

        assertTrue(worker.dispatches.get() > 0)
        assertEquals(1, presenter.state.value.keys.size)
    }

    @Test
    fun `a blank name generates nothing`() = runTest {
        val presenter = loaded()

        presenter.onIntent(KeysIntent.Generate("  "))
        runCurrent()

        assertEquals(KeysError.GenerateFailed, presenter.state.value.error)
        assertTrue(index.fake.list().isEmpty())
        assertEquals(0, secrets.fake.entryCount)
    }

    @Test
    fun `a secret that cannot be written leaves no index entry`() = runTest {
        secrets.failPutAt = 1
        val presenter = loaded()

        presenter.onIntent(KeysIntent.Generate("Phone key"))
        runCurrent()

        assertEquals(KeysError.SaveFailed, presenter.state.value.error)
        assertFalse(presenter.state.value.busy)
        assertTrue(index.fake.list().isEmpty())
        assertEquals(0, secrets.fake.entryCount)
    }

    @Test
    fun `a passphrase that cannot be written removes the private key already written`() = runTest {
        secrets.failPutAt = 2
        val presenter = loaded()

        presenter.onIntent(KeysIntent.Generate("Phone key", TestSecrets.PASSPHRASE))
        runCurrent()

        assertEquals(KeysError.SaveFailed, presenter.state.value.error)
        assertTrue(index.fake.list().isEmpty())
        assertEquals(0, secrets.fake.entryCount)
    }

    @Test
    fun `an index that cannot be written leaves no orphaned secrets`() = runTest {
        index.failSave = true
        val presenter = loaded()

        presenter.onIntent(KeysIntent.Generate("Phone key", TestSecrets.PASSPHRASE))
        runCurrent()

        assertEquals(KeysError.SaveFailed, presenter.state.value.error)
        assertTrue(index.fake.list().isEmpty())
        assertEquals(0, secrets.fake.entryCount)
    }

    // Import

    @Test
    fun `import stores the file as it is and wipes the given bytes`() = runTest {
        val file = TestKeys.openSsh()
        val original = file.copyOf()
        val presenter = loaded()

        presenter.onIntent(KeysIntent.Import("id_ed25519", file))
        runCurrent()

        val state = presenter.state.value
        assertNull(state.error)
        assertFalse(state.busy)
        assertFalse(state.importAwaitingPassphrase)
        val entry = state.keys.single()
        assertEquals("id_ed25519", entry.name)
        assertEquals("ssh-ed25519", entry.type)
        assertNull(entry.passphraseRef)
        assertEquals(SshKeys.read(original).publicKey, SshPublicKey.parse(entry.publicKey))
        assertContentEquals(original, secrets.bytes(entry.privateKeyRef))
        assertTrue(file.isWiped())
    }

    @Test
    fun `import reads PEM keys`() = runTest {
        val file = TestKeys.ecdsaPem()
        val original = file.copyOf()
        val presenter = loaded()

        presenter.onIntent(KeysIntent.Import("id_ecdsa", file))
        runCurrent()

        val entry = presenter.state.value.keys.single()
        assertEquals("ecdsa-sha2-nistp256", entry.type)
        assertContentEquals(original, secrets.bytes(entry.privateKeyRef))
        assertTrue(file.isWiped())
    }

    @Test
    fun `an encrypted key waits for its passphrase, then is stored with it`() = runTest {
        val file = TestKeys.openSsh(TestSecrets.PASSPHRASE)
        val original = file.copyOf()
        val presenter = loaded()

        presenter.onIntent(KeysIntent.Import("id_ed25519", file))
        runCurrent()

        assertTrue(presenter.state.value.importAwaitingPassphrase)
        assertFalse(presenter.state.value.busy)
        assertNull(presenter.state.value.error)
        assertTrue(index.fake.list().isEmpty())
        assertEquals(0, secrets.fake.entryCount)

        presenter.onIntent(KeysIntent.SubmitPassphrase(TestSecrets.WRONG_PASSPHRASE))
        runCurrent()

        assertEquals(KeysError.WrongPassphrase, presenter.state.value.error)
        assertTrue(presenter.state.value.importAwaitingPassphrase)
        assertTrue(index.fake.list().isEmpty())

        presenter.onIntent(KeysIntent.DismissError)
        presenter.onIntent(KeysIntent.SubmitPassphrase(TestSecrets.PASSPHRASE))
        runCurrent()

        val state = presenter.state.value
        assertNull(state.error)
        assertFalse(state.importAwaitingPassphrase)
        val entry = state.keys.single()
        assertContentEquals(original, secrets.bytes(entry.privateKeyRef))
        assertEquals(TestSecrets.PASSPHRASE, secrets.text(assertNotNull(entry.passphraseRef)))
        assertTrue(file.isWiped())
    }

    @Test
    fun `an empty passphrase counts as wrong`() = runTest {
        val presenter = loaded()
        presenter.onIntent(KeysIntent.Import("id_ed25519", TestKeys.openSsh(TestSecrets.PASSPHRASE)))
        runCurrent()

        presenter.onIntent(KeysIntent.SubmitPassphrase(""))
        runCurrent()

        assertEquals(KeysError.WrongPassphrase, presenter.state.value.error)
        assertTrue(presenter.state.value.importAwaitingPassphrase)
    }

    @Test
    fun `cancelling the import wipes the key and stores nothing`() = runTest {
        val file = TestKeys.openSsh(TestSecrets.PASSPHRASE)
        val presenter = loaded()
        presenter.onIntent(KeysIntent.Import("id_ed25519", file))
        runCurrent()

        presenter.onIntent(KeysIntent.CancelImport)
        runCurrent()

        assertFalse(presenter.state.value.importAwaitingPassphrase)
        assertTrue(file.isWiped())
        assertTrue(index.fake.list().isEmpty())
        assertEquals(0, secrets.fake.entryCount)

        // Nothing waits any more.
        presenter.onIntent(KeysIntent.SubmitPassphrase(TestSecrets.PASSPHRASE))
        runCurrent()
        assertTrue(index.fake.list().isEmpty())
    }

    @Test
    fun `a key waiting for its passphrase is wiped when the screen goes away`() = runTest {
        val job = Job(backgroundScope.coroutineContext[Job])
        val scope = CoroutineScope(backgroundScope.coroutineContext + job)
        val file = TestKeys.openSsh(TestSecrets.PASSPHRASE)
        val presenter = presenter(scope = scope)
        presenter.onIntent(KeysIntent.Import("id_ed25519", file))
        runCurrent()
        assertTrue(presenter.state.value.importAwaitingPassphrase)
        assertFalse(file.isWiped())

        job.cancel()
        runCurrent()

        assertTrue(file.isWiped())
    }

    @Test
    fun `an import whose screen goes away before it starts is wiped`() = runTest {
        val job = Job(backgroundScope.coroutineContext[Job])
        val presenter = presenter(scope = CoroutineScope(backgroundScope.coroutineContext + job))
        runCurrent()
        val file = TestKeys.openSsh()

        presenter.onIntent(KeysIntent.Import("id_ed25519", file))
        job.cancel()
        runCurrent()

        assertTrue(file.isWiped())
        assertTrue(index.fake.list().isEmpty())
        assertEquals(0, secrets.fake.entryCount)
    }

    @Test
    fun `a submitted key whose screen goes away before it is read is wiped`() = runTest {
        val job = Job(backgroundScope.coroutineContext[Job])
        val presenter = presenter(scope = CoroutineScope(backgroundScope.coroutineContext + job))
        val file = TestKeys.openSsh(TestSecrets.PASSPHRASE)
        presenter.onIntent(KeysIntent.Import("id_ed25519", file))
        runCurrent()
        assertTrue(presenter.state.value.importAwaitingPassphrase)

        presenter.onIntent(KeysIntent.SubmitPassphrase(TestSecrets.PASSPHRASE))
        job.cancel()
        runCurrent()

        assertTrue(file.isWiped())
        assertTrue(index.fake.list().isEmpty())
    }

    @Test
    fun `a generated key dropped by cancellation is wiped`() = runTest {
        val job = Job(backgroundScope.coroutineContext[Job])
        val presenter = presenter(scope = CoroutineScope(backgroundScope.coroutineContext + job))
        runCurrent()
        var generated: GeneratedKey? = null
        // The screen goes away just as the worker finishes: the worker's result is never used.
        KeyMaterialHooks.generated = { key ->
            generated = key
            job.cancel()
        }
        try {
            presenter.onIntent(KeysIntent.Generate("Phone key"))
            runCurrent()
        } finally {
            KeyMaterialHooks.generated = null
        }

        val key = assertNotNull(generated)
        assertFailsWith<IllegalStateException> { key.privateKeyBytes() }
        assertTrue(index.fake.list().isEmpty())
        assertEquals(0, secrets.fake.entryCount)
    }

    @Test
    fun `a file that is not a key is malformed and wiped`() = runTest {
        val file = "not a key file at all".encodeToByteArray()
        val presenter = loaded()

        presenter.onIntent(KeysIntent.Import("notes.txt", file))
        runCurrent()

        assertEquals(KeysError.ImportMalformed, presenter.state.value.error)
        assertFalse(presenter.state.value.busy)
        assertFalse(presenter.state.value.importAwaitingPassphrase)
        assertTrue(file.isWiped())
        assertTrue(index.fake.list().isEmpty())
    }

    @Test
    fun `a key type the app does not read is unsupported and wiped`() = runTest {
        val file = TestKeys.dsaPem()
        val presenter = loaded()

        presenter.onIntent(KeysIntent.Import("id_dsa", file))
        runCurrent()

        assertEquals(KeysError.ImportUnsupported, presenter.state.value.error)
        assertTrue(file.isWiped())
        assertTrue(index.fake.list().isEmpty())
    }

    @Test
    fun `an import that cannot be saved leaves nothing behind`() = runTest {
        index.failSave = true
        val file = TestKeys.openSsh(TestSecrets.PASSPHRASE)
        val presenter = loaded()

        presenter.onIntent(KeysIntent.Import("id_ed25519", file))
        runCurrent()
        presenter.onIntent(KeysIntent.SubmitPassphrase(TestSecrets.PASSPHRASE))
        runCurrent()

        assertEquals(KeysError.SaveFailed, presenter.state.value.error)
        assertFalse(presenter.state.value.importAwaitingPassphrase)
        assertTrue(file.isWiped())
        assertEquals(0, secrets.fake.entryCount)
    }

    @Test
    fun `an import sent while busy is dropped and wiped`() = runTest {
        val presenter = loaded()
        presenter.onIntent(KeysIntent.Generate("Phone key"))
        val file = TestKeys.openSsh()

        presenter.onIntent(KeysIntent.Import("id_ed25519", file))
        assertTrue(file.isWiped())
        runCurrent()

        assertEquals(listOf("Phone key"), presenter.state.value.keys.map { it.name })
    }

    @Test
    fun `the private key never reaches the state or events`() = runTest {
        val file = TestKeys.openSsh()
        val bodyLines = file.decodeToString().lines().filter { it.isNotBlank() && !it.startsWith("-----") }
        val presenter = loaded()

        presenter.events.test {
            presenter.onIntent(KeysIntent.Import("id_ed25519", file))
            runCurrent()
            val entry = presenter.state.value.keys.single()
            presenter.onIntent(KeysIntent.CopyPublicKey(entry.id))
            runCurrent()
            val event = awaitItem() as KeysEvent.CopyPublicKey

            val shown = listOf(presenter.state.value.toString(), event.toString(), event.publicKey) +
                presenter.state.value.keys.flatMap { listOf(it.name, it.type, it.fingerprint, it.publicKey) }
            for (text in shown) {
                assertFalse(text.contains("PRIVATE"))
                bodyLines.forEach { line -> assertFalse(text.contains(line)) }
            }
        }
    }

    // Copy

    @Test
    fun `copy emits the public key line only`() = runTest {
        val entry = seedKey("a", "Laptop")
        val presenter = loaded()

        presenter.events.test {
            presenter.onIntent(KeysIntent.CopyPublicKey(entry.id))
            runCurrent()
            assertEquals(KeysEvent.CopyPublicKey(entry.publicKey), awaitItem())

            presenter.onIntent(KeysIntent.CopyPublicKey(SshKeyId("missing")))
            runCurrent()
            expectNoEvents()
        }
    }

    // Delete

    @Test
    fun `delete asks first and can be cancelled`() = runTest {
        val entry = seedKey("a", "Laptop")
        val presenter = loaded()

        presenter.onIntent(KeysIntent.RequestDelete(entry.id))
        assertEquals(entry, presenter.state.value.pendingDelete)

        presenter.onIntent(KeysIntent.CancelDelete)
        runCurrent()
        assertNull(presenter.state.value.pendingDelete)
        assertEquals(listOf(entry), index.fake.list())
    }

    @Test
    fun `confirmed delete removes the entry and its secrets`() = runTest {
        val entry = seedKey("a", "Laptop", TestSecrets.PASSPHRASE)
        val other = seedKey("b", "Phone")
        val presenter = loaded()

        presenter.onIntent(KeysIntent.RequestDelete(entry.id))
        presenter.onIntent(KeysIntent.ConfirmDelete)
        runCurrent()

        val state = presenter.state.value
        assertNull(state.error)
        assertNull(state.pendingDelete)
        assertFalse(state.busy)
        assertEquals(listOf(other), state.keys)
        assertNull(secrets.bytes(entry.privateKeyRef))
        assertNull(secrets.bytes(assertNotNull(entry.passphraseRef)))
        assertNotNull(secrets.bytes(other.privateKeyRef))
    }

    @Test
    fun `a key a saved server logs in with is not deleted`() = runTest {
        val entry = seedKey("a", "Laptop")
        profiles.save(profileUsing(entry))
        val presenter = loaded()

        presenter.onIntent(KeysIntent.RequestDelete(entry.id))
        presenter.onIntent(KeysIntent.ConfirmDelete)
        runCurrent()

        assertEquals(KeysError.KeyInUse, presenter.state.value.error)
        assertNull(presenter.state.value.pendingDelete)
        assertEquals(listOf(entry), index.fake.list())
        assertNotNull(secrets.bytes(entry.privateKeyRef))
    }

    @Test
    fun `profiles that cannot be read stop the delete`() = runTest {
        val entry = seedKey("a", "Laptop")
        profiles.unavailable = true
        val presenter = loaded()

        presenter.onIntent(KeysIntent.RequestDelete(entry.id))
        presenter.onIntent(KeysIntent.ConfirmDelete)
        runCurrent()

        assertEquals(KeysError.DeleteFailed, presenter.state.value.error)
        assertEquals(listOf(entry), index.fake.list())
        assertNotNull(secrets.bytes(entry.privateKeyRef))
    }

    @Test
    fun `an index entry that cannot be removed keeps its secrets`() = runTest {
        val entry = seedKey("a", "Laptop")
        index.failDelete = true
        val presenter = loaded()

        presenter.onIntent(KeysIntent.RequestDelete(entry.id))
        presenter.onIntent(KeysIntent.ConfirmDelete)
        runCurrent()

        assertEquals(KeysError.DeleteFailed, presenter.state.value.error)
        assertEquals(listOf(entry), index.fake.list())
        assertNotNull(secrets.bytes(entry.privateKeyRef))
    }

    @Test
    fun `secrets that cannot be removed are reported`() = runTest {
        val entry = seedKey("a", "Laptop")
        secrets.failDelete = true
        val presenter = loaded()

        presenter.onIntent(KeysIntent.RequestDelete(entry.id))
        presenter.onIntent(KeysIntent.ConfirmDelete)
        runCurrent()

        assertEquals(KeysError.DeleteFailed, presenter.state.value.error)
        assertTrue(index.fake.list().isEmpty())
    }

    // Other intents

    @Test
    fun `dismiss clears the error and close emits Close`() = runTest {
        index.failObserve = true
        val presenter = loaded()
        assertEquals(KeysError.LoadFailed, presenter.state.value.error)

        presenter.onIntent(KeysIntent.DismissError)
        assertNull(presenter.state.value.error)

        presenter.events.test {
            presenter.onIntent(KeysIntent.Close)
            assertEquals(KeysEvent.Close, awaitItem())
        }
    }
}
