package io.github.thewisekd.phopencode.feature.git.logic.contract

import io.github.thewisekd.phopencode.core.navigation.GitRoute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class ContractTypesTest {
    // Assembled at runtime, so that the source holds no secret-shaped literal.
    private val secretPath = listOf("src", "gizli", "token-42.env").joinToString("/")
    private val secretText = listOf("password", "=", "hunter", "2").joinToString("")
    private val secretEmail = listOf("ayse", "@", "example.com").joinToString("")
    private val secretName = listOf("Ayşe", "Yılmaz").joinToString(" ")
    private val secretBranch = listOf("feature", "gizli-iş").joinToString("/")
    private val secretUrl = listOf("https://", "user:", "pa55", "@", "git.example.com/repo.git").joinToString("")
    private val root = "/srv/çalışma alanı"
    private val hash = "0123456789abcdef0123456789abcdef01234567"
    private val time = Instant.fromEpochSeconds(1_700_000_000)

    private fun assertMasked(value: Any, vararg hidden: String) {
        val printed = value.toString()
        hidden.forEach { assertFalse(it in printed, "$printed shows a hidden value") }
    }

    private fun change(path: String = secretPath, kind: GitChangeKind = GitChangeKind.Modified) = GitFileChange(path, kind)

    private fun summary() = CommitSummary(
        hash = hash,
        parents = listOf("1111111111111111111111111111111111111111"),
        subject = secretText,
        authorName = secretName,
        authorEmail = secretEmail,
        authoredAt = time,
        refs = listOf(secretBranch),
    )

    private fun branch(name: String, current: Boolean = false, remote: Boolean = false) =
        GitBranch(name = name, isCurrent = current, isRemote = remote)

    @Test
    fun `change kinds are fixed`() {
        assertEquals(
            listOf(
                GitChangeKind.Added,
                GitChangeKind.Modified,
                GitChangeKind.Deleted,
                GitChangeKind.Renamed,
                GitChangeKind.Copied,
                GitChangeKind.TypeChanged,
                GitChangeKind.Untracked,
                GitChangeKind.Unmerged,
            ),
            GitChangeKind.entries,
        )
    }

    @Test
    fun `a change and a branch status print no path or branch name`() {
        assertMasked(GitFileChange(secretPath, GitChangeKind.Renamed, originalPath = "$secretPath.old"), secretPath)
        val branch = GitBranchStatus(head = secretBranch, commit = hash, upstream = "origin/$secretBranch", ahead = 2, behind = 1)
        assertMasked(branch, secretBranch)
        assertFalse(branch.isDetached)
        assertTrue(branch.hasUpstream)
        assertTrue(GitBranchStatus(head = null, commit = hash).isDetached)
        assertFalse(GitBranchStatus(head = "main").hasUpstream)
    }

    @Test
    fun `status starts loading and is clean only once loaded without changes`() {
        with(StatusState(directory = root)) {
            assertTrue(loading)
            assertFalse(refreshing)
            assertNull(branch)
            assertTrue(staged.isEmpty() && unstaged.isEmpty() && untracked.isEmpty() && conflicted.isEmpty())
            assertTrue(pending.isEmpty())
            assertNull(error)
            assertNull(actionError)
            assertFalse(isClean)
            assertEquals(0, changeCount)
        }
        assertTrue(StatusState(directory = root, loading = false).isClean)
        assertFalse(StatusState(directory = root, loading = false, error = GitFailure.NotRepository).isClean)
        val dirty = StatusState(
            directory = root,
            loading = false,
            staged = listOf(change()),
            unstaged = listOf(change()),
            untracked = listOf(change(kind = GitChangeKind.Untracked)),
            conflicted = listOf(change(kind = GitChangeKind.Unmerged)),
        )
        assertFalse(dirty.isClean)
        assertEquals(4, dirty.changeCount)
    }

    @Test
    fun `status, its intents and events print no path`() {
        val state = StatusState(directory = root, staged = listOf(change()), pending = setOf(secretPath))
        assertMasked(state, secretPath, root)
        assertMasked(StatusIntent.Stage(change()), secretPath)
        assertMasked(StatusIntent.OpenDiff(change(), staged = true), secretPath)
        assertMasked(StatusEvent.OpenDiff(secretPath, staged = false), secretPath)
    }

    @Test
    fun `a diff target follows its route`() {
        assertEquals(DiffTarget.Working("a.kt"), DiffTarget.of(GitRoute.FileDiff(root, "a.kt")))
        assertEquals(DiffTarget.Staged("a.kt"), DiffTarget.of(GitRoute.FileDiff(root, "a.kt", staged = true)))
        assertEquals(DiffTarget.InCommit(hash, "a.kt"), DiffTarget.of(GitRoute.CommitFileDiff(root, hash, "a.kt")))
        assertEquals("a.kt", DiffTarget.InCommit(hash, "a.kt").path)
        assertMasked(DiffTarget.Staged(secretPath), secretPath)
        assertMasked(DiffTarget.InCommit(hash, secretPath), secretPath)
    }

    @Test
    fun `a diff starts loading`() {
        with(DiffState(target = DiffTarget.Working("a.kt"))) {
            assertTrue(loading)
            assertNull(diff)
            assertNull(error)
        }
    }

    @Test
    fun `diff lines carry the numbers of their side`() {
        assertEquals(3, DiffLine(DiffLineKind.Context, "x", oldNumber = 3, newNumber = 4).oldNumber)
        assertEquals(4, DiffLine(DiffLineKind.Added, "x", oldNumber = null, newNumber = 4).newNumber)
        assertNull(DiffLine(DiffLineKind.Deleted, "x", oldNumber = 3, newNumber = null).newNumber)
        DiffLine(DiffLineKind.NoNewline, "\\ No newline at end of file", oldNumber = null, newNumber = null)
        assertFailsWith<IllegalArgumentException> { DiffLine(DiffLineKind.Added, "x", oldNumber = 3, newNumber = 4) }
        assertFailsWith<IllegalArgumentException> { DiffLine(DiffLineKind.Deleted, "x", oldNumber = null, newNumber = 4) }
        assertFailsWith<IllegalArgumentException> { DiffLine(DiffLineKind.Context, "x", oldNumber = 3, newNumber = null) }
        assertEquals(
            listOf(DiffLineKind.Context, DiffLineKind.Added, DiffLineKind.Deleted, DiffLineKind.NoNewline),
            DiffLineKind.entries,
        )
    }

    @Test
    fun `a diff prints no path or content`() {
        val hunk = DiffHunk(
            oldStart = 1,
            oldCount = 2,
            newStart = 1,
            newCount = 3,
            section = "fun $secretText()",
            lines = listOf(
                DiffLine(DiffLineKind.Context, secretText, oldNumber = 1, newNumber = 1),
                DiffLine(DiffLineKind.Added, secretText, oldNumber = null, newNumber = 2),
            ),
        )
        val diff = GitFileDiff(
            path = secretPath,
            oldPath = "$secretPath.old",
            kind = GitChangeKind.Renamed,
            content = DiffContent.Text(listOf(hunk)),
            additions = 1,
            deletions = 0,
        )
        assertMasked(diff, secretPath, secretText)
        assertMasked(DiffState(target = DiffTarget.Working(secretPath), loading = false, diff = diff), secretPath, secretText)
        assertEquals(1, diff.additions)
        assertEquals(0, diff.deletions)
        assertTrue(GitFileDiff(secretPath, content = DiffContent.Binary, additions = 0, deletions = 0).isBinary)
        assertFalse(diff.isBinary)
        assertEquals(1024L, DiffContent.TooLarge(limitBytes = 1024).limitBytes)
    }

    @Test
    fun `a commit summary shortens its hash and prints no message, name or email`() {
        val commit = summary()
        assertEquals("0123456", commit.shortHash)
        assertFalse(commit.isMerge)
        assertTrue(commit.copy(parents = listOf(hash, hash)).isMerge)
        assertMasked(commit, secretText, secretName, secretEmail, secretBranch)
    }

    @Test
    fun `the log starts loading the first page of the current branch`() {
        with(LogState(directory = root)) {
            assertNull(branch)
            assertTrue(branches.isEmpty())
            assertTrue(commits.isEmpty())
            assertTrue(loading)
            assertFalse(loadingMore)
            assertFalse(endReached)
            assertNull(error)
            assertNull(moreError)
            assertFalse(canLoadMore)
            assertFalse(isEmpty)
        }
        val loaded = LogState(directory = root, loading = false, commits = listOf(summary()))
        assertTrue(loaded.canLoadMore)
        assertFalse(loaded.copy(loadingMore = true).canLoadMore)
        assertFalse(loaded.copy(endReached = true).canLoadMore)
        assertTrue(LogState(directory = root, loading = false, endReached = true).isEmpty)
        assertMasked(LogState(directory = root, branch = secretBranch, branches = listOf(secretBranch)), secretBranch, root)
        assertMasked(LogIntent.FilterBranch(secretBranch), secretBranch)
    }

    @Test
    fun `commit details start loading and print no message, name, email or path`() {
        with(CommitDetailState(commit = hash)) {
            assertTrue(loading)
            assertNull(detail)
            assertNull(error)
        }
        val detail = CommitDetail(
            summary = summary(),
            body = secretText,
            committerName = secretName,
            committerEmail = secretEmail,
            committedAt = time,
            files = listOf(
                CommitFileChange(secretPath, GitChangeKind.Modified, additions = 3, deletions = 1),
                CommitFileChange("$secretPath.png", GitChangeKind.Added, additions = null, deletions = null),
            ),
        )
        assertTrue(detail.files[1].isBinary)
        assertFalse(detail.files[0].isBinary)
        assertMasked(CommitDetailState(commit = hash, loading = false, detail = detail), secretText, secretName, secretEmail, secretPath)
        assertMasked(CommitDetailIntent.OpenFile(secretPath), secretPath)
        assertMasked(CommitDetailEvent.OpenFileDiff(secretPath), secretPath)
    }

    @Test
    fun `a commit is possible only with a message, staged changes and nothing running`() {
        with(CommitState(directory = root)) {
            assertTrue(loading)
            assertEquals("", message)
            assertNull(busy)
            assertNull(confirmDiscard)
            assertNull(messageProblem)
            assertNull(error)
            assertFalse(canCommit)
        }
        val ready = CommitState(directory = root, loading = false, message = "Fix", staged = listOf(change()))
        assertTrue(ready.canCommit)
        assertFalse(ready.copy(message = "  \n").canCommit)
        assertFalse(ready.copy(staged = emptyList()).canCommit)
        assertFalse(ready.copy(busy = CommitOp.Stage).canCommit)
        assertEquals(listOf(CommitOp.Stage, CommitOp.Unstage, CommitOp.Discard, CommitOp.Commit), CommitOp.entries)
        assertEquals(listOf(CommitMessageProblem.Blank), CommitMessageProblem.entries)
    }

    @Test
    fun `only tracked changes can be discarded`() {
        assertTrue(CommitState.canDiscard(change()))
        assertTrue(CommitState.canDiscard(change(kind = GitChangeKind.Deleted)))
        assertFalse(CommitState.canDiscard(change(kind = GitChangeKind.Untracked)))
        assertFalse(CommitState.canDiscard(change(kind = GitChangeKind.Unmerged)))
    }

    @Test
    fun `the commit screen prints no message, path or hook output`() {
        val state = CommitState(
            directory = root,
            message = secretText,
            unstaged = listOf(change()),
            confirmDiscard = change(),
            error = CommitError.HookRejected(output = secretText),
        )
        assertMasked(state, secretText, secretPath, root)
        assertMasked(CommitIntent.EditMessage(secretText), secretText)
        assertMasked(CommitError.HookRejected(secretText), secretText)
        assertMasked(CommitEvent.OpenDiff(secretPath, staged = true), secretPath)
        assertEquals(CommitOp.Commit, CommitError.Failed(CommitOp.Commit, GitFailure.GitError(1)).op)
    }

    @Test
    fun `branches start loading and the search filters both lists`() {
        with(BranchesState(directory = root)) {
            assertTrue(loading)
            assertTrue(local.isEmpty() && remote.isEmpty())
            assertEquals("", query)
            assertFalse(dirty)
            assertNull(dialog)
            assertNull(busy)
            assertNull(error)
            assertNull(actionError)
            assertNull(current)
        }
        val main = branch("main", current = true)
        val feature = branch("feature/Arama")
        val remoteMain = branch("origin/main", remote = true)
        val remoteSearch = branch("origin/feature/arama", remote = true)
        val state = BranchesState(directory = root, loading = false, local = listOf(main, feature), remote = listOf(remoteMain, remoteSearch))
        assertEquals(main, state.current)
        assertEquals(listOf(main, feature), state.shownLocal)
        val searched = state.copy(query = " ARAMA ")
        assertEquals(listOf(feature), searched.shownLocal)
        assertEquals(listOf(remoteSearch), searched.shownRemote)
    }

    @Test
    fun `a new branch is submitted only with a name without a problem`() {
        assertTrue(BranchDialog.Create(name = "feature/x").canSubmit)
        assertFalse(BranchDialog.Create(name = " ").canSubmit)
        assertFalse(BranchDialog.Create(name = "x", problem = BranchNameProblem.Taken).canSubmit)
        assertTrue(BranchDialog.Create().switchAfter)
        assertEquals(listOf(BranchNameProblem.Blank, BranchNameProblem.Invalid, BranchNameProblem.Taken), BranchNameProblem.entries)
        assertEquals(listOf(BranchOp.Create, BranchOp.Switch, BranchOp.Delete), BranchOp.entries)
    }

    @Test
    fun `branch screens print no branch name`() {
        val secret = branch(secretBranch)
        assertMasked(secret, secretBranch)
        assertMasked(
            BranchesState(directory = root, local = listOf(secret), query = secretBranch, dialog = BranchDialog.ConfirmDeleteUnmerged(secret)),
            secretBranch,
            root,
        )
        assertMasked(BranchDialog.Create(name = secretBranch, startPoint = secretBranch), secretBranch)
        assertMasked(BranchIntent.Search(secretBranch), secretBranch)
        assertMasked(BranchIntent.EditName(secretBranch), secretBranch)
    }

    @Test
    fun `remote operations start loading and run one at a time`() {
        with(RemoteState(directory = root)) {
            assertTrue(loading)
            assertTrue(remotes.isEmpty())
            assertNull(selected)
            assertNull(branch)
            assertNull(running)
            assertNull(confirmPush)
            assertNull(result)
            assertNull(error)
            assertFalse(canStart)
        }
        val ready = RemoteState(directory = root, loading = false, remotes = listOf(GitRemote("origin")), selected = "origin")
        assertTrue(ready.canStart)
        assertFalse(ready.copy(running = RemoteOp.Fetch).canStart)
        assertFalse(ready.copy(selected = null).canStart)
        assertEquals(listOf(RemoteOp.Fetch, RemoteOp.Pull, RemoteOp.Push), RemoteOp.entries)
        assertEquals(RemoteOp.Pull, RemoteResult.Failed(RemoteOp.Pull, GitFailure.Diverged).op)
        assertEquals(RemoteOp.Push, RemoteResult.Cancelled(RemoteOp.Push).op)
        assertTrue(RemoteResult.Succeeded(RemoteOp.Fetch, upToDate = true).upToDate)
    }

    @Test
    fun `remote screens print no address or branch name`() {
        val remote = GitRemote(name = "origin", fetchUrl = secretUrl, pushUrl = secretUrl)
        assertMasked(remote, secretUrl)
        val plan = PushPlan(remote = "origin", branch = secretBranch, ahead = 3, setUpstream = true)
        assertMasked(plan, secretBranch)
        assertMasked(RemoteState(directory = root, remotes = listOf(remote), confirmPush = plan), secretUrl, secretBranch, root)
    }

    @Test
    fun `nothing in the remote contract can force a push`() {
        val types = listOf(RemoteIntent::class.java, RemoteEvent::class.java, RemoteState::class.java, PushPlan::class.java)
        val names = types.flatMap { type ->
            type.declaredClasses.map { it.simpleName } + type.declaredFields.map { it.name } + type.declaredMethods.map { it.name }
        }
        assertTrue(names.isNotEmpty())
        names.forEach { assertFalse("force" in it.lowercase(), "$it offers a forced push") }
    }

    @Test
    fun `failures name their case and nothing else`() {
        assertEquals("NotRepository", GitFailure.NotRepository.toString())
        assertEquals("CredentialRequired", GitFailure.CredentialRequired.toString())
    }
}
