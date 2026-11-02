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
                FormField.SingleChoice(key = "q0", label = "One?", options = options, allowCustom = true, required = true),
                FormField.MultipleChoice(key = "q1", label = "Many?", options = options, minSelected = 1, maxSelected = 2),
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
        val field = FormField.YesNo(key = "ok", label = "Proceed?")

        assertFalse(field.required)
        assertNull(field.visibleWhen)
        assertNull(field.defaultValue)
    }

    @Test
    fun `free text carries length limits`() {
        val field = FormField.FreeText(key = "name", label = "Name", minLength = 1, maxLength = 40, defaultValue = "x")

        assertEquals(1, field.minLength)
        assertEquals(40, field.maxLength)
    }

    @Test
    fun `a number field carries exact limits and may be integer only`() {
        val field = FormField.Number(
            key = "n",
            label = "Count",
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
            label = "Count",
            min = BigDecimal("-100e2147483647"),
            max = BigDecimal("100e2147483647"),
            defaultValue = BigDecimal("1e-2147483647"),
        )

        assertEquals(field, field.copy())
        assertEquals(field.hashCode(), field.copy().hashCode())
        assertTrue(field.toString().contains("Count"))
    }

    @Test
    fun `a field can be conditional on another field`() {
        val condition = FieldCondition.AllOf(
            listOf(
                FieldCondition.Equals(key = "q0", value = DataValue.Text("a")),
                FieldCondition.NotEquals(key = "q1", value = DataValue.Bool(true)),
                FieldCondition.OneOf(key = "q2", values = listOf(DataValue.Text("b"))),
                FieldCondition.AnyOf(listOf(FieldCondition.Unknown("future-operator"))),
            ),
        )
        val field = FormField.FreeText(key = "q3", label = "Why?", visibleWhen = condition)

        assertEquals(condition, field.visibleWhen)
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
