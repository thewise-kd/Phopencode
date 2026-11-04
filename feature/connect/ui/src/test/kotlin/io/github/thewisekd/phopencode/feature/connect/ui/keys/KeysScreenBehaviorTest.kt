package io.github.thewisekd.phopencode.feature.connect.ui.keys

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import io.github.thewisekd.phopencode.feature.connect.logic.contract.KeysError
import io.github.thewisekd.phopencode.feature.connect.logic.contract.KeysIntent
import io.github.thewisekd.phopencode.feature.connect.logic.contract.KeysState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import androidx.compose.ui.unit.dp

private const val IMPORT = "Import"
private const val GENERATE = "Generate"
private const val CREATE = "Create key"
private const val UNLOCK = "Unlock"
private const val CANCEL = "Cancel"
private const val DELETE = "Delete"
private const val GO_BACK = "Go back"
private const val DISMISS = "Dismiss error"
/** Built at runtime, so it reads as what it is: a made-up value. */
private val TYPED = listOf("not", "a", "real", "one").joinToString("-")

private val isPassword = SemanticsMatcher.keyIsDefined(SemanticsProperties.Password)

/** What a node draws or reads out: its text and the field's shown (transformed) text, not its raw input. */
private fun showsText(text: String) = SemanticsMatcher("shows '$text'") { node ->
    val shown = node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
        listOfNotNull(node.config.getOrNull(SemanticsProperties.EditableText)?.text) +
        node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    shown.any { text in it }
}

