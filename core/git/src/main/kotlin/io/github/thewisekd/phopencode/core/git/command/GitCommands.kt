package io.github.thewisekd.phopencode.core.git.command

import io.github.thewisekd.phopencode.core.ssh.api.ExecRequest
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** What `diff` prints. */
enum class DiffOutput {
    /** A unified diff. */
    Patch,

    /** `--numstat -z`: added and deleted line counts per file; `-` for both marks a binary file. */
    NumStat,

    /** `--name-status -z`: a status letter and the path per file. */
    NameStatus,
}

/** What `show` prints after the commit header. */
enum class ShowOutput {
    /** `-s`: the header alone, or the content of a blob. */
    None,
    Patch,
    NumStat,
    NameStatus,
}

/** How [GitCommands.createBranch] sets the new branch's upstream. */
enum class BranchTracking {
    /** No option: git's `branch.autoSetupMerge` decides (by default, a remote-tracking start point is tracked). */
    Default,

    /** `--track`: the start point becomes the upstream, whatever `branch.autoSetupMerge` says. */
    Track,

    /** `--no-track`: no upstream, whatever `branch.autoSetupMerge` says. */
    NoTrack,
}

/** Which branches `branchList` lists. */
enum class BranchScope { Local, Remote, All }

/**
 * Builds every git command the app runs. Each function checks its input and throws
 * [IllegalArgumentException] with a fixed message, which never repeats the value.
 *
 * The rules the functions enforce:
 * - [directory][status] is an absolute path on the server; it goes to `git -C`. Paths are relative
 *   to it, while `status` and `diff` print paths relative to the work tree root, so a caller that
 *   passes paths back must use the root ([revParseToplevel]) as the directory.
 * - Paths always come after `--`, so a file called `-rf` or `--all` is a path, and are literal
 *   (`--literal-pathspecs`), so `*.txt` matches only a file called `*.txt`. See [GitNames.isValidPath].
 * - Branch, remote and revision names come before `--`; they are checked by [GitNames] so that
 *   none can start with `-`.
 * - A commit message goes through stdin (`--file=-`), never into the command line.
 * - Remote names must name a configured remote; take them from [remoteList]. See
 *   [GitNames.isValidRemoteName] for what is rejected; [fetch] makes git itself refuse an
 *   unconfigured name.
 * - A file git does not track is read only through [GitRunner.diffUntracked], which first checks
 *   that it is an untracked file reached through real folders of the work tree.
 * - Untracked and ignored files are never deleted or overwritten: there is no `clean` builder,
 *   every command is git, `switch` and `merge` pass `--no-overwrite-ignore`, and discarding changes
 *   goes through the check in [GitRunner.discardWorktree].
 * - Remote commands cannot force: there is no option for it, a pull ([fetch], then
 *   [mergeFastForward]) only fast-forwards, and `push` sends an explicit refspec without `+` for
 *   one branch.
 *
 * Local commands default to [ExecRequest.DEFAULT_TIMEOUT]; remote ones to [REMOTE_TIMEOUT]. A
 * caller cancels a long operation by cancelling the coroutine that runs it.
 */
object GitCommands {
    /** Default limit for `fetch`, `pull` and `push`, which wait on another server. */
    val REMOTE_TIMEOUT: Duration = 10.minutes

    private val LOCAL_TIMEOUT: Duration = ExecRequest.DEFAULT_TIMEOUT

    /**
     * `status --porcelain=v2 -z --branch --ahead-behind --find-renames`: the branch header
     * (`# branch.head`, `# branch.ab` for ahead/behind) and one NUL-terminated record per changed
     * file. Ahead/behind counts and rename detection are pinned, so `status.aheadBehind` and
     * `status.renames` cannot change the output.
     *
     * @param includeUntracked Lists every untracked file (`--untracked-files=all`), not only folders.
     * @param paths Limits the status to these paths.
     */
    fun status(
        directory: String,
        includeUntracked: Boolean = true,
        paths: List<String> = emptyList(),
        timeout: Duration? = LOCAL_TIMEOUT,
    ): GitCommand = command(
        directory, "status", readOnly = true, timeout = timeout,
        options = listOf("--porcelain=v2", "-z", "--branch", "--ahead-behind", "--find-renames", if (includeUntracked) "--untracked-files=all" else "--untracked-files=no"),
        paths = paths,
    )

