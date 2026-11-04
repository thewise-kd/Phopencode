package io.github.thewisekd.phopencode.feature.connect.ui.keys

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.thewisekd.phopencode.feature.connect.logic.contract.KeysError
import io.github.thewisekd.phopencode.feature.connect.logic.contract.KeysState
import org.junit.Rule
import org.junit.Test

/** The texts of the keys screen in one language. Formats take the key's name, type or fingerprint. */
internal data class ExpectedKeysStrings(
    val title: String,
    val loading: String,
    val emptyTitle: String,
    val emptyMessage: String,
    val import: String,
    val generate: String,
    val type: (String) -> String,
    val fingerprint: (String) -> String,
    val copyDescription: (String) -> String,
    val deleteDescription: (String) -> String,
    val deleteTitle: String,
    val deleteMessage: (String) -> String,
    val delete: String,
    val generateTitle: String,
    val generateName: String,
    val generatePassphraseLabel: String,
    val generateConfirm: String,
    val passphraseTitle: String,
    val passphraseMessage: String,
    val passphraseSubmit: String,
    val errors: Map<KeysError, String>,
    val fileErrors: Map<KeyFileError, String>,
)

/** What the keys screen shows, the same in each language; a subclass sets the language and its texts. */
abstract class KeysScreenLanguageContract {
    @get:Rule
    val compose = createComposeRule()

    internal abstract val strings: ExpectedKeysStrings

    private val first = testKey("a", name = "laptop")
    private val second = testKey("b", name = "work", type = "ecdsa-sha2-nistp256")

    @Test
    fun `the list shows each key's name, type and fingerprint`() {
        compose.setKeysScreen(KeysState(loading = false, keys = listOf(first, second)))

        compose.onNodeWithText(strings.title).assertIsDisplayed()
        for (key in listOf(first, second)) {
            compose.onNodeWithText(key.name).assertIsDisplayed()
            compose.onNodeWithText(strings.type(key.type), useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithText(strings.fingerprint(key.fingerprint), useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithContentDescription(strings.copyDescription(key.name)).assertIsDisplayed()
            compose.onNodeWithContentDescription(strings.deleteDescription(key.name)).assertIsDisplayed()
        }
    }

    @Test
    fun `no keys shows the empty state with both actions`() {
        compose.setKeysScreen(KeysState(loading = false))

        compose.onNodeWithText(strings.emptyTitle).assertIsDisplayed()
        compose.onNodeWithText(strings.emptyMessage).assertIsDisplayed()
        compose.onNodeWithText(strings.import).assertIsDisplayed()
        compose.onNodeWithText(strings.generate).assertIsDisplayed()
    }

    @Test
    fun `loading says so`() {
        compose.setKeysScreen(KeysState(loading = true))

        compose.onNodeWithContentDescription(strings.loading).assertIsDisplayed()
        compose.onNodeWithText(strings.emptyTitle).assertDoesNotExist()
    }

    @Test
    fun `an encrypted import asks for the passphrase`() {
        compose.setKeysScreen(KeysState(loading = false, importAwaitingPassphrase = true))

        compose.onNodeWithText(strings.passphraseTitle).assertIsDisplayed()
        compose.onNodeWithText(strings.passphraseMessage).assertIsDisplayed()
        compose.onNodeWithText(strings.passphraseSubmit).assertIsDisplayed()
    }

    @Test
    fun `generate asks for a name and an optional passphrase`() {
        compose.setKeysScreen(KeysState(loading = false, keys = listOf(first)))

        compose.onNodeWithText(strings.generate).performClick()

        compose.onNodeWithText(strings.generateTitle).assertIsDisplayed()
        compose.onNodeWithText(strings.generateName).assertIsDisplayed()
        compose.onNodeWithText(strings.generatePassphraseLabel).assertIsDisplayed()
        compose.onNodeWithText(strings.generateConfirm).assertIsDisplayed()
    }

    @Test
    fun `a pending delete asks for confirmation naming the key`() {
        compose.setKeysScreen(KeysState(loading = false, keys = listOf(first), pendingDelete = first))

        compose.onNodeWithText(strings.deleteTitle).assertIsDisplayed()
        compose.onNodeWithText(strings.deleteMessage(first.name)).assertIsDisplayed()
        compose.onNode(hasText(strings.delete) and hasAnyAncestor(isDialog())).assertIsDisplayed()
    }

    @Test
    fun `every error has its own message`() {
        assertEveryKeyErrorIsCovered()
        val harness = compose.setKeysScreen(KeysState(loading = false, keys = listOf(first)))

        for ((error, message) in strings.errors) {
            harness.state.value = KeysState(loading = false, keys = listOf(first), error = error)

            compose.onNodeWithText(message).assertIsDisplayed()
        }
    }

    @Test
    fun `a wrong passphrase shows in the passphrase prompt`() {
        compose.setKeysScreen(
            KeysState(loading = false, importAwaitingPassphrase = true, error = KeysError.WrongPassphrase),
        )

        compose.onNodeWithText(strings.passphraseTitle).assertIsDisplayed()
        compose.onNodeWithText(strings.errors.getValue(KeysError.WrongPassphrase)).assertIsDisplayed()
    }

    @Test
    fun `every file reading error has its own message`() {
        kotlin.test.assertEquals(KeyFileError.entries.toSet(), strings.fileErrors.keys)
        val harness = compose.setKeysScreen(KeysState(loading = false))
        for ((error, message) in strings.fileErrors) {
            when (error) {
                KeyFileError.TooLarge -> harness.source.content = ByteArray(MAX_KEY_FILE_BYTES + 1)
                KeyFileError.Empty -> harness.source.content = ByteArray(0)
                KeyFileError.Unreadable -> harness.source.content = null
            }

            compose.onNodeWithText(strings.import).performClick()

            compose.waitUntil(timeoutMillis = 5_000) {
                compose.onAllNodesWithTextCount(message) > 0
            }
            compose.onNodeWithText(message).assertIsDisplayed()
        }
        kotlin.test.assertTrue(harness.intents.isEmpty())
    }

    private fun assertEveryKeyErrorIsCovered() {
        kotlin.test.assertEquals(KeysError.entries.toSet(), strings.errors.keys)
    }

    @Test
    fun `the root keeps the placeholder tag`() {
        compose.setKeysScreen(KeysState(loading = false))

        compose.onNodeWithTag("placeholder:Keys").assertExists()
    }
}

private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(text: String): Int =
    onAllNodes(hasText(text)).fetchSemanticsNodes().size
