package io.github.thewisekd.phopencode.e2e.preview

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.thewisekd.phopencode.MainActivity
import io.github.thewisekd.phopencode.core.navigation.ConnectRoute
import io.github.thewisekd.phopencode.core.navigation.SettingsRoute
import io.github.thewisekd.phopencode.core.storage_api.SettingsRepository
import io.github.thewisekd.phopencode.e2e.E2eEnvironment
import io.github.thewisekd.phopencode.e2e.FirstRunDoneRule
import io.github.thewisekd.phopencode.e2e.appSettings
import io.github.thewisekd.phopencode.e2e.awaitSettings
import io.github.thewisekd.phopencode.e2e.endFirstRun
import io.github.thewisekd.phopencode.e2e.startFirstRunAgain
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The usage guide (P14.1) on a fresh store, end to end (P14.3): launched while the guide was never
 * seen, the app opens it above the server list; Skip lands on the server list and stores the guide
 * as seen; a later launch shows the server list alone.
 *
 * It lives in `e2e.preview` only because that package is the debug-only one: it drives the screens
 * with androidx.compose.ui.test, which the releaseCheck build's keep rules do not cover. It needs
 * no server, but it deletes all of the app's data, so like every end-to-end test it runs only with
 * the end-to-end arguments and is skipped on a plain connected run.
 */
@RunWith(AndroidJUnit4::class)
class FirstRunGuideEndToEndTest {
    @get:Rule(order = 0)
    val firstRun = FirstRunDoneRule()

    /** No activity of its own: the test launches the app itself, twice, after clearing its data. */
    @get:Rule(order = 1)
    val compose = createEmptyComposeRule()

    private lateinit var settings: SettingsRepository

    @Before
    fun setUp() = runBlocking<Unit> {
        E2eEnvironment.fromArguments()
        settings = appSettings()
        startFirstRunAgain(settings)
    }

    @After
    fun tearDown() = runBlocking {
        if (::settings.isInitialized) endFirstRun(settings)
    }

    @Test
    fun theGuideOpensAboveTheServerListAndSkipEndsItForGood() = runBlocking<Unit> {
        assertFalse("The store is not fresh", settings.observe().first().guideSeen)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitForNode("the guide on the first launch", hasTestTag(GuideTags.PAGER))
            assertEquals(
                "The guide is not above the server list alone",
                Stack(current = "Guide", previous = "ServerList", screens = 2),
                scenario.stack(),
            )

            compose.onNodeWithTag(GuideTags.SKIP, useUnmergedTree = true).performClick()
            waitForNoNode("the guide to close", hasTestTag(GuideTags.PAGER))
            PreviewDriver.waitFor("the server list alone") { scenario.stack() == SERVER_LIST_ALONE }
            awaitSettings(settings, "the guide stored as seen") { it.guideSeen }
        }

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            PreviewDriver.waitFor("the server list on the next launch") { scenario.stack() == SERVER_LIST_ALONE }
            // The launch decides after reading the settings, which nothing here can observe: the
            // guide must stay away for a while, as long as a first launch takes to show it.
            val mark = TimeSource.Monotonic.markNow()
            while (mark.elapsedNow() < QUIET) {
                assertFalse("The guide opened again", exists(hasTestTag(GuideTags.PAGER)))
                assertEquals(SERVER_LIST_ALONE, scenario.stack())
                Thread.sleep(POLL_MILLIS)
            }
        }
    }

    /** The top of the back stack: the current destination, the one below it, and how many screens it holds. */
    private data class Stack(val current: String?, val previous: String?, val screens: Int)

    private fun ActivityScenario<MainActivity>.stack(): Stack {
        val result = AtomicReference<Stack>()
        onActivity { activity ->
            val nav = activity.navController
            if (nav == null) {
                result.set(Stack(null, null, 0))
                return@onActivity
            }
            // Graph entries are not screens: count only the other destinations.
            val screens = nav.currentBackStack.value.count { it.destination !is NavGraph }
            result.set(Stack(nav.currentDestination?.name(), nav.previousBackStackEntry?.destination?.name(), screens))
        }
        return result.get()
    }

    private fun NavDestination.name(): String? = when {
        hasRoute<SettingsRoute.Guide>() -> "Guide"
        hasRoute<ConnectRoute.ServerList>() -> "ServerList"
        else -> route
    }

    private fun waitForNode(what: String, matcher: SemanticsMatcher) = PreviewDriver.waitFor(what) { exists(matcher) }

    private fun waitForNoNode(what: String, matcher: SemanticsMatcher) = PreviewDriver.waitFor(what) { !exists(matcher) }

    private fun exists(matcher: SemanticsMatcher): Boolean =
        compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()

    private companion object {
        val SERVER_LIST_ALONE = Stack(current = "ServerList", previous = null, screens = 1)
        val QUIET = 5.seconds
        const val POLL_MILLIS = 250L
    }
}
