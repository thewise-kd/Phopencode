package io.github.thewisekd.phopencode.feature.files.ui.transfer

import android.net.Uri
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.thewisekd.phopencode.feature.files.logic.contract.TransferIntent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * A picker's result is consumed once, so the picked documents go to the queue right in the result
 * callback, before anything is read: their names and sizes are looked up later, by whoever first
 * asks, off the screen. Closing the screen or rotating it after the pick loses nothing, and the
 * picker does not open again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class TransferRestoreTest {
    @get:Rule
    val compose = createComposeRule()

    /** Reads [name] on another thread, as the queue's job would, and waits for it. */
    private fun readElsewhere(name: () -> String): String {
        val thread = Executors.newSingleThreadExecutor()
        try {
            return thread.submit<String> { name() }.get(10, TimeUnit.SECONDS)
        } finally {
            thread.shutdown()
        }
    }

    @Test
    fun `a download picked just before the screen closes still reaches the queue`() {
        val harness = TransferHarness(EMPTY.copy(pendingDownload = "$FOLDER/rapor.pdf"))
        harness.registry.answer = { Uri.parse("content://test/doc/rapor.pdf") }
        val gate = CountDownLatch(1)
        harness.documents.gate = gate
        val shown = mutableStateOf(true)
        compose.setContent { if (shown.value) harness.Content() }
        compose.waitForIdle()

        shown.value = false
        compose.waitForIdle()
        gate.countDown()

        val download = assertIs<TransferIntent.DownloadTo>(harness.intents.single())
        assertEquals(0, harness.documents.lookups.get(), "looked up before the intent went out")
        assertEquals("rapor.pdf", readElsewhere { download.document.name })
        assertEquals(listOf(true), harness.documents.writable)
    }

    @Test
    fun `uploads picked just before the screen closes still reach the queue`() {
        val harness = TransferHarness(EMPTY)
        harness.registry.answer = { listOf(Uri.parse("content://test/doc/a.txt"), Uri.parse("content://test/doc/b.png")) }
        val gate = CountDownLatch(1)
        harness.documents.gate = gate
        val shown = mutableStateOf(true)
        compose.setContent { if (shown.value) harness.Content() }
        compose.onNodeWithTag(TransferTags.UPLOAD).performScrollTo().performClick()
        compose.waitForIdle()

        shown.value = false
        compose.waitForIdle()
        gate.countDown()

        val upload = assertIs<TransferIntent.Upload>(harness.intents.single())
        assertEquals(0, harness.documents.lookups.get(), "looked up before the intent went out")
        assertEquals(listOf("a.txt", "b.png"), readElsewhere { upload.documents.joinToString("|") { it.name } }.split("|"))
        assertEquals(listOf(false, false), harness.documents.writable)
    }

    @Test
    fun `a download picked just before a rotation is queued once and the picker stays closed`() {
        val harness = TransferHarness(EMPTY.copy(pendingDownload = "$FOLDER/rapor.pdf"))
        harness.registry.answer = { Uri.parse("content://test/doc/rapor.pdf") }
        val tester = StateRestorationTester(compose)
        tester.setContent { harness.Content() }
        compose.waitForIdle()

        tester.emulateSavedInstanceStateRestore()

        compose.runOnIdle {
            assertEquals(1, harness.registry.inputs.size, "the picker opened again")
            assertIs<TransferIntent.DownloadTo>(harness.intents.single())
        }
    }
}
