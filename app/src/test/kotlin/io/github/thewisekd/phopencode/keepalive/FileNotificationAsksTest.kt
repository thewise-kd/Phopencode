package io.github.thewisekd.phopencode.keepalive

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.github.thewisekd.phopencode.StopKoinRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The record that the app asked for the notification permission. The levels of `MainActivityTest`. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36])
class FileNotificationAsksTest {
    @get:Rule
    val koin = StopKoinRule()

    private val context: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun `a fresh install has not asked`() {
        assertFalse(FileNotificationAsks(context).askedBefore())
    }

    @Test
    fun `once recorded it stays asked, for a later instance too`() {
        FileNotificationAsks(context).recordAsked()

        assertTrue(FileNotificationAsks(context).askedBefore())
    }

    @Test
    fun `recording twice keeps it asked`() {
        val asks = FileNotificationAsks(context)
        asks.recordAsked()
        asks.recordAsked()

        assertTrue(asks.askedBefore())
    }

    @Test
    fun `it is an empty file in the app's private storage that is never backed up`() {
        FileNotificationAsks(context).recordAsked()

        val written = context.noBackupFilesDir.listFiles().orEmpty().toList()
        val record = written.single { it.name == FileNotificationAsks.FILE_NAME }
        assertEquals(0L, record.length(), "it holds no data, no server detail and no secret")
        assertEquals(context.noBackupFilesDir.canonicalFile, record.canonicalFile.parentFile)
    }
}