    /**
     * `diff` of the work tree against the index, or with [staged] of the index against `HEAD`.
     * External diff tools and text conversion filters are off, so the output is git's own.
     *
     * @param contextLines Lines of context around each change in a [DiffOutput.Patch].
     */
    fun diff(
        directory: String,
        staged: Boolean,
        paths: List<String> = emptyList(),
        output: DiffOutput = DiffOutput.Patch,
        contextLines: Int = DEFAULT_CONTEXT_LINES,
        timeout: Duration? = LOCAL_TIMEOUT,
    ): GitCommand {
        require(contextLines >= 0) { "Context lines must not be negative" }
        val options = buildList {
            addAll(DIFF_OPTIONS)
            if (staged) add("--cached")
            addAll(diffOutput(output, contextLines))
        }
        return command(directory, "diff", readOnly = true, timeout = timeout, options = options, paths = paths)
    }

    /**
     * `log -z`: one NUL-terminated record per commit in [format] (git's `--format` placeholders).
     *
     * @param maxCount Commits per page, at least 1.
     * @param skip Commits to skip, for the next page.
     * @param revision Where to start or a range, for example a branch; null for `HEAD`.
     * @param allRefs Every branch and tag, `--all`.
     * @param paths Only commits that touch these paths.
     */
    fun log(
        directory: String,
        format: String,
        maxCount: Int,
        skip: Int = 0,
        revision: String? = null,
        allRefs: Boolean = false,
        paths: List<String> = emptyList(),
        timeout: Duration? = LOCAL_TIMEOUT,
    ): GitCommand {
        require(maxCount > 0) { "Max count must be positive" }
        require(skip >= 0) { "Skip must not be negative" }
        val options = buildList {
            addAll(listOf("-z", "--no-color", "--no-show-signature", "--encoding=UTF-8", formatOption(format), "--max-count=$maxCount", "--skip=$skip"))
            if (allRefs) add("--all")
            if (revision != null) add(checkedRevision(revision))
        }
        return command(directory, "log", readOnly = true, timeout = timeout, options = options, paths = paths)
    }

    /**
     * `show` of one commit (header in [format], then [output]), or of a blob such as `HEAD:path` or
     * `:path` (the staged version), whose bytes come out unchanged.
     *
     * With the default empty [format] and [ShowOutput.Patch], the output is a pure patch: it starts
     * with the first `diff --git` line, with no header and no blank line (checked against real
     * git). Patch output always has `a/` and `b/` prefixes, root-relative paths, renames, full
     * hashes and short submodule lines, whatever the user's diff settings; text is UTF-8.
     *
     * For a merge commit, git prints a combined diff (`--cc`) by default, and `--name-status` lists
     * only the files that differ from every parent. With [firstParent], the diff is a plain one
     * against the first parent (`--diff-merges=first-parent`, git 2.31 or later), so added, deleted
     * and type-changed files show as such; it changes nothing for other commits.
     *
     * @param format The header; empty prints none.
     * @param firstParent Diff a merge against its first parent; needs an [output] other than
     *   [ShowOutput.None].
     */
    fun show(
        directory: String,
        revision: String,
        format: String = "",
        output: ShowOutput = ShowOutput.Patch,
        paths: List<String> = emptyList(),
        contextLines: Int = DEFAULT_CONTEXT_LINES,
        firstParent: Boolean = false,
        timeout: Duration? = LOCAL_TIMEOUT,
    ): GitCommand {
        require(contextLines >= 0) { "Context lines must not be negative" }
        require(!firstParent || output != ShowOutput.None) { "First parent needs diff output" }
        val options = buildList {
            addAll(DIFF_OPTIONS)
            add("--encoding=UTF-8")
            add(formatOption(format))
            when (output) {
                ShowOutput.None -> add("-s")
                ShowOutput.Patch -> addAll(diffOutput(DiffOutput.Patch, contextLines))
                ShowOutput.NumStat -> addAll(diffOutput(DiffOutput.NumStat, contextLines))
                ShowOutput.NameStatus -> addAll(diffOutput(DiffOutput.NameStatus, contextLines))
            }
            if (firstParent) add("--diff-merges=first-parent")
            add(checkedRevision(revision))
        }
        return command(directory, "show", readOnly = true, timeout = timeout, options = options, paths = paths)
    }

