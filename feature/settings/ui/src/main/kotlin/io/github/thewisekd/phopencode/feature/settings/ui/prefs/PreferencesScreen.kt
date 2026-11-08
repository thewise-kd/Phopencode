package io.github.thewisekd.phopencode.feature.settings.ui.prefs

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.thewisekd.phopencode.core.navigation.SettingsRoute
import io.github.thewisekd.phopencode.core.storage_api.AppLanguage
import io.github.thewisekd.phopencode.core.storage_api.ThemePreference
import io.github.thewisekd.phopencode.core.ui.component.ConfirmDialog
import io.github.thewisekd.phopencode.core.ui.component.ErrorBanner
import io.github.thewisekd.phopencode.core.ui.component.PhopencodeScaffold
import io.github.thewisekd.phopencode.core.ui.icon.PhopencodeIcons
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.settings.logic.contract.DataDeletion
import io.github.thewisekd.phopencode.feature.settings.logic.contract.FileSizeProblem
import io.github.thewisekd.phopencode.feature.settings.logic.contract.PreferencesError
import io.github.thewisekd.phopencode.feature.settings.logic.contract.PreferencesIntent
import io.github.thewisekd.phopencode.feature.settings.logic.contract.PreferencesState
import io.github.thewisekd.phopencode.feature.settings.ui.R

private val ICON_CIRCLE = 40.dp
private val ICON_SIZE = 22.dp

/**
 * The preferences, `SettingsRoute.Preferences`. Draws [state] and sends [onIntent]; it decides
 * nothing itself: the presenter stores each change and applies the language at once.
 *
 * Sections, each a rounded card under a heading, in the style of the other lists:
 * - Language and Theme: radio groups; a tap sends the choice right away.
 * - File size limit: whole mebibytes; the contract's [FileSizeProblem] shows under the field.
 * - About: opens the licenses.
 * - Data: deleting everything only asks first ([PreferencesIntent.RequestDelete]); only the
 *   confirmation's own button sends [PreferencesIntent.ConfirmDelete]. While deleting, a still
 *   progress message replaces the button and nothing can be changed.
 *
 * Loading and deleting show a still message rather than core/ui's endless spinner, so the screen
 * comes to rest in the app's ActivityScenario tests (see `SettingsIdleTest`).
 *
 * The root keeps the `placeholder:<route>` tag the app's navigation test looks for.
 */
@Composable
fun PreferencesScreen(
    route: SettingsRoute.Preferences,
    state: PreferencesState,
    onIntent: (PreferencesIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    PhopencodeScaffold(
        title = stringResource(R.string.settings_prefs_title),
        modifier = modifier.fillMaxSize().testTag("placeholder:$route"),
        onBack = { onIntent(PreferencesIntent.Close) },
    ) { padding ->
        val content = Modifier.fillMaxSize().padding(padding)
        if (state.loading) {
            StillProgress(
                message = stringResource(R.string.settings_prefs_loading),
                modifier = content.testTag(PREFS_LOADING_TAG),
            )
        } else {
            PreferencesList(state, onIntent, content)
        }
    }
    if (state.deletion == DataDeletion.Confirming) {
        ConfirmDialog(
            title = stringResource(R.string.settings_prefs_delete_confirm_title),
            message = stringResource(R.string.settings_prefs_delete_confirm_message),
            onConfirm = { onIntent(PreferencesIntent.ConfirmDelete) },
            onDismiss = { onIntent(PreferencesIntent.CancelDelete) },
            modifier = Modifier.testTag(DELETE_DIALOG_TAG),
            confirmLabel = stringResource(R.string.settings_prefs_delete_confirm),
            destructive = true,
        )
    }
}

@Composable
private fun PreferencesList(state: PreferencesState, onIntent: (PreferencesIntent) -> Unit, modifier: Modifier) {
    val spacing = PhopencodeTheme.spacing
    val enabled = state.canEdit
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .testTag(PREFS_LIST_TAG)
            .padding(horizontal = spacing.lg, vertical = spacing.sm),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        state.error?.let { error -> PreferencesErrorBanner(error, onIntent) }

        SectionHeading(stringResource(R.string.settings_prefs_language_heading))
        OptionGroup(
            options = AppLanguage.entries,
            selected = state.language,
            enabled = enabled,
            groupTag = LANGUAGE_GROUP_TAG,
            optionTag = ::languageOptionTag,
            label = { stringResource(it.labelRes) },
            onSelect = { onIntent(PreferencesIntent.SetLanguage(it)) },
        )

        SectionHeading(stringResource(R.string.settings_prefs_theme_heading))
        OptionGroup(
            options = ThemePreference.entries,
            selected = state.theme,
            enabled = enabled,
            groupTag = THEME_GROUP_TAG,
            optionTag = ::themeOptionTag,
            label = { stringResource(it.labelRes) },
            onSelect = { onIntent(PreferencesIntent.SetTheme(it)) },
        )

        SectionHeading(stringResource(R.string.settings_prefs_file_size_heading))
        Card { FileSizeField(state, enabled, onIntent) }

        SectionHeading(stringResource(R.string.settings_prefs_about_heading))
        LicensesEntry(enabled = enabled, onClick = { onIntent(PreferencesIntent.OpenLicenses) })

        SectionHeading(stringResource(R.string.settings_prefs_data_heading))
        DeleteDataCard(state, onIntent)
    }
}

