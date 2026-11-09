package io.github.thewisekd.phopencode.core.remote.notify

import io.github.thewisekd.phopencode.core.ssh.quote.ShellQuote

/**
 * The command lines the installer runs. Fixed `sh` text, run as `sh -c <text> <name> [args]`, so it
 * is POSIX `sh` whatever the login shell is; every variable part, always a path, passes through
 * [ShellQuote] as an argument. None takes or prints a secret, and none stops, signals or kills a
 * process, uses `sudo` or touches the system: the scripts in `helper/` do the service work, inside
 * the login user's HOME.
 */
internal object NotifyCommands {
    /** `$0` of the `sh -c` scripts, as the server's process list shows it. */
    private const val NAME = "phopencode-notify"

    /** The private folder's name before `mktemp`'s random part. */
    private const val TEMP_PREFIX = "phopencode-notify."

    /** The temporary config of [sendTest], in the private folder. */
    const val SEND_TEST_CONFIG = "send-test.json"

    /** The config folder and file below the config base, as `helper/notifyd/config.py` has them. */
    const val CONFIG_DIRECTORY = "phopencode"
    const val CONFIG_FILE = "notifyd.json"

    /**
     * Prints `python <major>.<minor>`, `python missing` (no `python3` on `PATH`) or `python broken`
     * (it does not run), then `config <base>`: `$XDG_CONFIG_HOME` when absolute, else
     * `$HOME/.config`, the rule of `install.sh` and `config.py`; an empty base when neither works.
     */
    private val PROBE_SCRIPT = """
        if command -v python3 >/dev/null 2>&1; then
          v=${'$'}(python3 -c 'import sys; print("%d.%d" % sys.version_info[:2])' 2>/dev/null </dev/null) || v=
          printf 'python %s\n' "${'$'}{v:-broken}"
        else
          printf 'python missing\n'
        fi
        case "${'$'}{XDG_CONFIG_HOME:-}" in
          /*) b=${'$'}XDG_CONFIG_HOME ;;
          *) case "${'$'}{HOME:-}" in /*) b=${'$'}HOME/.config ;; *) b= ;; esac ;;
        esac
        printf 'config %s\n' "${'$'}b"
    """.trimIndent()

    /**
     * The base the private folder goes in, as `t`: `$TMPDIR` when absolute, else `/tmp`. A base
     * [parseTemp] would refuse (empty or `/`, a character outside `install.sh`'s set, or an empty,
     * `.` or `..` segment) exits 3 before anything is made. Then `$1` must be a name of
     * [newTempName]'s shape, or the script exits 2. [makeTemp] and [removeTemp] share it, so both
     * reach the same folder.
     */
    private val TEMP_BASE = """
        LC_ALL=C
        case "${'$'}{TMPDIR:-}" in /*) t=${'$'}{TMPDIR%/} ;; *) t=/tmp ;; esac
        case ${'$'}t in ''|*[!A-Za-z0-9._/+@-]*|*//*|*/./*|*/../*|*/.|*/..) exit 3 ;; esac
        case ${'$'}1 in $TEMP_PREFIX*) ;; *) exit 2 ;; esac
        case ${'$'}{1#$TEMP_PREFIX} in ''|*[!A-Za-z0-9]*) exit 2 ;; esac
    """.trimIndent()

    /**
     * Makes the folder `<base>/$1`, mode 0700, with `mkdir -m 700` and no `-p`: it fails, exit 4,
     * when the name exists already, so a planted folder or link is never used. Prints its path.
     */
    private val MAKE_TEMP_SCRIPT = TEMP_BASE + "\n" + """

        mkdir -m 700 -- "${'$'}t/${'$'}1" || exit 4
        printf '%s\n' "${'$'}t/${'$'}1"
    """.trimIndent()

    /** Removes exactly `<base>/$1`, the folder [makeTemp] made for the same name. */
    private val REMOVE_TEMP_SCRIPT = TEMP_BASE + "\n" + """

        rm -rf -- "${'$'}t/${'$'}1"
    """.trimIndent()

    /** [makeTemp]'s exit when the name exists already: nothing was made, so nothing is removed. */
    const val TEMP_EXISTS = 4

    /** Makes folder `$1`, and missing parents, mode 0700, and sets an existing one to 0700. */
    private const val CONFIG_DIR_SCRIPT = "umask 077 && mkdir -p -- \"\$1\" && chmod 700 -- \"\$1\""

    val PROBE: String = ShellQuote.join(listOf("sh", "-c", PROBE_SCRIPT, NAME))

