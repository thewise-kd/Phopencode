package io.github.thewisekd.phopencode.feature.files.logic.ops

import io.github.thewisekd.phopencode.feature.files.logic.contract.NameProblem

/**
 * The check every typed name passes before a rename or a new folder is sent. A name is one path
 * segment: it can never point anywhere but into the folder it is created in.
 *
 * The name is checked trimmed, as it is sent. In order, a name is refused when it is:
 * - empty or only whitespace ([NameProblem.Blank]);
 * - `.` or `..` ([NameProblem.Reserved]);
 * - holding `/` ([NameProblem.Separator]);
 * - holding NUL or any other ISO control character, C0, DEL or C1 ([NameProblem.ControlCharacter]);
 * - longer than [MAX_NAME_BYTES] bytes in UTF-8 ([NameProblem.TooLong]).
 *
 * [NameProblem.Taken] is never decided here: only the server knows it.
 */
internal object FileNames {
    /** `NAME_MAX` of Linux and of most file systems a server runs on, in bytes. */
    const val MAX_NAME_BYTES = 255

    fun problem(name: String): NameProblem? {
        val trimmed = name.trim()
        return when {
            trimmed.isEmpty() -> NameProblem.Blank
            trimmed == "." || trimmed == ".." -> NameProblem.Reserved
            '/' in trimmed -> NameProblem.Separator
            trimmed.any(Char::isISOControl) -> NameProblem.ControlCharacter
            trimmed.toByteArray(Charsets.UTF_8).size > MAX_NAME_BYTES -> NameProblem.TooLong
            else -> null
        }
    }
}
