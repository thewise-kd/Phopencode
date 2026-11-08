package io.github.thewisekd.phopencode.feature.chat.ui.composer

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatFailure
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ComposerAction
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ComposerError
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/**
 * The texts of the composer in one language. [fieldHint] is the empty field's placeholder; [send]
 * and [stop] name the round icon button for a screen reader. An error reads as the sentence of what
 * failed ([failed]) followed by the sentence of why ([reasons]), with one space.
 */
internal data class ExpectedComposerStrings(
    val fieldHint: String,
    val send: String,
    val stop: String,
    val sending: String,
    val stopping: String,
    val steer: String,
    val queue: String,
    val failed: Map<ComposerAction, String>,
    val reasons: Map<ChatFailure, String>,
)

/** What the composer shows, the same in each language; a subclass sets the language and its texts. */
abstract class ComposerLanguageContract {
    @get:Rule
    val compose = createComposeRule()

    internal abstract val strings: ExpectedComposerStrings

    private fun shows(text: String) = compose.onNodeWithText(text).performScrollTo().assertIsDisplayed()

    private fun named(name: String) = compose.onNodeWithContentDescription(name).performScrollTo().assertIsDisplayed()

    @Test
    fun `an idle composer shows its field with the hint, and Send`() {
        compose.setComposer(ready())

        shows(strings.fieldHint)
        named(strings.send)
        compose.onNodeWithContentDescription(strings.stop).assertDoesNotExist()
        compose.onNodeWithText(strings.steer).assertDoesNotExist()
    }

    @Test
    fun `running work offers Stop and the delivery choice`() {
        compose.setComposer(running())

        named(strings.stop)
        shows(strings.steer)
        shows(strings.queue)
        compose.onNodeWithContentDescription(strings.send).assertDoesNotExist()
    }

    @Test
    fun `sending says so`() {
        compose.setComposer(ready(DRAFT).copy(sending = true))

        compose.onNodeWithContentDescription(strings.sending).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `stopping says so`() {
        compose.setComposer(running().copy(stopping = true))

        compose.onNodeWithContentDescription(strings.stopping).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `every failed action says which one and why`() {
        assertEquals(ComposerAction.entries.toSet(), strings.failed.keys)
        assertEquals(strings.failed.size, strings.failed.values.toSet().size, "messages must differ")
        assertEquals(ChatFailure.entries.toSet(), strings.reasons.keys)
        assertEquals(strings.reasons.size, strings.reasons.values.toSet().size, "messages must differ")
        val harness = compose.setComposer(ready())

        for ((action, failed) in strings.failed) {
            for ((failure, reason) in strings.reasons) {
                harness.state.value = ready().copy(error = ComposerError(action, failure))

                shows("$failed $reason")
            }
        }
    }
}
