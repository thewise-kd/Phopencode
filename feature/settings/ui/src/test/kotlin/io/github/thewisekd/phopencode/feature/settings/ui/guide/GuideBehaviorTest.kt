package io.github.thewisekd.phopencode.feature.settings.ui.guide

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import io.github.thewisekd.phopencode.core.navigation.SettingsRoute
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.settings.logic.contract.GuideIntent
import io.github.thewisekd.phopencode.feature.settings.logic.contract.GuidePage
import io.github.thewisekd.phopencode.feature.settings.logic.contract.GuideState
import io.github.thewisekd.phopencode.feature.settings.ui.LARGE_FONT_SCALE
import io.github.thewisekd.phopencode.feature.settings.ui.prefs.assertTouchSizeIsAtLeast
import io.github.thewisekd.phopencode.feature.settings.ui.setThemedAt
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * The guide sends the contract's intents and decides nothing: Back, Next, Skip on every page and
 * Start on the last, the system back, and swipes; and it shows the page its state names.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class GuideBehaviorTest {
    @get:Rule
    val compose = createComposeRule()

    private val heading = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)

    @Test
    fun `next moves on`() {
        val sent = compose.showGuide(GuidePage.Welcome)

        compose.onNodeWithTag(GUIDE_NEXT_TAG).performClick()

        assertEquals(listOf<GuideIntent>(GuideIntent.Next), sent)
    }

    @Test
    fun `the first page has no back button`() {
        compose.showGuide(GuidePage.Welcome)

        compose.onNodeWithTag(GUIDE_BACK_TAG).assertDoesNotExist()
        compose.onNodeWithTag(GUIDE_SKIP_TAG).assertIsDisplayed()
    }

    @Test
    fun `back goes back`() {
        val sent = compose.showGuide(GuidePage.AddServer)

        compose.onNodeWithTag(GUIDE_BACK_TAG).performClick()

        assertEquals(listOf<GuideIntent>(GuideIntent.Back), sent)
    }

    @Test
    fun `skip is on every page, start only on the last`() {
        val driven = Driven()
        compose.showDriven(driven)
        for (page in GuidePage.entries) {
            compose.runOnIdle { driven.state = GuideState(page = page) }
            compose.waitForIdle()

            compose.onNodeWithTag(GUIDE_SKIP_TAG).assertIsDisplayed().assertIsEnabled()
            if (page == GuidePage.Done) {
                compose.onNodeWithTag(GUIDE_START_TAG).assertIsDisplayed()
                compose.onNodeWithTag(GUIDE_NEXT_TAG).assertDoesNotExist()
            } else {
                compose.onNodeWithTag(GUIDE_NEXT_TAG).assertIsDisplayed()
                compose.onNodeWithTag(GUIDE_START_TAG).assertDoesNotExist()
            }
        }
    }

    @Test
    fun `skip leaves the guide`() {
        val sent = compose.showGuide(GuidePage.Notifications)

        compose.onNodeWithTag(GUIDE_SKIP_TAG).performClick()

        assertEquals(listOf<GuideIntent>(GuideIntent.Skip), sent)
    }

    @Test
    fun `start ends the guide`() {
        val sent = compose.showGuide(GuidePage.Done)

        compose.onNodeWithTag(GUIDE_START_TAG).performClick()

        assertEquals(listOf<GuideIntent>(GuideIntent.Start), sent)
    }

    @Test
    fun `while finishing nothing can be pressed`() {
        compose.showGuide(state = GuideState(page = GuidePage.Done, finishing = true))

        compose.onNodeWithTag(GUIDE_BACK_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(GUIDE_SKIP_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(GUIDE_START_TAG).assertIsNotEnabled()
    }

    @Test
    fun `every button is big enough to tap`() {
        compose.showGuide(GuidePage.FilesAndGit)

        for (tag in listOf(GUIDE_BACK_TAG, GUIDE_NEXT_TAG, GUIDE_SKIP_TAG)) {
            compose.onNodeWithTag(tag).assertTouchSizeIsAtLeast()
        }
    }

    @Test
    fun `the start button is big enough to tap`() {
        compose.showGuide(GuidePage.Done)

        compose.onNodeWithTag(GUIDE_START_TAG).assertTouchSizeIsAtLeast()
    }

    @Test
    fun `the system back sends back`() {
        val sent = mutableListOf<GuideIntent>()
        var dispatcher: OnBackPressedDispatcher? = null
        compose.setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            PhopencodeTheme { GuideScreen(SettingsRoute.Guide, GuideState(page = GuidePage.SessionsAndChat), onIntent = { sent += it }) }
        }

        compose.runOnIdle { dispatcher!!.onBackPressed() }

        assertEquals(listOf<GuideIntent>(GuideIntent.Back), sent)
    }

    @Test
    fun `a swipe shows the next or the previous page`() {
        val driven = Driven(GuideState(page = GuidePage.AddServer))
        compose.showDriven(driven)

        compose.onNodeWithTag(GUIDE_PAGER_TAG).performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals(GuideIntent.ShowPage(GuidePage.SessionsAndChat), driven.sent.last())
        compose.onNodeWithTag(guidePageTag(GuidePage.SessionsAndChat)).assertIsDisplayed()

        compose.onNodeWithTag(GUIDE_PAGER_TAG).performTouchInput { swipeRight() }
        compose.waitForIdle()
        assertEquals(GuideIntent.ShowPage(GuidePage.AddServer), driven.sent.last())
    }

    @Test
    fun `paging by accessibility actions tells the logic and moves the indicator`() {
        val driven = Driven(GuideState(page = GuidePage.AddServer))
        compose.showDriven(driven)

        compose.onNodeWithTag(GUIDE_PAGER_TAG).performSemanticsAction(SemanticsActions.PageRight)
        compose.waitForIdle()

        assertEquals(listOf<GuideIntent>(GuideIntent.ShowPage(GuidePage.SessionsAndChat)), driven.sent)
        compose.onNodeWithTag(guidePageTag(GuidePage.SessionsAndChat)).assertIsDisplayed()
        compose.onNodeWithTag(GUIDE_INDICATOR_TAG).assertContentDescriptionEquals("Page 4 of 8")

        compose.onNodeWithTag(GUIDE_PAGER_TAG).performSemanticsAction(SemanticsActions.PageLeft)
        compose.waitForIdle()

        assertEquals(GuideIntent.ShowPage(GuidePage.AddServer), driven.sent.last())
        assertEquals(2, driven.sent.size, "each page change is told once: ${driven.sent}")
        compose.onNodeWithTag(GUIDE_INDICATOR_TAG).assertContentDescriptionEquals("Page 3 of 8")
    }

    @Test
    fun `a swipe back that cuts next short leaves state, pager and indicator in agreement`() {
        val driven = Driven()
        compose.showDriven(driven)
        compose.mainClock.autoAdvance = false

        compose.onNodeWithTag(GUIDE_NEXT_TAG).performClick()
        compose.mainClock.advanceTimeBy(FRAMES_INTO_THE_SCROLL)
        compose.onNodeWithTag(GUIDE_PAGER_TAG).performTouchInput { swipeRight() }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()

        assertEquals(listOf(GuideIntent.Next, GuideIntent.ShowPage(GuidePage.Welcome)), driven.sent)
        assertEquals(GuidePage.Welcome, driven.state.page)
        compose.onNodeWithTag(guidePageTag(GuidePage.Welcome)).assertIsDisplayed()
        compose.onNodeWithTag(GUIDE_INDICATOR_TAG).assertContentDescriptionEquals("Page 1 of 8")
    }

    @Test
    fun `quick buttons are not undone by the pager`() {
        val driven = Driven()
        compose.showDriven(driven)

        compose.runOnIdle {
            driven.onIntent(GuideIntent.Next)
            driven.onIntent(GuideIntent.Next)
            driven.onIntent(GuideIntent.Next)
        }
        compose.waitForIdle()

        assertEquals(List<GuideIntent>(3) { GuideIntent.Next }, driven.sent)
        assertEquals(GuidePage.SessionsAndChat, driven.state.page)
        compose.onNodeWithTag(guidePageTag(GuidePage.SessionsAndChat)).assertIsDisplayed()
    }

    @Test
    fun `the pager follows the state`() {
        val driven = Driven()
        compose.showDriven(driven)

        compose.onNodeWithTag(GUIDE_NEXT_TAG).performClick()
        compose.onNodeWithTag(GUIDE_NEXT_TAG).performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(guidePageTag(GuidePage.AddServer)).assertIsDisplayed()
        compose.onNodeWithTag(guidePageTag(GuidePage.Welcome)).assertDoesNotExist()
        assertEquals(listOf<GuideIntent>(GuideIntent.Next, GuideIntent.Next), driven.sent)
    }

    @Test
    fun `each page's title is a heading`() {
        compose.showGuide(GuidePage.LivePreview)

        val onPage = hasAnyAncestor(hasTestTag(guidePageTag(GuidePage.LivePreview)))
        compose.onNode(hasText("Live preview") and onPage).assert(heading)
    }

    /** The smallest phone screen, at the largest font: the text still scrolls into view. */
    @Test
    @Config(qualifiers = "en-w320dp-h480dp")
    fun `the text scrolls on a small screen`() {
        compose.setThemedAt(LARGE_FONT_SCALE) {
            GuideScreen(SettingsRoute.Guide, GuideState(page = GuidePage.OnYourServer), onIntent = {})
        }

        compose.onNodeWithTag(guideBodyTag(GuidePage.OnYourServer)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(GUIDE_NEXT_TAG).assertIsDisplayed()
        compose.onNodeWithTag(GUIDE_SKIP_TAG).assertIsDisplayed()
    }
}

/** Long enough for Next's scroll to have started, far shorter than the whole scroll. */
private const val FRAMES_INTO_THE_SCROLL = 48L
