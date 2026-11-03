package io.github.thewisekd.phopencode.core.testing.contract

import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/**
 * One promise from an interface's KDoc, as a check that any implementation can run.
 *
 * A contract is a plain list of these and names no test framework, so that fakes and real
 * implementations share it from any test source set, JUnit 5 or an instrumented JUnit 4 test:
 * each one wraps [run] in a test of its own. A failed promise throws [AssertionError].
 *
 * A contract takes a factory for a harness. Every case calls the factory once, so cases do not
 * share state. The harness is how a contract arranges what only the implementation's own test can
 * reach, such as a server's state.
 */
class ContractCase internal constructor(val name: String, private val body: suspend TestScope.() -> Unit) {
    fun run() {
        runTest(timeout = CASE_TIMEOUT) { body() }
    }

    override fun toString(): String = name

    private companion object {
        val CASE_TIMEOUT = 60.seconds
    }
}

internal class ContractCases {
    private val cases = mutableListOf<ContractCase>()

    fun case(name: String, body: suspend TestScope.() -> Unit) {
        cases += ContractCase(name, body)
    }

    fun build(): List<ContractCase> = cases.toList()
}

internal fun contractCases(block: ContractCases.() -> Unit): List<ContractCase> = ContractCases().apply(block).build()
