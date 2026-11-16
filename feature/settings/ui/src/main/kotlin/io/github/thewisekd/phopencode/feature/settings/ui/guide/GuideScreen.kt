package io.github.thewisekd.phopencode.feature.settings.ui.guide

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.thewisekd.phopencode.core.navigation.SettingsRoute
import io.github.thewisekd.phopencode.core.ui.component.PhopencodeScaffold
import io.github.thewisekd.phopencode.core.ui.icon.PhopencodeIcons
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.settings.logic.contract.GuideIntent
import io.github.thewisekd.phopencode.feature.settings.logic.contract.GuidePage
import io.github.thewisekd.phopencode.feature.settings.logic.contract.GuideState
import io.github.thewisekd.phopencode.feature.settings.ui.R

private val ICON_CIRCLE = 72.dp
private val ICON_SIZE = 36.dp
private val DOT_SIZE = 8.dp

/**
 * The usage guide, `SettingsRoute.Guide`. Draws [state] and sends [onIntent]; it decides nothing.
 *
 * - A pager of [GuidePage]s, one at a time: an icon, a title (a heading) and a few lines, in a column
 *   that scrolls on a small screen or with large text.
 * - Skip in the top bar on every page; under the pages, the page indicator, then Back (not on the
 *   first page) and Next, or Start on the last page. Every button is at least a touch target high.
 * - The indicator reads out "Page n of m" and is a polite live region, so TalkBack says the new
 *   position when the page changes.
 * - The pager follows [GuideState.page]. Any other settled page, from a swipe or from TalkBack's
 *   page actions, sends [GuideIntent.ShowPage] once; pages the screen scrolled to itself send
 *   nothing, so the state and the pager never chase each other.
 * - The system back sends [GuideIntent.Back]. While finishing, nothing can be pressed and the
 *   system back is held, so the guide does not leave before it is recorded as seen.
 *
 * The root keeps the `placeholder:<route>` tag the app's navigation test looks for.
 */
@Composable
fun GuideScreen(
    route: SettingsRoute.Guide,
    state: GuideState,
    onIntent: (GuideIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val enabled = !state.finishing
    BackHandler { if (enabled) onIntent(GuideIntent.Back) }
    PhopencodeScaffold(
        title = stringResource(R.string.settings_guide_title),
        modifier = modifier.fillMaxSize().testTag("placeholder:$route"),
        actions = {
            TextButton(
                onClick = { onIntent(GuideIntent.Skip) },
                enabled = enabled,
                modifier = Modifier.heightIn(min = PhopencodeTheme.spacing.touchTarget).testTag(GUIDE_SKIP_TAG),
            ) {
                Text(stringResource(R.string.settings_guide_skip))
            }
        },
        bottomBar = { GuideBar(state, onIntent) },
    ) { padding ->
        GuidePager(state, onIntent, Modifier.fillMaxSize().padding(padding))
    }
}

@Composable
private fun GuidePager(state: GuideState, onIntent: (GuideIntent) -> Unit, modifier: Modifier) {
    val pages = GuidePage.entries
    val page = state.page.ordinal
    val pagerState = rememberPagerState(initialPage = page) { pages.size }
    val sync = remember { PagerSync(target = page) }
    val send by rememberUpdatedState(onIntent)

    // A new page from the state: from now until the pager is there, its settling is the screen's own.
    SideEffect {
        if (sync.target != page) {
            sync.target = page
            sync.scrolling = true
        }
    }
    LaunchedEffect(pagerState, page) {
        try {
            if (pagerState.currentPage != page || pagerState.currentPageOffsetFraction != 0f) {
                pagerState.animateScrollToPage(page)
            }
        } finally {
            // A newer target keeps the flag: its own effect clears it.
            if (sync.target == page) sync.scrolling = false
        }
    }
    // Any other resting page, by a swipe or by TalkBack's page actions, is told to the logic once.
    // Checked each time the pager comes to rest, not only when the page changes: a swipe that cuts a
    // button's scroll short can settle back on the page it started from.
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.isScrollInProgress to pagerState.settledPage }.collect { (moving, settled) ->
            if (!moving && !sync.scrolling && settled != sync.target) {
                sync.target = settled
                send(GuideIntent.ShowPage(pages[settled]))
            }
        }
    }
    HorizontalPager(
        state = pagerState,
        modifier = modifier.testTag(GUIDE_PAGER_TAG),
        userScrollEnabled = !state.finishing,
    ) { index ->
        GuidePageContent(pages[index])
    }
}

