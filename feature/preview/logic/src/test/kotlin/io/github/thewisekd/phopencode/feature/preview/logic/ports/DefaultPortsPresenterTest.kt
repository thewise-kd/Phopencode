package io.github.thewisekd.phopencode.feature.preview.logic.ports

import app.cash.turbine.test
import io.github.thewisekd.phopencode.feature.preview.logic.contract.ManualPortProblem
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PortsEvent
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PortsIntent
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PortsState
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewException
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewFailure
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewPort
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewPortList
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewPortsSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

private val OPENCODE = PreviewPort(4096, isOpencode = true)
private val VITE = PreviewPort(5173, isOpencode = false)
private val DEV = PreviewPort(8080, isOpencode = false)

/**
 * A port source that answers each listing from a queue of answers; a test can hold a listing open
 * with a [CompletableDeferred]. The source has only `list()`: that is all the picker may run.
 */
private class FakePortsSource {
    val answers = ArrayDeque<suspend () -> PreviewPortList>()
    var calls = 0
        private set

    fun listed(vararg ports: PreviewPort) = apply { answers.addLast { PreviewPortList.Listed(ports.toList()) } }

    fun unsupported() = apply { answers.addLast { PreviewPortList.Unsupported } }

    fun fails(failure: PreviewFailure) = apply { answers.addLast { throw PreviewException(failure) } }

    fun throws(error: Exception) = apply { answers.addLast { throw error } }

    fun held(gate: CompletableDeferred<PreviewPortList>) = apply { answers.addLast { gate.await() } }

