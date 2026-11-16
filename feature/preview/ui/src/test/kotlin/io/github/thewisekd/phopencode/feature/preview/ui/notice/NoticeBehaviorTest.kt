package io.github.thewisekd.phopencode.feature.preview.ui.notice

import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import io.github.thewisekd.phopencode.core.navigation.PreviewRoute
import io.github.thewisekd.phopencode.core.ui.icon.PhopencodeIcons
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.preview.logic.contract.NoticeEvent
import io.github.thewisekd.phopencode.feature.preview.logic.contract.NoticeIntent
import io.github.thewisekd.phopencode.feature.preview.logic.contract.NoticePresenter
import io.github.thewisekd.phopencode.feature.preview.logic.contract.NoticeState
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PortsIntent
import io.github.thewisekd.phopencode.feature.preview.ui.ports.PortsScreen
import io.github.thewisekd.phopencode.feature.preview.ui.ports.PortsTags
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A notice presenter whose state a test sets and whose events it sends; records the intents. */
private class FakeNotice(initial: NoticeState) : NoticePresenter {
    val sent = Channel<NoticeEvent>(Channel.UNLIMITED)
    val intents: MutableList<NoticeIntent> = Collections.synchronizedList(mutableListOf())
    override val state = MutableStateFlow(initial)
    override val events: Flow<NoticeEvent> = sent.receiveAsFlow()

    override fun onIntent(intent: NoticeIntent) {
        intents += intent
    }
}

