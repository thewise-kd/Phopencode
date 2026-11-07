package io.github.thewisekd.phopencode.feature.chat.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import io.github.thewisekd.phopencode.core.model.FormId
import io.github.thewisekd.phopencode.core.model.PermissionRequestId
import io.github.thewisekd.phopencode.core.navigation.ChatRoute
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ComposerIntent
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ComposerState
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ReasoningIntent
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ReasoningState
import io.github.thewisekd.phopencode.feature.chat.logic.contract.StatusIntent
import io.github.thewisekd.phopencode.feature.chat.logic.contract.StatusState
import io.github.thewisekd.phopencode.feature.chat.logic.contract.TimelineIntent
import io.github.thewisekd.phopencode.feature.chat.logic.contract.TimelineKey
import io.github.thewisekd.phopencode.feature.chat.logic.contract.TimelineState
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ToolIntent
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ToolState
import io.github.thewisekd.phopencode.feature.chat.logic.contract.UsageIntent
import io.github.thewisekd.phopencode.feature.chat.logic.contract.UsageState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.ActionsIntent
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.ActionsState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.FormIntent
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.FormsState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PaletteIntent
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PaletteState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PermissionIntent
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PermissionsState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SubagentIntent
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SubagentState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SwitcherIntent
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SwitcherState
import io.github.thewisekd.phopencode.feature.chat.ui.actions.MessageActions
import io.github.thewisekd.phopencode.feature.chat.ui.actions.SessionActionsMenu
import io.github.thewisekd.phopencode.feature.chat.ui.composer.Composer
import io.github.thewisekd.phopencode.feature.chat.ui.form.FormCard
import io.github.thewisekd.phopencode.feature.chat.ui.form.FormListError
import io.github.thewisekd.phopencode.feature.chat.ui.palette.AttachmentChips
import io.github.thewisekd.phopencode.feature.chat.ui.palette.CommandPalette
import io.github.thewisekd.phopencode.feature.chat.ui.permission.PermissionCard
import io.github.thewisekd.phopencode.feature.chat.ui.permission.PermissionListError
import io.github.thewisekd.phopencode.feature.chat.ui.reasoning.ReasoningCard
import io.github.thewisekd.phopencode.feature.chat.ui.status.StatusBanner
import io.github.thewisekd.phopencode.feature.chat.ui.subagent.SubagentBadge
import io.github.thewisekd.phopencode.feature.chat.ui.subagent.SubagentHeader
import io.github.thewisekd.phopencode.feature.chat.ui.switcher.AgentModelSwitcher
import io.github.thewisekd.phopencode.feature.chat.ui.timeline.NoUserActions
import io.github.thewisekd.phopencode.feature.chat.ui.timeline.Timeline
import io.github.thewisekd.phopencode.feature.chat.ui.timeline.UserActionsSlot
import io.github.thewisekd.phopencode.feature.chat.ui.tool.ToolCard
import io.github.thewisekd.phopencode.feature.chat.ui.usage.UsageBar

/** The tags of the chat's slots, for tests: each part's root carries its slot's tag. */
object ChatSlotTags {
    const val USAGE: String = "chat:slot:usage"
    const val STATUS: String = "chat:slot:status"
    const val TIMELINE: String = "chat:slot:timeline"
    const val COMPOSER: String = "chat:slot:composer"
    const val SUBAGENTS: String = "chat:slot:subagents"
    const val SUBAGENT_HEADER: String = "chat:slot:subagent_header"
    const val ACTIONS: String = "chat:slot:actions"
    const val OPEN_FILES: String = "chat:slot:open_files"
    const val OPEN_GIT: String = "chat:slot:open_git"
    const val PALETTE: String = "chat:slot:palette"
    const val ATTACHMENTS: String = "chat:slot:attachments"
    const val SWITCHER: String = "chat:slot:switcher"
    const val FORMS_ERROR: String = "chat:slot:forms_error"
    const val PERMISSIONS_ERROR: String = "chat:slot:permissions_error"
    const val INTERACTIONS: String = "chat:slot:interactions"

    fun reasoning(key: TimelineKey): String = "chat:slot:reasoning:${key.value}"

    fun tool(key: TimelineKey): String = "chat:slot:tool:${key.value}"

    fun form(id: FormId): String = "chat:slot:form:${id.value}"

    fun permission(id: PermissionRequestId): String = "chat:slot:permission:${id.value}"

    fun messageActions(key: TimelineKey): String = "chat:slot:message_actions:${key.value}"
}

