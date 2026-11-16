package io.github.thewisekd.phopencode.feature.settings.ui.guide

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import io.github.thewisekd.phopencode.core.navigation.SettingsRoute
import io.github.thewisekd.phopencode.feature.settings.logic.contract.GuideIntent
import io.github.thewisekd.phopencode.feature.settings.logic.contract.GuidePage
import io.github.thewisekd.phopencode.feature.settings.logic.contract.GuideState
import io.github.thewisekd.phopencode.feature.settings.ui.prefs.setThemedContent

/** The guide showing [state], or [page] on a fresh guide; returns the intents it sends, in order. */
internal fun ComposeContentTestRule.showGuide(
    page: GuidePage = GuidePage.Welcome,
    state: GuideState = GuideState(page = page),
): List<GuideIntent> {
    val sent = mutableListOf<GuideIntent>()
    setThemedContent { GuideScreen(SettingsRoute.Guide, state, onIntent = { sent += it }) }
    return sent
}

/**
 * The guide driven like its presenter drives it: each intent is recorded and moves [Driven.state]
 * the way the contract says, so the pager follows. Finishing only sets `finishing`.
 */
internal class Driven(initial: GuideState = GuideState()) {
    var state by mutableStateOf(initial)
    val sent = mutableListOf<GuideIntent>()

    fun onIntent(intent: GuideIntent) {
        sent += intent
        val pages = GuidePage.entries
        state = when (intent) {
            GuideIntent.Next -> state.copy(page = pages[(state.page.ordinal + 1).coerceAtMost(pages.lastIndex)])
            GuideIntent.Back ->
                if (state.isFirst) state.copy(finishing = true) else state.copy(page = pages[state.page.ordinal - 1])
            is GuideIntent.ShowPage -> state.copy(page = intent.page)
            GuideIntent.Skip, GuideIntent.Start -> state.copy(finishing = true)
        }
    }
}

internal fun ComposeContentTestRule.showDriven(driven: Driven) {
    setThemedContent { GuideScreen(SettingsRoute.Guide, driven.state, onIntent = driven::onIntent) }
}

/**
 * Shows every page of [pages] in turn, each with its title and its text, scrolled into view inside
 * that page; the text holds every fact given.
 */
internal fun ComposeContentTestRule.assertPages(pages: Map<GuidePage, Pair<String, List<String>>>) {
    val driven = Driven()
    showDriven(driven)
    for ((page, content) in pages) {
        val (title, facts) = content
        runOnIdle { driven.state = GuideState(page = page) }
        waitForIdle()

        val onPage = hasAnyAncestor(hasTestTag(guidePageTag(page)))
        onNodeWithTag(guidePageTag(page)).assertIsDisplayed()
        onNode(hasText(title) and onPage).performScrollTo().assertIsDisplayed()
        val body = onNodeWithTag(guideBodyTag(page)).performScrollTo().assertIsDisplayed()
        for (fact in facts) body.assert(hasText(fact, substring = true))
    }
}
