package io.github.thewisekd.phopencode.core.git.command

import io.github.thewisekd.phopencode.core.ssh.api.ExecRequest
import io.github.thewisekd.phopencode.core.ssh.quote.ShellQuote
import kotlin.time.Duration

/**
 * One git invocation, ready to run on the server through [GitRunner]. Build it with [GitCommands];
 * the constructor is private so that only checked commands exist.
 *
 * The command line has the form `env VAR=value… git <global options> -C <directory> <arguments>`,
 * and every word of it, constants included, passes through [ShellQuote]. `env` sets the variables
 * in every POSIX login shell the same way, and makes the line one simple command.
 *
 * It does not print its directory, paths or message: [toString] shows the git subcommand only.
 *
 * Every command is git: nothing here builds `rm`, `clean` or any other program.
 *
 * @property readOnly True for commands that only read, which run with `GIT_OPTIONAL_LOCKS=0` so they
 *   never take the index lock from a git process the user runs at the same time.
 * @property timeout Limit for the whole command; null means none, which only makes sense for a
 *   caller that cancels by itself.
 */
class GitCommand private constructor(
    /** Every word of the command line, unquoted. */
    internal val argv: List<String>,
    private val subcommand: String,
    val readOnly: Boolean,
    stdin: ByteArray?,
    val timeout: Duration?,
) {
    internal companion object {
        /** `env <variables> git <global options> -C <directory> <subcommand> <arguments>`. */
        fun git(
            directory: String,
            subcommand: String,
            gitArguments: List<String>,
            readOnly: Boolean,
            stdin: ByteArray?,
            timeout: Duration?,
        ): GitCommand {
            val argv = buildList {
                add("env")
                GitEnvironment.common.forEach { (key, value) -> add("$key=$value") }
                if (readOnly) GitEnvironment.readOnly.forEach { (key, value) -> add("$key=$value") }
                add("git")
                addAll(GitEnvironment.globalOptions)
                add("-C")
                add(directory)
                add(subcommand)
                addAll(gitArguments)
            }
            return GitCommand(argv, subcommand, readOnly, stdin, timeout)
        }
    }

    /** The command line for [ExecRequest.command]. Visible in the server's process list. */
    val commandLine: String = ShellQuote.join(argv)

    private val stdinBytes: ByteArray? = stdin?.copyOf()

    /** True when the command reads stdin; only `commit` does, for its message. */
    val hasStdin: Boolean get() = stdinBytes != null

    /**
     * A request for [io.github.thewisekd.phopencode.core.ssh.api.RemoteExec.run]. Its stdin is a
     * fresh copy, so the caller may wipe it after the run; null stdin closes the process's input at
     * once, so a command that would prompt sees end of input.
     */
    fun toExecRequest(): ExecRequest = ExecRequest(commandLine, stdinBytes?.copyOf(), timeout)

    override fun toString(): String = "GitCommand($subcommand, ***)"
}

/** The fixed environment and global options of every command. */
internal object GitEnvironment {
    /**
     * Set for every command. Together with stdin closed and no terminal, nothing can wait for input:
     * - `GIT_TERMINAL_PROMPT=0`: git does not ask for a user name or password on the terminal.
     * - `GIT_ASKPASS`, `SSH_ASKPASS` to `false` and `SSH_ASKPASS_REQUIRE=never`: no helper program
     *   prompts either; a credential git cannot find becomes an error.
     * - `GIT_EDITOR`, `GIT_SEQUENCE_EDITOR` to `false` and `GIT_MERGE_AUTOEDIT=no`: no editor opens.
     * - `GIT_PAGER`, `PAGER` to `cat`: no pager waits for a key.
     * - `LC_ALL=C`: messages in English, so callers can recognise them.
     *
     * `GIT_SSH_COMMAND` is deliberately left alone: it would replace the user's `core.sshCommand`.
     * OpenSSH without a terminal and without askpass cannot prompt and fails instead.
     */
    val common: List<Pair<String, String>> = listOf(
        "GIT_TERMINAL_PROMPT" to "0",
        "GIT_ASKPASS" to "false",
        "SSH_ASKPASS" to "false",
        "SSH_ASKPASS_REQUIRE" to "never",
        "GIT_EDITOR" to "false",
        "GIT_SEQUENCE_EDITOR" to "false",
        "GIT_MERGE_AUTOEDIT" to "no",
        "GIT_PAGER" to "cat",
        "PAGER" to "cat",
        "LC_ALL" to "C",
    )

    /** Added for read-only commands. */
    val readOnly: List<Pair<String, String>> = listOf("GIT_OPTIONAL_LOCKS" to "0")

    /**
     * Before the subcommand: no pager; every pathspec literal, so a file called `*.txt` or
     * `:(top)x` matches only itself; paths printed raw rather than C-quoted; no colour; no
     * filesystem monitor hook; no interactive credential manager; no signature checks in `log`.
     */
    val globalOptions: List<String> = listOf(
        "--no-pager",
        "--literal-pathspecs",
        "-c", "core.quotePath=false",
        "-c", "color.ui=false",
        "-c", "core.fsmonitor=false",
        "-c", "credential.interactive=never",
        "-c", "log.showSignature=false",
    )
}
