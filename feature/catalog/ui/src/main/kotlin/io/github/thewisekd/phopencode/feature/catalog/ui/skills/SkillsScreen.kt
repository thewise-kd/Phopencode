package io.github.thewisekd.phopencode.feature.catalog.ui.skills

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import io.github.thewisekd.phopencode.core.model.Skill
import io.github.thewisekd.phopencode.core.navigation.CatalogRoute
import io.github.thewisekd.phopencode.core.ui.component.EmptyState
import io.github.thewisekd.phopencode.core.ui.component.ErrorBanner
import io.github.thewisekd.phopencode.core.ui.component.PhopencodeScaffold
import io.github.thewisekd.phopencode.core.ui.icon.PhopencodeIcons
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.SkillsIntent
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.SkillsState
import io.github.thewisekd.phopencode.feature.catalog.ui.R

/**
 * The server's skills. Draws [state] and sends [onIntent]; it decides nothing itself: which skills
 * match the search is the logic's call ([SkillsState.shown]).
 *
 * Above the cards sits the search field, once there are skills to search. Each skill is a rounded
 * card, as in the session and server lists, with its name, its description and whether opencode uses
 * it on its own; a tap opens its details. Pulling the list down, or the reload button for those who
 * cannot pull, reads the list from the server again. Progress is shown as still messages, never as a
 * spinner: an endless animation keeps Robolectric's `idle()` in the app's tests from returning.
 *
 * A skill's name, description and path are user data: they are drawn, never logged.
 *
 * The root keeps the `placeholder:<route>` tag the app's navigation test looks for.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillsScreen(
    route: CatalogRoute.Skills,
    state: SkillsState,
    onIntent: (SkillsIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val canReload = !state.loading && !state.refreshing
    PhopencodeScaffold(
        title = stringResource(R.string.catalog_skills_title),
        modifier = modifier.fillMaxSize().testTag("placeholder:$route"),
        onBack = { onIntent(SkillsIntent.Close) },
        actions = {
            IconButton(onClick = { onIntent(SkillsIntent.Reload) }, enabled = canReload) {
                Icon(
                    imageVector = PhopencodeIcons.Refresh,
                    contentDescription = stringResource(R.string.catalog_skills_reload),
                )
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            state.error?.let { error ->
                ErrorBanner(
                    modifier = Modifier.padding(
                        horizontal = PhopencodeTheme.spacing.lg,
                        vertical = PhopencodeTheme.spacing.sm,
                    ),
                    message = stringResource(error.listMessageRes),
                    onRetry = if (error.canRetry) ({ onIntent(SkillsIntent.Reload) }) else null,
                )
            }
            if (state.refreshing) {
                StillProgress(stringResource(R.string.catalog_skills_refreshing), Modifier.fillMaxWidth())
            }
            val pull = rememberPullToRefreshState()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .pullToRefresh(
                        isRefreshing = state.refreshing,
                        state = pull,
                        enabled = canReload,
                        onRefresh = { onIntent(SkillsIntent.Reload) },
                    ),
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().testTag(SKILLS_LIST_TAG),
                    contentPadding = PaddingValues(bottom = PhopencodeTheme.spacing.lg),
                ) {
                    if (state.skills.isNotEmpty()) search(state.query, onIntent)
                    when {
                        state.shown.isNotEmpty() -> skills(state.shown, onIntent)
                        state.loading && state.skills.isEmpty() -> item(key = "loading") {
                            StillProgress(stringResource(R.string.catalog_skills_loading), Modifier.fillParentMaxSize())
                        }
                        state.isEmpty -> item(key = "empty") {
                            EmptyState(
                                modifier = Modifier.fillParentMaxSize(),
                                title = stringResource(R.string.catalog_skills_empty_title),
                                message = stringResource(R.string.catalog_skills_empty_message),
                                icon = PhopencodeIcons.Sparkle,
                            )
                        }
                        state.skills.isNotEmpty() -> item(key = "no-match") {
                            EmptyState(
                                modifier = Modifier.fillParentMaxSize(),
                                title = stringResource(R.string.catalog_skills_no_match_title),
                                message = stringResource(R.string.catalog_skills_no_match_message),
                                icon = PhopencodeIcons.Search,
                            )
                        }
                    }
                }
                PullIndicator(pull = pull, refreshing = state.refreshing, modifier = Modifier.align(Alignment.TopCenter))
            }
        }
    }
}

/**
 * The pull indicator: a ring that fills as the list is pulled, and a still reload icon while the
 * read runs, in place of Material's spinner, which never stops.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PullIndicator(
    pull: PullToRefreshState,
    refreshing: Boolean,
    modifier: Modifier = Modifier,
) {
    PullToRefreshDefaults.IndicatorBox(state = pull, isRefreshing = refreshing, modifier = modifier) {
        if (refreshing) {
            Icon(
                imageVector = PhopencodeIcons.Refresh,
                contentDescription = null,
                tint = PullToRefreshDefaults.indicatorColor,
            )
        } else {
            CircularProgressIndicator(
                progress = { pull.distanceFraction.coerceIn(0f, 1f) },
                modifier = Modifier.size(PhopencodeTheme.spacing.xl),
                color = PullToRefreshDefaults.indicatorColor,
            )
        }
    }
}

private fun LazyListScope.search(query: String, onIntent: (SkillsIntent) -> Unit) {
    item(key = "search") {
        OutlinedTextField(
            value = query,
            onValueChange = { onIntent(SkillsIntent.Search(it)) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PhopencodeTheme.spacing.lg, vertical = PhopencodeTheme.spacing.xs),
            label = { Text(stringResource(R.string.catalog_skills_search)) },
            leadingIcon = { Icon(imageVector = PhopencodeIcons.Search, contentDescription = null) },
            shape = MaterialTheme.shapes.large,
            singleLine = true,
        )
    }
}

private fun LazyListScope.skills(shown: List<Skill>, onIntent: (SkillsIntent) -> Unit) {
    items(shown, key = { "skill:${it.id}" }) { skill ->
        SkillCard(
            skill = skill,
            onOpen = { onIntent(SkillsIntent.OpenSkill(skill.id)) },
            modifier = Modifier.padding(horizontal = PhopencodeTheme.spacing.lg, vertical = PhopencodeTheme.spacing.xs),
        )
    }
}

/**
 * One skill as a rounded card: its name, its description in at most two lines, and a chip when
 * opencode uses it on its own. The trailing chevron only decorates: the click label says what a tap
 * does.
 */
@Composable
private fun SkillCard(skill: Skill, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val spacing = PhopencodeTheme.spacing
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.medium
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = spacing.touchTarget)
            .clip(shape)
            .background(colors.surfaceContainer)
            .clickable(
                onClickLabel = stringResource(R.string.catalog_skills_open),
                role = Role.Button,
                onClick = onOpen,
            )
            .testTag(skillCardTag(skill.id))
            .padding(start = spacing.lg, end = spacing.sm, top = spacing.md, bottom = spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            Text(
                text = skill.name,
                color = colors.onSurface,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            skill.description?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    color = colors.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (skill.autoInvoke) SmallChip(stringResource(R.string.catalog_skills_auto))
        }
        Icon(
            imageVector = PhopencodeIcons.ChevronRight,
            contentDescription = null,
            tint = colors.onSurfaceVariant,
        )
    }
}

/** A quiet tinted chip. */
@Composable
internal fun SmallChip(text: String, modifier: Modifier = Modifier) {
    val spacing = PhopencodeTheme.spacing
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = spacing.sm, vertical = spacing.xs),
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
        )
    }
}
