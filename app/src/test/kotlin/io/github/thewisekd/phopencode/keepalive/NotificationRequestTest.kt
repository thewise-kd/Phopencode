package io.github.thewisekd.phopencode.keepalive

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import io.github.thewisekd.phopencode.MainActivity
import io.github.thewisekd.phopencode.StopKoinRule
import io.github.thewisekd.phopencode.core.remote.connection.ConnectionState
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.loadKoinModules
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * MainActivity asks for the notification permission of the keep-alive once per install, on the
 * first active connection while it is at least started. The connection and the record are fakes
 * in place of the ones the application started. Whether the system dialog really shows only a
 * phone tells; Robolectric records the request. API 33 and later need level 36, which the Pi
 * cannot run (see `MainActivityTest`); x86-64 CI runs it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NotificationRequestTest {
    @get:Rule(order = 0)
    val koin = StopKoinRule()

    @get:Rule(order = 1)
    val compose = createEmptyComposeRule()

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val connection = FakeConnection()
    private val asks = FakeAsks()

    @Before
    fun fakes() {
        loadKoinModules(
            module {
                single<KeepAliveConnection> { connection }
                single<NotificationAsks> { asks }
            },
        )
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun launch(block: (ActivityScenario<MainActivity>) -> Unit) {
        val launcher = assertNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
        ActivityScenario.launch<MainActivity>(launcher).use { scenario ->
            compose.waitForIdle()
            block(scenario)
        }
    }

    private fun set(state: ConnectionState) {
        connection.state.value = state
        compose.waitForIdle()
    }

    /** The permissions the activity asked the system for last, or null when it asked for none. */
    private fun ActivityScenario<MainActivity>.requested(): List<String>? {
        var requested: List<String>? = null
        onActivity { requested = shadowOf(it).lastRequestedPermission?.requestedPermissions?.toList() }
        return requested
    }

    /** Answers the request as the system dialog would. */
    @Suppress("DEPRECATION")
    private fun ActivityScenario<MainActivity>.answer(granted: Boolean) {
        onActivity { activity ->
            val request = assertNotNull(shadowOf(activity).lastRequestedPermission)
            if (granted) shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
            val result = if (granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
            activity.onRequestPermissionsResult(request.requestCode, request.requestedPermissions, intArrayOf(result))
        }
        compose.waitForIdle()
    }

    /** The service intents started since the last call. */
    private fun startedServices(): List<Intent> = generateSequence { shadowOf(context).nextStartedService }.toList()

    @Test
    fun `nothing is asked before a connection is active`() {
        launch { scenario ->
            set(ConnectionState.Idle)

            assertNull(scenario.requested())
            assertEquals(0, asks.records)
        }
    }

    @Test
    fun `the first active state asks once and records it`() {
        launch { scenario ->
            set(ConnectionState.Ssh())

            assertEquals(listOf(Manifest.permission.POST_NOTIFICATIONS), scenario.requested())
            assertEquals(1, asks.records)

            // The later steps of the same connect ask nothing more.
            set(ConnectionState.Server())
            set(CONNECTED)
            assertEquals(1, asks.records)
        }
    }

    @Test
    fun `a connection already active when the activity starts asks once`() {
        connection.state.value = CONNECTED

        launch { scenario ->
            assertEquals(listOf(Manifest.permission.POST_NOTIFICATIONS), scenario.requested())
            assertEquals(1, asks.records)
        }
    }

    @Test
    fun `a recreation and a second connect do not ask again`() {
        launch { scenario ->
            set(CONNECTED)
            assertEquals(1, asks.records)

            scenario.recreate()
            compose.waitForIdle()
            assertNull(scenario.requested(), "the recreated activity asks nothing")

            set(ConnectionState.Idle)
            set(ConnectionState.Ssh())
            set(CONNECTED)
            assertNull(scenario.requested())
            assertEquals(1, asks.records)
        }
    }

    @Test
    fun `a denial is recorded, asks nothing more and leaves the connection alone`() {
        launch { scenario ->
            set(CONNECTED)
            scenario.answer(granted = false)

            assertEquals(1, asks.records)
            assertEquals(CONNECTED, connection.state.value)
            assertEquals(0, connection.disconnects)
            assertTrue(startedServices().none { it.action == KeepAliveService.repostIntent(context).action })

            set(RECONNECTING)
            set(CONNECTED)
            assertEquals(1, asks.records)
        }
    }

    @Test
    fun `a grant while connected re-posts the notification once`() {
        launch { scenario ->
            set(CONNECTED)
            startedServices()

            scenario.answer(granted = true)

            val reposts = startedServices().filter { it.action == KeepAliveService.repostIntent(context).action }
            assertEquals(1, reposts.size)
            assertEquals(ComponentName(context, KeepAliveService::class.java), reposts.single().component)
            assertEquals(CONNECTED, connection.state.value)
        }
    }

    @Test
    fun `a grant after the connection ended re-posts nothing`() {
        launch { scenario ->
            set(CONNECTED)
            set(ConnectionState.Idle)
            startedServices()

            scenario.answer(granted = true)

            assertTrue(startedServices().none { it.action == KeepAliveService.repostIntent(context).action })
        }
    }

    @Test
    fun `an app that asked before asks nothing`() {
        asks.recordAsked()
        val before = asks.records

        launch { scenario ->
            set(CONNECTED)

            assertNull(scenario.requested())
            assertEquals(before, asks.records)
        }
    }

    @Test
    fun `a granted permission asks nothing and records nothing`() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        launch { scenario ->
            set(CONNECTED)

            assertNull(scenario.requested())
            assertEquals(0, asks.records)
        }
    }

    @Test
    fun `a connection that becomes active while the activity is stopped asks once it starts`() {
        launch { scenario ->
            scenario.moveToState(Lifecycle.State.CREATED)
            set(CONNECTED)
            assertNull(scenario.requested())
            assertEquals(0, asks.records)

            scenario.moveToState(Lifecycle.State.RESUMED)
            compose.waitForIdle()
            assertEquals(listOf(Manifest.permission.POST_NOTIFICATIONS), scenario.requested())
            assertEquals(1, asks.records)
        }
    }

    @Test
    @Config(sdk = [26])
    fun `below API 33 nothing is asked and nothing is recorded`() {
        launch { scenario ->
            set(CONNECTED)

            assertNull(scenario.requested())
            assertEquals(0, asks.records)
        }
    }
}

/** The record of the ask, in memory, counting how often the app recorded it. */
internal class FakeAsks : NotificationAsks {
    var records = 0
        private set

    override fun askedBefore(): Boolean = records > 0

    override fun recordAsked() {
        records++
    }
}
