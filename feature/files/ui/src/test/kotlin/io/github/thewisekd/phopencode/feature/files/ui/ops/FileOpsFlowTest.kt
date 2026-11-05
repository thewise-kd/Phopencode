package io.github.thewisekd.phopencode.feature.files.ui.ops

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.files.logic.contract.FileAction
import io.github.thewisekd.phopencode.feature.files.logic.contract.FileOpsDialog
import io.github.thewisekd.phopencode.feature.files.logic.contract.FileOpsEvent
import io.github.thewisekd.phopencode.feature.files.logic.contract.FileOpsIntent
import io.github.thewisekd.phopencode.feature.files.logic.contract.FileOpsPresenter
import io.github.thewisekd.phopencode.feature.files.logic.contract.FileOpsRequest
import io.github.thewisekd.phopencode.feature.files.logic.contract.FileOpsState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * A fake presenter that follows the contract's flow just far enough: open a menu, choose an
 * action, type a name, submit. It records what the layer sent.
 */
private class FakeFileOpsPresenter : FileOpsPresenter {
    val received = mutableListOf<FileOpsIntent>()
    override val state = MutableStateFlow(FileOpsState())
    override val events: Flow<FileOpsEvent> = emptyFlow()

    override fun onIntent(intent: FileOpsIntent) {
        received += intent
        val now = state.value
        state.value = when (intent) {
            is FileOpsIntent.Open -> when (val request = intent.request) {
                is FileOpsRequest.Actions -> FileOpsState(menu = request, menuActions = FileAction.entries)
                is FileOpsRequest.NewFolder -> FileOpsState(dialog = FileOpsDialog.NewFolder(request.folder))
            }
            is FileOpsIntent.Choose -> {
                val entry = now.menu!!.entry
                when (intent.action) {
                    FileAction.Rename -> FileOpsState(dialog = FileOpsDialog.Rename(entry, entry.name))
                    FileAction.Delete -> FileOpsState(dialog = FileOpsDialog.ConfirmDelete(entry))
                    FileAction.Edit, FileAction.Download -> FileOpsState()
                }
            }
            is FileOpsIntent.NameChanged -> when (val dialog = now.dialog) {
                is FileOpsDialog.Rename -> now.copy(dialog = dialog.copy(name = intent.name))
                is FileOpsDialog.NewFolder -> now.copy(dialog = dialog.copy(name = intent.name))
                else -> now
            }
            FileOpsIntent.Submit, FileOpsIntent.ConfirmDelete, FileOpsIntent.Cancel -> FileOpsState()
            FileOpsIntent.DismissError -> now.copy(error = null)
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class FileOpsFlowTest {
    @get:Rule
    val compose = createComposeRule()

    private fun setUp(): FakeFileOpsPresenter {
        val presenter = FakeFileOpsPresenter()
        val state: StateFlow<FileOpsState> = presenter.state
        compose.setContent {
            val current by state.collectAsState()
            PhopencodeTheme(darkTheme = false) { FileOpsLayer(current, presenter::onIntent) }
        }
        return presenter
    }

    @Test
    fun `menu, rename, type and submit run through the presenter`() {
        val presenter = setUp()

        compose.runOnIdle { presenter.onIntent(FileOpsIntent.Open(FileOpsRequest.Actions(opsFile, OPS_FOLDER))) }
        compose.onNodeWithTag(FileAction.Rename.tag).performScrollTo().performClick()
        compose.onNodeWithTag(FileOpsTags.NAME_DIALOG).assertIsDisplayed()
        assertFalse(compose.hasTag(FileOpsTags.MENU))
        compose.onNodeWithTag(FileOpsTags.NAME_FIELD).performTextReplacement("yeni.kt")
        compose.onNodeWithTag(FileOpsTags.SUBMIT).performScrollTo().performClick()

        compose.runOnIdle {
            assertEquals(
                listOf(
                    FileOpsIntent.Open(FileOpsRequest.Actions(opsFile, OPS_FOLDER)),
                    FileOpsIntent.Choose(FileAction.Rename),
                    FileOpsIntent.NameChanged("yeni.kt"),
                    FileOpsIntent.Submit,
                ),
                presenter.received,
            )
        }
        assertFalse(compose.hasTag(FileOpsTags.NAME_DIALOG))
    }

    @Test
    fun `menu, delete and confirm run through the presenter`() {
        val presenter = setUp()

        compose.runOnIdle { presenter.onIntent(FileOpsIntent.Open(FileOpsRequest.Actions(opsFolder, OPS_FOLDER))) }
        compose.onNodeWithTag(FileAction.Delete.tag).performScrollTo().performClick()
        compose.onNodeWithTag(FileOpsTags.DELETE_CONFIRM).performScrollTo().performClick()

        compose.runOnIdle {
            assertEquals(
                listOf(
                    FileOpsIntent.Open(FileOpsRequest.Actions(opsFolder, OPS_FOLDER)),
                    FileOpsIntent.Choose(FileAction.Delete),
                    FileOpsIntent.ConfirmDelete,
                ),
                presenter.received,
            )
        }
        assertFalse(compose.hasTag(FileOpsTags.DELETE_DIALOG))
    }
}
