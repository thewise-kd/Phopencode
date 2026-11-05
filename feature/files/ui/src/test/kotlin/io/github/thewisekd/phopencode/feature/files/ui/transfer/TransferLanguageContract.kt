package io.github.thewisekd.phopencode.feature.files.ui.transfer

import android.content.ActivityNotFoundException
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import io.github.thewisekd.phopencode.feature.files.logic.contract.FilesFailure
import io.github.thewisekd.phopencode.feature.files.logic.contract.TransferDirection
import io.github.thewisekd.phopencode.feature.files.logic.contract.TransferStatus
import io.github.thewisekd.phopencode.feature.files.ui.R
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/** The texts of the transfer screen and its notification in one language. */
internal data class ExpectedTransferStrings(
    val title: String,
    val upload: String,
    val clearFinished: String,
    /** For the folder `belgeler`. */
    val folder: String,
    val emptyTitle: String,
    val emptyMessage: String,
    val queued: String,
    val uploading: String,
    /** At 45 percent. */
    val uploadingPercent: String,
    val downloading: String,
    val awaiting: String,
    val uploaded: String,
    val downloaded: String,
    val cancelled: String,
    /** "Failed" followed by a reason. */
    val failed: (String) -> String,
    val reasons: Map<FilesFailure, String>,
    val cancel: String,
    /** For `notlar.md`. */
    val overwriteTitle: String,
    /** For `notlar.md`. */
    val overwriteMessage: String,
    val replace: String,
    val skip: String,
    val pickerMissing: String,
    val channel: String,
    /** For `ana kod.kt`. */
    val notificationUploading: String,
    /** For `veri.bin`. */
    val notificationDownloading: String,
    /** For `rapor.pdf`, not started yet. */
    val notificationWaiting: String,
    /** For `notlar.md`. */
    val notificationAwaitingTitle: String,
    val notificationAwaitingText: String,
    val notificationOneMore: String,
    val notificationThreeMore: String,
    val notificationCancel: String,
)

/** What the transfer screen and its notification say, the same in each language. */
abstract class TransferLanguageContract {
    @get:Rule
    val compose = createComposeRule()

    internal abstract val strings: ExpectedTransferStrings

    @Test
    fun `the header, the buttons and the empty list are worded`() {
        compose.setTransfers(EMPTY)

        compose.onNodeWithText(strings.title).assertIsDisplayed()
        compose.onNodeWithTag(TransferTags.UPLOAD).assertTextEquals(strings.upload)
        compose.onNodeWithTag(TransferTags.CLEAR).assertTextEquals(strings.clearFinished)
        compose.onNodeWithTag(TransferTags.FOLDER).assertTextEquals(strings.folder)
        compose.onNodeWithText(strings.emptyTitle).assertIsDisplayed()
        compose.onNodeWithText(strings.emptyMessage).assertIsDisplayed()
    }

    @Test
    fun `every status is worded`() {
        val harness = compose.setTransfers(EMPTY)
        val expected = mapOf(
            QUEUED to strings.queued,
            UPLOADING to strings.uploadingPercent,
            UPLOADING.copy(totalBytes = null) to strings.uploading,
            DOWNLOADING_UNKNOWN to strings.downloading,
            AWAITING to strings.awaiting,
            UPLOADED to strings.uploaded,
            DOWNLOADED to strings.downloaded,
            CANCELLED to strings.cancelled,
        )
        expected.forEach { (job, text) ->
            harness.state.value = EMPTY.copy(jobs = listOf(job))
            compose.onNodeWithTag(TransferTags.status(job.id)).assertTextEquals(text)
        }
        harness.state.value = EMPTY.copy(jobs = listOf(QUEUED))
        compose.onNodeWithTag(TransferTags.cancel(QUEUED.id)).assertTextEquals(strings.cancel)
    }

    @Test
    fun `every failure is worded`() {
        assertEquals(FilesFailure.entries.toSet(), strings.reasons.keys)
        val harness = compose.setTransfers(EMPTY)
        strings.reasons.forEach { (failure, reason) ->
            harness.state.value = EMPTY.copy(jobs = listOf(FAILED.copy(status = TransferStatus.Failed(failure))))
            compose.onNodeWithTag(TransferTags.status(FAILED.id)).assertTextEquals(strings.failed(reason))
        }
    }

    @Test
    fun `the overwrite prompt names the file`() {
        compose.setTransfers(EMPTY.copy(jobs = listOf(AWAITING)))

        compose.onNodeWithText(strings.overwriteTitle).assertIsDisplayed()
        compose.onNodeWithText(strings.overwriteMessage).assertIsDisplayed()
        compose.onNodeWithTag(TransferTags.OVERWRITE_REPLACE).assertTextEquals(strings.replace)
        compose.onNodeWithTag(TransferTags.OVERWRITE_SKIP).assertTextEquals(strings.skip)
    }

    @Test
    fun `a phone without a picker is told so`() {
        val harness = compose.setTransfers(EMPTY)
        harness.registry.answer = { throw ActivityNotFoundException() }

        compose.onNodeWithTag(TransferTags.UPLOAD).performScrollTo().performClick()

        compose.onNodeWithTag(TransferTags.PICKER_MISSING).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(strings.pickerMissing).assertIsDisplayed()
    }

    @Test
    fun `the notification is worded`() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        assertEquals(strings.channel, context.getString(R.string.files_transfer_notification_channel))
        val uploading = notificationText(context, summarize(listOf(UPLOADING))!!)
        assertEquals(strings.notificationUploading, uploading.title)
        assertEquals(null, uploading.text)
        assertEquals(strings.notificationCancel, uploading.cancel)
        val downloading = notificationText(context, summarize(listOf(DOWNLOADING_UNKNOWN, QUEUED))!!)
        assertEquals(strings.notificationDownloading, downloading.title)
        assertEquals(strings.notificationOneMore, downloading.text)
        val waiting = notificationText(context, summarize(listOf(QUEUED, job(20, "x"), job(21, "y"), job(22, "z")))!!)
        assertEquals(strings.notificationWaiting, waiting.title)
        assertEquals(strings.notificationThreeMore, waiting.text)
        val awaiting = notificationText(context, summarize(listOf(AWAITING))!!)
        assertEquals(strings.notificationAwaitingTitle, awaiting.title)
        assertEquals(strings.notificationAwaitingText, awaiting.text)
        assertEquals(TransferDirection.Upload, summarize(listOf(AWAITING))!!.direction)
    }
}
