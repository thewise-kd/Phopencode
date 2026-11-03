package io.github.thewisekd.phopencode.core.api_http.client

import io.github.thewisekd.phopencode.core.testing.contract.ApiHarness
import io.github.thewisekd.phopencode.core.testing.contract.ContractCase
import io.github.thewisekd.phopencode.core.testing.contract.catalogApiContract
import io.github.thewisekd.phopencode.core.testing.contract.eventApiContract
import io.github.thewisekd.phopencode.core.testing.contract.fsApiContract
import io.github.thewisekd.phopencode.core.testing.contract.interactionApiContract
import io.github.thewisekd.phopencode.core.testing.contract.messageApiContract
import io.github.thewisekd.phopencode.core.testing.contract.opencodeClientContract
import io.github.thewisekd.phopencode.core.testing.contract.sessionApiContract
import io.github.thewisekd.phopencode.core.testing.contract.vcsApiContract
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * Runs every `core/testing` API contract, in full and without exclusions, against the assembled
 * [HttpOpencodeClient] over HTTP, with [FullServer] as the server. This includes the cases the API
 * packages had to leave out because they need more than one API at once.
 */
class ClientContractTest {
    private val open = mutableListOf<FullHarness>()

    private fun harness(): ApiHarness = FullHarness().also { synchronized(open) { open += it } }

    private fun List<ContractCase>.asTests(): List<DynamicTest> {
        assertTrue(isNotEmpty(), "no case selected")
        return map { case ->
            DynamicTest.dynamicTest(case.name) {
                try {
                    case.run()
                } finally {
                    synchronized(open) { open.toList().also { open.clear() } }.forEach(FullHarness::close)
                }
            }
        }
    }

    @TestFactory
    fun opencodeClient() = opencodeClientContract(::harness).asTests()

    @TestFactory
    fun events() = eventApiContract(::harness).asTests()

    @TestFactory
    fun sessions() = sessionApiContract(::harness).asTests()

    @TestFactory
    fun messages() = messageApiContract(::harness).asTests()

    @TestFactory
    fun interactions() = interactionApiContract(::harness).asTests()

    @TestFactory
    fun catalog() = catalogApiContract(::harness).asTests()

    @TestFactory
    fun vcs() = vcsApiContract(::harness).asTests()

    @TestFactory
    fun fs() = fsApiContract(::harness).asTests()

    /** The cases other packages skipped run here; a rename must not drop them silently. */
    @Test
    fun `the cases that need several APIs at once are part of the run`() {
        val names = (
            sessionApiContract(::harness) + interactionApiContract(::harness) + opencodeClientContract(::harness)
            ).map { it.name }.toSet()
        val needed = setOf(
            "dismissing a question ends the whole execution",
            "un-archiving sends SessionUnarchived",
            "fork copies the history, all of it or up to a message",
            "an event collector fails with Closed on close, and does not reconnect",
            "every running call fails with Closed on close",
        )
        assertEquals(emptySet(), needed - names)
    }
}