/** What the keys screen sends, in English: the texts of both languages are in `KeysScreenLanguageContract`. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class KeysScreenBehaviorTest {
    @get:Rule
    val compose = createComposeRule()

    private val key = testKey("a", name = "laptop")
    private val listed = KeysState(loading = false, keys = listOf(key))

    private fun inDialog(text: String) = compose.onNode(hasText(text) and hasAnyAncestor(isDialog()))

    // List

    @Test
    fun `no part of the public or private key is shown, only its fingerprint`() {
        compose.setKeysScreen(listed)

        compose.onNode(showsText(key.publicKey)).assertDoesNotExist()
        compose.onNode(showsText("AAAAfakepublic")).assertDoesNotExist()
        compose.onNode(hasText(key.fingerprint, substring = true)).assertExists()
    }

    @Test
    fun `copy sends the key's ID`() {
        val harness = compose.setKeysScreen(listed)

        compose.onNodeWithContentDescription("Copy the public key of laptop").performClick()

        compose.runOnIdle { assertEquals(listOf<KeysIntent>(KeysIntent.CopyPublicKey(key.id)), harness.intents) }
    }

    @Test
    fun `delete only asks, by sending RequestDelete`() {
        val harness = compose.setKeysScreen(listed)

        compose.onNodeWithContentDescription("Delete laptop").performClick()

        compose.runOnIdle { assertEquals(listOf<KeysIntent>(KeysIntent.RequestDelete(key.id)), harness.intents) }
    }

    @Test
    fun `confirming the delete sends ConfirmDelete, cancelling sends CancelDelete`() {
        val harness = compose.setKeysScreen(listed.copy(pendingDelete = key))

        inDialog(DELETE).performClick()
        inDialog(CANCEL).performClick()

        compose.runOnIdle {
            assertEquals(listOf(KeysIntent.ConfirmDelete, KeysIntent.CancelDelete), harness.intents)
        }
    }

    @Test
    fun `back sends Close and dismissing an error sends DismissError`() {
        val harness = compose.setKeysScreen(listed.copy(error = KeysError.KeyInUse))

        compose.onNodeWithContentDescription(GO_BACK).performClick()
        compose.onNodeWithContentDescription(DISMISS).performClick()

        compose.runOnIdle { assertEquals(listOf(KeysIntent.Close, KeysIntent.DismissError), harness.intents) }
    }

    @Test
    fun `buttons are at least 48dp to touch`() {
        compose.setKeysScreen(listed.copy(error = KeysError.LoadFailed))

        for (node in listOf(
            compose.onNodeWithText(IMPORT),
            compose.onNodeWithText(GENERATE),
            compose.onNodeWithContentDescription("Copy the public key of laptop"),
            compose.onNodeWithContentDescription("Delete laptop"),
            compose.onNodeWithContentDescription(GO_BACK),
            compose.onNodeWithContentDescription(DISMISS),
        )) {
            node.assertTouchSizeIsAtLeast(48.dp)
        }
    }

    @Test
    fun `while busy nothing new can start`() {
        compose.setKeysScreen(listed.copy(busy = true))

        compose.onNodeWithText(IMPORT).assertIsNotEnabled()
        compose.onNodeWithText(GENERATE).assertIsNotEnabled()
        compose.onNodeWithContentDescription("Delete laptop").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Working…").assertExists()
    }

    // Import

    @Test
    fun `import opens the document picker for any file type`() {
        val harness = compose.setKeysScreen(listed)
        harness.registry.result = null

        compose.onNodeWithText(IMPORT).performClick()

        compose.runOnIdle {
            val input = assertIs<Array<*>>(harness.registry.inputs.single())
            assertEquals(listOf<Any?>("*/*"), input.toList())
        }
    }

    @Test
    fun `a picked file is sent as Import with its name and bytes`() {
        val harness = compose.setKeysScreen(listed)
        harness.source.content = "fake key bytes".toByteArray()
        harness.source.name = "id_test"

        compose.onNodeWithText(IMPORT).performClick()

        compose.waitUntil(timeoutMillis = 5_000) { harness.intents.isNotEmpty() }
        val intent = assertIs<KeysIntent.Import>(harness.intents.single())
        assertEquals("id_test", intent.name)
        assertContentEquals("fake key bytes".toByteArray(), intent.content)
    }

    @Test
    fun `a file without a name is imported under the default name`() {
        val harness = compose.setKeysScreen(listed)
        harness.source.content = "fake key bytes".toByteArray()

        compose.onNodeWithText(IMPORT).performClick()

        compose.waitUntil(timeoutMillis = 5_000) { harness.intents.isNotEmpty() }
        assertEquals("Imported key", assertIs<KeysIntent.Import>(harness.intents.single()).name)
    }

    @Test
    fun `a cancelled picker sends nothing`() {
        val harness = compose.setKeysScreen(listed)
        harness.registry.result = null

        compose.onNodeWithText(IMPORT).performClick()

        compose.runOnIdle {
            assertTrue(harness.intents.isEmpty())
            assertTrue(harness.source.opened.isEmpty())
        }
    }

    @Test
    fun `a file over the limit sends nothing and its error can be dismissed`() {
        val harness = compose.setKeysScreen(listed)
        harness.source.content = ByteArray(MAX_KEY_FILE_BYTES + 1)

        compose.onNodeWithText(IMPORT).performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodes(hasText("The file is too large to be a private key.")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription(DISMISS).performClick()

        compose.onNodeWithText("The file is too large to be a private key.").assertDoesNotExist()
        compose.runOnIdle { assertTrue(harness.intents.isEmpty()) }
    }

    /** Picks [EARLIER_URI], whose read waits for the returned latch, then [PICKED_URI]. */
    private fun pickTwiceWithSlowFirst(harness: KeysScreenHarness): CountDownLatch {
        val release = CountDownLatch(1)
        harness.source.onOpen = { uri -> if (uri == EARLIER_URI) release.await(10, TimeUnit.SECONDS) }
        harness.registry.result = EARLIER_URI
        compose.onNodeWithText(IMPORT).performClick()
        compose.waitUntil(timeoutMillis = 5_000) { EARLIER_URI in harness.source.opened }
        harness.registry.result = PICKED_URI
        compose.onNodeWithText(IMPORT).performClick()
        return release
    }

    @Test
    fun `a second pick replaces a slower first one, whose bytes are wiped`() {
        val harness = compose.setKeysScreen(listed)
        harness.source.contentByUri[EARLIER_URI] = "earlier fake bytes".toByteArray()
        harness.source.contentByUri[PICKED_URI] = "later fake bytes".toByteArray()

        val release = pickTwiceWithSlowFirst(harness)
        compose.waitUntil(timeoutMillis = 5_000) { harness.intents.isNotEmpty() }
        release.countDown()

        val sent = assertIs<KeysIntent.Import>(harness.intents.single()).content
        // Two buffers per read: the read buffer and the content.
        compose.waitUntil(timeoutMillis = 5_000) {
            harness.allocator.count == 4 && harness.allocator.allWipedExcept(sent)
        }
        compose.runOnIdle {
            assertEquals(1, harness.intents.size)
            assertContentEquals("later fake bytes".toByteArray(), sent)
        }
    }

    @Test
    fun `a slower first pick can neither send nor overwrite the second pick's error`() {
        val harness = compose.setKeysScreen(listed)
        harness.source.contentByUri[EARLIER_URI] = "earlier fake bytes".toByteArray()
        harness.source.contentByUri[PICKED_URI] = ByteArray(0)

        val release = pickTwiceWithSlowFirst(harness)
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodes(hasText("The file is empty.")).fetchSemanticsNodes().isNotEmpty()
        }
        release.countDown()

        // The first read's buffers are wiped once it gave up; after that it can do nothing more.
        compose.waitUntil(timeoutMillis = 5_000) { harness.allocator.count == 3 && harness.allocator.allWipedExcept() }
        compose.onNodeWithText("The file is empty.").assertExists()
        compose.runOnIdle { assertTrue(harness.intents.isEmpty()) }
    }

    // Passphrase prompt

    @Test
    fun `the passphrase is masked and sent only through SubmitPassphrase`() {
        val harness = compose.setKeysScreen(listed.copy(importAwaitingPassphrase = true))

        compose.onNodeWithTag(PASSPHRASE_FIELD_TAG).assert(isPassword).performTextInput(TYPED)

        compose.onNode(showsText(TYPED)).assertDoesNotExist()
        inDialog(UNLOCK).performClick()
        compose.runOnIdle { assertEquals(listOf<KeysIntent>(KeysIntent.SubmitPassphrase(TYPED)), harness.intents) }
    }

    @Test
    fun `unlock is disabled until a passphrase is typed`() {
        compose.setKeysScreen(listed.copy(importAwaitingPassphrase = true))

        inDialog(UNLOCK).assertIsNotEnabled()
        compose.onNodeWithTag(PASSPHRASE_FIELD_TAG).performTextInput("x")
        inDialog(UNLOCK).assertIsEnabled()
    }

    @Test
    fun `cancelling the passphrase prompt sends CancelImport`() {
        val harness = compose.setKeysScreen(listed.copy(importAwaitingPassphrase = true))

        inDialog(CANCEL).performClick()

        compose.runOnIdle { assertEquals(listOf<KeysIntent>(KeysIntent.CancelImport), harness.intents) }
    }

    // Generate

    @Test
    fun `generate sends the trimmed name and the passphrase`() {
        val harness = compose.setKeysScreen(listed)

        compose.onNodeWithText(GENERATE).performClick()
        compose.onNodeWithTag(GENERATE_NAME_TAG).performTextInput("  phone  ")
        compose.onNodeWithTag(GENERATE_PASSPHRASE_TAG).assert(isPassword).performTextInput(TYPED)
        compose.onNode(showsText(TYPED)).assertDoesNotExist()
        inDialog(CREATE).performClick()

        compose.runOnIdle { assertEquals(listOf<KeysIntent>(KeysIntent.Generate("phone", TYPED)), harness.intents) }
        compose.onNodeWithTag(GENERATE_NAME_TAG).assertDoesNotExist()
    }

    @Test
    fun `generate without a passphrase sends null`() {
        val harness = compose.setKeysScreen(listed)

        compose.onNodeWithText(GENERATE).performClick()
        compose.onNodeWithTag(GENERATE_NAME_TAG).performTextInput("phone")
        inDialog(CREATE).performClick()

        compose.runOnIdle { assertEquals(listOf<KeysIntent>(KeysIntent.Generate("phone", null)), harness.intents) }
    }

    @Test
    fun `generate needs a name and can be cancelled without an intent`() {
        val harness = compose.setKeysScreen(listed)

        compose.onNodeWithText(GENERATE).performClick()
        inDialog(CREATE).assertIsNotEnabled()
        compose.onNodeWithTag(GENERATE_NAME_TAG).performTextInput("   ")
        inDialog(CREATE).assertIsNotEnabled()
        inDialog(CANCEL).performClick()

        compose.onNodeWithTag(GENERATE_NAME_TAG).assertDoesNotExist()
        compose.runOnIdle { assertTrue(harness.intents.isEmpty()) }
    }

    @Test
    fun `the delete and copy buttons name their key for TalkBack`() {
        compose.setKeysScreen(listed)

        compose.onNode(hasContentDescription("Copy the public key of laptop")).assertExists()
        compose.onNode(hasContentDescription("Delete laptop")).assertExists()
    }
}
