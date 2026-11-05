package io.github.thewisekd.phopencode.feature.chat.ui.form

import android.os.Looper
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatFailure
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.FieldAnswer
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.FieldProblem
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.FormAction
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.FormCardState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.FormError
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

/** Far more main thread steps than drawing a still card takes; an endless animation never runs out. */
private const val STEP_LIMIT = 2_000

/** A task further ahead than this is not the next frame of an animation, so the card is at rest. */
private val REST_HORIZON: Duration = Duration.ofSeconds(1)

/**
 * The card comes to rest in a plain activity, the way the app's ActivityScenario tests open the
 * chat. An endless animation, such as a spinner while a reply is on its way, would keep posting
 * frames, and Robolectric's `idle()` would never return. The looper is driven by hand as in the
 * other screens' idle tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class FormIdleTest {
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

    private fun assertCardComesToRest(card: FormCardState) = assertComesToRest { FormCard(card, onIntent = {}) }

    /** How long until the looper's next task, or null when nothing is scheduled. */
    private fun untilNextTask(looper: ShadowLooper): Duration? {
        val next = looper.nextScheduledTaskTime
        if (next == Duration.ZERO) return null
        return next.minusMillis(SystemClock.uptimeMillis()).coerceAtLeast(Duration.ofMillis(1))
    }

    @Test
    fun `a question comes to rest`() =
        assertCardComesToRest(question(single(FieldAnswer.SingleChoice(own = OWN_TEXT)), multi(), yesNo()))

    @Test
    fun `a form with every kind of field comes to rest`() =
        assertCardComesToRest(form(single(), multi(), number("4"), yesNo(true), text("a"), external(), unknown()))

    @Test
    fun `a card sending its answer comes to rest`() =
        assertCardComesToRest(form(yesNo(true)).copy(submitting = true))

    @Test
    fun `a card declining comes to rest`() =
        assertCardComesToRest(form(yesNo(true)).copy(declining = true))

    @Test
    fun `a card with problems and an error comes to rest`() =
        assertCardComesToRest(
            form(number("x", problem = FieldProblem.NotANumber))
                .copy(attempted = true, error = FormError(FormAction.Submit, ChatFailure.Rejected)),
        )

    @Test
    fun `the list error comes to rest`() =
        assertComesToRest { FormListError(ChatFailure.Unreachable, onIntent = {}) }
}
