package io.github.thewisekd.phopencode.feature.notify.ui.pending

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import io.github.thewisekd.phopencode.core.navigation.NotifyRoute
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.notify.logic.contract.NotifyFailure
import io.github.thewisekd.phopencode.feature.notify.logic.contract.PendingState
import org.junit.Rule
import org.junit.Test

/** The texts of the pending list in one language. */
internal data class ExpectedPendingStrings(
    val title: String,
    val refresh: String,
    val loading: String,
    val refreshing: String,
    val empty: String,
    val question: String,
    val form: String,
    val permission: String,
    val untitled: String,
    val subagent: String,
    /** The click label of an item: what a tap does. */
    val open: String,
    val retry: String,
    val connect: String,
    /** By failure; the ones the pending list never gets share [NotifyFailure.Unknown]'s text. */
    val errors: Map<NotifyFailure, String>,
)

/** A node whose click action carries [label], what TalkBack says a tap does. */
internal fun hasClickLabel(label: String) = SemanticsMatcher("click label is $label") { node ->
    node.config.getOrNull(SemanticsActions.OnClick)?.label == label
}

/** What the pending list shows, the same in each language; a subclass sets the language and its texts. */
abstract class PendingScreenLanguageContract {
    @get:Rule
    val compose = createComposeRule()

    internal abstract val strings: ExpectedPendingStrings

    @Test
    fun `the list shows its title, refresh button and items`() {
        compose.setPending(listed)

        compose.onNodeWithText(strings.title).assertIsDisplayed()
        compose.onNodeWithContentDescription(strings.refresh).assertIsDisplayed()
        compose.onNodeWithText(strings.question).assertIsDisplayed()
        compose.onNodeWithText(strings.form).assertIsDisplayed()
        compose.onNodeWithText(strings.permission).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(strings.untitled).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(strings.subagent).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `an item is announced as opening its session`() {
        compose.setPending(listed)

        compose.onNode(hasText(question.sessionTitle!!).and(hasClickLabel(strings.open))).assertIsDisplayed()
    }

    @Test
    fun `loading is told in words`() {
        compose.setPending(PendingState())
        compose.onNodeWithContentDescription(strings.loading).assertIsDisplayed()
    }

    @Test
    fun `refreshing is told above the items`() {
        compose.setPending(listed.copy(refreshing = true))
        compose.onNodeWithContentDescription(strings.refreshing).assertIsDisplayed()
    }

    @Test
    fun `an empty list says nothing is waiting`() {
        compose.setPending(PendingState(loading = false))
        compose.onNodeWithText(strings.empty).assertIsDisplayed()
    }

    @Test
    fun `every failure has its own words and its way out`() {
        val state = mutableStateOf(PendingState(loading = false))
        compose.setContent { PhopencodeTheme { PendingScreen(NotifyRoute.Pending, state.value, onIntent = {}) } }

        NotifyFailure.entries.forEach { failure ->
            compose.runOnIdle { state.value = PendingState(loading = false, error = failure) }
            val message = strings.errors[failure] ?: strings.errors.getValue(NotifyFailure.Unknown)
            compose.onNodeWithText(message).assertIsDisplayed()
            val action = if (failure in CONNECTION_FAILURES) strings.connect else strings.retry
            compose.onNodeWithText(action).assertIsDisplayed()
        }
    }
}
