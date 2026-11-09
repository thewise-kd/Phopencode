package io.github.thewisekd.phopencode.feature.settings.ui.licenses

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.LayoutDirection
import io.github.thewisekd.phopencode.core.navigation.SettingsRoute
import io.github.thewisekd.phopencode.core.ui.component.PhopencodeScaffold
import io.github.thewisekd.phopencode.core.ui.icon.PhopencodeIcons
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.settings.ui.R

/** Turns the closed chevron, which points to the end, down. */
private const val OPEN_ROTATION = 90f

/**
 * The open source licenses, `SettingsRoute.Licenses`: everything the app ships from others, with the
 * notices their licenses ask for. The data is fixed and ships with the app, so the screen has no
 * presenter; [onClose] goes back.
 *
 * In the style of the preferences, rounded cards under headings, in one scrolling list:
 * - The LGPL notice for sora-editor, and the EPL notice for the Eclipse annotations: where their
 *   source is, and for the LGPL that the library may be replaced.
 * - Every library of the release build ([LIBRARIES]): its coordinate, version and license.
 * - Every TextMate grammar and theme directory of `core:codeview` ([TEXTMATE_NOTICES]); a tap opens
 *   its license files.
 * - Every license's full text ([LicenseText]), opened by a tap.
 *
 * License texts load from the assets only when opened. Which ones are open survives configuration
 * changes. The root keeps the `placeholder:<route>` tag the app's navigation test looks for.
 */
@Composable
fun LicensesScreen(
    route: SettingsRoute.Licenses,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PhopencodeScaffold(
        title = stringResource(R.string.settings_licenses_title),
        modifier = modifier.fillMaxSize().testTag("placeholder:$route"),
        onBack = onClose,
    ) { padding ->
        LicensesList(Modifier.fillMaxSize().padding(padding))
    }
}

