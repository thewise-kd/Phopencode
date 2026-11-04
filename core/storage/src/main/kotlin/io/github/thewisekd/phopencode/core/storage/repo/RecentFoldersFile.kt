package io.github.thewisekd.phopencode.core.storage.repo

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import io.github.thewisekd.phopencode.core.model.ServerProfileId
import io.github.thewisekd.phopencode.core.storage_api.RecentFoldersStore
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Each profile's recent folders, newest first. A profile without folders has no key. */
internal typealias RecentFolders = Map<ServerProfileId, List<String>>

/**
 * The recent folders as JSON: `{"version":1,"profiles":[{"profile":…,"folders":["/newest",…]}]}`.
 *
 * A profile entry this version cannot read is left out, and so is a folder that is not text; a list
 * is cut to [RecentFoldersStore.MAX_PER_PROFILE] and loses repeats, so the file's limits hold even
 * for a file this version did not write. A file that is not a recent folders file is corrupt: see
 * [recentFoldersDataStore].
 */
internal object RecentFoldersFileSerializer : Serializer<RecentFolders> {
    override val defaultValue: RecentFolders = emptyMap()

    override suspend fun readFrom(input: InputStream): RecentFolders {
        // The messages say nothing about the content: paths can name users and projects.
        val root = input.readJsonObject() ?: throw CorruptionException("The recent folders file is not a JSON object")
        val entries = root["profiles"] as? JsonArray
            ?: throw CorruptionException("The recent folders file has no profile list")
        val folders = LinkedHashMap<ServerProfileId, List<String>>()
        for (element in entries) {
            val (profile, paths) = decodeOrNull(element) ?: continue
            if (paths.isNotEmpty()) folders[profile] = paths
        }
        return folders
    }

    override suspend fun writeTo(t: RecentFolders, output: OutputStream) {
        val profiles = t.entries
            .filter { it.value.isNotEmpty() }
            .sortedBy { it.key.value }
            .map { (profile, paths) ->
                JsonObject(
                    mapOf(
                        "profile" to JsonPrimitive(profile.value),
                        "folders" to JsonArray(paths.map(::JsonPrimitive)),
                    ),
                )
            }
        output.writeJson(JsonObject(mapOf("version" to JsonPrimitive(FILE_VERSION), "profiles" to JsonArray(profiles))))
    }

    private fun decodeOrNull(element: JsonElement): Pair<ServerProfileId, List<String>>? {
        val entry = element as? JsonObject ?: return null
        val profile = entry.string("profile") ?: return null
        val paths = entry["folders"] as? JsonArray ?: return null
        val folders = paths
            .mapNotNull { (it as? JsonPrimitive)?.takeIf { path -> path.isString }?.content }
            .distinct()
            .take(RecentFoldersStore.MAX_PER_PROFILE)
        return ServerProfileId(profile) to folders
    }
}

/**
 * Opens the recent folders file. A corrupt file is replaced by an empty one: recent folders are a
 * convenience, and the user can browse to a folder again.
 */
internal fun recentFoldersDataStore(scope: CoroutineScope, produceFile: () -> File): DataStore<RecentFolders> =
    DataStoreFactory.create(
        serializer = RecentFoldersFileSerializer,
        corruptionHandler = ReplaceFileCorruptionHandler { emptyMap() },
        scope = scope,
        produceFile = produceFile,
    )
