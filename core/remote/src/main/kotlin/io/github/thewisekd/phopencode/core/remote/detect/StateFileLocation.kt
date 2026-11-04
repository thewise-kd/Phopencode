package io.github.thewisekd.phopencode.core.remote.detect

/**
 * Where the state file lives, relative to the login user's XDG state folder: `$XDG_STATE_HOME`
 * when that is set to an absolute path, else `~/.local/state`. Plain names separated by `/`, no `.`
 * or `..`, nothing absolute. It reaches the shell only through `ShellQuote`. The file's format is
 * [io.github.thewisekd.phopencode.core.remote.state.ServerState].
 */
data class StateFileLocation(val relativePath: String) {
    init {
        require(relativePath.length in 1..MAX_LENGTH) { "State file path is empty or too long" }
        require(!relativePath.startsWith('/')) { "State file path must be relative to the state folder" }
        require(relativePath.none { it < ' ' || it == '\u007F' }) { "State file path contains a control character" }
        require(relativePath.split('/').none { it.isEmpty() || it == "." || it == ".." }) {
            "State file path must consist of plain names"
        }
    }

    companion object {
        private const val MAX_LENGTH = 1024

        /** `phopencode/server.state` in the XDG state folder, where server startup writes it. */
        val DEFAULT: StateFileLocation = StateFileLocation("phopencode/server.state")
    }
}
