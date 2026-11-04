package io.github.thewisekd.phopencode.feature.sessions.ui.create

import androidx.compose.material3.Surface
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.CreateFolderError
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionCreateState
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionsFailure
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/** The texts of the creation screen in one language. Formats take a model name or a failure's reason. */
internal data class ExpectedCreateStrings(
    val title: String,
    val folder: String,
    val folderHelp: String,
    val folderErrors: Map<CreateFolderError, String>,
    val recent: String,
    val sessionTitle: String,
    val sessionTitleHelp: String,
    val agent: String,
    val agentDefault: String,
    val model: String,
    val modelDefault: String,
    val modelDefaultNamed: (String) -> String,
    val modelChoose: String,
    val modelCancel: String,
    val loading: String,
    val skill: String,
    val create: String,
    val creating: String,
    val createError: (String) -> String,
    val catalogError: (String) -> String,
    val reasons: Map<SessionsFailure, String>,
    val retry: String,
    val dismiss: String,
)

/** What the creation screen shows, the same in each language; a subclass sets the language and its texts. */
abstract class SessionCreateScreenLanguageContract {
    @get:Rule
    val compose = createComposeRule()

    internal abstract val strings: ExpectedCreateStrings

    @Test
    fun `the form shows its fields, pickers and action`() {
        compose.setCreateScreen(ready)

        compose.onNodeWithText(strings.title).assertIsDisplayed()
        compose.onNodeWithText(strings.folder).assertExists()
        compose.onNodeWithText(strings.folderHelp).assertExists()
        compose.onNodeWithText(strings.recent).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(strings.sessionTitle).assertExists()
        compose.onNodeWithText(strings.sessionTitleHelp).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(strings.agent).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(strings.agentDefault).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(strings.model).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(strings.create).assertIsDisplayed()
    }

    @Test
    fun `the default model is named when the server gives one`() {
        compose.setCreateScreen(ready)

        compose.onNodeWithText(strings.modelDefaultNamed(deep.name)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `without a server default the model is left to the server`() {
        compose.setCreateScreen(ready.copy(defaultModel = null))

        compose.onNodeWithText(strings.modelDefault).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `the model picker has a title and a way out`() {
        compose.setCreateScreen(ready)

        compose.onNodeWithTag(SessionCreateTags.MODEL).performScrollTo().performClick()

        compose.onNodeWithText(strings.modelChoose).assertIsDisplayed()
        compose.onNodeWithText(strings.modelCancel).assertIsDisplayed()
    }

    @Test
    fun `loading says so`() {
        compose.setCreateScreen(SessionCreateState(directory = FOLDER))

        compose.onNodeWithContentDescription(strings.loading).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `creating says so`() {
        compose.setCreateScreen(ready.copy(creating = true))

        compose.onNodeWithText(strings.creating).assertIsDisplayed()
    }

    @Test
    fun `a skill from the route is mentioned`() {
        compose.setCreateScreen(ready.copy(skillId = "skill-1"))

        compose.onNodeWithText(strings.skill).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `every folder error has its own message`() {
        assertEquals(CreateFolderError.entries.toSet(), strings.folderErrors.keys)
        assertEquals(CreateFolderError.entries.size, strings.folderErrors.values.toSet().size, "messages must differ")
        CreateFolderError.entries.forEach { error ->
            compose.setCreateScreenOnce(ready.copy(folderError = error))
            compose.onNodeWithText(strings.folderErrors.getValue(error)).assertExists()
        }
    }

    @Test
    fun `every failure has its own reason`() {
        assertEquals(SessionsFailure.entries.toSet(), strings.reasons.keys)
        assertEquals(SessionsFailure.entries.size, strings.reasons.values.toSet().size, "reasons must differ")
    }

    @Test
    fun `a failed creation is explained and can be dismissed`() {
        SessionsFailure.entries.forEach { failure ->
            compose.setCreateScreenOnce(ready.copy(error = failure))
            compose.onNodeWithText(strings.createError(strings.reasons.getValue(failure))).assertIsDisplayed()
            compose.onNodeWithContentDescription(strings.dismiss).assertIsDisplayed()
        }
    }

    @Test
    fun `a failed catalog is explained and can be retried`() {
        SessionsFailure.entries.forEach { failure ->
            compose.setCreateScreenOnce(ready.copy(catalogError = failure, agents = emptyList(), models = emptyList()))
            compose.onNodeWithText(strings.catalogError(strings.reasons.getValue(failure))).assertIsDisplayed()
            compose.onNodeWithText(strings.retry).assertIsDisplayed()
        }
    }

    /** A rule sets content once; the loops above swap the state through one composition instead. */
    private var holder: MutableState<SessionCreateState>? = null

    private fun ComposeContentTestRule.setCreateScreenOnce(state: SessionCreateState) {
        val current = holder
        if (current == null) {
            val created = mutableStateOf(state)
            holder = created
            setContent {
                PhopencodeTheme(darkTheme = false) {
                    Surface { SessionCreateScreen(route = createRoute, state = created.value, onIntent = {}) }
                }
            }
        } else {
            current.value = state
        }
        waitForIdle()
    }
}