@Composable
private fun PreferencesErrorBanner(error: PreferencesError, onIntent: (PreferencesIntent) -> Unit) {
    val dismiss = { onIntent(PreferencesIntent.DismissError) }
    when (error) {
        PreferencesError.SaveFailed -> ErrorBanner(
            message = stringResource(R.string.settings_prefs_error_save),
            onDismiss = dismiss,
        )
        PreferencesError.DeleteFailed -> ErrorBanner(
            message = stringResource(R.string.settings_prefs_error_delete),
            onRetry = { onIntent(PreferencesIntent.RequestDelete) },
            onDismiss = dismiss,
        )
    }
}

@Composable
private fun SectionHeading(text: String) {
    Text(
        text = text,
        modifier = Modifier
            .padding(top = PhopencodeTheme.spacing.sm)
            .semantics { heading() },
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.titleSmall,
    )
}

/** A rounded card, as in the server and session lists. */
@Composable
private fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        content = content,
    )
}

/**
 * One choice out of [options], as a radio group: each row is one radio button, tappable across its
 * whole width and at least a touch target high; TalkBack reads its label, role and whether it is
 * selected, and the group as one.
 */
@Composable
private fun <T> OptionGroup(
    options: List<T>,
    selected: T,
    enabled: Boolean,
    groupTag: String,
    optionTag: (T) -> String,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
) {
    val spacing = PhopencodeTheme.spacing
    Card {
        Column(
            modifier = Modifier
                .selectableGroup()
                .testTag(groupTag)
                .padding(vertical = spacing.xs),
        ) {
            for (option in options) {
                val isSelected = option == selected
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = spacing.touchTarget)
                        .selectable(
                            selected = isSelected,
                            enabled = enabled,
                            role = Role.RadioButton,
                            onClick = { if (!isSelected) onSelect(option) },
                        )
                        .testTag(optionTag(option))
                        .padding(horizontal = spacing.lg),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.md),
                ) {
                    RadioButton(selected = isSelected, onClick = null, enabled = enabled)
                    Text(
                        text = label(option),
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
    }
}

@get:StringRes
private val AppLanguage.labelRes: Int
    get() = when (this) {
        AppLanguage.SYSTEM -> R.string.settings_prefs_language_system
        AppLanguage.TURKISH -> R.string.settings_prefs_language_turkish
        AppLanguage.ENGLISH -> R.string.settings_prefs_language_english
    }

@get:StringRes
private val ThemePreference.labelRes: Int
    get() = when (this) {
        ThemePreference.SYSTEM -> R.string.settings_prefs_theme_system
        ThemePreference.LIGHT -> R.string.settings_prefs_theme_light
        ThemePreference.DARK -> R.string.settings_prefs_theme_dark
    }

/** The limit in whole mebibytes; what is wrong with it shows under it, else the allowed range. */
@Composable
private fun FileSizeField(state: PreferencesState, enabled: Boolean, onIntent: (PreferencesIntent) -> Unit) {
    val spacing = PhopencodeTheme.spacing
    val problem = state.fileSizeProblem
    val note = problem?.message() ?: stringResource(
        R.string.settings_prefs_file_size_hint,
        PreferencesState.MIN_FILE_SIZE_LIMIT_MIB,
        PreferencesState.MAX_FILE_SIZE_LIMIT_MIB,
    )
    OutlinedTextField(
        value = state.fileSizeLimitText,
        onValueChange = { onIntent(PreferencesIntent.EditFileSizeLimit(it)) },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.lg, vertical = spacing.md)
            .testTag(FILE_SIZE_FIELD_TAG),
        enabled = enabled,
        singleLine = true,
        label = { Text(stringResource(R.string.settings_prefs_file_size_label)) },
        suffix = { Text(stringResource(R.string.settings_prefs_file_size_unit)) },
        isError = problem != null,
        supportingText = { Text(note) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
    )
}

@Composable
private fun FileSizeProblem.message(): String = when (this) {
    FileSizeProblem.Blank -> stringResource(R.string.settings_prefs_file_size_blank)
    FileSizeProblem.NotANumber -> stringResource(R.string.settings_prefs_file_size_not_a_number)
    FileSizeProblem.TooSmall -> stringResource(
        R.string.settings_prefs_file_size_too_small,
        PreferencesState.MIN_FILE_SIZE_LIMIT_MIB,
    )
    FileSizeProblem.TooLarge -> stringResource(
        R.string.settings_prefs_file_size_too_large,
        PreferencesState.MAX_FILE_SIZE_LIMIT_MIB,
    )
}

/** Opens the licenses: a whole-card button, its title and description read as one. */
@Composable
private fun LicensesEntry(enabled: Boolean, onClick: () -> Unit) {
    val spacing = PhopencodeTheme.spacing
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().testTag(LICENSES_TAG),
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        color = colors.surfaceContainer,
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = spacing.touchTarget)
                .padding(horizontal = spacing.lg, vertical = spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            IconCircle(PhopencodeIcons.Info)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.xxs)) {
                Text(
                    text = stringResource(R.string.settings_prefs_licenses),
                    color = colors.onSurface,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.settings_prefs_licenses_description),
                    color = colors.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Icon(imageVector = PhopencodeIcons.ChevronRight, contentDescription = null, tint = colors.onSurfaceVariant)
        }
    }
}

