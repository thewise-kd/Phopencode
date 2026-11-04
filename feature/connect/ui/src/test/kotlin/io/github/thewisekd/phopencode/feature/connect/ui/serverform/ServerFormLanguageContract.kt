package io.github.thewisekd.phopencode.feature.connect.ui.serverform

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ServerFormAuthMethod
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ServerFormError
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ServerFormField
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ServerFormFieldError
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ServerFormState
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/** The texts of `strings_connect_serverform.xml`, as a user of that language reads them. */
data class ExpectedServerFormStrings(
    val titleNew: String,
    val titleEdit: String,
    val name: String,
    val host: String,
    val port: String,
    val username: String,
    val authPasswordLabel: String,
    val authKeyLabel: String,
    val passwordLabel: String,
    val passwordKeepHint: String,
    val opencodePasswordLabel: String,
    val opencodePasswordClearLabel: String,
    val keysEmpty: String,
    val manageKeys: String,
    val loading: String,
    val save: String,
    val saving: String,
    val cancel: String,
    val fieldErrors: Map<Pair<ServerFormField, ServerFormFieldError>, String>,
    val formErrors: Map<ServerFormError, String>,
)

/**
 * The screen's texts, once per language: a subclass fixes the language with a qualifier. Every field
 * error and every form error the contract defines must have its own message.
 */
abstract class ServerFormLanguageContract {
    @get:Rule
    val compose = createComposeRule()

    protected abstract val strings: ExpectedServerFormStrings

    @Test
    fun `every field error has a message`() {
        val all = ServerFormField.entries.flatMap { field -> ServerFormFieldError.entries.map { field to it } }
        assertEquals(all.toSet(), strings.fieldErrors.keys, "the expected strings cover every field error")
        var state by mutableStateOf(loadedState())
        compose.setForm({ state })

        all.forEach { (field, error) ->
            val method = if (field == ServerFormField.Key) ServerFormAuthMethod.Key else ServerFormAuthMethod.Password
            state = loadedState().copy(
                authMethod = method,
                keys = listOf(WORK_KEY),
                fieldErrors = mapOf(field to error),
            )
            compose.waitForIdle()
            compose.onNodeWithText(strings.fieldErrors.getValue(field to error), useUnmergedTree = true)
                .performScrollTo()
                .assertIsDisplayed()
        }
    }

    @Test
    fun `every form error has a message`() {
        assertEquals(ServerFormError.entries.toSet(), strings.formErrors.keys)
        var state by mutableStateOf(loadedState())
        compose.setForm({ state })

        ServerFormError.entries.forEach { error ->
            state = loadedState().copy(error = error)
            compose.waitForIdle()
            compose.onNodeWithText(strings.formErrors.getValue(error), useUnmergedTree = true).assertIsDisplayed()
        }
    }

    @Test
    fun `a new server form names its fields and actions`() {
        compose.setForm({ loadedState().copy(storedPassword = true) })

        // In the top bar, outside the scrolling form.
        compose.onNodeWithText(strings.titleNew, useUnmergedTree = true).assertIsDisplayed()
        listOf(
            strings.name,
            strings.host,
            strings.port,
            strings.username,
            strings.authPasswordLabel,
            strings.authKeyLabel,
            strings.passwordLabel,
            strings.passwordKeepHint,
            strings.opencodePasswordLabel,
            strings.save,
            strings.cancel,
        ).forEach { compose.onNodeWithText(it, useUnmergedTree = true).performScrollTo().assertIsDisplayed() }
    }

    @Test
    fun `an edited server with a saved opencode password offers to remove it`() {
        compose.setForm({ loadedState(isNew = false).copy(storedOpencodePassword = true) })

        compose.onNodeWithText(strings.titleEdit, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(strings.opencodePasswordClearLabel, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `key login without keys says so and offers key management`() {
        compose.setForm({ loadedState().copy(authMethod = ServerFormAuthMethod.Key) })

        compose.onNodeWithText(strings.keysEmpty, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(strings.manageKeys, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `loading and saving are named`() {
        var state by mutableStateOf(ServerFormState(isNew = false))
        compose.setForm({ state })
        compose.onNodeWithContentDescription(strings.loading).assertIsDisplayed()

        state = loadedState().copy(saving = true)
        compose.waitForIdle()
        compose.onNodeWithText(strings.saving, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
    }
}
