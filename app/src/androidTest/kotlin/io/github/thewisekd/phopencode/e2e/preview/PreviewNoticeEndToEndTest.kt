package io.github.thewisekd.phopencode.e2e.preview

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.thewisekd.phopencode.MainActivity
import io.github.thewisekd.phopencode.core.navigation.PreviewRoute
import io.github.thewisekd.phopencode.core.navigation.SessionsRoute
import io.github.thewisekd.phopencode.core.storage_api.SettingsRepository
import io.github.thewisekd.phopencode.e2e.E2eApp
import io.github.thewisekd.phopencode.e2e.E2eEnvironment
import io.github.thewisekd.phopencode.e2e.FirstRunDoneRule
import io.github.thewisekd.phopencode.e2e.appSettings
import io.github.thewisekd.phopencode.e2e.awaitSettings
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The live preview's first-visit notice (P13.24), end to end (P14.3): while the notice was never
 * accepted, the port picker opens with the notice over it; "Not now" leaves the picker and the
 * notice comes back on the next visit; "I have read and understood" hides it, the port list can be
 * used, and a later visit opens the picker without it.
 *
 * The shared [FirstRunDoneRule], in its `noticePending` mode, deletes all data and marks only the
 * guide seen before the activity starts, so that the activity's first read of the settings finds
 * the guide seen and the notice not accepted: the guide stays away while the notice is under test.
 * Once the test is over the rule accepts the notice again for the tests after it.
 */
@RunWith(AndroidJUnit4::class)
class PreviewNoticeEndToEndTest {
    @get:Rule(order = 0)
    val firstRun = FirstRunDoneRule(noticePending = true)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    private lateinit var settings: SettingsRepository
    private lateinit var app: E2eApp
    private lateinit var preview: PreviewDriver

    @Before
    fun setUp() = runBlocking {
        val env = E2eEnvironment.fromArguments()
        val port = previewPortArgument()
        settings = appSettings()
        val start = settings.observe().first()
        assertTrue("The guide is not marked seen before the launch", start.guideSeen)
        assertFalse("The notice is already accepted before the test", start.previewNoticeAccepted)
        app = E2eApp(env)
        app.setUp()
        preview = PreviewDriver(app, compose, port)
    }

    @After
    fun tearDown() = runBlocking {
        if (::app.isInitialized) app.tearDown()
    }

    @Test
    fun theNoticeShowsUntilItIsAcceptedAndNeverAfter() = runBlocking<Unit> {
        app.connect()

        // The first visit: the notice covers the port picker, whose list cannot be reached.
        preview.openPortPicker()
        awaitNotice()
        assertFalse("The port list is usable under the notice", preview.exists(hasTestTag(PreviewTags.port(preview.fixturePort))))

        // "Not now" leaves the picker for the session list, without accepting.
        preview.click(PreviewTags.NOTICE_NOT_NOW)
        preview.waitForNoNode("the notice to close", hasTestTag(PreviewTags.NOTICE_DIALOG))
        PreviewDriver.waitFor("the session list after Not now") { showsSessionList() }
        assertFalse("Not now accepted the notice", settings.observe().first().previewNoticeAccepted)

        // The next visit shows it again.
        preview.openPortPicker()
        awaitNotice()

        // Accepting hides it for good, and the port list is there to use.
        preview.click(PreviewTags.NOTICE_ACCEPT)
        preview.waitForNoNode("the notice to close", hasTestTag(PreviewTags.NOTICE_DIALOG))
        preview.waitForNoNode("the blocker to go", hasTestTag(PreviewTags.NOTICE_BLOCKER))
        awaitSettings(settings, "the notice stored as accepted") { it.previewNoticeAccepted }
        assertTrue("Accepting left the port picker", showsPortPicker())
        preview.waitForNode("the fixture's port in the list", hasTestTag(PreviewTags.port(preview.fixturePort)))

        // A later visit: the list is usable at once, with no notice over it. The list's rows are
        // hidden from the tests while the notice blocks, so a row found means it did not block.
        preview.back()
        PreviewDriver.waitFor("the session list after leaving the picker") { showsSessionList() }
        preview.openPortPicker()
        preview.waitForNode("the fixture's port in the list on a later visit", hasTestTag(PreviewTags.port(preview.fixturePort)))
        assertFalse("The notice showed again", preview.exists(hasTestTag(PreviewTags.NOTICE_DIALOG)))
        assertFalse("The notice blocked again", preview.exists(hasTestTag(PreviewTags.NOTICE_BLOCKER)))
        preview.openFixtureFromList()
        preview.awaitFixturePage()
    }

    private fun awaitNotice() {
        preview.waitForNode("the preview notice", hasTestTag(PreviewTags.NOTICE_DIALOG))
        assertTrue("The notice does not block the picker", preview.exists(hasTestTag(PreviewTags.NOTICE_BLOCKER)))
        assertTrue("The notice is not over the port picker", showsPortPicker())
    }

    private fun showsPortPicker(): Boolean = currentIs { it.hasRoute<PreviewRoute.Ports>() }

    private fun showsSessionList(): Boolean = currentIs { it.hasRoute<SessionsRoute.SessionList>() }

    private fun currentIs(test: (NavDestination) -> Boolean): Boolean {
        val result = AtomicBoolean(false)
        compose.activityRule.scenario.onActivity { activity ->
            result.set(activity.navController?.currentDestination?.let(test) == true)
        }
        return result.get()
    }
}
