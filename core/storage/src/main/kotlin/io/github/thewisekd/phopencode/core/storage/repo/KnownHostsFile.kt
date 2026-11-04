package io.github.thewisekd.phopencode.core.storage.repo

import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import io.github.thewisekd.phopencode.core.storage_api.HostEndpoint
import io.github.thewisekd.phopencode.core.storage_api.HostKeyFingerprint
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * The approved host keys as read from disk. A file is [Unreadable] unless every entry in it can be
 * read: a missing entry would turn an approved host into an unknown one, and the user would be asked
 * to approve whatever key the server presents. Only clearing the file writes over it.
 */
internal sealed interface KnownHostsFile {
    data class Readable(val hosts: Map<HostEndpoint, HostKeyFingerprint>) : KnownHostsFile

    data object Unreadable : KnownHostsFile
}

/** `{"version":1,"hosts":[{"host":…,"port":…,"fingerprint":"SHA256:…"}]}`, sorted for stable output. */
internal object KnownHostsFileSerializer : Serializer<KnownHostsFile> {
    override val defaultValue: KnownHostsFile = KnownHostsFile.Readable(emptyMap())

    override suspend fun readFrom(input: InputStream): KnownHostsFile {
        val entries = input.readJsonObject()?.get("hosts") as? JsonArray ?: return KnownHostsFile.Unreadable
        val hosts = HashMap<HostEndpoint, HostKeyFingerprint>()
        for (element in entries) {
            val entry = element as? JsonObject ?: return KnownHostsFile.Unreadable
            val host = entry.string("host") ?: return KnownHostsFile.Unreadable
            val port = (entry["port"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
                ?: return KnownHostsFile.Unreadable
            val fingerprintText = entry.string("fingerprint") ?: return KnownHostsFile.Unreadable
            // The storage-api types do not validate today; if they start to, a value they refuse
            // must still leave the file Unreadable, so that clearing it can recover.
            val (endpoint, fingerprint) = try {
                HostEndpoint(host, port) to HostKeyFingerprint(fingerprintText)
            } catch (_: IllegalArgumentException) {
                return KnownHostsFile.Unreadable
            }
            val previous = hosts.put(endpoint, fingerprint)
            if (previous != null && previous != fingerprint) return KnownHostsFile.Unreadable
        }
        return KnownHostsFile.Readable(hosts)
    }

    override suspend fun writeTo(t: KnownHostsFile, output: OutputStream) {
        val hosts = when (t) {
            is KnownHostsFile.Readable -> t.hosts
            // Updates refuse an unreadable file, so this is never asked for.
            KnownHostsFile.Unreadable -> throw IOException("An unreadable known hosts file is not written")
        }
        val entries = hosts.entries
            .sortedWith(compareBy({ it.key.host }, { it.key.port }))
            .map { (endpoint, fingerprint) ->
                JsonObject(
                    mapOf(
                        "host" to JsonPrimitive(endpoint.host),
                        "port" to JsonPrimitive(endpoint.port),
                        "fingerprint" to JsonPrimitive(fingerprint.sha256),
                    ),
                )
            }
        output.writeJson(JsonObject(mapOf("version" to JsonPrimitive(FILE_VERSION), "hosts" to JsonArray(entries))))
    }

    private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
}

/** Opens the known hosts file. No corruption handler: an unreadable file is [KnownHostsFile.Unreadable]. */
internal fun knownHostsDataStore(scope: CoroutineScope, produceFile: () -> File): DataStore<KnownHostsFile> =
    DataStoreFactory.create(serializer = KnownHostsFileSerializer, scope = scope, produceFile = produceFile)