/**
 * What deleting removes, and the button that asks for the confirmation; while deleting, a still
 * progress message in its place.
 */
@Composable
private fun DeleteDataCard(state: PreferencesState, onIntent: (PreferencesIntent) -> Unit) {
    val spacing = PhopencodeTheme.spacing
    val colors = MaterialTheme.colorScheme
    Card {
        Column(
            modifier = Modifier.padding(horizontal = spacing.lg, vertical = spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Text(
                text = stringResource(R.string.settings_prefs_delete_description),
                color = colors.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (state.deletion == DataDeletion.Deleting) {
                StillProgress(
                    message = stringResource(R.string.settings_prefs_deleting),
                    modifier = Modifier.fillMaxWidth().testTag(DELETING_TAG),
                )
            } else {
                Button(
                    onClick = { onIntent(PreferencesIntent.RequestDelete) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = spacing.touchTarget)
                        .testTag(DELETE_BUTTON_TAG),
                    enabled = state.canEdit,
                    colors = ButtonDefaults.buttonColors(containerColor = colors.error, contentColor = colors.onError),
                ) {
                    Icon(
                        imageVector = PhopencodeIcons.Warning,
                        contentDescription = null,
                        modifier = Modifier.padding(end = spacing.sm).size(ButtonDefaults.IconSize),
                    )
                    Text(stringResource(R.string.settings_prefs_delete_button))
                }
            }
        }
    }
}

@Composable
private fun IconCircle(icon: ImageVector) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.size(ICON_CIRCLE),
        shape = CircleShape,
        color = colors.secondaryContainer,
        contentColor = colors.onSecondaryContainer,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(ICON_SIZE))
        }
    }
}

/**
 * Work under way, as a still message rather than core/ui's `Loading`: its spinner never stops, and
 * Robolectric's `idle()` in the app's ActivityScenario tests would then never return. TalkBack hears
 * the same as for `Loading`: the message, an indeterminate progress and a polite live region.
 */
@Composable
private fun StillProgress(message: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clearAndSetSemantics {
                contentDescription = message
                progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
                liveRegion = LiveRegionMode.Polite
            }
            .padding(PhopencodeTheme.spacing.xl),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
