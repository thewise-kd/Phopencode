package io.github.thewisekd.phopencode.feature.connect.ui.serverlist

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.thewisekd.phopencode.core.model.ServerProfile
import io.github.thewisekd.phopencode.core.model.ServerProfileId
import io.github.thewisekd.phopencode.core.model.SshAuth
import io.github.thewisekd.phopencode.core.navigation.ConnectRoute
import io.github.thewisekd.phopencode.core.ui.component.ConfirmDialog
import io.github.thewisekd.phopencode.core.ui.component.EmptyState
import io.github.thewisekd.phopencode.core.ui.component.EmptyStateAction
import io.github.thewisekd.phopencode.core.ui.component.ErrorBanner
import io.github.thewisekd.phopencode.core.ui.component.PhopencodeScaffold
import io.github.thewisekd.phopencode.core.ui.icon.PhopencodeIcons
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ServerListError
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ServerListIntent
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ServerListState
import io.github.thewisekd.phopencode.feature.connect.ui.R
import kotlinx.coroutines.launch
import java.util.Locale

/** Room under the last server, so the add button never covers it. */
private val FAB_CLEARANCE = 96.dp

private val AVATAR_SIZE = 44.dp

/** What a blank name shows in its avatar. Not text to translate: a mark. */
private const val NO_LETTER = "?"

/**
 * The letter a server's avatar shows, for tests. The avatar is decoration, so TalkBack does not read
 * it; the name next to it says the same.
 */
internal val AvatarLetterKey = SemanticsPropertyKey<String>("ServerAvatarLetter")

/** Marks a server's avatar. Carries the profile's ID, never its name or address. */
internal fun serverAvatarTag(id: ServerProfileId): String = "serverlist:avatar:${id.value}"

/**
 * The saved servers, the app's start screen. Draws [state] and sends [onIntent]; it decides nothing
 * itself. A swipe toward the start only asks to delete ([ServerListIntent.RequestDelete]): the
 * confirmation dialog is drawn from [ServerListState.pendingDelete], and the presenter deletes.
 *
 * Each server is a rounded card: a letter avatar, the name, the address and how it logs in. Keys and
 * settings are named icon buttons in the top bar; adding a server is the extended button, or the
 * empty state's button when there is none yet.
 *
 * The root keeps the `placeholder:<route>` tag the app's navigation test looks for.
 */
@Composable
fun ServerListScreen(
    route: ConnectRoute.ServerList,
    state: ServerListState,
    onIntent: (ServerListIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val addLabel = stringResource(R.string.connect_serverlist_add)
    PhopencodeScaffold(
        title = stringResource(R.string.connect_serverlist_title),
        modifier = modifier.fillMaxSize().testTag("placeholder:$route"),
        actions = {
            IconButton(onClick = { onIntent(ServerListIntent.OpenKeys) }) {
                Icon(
                    imageVector = PhopencodeIcons.Key,
                    contentDescription = stringResource(R.string.connect_serverlist_keys),
                )
            }
            IconButton(onClick = { onIntent(ServerListIntent.OpenSettings) }) {
                Icon(
                    imageVector = PhopencodeIcons.Settings,
                    contentDescription = stringResource(R.string.connect_serverlist_settings),
                )
            }
        },
        floatingActionButton = {
            // The empty state has its own add button; one is enough. The content overload, so the
            // label is plain text that TalkBack reads: the icon and text overload of Material 3 1.4
            // hides its text from accessibility services.
            if (state.servers.isNotEmpty()) {
                ExtendedFloatingActionButton(onClick = { onIntent(ServerListIntent.Add) }) {
                    Icon(imageVector = PhopencodeIcons.Add, contentDescription = null)
                    Spacer(Modifier.width(PhopencodeTheme.spacing.sm))
                    Text(addLabel)
                }
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
                    message = stringResource(error.messageRes),
                    onDismiss = { onIntent(ServerListIntent.DismissError) },
                )
            }
            val content = Modifier.fillMaxWidth().weight(1f)
            when {
                state.servers.isNotEmpty() -> ServerList(state.servers, onIntent, content)
                state.loading -> LoadingServers(content)
                else -> EmptyState(
                    modifier = content,
                    title = stringResource(R.string.connect_serverlist_empty_title),
                    message = stringResource(R.string.connect_serverlist_empty_message),
                    icon = PhopencodeIcons.Server,
                    action = EmptyStateAction(addLabel) { onIntent(ServerListIntent.Add) },
                )
            }
        }
    }
    state.pendingDelete?.let { server ->
        ConfirmDialog(
            title = stringResource(R.string.connect_serverlist_delete_title),
            message = stringResource(R.string.connect_serverlist_delete_message, server.name),
            onConfirm = { onIntent(ServerListIntent.ConfirmDelete) },
            onDismiss = { onIntent(ServerListIntent.CancelDelete) },
            confirmLabel = stringResource(R.string.connect_serverlist_delete),
            destructive = true,
        )
    }
}

/**
 * The servers are being read. A still message rather than core/ui's `Loading`: its spinner never
 * stops, and Robolectric's `idle()` in the app's ActivityScenario tests, which open this screen
 * while the presenter still loads, would then never return. Reading the saved servers is a local
 * read that ends quickly, so a spinner would barely show anyway. TalkBack hears the same as for
 * `Loading`: the message, an indeterminate progress and a polite live region.
 */
