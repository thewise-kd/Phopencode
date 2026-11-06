package io.github.thewisekd.phopencode.core.git.command

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GitInputValidationTest {
    private val dir = "/home/u/project"

    private val badRefNames = listOf(
        "", "-D", "--force", "-f", "+main", "+refs/heads/main", "a..b", "a b", "a\tb", "x:y", ":main",
        "main~1", "main^", "a?b", "a*b", "a[b", "a\\b", "a@{1}", "@", "HEAD", "/main", "main/", "a//b",
        "main.", ".hidden", "a/.b", "main.lock", "a/b.lock", "new\nline", "nul\u0000", "del\u007f",
        "x".repeat(1025),
    )

    private val goodRefNames = listOf("main", "feature/x", "fix-1", "release_2.0", "çalış/İğ", "a.b", "a@b", "v1.2-rc")

    @Test
    fun `branch names that could be options, ranges or refspecs are rejected`() {
        badRefNames.forEach { name ->
            assertFalse(GitNames.isValidBranchName(name), "accepted [$name]")
            assertFailsWith<IllegalArgumentException> { GitCommands.createBranch(dir, name) }
            assertFailsWith<IllegalArgumentException> { GitCommands.switchBranch(dir, name) }
            assertFailsWith<IllegalArgumentException> { GitCommands.deleteBranch(dir, name) }
            assertFailsWith<IllegalArgumentException> { GitCommands.push(dir, "origin", name) }
        }
        goodRefNames.forEach { assertTrue(GitNames.isValidBranchName(it), "rejected [$it]") }
    }

    @Test
    fun `remote names that could be options or URLs are rejected`() {
        val badRemotes = badRefNames + listOf(
            "https://example.com/x.git", "ext::sh -c x", "/abs/path", "../up", "u@h:x", "team/fork", "a/b",
            "~user", "~", ".", "..", ".git", "a\\b", "file:x",
        )
        badRemotes.forEach { remote ->
            assertFalse(GitNames.isValidRemoteName(remote), "accepted [$remote]")
            assertFailsWith<IllegalArgumentException> { GitCommands.fetch(dir, remote) }
            assertFailsWith<IllegalArgumentException> { GitCommands.push(dir, remote, "main") }
        }
        listOf("origin", "upstream", "my-fork", "fork.2", "çatal").forEach {
            assertTrue(GitNames.isValidRemoteName(it), "rejected [$it]")
        }
    }

    @Test
    fun `revisions may be ranges and ancestors but never options`() {
        listOf("HEAD", "HEAD~3", "abc123^", "main..feature", "origin/main...HEAD", "HEAD@{1}", "v1.0^{commit}", "^main")
            .forEach { assertTrue(GitNames.isValidRevision(it), "rejected [$it]") }
        listOf("", "-n", "--all", "--output=/tmp/x", "a\nb", "a\u0000b", "a\rb", "x".repeat(1025)).forEach { revision ->
            assertFalse(GitNames.isValidRevision(revision), "accepted [$revision]")
            assertFailsWith<IllegalArgumentException> { GitCommands.show(dir, revision) }
            assertFailsWith<IllegalArgumentException> { GitCommands.log(dir, format = "%H", maxCount = 1, revision = revision) }
            assertFailsWith<IllegalArgumentException> { GitCommands.createBranch(dir, "b", startPoint = revision) }
            assertFailsWith<IllegalArgumentException> { GitCommands.mergeFastForward(dir, revision) }
        }
    }

    @Test
    fun `a fast-forward target is a revision, never an option`() {
        assertFailsWith<IllegalArgumentException> { GitCommands.mergeFastForward(dir, "--no-ff") }
        assertFailsWith<IllegalArgumentException> { GitCommands.mergeFastForward(dir, "-s") }
    }

    @Test
    fun `the directory must be an absolute path`() {
        listOf("", "relative/dir", "~/project", "-rf", "nul\u0000dir").forEach { bad ->
            assertFailsWith<IllegalArgumentException> { GitCommands.status(bad) }
        }
    }

    @Test
    fun `paths must be real paths and changes need at least one`() {
        assertFailsWith<IllegalArgumentException> { GitCommands.add(dir, emptyList()) }
        assertFailsWith<IllegalArgumentException> { GitCommands.unstage(dir, emptyList()) }
        assertFailsWith<IllegalArgumentException> { GitCommands.discardWorktree(dir, emptyList()) }
        assertFailsWith<IllegalArgumentException> { GitCommands.add(dir, listOf("")) }
        assertFailsWith<IllegalArgumentException> { GitCommands.add(dir, listOf("a\u0000b")) }
        assertFailsWith<IllegalArgumentException> { GitCommands.status(dir, paths = listOf("")) }
        assertFailsWith<IllegalArgumentException> { GitCommands.add(dir, listOf("\uD800")) }
    }

    @Test
    fun `an untracked diff takes only a plain relative path inside the work tree`() {
        val bad = listOf(
            "", "/etc/passwd", "/", "../x", "..", "a/../b", "a/..", ".", "./a", "a/./b", "a//b", "a/", "/a",
            ".git", ".git/config", "a/.git/HEAD", ".GIT/config", "a/.Git", "a\u0000b", "\uD800",
        )
        bad.forEach { path ->
            assertFailsWith<IllegalArgumentException>(path) { GitCommands.diffUntracked(dir, path) }
            assertFailsWith<IllegalArgumentException>(path) { GitCommands.untrackedFolderCheck(dir, path) }
        }
        listOf("-rf", "a/b.txt", "..a", "a..", "a/...", ".gitignore", "a/.github/x", "new\nline", "a b").forEach { path ->
            GitCommands.diffUntracked(dir, path)
            GitCommands.untrackedFolderCheck(dir, path)
        }
        assertFailsWith<IllegalArgumentException> { GitCommands.diffUntracked(dir, "a", contextLines = -1) }
        assertFailsWith<IllegalArgumentException> { GitCommands.diffUntracked("relative", "a") }
    }

    @Test
    fun `the first parent needs diff output and tracking needs a start point`() {
        assertFailsWith<IllegalArgumentException> { GitCommands.show(dir, "HEAD", output = ShowOutput.None, firstParent = true) }
        assertFailsWith<IllegalArgumentException> { GitCommands.createBranch(dir, "b", track = BranchTracking.Track) }
        GitCommands.createBranch(dir, "b", track = BranchTracking.NoTrack)
    }

    @Test
    fun `a blank commit message is rejected`() {
        listOf("", " ", "\n\t ").forEach { message ->
            assertFailsWith<IllegalArgumentException> { GitCommands.commit(dir, message) }
        }
        assertFailsWith<IllegalArgumentException> { GitCommands.commit(dir, "a\u0000b") }
    }

    @Test
    fun `counts and formats are checked`() {
        assertFailsWith<IllegalArgumentException> { GitCommands.log(dir, format = "%H", maxCount = 0) }
        assertFailsWith<IllegalArgumentException> { GitCommands.log(dir, format = "%H", maxCount = 5, skip = -1) }
        assertFailsWith<IllegalArgumentException> { GitCommands.diff(dir, staged = false, contextLines = -1) }
        assertFailsWith<IllegalArgumentException> { GitCommands.log(dir, format = "a\u0000b", maxCount = 5) }
    }

    @Test
    fun `error messages never repeat the rejected value`() {
        val value = "-secret-value"
        val messages = listOf(
            runCatching { GitCommands.createBranch(dir, value) },
            runCatching { GitCommands.fetch(dir, value) },
            runCatching { GitCommands.show(dir, value) },
            runCatching { GitCommands.status("relative-secret-value") },
        ).map { it.exceptionOrNull()?.message.orEmpty() }
        messages.forEach { assertFalse("secret" in it, it) }
        assertEquals(4, messages.count { it.isNotEmpty() })
    }

    @Test
    fun `force push cannot be expressed`() {
        // The only push builder takes a validated remote and branch and writes a refspec without `+`.
        val command = GitCommands.push(dir, "origin", "main", setUpstream = true)
        val gitArgs = command.argv.drop(command.argv.indexOf("-C") + 2)
        gitArgs.forEach { arg ->
            assertFalse(arg.startsWith("+"), "force refspec")
            assertFalse(arg == "-f" || arg.startsWith("--force") || arg == "--mirror" || arg == "--delete" || arg == "-d")
        }
        assertFailsWith<IllegalArgumentException> { GitCommands.push(dir, "origin", "+main") }
        assertFailsWith<IllegalArgumentException> { GitCommands.push(dir, "--force", "main") }
        assertFailsWith<IllegalArgumentException> { GitCommands.push(dir, "origin", ":main") }
    }
}
