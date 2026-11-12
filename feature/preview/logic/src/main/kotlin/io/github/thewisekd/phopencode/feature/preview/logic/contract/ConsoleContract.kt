package io.github.thewisekd.phopencode.feature.preview.logic.contract

/** A console message's level, as the WebView reports it; load errors and refused requests are [Error]. */
enum class ConsoleLevel {
    Debug,
    Log,
    Info,
    Warning,
    Error,
}

/** Where a [ConsoleEntry] came from. */
enum class ConsoleKind {
    /** The page's own `console.*` call. */
    Console,

    /** The main frame or a sub-resource failed to load. */
    LoadError,

    /** The main frame or a sub-resource answered with an HTTP error status. */
    HttpError,

    /** The page asked for an address off the preview origin, and it was refused. */
    RefusedRequest,
}

/**
 * One line of the preview's console (P13.12). The page's own text: kept in memory only, never
 * logged or written to disk, and dropped when the preview closes; it prints masked.
 *
 * @property source The script or address it came from, if any.
 * @property line The line in [source], if known.
 */
data class ConsoleEntry(
    val kind: ConsoleKind,
    val level: ConsoleLevel,
    val text: String,
    val source: String?,
    val line: Int?,
) {
    override fun toString(): String =
        "ConsoleEntry(kind=$kind, level=$level, text=***, source=${if (source == null) "null" else "***"}, line=$line)"
}

/**
 * The console of one open preview.
 *
 * @property entries Oldest first; at most [MAX_ENTRIES], the oldest dropped first, each text cut at
 *   [MAX_TEXT_LENGTH] characters.
 * @property filter Shows only entries of this level; null shows all.
 */
data class ConsoleState(
    val entries: List<ConsoleEntry> = emptyList(),
    val filter: ConsoleLevel? = null,
) {
    /** For the toolbar badge. */
    val errorCount: Int
        get() = entries.count { it.level == ConsoleLevel.Error }

    /** What the panel lists under [filter]. */
    val shown: List<ConsoleEntry>
        get() = if (filter == null) entries else entries.filter { it.level == filter }

    override fun toString(): String = "ConsoleState(entries=${entries.size}, filter=$filter)"

    companion object {
        const val MAX_ENTRIES = 500

        /** 4 KiB of text. */
        const val MAX_TEXT_LENGTH = 4 * 1024
    }
}
