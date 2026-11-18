package io.github.thewisekd.phopencode.connection

import android.app.Activity
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.github.thewisekd.phopencode.StopKoinRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * The foreground is the first started activity while none was started. The levels of
 * `MainActivityTest`; the Pi passes 26 only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36])
class ForegroundSignalSourceTest {
    @get:Rule
    val koin = StopKoinRule()

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val signals = RecordingSignals()
    private val source = ForegroundSignalSource(signals).also { it.start(application) }

    private fun foregrounds(): Int = signals.calls.count { it.first == "foreground" }

    /** An activity that says, as told, whether it is stopping for a configuration change. */
    class ChangingActivity : Activity() {
        var changing = false

        override fun isChangingConfigurations(): Boolean = changing
    }

    private fun changingActivity(): ChangingActivity = Robolectric.buildActivity(ChangingActivity::class.java).get()

    @Test
    fun `the first started activity brings the app to the foreground`() {
        Robolectric.buildActivity(Activity::class.java).setup()

        assertEquals(1, foregrounds())
    }

    @Test
    fun `a second activity while one is started is no new foreground`() {
        Robolectric.buildActivity(Activity::class.java).setup()
        Robolectric.buildActivity(Activity::class.java).setup()

        assertEquals(1, foregrounds())
    }

    @Test
    fun `coming back after every activity stopped is a new foreground`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        activity.pause().stop()
        activity.restart().start().resume()

        assertEquals(2, foregrounds())
    }

    @Test
    fun `a recreated activity is no new foreground`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        activity.recreate()

        assertEquals(1, foregrounds())
        activity.pause().stop()
        Robolectric.buildActivity(Activity::class.java).setup()
        assertEquals(2, foregrounds())
    }

    @Test
    fun `two activities recreated at the same time are no new foreground, and later ones still count`() {
        // Driven by hand, unregistered, to interleave the two recreations as the system may.
        val signals = RecordingSignals()
        val source = ForegroundSignalSource(signals)
        val first = changingActivity()
        val second = changingActivity()
        source.onActivityStarted(first)
        source.onActivityStarted(second)

        first.changing = true
        second.changing = true
        source.onActivityStopped(first)
        source.onActivityStopped(second)
        val firstAgain = changingActivity()
        val secondAgain = changingActivity()
        source.onActivityStarted(firstAgain)
        source.onActivityStarted(secondAgain)
        assertEquals(1, signals.calls.size, "a recreation is no new foreground")

        source.onActivityStopped(firstAgain)
        source.onActivityStopped(secondAgain)
        source.onActivityStarted(changingActivity())
        assertEquals(List(2) { "foreground" to null }, signals.calls)
    }

    @Test
    fun `starting twice registers once`() {
        source.start(application)
        Robolectric.buildActivity(Activity::class.java).setup()

        assertEquals(1, foregrounds())
    }
}
