package io.github.thewisekd.phopencode.core.storage.repo

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import io.github.thewisekd.phopencode.core.model.ServerProfile
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The JSON of the repository's own files: newer files may carry keys this version does not know. */
internal val FileJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

internal const val FILE_VERSION = 1

/** Parses a whole file as a JSON object, or gives `null` if it is not one. */
internal fun InputStream.readJsonObject(): JsonObject? = try {
    FileJson.parseToJsonElement(readBytes().decodeToString()) as? JsonObject
} catch (_: SerializationException) {
    null
} catch (_: IllegalArgumentException) {
    null
}

internal fun OutputStream.writeJson(root: JsonObject) {
    write(FileJson.encodeToString(JsonObject.serializer(), root).encodeToByteArray())
}

/**
 * The saved servers as JSON: `{"version":1,"profiles":[…]}`. A [ServerProfile] holds secret
 * references only, so the file never holds a password; the secrets themselves live in `SecretStore`.
 *
 * An entry this version cannot decode is left out, so one bad profile does not hide the others.
 * A file that is not a profile file at all is corrupt: see [profileDataStore].
 */
internal object ProfileFileSerializer : Serializer<List<ServerProfile>> {
    override val defaultValue: List<ServerProfile> = emptyList()

    override suspend fun readFrom(input: InputStream): List<ServerProfile> {
        // The messages say nothing about the content: the file holds addresses and account names.
        val root = input.readJsonObject() ?: throw CorruptionException("The profile file is not a JSON object")
        val entries = root["profiles"] as? JsonArray ?: throw CorruptionException("The profile file has no profile list")
        return entries.mapNotNull(::decodeOrNull).associateBy { it.id }.values.toList()
    }

    override suspend fun writeTo(t: List<ServerProfile>, output: OutputStream) {
        val profiles = t.map { FileJson.encodeToJsonElement(ServerProfile.serializer(), it) }
        output.writeJson(JsonObject(mapOf("version" to JsonPrimitive(FILE_VERSION), "profiles" to JsonArray(profiles))))
    }

    private fun decodeOrNull(entry: JsonElement): ServerProfile? = try {
        FileJson.decodeFromJsonElement(ServerProfile.serializer(), entry)
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}

/**
 * Opens the profile file. A corrupt file is replaced by an empty list: the profiles hold no
 * secrets and can be entered again, while a file that never opens would lock the user out of the
 * app. DataStore writes through a temporary file and a rename, so corruption is rare.
 */
internal fun profileDataStore(scope: CoroutineScope, produceFile: () -> File): DataStore<List<ServerProfile>> =
    DataStoreFactory.create(
        serializer = ProfileFileSerializer,
        corruptionHandler = ReplaceFileCorruptionHandler { emptyList() },
        scope = scope,
        produceFile = produceFile,
    )
