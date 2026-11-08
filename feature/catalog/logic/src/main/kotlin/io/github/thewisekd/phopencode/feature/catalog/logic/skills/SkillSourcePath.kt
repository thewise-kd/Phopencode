package io.github.thewisekd.phopencode.feature.catalog.logic.skills

/**
 * Where a skill's file is, as the detail screen shows it: [path] with a user's home folder written
 * as `~`, so that the line is shorter and does not show the account's name. Home folders are
 * `/home/<user>`, `/Users/<user>`, `/root` and `<drive>:\Users\<user>`; any other path, a project's
 * own skill for example, stays as it is. Surrounding blanks go; a blank path is none.
 */
internal fun skillSourcePath(path: String): String? {
    val trimmed = path.trim()
    if (trimmed.isEmpty()) return null
    val home = UNIX_HOME.find(trimmed) ?: WINDOWS_HOME.find(trimmed) ?: return trimmed
    return "~" + trimmed.substring(home.range.last + 1)
}

/** `/root`, or `/home/<user>` or `/Users/<user>`, ending there or at a separator. */
private val UNIX_HOME = Regex("""^(?:/root|/(?:home|Users)/[^/]+)(?=/|$)""")

/** `C:\Users\<user>`, ending there or at a separator. */
private val WINDOWS_HOME = Regex("""^[A-Za-z]:\\Users\\[^\\]+(?=\\|$)""")