@Composable
private fun LicensesList(modifier: Modifier) {
    val spacing = PhopencodeTheme.spacing
    val opened = rememberSaveable(saver = OpenedSaver) { SnapshotStateList<String>() }
    val toggle = { key: String -> if (!opened.remove(key)) opened.add(key) }
    LazyColumn(
        modifier = modifier.testTag(LICENSES_LIST_TAG),
        contentPadding = PaddingValues(horizontal = spacing.lg, vertical = spacing.sm),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        item(key = "intro") {
            Text(
                text = stringResource(R.string.settings_licenses_intro),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        notices()
        libraries()
        textMate(opened, toggle)
        licenseTexts(opened, toggle)
    }
}

private fun LazyListScope.notices() {
    item(key = "lgpl-heading") { SectionHeading(stringResource(R.string.settings_licenses_lgpl_heading)) }
    item(key = "lgpl") {
        NoticeCard(
            text = stringResource(
                R.string.settings_licenses_lgpl_notice,
                SORA_EDITOR_VERSION,
                SORA_EDITOR_SOURCE,
                APP_SOURCE,
            ),
            tag = LGPL_NOTICE_TAG,
        )
    }
    item(key = "epl-heading") { SectionHeading(stringResource(R.string.settings_licenses_epl_heading)) }
    item(key = "epl") {
        val annotations = LIBRARIES.first { it.license == LicenseText.EPL_2_0 }
        NoticeCard(
            text = stringResource(R.string.settings_licenses_epl_notice, annotations.version, ECLIPSE_SOURCE),
            tag = EPL_NOTICE_TAG,
        )
    }
}

private fun LazyListScope.libraries() {
    item(key = "libraries-heading") {
        SectionHeading(
            text = stringResource(R.string.settings_licenses_libraries_heading),
            modifier = Modifier.testTag(LIBRARIES_HEADING_TAG),
        )
    }
    items(LIBRARIES, key = { "library:${it.coordinate}" }) { library -> LibraryRow(library) }
}

private fun LazyListScope.textMate(opened: List<String>, toggle: (String) -> Unit) {
    item(key = "textmate-heading") { SectionHeading(stringResource(R.string.settings_licenses_textmate_heading)) }
    item(key = "textmate-intro") {
        Text(
            text = stringResource(R.string.settings_licenses_textmate_intro),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    items(TEXTMATE_NOTICES, key = { "textmate:${it.directory}" }) { notice ->
        val key = textMateTag(notice)
        ExpandableCard(
            title = notice.name,
            details = listOf(stringResource(R.string.settings_licenses_textmate_source, notice.source), notice.license),
            assets = notice.licenseAssets,
            open = key in opened,
            onToggle = { toggle(key) },
            tag = key,
            bodyTag = textMateBodyTag(notice),
        )
    }
}

private fun LazyListScope.licenseTexts(opened: List<String>, toggle: (String) -> Unit) {
    item(key = "texts-heading") { SectionHeading(stringResource(R.string.settings_licenses_texts_heading)) }
    items(LicenseText.entries, key = { "license:${it.name}" }) { license ->
        val key = licenseTextTag(license)
        ExpandableCard(
            title = license.title,
            details = emptyList(),
            assets = listOf(license.asset),
            open = key in opened,
            onToggle = { toggle(key) },
            tag = key,
            bodyTag = licenseTextBodyTag(license),
        )
    }
}

@Composable
private fun SectionHeading(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier
            .padding(top = PhopencodeTheme.spacing.sm)
            .semantics { heading() },
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.titleSmall,
    )
}

/** A rounded card, as in the preferences. */
@Composable
private fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        content = content,
    )
}

@Composable
private fun NoticeCard(text: String, tag: String) {
    val spacing = PhopencodeTheme.spacing
    Card {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = spacing.lg, vertical = spacing.md).testTag(tag),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** A library's coordinate, version and license, read as one. */
@Composable
private fun LibraryRow(library: Library) {
    val spacing = PhopencodeTheme.spacing
    val colors = MaterialTheme.colorScheme
    Card {
        Column(
            modifier = Modifier
                .semantics(mergeDescendants = true) {}
                .testTag(libraryTag(library))
                .padding(horizontal = spacing.lg, vertical = spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.xxs),
        ) {
            Text(text = library.coordinate, color = colors.onSurface, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = stringResource(
                    R.string.settings_licenses_library_details,
                    library.version,
                    library.license.shortName,
                ),
                color = colors.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/**
 * A card whose header, a whole-width button at least a touch target high, opens and closes the
 * license text of [assets] under it. TalkBack reads the header as one, says whether the text is
 * open, and names what a tap does.
 */
@Composable
private fun ExpandableCard(
    title: String,
    details: List<String>,
    assets: List<String>,
    open: Boolean,
    onToggle: () -> Unit,
    tag: String,
    bodyTag: String,
) {
    val spacing = PhopencodeTheme.spacing
    val colors = MaterialTheme.colorScheme
    val state = stringResource(if (open) R.string.settings_licenses_expanded else R.string.settings_licenses_collapsed)
    val action = stringResource(if (open) R.string.settings_licenses_close else R.string.settings_licenses_open)
    val rotation = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -OPEN_ROTATION else OPEN_ROTATION
    Card {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = spacing.touchTarget)
                    .clickable(onClickLabel = action, role = Role.Button, onClick = onToggle)
                    .semantics { stateDescription = state }
                    .testTag(tag)
                    .padding(horizontal = spacing.lg, vertical = spacing.md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.md),
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.xxs)) {
                    Text(text = title, color = colors.onSurface, style = MaterialTheme.typography.titleMedium)
                    details.forEach { detail ->
                        Text(
                            text = detail,
                            color = colors.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                Icon(
                    imageVector = PhopencodeIcons.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier.rotate(if (open) rotation else 0f),
                    tint = colors.onSurfaceVariant,
                )
            }
            if (open) {
                LicenseBody(
                    assets = assets,
                    modifier = Modifier
                        .padding(start = spacing.lg, end = spacing.lg, bottom = spacing.md)
                        .testTag(bodyTag),
                )
            }
        }
    }
}

/** The texts of [assets], one after another; a still message when one cannot be read. */
@Composable
private fun LicenseBody(assets: List<String>, modifier: Modifier) {
    val context = LocalContext.current
    val texts = remember(assets) { assets.map { context.readAsset(it) } }
    if (texts.any { it == null }) {
        Text(
            text = stringResource(R.string.settings_licenses_unreadable),
            modifier = modifier,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
        )
    } else {
        Text(
            text = texts.joinToString(separator = "\n\n") { it.orEmpty().trimEnd() },
            modifier = modifier,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** The asset's text, or null when it cannot be read. Licenses are small, so this reads at once. */
private fun Context.readAsset(path: String): String? =
    runCatching { assets.open(path).bufferedReader().use { it.readText() } }.getOrNull()

/** Saves which cards are open by their tags. */
private val OpenedSaver = listSaver<SnapshotStateList<String>, String>(
    save = { it.toList() },
    restore = { it.toMutableStateList() },
)
