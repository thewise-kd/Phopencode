package io.github.thewisekd.phopencode.core.ui.component

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import io.github.thewisekd.phopencode.core.ui.R
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeStatusColors
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.core.ui.theme.StatusColor

/** What a status means. Its name, icon and color say the same thing, so no one depends on color. */
enum class StatusTone(@param:StringRes internal val labelRes: Int, internal val icon: ImageVector) {
    Success(R.string.ui_common_status_success, Icons.Outlined.CheckCircle),
    Warning(R.string.ui_common_status_warning, Icons.Outlined.WarningAmber),
    Danger(R.string.ui_common_status_danger, Icons.Outlined.ErrorOutline),
    Info(R.string.ui_common_status_info, Icons.Outlined.Info),
    ;

    internal fun colors(status: PhopencodeStatusColors): StatusColor = when (this) {
        Success -> status.success
        Warning -> status.warning
        Danger -> status.danger
        Info -> status.info
    }
}

/**
 * A small read-only status: a soft tinted chip whose icon takes the status's accent
 * color. A screen's own [label], such as "Connected", replaces the
 * status name on screen, and TalkBack then reads the name as the node's state.
 */
@Composable
fun StatusChip(
    status: StatusTone,
    modifier: Modifier = Modifier,
    label: String = stringResource(status.labelRes),
) {
    val spacing = PhopencodeTheme.spacing
    val colors = status.colors(PhopencodeTheme.status)
    val statusName = stringResource(status.labelRes)
    Surface(
        modifier = modifier.semantics(mergeDescendants = true) {
            if (label != statusName) stateDescription = statusName
        },
        color = colors.container,
        contentColor = colors.onContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Row(
            modifier = Modifier.padding(start = spacing.sm, end = spacing.md, top = spacing.xs, bottom = spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            Icon(
                imageVector = status.icon,
                contentDescription = null,
                modifier = Modifier.size(spacing.lg),
                tint = colors.color,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