/**
 * How the notice behaves over the port picker: it keeps the list out of reach until it is answered,
 * sends what the user chose, cannot be tapped away, and the picker shows it from its presenter.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class NoticeBehaviorTest {
    @get:Rule
    val compose = createComposeRule()

    private val ports: MutableList<PortsIntent> = Collections.synchronizedList(mutableListOf())
    private val notice: MutableList<NoticeIntent> = Collections.synchronizedList(mutableListOf())

    private var koinStarted = false

    @After
    fun stopDi() {
        if (koinStarted) stopKoin()
    }

    private fun drawn() = NoticeOverPorts(compose, onPorts = { ports += it }, onNotice = { notice += it })

    private fun inDialog(text: String) = compose.onNode(hasText(text) and hasAnyAncestor(isDialog()))

    private fun dialogs(): Int = compose.onAllNodes(isDialog()).fetchSemanticsNodes().size

    private fun tapPort() {
        compose.onNodeWithTag(PortsTags.port(NOTICE_DEV_PORT)).performClick()
        compose.waitForIdle()
    }

    /**
     * Draws the picker free, notes where its port row is, then puts [notice] over it and taps there:
     * the tap lands on the blocker. TalkBack cannot reach the row either.
     */
    private fun tapWhereThePortWasUnder(notice: NoticeState) {
        val drawn = drawn()
        drawn.show(hidden)
        val row = compose.onNodeWithTag(PortsTags.port(NOTICE_DEV_PORT)).fetchSemanticsNode().boundsInRoot
        drawn.show(notice)

        compose.onNodeWithTag(PortsTags.port(NOTICE_DEV_PORT)).assertDoesNotExist()
        compose.onNodeWithTag(NoticeTags.BLOCKER).performTouchInput { click(row.center) }
        compose.waitForIdle()
    }

    @Test
    fun `while the setting is read the list cannot be used and nothing is shown yet`() {
        tapWhereThePortWasUnder(checking)

        assertEquals(0, dialogs())
        assertEquals(emptyList(), ports)
    }

    @Test
    fun `while the dialog shows the list cannot be used`() {
        tapWhereThePortWasUnder(showing)

        assertEquals(1, dialogs())
        assertEquals(emptyList(), ports)
    }

    @Test
    fun `once hidden the list is free and nothing covers it`() {
        drawn().show(hidden)

        assertEquals(0, dialogs())
        compose.onNodeWithTag(NoticeTags.BLOCKER).assertDoesNotExist()
        tapPort()
        assertEquals(listOf<PortsIntent>(PortsIntent.Open(NOTICE_DEV_PORT)), ports)
    }

    @Test
    fun `accept and not now send the user's answer`() {
        drawn().show(showing)

        compose.onNodeWithTag(NoticeTags.ACCEPT).performClick()
        compose.onNodeWithTag(NoticeTags.NOT_NOW).performClick()

        compose.runOnIdle { assertEquals(listOf(NoticeIntent.Accept, NoticeIntent.NotNow), notice) }
    }

    @Test
    fun `while saving both answers wait`() {
        drawn().show(saving)

        assertEquals(1, dialogs())
        compose.onNodeWithTag(NoticeTags.ACCEPT).assertIsNotEnabled()
        compose.onNodeWithTag(NoticeTags.NOT_NOW).assertIsNotEnabled().performClick()

        compose.runOnIdle { assertEquals(emptyList(), notice) }
    }

    @Test
    fun `after leaving the dialog stays until the screen goes, its answers off and the list blocked`() {
        tapWhereThePortWasUnder(left)

        assertEquals(1, dialogs())
        compose.onNodeWithTag(NoticeTags.ACCEPT).assertIsNotEnabled()
        compose.onNodeWithTag(NoticeTags.NOT_NOW).assertIsNotEnabled()
        assertEquals(emptyList(), ports)
        compose.runOnIdle { assertEquals(emptyList(), notice) }
    }

    @Test
    fun `after a failed save accept can be tried again`() {
        drawn().show(saveFailed)

        compose.onNodeWithTag(NoticeTags.SAVE_FAILED).performScrollTo()
        compose.onNodeWithTag(NoticeTags.NOT_NOW).assertIsEnabled()
        compose.onNodeWithTag(NoticeTags.ACCEPT).assertIsEnabled().performClick()

        compose.runOnIdle { assertEquals(listOf<NoticeIntent>(NoticeIntent.Accept), notice) }
    }

    @Test
    fun `a tap outside does not dismiss it, back answers not now`() {
        assertFalse(NOTICE_DIALOG_PROPERTIES.dismissOnClickOutside)
        assertTrue(NOTICE_DIALOG_PROPERTIES.dismissOnBackPress)
        drawn().show(showing)

        compose.runOnIdle { ShadowDialog.getLatestDialog().onBackPressed() }

        compose.runOnIdle { assertEquals(listOf<NoticeIntent>(NoticeIntent.NotNow), notice) }
    }

    @Test
    fun `the dialog has an information icon and nothing that reads as alarming`() {
        drawn().show(showing)

        compose.onNodeWithTag(NoticeTags.ICON, useUnmergedTree = true).assertExists()
        assertEquals(PhopencodeIcons.Info, NoticeIcon)
    }

    // The picker's own notice, from its presenter

    private fun startDi(fake: FakeNotice?) {
        startKoin {
            modules(
                module {
                    if (fake != null) factory<NoticePresenter> { (_: CoroutineScope) -> fake }
                },
            )
        }
        koinStarted = true
    }

    private fun setPicker() {
        compose.setContent {
            PhopencodeTheme(darkTheme = false) {
                Surface {
                    PortsScreen(route = PreviewRoute.Ports, state = listedBehindNotice, onIntent = { ports += it })
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `the picker shows the notice from its presenter and passes the answer on`() {
        val fake = FakeNotice(showing)
        startDi(fake)
        setPicker()

        assertEquals(1, dialogs())
        compose.onNodeWithTag(NoticeTags.ACCEPT).performClick()

        compose.runOnIdle { assertEquals(listOf<NoticeIntent>(NoticeIntent.Accept), fake.intents) }
    }

    @Test
    fun `leaving the notice closes the picker`() {
        val fake = FakeNotice(showing)
        startDi(fake)
        setPicker()

        fake.sent.trySend(NoticeEvent.Leave)
        compose.waitForIdle()

        assertEquals(listOf<PortsIntent>(PortsIntent.Close), ports)
    }

    @Test
    fun `once the presenter hides it the picker is free`() {
        val fake = FakeNotice(showing)
        startDi(fake)
        setPicker()

        fake.state.value = hidden
        compose.waitForIdle()

        assertEquals(0, dialogs())
        tapPort()
        assertEquals(listOf<PortsIntent>(PortsIntent.Open(NOTICE_DEV_PORT)), ports)
    }

    /** The [IllegalStateException] drawing the picker throws, wherever Compose wraps it. */
    private fun pickerFailure(): IllegalStateException {
        val thrown = assertFails { setPicker() }
        return generateSequence(thrown) { it.cause }.filterIsInstance<IllegalStateException>().firstOrNull()
            ?: throw AssertionError("expected an IllegalStateException, got $thrown", thrown)
    }

    @Test
    fun `without Koin the picker fails loudly rather than skipping the notice`() {
        assertEquals(NO_KOIN_MESSAGE, pickerFailure().message)
    }

    @Test
    fun `without a notice binding the picker fails loudly rather than skipping the notice`() {
        startDi(null)

        assertEquals(NO_BINDING_MESSAGE, pickerFailure().message)
    }

    @Test
    fun `only a caller passing null draws the picker without the notice`() {
        compose.setContent {
            PhopencodeTheme(darkTheme = false) {
                Surface {
                    PortsScreen(
                        route = PreviewRoute.Ports,
                        state = listedBehindNotice,
                        onIntent = { ports += it },
                        notice = null,
                    )
                }
            }
        }
        compose.waitForIdle()

        assertEquals(0, dialogs())
        compose.onNodeWithTag(NoticeTags.BLOCKER).assertDoesNotExist()
        tapPort()
        assertEquals(listOf<PortsIntent>(PortsIntent.Open(NOTICE_DEV_PORT)), ports)
    }
}
