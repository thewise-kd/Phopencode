package io.github.thewisekd.phopencode.core.storage.repo

import io.github.thewisekd.phopencode.core.model.SecretRef
import io.github.thewisekd.phopencode.core.storage_api.SecretValue
import io.github.thewisekd.phopencode.core.storage_api.SshKeyEntry
import io.github.thewisekd.phopencode.core.storage_api.SshKeyId
import io.github.thewisekd.phopencode.core.testing.storage.FakeSecretStore
import java.security.KeyPairGenerator
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What the key index holds on disk, and how it reads back what it did not write itself. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36])
class KeyIndexFileTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val stores by lazy { TestStores(folder.root) }

    @After
    fun close() {
        stores.close()
    }

    private val laptop = SshKeyEntry(
        id = SshKeyId("a"),
        name = "Laptop",
        type = "ssh-ed25519",
        fingerprint = "SHA256:AbC+/def",
        publicKey = "ssh-ed25519 AAAAlaptop user@phone",
        privateKeyRef = SecretRef("ref-key-a"),
        passphraseRef = SecretRef("ref-passphrase-a"),
    )

    private val phone = SshKeyEntry(
        id = SshKeyId("b"),
        name = "Phone",
        type = "ssh-rsa",
        fingerprint = "SHA256:xyz",
        publicKey = "ssh-rsa AAAAphone",
        privateKeyRef = SecretRef("ref-key-b"),
    )

    @Test
    fun `saved keys survive reopening the file, every field as given`() = runTest {
        val first = stores.keyIndex()
        DataStoreSshKeyIndex(first.store).apply {
            save(laptop)
            save(phone)
        }

        val second = stores.reopen(first) { stores.keyIndex(it) }

        assertEquals(listOf(laptop, phone), DataStoreSshKeyIndex(second.store).list())
    }

    @Test
    fun `the index holds references, never the private key they point at`() = runTest {
        // A key generated for this test: the repository is public, so no real key is checked in.
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val base64 = Base64.getEncoder()
        val privateText = base64.encodeToString(pair.private.encoded)
        val secrets = FakeSecretStore()
        SecretValue.ofText(privateText).use { secrets.put(SecretRef("ref-generated"), it) }
        val generated = SshKeyEntry(
            id = SshKeyId("g"),
            name = "Generated",
            type = "ecdsa-sha2-nistp256",
            fingerprint = "SHA256:generated",
            publicKey = "ecdsa-sha2-nistp256 ${base64.encodeToString(pair.public.encoded)}",
            privateKeyRef = SecretRef("ref-generated"),
        )
        val opened = stores.keyIndex()
        DataStoreSshKeyIndex(opened.store).apply {
            save(generated)
            save(laptop)
        }

        val text = opened.file.readText()

        assertFalse(privateText in text, "the private key is in the index")
        // In a P-256 PKCS#8 encoding the secret scalar follows a 36-byte header: 48 base64 characters.
        assertFalse(privateText.substring(48, 90) in text, "the private scalar is in the index")
        assertTrue("ref-generated" in text, "the reference is missing")
        val keys = Json.parseToJsonElement(text).jsonObject.getValue("keys").jsonArray
        assertEquals(2, keys.size)
        keys.forEach { entry ->
            assertEquals(KEY_FIELDS, entry.jsonObject.keys - OPTIONAL_KEY_FIELDS, "key fields")
            assertTrue(entry.jsonObject.keys.all { it in KEY_FIELDS + OPTIONAL_KEY_FIELDS }, "unexpected key fields")
        }
    }

    @Test
    fun `a key whose comment says private key survives reopening the file`() = runTest {
        val commented = phone.copy(publicKey = "ssh-rsa AAAAphone my PRIVATE KEY----- for Private-Lines: work")
        val first = stores.keyIndex()
        DataStoreSshKeyIndex(first.store).save(commented)

        val second = stores.reopen(first) { stores.keyIndex(it) }

        assertEquals(listOf(commented), DataStoreSshKeyIndex(second.store).list())
    }

    @Test
    fun `an entry that cannot be read is left out, and the others are kept`() = runTest {
        val armor = listOf("-----BEGIN OPENSSH", "PRIVATE KEY-----").joinToString(" ")
        val file = stores.newFile(TestStores.KEY_INDEX)
        file.writeText(
            """
            {"version":1,"keys":[
            {"id":"b","name":"Phone","type":"ssh-rsa","fingerprint":"SHA256:xyz","publicKey":"ssh-rsa AAAAphone",
             "privateKeyRef":"ref-key-b"},
            {"id":"c","name":"No reference","type":"ssh-rsa","fingerprint":"SHA256:c","publicKey":"ssh-rsa AAAA"},
            {"id":"d","name":"Number","type":"ssh-rsa","fingerprint":"SHA256:d","publicKey":"ssh-rsa AAAA","privateKeyRef":4},
            {"id":"e","name":"Armor","type":"ssh-rsa","fingerprint":"SHA256:e","publicKey":"$armor","privateKeyRef":"ref-e"},
            {"id":"f","name":"Bad passphrase","type":"ssh-rsa","fingerprint":"SHA256:f","publicKey":"ssh-rsa AAAA",
             "privateKeyRef":"ref-f","passphraseRef":7},
            "not an object"
            ]}
            """.trimIndent(),
        )

        assertEquals(listOf(phone), DataStoreSshKeyIndex(stores.keyIndex(file).store).list())
    }

    @Test
    fun `keys a later version added are ignored`() = runTest {
        val file = stores.newFile(TestStores.KEY_INDEX)
        file.writeText(
            """
            {"version":9,"future":true,"keys":[{"id":"b","name":"Phone","type":"ssh-rsa","fingerprint":"SHA256:xyz",
            "publicKey":"ssh-rsa AAAAphone","privateKeyRef":"ref-key-b","passphraseRef":null,"future":1}]}
            """.trimIndent(),
        )

        assertEquals(listOf(phone), DataStoreSshKeyIndex(stores.keyIndex(file).store).list())
    }

    @Test
    fun `a file that is not a key index reads as empty and can be written again`() = runTest {
        val file = stores.newFile(TestStores.KEY_INDEX)
        file.writeText("not json at all {")
        val index = DataStoreSshKeyIndex(stores.keyIndex(file).store)

        assertEquals(emptyList(), index.list())
        index.save(phone)
        assertEquals(listOf(phone), index.list())
    }

    private companion object {
        val KEY_FIELDS = setOf("id", "name", "type", "fingerprint", "publicKey", "privateKeyRef")
        val OPTIONAL_KEY_FIELDS = setOf("passphraseRef")
    }
}
