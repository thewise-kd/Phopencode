package io.github.thewisekd.phopencode.core.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import io.github.thewisekd.phopencode.core.ui.R
import io.github.thewisekd.phopencode.core.ui.icon.PhopencodeIcons

/**
 * A screen with a top bar. A root screen leaves [onBack] out, which hides the back
 * button. [content] must apply the padding it receives, or it runs under the bars.
 *
 * A [subtitle], such as a folder or a short status, sits under the title in a smaller,
 * muted style on one line. It is plain text, not a heading: the title alone names the
 * screen. The bar shares the background's color, so cards on the screen stand out.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhopencodeScaffold(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    subtitle: String? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Scaffold(
        modifier = modifier,
        containerColor = colors.background,
        contentColor = colors.onBackground,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = title,
                            modifier = Modifier.semantics { heading() },
                            color = colors.onSurface,
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (subtitle != null) {
                            Text(
                                text = subtitle,
                                color = colors.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = PhopencodeIcons.Back,
                                contentDescription = stringResource(R.string.ui_common_navigate_back),
                            )
                        }
                    }
                },
                actions = actions,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colors.background,
                    scrolledContainerColor = colors.surfaceContainer,
                    navigationIconContentColor = colors.onSurface,
                    titleContentColor = colors.onSurface,
                    actionIconContentColor = colors.onSurfaceVariant,
                ),
            )
        },
        bottomBar = bottomBar,
        snackbarHost = snackbarHost,
        floatingActionButton = floatingActionButton,
        content = content,
    )
}