    /** `branch --list` with one line per branch in [format] (`for-each-ref` placeholders). */
    fun branchList(
        directory: String,
        format: String,
        scope: BranchScope = BranchScope.Local,
        timeout: Duration? = LOCAL_TIMEOUT,
    ): GitCommand {
        val options = buildList {
            addAll(listOf("--list", "--no-color", "--no-column", formatOption(format)))
            when (scope) {
                BranchScope.Local -> Unit
                BranchScope.Remote -> add("-r")
                BranchScope.All -> add("-a")
            }
        }
        return command(directory, "branch", readOnly = true, timeout = timeout, options = options, separator = false)
    }

    /** `remote`: the names of the configured remotes, one per line. */
    fun remoteList(directory: String, timeout: Duration? = LOCAL_TIMEOUT): GitCommand =
        command(directory, "remote", readOnly = true, timeout = timeout, separator = false)

    /** `rev-parse --show-toplevel`: the work tree root, or exit 128 outside a repository. */
    fun revParseToplevel(directory: String, timeout: Duration? = LOCAL_TIMEOUT): GitCommand =
        command(directory, "rev-parse", readOnly = true, timeout = timeout, options = listOf("--show-toplevel"), separator = false)

    /** `add -- paths`: stages changes, new files and deletions of these paths. */
    fun add(directory: String, paths: List<String>, timeout: Duration? = LOCAL_TIMEOUT): GitCommand =
        command(directory, "add", timeout = timeout, paths = nonEmpty(paths))

    /** `reset -q -- paths`: unstages these paths and keeps the work tree; works before the first commit. */
    fun unstage(directory: String, paths: List<String>, timeout: Duration? = LOCAL_TIMEOUT): GitCommand =
        command(directory, "reset", timeout = timeout, options = listOf("-q"), paths = nonEmpty(paths))

    /**
     * `checkout-index -f -u -- paths`: writes these files from the index over the work tree. Only
     * [GitRunner.discardWorktree] runs it, after its check: on its own, `-f` replaces a folder that
     * stands where a tracked file was, with everything in it.
     */
    internal fun discardWorktree(directory: String, paths: List<String>, timeout: Duration? = LOCAL_TIMEOUT): GitCommand =
        command(directory, "checkout-index", timeout = timeout, options = listOf("-f", "-u"), paths = nonEmpty(paths))

    /**
     * `git -C <directory>/<path> version`: succeeds exactly when [path] is a folder (or a link to
     * one) that git can enter, and fails when nothing or a file is there. Part of the check in
     * [GitRunner.discardWorktree].
     */
    internal fun directoryProbe(directory: String, path: String, timeout: Duration? = LOCAL_TIMEOUT): GitCommand {
        require(GitNames.isValidPath(path)) { "Invalid path" }
        return command(directory.trimEnd('/') + "/" + path, "version", readOnly = true, timeout = timeout, separator = false)
    }

    /**
     * `status --porcelain=v2 -z --no-renames --untracked-files=all --ignored=matching --`: every
     * tracked, untracked and ignored entry of the whole work tree, one record per path. The check
     * before [discardWorktree]; `--no-renames` pins rename detection off so each record names one
     * path. No pathspec: given a path, git hides a nested repository standing at it, and an entry at
     * an ancestor folder would fall outside a narrower scan.
     */
    internal fun discardCheck(directory: String, paths: List<String>, timeout: Duration? = LOCAL_TIMEOUT): GitCommand {
        nonEmpty(paths).forEach { require(GitNames.isValidPath(it)) { "Invalid path" } }
        return command(
            directory, "status", readOnly = true, timeout = timeout,
            options = listOf("--porcelain=v2", "-z", "--no-renames", "--untracked-files=all", "--ignored=matching"),
        )
    }

