package io.github.thewisekd.phopencode.feature.preview.ui.notice

import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import io.github.thewisekd.phopencode.core.navigation.PreviewRoute
import io.github.thewisekd.phopencode.feature.preview.logic.contract.NoticeIntent
import io.github.thewisekd.phopencode.feature.preview.logic.contract.NoticePhase
import io.github.thewisekd.phopencode.feature.preview.logic.contract.NoticeState
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PortsIntent
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PortsState
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewPort
import io.github.thewisekd.phopencode.feature.preview.ui.ports.PortsScreen
import io.github.thewisekd.phopencode.feature.preview.ui.ports.setThemedAt

/** A made-up dev server port, listed on the picker behind the notice. */
internal const val NOTICE_DEV_PORT = 5173

internal val listedBehindNotice = PortsState(loading = false, ports = listOf(PreviewPort(NOTICE_DEV_PORT, isOpencode = false)))

internal val checking = NoticeState()
internal val showing = NoticeState(phase = NoticePhase.Showing)
internal val saving = NoticeState(phase = NoticePhase.Saving)
internal val saveFailed = NoticeState(phase = NoticePhase.Showing, saveFailed = true)
internal val hidden = NoticeState(phase = NoticePhase.Hidden)
internal val left = NoticeState(phase = NoticePhase.Left)

/**
 * The port picker with the notice in [notice] drawn over it, at [fontScale]. The picker's intents
 * go to [onPorts], the notice's to [onNotice]. The state can change after drawing, as a test may set
 * its content only once.
 */
internal class NoticeOverPorts(
    private val compose: ComposeContentTestRule,
    private val fontScale: Float = 1f,
    private val onPorts: (PortsIntent) -> Unit = {},
    private val onNotice: (NoticeIntent) -> Unit = {},
) {
    private val shown = mutableStateOf(checking)
    private var started = false

    fun show(notice: NoticeState) {
        shown.value = notice
        if (!started) {
            started = true
            compose.setThemedAt(fontScale) {
                Surface {
                    PortsScreen(
                        route = PreviewRoute.Ports,
                        state = listedBehindNotice,
                        onIntent = onPorts,
                        notice = PreviewNoticeUi(shown.value, onNotice),
                    )
                }
            }
        }
        compose.waitForIdle()
    }
}
