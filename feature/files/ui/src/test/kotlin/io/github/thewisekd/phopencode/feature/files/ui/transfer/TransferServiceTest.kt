package io.github.thewisekd.phopencode.feature.files.ui.transfer

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import io.github.thewisekd.phopencode.feature.files.logic.contract.LocalDocument
import io.github.thewisekd.phopencode.feature.files.logic.contract.TransferId
import io.github.thewisekd.phopencode.feature.files.logic.contract.TransferJob
import io.github.thewisekd.phopencode.feature.files.logic.contract.TransferQueue
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The app's queue as the service sees it: jobs the test sets, and what was cancelled. */
private class FakeQueue(initial: List<TransferJob>) : TransferQueue {
    override val jobs = MutableStateFlow(initial)
    val cancelled = mutableListOf<TransferId>()

    override fun upload(directory: String, folder: String, document: LocalDocument): TransferId = error("not used")

    override fun download(directory: String, path: String, document: LocalDocument): TransferId = error("not used")

    override fun answerOverwrite(id: TransferId, overwrite: Boolean) = error("not used")

    override fun cancel(id: TransferId) {
        cancelled += id
    }

    override fun clearFinished() = error("not used")
}

/**
 * The foreground service follows the app's one queue, which it gets from Koin: a progress
 * notification while any job is unfinished, a cancel action, and it stops itself when the queue is
 * idle. Whether the system really keeps it in the foreground, and the permission prompt on API 33,
 * only the emulator shows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class TransferServiceTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val queue = FakeQueue(emptyList())

    @Before
    fun koin() {
        startKoin { modules(module { single<TransferQueue> { queue } }) }
    }

    @After
    fun stop() {
        // Destroyed, the services stop following the queue, so nothing of theirs runs in a later test.
        controllers.forEach { it.destroy() }
        idle()
        stopKoin()
    }

    private val controllers = mutableListOf<ServiceController<TransferService>>()

    private fun started(intent: Intent = Intent(context, TransferService::class.java)): ServiceController<TransferService> {
        val controller = Robolectric.buildService(TransferService::class.java, intent).create().startCommand(0, 1)
        controllers += controller
        idle()
        return controller
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun TransferService.foreground(): Notification = assertNotNull(shadowOf(this).lastForegroundNotification)

    private fun Notification.title(): String? = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()

    private fun Notification.text(): String? = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()

    private fun shown(): Notification? =
        shadowOf(context.getSystemService(NotificationManager::class.java)).getNotification(TransferService.NOTIFICATION_ID)

    @Test
    fun `start goes to the foreground with the running job's progress`() {
        queue.jobs.value = listOf(UPLOADING, QUEUED)

        val service = started().get()

        val notification = service.foreground()
        assertEquals("Uploading ana kod.kt", notification.title())
        assertEquals("1 more file waiting", notification.text())
        assertEquals(100, notification.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
        assertEquals(45, notification.extras.getInt(Notification.EXTRA_PROGRESS))
        assertFalse(notification.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals("Cancel all", notification.actions.single().title.toString())
        assertFalse(shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun `a job of unknown size shows no progress bar rather than an endless one`() {
        queue.jobs.value = listOf(DOWNLOADING_UNKNOWN)

        val notification = started().get().foreground()

        assertEquals(0, notification.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
        assertFalse(notification.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
    }

    @Test
    fun `the notification follows progress`() {
        queue.jobs.value = listOf(UPLOADING)
        started()

        queue.jobs.value = listOf(UPLOADING.copy(bytesDone = 80))
        idle()

        assertEquals(80, assertNotNull(shown()).extras.getInt(Notification.EXTRA_PROGRESS))
    }

    @Test
    fun `the notification never shows the server's folder or a full path`() {
        val secretDirectory = "/home/kişi/gizli-proje"
        queue.jobs.value = listOf(
            UPLOADING.copy(directory = secretDirectory, remotePath = "$secretDirectory/derin/ana kod.kt"),
            AWAITING.copy(directory = secretDirectory, remotePath = "$secretDirectory/notlar.md"),
        )

        val notification = started().get().foreground()

        val texts = notification.extras.keySet().mapNotNull { notification.extras.get(it) as? CharSequence }.map { it.toString() }
        assertContains(texts, "Uploading ana kod.kt")
        texts.forEach { text ->
            assertFalse("gizli-proje" in text, text)
            assertFalse("/" in text, text)
        }
        assertEquals(null, notification.tickerText)
    }

    @Test
    fun `it stops itself when the queue becomes idle`() {
        queue.jobs.value = listOf(UPLOADING)
        val service = started().get()

        queue.jobs.value = listOf(UPLOADED.copy(id = UPLOADING.id))
        idle()

        assertTrue(shadowOf(service).isStoppedBySelf)
        assertTrue(shadowOf(service).isForegroundStopped)
    }

    @Test
    fun `started on an idle queue it still goes to the foreground, then stops`() {
        val service = started().get()

        // Going to the foreground is owed to startForegroundService; the notification is gone again.
        assertEquals(TransferService.NOTIFICATION_ID, shadowOf(service).lastForegroundNotificationId)
        assertTrue(shadowOf(service).notificationShouldRemoved)
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun `document grants are kept while jobs run and released when the queue is idle`() {
        val document = Uri.parse("content://test.documents/doc/1")
        context.contentResolver.takePersistableUriPermission(document, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        queue.jobs.value = listOf(UPLOADING)
        started()
        assertEquals(listOf(document), context.contentResolver.persistedUriPermissions.map { it.uri })

        queue.jobs.value = listOf(UPLOADED.copy(id = UPLOADING.id))
        idle()

        assertTrue(context.contentResolver.persistedUriPermissions.isEmpty())
    }

    @Test
    fun `refused the foreground on an idle queue, it gives the grants back and stops without failing`() {
        val document = Uri.parse("content://test.documents/doc/1")
        context.contentResolver.takePersistableUriPermission(document, Intent.FLAG_GRANT_READ_URI_PERMISSION)

        val service = refused().get()

        assertTrue(shadowOf(service).isStoppedBySelf)
        assertTrue(context.contentResolver.persistedUriPermissions.isEmpty())
    }

    @Test
    fun `refused the foreground while jobs run, it stops at once and keeps the grants for the next idle stop`() {
        val document = Uri.parse("content://test.documents/doc/1")
        context.contentResolver.takePersistableUriPermission(document, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        queue.jobs.value = listOf(UPLOADING)

        val service = refused().get()

        // Running on unpromoted would leave the foreground-start deadline pending.
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertEquals(null, shown(), "a notification without the foreground")
        assertEquals(listOf(document), context.contentResolver.persistedUriPermissions.map { it.uri })
    }

    @Test
    fun `at the dataSync time limit it leaves the foreground and stops, while the jobs go on`() {
        queue.jobs.value = listOf(UPLOADING)
        val service = started().get()

        service.onTimeout(1, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        idle()

        assertTrue(shadowOf(service).isForegroundStopped)
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertTrue(queue.cancelled.isEmpty())
    }

    /** A service whose start in the foreground the system refuses, as from the background. */
    private fun refused(): ServiceController<TransferService> {
        val controller = Robolectric.buildService(TransferService::class.java, Intent(context, TransferService::class.java)).create()
        controllers += controller
        shadowOf(controller.get()).setThrowInStartForeground(IllegalStateException("not allowed from the background"))
        controller.startCommand(0, 1)
        idle()
        return controller
    }

    @Test
    fun `the cancel action cancels every unfinished job`() {
        queue.jobs.value = listOf(UPLOADED, UPLOADING, QUEUED, AWAITING)
        val controller = started()

        controller.withIntent(TransferService.cancelIntent(context)).startCommand(0, 2)
        idle()

        assertEquals(listOf(UPLOADING.id, QUEUED.id, AWAITING.id), queue.cancelled)
    }

    @Test
    fun `the cancel action reaches this service only`() {
        queue.jobs.value = listOf(UPLOADING)
        val action = started().get().foreground().actions.single().actionIntent

        val sent = shadowOf(action).savedIntent
        assertEquals(ComponentName(context, TransferService::class.java), sent.component)
        assertTrue(shadowOf(action).isServiceIntent)
    }

    @Test
    fun `start asks for the service in the foreground`() {
        TransferService.start(context)

        val intent = assertNotNull(shadowOf(context).nextStartedService)
        assertEquals(ComponentName(context, TransferService::class.java), intent.component)
    }

    @Test
    fun `the manifest keeps the service private and asks for no storage access`() {
        val manager = context.packageManager
        val info = manager.getServiceInfo(ComponentName(context, TransferService::class.java), 0)
        assertFalse(info.exported)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, info.foregroundServiceType)
        }

        val requested = manager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty().toSet()
        assertTrue(Manifest.permission.FOREGROUND_SERVICE in requested)
        assertTrue("android.permission.FOREGROUND_SERVICE_DATA_SYNC" in requested)
        assertTrue("android.permission.POST_NOTIFICATIONS" in requested)
        listOf(
            "android.permission.READ_EXTERNAL_STORAGE",
            "android.permission.WRITE_EXTERNAL_STORAGE",
            "android.permission.MANAGE_EXTERNAL_STORAGE",
            "android.permission.READ_MEDIA_IMAGES",
            "android.permission.READ_MEDIA_VIDEO",
            "android.permission.READ_MEDIA_AUDIO",
        ).forEach { assertFalse(it in requested, it) }
    }

    @Test
    fun `the channel is quiet`() {
        queue.jobs.value = listOf(UPLOADING)
        started()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = assertNotNull(
                context.getSystemService(NotificationManager::class.java).getNotificationChannel(TransferService.CHANNEL_ID),
            )
            assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
            assertEquals("File transfers", channel.name.toString())
        }
    }
}