    /**
     * `git -C <directory>/<path> rev-parse --show-prefix`: inside this work tree's real folder
     * [path], prints `path/`; a symlink or a nested repository prints something else. Part of the
     * check in [GitRunner.discardWorktree].
     */
    internal fun directoryPrefix(directory: String, path: String, timeout: Duration? = LOCAL_TIMEOUT): GitCommand {
        require(GitNames.isValidPath(path)) { "Invalid path" }
        return command(
            directory.trimEnd('/') + "/" + path, "rev-parse", readOnly = true, timeout = timeout,
            options = listOf("--show-prefix"), separator = false,
        )
    }

    /**
     * `diff --no-index … -- /dev/null path`: [path] compared with an empty file, so a file git does
     * not track shows as new, every line added ([DiffOutput.Patch], with the same `a/` and `b/`
     * prefixes and full hashes as [diff]) or with its counts ([DiffOutput.NumStat], a rename-style
     * record `added\tdeleted\t\0/dev/null\0path\0`). Binary content is `Binary files … differ`, and
     * a symlink shows the path it points to, never the target's content.
     *
     * Git exits 1 when the two differ, which is the normal case, and also 1 on an error such as a
     * missing file. Run it only through [GitRunner.diffUntracked], which checks the path first:
     * `--no-index` reads any file the account can, and follows a symlinked folder out of the work
     * tree. It is public, read-only, so that callers' tests can recognise the command line.
     *
     * @param directory The work tree root; [path] is relative to it.
     * @param path Relative and inside [directory]; see [isWorkTreePath].
     */
    fun diffUntracked(
        directory: String,
        path: String,
        output: DiffOutput = DiffOutput.Patch,
        contextLines: Int = DEFAULT_CONTEXT_LINES,
        timeout: Duration? = LOCAL_TIMEOUT,
    ): GitCommand {
        require(contextLines >= 0) { "Context lines must not be negative" }
        require(isWorkTreePath(path)) { "Invalid path" }
        val options = buildList {
            add("--no-index")
            addAll(DIFF_OPTIONS)
            when (output) {
                DiffOutput.Patch -> addAll(STABLE_PATCH_NO_INDEX + "--unified=$contextLines")
                DiffOutput.NumStat -> addAll(listOf("--numstat", "-z"))
                DiffOutput.NameStatus -> addAll(listOf("--name-status", "-z"))
            }
        }
        return command(directory, "diff", readOnly = true, timeout = timeout, options = options, paths = listOf("/dev/null", path))
    }

    /**
     * `git -C <directory>/<folder of path> rev-parse --show-toplevel --show-prefix`: git resolves
     * the folder to its real place, so it prints [directory] and `folder/` exactly when the folder
     * is a real folder of this work tree, and something else (or fails) when a symlink on the way
     * leads elsewhere or a nested repository stands there. For a path at the root it runs in
     * [directory] and prints the root and an empty line. Part of [GitRunner.diffUntracked].
     */
    fun untrackedFolderCheck(directory: String, path: String, timeout: Duration? = LOCAL_TIMEOUT): GitCommand {
        require(isWorkTreePath(path)) { "Invalid path" }
        val folder = path.substringBeforeLast('/', "")
        val where = if (folder.isEmpty()) directory else directory.trimEnd('/') + "/" + folder
        return command(
            where, "rev-parse", readOnly = true, timeout = timeout,
            options = listOf("--show-toplevel", "--show-prefix"), separator = false,
        )
    }

    /**
     * True for a path git could list in its work tree: valid ([GitNames.isValidPath]), relative, and
     * made of components that are not empty, `.`, `..` or `.git` (in any case), so it cannot name
     * anything outside the folder it is relative to, nor the repository's own data.
     */
    fun isWorkTreePath(path: String): Boolean =
        GitNames.isValidPath(path) &&
            !path.startsWith('/') &&
            path.split('/').none { it.isEmpty() || it == "." || it == ".." || it.equals(".git", ignoreCase = true) }

    /**
     * `commit -q --file=-` with [message] as UTF-8 on stdin. Hooks run; a hook that refuses makes
     * a non-zero exit with its message on stderr.
     */
    fun commit(directory: String, message: String, timeout: Duration? = LOCAL_TIMEOUT): GitCommand {
        require(message.isNotBlank()) { "Commit message is blank" }
        require('\u0000' !in message) { "Commit message contains a NUL byte" }
        require(!GitNames.hasLoneSurrogate(message)) { "Commit message contains half a surrogate pair" }
        return command(
            directory, "commit", timeout = timeout, options = listOf("-q", "--file=-"),
            stdin = message.toByteArray(Charsets.UTF_8), separator = false,
        )
    }

