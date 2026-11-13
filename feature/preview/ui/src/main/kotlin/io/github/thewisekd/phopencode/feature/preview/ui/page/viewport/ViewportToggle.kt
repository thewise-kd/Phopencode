package io.github.thewisekd.phopencode.feature.preview.ui.page.viewport

import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PageIntent
import io.github.thewisekd.phopencode.feature.preview.logic.contract.ViewportMode
import io.github.thewisekd.phopencode.feature.preview.logic.viewport.ViewportSwitch
import io.github.thewisekd.phopencode.feature.preview.ui.R

/**
 * The top bar's viewport toggle (P13.15): "Desktop view", on in [ViewportMode.Desktop] and off in
 * [ViewportMode.Mobile]. Tapping it sends [PageIntent.SetViewport] with the other mode; it keeps no
 * state of its own and shows the [mode] the page logic holds, which lasts across rotation while the
 * preview is open.
 *
 * TalkBack reads its name, its on or off state and what the mode means. A 48 dp target.
 *
 * @param enabled False once the preview is closed.
 */
@Composable
internal fun ViewportToggle(
    mode: ViewportMode,
    onIntent: (PageIntent) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val desktop = mode == ViewportMode.Desktop
    val meaning = stringResource(
        if (desktop) R.string.preview_viewport_state_desktop else R.string.preview_viewport_state_mobile,
    )
    IconToggleButton(
        checked = desktop,
        onCheckedChange = { onIntent(ViewportSwitch.toggle(mode)) },
        enabled = enabled,
        modifier = modifier
            .testTag(ViewportTags.TOGGLE)
            .semantics { stateDescription = meaning },
    ) {
        Icon(imageVector = DesktopIcon, contentDescription = stringResource(R.string.preview_viewport_toggle))
    }
}

private const val STROKE = 2f

/**
 * A monitor on a stand, drawn as strokes on the 24 unit grid in the weight of the app's outlined
 * icons; core:ui's set has none, and a feature does not take the icon library itself.
 */
private val DesktopIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Phopencode.PreviewDesktop",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = STROKE,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            // The screen.
            moveTo(4f, 4f)
            horizontalLineTo(20f)
            verticalLineTo(15f)
            horizontalLineTo(4f)
            close()
            // The neck and the foot.
            moveTo(12f, 15f)
            verticalLineTo(19f)
            moveTo(8f, 20f)
            horizontalLineTo(16f)
        }
    }.build()
}
