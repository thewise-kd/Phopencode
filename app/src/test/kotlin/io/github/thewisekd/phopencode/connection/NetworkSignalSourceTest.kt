package io.github.thewisekd.phopencode.connection

import android.content.Context
import android.net.ConnectivityManager
import androidx.test.core.app.ApplicationProvider
import io.github.thewisekd.phopencode.StopKoinRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The default network's callbacks reach the connection as they are; the manager's tests cover what
 * they mean. The levels of `MainActivityTest`; the Pi passes 26 only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36])
class NetworkSignalSourceTest {
    @get:Rule
    val koin = StopKoinRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val signals = RecordingSignals()
    private val source = NetworkSignalSource(context, signals)

    @Test
    fun `start registers the callback once`() {
        source.start()
        source.start()

        val registered = shadowOf(connectivity).networkCallbacks
        assertTrue(source.callback in registered)
        assertEquals(1, registered.count { it === source.callback })
    }

    @Test
    fun `available and lost networks are passed on as they are`() {
        val wifi = ShadowNetwork.newInstance(1)
        val mobile = ShadowNetwork.newInstance(2)

        source.callback.onAvailable(wifi)
        source.callback.onLost(wifi)
        source.callback.onAvailable(mobile)

        assertEquals(listOf("available" to wifi, "lost" to wifi, "available" to mobile), signals.calls)
    }
}
