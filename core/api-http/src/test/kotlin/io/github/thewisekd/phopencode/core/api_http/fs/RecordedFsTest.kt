package io.github.thewisekd.phopencode.core.api_http.fs

import io.github.thewisekd.phopencode.core.api.FileSearchType
import io.github.thewisekd.phopencode.core.api.Location
import io.github.thewisekd.phopencode.core.api_http.base.ApiJson
import io.github.thewisekd.phopencode.core.api_http.base.TestServer
import io.github.thewisekd.phopencode.core.api_http.base.fixture
import io.github.thewisekd.phopencode.core.model.FileEntry
import io.github.thewisekd.phopencode.core.model.FileEntryType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Replays the recorded file name searches: same request, and the recorded answer decodes. */
class RecordedFsTest {
    private val project = Location("/workspace/project")

    private class Recorded(val target: String, val body: String)

    private fun recorded(label: String): Recorded {
        val line = fixture("catalog/exchanges.jsonl").lineSequence()
            .filter { it.isNotBlank() }
            .map { ApiJson.parseToJsonElement(it).jsonObject }
            .single { it.getValue("label").jsonPrimitive.content == label }
        return Recorded(
            target = line.getValue("path").jsonPrimitive.content,
            body = fixture("catalog/${line.getValue("response").jsonPrimitive.content}"),
        )
    }

    private suspend fun replay(label: String, call: suspend HttpFsApi.() -> List<FileEntry>): List<FileEntry> =
        TestServer().use { t ->
            val exchange = recorded(label)
            t.enqueue(body = exchange.body)
            val result = HttpFsApi(t.transport).call()
            val sent = t.server.takeRequest()
            assertEquals("GET", sent.method, label)
            assertEquals(exchange.target, sent.target, label)
            result
        }

    @Test
    fun `a file search matches the recording`() = runTest {
        assertEquals(
            listOf(FileEntry("notes.txt", FileEntryType.File), FileEntry(".gitignore", FileEntryType.File)),
            replay("fs-find-file") { find(project, "notes", FileSearchType.File, limit = 5) },
        )
    }

    /** The server ends a directory's path with `/`; the model holds the path without it, as SFTP gives it. */
    @Test
    fun `a directory search matches the recording`() = runTest {
        assertEquals(
            listOf(FileEntry("docs", FileEntryType.Directory)),
            replay("fs-find-directory") { find(project, "docs", FileSearchType.Directory, limit = 5) },
        )
    }
}
