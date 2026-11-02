package io.github.thewisekd.phopencode.core.model

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FormTest {
    private val options = listOf(FormOption(value = "a", label = "Option A"), FormOption(value = "b", label = "Option B"))

    @Test
    fun `a question form holds single choice, multiple choice and custom answers`() {
        val form = Form(
            id = FormId("form-1"),
            sessionId = SessionId("session-1"),
            kind = FormKind.Question,
            fields = listOf(
                FormField.SingleChoice(key = "q0", title = "One?", options = options, allowCustom = true, required = true),
                FormField.MultipleChoice(key = "q1", title = "Many?", options = options, minSelected = 1, maxSelected = 2),
            ),
        )

        val single = form.fields[0] as FormField.SingleChoice
        val multiple = form.fields[1] as FormField.MultipleChoice
        assertEquals(FormKind.Question, form.kind)
        assertTrue(single.allowCustom)
        assertTrue(single.required)
        assertEquals(1, multiple.minSelected)
        assertEquals(2, multiple.maxSelected)
    }

    @Test
    fun `fields are optional and unconditional unless stated`() {
        val field = FormField.YesNo(key = "ok", title = "Proceed?")

        assertFalse(field.required)
        assertFalse(field.hidden)
        assertEquals(emptyList(), field.visibleWhen)
        assertNull(field.defaultValue)
    }

    @Test
    fun `free text carries length limits`() {
        val field = FormField.FreeText(key = "name", title = "Name", minLength = 1, maxLength = 40, defaultValue = "x")

        assertEquals(1, field.minLength)
        assertEquals(40, field.maxLength)
    }

    @Test
    fun `a number field carries exact limits and may be integer only`() {
        val field = FormField.Number(
            key = "n",
            title = "Count",
            integerOnly = true,
            min = BigDecimal("0.1"),
            max = BigDecimal("10"),
        )

        assertTrue(field.integerOnly)
        assertEquals(BigDecimal("0.1"), field.min)
        assertEquals(BigDecimal("10"), field.max)
    }

    @Test
    fun `a number field with limits at the extremes of the scale hashes and prints`() {
        val field = FormField.Number(
            key = "n",
            title = "Count",
            min = BigDecimal("-100e2147483647"),
            max = BigDecimal("100e2147483647"),
            defaultValue = BigDecimal("1e-2147483647"),
        )

        assertEquals(field, field.copy())
        assertEquals(field.hashCode(), field.copy().hashCode())
        assertTrue(field.toString().contains("Count"))
    }

    @Test
    fun `a field can have several conditions on other fields`() {
        val conditions = listOf(
            FieldCondition.Equals(key = "q0", value = DataValue.Text("a")),
            FieldCondition.NotEquals(key = "q1", value = DataValue.Bool(true)),
            FieldCondition.Equals(key = "q2", value = DataValue.Num(BigDecimal.ONE)),
            FieldCondition.Unknown("future-operator"),
        )
        val field = FormField.FreeText(key = "q3", title = "Why?", visibleWhen = conditions)

        assertEquals(conditions, field.visibleWhen)
    }

    @Test
    fun `a condition is eq or neq, with no nesting`() {
        assertEquals(
            setOf("Equals", "NotEquals", "Unknown"),
            FieldCondition::class.java.permittedSubclasses.map { it.simpleName }.toSet(),
        )
    }

    @Test
    fun `a field may have no title, as a hidden field does`() {
        val field = FormField.FreeText(key = "hidden", hidden = true, defaultValue = "x")

        assertNull(field.title)
        assertTrue(field.hidden)
    }

    @Test
    fun `free text carries the format and the input hints`() {
        val field = FormField.FreeText(
            key = "mail",
            format = TextFormat.Email,
            pattern = "^.+$",
            placeholder = "a@example.invalid",
        )

        assertEquals(TextFormat.Email, field.format)
        assertEquals("^.+$", field.pattern)
        assertEquals("a@example.invalid", field.placeholder)
    }

    @Test
    fun `a form has no description of its own`() {
        assertFalse(Form::class.java.declaredFields.any { it.name == "description" })
    }

    @Test
    fun `an external field has a url and only the limited keys`() {
        val field: FormField = FormField.External(
            key = "login",
            url = "https://example.invalid/login",
            title = "Sign in",
            description = "Opens a page",
        )
        val names = FormField.External::class.java.declaredFields.map { it.name }.toSet()

        assertEquals(setOf("key", "title", "description", "url"), names)
        assertFalse(field is FormField.Input)
    }

    @Test
    fun `the fields other than external can be required, hidden and conditional`() {
        val inputs = FormField.Input::class.java.permittedSubclasses.map { it.simpleName }.toSet()

        assertEquals(setOf("SingleChoice", "MultipleChoice", "FreeText", "Number", "YesNo", "Unknown"), inputs)
    }

    @Test
    fun `the detail route adds a state`() {
        val answers = mapOf("q0" to DataValue.Text("Red"))
        val form = Form(
            id = FormId("f"),
            sessionId = SessionId("s"),
            kind = FormKind.Question,
            state = FormState.Answered(answers),
        )

        assertEquals(FormState.Answered(answers), form.state)
        assertNull(form.copy(state = null).state)
        assertEquals("gone", FormState.Cancelled("gone").message)
    }

    @Test
    fun `a form that is not a question keeps its raw kind`() {
        val form = Form(id = FormId("f"), sessionId = SessionId("s"), kind = FormKind.Other("elicitation"))

        assertEquals(FormKind.Other("elicitation"), form.kind)
        assertEquals(emptyList(), form.fields)
    }

    @Test
    fun `a permission request keeps action, resources and the session it belongs to`() {
        val request = PermissionRequest(
            id = PermissionRequestId("perm-1"),
            sessionId = SessionId("child-1"),
            action = "bash",
            resources = listOf("ls -la"),
            message = "Run this?",
        )

        assertEquals("bash", request.action)
        assertEquals(listOf("ls -la"), request.resources)
        assertEquals(SessionId("child-1"), request.sessionId)
    }

    @Test
    fun `a permission reply is once, always or reject`() {
        assertEquals(
            setOf(PermissionDecision.Once, PermissionDecision.Always, PermissionDecision.Reject),
            PermissionDecision.entries.toSet(),
        )
    }
}
