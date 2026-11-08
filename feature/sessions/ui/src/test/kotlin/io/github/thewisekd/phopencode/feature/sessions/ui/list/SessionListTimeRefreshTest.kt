package io.github.thewisekd.phopencode.feature.sessions.ui.list

import android.os.Looper
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.thewisekd.phopencode.core.navigation.SessionsRoute
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** A clock the test moves by hand. */
private class MovableClock(var current: Instant) : Clock {
    override fun now(): Instant = current
}

/** A lifecycle the test moves by hand; the unsafe registry skips the main thread check. */
private class TestOwner : LifecycleOwner {
    val registry = LifecycleRegistry.createUnsafe(this)
    override val lifecycle: Lifecycle get() = registry
}

/** The rows' relative times follow the clock while the screen is started, and not while it is stopped. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class SessionListTimeRefreshTest {
    @get:Rule
    val compose = createComposeRule()

    private val clock = MovableClock(NOW)
    private val owner = TestOwner()

    private fun show(state: Lifecycle.State) {
        owner.registry.currentState = state
        val row = second.copy(updatedAt = NOW - 5.minutes)
        compose.setContent {
            PhopencodeTheme(darkTheme = false) {
                CompositionLocalProvider(LocalSessionListClock provides clock, LocalLifecycleOwner provides owner) {
                    Surface {
                        SessionListScreen(
                            route = SessionsRoute.SessionList(),
                            state = listed.copy(rows = listOf(row)),
                            onIntent = {},
                        )
                    }
                }
            }
        }
    }

    /** Lets a minute pass on the clocks the screen may wait on: Compose's and the main looper's. */
    private fun letAMinutePass() {
        compose.mainClock.advanceTimeBy(TICK_MILLIS)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(TICK_MILLIS))
        compose.waitForIdle()
    }

    @Test
    fun `a visible row's time moves on with the clock`() {
        show(Lifecycle.State.RESUMED)
        compose.onNodeWithText("5 min ago", useUnmergedTree = true).assertIsDisplayed()

        clock.current = NOW + 3.minutes
        letAMinutePass()

        compose.onNodeWithText("8 min ago", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `a stopped screen does not tick, and catches up when it starts again`() {
        show(Lifecycle.State.CREATED)
        compose.onNodeWithText("5 min ago", useUnmergedTree = true).assertIsDisplayed()

        clock.current = NOW + 10.minutes
        letAMinutePass()
        compose.onNodeWithText("5 min ago", useUnmergedTree = true).assertIsDisplayed()

        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitForIdle()

        compose.onNodeWithText("15 min ago", useUnmergedTree = true).assertIsDisplayed()
    }

    private companion object {
        const val TICK_MILLIS = 61_000L
    }
}
