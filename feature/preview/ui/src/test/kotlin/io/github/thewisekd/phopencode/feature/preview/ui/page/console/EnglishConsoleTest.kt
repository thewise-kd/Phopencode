package io.github.thewisekd.phopencode.feature.preview.ui.page.console

import io.github.thewisekd.phopencode.feature.preview.logic.contract.ConsoleLevel
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Level 36 has no arm64 Linux runtime, so the Pi runs 26 only; CI runs both. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class EnglishConsoleTest : ConsoleLanguageContract() {
    override val strings = ENGLISH_CONSOLE_STRINGS
}

internal val ENGLISH_CONSOLE_STRINGS = ExpectedConsoleStrings(
    title = "Console",
    show = "Show console",
    hide = "Hide console",
    withErrors = { action, count -> if (count == 1) "$action, 1 error" else "$action, $count errors" },
    clear = "Clear",
    all = "All",
    levels = mapOf(
        ConsoleLevel.Debug to "Debug",
        ConsoleLevel.Log to "Log",
        ConsoleLevel.Info to "Info",
        ConsoleLevel.Warning to "Warning",
        ConsoleLevel.Error to "Error",
    ),
    entry = { level, text, location -> if (location == null) "$level: $text" else "$level: $text. $location" },
    sourceLine = { source, line -> "$source, line $line" },
    line = { "Line $it" },
    empty = "No messages yet.",
    emptyFiltered = "No messages at this level.",
)
