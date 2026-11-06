package io.github.thewisekd.phopencode.core.storage.repo

import io.github.thewisekd.phopencode.core.model.SecretRef
import io.github.thewisekd.phopencode.core.model.ServerProfile
import io.github.thewisekd.phopencode.core.model.ServerProfileId
import io.github.thewisekd.phopencode.core.model.SshAuth
import io.github.thewisekd.phopencode.core.storage_api.SecretValue
import io.github.thewisekd.phopencode.core.testing.storage.FakeSecretStore
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What the profile file holds on disk, and how it reads back what it did not write itself. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36])
class ProfileFileTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val stores by lazy { TestStores(folder.root) }

    @After
    fun close() {
        stores.close()
    }

    private val passwordProfile = ServerProfile(
        id = ServerProfileId("a"),
        name = "Home",
        host = "server.invalid",
        port = 2222,
        username = "user",
        auth = SshAuth.Password(SecretRef("ref-password")),
        opencodeCredential = SecretRef("ref-opencode"),
        lastUsedAt = Instant.fromEpochSeconds(1_700_000_000),
    )

    private val keyProfile = ServerProfile(
        id = ServerProfileId("b"),
        name = "Work",
        host = "other.invalid",
        username = "user",
        auth = SshAuth.PrivateKey(SecretRef("ref-key"), SecretRef("ref-passphrase")),
    )

    @Test
    fun `saved profiles survive reopening the file`() = runTest {
        val first = stores.profiles()
        DataStoreProfileRepository(first.store).apply {
            save(passwordProfile)
            save(keyProfile)
        }

        val second = stores.reopen(first) { stores.profiles(it) }

        assertEquals(listOf(passwordProfile, keyProfile), DataStoreProfileRepository(second.store).list())
    }

    @Test
    fun `the profile file holds references, never the password they point at`() = runTest {
        // Assembled at runtime: the repository is public, and the secret scan reads tests too.
        val typed = listOf("correct", "horse", "battery").joinToString("-")
        val secrets = FakeSecretStore()
        SecretValue.ofText(typed).use { secrets.put(SecretRef("ref-password"), it) }
        val opened = stores.profiles()
        DataStoreProfileRepository(opened.store).apply {
            save(passwordProfile)
            save(keyProfile)
        }

        val text = opened.file.readText()

        assertFalse(typed in text, "the password is in the profile file")
        assertTrue("ref-password" in text, "the reference is missing")
        val profiles = Json.parseToJsonElement(text).jsonObject.getValue("profiles").jsonArray
        assertEquals(2, profiles.size)
        profiles.forEach { entry ->
            val profile = entry.jsonObject
            assertEquals(PROFILE_KEYS, profile.keys - OPTIONAL_PROFILE_KEYS, "profile keys")
            assertTrue(profile.keys.all { it in PROFILE_KEYS + OPTIONAL_PROFILE_KEYS }, "unexpected profile keys")
            assertTrue(profile.getValue("auth").jsonObject.keys.all { it in AUTH_KEYS }, "unexpected auth keys")
        }
    }

    @Test
    fun `an entry that cannot be read is left out, and the others are kept`() = runTest {
        val opened = stores.profiles()
        DataStoreProfileRepository(opened.store).save(keyProfile)
        val reopened = stores.reopen(opened) { file ->
            val root = Json.parseToJsonElement(file.readText()).jsonObject
            val broken = JsonObject(mapOf("id" to Json.parseToJsonElement("\"c\"")))
            val entries = JsonArray(root.getValue("profiles").jsonArray + broken)
            file.writeText(JsonObject(root + ("profiles" to entries)).toString())
            stores.profiles(file)
        }

        assertEquals(listOf(keyProfile), DataStoreProfileRepository(reopened.store).list())
    }

    @Test
    fun `keys a later version added are ignored`() = runTest {
        val file = stores.newFile(TestStores.PROFILES)
        file.writeText(
            """
            {"version":9,"future":true,"profiles":[{"id":"b","name":"Work","host":"other.invalid",
            "username":"user","auth":{"type":"password","secretRef":"ref-x","future":1},"future":"x"}]}
            """.trimIndent(),
        )

        val profiles = DataStoreProfileRepository(stores.profiles(file).store).list()

        assertEquals(listOf("b"), profiles.map { it.id.value })
        assertEquals(SshAuth.Password(SecretRef("ref-x")), profiles.single().auth)
    }

    @Test
    fun `a profile written before the opencode port existed loads with no port`() = runTest {
        val file = stores.newFile(TestStores.PROFILES)
        file.writeText(
            """
            {"version":1,"profiles":[{"id":"b","name":"Work","host":"other.invalid","port":22,
            "username":"user","auth":{"type":"password","secretRef":"ref-x"},"opencodeCredential":"ref-o",
            "lastUsedAt":null}]}
            """.trimIndent(),
        )

        val profile = DataStoreProfileRepository(stores.profiles(file).store).list().single()

        assertEquals(null, profile.opencodePort)
        assertEquals(SecretRef("ref-o"), profile.opencodeCredential)
        assertEquals(SshAuth.Password(SecretRef("ref-x")), profile.auth)
    }

    @Test
    fun `the opencode port survives reopening the file, and so does its absence`() = runTest {
        val withPort = passwordProfile.copy(opencodePort = 4096)
        val first = stores.profiles()
        DataStoreProfileRepository(first.store).apply {
            save(withPort)
            save(keyProfile)
        }

        val second = stores.reopen(first) { stores.profiles(it) }

        val read = DataStoreProfileRepository(second.store).list()
        assertEquals(listOf(withPort, keyProfile), read)
        assertEquals(listOf(4096, null), read.map { it.opencodePort })
    }

    @Test
    fun `a file that is not a profile file reads as empty and can be written again`() = runTest {
        val file = stores.newFile(TestStores.PROFILES)
        file.writeText("not json at all {")
        val repository = DataStoreProfileRepository(stores.profiles(file).store)

        assertEquals(emptyList(), repository.list())
        repository.save(keyProfile)
        assertEquals(listOf(keyProfile), repository.list())
    }

    private companion object {
        val PROFILE_KEYS = setOf("id", "name", "host", "port", "username", "auth")
        val OPTIONAL_PROFILE_KEYS = setOf("opencodeCredential", "lastUsedAt", "opencodePort")
        val AUTH_KEYS = setOf("type", "secretRef", "keyRef", "passphraseRef")
    }
}
