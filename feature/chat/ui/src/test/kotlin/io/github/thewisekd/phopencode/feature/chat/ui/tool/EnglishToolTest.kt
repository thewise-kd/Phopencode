package io.github.thewisekd.phopencode.feature.chat.ui.tool

import io.github.thewisekd.phopencode.feature.chat.logic.contract.ToolCardStatus
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Level 36 has no arm64 Linux runtime, so the Pi runs 26 only; CI runs both. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class EnglishToolTest : ToolLanguageContract() {
    override val strings = ExpectedToolStrings(
        status = mapOf(
            ToolCardStatus.Preparing to "Pending",
            ToolCardStatus.Running to "Running",
            ToolCardStatus.Completed to "Done",
            ToolCardStatus.Failed to "Failed",
            ToolCardStatus.Unknown to "Unknown status",
        ),
        shell = { "Command: $it" },
        edit = { "Edit: $it" },
        read = { "Read: $it" },
        search = { "Search: $it" },
        searchIn = { pattern, path -> "Search: $pattern in $path" },
        generic = { "Tool: $it" },
        input = "Input",
        output = "Output",
        expanded = "Expanded",
        collapsed = "Collapsed",
        showInput = "Show the input",
        hideInput = "Hide the input",
        showOutput = "Show the output",
        hideOutput = "Hide the output",
        showDetails = "Show details",
        hideDetails = "Hide details",
        moreLines = { if (it == 1) "1 more line" else "$it more lines" },
        moreCharacters = { if (it == 1) "1 more character" else "$it more characters" },
        error = { "Error: $it" },
    )
}
