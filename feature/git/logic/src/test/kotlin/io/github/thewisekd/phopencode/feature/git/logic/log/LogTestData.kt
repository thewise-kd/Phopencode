package io.github.thewisekd.phopencode.feature.git.logic.log

import io.github.thewisekd.phopencode.core.testing.ssh.FakeRemoteExec

internal const val DIR = "/srv/çalışma alanı"

/** A full hash made from [n], so that tests can tell commits apart. */
internal fun hash(n: Int): String = n.toString(16).padStart(40, '0')

/** One `log -z` record in [LogCommands.LOG_FORMAT]. */
internal fun record(
    hash: String,
    parents: List<String> = emptyList(),
    subject: String = "commit $hash",
    body: String = "",
    authorName: String = "Ayşe Yılmaz",
    authorEmail: String = "ayse@example.com",
    authorTime: Long = 1_700_000_000,
    committerName: String = "Çağrı Öztürk",
    committerEmail: String = "cagri@example.com",
    commitTime: Long = 1_700_000_100,
): String = listOf(
    hash, parents.joinToString(" "), authorName, authorEmail, authorTime.toString(),
    committerName, committerEmail, commitTime.toString(), subject, body,
).joinToString("\u0000") + "\u0000"

/** Records for [range], each the child of the next, newest first. */
internal fun records(range: IntProgression): String = range.joinToString("") { record(hash(it), parents = listOf(hash(it - 1))) }

/** One line of `branch --list` in [LogCommands.BRANCH_FORMAT]. */
internal fun branchLine(refName: String, hash: String, current: Boolean = false, symref: String = ""): String =
    listOf(refName, if (current) "*" else " ", hash, "", "", symref).joinToString("\u0000") + "\n"

internal fun FakeRemoteExec.onOutput(command: String, stdout: String, exit: Int = 0, stderr: String = "") {
    on(command) {
        if (stdout.isNotEmpty()) stdout(stdout)
        if (stderr.isNotEmpty()) stderr(stderr)
        exit(exit)
    }
}

internal fun FakeRemoteExec.commands(): List<String> = executions.map { it.command }