    /** Makes the private folder [name] (from [newTempName]); see [MAKE_TEMP_SCRIPT]. */
    fun makeTemp(name: String): String = ShellQuote.join(listOf("sh", "-c", MAKE_TEMP_SCRIPT, NAME, name))

    fun makeConfigDir(directory: String): String = ShellQuote.join(listOf("sh", "-c", CONFIG_DIR_SCRIPT, NAME, directory))

    fun status(temp: String): String = ShellQuote.join(listOf("sh", file(temp, NotifyHelperFile.Install), "--status"))

    fun install(temp: String): String =
        ShellQuote.join(listOf("sh", file(temp, NotifyHelperFile.Install), file(temp, NotifyHelperFile.Service)))

    fun uninstall(temp: String): String = ShellQuote.join(listOf("sh", file(temp, NotifyHelperFile.Uninstall)))

    fun sendTest(temp: String): String = ShellQuote.join(
        listOf("python3", file(temp, NotifyHelperFile.Service), "--send-test", "--config", "$temp/$SEND_TEST_CONFIG"),
    )

    /** Removes the private folder [name], found the way [makeTemp] found it; see [REMOVE_TEMP_SCRIPT]. */
    fun removeTemp(name: String): String = ShellQuote.join(listOf("sh", "-c", REMOVE_TEMP_SCRIPT, NAME, name))

    /** Random characters after [TEMP_PREFIX]: 16 of 62, about 95 bits. */
    private const val TEMP_RANDOM_LENGTH = 16
    private const val TEMP_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

    /** A new, unpredictable private folder name, made on the phone so it is known before the folder exists. */
    fun newTempName(random: java.util.Random): String =
        TEMP_PREFIX + String(CharArray(TEMP_RANDOM_LENGTH) { TEMP_ALPHABET[random.nextInt(TEMP_ALPHABET.length)] })

    fun file(temp: String, file: NotifyHelperFile): String = "$temp/${file.fileName}"

    /**
     * What [PROBE] found. [python] is null when there is no `python3` that runs; [configBase] is
     * null when it is not a path `install.sh` takes.
     */
    class Probe(val python: PythonVersion?, val configBase: String?)

    data class PythonVersion(val major: Int, val minor: Int) : Comparable<PythonVersion> {
        override fun compareTo(other: PythonVersion): Int = compareValuesBy(this, other, { it.major }, { it.minor })
    }

    /** The oldest `python3` notifyd runs on. */
    val MIN_PYTHON = PythonVersion(3, 9)

    private val PYTHON_LINE = Regex("""python (missing|broken|(\d{1,4})\.(\d{1,4}))""")
    private val CONFIG_LINE = Regex("""config (.*)""")

    /** Characters `install.sh` allows in its paths. */
    private val SAFE_PATH = Regex("""/[A-Za-z0-9._/+@-]*""")

    /** Null when [stdout] lacks either line. */
    fun parseProbe(stdout: String): Probe? {
        val lines = stdout.lines()
        val python = lines.lastOrNull(PYTHON_LINE::matches)?.let(PYTHON_LINE::matchEntire) ?: return null
        val base = lines.lastOrNull(CONFIG_LINE::matches)?.let(CONFIG_LINE::matchEntire)?.groupValues?.get(1) ?: return null
        val version = python.groups[2]?.let { major ->
            PythonVersion(major.value.toInt(), python.groupValues[3].toInt())
        }
        return Probe(version, base.trimEnd('/').takeIf(::isPlainAbsolute))
    }

    private val TEMP_NAME = Regex(Regex.escape(TEMP_PREFIX) + "[A-Za-z0-9]{8,}")

    /**
     * The folder [makeTemp] printed for [name], its last line, or null unless it is a plain
     * absolute path, below a base that is not `/`, whose last segment is [name]: no `.` or `..`
     * segment, no empty one, nothing `install.sh` refuses.
     */
    fun parseTemp(stdout: String, name: String): String? {
        if (!TEMP_NAME.matches(name)) return null
        val path = stdout.lines().lastOrNull { it.isNotEmpty() } ?: return null
        if (!isPlainAbsolute(path)) return null
        return path.takeIf { it.substringAfterLast('/') == name && it.substringBeforeLast('/').isNotEmpty() }
    }

    /** Absolute, only `install.sh`'s characters, and no empty, `.` or `..` segment. */
    private fun isPlainAbsolute(path: String): Boolean {
        if (!SAFE_PATH.matches(path) || path == "/") return false
        val segments = path.removePrefix("/").removeSuffix("/").split('/')
        return segments.none { it.isEmpty() || it == "." || it == ".." }
    }
}
