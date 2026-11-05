package io.github.thewisekd.phopencode.feature.chat.ui

import android.os.Looper
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import io.github.thewisekd.phopencode.core.model.FormField
import io.github.thewisekd.phopencode.core.model.FormId
import io.github.thewisekd.phopencode.core.model.PermissionRequestId
import io.github.thewisekd.phopencode.core.model.SessionId
import io.github.thewisekd.phopencode.core.navigation.ChatRoute
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatStatus
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ComposerState
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ReasoningState
import io.github.thewisekd.phopencode.feature.chat.logic.contract.StatusState
import io.github.thewisekd.phopencode.feature.chat.logic.contract.TimelineState
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ToolState
import io.github.thewisekd.phopencode.feature.chat.logic.contract.UsageState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.ActionsState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.FieldAnswer
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.FormCardState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.FormFieldState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.FormsState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PaletteState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PaletteTrigger
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PermissionCardState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PermissionsState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PromptAttachment
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.RevertState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SubagentNode
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SubagentState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SubagentStatus
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SwitcherState
import io.github.thewisekd.phopencode.feature.chat.ui.revert.TurnDiffScreen
import io.github.thewisekd.phopencode.feature.chat.ui.subagent.SubagentListScreen
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import kotlin.test.fail

/** Far more main thread steps than drawing a still screen takes; an endless animation never runs out. */
private const val STEP_LIMIT = 2_000

/** A task further ahead than this is not the next frame of an animation, so the screen is at rest. */
private val REST_HORIZON: Duration = Duration.ofSeconds(1)

/**
 * The pages of P7 and the chat with every interaction slot filled come to rest in a plain
 * activity, the way the app's ActivityScenario tests open them. An endless animation, such as a
 * spinner while loading or a pulsing live badge, would keep posting frames, and Robolectric's
 * `idle()` would never return. The looper is driven by hand as in the other screens' idle tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class InteractionIdleTest {
    private fun assertComesToRest(content: @Composable () -> Unit) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent { PhopencodeTheme { content() } }
        val looper = shadowOf(Looper.getMainLooper())
        var steps = 0
        while (steps < STEP_LIMIT) {
            if (!looper.isIdle) {
                looper.runOneTask()
            } else {
                val wait = untilNextTask(looper) ?: return
                if (wait > REST_HORIZON) return
                ShadowSystemClock.advanceBy(wait)
            }
            steps++
        }
        fail("the main thread still had work due within $REST_HORIZON after $steps steps")
    }

    /** How long until the looper's next task, or null when nothing is scheduled. */
    private fun untilNextTask(looper: ShadowLooper): Duration? {
        val next = looper.nextScheduledTaskTime
        if (next == Duration.ZERO) return null
        return next.minusMillis(SystemClock.uptimeMillis()).coerceAtLeast(Duration.ofMillis(1))
    }

    private val running = SubagentState(
        loading = false,
        subagents = listOf(SubagentNode(SessionId("ses_2"), status = SubagentStatus.Running)),
    )

    private val forms = FormsState(
        cards = listOf(
            FormCardState(
                formId = FormId("frm_1"),
                sessionId = SessionId("ses_1"),
                question = true,
                fields = listOf(FormFieldState(FormField.YesNo(key = "q0"), FieldAnswer.YesNo())),
            ),
        ),
    )

    private val permissions = PermissionsState(
        cards = listOf(PermissionCardState(PermissionRequestId("per_1"), SessionId("ses_2"), fromSubagent = true, action = "bash")),
    )

    @Test
    fun `a loading subagent list comes to rest`() {
        assertComesToRest { SubagentListScreen(ChatRoute.Subagents("ses_1"), SubagentState(), onIntent = {}) }
    }

    @Test
    fun `a subagent list with a running subagent comes to rest`() {
        assertComesToRest { SubagentListScreen(ChatRoute.Subagents("ses_1"), running, onIntent = {}) }
    }

    @Test
    fun `a loading turn diff page comes to rest`() {
        assertComesToRest { TurnDiffScreen(ChatRoute.TurnDiff("ses_1", "msg_1"), RevertState(), onIntent = {}) }
    }

    @Test
    fun `a loading child session comes to rest`() {
        assertComesToRest {
            ChildSessionScreen(
                route = ChatRoute.ChildSession("ses_2"),
                status = StatusState(),
                onStatusIntent = {},
                subagents = SubagentState(),
                onSubagentIntent = {},
                timeline = TimelineState(),
                onTimelineIntent = {},
                reasoning = ReasoningState(),
                onReasoningIntent = {},
                tool = ToolState(),
                onToolIntent = {},
                forms = FormsState(),
                onFormIntent = {},
                permissions = PermissionsState(),
                onPermissionIntent = {},
            )
        }
    }

    @Test
    fun `a running child session with cards comes to rest`() {
        assertComesToRest {
            ChildSessionScreen(
                route = ChatRoute.ChildSession("ses_2"),
                status = StatusState(ChatStatus.Idle),
                onStatusIntent = {},
                subagents = running,
                onSubagentIntent = {},
                timeline = TimelineState(loading = false),
                onTimelineIntent = {},
                reasoning = ReasoningState(),
                onReasoningIntent = {},
                tool = ToolState(),
                onToolIntent = {},
                forms = forms,
                onFormIntent = {},
                permissions = permissions,
                onPermissionIntent = {},
            )
        }
    }

    @Test
    fun `a chat with every interaction open comes to rest`() {
        assertComesToRest {
            ChatScreen(
                route = ChatRoute.Chat("ses_1"),
                status = StatusState(ChatStatus.Idle),
                onStatusIntent = {},
                usage = UsageState(),
                onUsageIntent = {},
                timeline = TimelineState(loading = false),
                onTimelineIntent = {},
                reasoning = ReasoningState(),
                onReasoningIntent = {},
                tool = ToolState(),
                onToolIntent = {},
                composer = ComposerState(draft = "@src", attachments = listOf(PromptAttachment.Skill("review"))),
                onComposerIntent = {},
                forms = forms,
                onFormIntent = {},
                permissions = permissions,
                onPermissionIntent = {},
                subagents = running,
                onSubagentIntent = {},
                palette = PaletteState(trigger = PaletteTrigger.At, loading = true),
                onPaletteIntent = {},
                switcher = SwitcherState(enabled = true, open = true, loading = true),
                onSwitcherIntent = {},
                actions = ActionsState(enabled = true, menuOpen = true, compacting = true),
                onActionsIntent = {},
            )
        }
    }
}
