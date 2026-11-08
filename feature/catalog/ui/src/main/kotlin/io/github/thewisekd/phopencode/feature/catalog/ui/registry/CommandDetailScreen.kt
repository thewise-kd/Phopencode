package io.github.thewisekd.phopencode.feature.catalog.ui.registry

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import io.github.thewisekd.phopencode.core.navigation.CatalogRoute
import io.github.thewisekd.phopencode.core.ui.component.PhopencodeScaffold
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.CommandDetailIntent
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.CommandDetailState
import io.github.thewisekd.phopencode.feature.catalog.ui.R

/**
 * One command: its name, as it is typed, in the top bar, and its description. Draws [state] and
 * sends [onIntent]; it decides nothing itself. The name comes from the route, so the title shows
 * while the command loads.
 *
 * The root keeps the `placeholder:<route>` tag the app's navigation test looks for.
 */
@Composable
fun CommandDetailScreen(
    route: CatalogRoute.CommandDetail,
    state: CommandDetailState,
    onIntent: (CommandDetailIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    PhopencodeScaffold(
        title = COMMAND_PREFIX + state.name,
        modifier = modifier.fillMaxSize().testTag("placeholder:$route"),
        onBack = { onIntent(CommandDetailIntent.Close) },
    ) { padding ->
        val command = state.command
        DetailBody(
            loading = state.loading,
            failure = state.error,
            loadingMessage = stringResource(R.string.catalog_registry_loading_command),
            hasContent = command != null,
            onRetry = { onIntent(CommandDetailIntent.Retry) },
            modifier = Modifier.padding(padding),
        ) {
            if (command != null) {
                Section(stringResource(R.string.catalog_registry_section_description)) {
                    val description = command.description
                    if (description != null) {
                        BodyText(description)
                    } else {
                        BodyText(stringResource(R.string.catalog_registry_no_description), muted = true)
                    }
                }
            }
        }
    }
}
