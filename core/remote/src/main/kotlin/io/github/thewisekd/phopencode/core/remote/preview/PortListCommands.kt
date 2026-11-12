package io.github.thewisekd.phopencode.core.remote.preview

/**
 * The shell text the port listing runs, for POSIX `sh`. Both commands are fixed: they take no
 * value from the app or the user, read only, and never use `sudo` or signal a process.
 */
internal object PortListCommands {
    /** Listening TCP sockets, numeric, without the header line. */
    const val SS: String = "ss -ltnH"

    /** The exit status of [PROC_NET] when neither table can be read. */
    const val NO_TABLES: Int = 3

    /**
     * The fallback when [SS] does not work: prints `E <order>`, the server's byte order as
     * `od -An -tx2` shows the bytes `01 00` (`0001` little-endian, `0100` big-endian, empty when
     * `od` fails), then `4 <hex address>:<hex port>` for each listening row (state `0A`) of
     * `/proc/net/tcp` and `6 …` for `/proc/net/tcp6`. Exits [NO_TABLES] when neither table is
     * readable, non-zero when reading one fails.
     */
    val PROC_NET: String = procNetScript("/proc/net/tcp", "/proc/net/tcp6")

    /** [PROC_NET] on other table files, for tests. The paths are fixed text, never quoted input. */
    internal fun procNetScript(tcp: String, tcp6: String): String = """
        r=''
        e=${'$'}(printf '\001\000' | od -An -tx2 2>/dev/null | tr -d ' \n')
        printf 'E %s\n' "${'$'}e"
        if [ -r $tcp ]; then r=1; awk '${'$'}4 == "0A" { print "4 " ${'$'}2 }' $tcp || exit 1; fi
        if [ -r $tcp6 ]; then r=1; awk '${'$'}4 == "0A" { print "6 " ${'$'}2 }' $tcp6 || exit 1; fi
        [ -n "${'$'}r" ] || exit $NO_TABLES
    """.trimIndent()
}