/**
 * The chat: places each part, drawn by its own task, in its slot. Decides nothing; every part draws
 * its presenter's state and sends that presenter's intents.
 *
 * From top to bottom, all inside the screen's insets, keyboard included, so the composer stays
 * above it:
 * - the usage bar, with the subagent badge, the buttons to the session's files and git (see
 *   [FolderButtons]) and the session's action menu at its end;
 * - the status banner;
 * - the timeline, where a long press on a prompt opens its actions;
 * - the pending permission requests, then the pending forms, of this session and its subagents, at
 *   most half the screen and scrolling beyond that;
 * - the command palette, the attachment chips and the agent and model switcher;
 * - the composer.
 *
 * On a short window, as in landscape or with the keyboard open, the pending cards give way first,
 * so the timeline keeps [timelineMinHeight] and the composer and switcher stay whole: see
 * [ChatColumn].
 *
 * When [StatusState.blocksChat] holds, as for a session the active server does not have, the
 * status takes the place of the others.
 *
 * In the timeline, a reasoning or tool item draws the card of its key from [reasoning] or [tool];
 * a key without a card draws nothing. Taking an attachment off a chip sends
 * [ComposerIntent.Detach].
 *
 * The root keeps the `placeholder:<route>` tag the app's navigation test looks for.
 */
@Composable
fun ChatScreen(
    route: ChatRoute.Chat,
    status: StatusState,
    onStatusIntent: (StatusIntent) -> Unit,
    usage: UsageState,
    onUsageIntent: (UsageIntent) -> Unit,
    timeline: TimelineState,
    onTimelineIntent: (TimelineIntent) -> Unit,
    reasoning: ReasoningState,
    onReasoningIntent: (ReasoningIntent) -> Unit,
    tool: ToolState,
    onToolIntent: (ToolIntent) -> Unit,
    composer: ComposerState,
    onComposerIntent: (ComposerIntent) -> Unit,
    forms: FormsState,
    onFormIntent: (FormIntent) -> Unit,
    permissions: PermissionsState,
    onPermissionIntent: (PermissionIntent) -> Unit,
    subagents: SubagentState,
    onSubagentIntent: (SubagentIntent) -> Unit,
    palette: PaletteState,
    onPaletteIntent: (PaletteIntent) -> Unit,
    switcher: SwitcherState,
    onSwitcherIntent: (SwitcherIntent) -> Unit,
    actions: ActionsState,
    onActionsIntent: (ActionsIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize().safeDrawingPadding().testTag("placeholder:$route")) {
        if (status.blocksChat) {
            StatusBanner(status, onStatusIntent, Modifier.fillMaxSize().testTag(ChatSlotTags.STATUS))
        } else {
            ChatColumn(
                top = {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        UsageBar(usage, onUsageIntent, Modifier.weight(1f).testTag(ChatSlotTags.USAGE))
                        SubagentBadge(subagents, onSubagentIntent, Modifier.testTag(ChatSlotTags.SUBAGENTS))
                        FolderButtons(actions, onActionsIntent)
                        SessionActionsMenu(actions, onActionsIntent, Modifier.testTag(ChatSlotTags.ACTIONS))
                    }
                    StatusBanner(status, onStatusIntent, Modifier.fillMaxWidth().testTag(ChatSlotTags.STATUS))
                },
                timeline = {
                    ChatTimeline(
                        timeline = timeline,
                        onTimelineIntent = onTimelineIntent,
                        reasoning = reasoning,
                        onReasoningIntent = onReasoningIntent,
                        tool = tool,
                        onToolIntent = onToolIntent,
                        userActions = { item, dismiss ->
                            MessageActions(
                                messageId = item.messageId,
                                state = actions,
                                onIntent = onActionsIntent,
                                onDismiss = dismiss,
                                modifier = Modifier.testTag(ChatSlotTags.messageActions(item.key)),
                            )
                        },
                    )
                },
                interactions = { Interactions(forms, onFormIntent, permissions, onPermissionIntent) },
                bottom = {
                    CommandPalette(palette, onPaletteIntent, Modifier.fillMaxWidth().testTag(ChatSlotTags.PALETTE))
                    AttachmentChips(
                        attachments = composer.attachments,
                        onDetach = { onComposerIntent(ComposerIntent.Detach(it)) },
                        modifier = Modifier.fillMaxWidth().testTag(ChatSlotTags.ATTACHMENTS),
                    )
                    AgentModelSwitcher(
                        switcher,
                        onSwitcherIntent,
                        Modifier.fillMaxWidth().testTag(ChatSlotTags.SWITCHER),
                    )
                    Composer(composer, onComposerIntent, Modifier.fillMaxWidth().testTag(ChatSlotTags.COMPOSER))
                },
            )
        }
    }
}

/**
 * The buttons to the session's folder, [ActionsState.directory]: its files, sending
 * [ActionsIntent.OpenFiles], and its git, sending [ActionsIntent.OpenGit]. Icon buttons named for a
 * screen reader, each a full touch target; they cannot be tapped until the folder is known, but
 * always keep their place, as the action menu's button does.
 */
