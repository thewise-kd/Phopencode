package io.github.thewisekd.phopencode.feature.git.logic.branch

import io.github.thewisekd.phopencode.core.git.command.BranchScope
import io.github.thewisekd.phopencode.core.git.command.BranchTracking
import io.github.thewisekd.phopencode.core.git.command.GitCommands
import io.github.thewisekd.phopencode.core.git.parse.BranchParser
import io.github.thewisekd.phopencode.core.testing.ssh.FakeRemoteExec
import java.io.ByteArrayOutputStream

internal const val DIR = "/srv/çalışma alanı"

/** A full hash made from [n]. */
internal fun hash(n: Int): String = n.toString(16).padStart(40, '0')

internal val BRANCH_LIST: String =
    GitCommands.branchList(DIR, BranchParser.FORMAT.removePrefix("--format="), BranchScope.All).commandLine

internal val STATUS: String = GitCommands.status(DIR, includeUntracked = false).commandLine

internal fun create(name: String, startPoint: String? = null): String = GitCommands.createBranch(DIR, name, startPoint).commandLine

/** What a remote branch tap runs: `branch --track name refs/remotes/…`. */
internal fun createTracking(name: String, remoteRef: String): String =
    GitCommands.createBranch(DIR, name, remoteRef, track = BranchTracking.Track).commandLine

internal fun switch(name: String): String = GitCommands.switchBranch(DIR, name).commandLine

internal fun delete(name: String, unmerged: Boolean = false): String = GitCommands.deleteBranch(DIR, name, unmerged).commandLine

/** One line of `branch --list` in [BranchParser.FORMAT]. */
internal fun branchLine(
    refName: String,
    hash: String,
    current: Boolean = false,
    upstream: String = "",
    track: String = "",
    symref: String = "",
): String = listOf(refName, if (current) "*" else " ", hash, upstream, track, symref).joinToString("\u0000") + "\n"

/** `status --porcelain=v2 -z --branch` output; [changes] are tracked files changed in the work tree. */
internal fun statusOutput(head: String? = "main", oid: String? = hash(1), changes: List<String> = emptyList()): ByteArray =
    ByteArrayOutputStream().apply {
        record("# branch.oid ${oid ?: "(initial)"}")
        record("# branch.head ${head ?: "(detached)"}")
        changes.forEach { record("1 .M N... 100644 100644 100644 ${hash(2)} ${hash(2)} $it") }
    }.toByteArray()

private fun ByteArrayOutputStream.record(text: String) {
    write(text.toByteArray(Charsets.UTF_8))
    write(0)
}

internal fun FakeRemoteExec.commands(): List<String> = executions.map { it.command }

/** Runs a command that prints [stderr] and exits with [exit]. */
internal fun FakeRemoteExec.fails(command: String, stderr: String, exit: Int = 1) {
    on(command) {
        stderr(stderr)
        exit(exit)
    }
}

internal fun FakeRemoteExec.succeeds(command: String) {
    on(command) { exit(0) }
}