@Composable
private fun LoadingServers(modifier: Modifier = Modifier) {
    val message = stringResource(R.string.connect_serverlist_loading)
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

@get:StringRes
private val ServerListError.messageRes: Int
    get() = when (this) {
        ServerListError.LoadFailed -> R.string.connect_serverlist_error_load
        ServerListError.SaveFailed -> R.string.connect_serverlist_error_save
        ServerListError.DeleteFailed -> R.string.connect_serverlist_error_delete
    }

@Composable
private fun ServerList(
    servers: List<ServerProfile>,
    onIntent: (ServerListIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = PhopencodeTheme.spacing
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(start = spacing.lg, end = spacing.lg, top = spacing.sm, bottom = FAB_CLEARANCE),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        items(servers, key = { it.id.value }) { server -> ServerRow(server, onIntent) }
    }
}

/**
 * One server: a tap connects, the pencil edits, a swipe toward the start asks to delete. The swipe
 * snaps back at once; the dialog that follows comes from the state. Accessibility services, which
 * cannot swipe, get the same request as a custom action.
 */
@Composable
private fun ServerRow(server: ServerProfile, onIntent: (ServerListIntent) -> Unit) {
    val swipe = rememberSwipeToDismissBoxState()
    val scope = rememberCoroutineScope()
    val requestDelete = { onIntent(ServerListIntent.RequestDelete(server.id)) }
    val deleteLabel = stringResource(R.string.connect_serverlist_delete)
    val shape = MaterialTheme.shapes.medium
    val spacing = PhopencodeTheme.spacing
    SwipeToDismissBox(
        state = swipe,
        modifier = Modifier.clip(shape),
        enableDismissFromStartToEnd = false,
        onDismiss = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                requestDelete()
                scope.launch { swipe.reset() }
            }
        },
        backgroundContent = { DeleteBackground() },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .clickable(
                    onClickLabel = stringResource(R.string.connect_serverlist_connect),
                    role = Role.Button,
                ) { onIntent(ServerListIntent.Select(server.id)) }
                .semantics {
                    customActions = listOf(
                        CustomAccessibilityAction(deleteLabel) {
                            requestDelete()
                            true
                        },
                    )
                }
                .padding(start = spacing.lg, top = spacing.md, bottom = spacing.md, end = spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            LetterAvatar(server)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = server.name,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = server.address(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(spacing.xs))
                ModeChip(server.auth)
            }
            IconButton(onClick = { onIntent(ServerListIntent.Edit(server.id)) }) {
                Icon(
                    imageVector = PhopencodeIcons.Edit,
                    contentDescription = stringResource(R.string.connect_serverlist_edit, server.name),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The first letter of the server's name in a tinted circle. The tint is one of three soft container
 * colors, picked from the profile's ID, so neighbouring servers tell apart at a glance and each keeps
 * its color.
 */
@Composable
private fun LetterAvatar(server: ServerProfile) {
    val locale = LocalConfiguration.current.locales[0] ?: Locale.ROOT
    val letter = avatarLetter(server.name, locale)
    val (container, content) = avatarColors(server.id)
    Box(
        modifier = Modifier
            .size(AVATAR_SIZE)
            .clip(CircleShape)
            .background(container)
            .testTag(serverAvatarTag(server.id))
            .clearAndSetSemantics { this[AvatarLetterKey] = letter },
        contentAlignment = Alignment.Center,
    ) {
        Text(text = letter, color = content, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun avatarColors(id: ServerProfileId): Pair<Color, Color> {
    val colors = MaterialTheme.colorScheme
    return when (Math.floorMod(id.value.hashCode(), 3)) {
        0 -> colors.primaryContainer to colors.onPrimaryContainer
        1 -> colors.tertiaryContainer to colors.onTertiaryContainer
        else -> colors.secondaryContainer to colors.onSecondaryContainer
    }
}

/**
 * The first character of [name] that is not a space, upper-cased in [locale] (a Turkish "i" becomes
 * "İ"). A character outside the basic plane, such as an emoji, is kept whole. A blank name gets a
 * question mark.
 */
internal fun avatarLetter(name: String, locale: Locale): String {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return NO_LETTER
    val first = String(Character.toChars(trimmed.codePointAt(0)))
    return first.uppercase(locale)
}

/** How the server logs in, as a small chip. */
@Composable
private fun ModeChip(auth: SshAuth) {
    val label = when (auth) {
        is SshAuth.PrivateKey -> stringResource(R.string.connect_serverlist_mode_key)
        is SshAuth.Password -> stringResource(R.string.connect_serverlist_mode_password)
    }
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = PhopencodeTheme.spacing.sm, vertical = PhopencodeTheme.spacing.xxs),
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
        )
    }
}

/** What a swipe uncovers. Decorative: the dialog that follows says what happens. */
@Composable
private fun DeleteBackground() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = PhopencodeTheme.spacing.lg),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Icon(
            imageVector = ServerListIcons.Delete,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

/** `user@host:port`; an IPv6 host goes in brackets so its colons stay apart from the port. */
@Composable
private fun ServerProfile.address(): String {
    val shownHost = if (':' in host) "[$host]" else host
    return stringResource(R.string.connect_serverlist_address, username, shownHost, port)
}
