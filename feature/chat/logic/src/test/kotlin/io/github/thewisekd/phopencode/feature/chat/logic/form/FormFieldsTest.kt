package io.github.thewisekd.phopencode.feature.chat.logic.form

import io.github.thewisekd.phopencode.core.model.FormField
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.FieldAnswer
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.FieldProblem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** A server's `pattern` is never run on the phone: the server alone enforces it. */
class FormFieldsTest {
    /** Runs [block] on its own thread, so a hang fails the test instead of stalling the run. */
    private fun <T> within(millis: Long, block: () -> T): T {
        var result: Result<T>? = null
        val thread = Thread { result = runCatching(block) }.apply {
            isDaemon = true
            start()
            join(millis)
        }
        assertFalse(thread.isAlive, "Still checking after $millis ms")
        return result!!.getOrThrow()
    }

    private fun problemOf(pattern: String, text: String, maxLength: Int? = null): FieldProblem? =
        FormFields.problem(FormField.FreeText(key = "x", pattern = pattern, maxLength = maxLength), FieldAnswer.Text(text))

    @Test
    fun `pathological patterns are not evaluated, and the answer is accepted at once`() {
        val long = "a".repeat(200_000) + "!"
        val patterns = listOf("(?:^){2147483647}", "(a|aa)+", "(a+)+\\1", "(a|b)*c")

        val problems = within(2_000) { patterns.map { problemOf(it, long) } }

        assertEquals(List(patterns.size) { null }, problems)
    }

    @Test
    fun `a pattern never makes a wrong format`() {
        assertNull(problemOf("[a-z]+", "abc1"))
        assertNull(problemOf("(unclosed", "anything"))
    }

    @Test
    fun `other checks still apply next to a pattern`() {
        assertEquals(FieldProblem.TooLong, problemOf("[a-z]+", "abcdef", maxLength = 3))
        assertEquals(
            FieldProblem.WrongFormat,
            FormFields.problem(
                FormField.FreeText(key = "mail", format = io.github.thewisekd.phopencode.core.model.TextFormat.Email),
                FieldAnswer.Text("not-mail"),
            ),
        )
    }
}
