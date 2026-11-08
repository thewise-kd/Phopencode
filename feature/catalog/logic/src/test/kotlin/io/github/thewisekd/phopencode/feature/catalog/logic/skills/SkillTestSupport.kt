package io.github.thewisekd.phopencode.feature.catalog.logic.skills

import io.github.thewisekd.phopencode.core.api.ServerInfo
import io.github.thewisekd.phopencode.core.model.ServerProfileId
import io.github.thewisekd.phopencode.core.model.Skill
import io.github.thewisekd.phopencode.core.remote.connection.ConnectionServer
import io.github.thewisekd.phopencode.core.remote.connection.ConnectionState
import io.github.thewisekd.phopencode.core.storage_api.HostEndpoint
import io.github.thewisekd.phopencode.core.testing.api.FakeOpencodeClient
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.ActiveCatalogConnection
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.SkillCatalog
import java.util.Collections
import kotlinx.coroutines.flow.MutableStateFlow

internal fun skill(id: String, name: String = id, description: String? = null, path: String = "/srv/skills/$id/SKILL.md") =
    Skill(id = id, name = name, description = description, path = path, content = "# $name")

/** The app's connection state, driven by hand, with the server a fake client stands for. */
internal class TestServer {
    private val endpoint = ConnectionServer(ServerProfileId("server-1"), HostEndpoint("host.invalid", 22))

    val state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)

    val connection = ActiveCatalogConnection(state)

    fun connect(client: FakeOpencodeClient = FakeOpencodeClient(), generation: Long = 1): FakeOpencodeClient {
        state.value = ConnectionState.Connected(client, ServerInfo("2.2.5"), generation, endpoint)
        return client
    }

    fun disconnect() {
        state.value = ConnectionState.Idle
    }
}

/** Counts the asks of a [SkillCatalog], noting which asked for a fresh read. */
internal class RecordingSkillCatalog(private val delegate: SkillCatalog) : SkillCatalog {
    val refreshes: MutableList<Boolean> = Collections.synchronizedList(mutableListOf())

    override suspend fun skills(refresh: Boolean): List<Skill> {
        refreshes += refresh
        return delegate.skills(refresh)
    }
}

internal fun FakeOpencodeClient.skillReads(): Int = control.calls.count { it == "catalog.skills" }