    /**
     * `branch [--track|--no-track] name [startPoint]`: creates a branch without switching to it.
     *
     * @param track [BranchTracking.Track] makes [startPoint], which must be given and be a
     *   remote-tracking (or local) branch, the upstream even when `branch.autoSetupMerge` is false;
     *   git refuses a start point that is not a branch. The default leaves it to git's settings.
     */
    fun createBranch(
        directory: String,
        name: String,
        startPoint: String? = null,
        track: BranchTracking = BranchTracking.Default,
        timeout: Duration? = LOCAL_TIMEOUT,
    ): GitCommand {
        require(track != BranchTracking.Track || startPoint != null) { "Tracking needs a start point" }
        val options = buildList {
            when (track) {
                BranchTracking.Default -> Unit
                BranchTracking.Track -> add("--track")
                BranchTracking.NoTrack -> add("--no-track")
            }
            add(checkedBranch(name))
            startPoint?.let { add(checkedRevision(it)) }
        }
        return command(directory, "branch", timeout = timeout, options = options, separator = false)
    }

    /**
     * `switch -q --no-overwrite-ignore name`: switches to a local branch, or creates one that tracks
     * the remote branch of that name. Git refuses when local changes or ignored files would be
     * overwritten.
     */
    fun switchBranch(directory: String, name: String, timeout: Duration? = LOCAL_TIMEOUT): GitCommand =
        command(
            directory, "switch", timeout = timeout,
            options = listOf("-q", "--no-overwrite-ignore", checkedBranch(name)), separator = false,
        )

    /**
     * `branch -d name`, which git refuses for a branch that is not merged ("not fully merged"), or
     * with [allowUnmerged] `branch -D name`, which the caller only builds after the user confirmed.
     */
    fun deleteBranch(
        directory: String,
        name: String,
        allowUnmerged: Boolean = false,
        timeout: Duration? = LOCAL_TIMEOUT,
    ): GitCommand = command(
        directory, "branch", timeout = timeout,
        options = listOf(if (allowUnmerged) "-D" else "-d", checkedBranch(name)), separator = false,
    )

    /**
     * `fetch [--prune] [--multiple remote]`; without [remote], git's default remote. With
     * `--multiple`, git accepts only a configured remote or remote group and never reads the name
     * as a path or URL.
     */
    fun fetch(
        directory: String,
        remote: String? = null,
        prune: Boolean = false,
        timeout: Duration? = REMOTE_TIMEOUT,
    ): GitCommand {
        val options = buildList {
            if (prune) add("--prune")
            if (remote != null) {
                add("--multiple")
                add(checkedRemote(remote))
            }
        }
        return command(directory, "fetch", timeout = timeout, options = options, separator = false)
    }

    /**
     * `merge -q --ff-only --no-squash --no-overwrite-ignore --no-autostash revision`: the second
     * half of a pull, after [fetch]. It only fast-forwards, whatever `merge.ff` or
     * `branch.<name>.mergeOptions` say (options given here come after and win, so `--no-ff` and
     * `--squash` there cannot turn the fast-forward into a merge commit or staged changes), and it
     * never opens an editor; a diverged branch is an error. An ignored file where the incoming
     * commit has a file makes it fail instead of being overwritten, and `merge.autoStash` cannot
     * stash and reapply local changes.
     *
     * There is no `pull` builder: `git pull` rejects `--no-overwrite-ignore` (checked with git
     * 2.47), so a pull is `fetch` and then this.
     *
     * @param revision What to fast-forward to: `@{upstream}` for the branch's upstream, or
     *   `refs/remotes/<remote>/<branch>` after fetching that remote.
     */
    fun mergeFastForward(
        directory: String,
        revision: String = "@{upstream}",
        timeout: Duration? = LOCAL_TIMEOUT,
    ): GitCommand = command(
        directory, "merge", timeout = timeout,
        options = listOf("-q", "--ff-only", "--no-squash", "--no-overwrite-ignore", "--no-autostash", checkedRevision(revision)),
        separator = false,
    )

