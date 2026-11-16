package io.github.thewisekd.phopencode.feature.preview.ui.notice

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.thewisekd.phopencode.core.ui.icon.PhopencodeIcons
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.preview.logic.contract.NoticeEvent
import io.github.thewisekd.phopencode.feature.preview.logic.contract.NoticeIntent
import io.github.thewisekd.phopencode.feature.preview.logic.contract.NoticePhase
import io.github.thewisekd.phopencode.feature.preview.logic.contract.NoticePresenter
import io.github.thewisekd.phopencode.feature.preview.logic.contract.NoticeState
import io.github.thewisekd.phopencode.feature.preview.ui.R
import kotlinx.coroutines.CoroutineScope
import org.koin.core.parameter.parametersOf
import org.koin.mp.KoinPlatform

/** Back answers "Not now"; a tap outside does nothing, so the notice is never dismissed by accident. */
internal val NOTICE_DIALOG_PROPERTIES = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = false)

/** An information icon, never a warning one: the notice informs, it does not alarm. */
internal val NoticeIcon: ImageVector = PhopencodeIcons.Info

/** The points, in the order shown. */
private val POINTS = listOf(
    R.string.preview_notice_point_loopback,
    R.string.preview_notice_point_trust,
    R.string.preview_notice_point_accounts,
    R.string.preview_notice_point_links,
    R.string.preview_notice_point_server,
)

/**
 * What the live preview's first-visit notice (P13.24) shows over a screen, and where the user's
 * answers go.
 */
@Stable
class PreviewNoticeUi(val state: NoticeState, val onIntent: (NoticeIntent) -> Unit)

/** Keeps the notice's presenter, and the scope it runs in, for as long as the destination lives. */
private class NoticeHolder(create: (CoroutineScope) -> NoticePresenter) : ViewModel() {
    val presenter: NoticePresenter = create(viewModelScope)
}

/** Why [rememberPreviewNotice] fails when Koin has not been started. */
internal const val NO_KOIN_MESSAGE =
    "The live preview notice needs Koin: start it with previewModule before showing the port picker, " +
        "or pass notice = null explicitly."

/** Why [rememberPreviewNotice] fails when Koin has no [NoticePresenter]. */
internal const val NO_BINDING_MESSAGE =
    "The live preview notice needs a NoticePresenter binding: load previewModule, " +
        "or pass notice = null explicitly."

/**
 * The notice of the destination this is called in: its presenter comes from Koin, built with the
 * destination's view model scope, so it survives rotation and is read once per visit.
 * [NoticeEvent.Leave] calls [onLeave] while the destination is at least started.
 *
 * Missing wiring fails loudly rather than skipping the notice: without a started Koin it throws
 * [IllegalStateException] with [NO_KOIN_MESSAGE], and without a [NoticePresenter] binding with
 * [NO_BINDING_MESSAGE]. A screen without the notice is only ever one whose caller passes null.
 */
@Composable
fun rememberPreviewNotice(onLeave: () -> Unit): PreviewNoticeUi {
    val koin = remember { checkNotNull(KoinPlatform.getKoinOrNull()) { NO_KOIN_MESSAGE } }
    val holder = viewModel<NoticeHolder>(key = NoticeHolder::class.java.name) {
        NoticeHolder { scope ->
            checkNotNull(koin.getOrNull<NoticePresenter> { parametersOf(scope) }) { NO_BINDING_MESSAGE }
        }
    }
    val presenter = holder.presenter
    val leave by rememberUpdatedState(onLeave)
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(presenter, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            presenter.events.collect { event ->
                when (event) {
                    NoticeEvent.Leave -> leave()
                }
            }
        }
    }
    val state by presenter.state.collectAsStateWithLifecycle()
    return PreviewNoticeUi(state, presenter::onIntent)
}

/**
 * Draws [content] with [notice] over it. While the notice blocks the screen ([NoticeState.blocksScreen])
 * the content takes no touch and TalkBack does not reach it: before the setting is read, and while
 * the dialog shows. A null [notice] draws [content] alone.
 */
@Composable
fun PreviewNoticeHost(notice: PreviewNoticeUi?, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val blocks = notice?.state?.blocksScreen == true
    Box(modifier = modifier) {
        Box(modifier = if (blocks) Modifier.clearAndSetSemantics {} else Modifier) { content() }
        if (notice != null) PreviewNotice(notice.state, notice.onIntent)
    }
}

/** The blocker and the dialog for [state]; the caller places it over the screen it guards. */
@Composable
internal fun PreviewNotice(state: NoticeState, onIntent: (NoticeIntent) -> Unit) {
    if (state.blocksScreen) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .testTag(NoticeTags.BLOCKER)
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) awaitPointerEvent().changes.forEach { it.consume() }
                    }
                },
        )
    }
    if (state.dialogShown) NoticeDialog(state, onIntent)
}

/**
 * The notice itself, in a calm tone: an information icon in a neutral colour, a short title and a
 * few short points that scroll on a small screen, each read out on its own. "I have read and
 * understood" stores the answer; while it is stored both buttons wait and back does nothing (the
 * presenter ignores it), so the user leaves only once the store has finished or failed. Once the
 * user has left, the dialog stays until the screen is gone, its answers disabled.
 */
@Composable
private fun NoticeDialog(state: NoticeState, onIntent: (NoticeIntent) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val spacing = PhopencodeTheme.spacing
    val notNow = { onIntent(NoticeIntent.NotNow) }
    AlertDialog(
        onDismissRequest = notNow,
        modifier = Modifier.testTag(NoticeTags.DIALOG),
        properties = NOTICE_DIALOG_PROPERTIES,
        icon = {
            Icon(imageVector = NoticeIcon, contentDescription = null, modifier = Modifier.testTag(NoticeTags.ICON))
        },
        title = {
            Text(
                text = stringResource(R.string.preview_notice_title),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineSmall,
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().testTag(NoticeTags.BODY).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(spacing.md),
            ) {
                POINTS.forEachIndexed { index, point -> NoticePoint(point, NoticeTags.point(index)) }
                if (state.saveFailed && state.phase == NoticePhase.Showing) {
                    Text(
                        text = stringResource(R.string.preview_notice_save_failed),
                        modifier = Modifier
                            .testTag(NoticeTags.SAVE_FAILED)
                            .semantics { liveRegion = LiveRegionMode.Polite },
                        color = colors.onSurface,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onIntent(NoticeIntent.Accept) },
                enabled = state.phase == NoticePhase.Showing,
                modifier = Modifier.heightIn(min = spacing.touchTarget).testTag(NoticeTags.ACCEPT),
            ) {
                Text(stringResource(R.string.preview_notice_accept))
            }
        },
        dismissButton = {
            TextButton(
                onClick = notNow,
                enabled = state.phase == NoticePhase.Showing,
                modifier = Modifier.heightIn(min = spacing.touchTarget).testTag(NoticeTags.NOT_NOW),
            ) {
                Text(stringResource(R.string.preview_notice_not_now))
            }
        },
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = colors.surfaceContainerHigh,
        iconContentColor = colors.onSurfaceVariant,
        titleContentColor = colors.onSurface,
        textContentColor = colors.onSurfaceVariant,
    )
}

/** One point: a bullet TalkBack skips, and its text, read out on its own. */
@Composable
private fun NoticePoint(@StringRes text: Int, tag: String) {
    val spacing = PhopencodeTheme.spacing
    Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
        Text(text = "•", modifier = Modifier.clearAndSetSemantics {}, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = stringResource(text),
            modifier = Modifier.weight(1f).testTag(tag),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