    val source = object : PreviewPortsSource {
        override suspend fun list(): PreviewPortList {
            calls++
            val answer = answers.removeFirstOrNull() ?: error("No listing scripted")
            return answer()
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultPortsPresenterTest {
    private val fake = FakePortsSource()

    private fun TestScope.presenter(scope: CoroutineScope = backgroundScope) =
        DefaultPortsPresenter(ports = fake.source, scope = scope)

    // Listing

    @Test
    fun `nothing is listed before the scope runs`() = runTest {
        fake.listed(DEV)
        val presenter = presenter()

        assertEquals(PortsState(), presenter.state.value)
        assertEquals(0, fake.calls)
    }

    @Test
    fun `it lists the ports once with the opencode port marked`() = runTest {
        fake.listed(OPENCODE, VITE, DEV)
        val presenter = presenter()
        runCurrent()

        val state = presenter.state.value
        assertFalse(state.loading)
        assertFalse(state.refreshing)
        assertEquals(listOf(OPENCODE, VITE, DEV), state.ports)
        assertTrue(state.ports.single { it.port == 4096 }.isOpencode)
        assertFalse(state.unsupported)
        assertNull(state.error)
        assertEquals(1, fake.calls)
    }

    @Test
    fun `the ports are shown ascending and each once`() = runTest {
        fake.listed(DEV, OPENCODE, VITE, DEV)
        val presenter = presenter()
        runCurrent()

        assertEquals(listOf(OPENCODE, VITE, DEV), presenter.state.value.ports)
    }

    @Test
    fun `a port listed twice keeps the opencode mark`() = runTest {
        fake.listed(PreviewPort(4096, isOpencode = false), OPENCODE)
        val presenter = presenter()
        runCurrent()

        assertEquals(listOf(OPENCODE), presenter.state.value.ports)
    }

    @Test
    fun `an empty listing is an empty list with no error`() = runTest {
        fake.listed()
        val presenter = presenter()
        runCurrent()

        val state = presenter.state.value
        assertFalse(state.loading)
        assertEquals(emptyList(), state.ports)
        assertFalse(state.unsupported)
        assertNull(state.error)
    }

    @Test
    fun `a server that cannot list its ports is unsupported`() = runTest {
        fake.unsupported()
        val presenter = presenter()
        runCurrent()

        val state = presenter.state.value
        assertFalse(state.loading)
        assertTrue(state.unsupported)
        assertEquals(emptyList(), state.ports)
        assertNull(state.error)
    }

    @Test
    fun `without a connection the state says NotConnected`() = runTest {
        fake.fails(PreviewFailure.NotConnected)
        val presenter = presenter()
        runCurrent()

        val state = presenter.state.value
        assertFalse(state.loading)
        assertEquals(PreviewFailure.NotConnected, state.error)
        assertEquals(emptyList(), state.ports)
    }

    @Test
    fun `every listing failure is shown as it is`() = runTest {
        listOf(
            PreviewFailure.ConnectionLost,
            PreviewFailure.TimedOut,
            PreviewFailure.OutputTooLarge,
            PreviewFailure.CommandFailed,
        ).forEach { failure ->
            fake.fails(failure)
            val scope = CoroutineScope(coroutineContext + Job())
            val presenter = presenter(scope)
            runCurrent()

            assertEquals(failure, presenter.state.value.error, failure.name)
            assertFalse(presenter.state.value.loading)
            scope.cancel()
        }
    }

    @Test
    fun `an unexpected error is Unknown`() = runTest {
        fake.throws(IllegalStateException("/home/user/secret host.example"))
        val presenter = presenter()
        runCurrent()

        assertEquals(PreviewFailure.Unknown, presenter.state.value.error)
        assertFalse(presenter.state.value.loading)
    }

    @Test
    fun `a failure after a listing clears the old ports`() = runTest {
        fake.listed(DEV).fails(PreviewFailure.ConnectionLost)
        val presenter = presenter()
        runCurrent()
        presenter.onIntent(PortsIntent.Refresh)
        runCurrent()

        assertEquals(PreviewFailure.ConnectionLost, presenter.state.value.error)
        assertEquals(emptyList(), presenter.state.value.ports)
    }

    // Refresh

    @Test
    fun `refresh lists again and keeps the ports while it runs`() = runTest {
        val gate = CompletableDeferred<PreviewPortList>()
        fake.listed(DEV).held(gate)
        val presenter = presenter()
        runCurrent()

        presenter.onIntent(PortsIntent.Refresh)
        runCurrent()
        assertTrue(presenter.state.value.refreshing)
        assertTrue(presenter.state.value.busy)
        assertEquals(listOf(DEV), presenter.state.value.ports)

        gate.complete(PreviewPortList.Listed(listOf(OPENCODE, VITE)))
        runCurrent()
        assertFalse(presenter.state.value.refreshing)
        assertEquals(listOf(OPENCODE, VITE), presenter.state.value.ports)
        assertEquals(2, fake.calls)
    }

    @Test
    fun `only one listing runs at a time`() = runTest {
        val gate = CompletableDeferred<PreviewPortList>()
        fake.held(gate)
        val presenter = presenter()
        runCurrent()

        presenter.onIntent(PortsIntent.Refresh)
        presenter.onIntent(PortsIntent.Refresh)
        runCurrent()
        assertEquals(1, fake.calls)

        gate.complete(PreviewPortList.Listed(listOf(DEV)))
        runCurrent()
        val refresh = CompletableDeferred<PreviewPortList>()
        fake.held(refresh)
        presenter.onIntent(PortsIntent.Refresh)
        presenter.onIntent(PortsIntent.Refresh)
        runCurrent()
        assertEquals(2, fake.calls)
        refresh.complete(PreviewPortList.Listed(listOf(DEV)))
        runCurrent()
        assertEquals(2, fake.calls)
    }

    @Test
    fun `refresh after an error lists again and clears it on success`() = runTest {
        fake.fails(PreviewFailure.NotConnected).listed(DEV)
        val presenter = presenter()
        runCurrent()

        presenter.onIntent(PortsIntent.Refresh)
        runCurrent()

        assertNull(presenter.state.value.error)
        assertEquals(listOf(DEV), presenter.state.value.ports)
    }

    @Test
    fun `refresh after unsupported can find ports`() = runTest {
        fake.unsupported().listed(DEV)
        val presenter = presenter()
        runCurrent()

        presenter.onIntent(PortsIntent.Refresh)
        runCurrent()

        assertFalse(presenter.state.value.unsupported)
        assertEquals(listOf(DEV), presenter.state.value.ports)
    }

    @Test
    fun `dismissing the error clears it`() = runTest {
        fake.fails(PreviewFailure.CommandFailed)
        val presenter = presenter()
        runCurrent()

        presenter.onIntent(PortsIntent.DismissError)

        assertNull(presenter.state.value.error)
        assertEquals(1, fake.calls)
    }

    // Opening a listed port

    @Test
    fun `opening a listed port opens its page and runs nothing`() = runTest {
        fake.listed(OPENCODE, DEV)
        val presenter = presenter()
        runCurrent()

        presenter.events.test {
            presenter.onIntent(PortsIntent.Open(8080))
            assertEquals(PortsEvent.OpenPage(8080), awaitItem())
        }
        assertEquals(1, fake.calls)
    }

    @Test
    fun `the opencode port is never opened`() = runTest {
        fake.listed(OPENCODE, DEV)
        val presenter = presenter()
        runCurrent()

        presenter.events.test {
            presenter.onIntent(PortsIntent.Open(4096))
            runCurrent()
            expectNoEvents()
        }
    }

    @Test
    fun `a port that is not listed is not opened from the list`() = runTest {
        fake.listed(DEV)
        val presenter = presenter()
        runCurrent()

        presenter.events.test {
            listOf(3000, 0, -1, 70000).forEach { presenter.onIntent(PortsIntent.Open(it)) }
            runCurrent()
            expectNoEvents()
        }
    }

    @Test
    fun `an event sent while nobody collects waits for the collector`() = runTest {
        fake.listed(DEV)
        val presenter = presenter()
        runCurrent()

        presenter.onIntent(PortsIntent.Open(8080))
        presenter.onIntent(PortsIntent.Close)

        presenter.events.test {
            assertEquals(PortsEvent.OpenPage(8080), awaitItem())
            assertEquals(PortsEvent.Close, awaitItem())
        }
    }

    // Manual entry

    @Test
    fun `a valid manual port opens its page`() = runTest {
        fake.listed(OPENCODE)
        val presenter = presenter()
        runCurrent()

        presenter.events.test {
            listOf("1", "3000", "65535").forEach { text ->
                presenter.onIntent(PortsIntent.EditManualPort(text))
                assertEquals(text, presenter.state.value.manualText)
                assertNull(presenter.state.value.manualProblem, text)
                presenter.onIntent(PortsIntent.OpenManualPort)
                assertEquals(PortsEvent.OpenPage(text.toInt()), awaitItem())
            }
        }
        assertEquals(1, fake.calls)
    }

    @Test
    fun `surrounding spaces are ignored`() = runTest {
        fake.listed()
        val presenter = presenter()
        runCurrent()

        presenter.events.test {
            presenter.onIntent(PortsIntent.EditManualPort(" 8080 "))
            presenter.onIntent(PortsIntent.OpenManualPort)
            assertEquals(PortsEvent.OpenPage(8080), awaitItem())
        }
    }

    @Test
    fun `a manual port outside 1 to 65535 is refused`() = runTest {
        fake.listed()
        val presenter = presenter()
        runCurrent()

        presenter.events.test {
            listOf("0", "00", "65536", "99999", "-1", "-8080", "4294967297", "123456789012345678901234567890").forEach { text ->
                presenter.onIntent(PortsIntent.EditManualPort(text))
                assertEquals(ManualPortProblem.OutOfRange, presenter.state.value.manualProblem, text)
                presenter.onIntent(PortsIntent.OpenManualPort)
                assertEquals(ManualPortProblem.OutOfRange, presenter.state.value.manualProblem, text)
            }
            runCurrent()
            expectNoEvents()
        }
    }

    @Test
    fun `a manual entry that is not a whole number is refused`() = runTest {
        fake.listed()
        val presenter = presenter()
        runCurrent()

        presenter.events.test {
            listOf("abc", "80.5", "8,080", "1e3", "+80", "0x50", "80 80", "٨٠٨٠", "８０８０", "-", "--1", "80;ls").forEach { text ->
                presenter.onIntent(PortsIntent.EditManualPort(text))
                assertEquals(ManualPortProblem.NotANumber, presenter.state.value.manualProblem, text)
                presenter.onIntent(PortsIntent.OpenManualPort)
            }
            runCurrent()
            expectNoEvents()
        }
    }

    @Test
    fun `an empty manual entry is untouched until opened`() = runTest {
        fake.listed()
        val presenter = presenter()
        runCurrent()

        presenter.events.test {
            presenter.onIntent(PortsIntent.EditManualPort("8"))
            presenter.onIntent(PortsIntent.EditManualPort("  "))
            assertNull(presenter.state.value.manualProblem)

            presenter.onIntent(PortsIntent.OpenManualPort)
            assertEquals(ManualPortProblem.Empty, presenter.state.value.manualProblem)
            runCurrent()
            expectNoEvents()
        }
    }

    @Test
    fun `the opencode port is refused as a manual entry`() = runTest {
        fake.listed(OPENCODE, DEV)
        val presenter = presenter()
        runCurrent()

        presenter.events.test {
            presenter.onIntent(PortsIntent.EditManualPort("4096"))
            assertEquals(ManualPortProblem.OpencodePort, presenter.state.value.manualProblem)
            presenter.onIntent(PortsIntent.OpenManualPort)
            runCurrent()
            expectNoEvents()
        }
    }

    @Test
    fun `a listing that finds the opencode port checks the manual entry again`() = runTest {
        val gate = CompletableDeferred<PreviewPortList>()
        fake.held(gate).listed(DEV)
        val presenter = presenter()
        runCurrent()

        presenter.onIntent(PortsIntent.EditManualPort("4096"))
        assertNull(presenter.state.value.manualProblem)

        gate.complete(PreviewPortList.Listed(listOf(OPENCODE)))
        runCurrent()
        assertEquals(ManualPortProblem.OpencodePort, presenter.state.value.manualProblem)

        presenter.onIntent(PortsIntent.Refresh)
        runCurrent()
        assertNull(presenter.state.value.manualProblem)
    }

    @Test
    fun `a failed or unsupported refresh keeps the known opencode port refused`() = runTest {
        fake.listed(OPENCODE, DEV).fails(PreviewFailure.ConnectionLost).unsupported().listed(DEV)
        val presenter = presenter()
        runCurrent()

        presenter.events.test {
            presenter.onIntent(PortsIntent.Refresh)
            runCurrent()
            assertEquals(PreviewFailure.ConnectionLost, presenter.state.value.error)
            presenter.onIntent(PortsIntent.EditManualPort("4096"))
            assertEquals(ManualPortProblem.OpencodePort, presenter.state.value.manualProblem)
            presenter.onIntent(PortsIntent.OpenManualPort)
            assertEquals(ManualPortProblem.OpencodePort, presenter.state.value.manualProblem)

            presenter.onIntent(PortsIntent.Refresh)
            runCurrent()
            assertTrue(presenter.state.value.unsupported)
            assertEquals(ManualPortProblem.OpencodePort, presenter.state.value.manualProblem)
            presenter.onIntent(PortsIntent.OpenManualPort)
            runCurrent()
            expectNoEvents()

            // Only a new successful listing replaces what is known.
            presenter.onIntent(PortsIntent.Refresh)
            runCurrent()
            assertNull(presenter.state.value.manualProblem)
            presenter.onIntent(PortsIntent.OpenManualPort)
            assertEquals(PortsEvent.OpenPage(4096), awaitItem())
        }
    }

    @Test
    fun `the opencode port submitted before the first listing ends is not opened`() = runTest {
        val gate = CompletableDeferred<PreviewPortList>()
        fake.held(gate)
        val presenter = presenter()
        runCurrent()

        presenter.events.test {
            presenter.onIntent(PortsIntent.EditManualPort("4096"))
            presenter.onIntent(PortsIntent.OpenManualPort)
            runCurrent()
            expectNoEvents()

            gate.complete(PreviewPortList.Listed(listOf(OPENCODE, DEV)))
            runCurrent()
            assertEquals(ManualPortProblem.OpencodePort, presenter.state.value.manualProblem)
            presenter.onIntent(PortsIntent.OpenManualPort)
            runCurrent()
            expectNoEvents()
        }
    }

    @Test
    fun `no manual port opens while the first listing runs`() = runTest {
        val gate = CompletableDeferred<PreviewPortList>()
        fake.held(gate)
        val presenter = presenter()
        runCurrent()

        presenter.events.test {
            presenter.onIntent(PortsIntent.EditManualPort("3000"))
            presenter.onIntent(PortsIntent.OpenManualPort)
            runCurrent()
            expectNoEvents()

            gate.complete(PreviewPortList.Listed(listOf(OPENCODE)))
            runCurrent()
            expectNoEvents()
            presenter.onIntent(PortsIntent.OpenManualPort)
            assertEquals(PortsEvent.OpenPage(3000), awaitItem())
        }
    }

    @Test
    fun `a refresh after a successful listing does not hold up manual entry`() = runTest {
        val gate = CompletableDeferred<PreviewPortList>()
        fake.listed(OPENCODE).held(gate)
        val presenter = presenter()
        runCurrent()
        presenter.onIntent(PortsIntent.Refresh)
        runCurrent()

        presenter.events.test {
            presenter.onIntent(PortsIntent.EditManualPort("4096"))
            presenter.onIntent(PortsIntent.OpenManualPort)
            assertEquals(ManualPortProblem.OpencodePort, presenter.state.value.manualProblem)
            presenter.onIntent(PortsIntent.EditManualPort("3000"))
            presenter.onIntent(PortsIntent.OpenManualPort)
            assertEquals(PortsEvent.OpenPage(3000), awaitItem())
        }
        gate.complete(PreviewPortList.Listed(listOf(OPENCODE)))
    }

    @Test
    fun `many events in a row are all delivered`() = runTest {
        fake.listed(DEV)
        val presenter = presenter()
        runCurrent()

        val count = 1_000
        repeat(count) { presenter.onIntent(PortsIntent.Open(8080)) }
        presenter.onIntent(PortsIntent.Close)

        presenter.events.test {
            repeat(count) { assertEquals(PortsEvent.OpenPage(8080), awaitItem()) }
            assertEquals(PortsEvent.Close, awaitItem())
        }
    }

    @Test
    fun `manual entry works while the ports cannot be listed`() = runTest {
        fake.unsupported()
        val presenter = presenter()
        runCurrent()

        presenter.events.test {
            presenter.onIntent(PortsIntent.EditManualPort("3000"))
            presenter.onIntent(PortsIntent.OpenManualPort)
            assertEquals(PortsEvent.OpenPage(3000), awaitItem())
        }
    }

    @Test
    fun `the manual text never prints`() = runTest {
        fake.listed()
        val presenter = presenter()
        runCurrent()

        presenter.onIntent(PortsIntent.EditManualPort("31337"))

        assertFalse("31337" in presenter.state.value.toString())
    }

    // Closing

    @Test
    fun `close is forwarded`() = runTest {
        fake.listed()
        val presenter = presenter()
        runCurrent()

        presenter.events.test {
            presenter.onIntent(PortsIntent.Close)
            assertEquals(PortsEvent.Close, awaitItem())
        }
    }

    @Test
    fun `a listing stops with its scope`() = runTest {
        val gate = CompletableDeferred<PreviewPortList>()
        fake.held(gate)
        val scope = CoroutineScope(coroutineContext + Job())
        val presenter = presenter(scope)
        runCurrent()

        scope.cancel()
        gate.complete(PreviewPortList.Listed(listOf(DEV)))
        runCurrent()

        assertTrue(presenter.state.value.loading)
        assertEquals(emptyList(), presenter.state.value.ports)
    }
}
