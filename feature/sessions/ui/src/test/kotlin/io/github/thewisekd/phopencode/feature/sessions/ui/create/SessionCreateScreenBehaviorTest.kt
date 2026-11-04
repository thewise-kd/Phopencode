package io.github.thewisekd.phopencode.feature.sessions.ui.create

import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.CreateFolderError
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionCreateIntent
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionCreateState
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionsFailure
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

private val en = ENGLISH_CREATE_STRINGS

/** Which intents the creation screen's controls send, and what each state enables. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class SessionCreateScreenBehaviorTest {
    @get:Rule
    val compose = createComposeRule()

    private val intents = mutableListOf<SessionCreateIntent>()

    private fun show(state: SessionCreateState) = compose.setCreateScreen(state, onIntent = { intents += it })

    @Test
    fun `the root keeps the placeholder tag`() {
        val route = createRoute.copy(skillId = "skill-1")
        compose.setCreateScreen(ready, route = route)

        compose.onNodeWithTag("placeholder:$route").assertExists()
    }

    @Test
    fun `create sends Create when it can`() {
        show(ready)

        compose.onNodeWithTag(SessionCreateTags.CREATE).assertIsEnabled().performClick()

        assertEquals(listOf<SessionCreateIntent>(SessionCreateIntent.Create), intents)
    }

    @Test
    fun `create is disabled without a folder`() {
        show(ready.copy(directory = " "))

        compose.onNodeWithTag(SessionCreateTags.CREATE).assertIsNotEnabled().performClick()

        assertEquals(emptyList(), intents)
    }

    @Test
    fun `create is disabled while the catalog loads`() {
        show(SessionCreateState(directory = FOLDER))

        compose.onNodeWithTag(SessionCreateTags.CREATE).assertIsNotEnabled()
    }

    @Test
    fun `create is disabled while creating`() {
        show(ready.copy(creating = true))

        compose.onNodeWithTag(SessionCreateTags.CREATE).assertIsNotEnabled()
        compose.onNodeWithTag(SessionCreateTags.FOLDER).assertIsNotEnabled()
        compose.onNodeWithTag(SessionCreateTags.TITLE).assertIsNotEnabled()
    }

    @Test
    fun `typing a folder sends it`() {
        show(ready.copy(directory = ""))

        compose.onNodeWithTag(SessionCreateTags.FOLDER).performTextInput("/srv")

        assertEquals(listOf<SessionCreateIntent>(SessionCreateIntent.FolderChanged("/srv")), intents)
    }

    @Test
    fun `the folder shows the state's text`() {
        show(ready)

        compose.onNodeWithText(FOLDER).assertIsDisplayed()
    }

    @Test
    fun `typing a title sends it`() {
        show(ready)

        compose.onNodeWithTag(SessionCreateTags.TITLE).performScrollTo().performTextReplacement(TYPED_TITLE)

        assertEquals(listOf<SessionCreateIntent>(SessionCreateIntent.TitleChanged(TYPED_TITLE)), intents)
    }

    @Test
    fun `the title shows the state's text`() {
        show(ready.copy(title = TYPED_TITLE))

        compose.onNodeWithText(TYPED_TITLE).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `tapping a recent folder picks it`() {
        show(ready)

        compose.onNodeWithText(RECENT_TWO).performScrollTo().performClick()

        assertEquals(listOf<SessionCreateIntent>(SessionCreateIntent.FolderChanged(RECENT_TWO)), intents)
    }

    @Test
    fun `no recent folders hides the section`() {
        show(ready.copy(recentFolders = emptyList()))

        compose.onNodeWithText(en.recent).assertDoesNotExist()
    }

    @Test
    fun `picking an agent sends it, and the default sends null`() {
        show(ready.copy(selectedAgent = build.id))

        compose.onNodeWithTag(SessionCreateTags.agent(build.id)).performScrollTo().assertIsSelected()
        compose.onNodeWithTag(SessionCreateTags.AGENT_DEFAULT).performScrollTo().assertIsNotSelected()
        compose.onNodeWithTag(SessionCreateTags.agent(plan.id)).performScrollTo().performClick()
        compose.onNodeWithTag(SessionCreateTags.AGENT_DEFAULT).performScrollTo().performClick()

        assertEquals<List<SessionCreateIntent>>(
            listOf(SessionCreateIntent.AgentSelected(plan.id), SessionCreateIntent.AgentSelected(null)),
            intents,
        )
    }

    @Test
    fun `an agent shows its name and description`() {
        show(ready)

        compose.onNodeWithText(build.name).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(build.description!!).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `the model picker sends the model picked`() {
        show(ready)

        compose.onNodeWithTag(SessionCreateTags.MODEL).performScrollTo().performClick()
        compose.onNodeWithTag(SessionCreateTags.model(fastRef)).performScrollTo().performClick()

        assertEquals(listOf<SessionCreateIntent>(SessionCreateIntent.ModelSelected(fastRef)), intents)
        compose.onNodeWithText(en.modelChoose).assertDoesNotExist()
    }

    @Test
    fun `the model picker goes back to the default`() {
        show(ready.copy(selectedModel = fastRef))

        compose.onNodeWithTag(SessionCreateTags.MODEL).performScrollTo().performClick()
        compose.onNodeWithTag(SessionCreateTags.model(fastRef)).assertIsSelected()
        compose.onNodeWithTag(SessionCreateTags.MODEL_DEFAULT).performScrollTo().performClick()

        assertEquals(listOf<SessionCreateIntent>(SessionCreateIntent.ModelSelected(null)), intents)
    }

    @Test
    fun `cancelling the model picker sends nothing`() {
        show(ready)

        compose.onNodeWithTag(SessionCreateTags.MODEL).performScrollTo().performClick()
        compose.onNodeWithText(en.modelCancel).performClick()

        assertEquals(emptyList(), intents)
        compose.onNodeWithText(en.modelChoose).assertDoesNotExist()
    }

    @Test
    fun `a picked model is shown by name`() {
        show(ready.copy(selectedModel = fastRef))

        compose.onNodeWithText(fast.name).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(en.modelDefaultNamed(deep.name)).assertDoesNotExist()
    }

    @Test
    fun `a model the catalog does not list is shown by reference`() {
        show(ready.copy(selectedModel = deepRef.copy(modelId = "gone-3")))

        compose.onNodeWithText("zeta/gone-3").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `loading shows a still message, not the pickers`() {
        show(SessionCreateState(directory = FOLDER, agents = listOf(build)))

        compose.onNodeWithContentDescription(en.loading)
            .performScrollTo()
            .assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate))
        compose.onNodeWithText(build.name).assertDoesNotExist()
        compose.onNodeWithTag(SessionCreateTags.MODEL).assertDoesNotExist()
    }

    @Test
    fun `retry reloads the catalog`() {
        show(ready.copy(catalogError = SessionsFailure.Unreachable, agents = emptyList(), models = emptyList()))

        compose.onNodeWithText(en.retry).performScrollTo().performClick()

        assertEquals(listOf<SessionCreateIntent>(SessionCreateIntent.Retry), intents)
    }

    @Test
    fun `a failed catalog still lets the session be created`() {
        show(ready.copy(catalogError = SessionsFailure.Unreachable, agents = emptyList(), models = emptyList()))

        compose.onNodeWithTag(SessionCreateTags.CREATE).assertIsEnabled()
    }

    @Test
    fun `dismiss clears a failed creation`() {
        show(ready.copy(error = SessionsFailure.Conflict))

        compose.onNodeWithContentDescription(en.dismiss).performScrollTo().performClick()

        assertEquals(listOf<SessionCreateIntent>(SessionCreateIntent.DismissError), intents)
    }

    @Test
    fun `a folder error marks the field`() {
        show(ready.copy(directory = "work", folderError = CreateFolderError.NotAbsolute))

        compose.onNodeWithTag(SessionCreateTags.FOLDER)
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
    }

    @Test
    fun `back cancels`() {
        show(ready)

        compose.onNodeWithContentDescription("Go back").performClick()

        assertEquals(listOf<SessionCreateIntent>(SessionCreateIntent.Cancel), intents)
    }

    @Test
    fun `controls are at least 48dp`() {
        show(ready)

        compose.onNodeWithTag(SessionCreateTags.CREATE).assertTouchAtLeast()
        compose.onNodeWithText(RECENT_ONE).performScrollTo().assertTouchAtLeast()
        compose.onNodeWithTag(SessionCreateTags.AGENT_DEFAULT).performScrollTo().assertTouchAtLeast()
        compose.onNodeWithTag(SessionCreateTags.agent(build.id)).performScrollTo().assertTouchAtLeast()
        compose.onNodeWithTag(SessionCreateTags.MODEL).performScrollTo().assertTouchAtLeast()
    }
}
