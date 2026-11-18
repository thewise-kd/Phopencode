package io.github.thewisekd.phopencode.keepalive

import android.Manifest
import android.app.Application
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import io.github.thewisekd.phopencode.MainActivity
import io.github.thewisekd.phopencode.StopKoinRule
import io.github.thewisekd.phopencode.core.remote.connection.ConnectionError
import io.github.thewisekd.phopencode.core.remote.connection.ConnectionState
import io.github.thewisekd.phopencode.di.AppScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import org.junit.After
import org.junit.Before
import org.junit.Rule
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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The foreground service that keeps the process alive while a connection is active. It reads the
 * connection from Koin, which each test starts with a fake in place of the one the application
 * started. Whether the system really keeps the process and its network alive in the background
 * only a phone shows. The levels of `MainActivityTest`; the Pi passes 26 only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class KeepAliveServiceTest {
    @get:Rule
    val koin = StopKoinRule()

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val connection = FakeConnection(CONNECTED)
    private val appJob = Job()
    private val controllers = mutableListOf<ServiceController<KeepAliveService>>()

    @Before
    fun fakes() {
        stopKoin()
        startKoin {
            modules(
                module {
                    single<KeepAliveConnection> { connection }
                    // Unconfined: a disconnect the service launches has run when startCommand returns.
                    single { AppScope(CoroutineScope(appJob + Dispatchers.Unconfined)) }
                },
            )
        }
    }

    @After
    fun destroy() {
        controllers.forEach { it.destroy() }
        idle()
        appJob.cancel()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun started(intent: Intent = Intent(context, KeepAliveService::class.java)): ServiceController<KeepAliveService> {
        val controller = Robolectric.buildService(KeepAliveService::class.java, intent).create().startCommand(0, 1)
        controllers += controller
        idle()
        return controller
    }

    /** A service whose start in the foreground the system refuses, as from the background. */
    private fun refused(refusal: RuntimeException): ServiceController<KeepAliveService> {
        val intent = Intent(context, KeepAliveService::class.java)
        val controller = Robolectric.buildService(KeepAliveService::class.java, intent).create()
        controllers += controller
        shadowOf(controller.get()).setThrowInStartForeground(refusal)
        controller.startCommand(0, 1)
        idle()
        return controller
    }

    private fun KeepAliveService.foreground(): Notification = assertNotNull(shadowOf(this).lastForegroundNotification)

    private fun shown(): Notification? =
        shadowOf(context.getSystemService(NotificationManager::class.java)).getNotification(KeepAliveService.NOTIFICATION_ID)

    private fun Notification.title(): String? = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()

    /** Every text the notification carries, its action titles included. */
    private fun Notification.texts(): List<String> =
        extras.keySet().mapNotNull { extras.get(it) as? CharSequence }.map { it.toString() } +
            actions.orEmpty().map { it.title.toString() }

    private fun assertNoServerDetail(notification: Notification) {
        val texts = notification.texts() + listOfNotNull(notification.tickerText?.toString())
        texts.forEach { text ->
            listOf(SECRET_HOST, "gizli", SECRET_PORT.toString(), SECRET_PROFILE, "2.3.0", "ssh-ed25519")
                .forEach { detail -> assertFalse(detail in text, "\"$text\" shows $detail") }
        }
        assertNull(notification.tickerText)
    }

    @Test
    fun `while connected it is in the foreground with an ongoing, silent notification`() {
        val service = started().get()

        val notification = service.foreground()
        assertEquals(KeepAliveService.NOTIFICATION_ID, shadowOf(service).lastForegroundNotificationId)
        assertEquals("Connected to the server", notification.title())
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertTrue(notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertNull(notification.sound)
        assertNull(notification.vibrate)
        assertEquals(KeepAliveService.CHANNEL_ID, notification.channelId)
        assertFalse(shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun `the text follows the state and never shows a server detail`() {
        val service = started().get()
        assertNoServerDetail(service.foreground())

        connection.state.value = RECONNECTING
        idle()
        val reconnecting = assertNotNull(shown())
        assertEquals("Reconnecting…", reconnecting.title())
        assertNoServerDetail(reconnecting)

        listOf(ConnectionState.Ssh(1), HOST_APPROVAL, ConnectionState.Server(1), ConnectionState.Api(1)).forEach { state ->
            connection.state.value = state
            idle()
            val connecting = assertNotNull(shown())
            assertEquals("Connecting…", connecting.title(), state.toString())
            assertNoServerDetail(connecting)
        }

        connection.state.value = CONNECTED
        idle()
        assertEquals("Connected to the server", assertNotNull(shown()).title())
        assertFalse(shadowOf(service).isStoppedBySelf)
    }

    @Test
    @Config(qualifiers = "tr")
    fun `the notification speaks Turkish on a Turkish phone`() {
        val service = started().get()
        val notification = service.foreground()
        assertEquals("Sunucuya bağlı", notification.title())
        assertEquals("Bağlantıyı kes", notification.actions.single().title.toString())

        connection.state.value = RECONNECTING
        idle()
        assertEquals("Yeniden bağlanıyor…", assertNotNull(shown()).title())

        connection.state.value = ConnectionState.Ssh()
        idle()
        assertEquals("Bağlanıyor…", assertNotNull(shown()).title())

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = context.getSystemService(NotificationManager::class.java).getNotificationChannel(KeepAliveService.CHANNEL_ID)
            assertEquals("Bağlantı", channel.name.toString())
        }
    }

    @Test
    fun `started while a connect is under way it says connecting`() {
        connection.state.value = ConnectionState.Ssh()

        assertEquals("Connecting…", started().get().foreground().title())
    }

    @Test
    fun `seeing the connection end does not stop it, as only the watcher stops it`() {
        val service = started().get()

        connection.state.value = ConnectionState.Error(ConnectionError.Unreachable)
        idle()

        assertFalse(shadowOf(service).isStoppedBySelf)
        assertFalse(shadowOf(service).isForegroundStopped)
        // The notification keeps its last text until the watcher's stop comes.
        assertEquals("Connected to the server", assertNotNull(shown() ?: service.foreground()).title())
    }

    @Test
    fun `an end the service sees but the watcher misses leaves the service running for the reconnect`() {
        val service = started().get()

        // The service sees the end; the watcher, its state conflated, sees only active then active
        // again, so it sends neither a stop nor a second start.
        connection.state.value = ConnectionState.Error(ConnectionError.Unreachable)
        idle()
        connection.state.value = ConnectionState.Ssh()
        idle()

        assertFalse(shadowOf(service).isStoppedBySelf)
        assertFalse(shadowOf(service).isForegroundStopped)
        assertEquals("Connecting…", assertNotNull(shown()).title())
    }

    @Test
    fun `the watcher's stop leaves the foreground and stops the service`() {
        val controller = started()

        controller.withIntent(KeepAliveService.stopIntent(context)).startCommand(0, 2)
        idle()

        assertTrue(shadowOf(controller.get()).isForegroundStopped)
        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
    }

    @Test
    fun `a stop sent right after the start reaches the service after it went to the foreground`() {
        val controller = Robolectric.buildService(KeepAliveService::class.java, Intent(context, KeepAliveService::class.java)).create()
        controllers += controller

        // The order the system delivers them in: the start, then the stop.
        controller.startCommand(0, 1)
        controller.withIntent(KeepAliveService.stopIntent(context)).startCommand(0, 2)
        idle()

        val service = controller.get()
        assertEquals(KeepAliveService.NOTIFICATION_ID, shadowOf(service).lastForegroundNotificationId)
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun `a stop that reaches a service that was not running only stops it`() {
        val service = started(KeepAliveService.stopIntent(context)).get()

        assertNull(shadowOf(service).lastForegroundNotification)
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertEquals(0, connection.disconnects)
    }

    @Test
    fun `the one action disconnects, and leaves stopping the service to the watcher`() {
        val controller = started()
        val action = controller.get().foreground().actions.single()
        assertEquals("Disconnect", action.title.toString())
        assertTrue(shadowOf(action.actionIntent).isServiceIntent)
        val sent = shadowOf(action.actionIntent).savedIntent
        assertEquals(ComponentName(context, KeepAliveService::class.java), sent.component)

        controller.withIntent(sent).startCommand(0, 2)
        idle()

        assertEquals(1, connection.disconnects)
        assertEquals(ConnectionState.Idle, connection.state.value)
        assertFalse(shadowOf(controller.get()).isStoppedBySelf)
    }

    @Test
    fun `the action reaching a service that was not running disconnects and stops it`() {
        val service = started(KeepAliveService.disconnectIntent(context)).get()

        assertEquals(1, connection.disconnects)
        assertNull(shadowOf(service).lastForegroundNotification)
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun `a re-post after the permission was granted shows the current notification again`() {
        val controller = started()
        val notifications = shadowOf(context.getSystemService(NotificationManager::class.java))
        notifications.cancelAll()
        connection.state.value = RECONNECTING
        idle()
        notifications.cancelAll()

        controller.withIntent(KeepAliveService.repostIntent(context)).startCommand(0, 2)
        idle()

        assertEquals("Reconnecting…", assertNotNull(shown()).title())
        assertFalse(shadowOf(controller.get()).isStoppedBySelf)
        assertEquals(0, connection.disconnects)
    }

    @Test
    fun `a re-post that reaches a service that was not running only stops it`() {
        val service = started(KeepAliveService.repostIntent(context)).get()

        assertNull(shadowOf(service).lastForegroundNotification)
        assertNull(shown())
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertEquals(0, connection.disconnects)
    }

    /** What became of a run of intents, all sent before the first reached the service. */
    private class Delivered(val service: KeepAliveService, val stopped: List<Int>)

    /**
     * Sends [intents] with start ids 1, 2, … as the system would if all were sent at once: a stop
     * succeeds only for the last id, as `stopSelfResult` refuses one with a later intent sent.
     * Robolectric's own `stopSelfResult` always succeeds, so the test stands in for it.
     */
    private fun deliver(vararg intents: Intent): Delivered {
        val controller = Robolectric.buildService(KeepAliveService::class.java, intents.first()).create()
        controllers += controller
        val stopped = mutableListOf<Int>()
        controller.get().stopIfLatest = { startId -> (startId == intents.size).also { if (it) stopped += startId } }
        intents.forEachIndexed { index, intent ->
            controller.withIntent(intent).startCommand(0, index + 1)
            idle()
        }
        return Delivered(controller.get(), stopped)
    }

    private fun startIntent() = Intent(context, KeepAliveService::class.java)

    @Test
    fun `a re-post sent after a stop ends the service the stop could not`() {
        val run = deliver(startIntent(), KeepAliveService.stopIntent(context), KeepAliveService.repostIntent(context))

        assertEquals(listOf(3), run.stopped)
        assertTrue(shadowOf(run.service).isForegroundStopped)
    }

    @Test
    fun `a stop sent after a re-post ends the service`() {
        val run = deliver(startIntent(), KeepAliveService.repostIntent(context), KeepAliveService.stopIntent(context))

        assertEquals(listOf(3), run.stopped)
        assertTrue(shadowOf(run.service).isForegroundStopped)
    }

    @Test
    fun `a start sent after a stop keeps the service in the foreground`() {
        val run = deliver(startIntent(), KeepAliveService.stopIntent(context), startIntent())

        assertEquals(emptyList<Int>(), run.stopped)
        assertFalse(shadowOf(run.service).isForegroundStopped)
        assertEquals(KeepAliveService.NOTIFICATION_ID, shadowOf(run.service).lastForegroundNotificationId)
    }

    @Test
    fun `a start after a stop and a re-post keeps the service, as the re-post cannot end it`() {
        val run = deliver(
            startIntent(),
            KeepAliveService.stopIntent(context),
            KeepAliveService.repostIntent(context),
            startIntent(),
        )

        assertEquals(emptyList<Int>(), run.stopped)
        assertFalse(shadowOf(run.service).isForegroundStopped)
    }

    @Test
    fun `a disconnect sent after a stop ends the service the stop could not`() {
        val run = deliver(startIntent(), KeepAliveService.stopIntent(context), KeepAliveService.disconnectIntent(context))

        assertEquals(1, connection.disconnects)
        assertEquals(listOf(3), run.stopped)
        assertTrue(shadowOf(run.service).isForegroundStopped)
    }

    @Test
    fun `tapping the notification opens the main activity through an immutable intent`() {
        val notification = started().get().foreground()
        val tap = assertNotNull(notification.contentIntent)

        assertTrue(shadowOf(tap).isActivityIntent)
        assertTrue(shadowOf(tap).flags and PendingIntent.FLAG_IMMUTABLE != 0)
        assertEquals(ComponentName(context, MainActivity::class.java), shadowOf(tap).savedIntent.component)
        // The action's intent cannot be changed by whoever holds it either.
        assertTrue(shadowOf(notification.actions.single().actionIntent).flags and PendingIntent.FLAG_IMMUTABLE != 0)
    }

    @Test
    fun `a refused foreground start does not crash, is not retried, and leaves the connection alone`() {
        listOf(
            IllegalStateException("not allowed from the background"),
            SecurityException("missing the foreground service permission"),
        ).forEach { refusal ->
            val service = refused(refusal).get()

            assertTrue(shadowOf(service).isStoppedBySelf, refusal.toString())
            assertNull(shown(), "a notification without the foreground")
        }
        assertEquals(0, connection.disconnects)
        assertEquals(CONNECTED, connection.state.value)
    }

    @Test
    @Config(sdk = [36])
    fun `a refused start of Android 12 and later is caught too`() {
        val service = refused(ForegroundServiceStartNotAllowedException("not allowed")).get()

        assertTrue(shadowOf(service).isStoppedBySelf)
        assertEquals(0, connection.disconnects)
    }

    @Test
    @Config(sdk = [36])
    fun `without the notification permission the service still runs`() {
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        val service = started().get()

        assertEquals(KeepAliveService.NOTIFICATION_ID, shadowOf(service).lastForegroundNotificationId)
        assertFalse(shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun `the channel is its own, quiet and of low importance`() {
        started()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = assertNotNull(
                context.getSystemService(NotificationManager::class.java).getNotificationChannel(KeepAliveService.CHANNEL_ID),
            )
            assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
            assertEquals("Connection", channel.name.toString())
            assertNull(channel.sound)
            assertFalse(channel.shouldVibrate())
        }
    }

    @Test
    fun `the starter asks for the service in the foreground and stops it`() {
        val starter = ServiceKeepAliveStarter(context)

        starter.start()
        val startedIntent = assertNotNull(shadowOf(context).nextStartedService)
        assertEquals(ComponentName(context, KeepAliveService::class.java), startedIntent.component)

        // An intent, not stopService, so the stop comes after the start has gone to the foreground.
        starter.stop()
        val stopIntent = assertNotNull(shadowOf(context).nextStartedService)
        assertEquals(ComponentName(context, KeepAliveService::class.java), stopIntent.component)
        assertEquals(KeepAliveService.stopIntent(context).action, stopIntent.action)
        assertNull(shadowOf(context).nextStoppedService)
    }
}