@Composable
private fun GuidePageContent(page: GuidePage) {
    val spacing = PhopencodeTheme.spacing
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .testTag(guidePageTag(page))
            .padding(horizontal = spacing.xl, vertical = spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing.lg),
    ) {
        Surface(
            modifier = Modifier.size(ICON_CIRCLE),
            shape = CircleShape,
            color = colors.secondaryContainer,
            contentColor = colors.onSecondaryContainer,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(imageVector = page.icon, contentDescription = null, modifier = Modifier.size(ICON_SIZE))
            }
        }
        Text(
            text = stringResource(page.titleRes),
            modifier = Modifier.fillMaxWidth().semantics { heading() },
            color = colors.onSurface,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(page.bodyRes),
            modifier = Modifier.fillMaxWidth().testTag(guideBodyTag(page)),
            color = colors.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

/** The page indicator, then Back and Next or Start; always in reach under the pages. */
@Composable
private fun GuideBar(state: GuideState, onIntent: (GuideIntent) -> Unit) {
    val spacing = PhopencodeTheme.spacing
    val enabled = !state.finishing
    Surface(tonalElevation = spacing.xxs) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = spacing.lg, vertical = spacing.sm),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            PageIndicator(state)
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (!state.isFirst) {
                    TextButton(
                        onClick = { onIntent(GuideIntent.Back) },
                        enabled = enabled,
                        modifier = Modifier.heightIn(min = spacing.touchTarget).testTag(GUIDE_BACK_TAG),
                    ) {
                        Text(stringResource(R.string.settings_guide_back))
                    }
                }
                Spacer(Modifier.weight(1f))
                if (state.isLast) {
                    Button(
                        onClick = { onIntent(GuideIntent.Start) },
                        enabled = enabled,
                        modifier = Modifier.heightIn(min = spacing.touchTarget).testTag(GUIDE_START_TAG),
                    ) {
                        Text(stringResource(R.string.settings_guide_start))
                    }
                } else {
                    Button(
                        onClick = { onIntent(GuideIntent.Next) },
                        enabled = enabled,
                        modifier = Modifier.heightIn(min = spacing.touchTarget).testTag(GUIDE_NEXT_TAG),
                    ) {
                        Text(stringResource(R.string.settings_guide_next))
                    }
                }
            }
        }
    }
}

/** One dot per page, the current one filled; read out as one "Page n of m". */
@Composable
private fun PageIndicator(state: GuideState) {
    val colors = MaterialTheme.colorScheme
    val position = stringResource(R.string.settings_guide_position, state.position, state.pageCount)
    Row(
        modifier = Modifier
            .testTag(GUIDE_INDICATOR_TAG)
            .clearAndSetSemantics {
                contentDescription = position
                liveRegion = LiveRegionMode.Polite
            },
        horizontalArrangement = Arrangement.spacedBy(DOT_SIZE),
    ) {
        for (page in GuidePage.entries) {
            Box(
                modifier = Modifier
                    .size(DOT_SIZE)
                    .clip(CircleShape)
                    .background(if (page == state.page) colors.primary else colors.outlineVariant),
            )
        }
    }
}

private val GuidePage.icon: ImageVector
    get() = when (this) {
        GuidePage.Welcome -> PhopencodeIcons.Sparkle
        GuidePage.OnYourServer -> PhopencodeIcons.Terminal
        GuidePage.AddServer -> PhopencodeIcons.Server
        GuidePage.SessionsAndChat -> PhopencodeIcons.Chat
        GuidePage.FilesAndGit -> PhopencodeIcons.Git
        GuidePage.Notifications -> PhopencodeIcons.Info
        GuidePage.LivePreview -> PhopencodeIcons.Search
        GuidePage.Done -> PhopencodeIcons.Check
    }

@get:StringRes
private val GuidePage.titleRes: Int
    get() = when (this) {
        GuidePage.Welcome -> R.string.settings_guide_welcome_title
        GuidePage.OnYourServer -> R.string.settings_guide_server_title
        GuidePage.AddServer -> R.string.settings_guide_add_server_title
        GuidePage.SessionsAndChat -> R.string.settings_guide_sessions_title
        GuidePage.FilesAndGit -> R.string.settings_guide_files_git_title
        GuidePage.Notifications -> R.string.settings_guide_notifications_title
        GuidePage.LivePreview -> R.string.settings_guide_preview_title
        GuidePage.Done -> R.string.settings_guide_done_title
    }

@get:StringRes
private val GuidePage.bodyRes: Int
    get() = when (this) {
        GuidePage.Welcome -> R.string.settings_guide_welcome_body
        GuidePage.OnYourServer -> R.string.settings_guide_server_body
        GuidePage.AddServer -> R.string.settings_guide_add_server_body
        GuidePage.SessionsAndChat -> R.string.settings_guide_sessions_body
        GuidePage.FilesAndGit -> R.string.settings_guide_files_git_body
        GuidePage.Notifications -> R.string.settings_guide_notifications_body
        GuidePage.LivePreview -> R.string.settings_guide_preview_body
        GuidePage.Done -> R.string.settings_guide_done_body
    }

/**
 * What the pager was last told to show, and whether it is still on its way there. Plain fields, not
 * state: they are written in [SideEffect] and the pager's effects on the main thread, and only read
 * there, so nothing recomposes for them.
 */
private class PagerSync(var target: Int) {
    var scrolling: Boolean = false
}