@Composable
private fun FolderButtons(actions: ActionsState, onActionsIntent: (ActionsIntent) -> Unit) {
    val known = actions.directory != null
    IconButton(
        onClick = { onActionsIntent(ActionsIntent.OpenFiles) },
        enabled = known,
        modifier = Modifier.testTag(ChatSlotTags.OPEN_FILES),
    ) {
        Icon(imageVector = FolderIcons.Files, contentDescription = stringResource(R.string.chat_actions_open_files))
    }
    IconButton(
        onClick = { onActionsIntent(ActionsIntent.OpenGit) },
        enabled = known,
        modifier = Modifier.testTag(ChatSlotTags.OPEN_GIT),
    ) {
        Icon(imageVector = FolderIcons.Git, contentDescription = stringResource(R.string.chat_actions_open_git))
    }
}

/**
 * The icons of [FolderButtons]: the 24 dp Material symbols "folder" and "account tree", kept here
 * as the module does not depend on the icon library.
 */
private object FolderIcons {
    val Files: ImageVector = icon(
        "Files",
        "M10,4H4c-1.1,0 -1.99,0.9 -1.99,2L2,18c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V8c0,-1.1 -0.9,-2 -2,-2h-8l-2,-2z",
    )

    val Git: ImageVector = icon("Git", "M22,11V3h-7v3H9V3H2v8h7V8h2v10h4v3h7v-8h-7v3h-2V8h2v3z")

    private fun icon(name: String, pathData: String): ImageVector =
        ImageVector.Builder(
            name = "ChatFolder.$name",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).addPath(pathData = addPathNodes(pathData), fill = SolidColor(Color.Black)).build()
}

/**
 * A subagent's session, opened as `ChatRoute.ChildSession`: read only, so no composer, palette or
 * switcher. Decides nothing, like [ChatScreen].
 *
 * From top to bottom: the subagent header, with the way back to the parent and to its own
 * subagents; the status banner; the timeline; the pending permission requests and forms of this
 * session and its subagents. When [StatusState.blocksChat] holds, the status takes the place of the
 * others.
 *
 * The root keeps the `placeholder:<route>` tag the app's navigation test looks for.
 */
@Composable
fun ChildSessionScreen(
    route: ChatRoute.ChildSession,
    status: StatusState,
    onStatusIntent: (StatusIntent) -> Unit,
    subagents: SubagentState,
    onSubagentIntent: (SubagentIntent) -> Unit,
    timeline: TimelineState,
    onTimelineIntent: (TimelineIntent) -> Unit,
    reasoning: ReasoningState,
    onReasoningIntent: (ReasoningIntent) -> Unit,
    tool: ToolState,
    onToolIntent: (ToolIntent) -> Unit,
    forms: FormsState,
    onFormIntent: (FormIntent) -> Unit,
    permissions: PermissionsState,
    onPermissionIntent: (PermissionIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize().safeDrawingPadding().testTag("placeholder:$route")) {
        if (status.blocksChat) {
            StatusBanner(status, onStatusIntent, Modifier.fillMaxSize().testTag(ChatSlotTags.STATUS))
        } else {
            ChatColumn(
                top = {
                    SubagentHeader(
                        subagents,
                        onSubagentIntent,
                        Modifier.fillMaxWidth().testTag(ChatSlotTags.SUBAGENT_HEADER),
                    )
                    StatusBanner(status, onStatusIntent, Modifier.fillMaxWidth().testTag(ChatSlotTags.STATUS))
                },
                timeline = {
                    ChatTimeline(
                        timeline = timeline,
                        onTimelineIntent = onTimelineIntent,
                        reasoning = reasoning,
                        onReasoningIntent = onReasoningIntent,
                        tool = tool,
                        onToolIntent = onToolIntent,
                        userActions = NoUserActions,
                    )
                },
                interactions = { Interactions(forms, onFormIntent, permissions, onPermissionIntent) },
                bottom = {},
            )
        }
    }
}

/**
 * The timeline's least height on a short window: 120dp, a few lines of a reply and room to scroll,
 * but never more than a third of [window], so even a tiny window keeps two thirds for the rest.
 */
internal fun timelineMinHeight(window: Dp): Dp = min(TIMELINE_MIN_HEIGHT, window / 3)

private val TIMELINE_MIN_HEIGHT: Dp = 120.dp

/**
 * The chat's column: [top], [timeline], [interactions] and [bottom], stacked in that order, each
 * child of [top] and [bottom] at its own height and the timeline filling what is left.
 *
 * Height goes first to [bottom], from its last child up, so the composer and the switcher stay
 * whole; then to [top]. The interactions get at most half the window, and never so much that the
 * timeline falls under [timelineMinHeight]; beyond that they scroll in their own area. On a tall
 * window that floor never binds, so this is a plain column with the interactions capped at half.
 */
