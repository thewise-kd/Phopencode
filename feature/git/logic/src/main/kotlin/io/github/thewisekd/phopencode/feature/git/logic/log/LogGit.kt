package io.github.thewisekd.phopencode.feature.git.logic.log

import io.github.thewisekd.phopencode.core.git.command.BranchScope
import io.github.thewisekd.phopencode.core.git.command.GitCommand
import io.github.thewisekd.phopencode.core.git.command.GitCommands
import io.github.thewisekd.phopencode.core.git.command.GitResult
import io.github.thewisekd.phopencode.core.git.command.GitRunner
import io.github.thewisekd.phopencode.core.git.command.ShowOutput
import io.github.thewisekd.phopencode.core.git.parse.BranchParser
import io.github.thewisekd.phopencode.core.git.parse.GitParseException
import io.github.thewisekd.phopencode.core.git.parse.LogParser
import io.github.thewisekd.phopencode.core.ssh.api.SshException
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitConnection
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitException
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitFailure
import io.github.thewisekd.phopencode.feature.git.logic.contract.toGitException
import kotlin.coroutines.cancellation.CancellationException
import io.github.thewisekd.phopencode.core.git.command.GitException as CommandException

/**
 * The command lines the history screens run. Every one is read-only and built by `core/git`, so
 * every word is quoted and revisions come before `--`.
 */
internal object LogCommands {
    private const val FORMAT_OPTION = "--format="

    /** [LogParser.FORMAT] without its option name: [GitCommands] adds `--format=` itself. */
    val LOG_FORMAT: String = LogParser.FORMAT.removePrefix(FORMAT_OPTION)

    /** [BranchParser.FORMAT] without its option name. */
    val BRANCH_FORMAT: String = BranchParser.FORMAT.removePrefix(FORMAT_OPTION)

    /** `log -z` of [count] commits from [revision] (null is `HEAD`), after [skip] commits. */
    fun page(directory: String, revision: String?, skip: Int, count: Int): GitCommand =
        GitCommands.log(directory, LOG_FORMAT, maxCount = count, skip = skip, revision = revision)

    /** Every local and remote branch. */
    fun branches(directory: String): GitCommand = GitCommands.branchList(directory, BRANCH_FORMAT, BranchScope.All)

    /** The commit [hash] alone, peeled so that a tree, blob or tag hash is refused by git. */
    fun commit(directory: String, hash: String): GitCommand =
        GitCommands.log(directory, LOG_FORMAT, maxCount = 1, revision = peeled(hash))

    /** `show --numstat -z` of [hash] against its first parent, without a header. */
    fun numStat(directory: String, hash: String): GitCommand =
        GitCommands.show(directory, peeled(hash), output = ShowOutput.NumStat, firstParent = true)

    /**
     * `show --name-status -z` of [hash] against its first parent, without a header. Without the
     * first-parent option a merge would print only its combined diff here.
     */
    fun nameStatus(directory: String, hash: String): GitCommand =
        GitCommands.show(directory, peeled(hash), output = ShowOutput.NameStatus, firstParent = true)

    private fun peeled(hash: String): String {
        require(isFullHash(hash)) { "Invalid commit hash" }
        return "$hash^{commit}"
    }
}

private val FULL_HASH = Regex("[0-9a-f]{40}|[0-9a-f]{64}")

/** A full SHA-1 or SHA-256 object name in lowercase, as `log` prints it. */
internal fun isFullHash(value: String): Boolean = FULL_HASH.matches(value)

/** Runs commands and turns `core/git` failures into [GitException]s. */
internal class GitSession(private val runner: GitRunner) {
    /** Runs [command]; a non-zero exit is a result, as with [GitRunner.run]. */
    suspend fun run(command: GitCommand): GitResult = try {
        runner.run(command)
    } catch (timedOut: CommandException.TimedOut) {
        throw GitException(GitFailure.TimedOut)
    } catch (tooLarge: CommandException.OutputTooLarge) {
        throw GitException(GitFailure.OutputTooLarge)
    } catch (lost: CommandException.ConnectionLost) {
        val cause = lost.cause
        throw GitException(if (cause is SshException) GitFailure.of(cause) else GitFailure.NotConnected)
    }

    /** Runs [command] and gives its stdout, or throws the failure its exit means. */
    suspend fun read(command: GitCommand): ByteArray {
        val result = run(command)
        if (!result.succeeded) throw GitException(failureOf(result))
        return result.stdout
    }
}

/**
 * Runs [block] with a [GitSession] on [this] connection. Parse errors become [GitFailure.Unknown];
 * cancellation passes unchanged; every other failure is a [GitException].
 */
internal suspend fun <T> GitConnection.session(block: suspend (GitSession) -> T): T = try {
    withExec { exec ->
        try {
            block(GitSession(GitRunner(exec)))
        } catch (malformed: GitParseException) {
            throw GitException(GitFailure.Unknown)
        }
    }
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    throw failure.toGitException()
}

/** Builds a command; input `core/git` refuses is [GitFailure.Rejected]. */
internal inline fun <T> checkedInput(build: () -> T): T = try {
    build()
} catch (refused: IllegalArgumentException) {
    throw GitException(GitFailure.Rejected)
}

/** The failure a caught exception stands for; never call it on a cancellation. */
internal fun failureOf(error: Exception): GitFailure = error.toGitException().failure

private const val COMMAND_NOT_FOUND = 127

/** Messages git prints with `LC_ALL=C`, which the commands set. */
private val NOT_REPOSITORY = listOf("not a git repository", "cannot change to")
private val NOT_FOUND = listOf("bad revision", "unknown revision", "bad object", "invalid object name")
private const val NO_COMMITS = "does not have any commits yet"

/** What a non-zero exit of a read-only history command means. */
internal fun failureOf(result: GitResult): GitFailure {
    if (result.exitCode == COMMAND_NOT_FOUND) return GitFailure.GitMissing
    val stderr = result.stderrText()
    return when {
        NOT_REPOSITORY.any { it in stderr } -> GitFailure.NotRepository
        NOT_FOUND.any { it in stderr } -> GitFailure.NotFound
        else -> GitFailure.GitError(result.exitCode)
    }
}

/** True when `log` of `HEAD` failed only because the current branch has no commits yet. */
internal fun isUnbornHead(result: GitResult): Boolean = !result.succeeded && NO_COMMITS in result.stderrText()
