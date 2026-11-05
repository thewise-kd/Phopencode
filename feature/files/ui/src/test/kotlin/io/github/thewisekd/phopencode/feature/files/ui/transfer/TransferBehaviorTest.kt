package io.github.thewisekd.phopencode.feature.files.ui.transfer

import android.app.Application
import android.net.Uri
import android.os.Build
import android.webkit.MimeTypeMap
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import androidx.test.core.app.ApplicationProvider
import io.github.thewisekd.phopencode.feature.files.logic.contract.TransferIntent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val NOTIFICATIONS_PERMISSION = "android.permission.POST_NOTIFICATIONS"

/**
 * What the transfer screen draws for each state of the contract and what it sends, in English: the
 * texts of both languages are in `TransferLanguageContract`. The system pickers are a fake
 * activity result registry; the real pickers, and the notification permission prompt of API 33 and
 * later, only the emulator shows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class TransferBehaviorTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the root keeps the route's tag and names the upload folder`() {
        compose.setTransfers(EMPTY)

        compose.onNodeWithTag("placeholder:$ROUTE").assertIsDisplayed()
        compose.onNodeWithTag(TransferTags.FOLDER).assertIsDisplayed()
        compose.onNodeWithText("Uploads go to belgeler").assertIsDisplayed()
    }

    @Test
    fun `no jobs shows the empty state and nothing to clear`() {
        compose.setTransfers(EMPTY)

        compose.onNodeWithTag(TransferTags.EMPTY).assertIsDisplayed()
        compose.onNodeWithTag(TransferTags.CLEAR).assertIsNotEnabled()
    }

    @Test
    fun `the top bar's back closes the screen`() {
        val harness = compose.setTransfers(EMPTY)

        compose.onNodeWithContentDescription("Go back").performClick()

        compose.runOnIdle { assertEquals(listOf<TransferIntent>(TransferIntent.Close), harness.intents) }
    }

    @Test
    fun `each job shows its name, and only unfinished ones can be cancelled`() {
        val jobs = listOf(QUEUED, UPLOADING, DOWNLOADING_UNKNOWN, UPLOADED, DOWNLOADED, CANCELLED, FAILED)
        compose.setTransfers(EMPTY.copy(jobs = jobs))

        jobs.forEach { job ->
            compose.onNodeWithTag(TransferTags.LIST).performScrollToKey(job.id.value)
            compose.onNodeWithTag(TransferTags.job(job.id)).assertIsDisplayed()
            compose.onNodeWithText(job.localName).assertIsDisplayed()
            assertEquals(!job.status.isFinished, compose.hasTag(TransferTags.cancel(job.id)), job.localName)
        }
        assertFalse(compose.hasTag(TransferTags.EMPTY))
    }

    @Test
    fun `a running job with a known size shows determinate progress`() {
        compose.setTransfers(EMPTY.copy(jobs = listOf(UPLOADING)))

        compose.onNodeWithTag(TransferTags.progress(UPLOADING.id))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(0.45f, 0f..1f)))
        compose.onNodeWithText("Uploading, 45%").assertIsDisplayed()
    }

    @Test
    fun `a running job of unknown size and a waiting one show no progress bar at all`() {
        compose.setTransfers(EMPTY.copy(jobs = listOf(DOWNLOADING_UNKNOWN, QUEUED)))

        assertFalse(compose.hasTag(TransferTags.progress(DOWNLOADING_UNKNOWN.id)))
        assertFalse(compose.hasTag(TransferTags.progress(QUEUED.id)))
        compose.onNodeWithText("Downloading").assertIsDisplayed()
        compose.onNodeWithText("Waiting").assertIsDisplayed()
    }

    @Test
    fun `cancel sends the job's id`() {
        val harness = compose.setTransfers(EMPTY.copy(jobs = listOf(UPLOADED, UPLOADING)))

        compose.onNodeWithTag(TransferTags.cancel(UPLOADING.id)).performScrollTo().performClick()

        compose.runOnIdle { assertEquals(listOf<TransferIntent>(TransferIntent.Cancel(UPLOADING.id)), harness.intents) }
    }

    @Test
    fun `clear finished is enabled by finished jobs and sends its intent`() {
        val harness = compose.setTransfers(EMPTY.copy(jobs = listOf(UPLOADED)))

        compose.onNodeWithTag(TransferTags.CLEAR).performScrollTo().assertIsEnabled().performClick()

        compose.runOnIdle { assertEquals(listOf<TransferIntent>(TransferIntent.ClearFinished), harness.intents) }
    }

    @Test
    fun `upload opens the system picker for any file and queues every picked document`() {
        val harness = compose.setTransfers(EMPTY)
        val picked = listOf(Uri.parse("content://test/doc/a.txt"), Uri.parse("content://test/doc/b.png"))
        harness.registry.answer = { picked }

        compose.onNodeWithTag(TransferTags.UPLOAD).performScrollTo().performClick()

        compose.waitUntil { harness.intents.isNotEmpty() }
        compose.runOnIdle {
            val input = harness.registry.inputs.single()
            assertIs<Array<*>>(input)
            assertEquals(listOf("*/*"), input.toList())
            val upload = assertIs<TransferIntent.Upload>(harness.intents.single())
            assertEquals(listOf("a.txt", "b.png"), upload.documents.map { it.name })
            assertEquals(picked, harness.documents.opened)
            assertEquals(1, harness.serviceStarts)
        }
    }

    @Test
    fun `closing the upload picker without a choice queues nothing`() {
        val harness = compose.setTransfers(EMPTY)
        harness.registry.answer = { emptyList<Uri>() }

        compose.onNodeWithTag(TransferTags.UPLOAD).performScrollTo().performClick()

        compose.runOnIdle {
            assertEquals(1, harness.registry.inputs.size)
            assertTrue(harness.intents.isEmpty())
            assertEquals(0, harness.serviceStarts)
        }
    }

    @Test
    fun `a pending download opens the create-document picker named after the file`() {
        val target = Uri.parse("content://test/doc/rapor.pdf")
        val harness = TransferHarness(EMPTY.copy(pendingDownload = "$FOLDER/rapor.pdf"))
        harness.registry.answer = { target }
        compose.setContent { harness.Content() }

        compose.waitUntil { harness.intents.isNotEmpty() }
        compose.runOnIdle {
            val pdf = MimeTypeMap.getSingleton().getMimeTypeFromExtension("pdf") ?: "application/octet-stream"
            assertEquals(DownloadTarget(name = "rapor.pdf", mimeType = pdf), harness.registry.inputs.single())
            val download = assertIs<TransferIntent.DownloadTo>(harness.intents.single())
            assertEquals("rapor.pdf", download.document.name)
            assertEquals(1, harness.serviceStarts)
        }
    }

    @Test
    fun `an unknown kind of file is created as plain bytes`() {
        val harness = TransferHarness(EMPTY.copy(pendingDownload = "$FOLDER/veri.qqq"))
        harness.registry.answer = { null }
        compose.setContent { harness.Content() }

        compose.runOnIdle {
            assertEquals(DownloadTarget(name = "veri.qqq", mimeType = "application/octet-stream"), harness.registry.inputs.single())
        }
    }

    @Test
    fun `closing the create-document picker cancels the download`() {
        val harness = TransferHarness(EMPTY.copy(pendingDownload = "$FOLDER/rapor.pdf"))
        harness.registry.answer = { null }
        compose.setContent { harness.Content() }

        compose.runOnIdle {
            assertEquals(listOf<TransferIntent>(TransferIntent.CancelDownload), harness.intents)
            assertEquals(0, harness.serviceStarts)
        }
    }

    @Test
    fun `the create-document picker opens once per pending download`() {
        val harness = TransferHarness(EMPTY.copy(pendingDownload = "$FOLDER/rapor.pdf"))
        harness.registry.answer = { null }
        compose.setContent { harness.Content() }
        compose.runOnIdle { assertEquals(1, harness.registry.inputs.size) }

        harness.state.value = EMPTY.copy(pendingDownload = "$FOLDER/rapor.pdf", jobs = listOf(QUEUED))
        compose.runOnIdle { assertEquals(1, harness.registry.inputs.size) }

        harness.state.value = EMPTY
        compose.waitForIdle()
        harness.state.value = EMPTY.copy(pendingDownload = "$FOLDER/rapor.pdf")
        compose.runOnIdle { assertEquals(2, harness.registry.inputs.size) }
    }

    @Test
    fun `unfinished jobs keep the service started, finished ones do not start it`() {
        val harness = compose.setTransfers(EMPTY.copy(jobs = listOf(UPLOADED, CANCELLED)))
        compose.runOnIdle { assertEquals(0, harness.serviceStarts) }

        harness.state.value = EMPTY.copy(jobs = listOf(UPLOADED, QUEUED))

        compose.runOnIdle { assertEquals(1, harness.serviceStarts) }
    }

    @Test
    fun `the notification permission is asked once while transfers run, on API 33 and later only`() {
        val harness = compose.setTransfers(EMPTY.copy(jobs = listOf(QUEUED)))
        harness.state.value = EMPTY.copy(jobs = listOf(UPLOADING))
        compose.waitForIdle()
        harness.state.value = EMPTY.copy(jobs = listOf(UPLOADED))
        compose.waitForIdle()
        harness.state.value = EMPTY.copy(jobs = listOf(UPLOADED, QUEUED))

        compose.runOnIdle {
            val asked = harness.registry.inputs.count { it == NOTIFICATIONS_PERMISSION }
            assertEquals(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) 1 else 0, asked)
        }
    }

    @Test
    fun `a granted notification permission is not asked again`() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>()).grantPermissions(NOTIFICATIONS_PERMISSION)
        val harness = compose.setTransfers(EMPTY.copy(jobs = listOf(UPLOADING)))

        compose.runOnIdle { assertTrue(harness.registry.inputs.isEmpty()) }
    }

    @Test
    fun `the overwrite prompt names the file and answers replace`() {
        val harness = compose.setTransfers(EMPTY.copy(jobs = listOf(UPLOADING, AWAITING)))

        compose.onNodeWithTag(TransferTags.OVERWRITE).assertIsDisplayed()
        compose.onNodeWithText("Replace notlar.md?").assertIsDisplayed()
        compose.onNodeWithTag(TransferTags.OVERWRITE_REPLACE).performScrollTo().performClick()

        compose.runOnIdle {
            assertEquals(listOf<TransferIntent>(TransferIntent.AnswerOverwrite(AWAITING.id, true)), harness.intents)
        }
    }

    @Test
    fun `the overwrite prompt answers skip`() {
        val harness = compose.setTransfers(EMPTY.copy(jobs = listOf(AWAITING)))

        compose.onNodeWithTag(TransferTags.OVERWRITE_SKIP).performScrollTo().performClick()

        compose.runOnIdle {
            assertEquals(listOf<TransferIntent>(TransferIntent.AnswerOverwrite(AWAITING.id, false)), harness.intents)
        }
    }

    @Test
    fun `no overwrite prompt shows without a waiting job`() {
        compose.setTransfers(EMPTY.copy(jobs = listOf(UPLOADING, UPLOADED)))

        assertFalse(compose.hasTag(TransferTags.OVERWRITE))
    }

    @Test
    fun `a failed job says why`() {
        compose.setTransfers(EMPTY.copy(jobs = listOf(FAILED)))

        compose.onNodeWithText("Failed. The file is too large.").assertIsDisplayed()
    }

    @Test
    fun `a cancelled job says so`() {
        compose.setTransfers(EMPTY.copy(jobs = listOf(CANCELLED)))

        compose.onNodeWithTag(TransferTags.status(CANCELLED.id)).assertIsDisplayed()
        compose.onNodeWithText("Cancelled").assertIsDisplayed()
    }
}