    /**
     * `push --no-follow-tags --no-recurse-submodules [--set-upstream] remote
     * refs/heads/branch:refs/heads/branch`. The explicit refspec has no `+`, so the remote refuses
     * anything but a fast-forward, and configured push refspecs or `push.default` cannot widen it.
     * `push.followTags` cannot add tags and `push.recurseSubmodules` cannot push other
     * repositories. There is deliberately no way to force. A [remote] that is not configured would
     * be read as a path inside [directory]: pass only names from [remoteList].
     */
    fun push(
        directory: String,
        remote: String,
        branch: String,
        setUpstream: Boolean = false,
        timeout: Duration? = REMOTE_TIMEOUT,
    ): GitCommand {
        val ref = "refs/heads/" + checkedBranch(branch)
        val options = buildList {
            addAll(listOf("--no-follow-tags", "--no-recurse-submodules"))
            if (setUpstream) add("--set-upstream")
            add(checkedRemote(remote))
            add("$ref:$ref")
        }
        return command(directory, "push", timeout = timeout, options = options, separator = false)
    }

    private const val DEFAULT_CONTEXT_LINES = 3

    private val DIFF_OPTIONS = listOf("--no-color", "--no-ext-diff", "--no-textconv")

    /**
     * Pinned so that the user's `diff.relative`, `diff.renames`, `diff.noPrefix`,
     * `diff.mnemonicPrefix` and `diff.submodule` cannot change what the parsers read: paths from the
     * repository root, renames detected, `a/` and `b/` prefixes, full object hashes in `index`
     * lines, and submodules as `Subproject commit` lines rather than summaries.
     */
    private val STABLE_DIFF = listOf("--no-relative", "--find-renames")
    private val STABLE_PATCH = listOf("--src-prefix=a/", "--dst-prefix=b/", "--full-index", "--submodule=short")

    /** Without the index there are no renames, submodules or relative paths to pin. */
    private val STABLE_PATCH_NO_INDEX = listOf("--src-prefix=a/", "--dst-prefix=b/", "--full-index")

    private fun diffOutput(output: DiffOutput, contextLines: Int): List<String> = when (output) {
        DiffOutput.Patch -> STABLE_DIFF + STABLE_PATCH + "--unified=$contextLines"
        DiffOutput.NumStat -> STABLE_DIFF + listOf("--numstat", "-z")
        DiffOutput.NameStatus -> STABLE_DIFF + listOf("--name-status", "-z")
    }

    /** One word, `--format=…`, so the format can never be read as an option of its own. */
    private fun formatOption(format: String): String {
        require('\u0000' !in format && !GitNames.hasLoneSurrogate(format)) { "Format is not representable" }
        return "--format=$format"
    }

    private fun checkedBranch(name: String): String {
        require(GitNames.isValidBranchName(name)) { "Invalid branch name" }
        return name
    }

    private fun checkedRemote(name: String): String {
        require(GitNames.isValidRemoteName(name)) { "Invalid remote name" }
        return name
    }

    private fun checkedRevision(revision: String): String {
        require(GitNames.isValidRevision(revision)) { "Invalid revision" }
        return revision
    }

    private fun nonEmpty(paths: List<String>): List<String> {
        require(paths.isNotEmpty()) { "No paths given" }
        return paths
    }

    private fun command(
        directory: String,
        subcommand: String,
        readOnly: Boolean = false,
        timeout: Duration?,
        options: List<String> = emptyList(),
        paths: List<String> = emptyList(),
        stdin: ByteArray? = null,
        separator: Boolean = true,
    ): GitCommand {
        require(timeout == null || timeout.isPositive()) { "Timeout must be positive" }
        require(directory.startsWith('/')) { "Directory must be an absolute path" }
        require(GitNames.isValidPath(directory)) { "Directory is not representable" }
        paths.forEach { require(GitNames.isValidPath(it)) { "Invalid path" } }
        require(separator || paths.isEmpty()) { "Paths need the separator" }
        val arguments = if (separator) options + "--" + paths else options
        return GitCommand.git(directory, subcommand, arguments, readOnly, stdin, timeout)
    }
}
