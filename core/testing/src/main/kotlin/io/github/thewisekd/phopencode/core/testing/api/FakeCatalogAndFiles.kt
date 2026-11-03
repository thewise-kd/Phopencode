package io.github.thewisekd.phopencode.core.testing.api

import io.github.thewisekd.phopencode.core.api.CatalogApi
import io.github.thewisekd.phopencode.core.api.FileSearchType
import io.github.thewisekd.phopencode.core.api.FsApi
import io.github.thewisekd.phopencode.core.api.Location
import io.github.thewisekd.phopencode.core.api.McpServer
import io.github.thewisekd.phopencode.core.api.Plugin
import io.github.thewisekd.phopencode.core.api.Provider
import io.github.thewisekd.phopencode.core.api.VcsApi
import io.github.thewisekd.phopencode.core.api.VcsBranch
import io.github.thewisekd.phopencode.core.api.VcsBranchInfo
import io.github.thewisekd.phopencode.core.api.VcsDiffMode
import io.github.thewisekd.phopencode.core.api.VcsDiffQuery
import io.github.thewisekd.phopencode.core.model.Agent
import io.github.thewisekd.phopencode.core.model.Command
import io.github.thewisekd.phopencode.core.model.FileDiff
import io.github.thewisekd.phopencode.core.model.FileEntry
import io.github.thewisekd.phopencode.core.model.FileEntryType
import io.github.thewisekd.phopencode.core.model.Model
import io.github.thewisekd.phopencode.core.model.ModelRef
import io.github.thewisekd.phopencode.core.model.Skill
import io.github.thewisekd.phopencode.core.model.VcsFile
import java.util.concurrent.ConcurrentHashMap

/** Values per folder. A folder with none of its own sees the server's default, the `null` entry. */
private class PerFolder<T : Any>(private val empty: T) {
    private data class Key(val location: Location?)

    private val values = ConcurrentHashMap<Key, T>()

    fun set(value: T, location: Location?) {
        values[Key(location)] = value
    }

    operator fun get(location: Location?): T = values[Key(location)] ?: values[Key(null)] ?: empty
}

/** A catalog a test fills in. Lists are empty until set, and null is the server's default folder. */
class FakeCatalogApi(private val control: FakeApiControl = FakeApiControl()) : CatalogApi {
    private val skills = PerFolder(emptyList<Skill>())
    private val commands = PerFolder(emptyList<Command>())
    private val agents = PerFolder(emptyList<Agent>())
    private val models = PerFolder(emptyList<Model>())
    private val defaultModels = PerFolder(listOf<ModelRef>())
    private val providers = PerFolder(emptyList<Provider>())
    private val mcpServers = PerFolder(emptyList<McpServer>())
    private val plugins = PerFolder(emptyList<Plugin>())

    fun setSkills(value: List<Skill>, location: Location? = null) = skills.set(value, location)

    fun setCommands(value: List<Command>, location: Location? = null) = commands.set(value, location)

    fun setAgents(value: List<Agent>, location: Location? = null) = agents.set(value, location)

    fun setModels(value: List<Model>, location: Location? = null) = models.set(value, location)

    fun setDefaultModel(value: ModelRef?, location: Location? = null) = defaultModels.set(listOfNotNull(value), location)

    fun setProviders(value: List<Provider>, location: Location? = null) = providers.set(value, location)

    fun setMcpServers(value: List<McpServer>, location: Location? = null) = mcpServers.set(value, location)

    fun setPlugins(value: List<Plugin>, location: Location? = null) = plugins.set(value, location)

    override suspend fun skills(location: Location?): List<Skill> = control.call("catalog.skills") { skills[location] }

    override suspend fun commands(location: Location?): List<Command> =
        control.call("catalog.commands") { commands[location] }

    override suspend fun agents(location: Location?): List<Agent> = control.call("catalog.agents") { agents[location] }

    override suspend fun models(location: Location?): List<Model> = control.call("catalog.models") { models[location] }

    override suspend fun defaultModel(location: Location?): ModelRef? =
        control.call("catalog.defaultModel") { defaultModels[location].firstOrNull() }

    override suspend fun providers(location: Location?): List<Provider> =
        control.call("catalog.providers") { providers[location] }

    override suspend fun mcpServers(location: Location?): List<McpServer> =
        control.call("catalog.mcpServers") { mcpServers[location] }

    override suspend fun plugins(location: Location?): List<Plugin> =
        control.call("catalog.plugins") { plugins[location] }
}

/** Git information a test fills in, per folder. A folder with none reads as having no repository. */
class FakeVcsApi(private val control: FakeApiControl = FakeApiControl()) : VcsApi {
    private data class DiffKey(val location: Location, val mode: VcsDiffMode)

    private val current = ConcurrentHashMap<Location, VcsBranchInfo>()
    private val status = ConcurrentHashMap<Location, List<VcsFile>>()
    private val diffs = ConcurrentHashMap<DiffKey, List<FileDiff>>()
    private val branches = ConcurrentHashMap<Location, List<VcsBranch>>()
    private val diffQueries = java.util.Collections.synchronizedList(mutableListOf<VcsDiffQuery>())

    /** The queries [diff] received, oldest first. */
    val receivedDiffQueries: List<VcsDiffQuery>
        get() = diffQueries.toList()

    fun setCurrent(location: Location, info: VcsBranchInfo) {
        current[location] = info
    }

    fun setStatus(location: Location, files: List<VcsFile>) {
        status[location] = files
    }

    fun setDiff(location: Location, files: List<FileDiff>, mode: VcsDiffMode = VcsDiffMode.Working) {
        diffs[DiffKey(location, mode)] = files
    }

    fun setBranches(location: Location, names: List<VcsBranch>) {
        branches[location] = names
    }

    override suspend fun current(location: Location): VcsBranchInfo =
        control.call("vcs.current") { current[location] ?: VcsBranchInfo() }

    override suspend fun status(location: Location): List<VcsFile> =
        control.call("vcs.status") { status[location].orEmpty() }

    override suspend fun diff(location: Location, query: VcsDiffQuery): List<FileDiff> = control.call("vcs.diff") {
        diffQueries += query
        diffs[DiffKey(location, query.mode)].orEmpty()
    }

    override suspend fun branches(location: Location, search: String?, limit: Int?): List<VcsBranch> =
        control.call("vcs.branches") {
            checkLimit(limit)
            branches[location].orEmpty()
                .filter { search == null || it.name.contains(search, ignoreCase = true) }
                .take(limit ?: Int.MAX_VALUE)
        }
}

/** File name search over a list a test fills in, per folder. */
class FakeFsApi(private val control: FakeApiControl = FakeApiControl()) : FsApi {
    private val files = ConcurrentHashMap<Location, List<FileEntry>>()

    fun seed(location: Location, entries: List<FileEntry>) {
        files[location] = entries
    }

    override suspend fun find(
        location: Location,
        query: String,
        type: FileSearchType?,
        limit: Int?,
    ): List<FileEntry> = control.call("fs.find") {
        checkLimit(limit)
        files[location].orEmpty()
            .filter { it.path.contains(query, ignoreCase = true) && it.isA(type) }
            .take(limit ?: Int.MAX_VALUE)
    }

    private fun FileEntry.isA(type: FileSearchType?): Boolean = when (type) {
        null -> true
        FileSearchType.File -> this.type == FileEntryType.File
        FileSearchType.Directory -> this.type == FileEntryType.Directory
    }
}
