package io.github.thewisekd.phopencode.feature.chat.ui.reasoning

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Level 36 has no arm64 Linux runtime, so the Pi runs 26 only; CI runs both. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class EnglishReasoningTest : ReasoningLanguageContract() {
    override val strings = ExpectedReasoningStrings(
        thinking = "Thinking",
        thought = "Thought",
        live = "Live",
        thoughtFor = { "Thought for $it" },
        seconds = { "$it s" },
        minutes = { "$it min" },
        minutesSeconds = { m, s -> "$m min $s s" },
        expanded = "Expanded",
        collapsed = "Collapsed",
        show = "Show the thinking",
        hide = "Hide the thinking",
    )
}