@Composable
private fun ChatColumn(
    top: @Composable () -> Unit,
    timeline: @Composable () -> Unit,
    interactions: @Composable () -> Unit,
    bottom: @Composable () -> Unit,
) {
    Layout(
        contents = listOf(top, timeline, interactions, bottom),
        modifier = Modifier.fillMaxSize(),
    ) { (tops, timelines, cards, bottoms), constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        fun upTo(max: Int) = Constraints(maxWidth = width, maxHeight = max.coerceAtLeast(0))
        var left = height
        val bottomPlaced = arrayOfNulls<Placeable>(bottoms.size)
        for (i in bottoms.indices.reversed()) {
            bottomPlaced[i] = bottoms[i].measure(upTo(left)).also { left -= it.height }
        }
        val topPlaced = tops.map { child -> child.measure(upTo(left)).also { left -= it.height } }
        left = left.coerceAtLeast(0)
        val floor = timelineMinHeight(height.toDp()).roundToPx().coerceAtMost(left)
        var cap = minOf(height / 2, left - floor)
        val cardPlaced = cards.map { child ->
            child.measure(upTo(cap)).also {
                cap -= it.height
                left -= it.height
            }
        }
        val rest = left.coerceAtLeast(0)
        val timelinePlaced = timelines.map { it.measure(Constraints(maxWidth = width, minHeight = rest, maxHeight = rest)) }
        layout(width, height) {
            var y = 0
            (topPlaced + timelinePlaced + cardPlaced + bottomPlaced.filterNotNull()).forEach {
                it.placeRelative(0, y)
                y += it.height
            }
        }
    }
}

/** The timeline with its reasoning and tool cards, taking the height the other parts leave. */
@Composable
private fun ChatTimeline(
    timeline: TimelineState,
    onTimelineIntent: (TimelineIntent) -> Unit,
    reasoning: ReasoningState,
    onReasoningIntent: (ReasoningIntent) -> Unit,
    tool: ToolState,
    onToolIntent: (ToolIntent) -> Unit,
    userActions: UserActionsSlot,
) {
    Timeline(
        state = timeline,
        onIntent = onTimelineIntent,
        reasoningCard = { item ->
            reasoning.cards[item.key]?.let { card ->
                ReasoningCard(item.key, card, onReasoningIntent, Modifier.testTag(ChatSlotTags.reasoning(item.key)))
            }
        },
        toolCard = { item ->
            tool.cards[item.key]?.let { card ->
                ToolCard(item.key, card, onToolIntent, Modifier.testTag(ChatSlotTags.tool(item.key)))
            }
        },
        modifier = Modifier.fillMaxSize().testTag(ChatSlotTags.TIMELINE),
        userActions = userActions,
    )
}

/**
 * The pending permission requests, then the pending forms, oldest first, each in its card's slot
 * and keyed by its ID, so a card keeps what it remembers as others come and go, with each list's
 * failure after it. At most as tall as [ChatColumn] lets it be, scrolling beyond that; nothing
 * when there is nothing to show.
 */
@Composable
private fun Interactions(
    forms: FormsState,
    onFormIntent: (FormIntent) -> Unit,
    permissions: PermissionsState,
    onPermissionIntent: (PermissionIntent) -> Unit,
) {
    val formsError = forms.error
    val permissionsError = permissions.error
    val empty = forms.cards.isEmpty() && permissions.cards.isEmpty() && formsError == null && permissionsError == null
    if (empty) return
    Column(
        Modifier
            .fillMaxWidth()
            .testTag(ChatSlotTags.INTERACTIONS)
            .verticalScroll(rememberScrollState()),
    ) {
        permissions.cards.forEach { card ->
            key(card.requestId.value) {
                PermissionCard(
                    card,
                    onPermissionIntent,
                    Modifier.fillMaxWidth().testTag(ChatSlotTags.permission(card.requestId)),
                )
            }
        }
        if (permissionsError != null) {
            PermissionListError(
                permissionsError,
                onPermissionIntent,
                Modifier.fillMaxWidth().testTag(ChatSlotTags.PERMISSIONS_ERROR),
            )
        }
        forms.cards.forEach { card ->
            key(card.formId.value) {
                FormCard(card, onFormIntent, Modifier.fillMaxWidth().testTag(ChatSlotTags.form(card.formId)))
            }
        }
        if (formsError != null) {
            FormListError(formsError, onFormIntent, Modifier.fillMaxWidth().testTag(ChatSlotTags.FORMS_ERROR))
        }
    }
}
