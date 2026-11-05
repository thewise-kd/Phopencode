package io.github.thewisekd.phopencode.feature.files.ui.transfer

import io.github.thewisekd.phopencode.feature.files.logic.contract.TransferDirection
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What the notification sums up from the queue's jobs: plain Kotlin, no Android needed. */
class TransferSummaryTest {
    @Test
    fun `no jobs, or only finished ones, is idle`() {
        assertNull(summarize(emptyList()))
        assertNull(summarize(listOf(UPLOADED, DOWNLOADED, CANCELLED, FAILED)))
    }

    @Test
    fun `the running job is shown, the other unfinished ones are counted`() {
        val summary = summarize(listOf(UPLOADED, QUEUED, UPLOADING, AWAITING))

        assertEquals(
            TransferSummary(
                name = "ana kod.kt",
                direction = TransferDirection.Upload,
                phase = TransferPhase.Running,
                percent = 45,
                others = 2,
            ),
            summary,
        )
    }

    @Test
    fun `without a running job, a waiting answer comes before the queue`() {
        val summary = summarize(listOf(QUEUED, AWAITING))

        assertEquals(TransferPhase.AwaitingAnswer, summary?.phase)
        assertEquals("notlar.md", summary?.name)
        assertEquals(1, summary?.others)
    }

    @Test
    fun `otherwise the oldest queued job is shown, with no progress`() {
        val summary = summarize(listOf(QUEUED, job(9, "ikinci.txt")))

        assertEquals(TransferSummary("rapor.pdf", TransferDirection.Upload, TransferPhase.Waiting, null, 1), summary)
    }

    @Test
    fun `an unknown size has no percent, a known one rounds down`() {
        assertNull(summarize(listOf(DOWNLOADING_UNKNOWN))?.percent)
        assertEquals(99, summarize(listOf(UPLOADING.copy(bytesDone = 999, totalBytes = 1000)))?.percent)
        assertEquals(0, summarize(listOf(UPLOADING.copy(bytesDone = 0, totalBytes = 0)))?.percent)
    }

    @Test
    fun `only the last part of a name is kept, never a path`() {
        val summary = summarize(listOf(UPLOADING.copy(localName = "$ROOT/gizli/ana kod.kt")))

        assertEquals("ana kod.kt", summary?.name)
    }

    @Test
    fun `progress within the same percent gives the same summary, so the notification is not redrawn`() {
        val first = summarize(listOf(UPLOADING.copy(bytesDone = 450, totalBytes = 1000)))
        val second = summarize(listOf(UPLOADING.copy(bytesDone = 459, totalBytes = 1000)))

        assertEquals(first, second)
    }
}
