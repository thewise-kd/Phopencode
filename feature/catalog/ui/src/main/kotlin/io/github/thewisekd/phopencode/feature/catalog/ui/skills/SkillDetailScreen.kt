package io.github.thewisekd.phopencode.feature.catalog.ui.skills

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import io.github.thewisekd.phopencode.core.markdown.MarkdownView
import io.github.thewisekd.phopencode.core.model.Skill
import io.github.thewisekd.phopencode.core.navigation.CatalogRoute
import io.github.thewisekd.phopencode.core.ui.component.ErrorBanner
import io.github.thewisekd.phopencode.core.ui.component.PhopencodeScaffold
import io.github.thewisekd.phopencode.core.ui.icon.PhopencodeIcons
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.SkillDetailIntent
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.SkillDetailState
import io.github.thewisekd.phopencode.feature.catalog.ui.R

/**
 * One skill. Draws [state] and sends [onIntent]; it decides nothing itself.
 *
 * The top bar names the skill, or shows the route's ID until it is found. Under it sit the skill's
 * description, where its file is ([SkillDetailState.sourcePath], as the logic shortened it), whether
 * opencode uses it on its own, and the button that starts a new session with it; then its text,
 * rendered as Markdown by core/markdown. The button shows only once the skill is loaded.
 *
 * Loading is a still message, never a spinner: an endless animation keeps Robolectric's `idle()` in
 * the app's tests from returning.
 *
 * A skill's name, description, path and text are user data: they are drawn, never logged.
 *
 * The root keeps the `placeholder:<route>` tag the app's navigation test looks for.
 */
@Composable
fun SkillDetailScreen(
    route: CatalogRoute.SkillDetail,
    state: SkillDetailState,
    onIntent: (SkillDetailIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val skill = state.skill
    PhopencodeScaffold(
        title = skill?.name ?: state.skillId,
        modifier = modifier.fillMaxSize().testTag("placeholder:$route"),
        onBack = { onIntent(SkillDetailIntent.Close) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            state.error?.let { error ->
                ErrorBanner(
                    modifier = Modifier.padding(
                        horizontal = PhopencodeTheme.spacing.lg,
                        vertical = PhopencodeTheme.spacing.sm,
                    ),
                    message = stringResource(error.detailMessageRes),
                    onRetry = if (error.canRetry) ({ onIntent(SkillDetailIntent.Retry) }) else null,
                )
            }
            when {
                skill != null -> SkillBody(
                    skill = skill,
                    sourcePath = state.sourcePath,
                    onUse = { onIntent(SkillDetailIntent.UseInNewSession) },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
                state.loading -> StillProgress(
                    stringResource(R.string.catalog_skills_detail_loading),
                    Modifier.fillMaxWidth().weight(1f),
                )
            }
        }
    }
}

@Composable
private fun SkillBody(
    skill: Skill,
    sourcePath: String?,
    onUse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = PhopencodeTheme.spacing
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .testTag(SKILL_DETAIL_BODY_TAG)
            .padding(horizontal = spacing.lg, vertical = spacing.sm),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        skill.description?.takeIf { it.isNotBlank() }?.let {
            Text(text = it, color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
        }
        sourcePath?.let { SourceLine(it) }
        if (skill.autoInvoke) SmallChip(stringResource(R.string.catalog_skills_auto))
        Button(
            onClick = onUse,
            modifier = Modifier.fillMaxWidth().heightIn(min = spacing.touchTarget),
        ) {
            Icon(
                imageVector = PhopencodeIcons.Chat,
                contentDescription = null,
                modifier = Modifier.size(ButtonDefaults.IconSize),
            )
            Spacer(Modifier.width(spacing.sm))
            Text(stringResource(R.string.catalog_skills_detail_use))
        }
        HorizontalDivider(color = colors.outlineVariant)
        if (skill.content.isBlank()) {
            Text(
                text = stringResource(R.string.catalog_skills_detail_no_content),
                color = colors.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            MarkdownView(markdown = skill.content, modifier = Modifier.fillMaxWidth().testTag(SKILL_CONTENT_TAG))
        }
    }
}

/**
 * Where the skill's file is: a label over the path in monospace. One node for TalkBack, which reads
 * the label, then the path.
 */
@Composable
private fun SourceLine(path: String) {
    val spacing = PhopencodeTheme.spacing
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {}
            .testTag(SKILL_SOURCE_TAG),
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = PhopencodeIcons.File,
            contentDescription = null,
            tint = colors.onSurfaceVariant,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.catalog_skills_detail_source),
                color = colors.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                text = path,
                color = colors.onSurface,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
        }
    }
}
