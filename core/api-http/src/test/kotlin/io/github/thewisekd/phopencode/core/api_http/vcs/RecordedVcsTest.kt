package io.github.thewisekd.phopencode.core.api_http.vcs

import io.github.thewisekd.phopencode.core.api.Location
import io.github.thewisekd.phopencode.core.api.VcsBranch
import io.github.thewisekd.phopencode.core.api.VcsBranchInfo
import io.github.thewisekd.phopencode.core.api.VcsDiffMode
import io.github.thewisekd.phopencode.core.api.VcsDiffQuery
import io.github.thewisekd.phopencode.core.api_http.base.TestServer
import io.github.thewisekd.phopencode.core.model.FileDiff
import io.github.thewisekd.phopencode.core.model.VcsFile
import io.github.thewisekd.phopencode.core.model.VcsFileStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

/**
 * Replays the recorded VCS calls: each request must be the one the recording sent, and each recorded
 * answer must decode to what it says.
 */
class RecordedVcsTest {
    private val project = Location("/workspace/project")

    private val created = VcsFile("created.txt", VcsFileStatus.Added, additions = 1, deletions = 0)
    private val tracked = VcsFile("tracked.txt", VcsFileStatus.Added, additions = 1, deletions = 0)
    private val createdPatch = "diff --git a/created.txt b/created.txt\nnew file mode 100644\n" +
        "index 0000000..fd08742\n--- /dev/null\n+++ b/created.txt\n@@ -0,0 +1 @@\n+made by the write tool\n"
    private val trackedPatch = "diff --git a/tracked.txt b/tracked.txt\nnew file mode 100644\n" +
        "index 0000000..65b2df8\n--- /dev/null\n+++ b/tracked.txt\n@@ -0,0 +1 @@\n+beta\n"

    /** Answers with [exchanges] in order, runs [call], and checks every request against its recording. */
    private suspend fun <T> replay(vararg exchanges: VcsExchange, call: suspend HttpVcsApi.() -> T): T =
        TestServer().use { t ->
            exchanges.forEach { t.enqueue(status = it.status, body = it.responseBody) }
            val result = HttpVcsApi(t.transport).call()
            exchanges.forEach {
                val sent = t.server.takeRequest()
                assertEquals(it.method, sent.method, "$it method")
                assertEquals(it.target, sent.target, "$it target")
                assertEquals("", sent.body?.utf8() ?: "", "$it body")
            }
            assertEquals(exchanges.size, t.server.requestCount, "requests")
            result
        }

    @Test
    fun `current reads the branch and the review base`() = runTest {
        val info = replay(VcsRecordings.get("catalog", "vcs"), VcsRecordings.get("catalog", "vcs-base")) {
            current(project)
        }
        assertEquals(VcsBranchInfo(branch = "main", defaultBranch = "main"), info)
    }

    @Test
    fun `a repository with no commit has no branch`() = runTest {
        TestServer().use { t ->
            t.enqueue(body = VcsRecordings.get("git-setup", "vcs-after-init").responseBody)
            t.enqueue(body = """{"location":{"directory":"/workspace/project"},"data":null}""")
            assertEquals(VcsBranchInfo(), HttpVcsApi(t.transport).current(project))
            assertEquals(VcsRecordings.get("git-setup", "vcs-after-init").target, t.server.takeRequest().target)
        }
    }

    @Test
    fun `status matches the recording`() = runTest {
        val files = replay(VcsRecordings.get("catalog", "vcs-status")) { status(project) }
        assertEquals(listOf(created, tracked), files)
    }

    @Test
    fun `branches match the recording`() = runTest {
        val branches = replay(VcsRecordings.get("catalog", "vcs-branch")) { branches(project, limit = 10) }
        assertEquals(listOf(VcsBranch("main")), branches)
    }

    @Test
    fun `the working diff matches the recording`() = runTest {
        val diff = replay(VcsRecordings.get("catalog", "vcs-diff-working")) { diff(project) }
        assertEquals(listOf(FileDiff(created, createdPatch), FileDiff(tracked, trackedPatch)), diff)
    }

    @Test
    fun `the committed diff matches the recording`() = runTest {
        val diff = replay(VcsRecordings.get("catalog", "vcs-diff-committed")) {
            diff(project, VcsDiffQuery(mode = VcsDiffMode.Committed))
        }
        assertEquals(emptyList(), diff)
    }

    @Test
    fun `the branch diff with a base matches the recording`() = runTest {
        val diff = replay(VcsRecordings.get("catalog", "vcs-diff-branch")) {
            diff(project, VcsDiffQuery(mode = VcsDiffMode.Branch, base = "main"))
        }
        assertEquals(listOf(FileDiff(created, createdPatch), FileDiff(tracked, trackedPatch)), diff)
    }

    @Test
    fun `every recorded VCS read is covered above`() {
        val covered = setOf(
            "catalog/vcs", "catalog/vcs-base", "catalog/vcs-status", "catalog/vcs-branch",
            "catalog/vcs-diff-working", "catalog/vcs-diff-committed", "catalog/vcs-diff-branch",
            "git-setup/vcs-after-init",
        )
        assertEquals(covered, VcsRecordings.reads.map { it.toString() }.toSet())
    }
}
