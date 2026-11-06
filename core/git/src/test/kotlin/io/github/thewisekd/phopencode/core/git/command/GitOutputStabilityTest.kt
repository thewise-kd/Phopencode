package io.github.thewisekd.phopencode.core.git.command

import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir

/**
 * The output the parsers read does not change with the server user's diff settings, and the
 * parsers' formats work against real git.
 */
class GitOutputStabilityTest {
    @TempDir
    lateinit var tmp: Path

    private lateinit var sandbox: GitSandbox
    private lateinit var repo: Path

    @BeforeTest
    fun setUp() {
        sandbox = GitSandbox(tmp)
        repo = sandbox.newRepo()
        repo.resolve("sub").createDirectories()
        sandbox.commitFile(repo, "sub/old name.txt", (1..20).joinToString("") { "line $it\n" }, message = "one")
        // Settings that would each change the patch if they were not overridden.
        listOf(
            "diff.noPrefix" to "true",
            "diff.mnemonicPrefix" to "true",
            "diff.relative" to "true",
            "diff.renames" to "false",
            "core.abbrev" to "7",
        ).forEach { (key, value) -> sandbox.git(repo, "config", key, value) }
    }

    private fun run(command: GitCommand): String = runBlocking {
        val result = sandbox.runner.run(command)
        assertTrue(result.succeeded, result.stderrText())
        String(result.stdout, Charsets.UTF_8)
    }

    private fun renameAndCommit() {
        sandbox.git(repo, "mv", "--", "sub/old name.txt", "sub/new name.txt")
        sandbox.git(repo, "commit", "-q", "-m", "rename")
    }

    @Test
    fun `show with an empty format prints a pure patch with fixed prefixes, renames and full hashes`() {
        repo.resolve("sub/old name.txt").writeText((1..20).joinToString("") { if (it == 5) "changed\n" else "line $it\n" })
        sandbox.git(repo, "commit", "-q", "-am", "edit")
        val patch = run(GitCommands.show(repo.resolve("sub").absolutePathString(), "HEAD"))
        assertTrue(patch.startsWith("diff --git a/sub/old name.txt b/sub/old name.txt\n"), patch)
        val index = patch.lines().first { it.startsWith("index ") }
        assertTrue(Regex("index [0-9a-f]{40}\\.\\.[0-9a-f]{40} 100644").matches(index), index)
        // Git ends a ---/+++ name that contains a space with a tab.
        assertTrue("--- a/sub/old name.txt\t\n+++ b/sub/old name.txt\t\n" in patch, patch)

        renameAndCommit()
        val rename = run(GitCommands.show(repo.absolutePathString(), "HEAD"))
        assertTrue(rename.startsWith("diff --git a/sub/old name.txt b/sub/new name.txt\n"), rename)
        assertTrue("rename from sub/old name.txt\nrename to sub/new name.txt\n" in rename, rename)
    }

    @Test
    fun `diff uses root paths, a and b prefixes and full hashes from a subfolder`() {
        repo.resolve("sub/old name.txt").writeText("short\n")
        val patch = run(GitCommands.diff(repo.resolve("sub").absolutePathString(), staged = false))
        assertTrue(patch.startsWith("diff --git a/sub/old name.txt b/sub/old name.txt\n"), patch)
        assertTrue(Regex("(?m)^index [0-9a-f]{40}\\.\\.[0-9a-f]{40} 100644$").containsMatchIn(patch), patch)
        val numstat = run(GitCommands.diff(repo.resolve("sub").absolutePathString(), staged = false, output = DiffOutput.NumStat))
        assertTrue(numstat.endsWith("\tsub/old name.txt\u0000"), numstat)
    }

    @Test
    fun `staged renames are detected in name-status despite diff renames off`() {
        sandbox.git(repo, "mv", "--", "sub/old name.txt", "sub/new name.txt")
        val nameStatus = run(GitCommands.diff(repo.absolutePathString(), staged = true, output = DiffOutput.NameStatus))
        val records = nameStatus.split('\u0000').filter { it.isNotEmpty() }
        assertEquals(listOf("sub/old name.txt", "sub/new name.txt"), records.drop(1))
        assertTrue(records[0].startsWith("R"), nameStatus)
    }

    @Test
    fun `status counts ahead and behind and finds renames despite the user's status settings`() {
        sandbox.git(repo, "config", "status.aheadBehind", "false")
        sandbox.git(repo, "config", "status.renames", "false")
        sandbox.git(repo, "branch", "base")
        sandbox.git(repo, "config", "branch.main.remote", ".")
        sandbox.git(repo, "config", "branch.main.merge", "refs/heads/base")
        sandbox.commitFile(repo, "x.txt", "x\n", message = "ahead")
        sandbox.git(repo, "mv", "--", "sub/old name.txt", "sub/new name.txt")
        val records = run(GitCommands.status(repo.absolutePathString())).split('\u0000')
        assertTrue("# branch.ab +1 -0" in records, records.toString())
        val rename = records.indexOfFirst { it.startsWith("2 R") }
        assertTrue(rename >= 0, records.toString())
        assertTrue(records[rename].endsWith(" sub/new name.txt"), records[rename])
        assertEquals("sub/old name.txt", records[rename + 1])
    }

    @Test
    fun `a submodule change stays a Subproject commit line despite diff submodule log`() {
        sandbox.git(repo, "config", "diff.submodule", "log")
        val head = sandbox.gitText(repo, "rev-parse", "HEAD").trim()
        sandbox.git(repo, "update-index", "--add", "--cacheinfo", "160000,$head,sm")
        val patch = run(GitCommands.diff(repo.absolutePathString(), staged = true))
        assertTrue(patch.startsWith("diff --git a/sm b/sm\n"), patch)
        assertTrue("+Subproject commit $head\n" in patch, patch)
    }

    @Test
    fun `log and show print UTF-8 whatever the log output encoding`() {
        sandbox.git(repo, "config", "i18n.logOutputEncoding", "ISO-8859-9")
        sandbox.commitFile(repo, "t.txt", "t\n", message = "çğış İ")
        assertEquals("çğış İ", run(GitCommands.log(repo.absolutePathString(), format = "%s", maxCount = 1)).trimEnd('\u0000', '\n'))
        val show = run(GitCommands.show(repo.absolutePathString(), "HEAD", format = "%s", output = ShowOutput.None))
        assertEquals("çğış İ", show.trimEnd('\n'))
    }

    @Test
    fun `the log parser format works and keeps fields apart with NUL`() {
        val format = "%H%x00%P%x00%an%x00%ae%x00%at%x00%cn%x00%ce%x00%ct%x00%s%x00%b"
        val log = run(GitCommands.log(repo.absolutePathString(), format = format, maxCount = 10))
        val fields = log.split('\u0000')
        assertEquals(40, fields[0].length)
        assertEquals("Test User", fields[2])
        assertEquals("one", fields[8])
    }

    @Test
    fun `the branch parser format works and keeps fields apart with NUL`() {
        val format = "%(refname)%00%(HEAD)%00%(objectname)%00%(upstream)%00%(upstream:track,nobracket)%00%(symref)"
        val list = run(GitCommands.branchList(repo.absolutePathString(), format = format, scope = BranchScope.All))
        val fields = list.removeSuffix("\n").split('\u0000')
        assertEquals("refs/heads/main", fields[0])
        assertEquals("*", fields[1])
        assertEquals(40, fields[2].length)
        assertEquals(6, fields.size)
    }
}
