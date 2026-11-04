package io.github.thewisekd.phopencode.core.storage.repo

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import io.github.thewisekd.phopencode.core.model.SecretRef
import io.github.thewisekd.phopencode.core.storage_api.SshKeyEntry
import io.github.thewisekd.phopencode.core.storage_api.SshKeyId
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The SSH key index as JSON: `{"version":1,"keys":[{"id":…,"name":…,"type":…,"fingerprint":…,
 * "publicKey":…,"privateKeyRef":…,"passphraseRef":…}]}`. It holds references to the private keys and
 * passphrases, never the keys themselves; [SshKeyEntry] refuses private key armor as a public key.
 *
 * An entry this version cannot read is left out, so one bad entry does not hide the others. A file
 * that is not a key index at all is corrupt: see [keyIndexDataStore].
 */
internal object KeyIndexFileSerializer : Serializer<List<SshKeyEntry>> {
    override val defaultValue: List<SshKeyEntry> = emptyList()

    override suspend fun readFrom(input: InputStream): List<SshKeyEntry> {
        // The messages say nothing about the content: names and keys can identify the user.
        val root = input.readJsonObject() ?: throw CorruptionException("The key index is not a JSON object")
        val entries = root["keys"] as? JsonArray ?: throw CorruptionException("The key index has no key list")
        return entries.mapNotNull(::decodeOrNull).associateBy { it.id }.values.toList()
    }

    override suspend fun writeTo(t: List<SshKeyEntry>, output: OutputStream) {
        val keys = t.map { entry ->
            JsonObject(
                buildMap {
                    put("id", JsonPrimitive(entry.id.value))
                    put("name", JsonPrimitive(entry.name))
                    put("type", JsonPrimitive(entry.type))
                    put("fingerprint", JsonPrimitive(entry.fingerprint))
                    put("publicKey", JsonPrimitive(entry.publicKey))
                    put("privateKeyRef", JsonPrimitive(entry.privateKeyRef.id))
                    entry.passphraseRef?.let { put("passphraseRef", JsonPrimitive(it.id)) }
                },
            )
        }
        output.writeJson(JsonObject(mapOf("version" to JsonPrimitive(FILE_VERSION), "keys" to JsonArray(keys))))
    }

    private fun decodeOrNull(element: JsonElement): SshKeyEntry? {
        val entry = element as? JsonObject ?: return null
        val passphrase = entry["passphraseRef"]
        val passphraseRef = when {
            passphrase == null || passphrase == JsonNull -> null
            else -> SecretRef((passphrase as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null)
        }
        return try {
            SshKeyEntry(
                id = SshKeyId(entry.string("id") ?: return null),
                name = entry.string("name") ?: return null,
                type = entry.string("type") ?: return null,
                fingerprint = entry.string("fingerprint") ?: return null,
                publicKey = entry.string("publicKey") ?: return null,
                privateKeyRef = SecretRef(entry.string("privateKeyRef") ?: return null),
                passphraseRef = passphraseRef,
            )
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

internal fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

/**
 * Opens the key index. A corrupt file is replaced by an empty index, as the profile file is: the
 * private keys stay in `SecretStore` and the profiles that use them keep working, while a file that
 * never opens would lock the user out of key management. Keys the lost index listed can then only
 * be removed by deleting all data.
 */
internal fun keyIndexDataStore(scope: CoroutineScope, produceFile: () -> File): DataStore<List<SshKeyEntry>> =
    DataStoreFactory.create(
        serializer = KeyIndexFileSerializer,
        corruptionHandler = ReplaceFileCorruptionHandler { emptyList() },
        scope = scope,
        produceFile = produceFile,
    )
