package io.github.thewisekd.phopencode.core.model

import java.math.BigDecimal

/** A reply to a form: field key to value. A choice is [DataValue.Text], several are [DataValue.Items]. */
typealias FormAnswers = Map<String, DataValue>

/**
 * A request for input from the user. A question from the model is a form of kind
 * [FormKind.Question], with one field per question.
 *
 * @property sessionId May be a child session.
 */
data class Form(
    val id: FormId,
    val sessionId: SessionId,
    val kind: FormKind,
    val title: String? = null,
    val description: String? = null,
    val fields: List<FormField> = emptyList(),
    val metadata: Map<String, DataValue> = emptyMap(),
)

sealed interface FormKind {
    data object Question : FormKind

    /** Any other form. [raw] is the kind the server named, if any. */
    data class Other(val raw: String? = null) : FormKind
}

/**
 * @property key What the answer is stored under, for example `q0`.
 * @property visibleWhen Null means always visible.
 */
sealed interface FormField {
    val key: String
    val label: String
    val description: String?
    val required: Boolean
    val visibleWhen: FieldCondition?

    /** @property allowCustom The user may type an answer of their own instead of picking an option. */
    data class SingleChoice(
        override val key: String,
        override val label: String,
        override val description: String? = null,
        override val required: Boolean = false,
        override val visibleWhen: FieldCondition? = null,
        val options: List<FormOption>,
        val allowCustom: Boolean = false,
        val defaultValue: String? = null,
    ) : FormField

    /** @property allowCustom The user may add answers of their own to the picked options. */
    data class MultipleChoice(
        override val key: String,
        override val label: String,
        override val description: String? = null,
        override val required: Boolean = false,
        override val visibleWhen: FieldCondition? = null,
        val options: List<FormOption>,
        val allowCustom: Boolean = false,
        val minSelected: Int? = null,
        val maxSelected: Int? = null,
        val defaultValue: List<String> = emptyList(),
    ) : FormField

    data class FreeText(
        override val key: String,
        override val label: String,
        override val description: String? = null,
        override val required: Boolean = false,
        override val visibleWhen: FieldCondition? = null,
        val minLength: Int? = null,
        val maxLength: Int? = null,
        val defaultValue: String? = null,
    ) : FormField

    data class Number(
        override val key: String,
        override val label: String,
        override val description: String? = null,
        override val required: Boolean = false,
        override val visibleWhen: FieldCondition? = null,
        val integerOnly: Boolean = false,
        val min: BigDecimal? = null,
        val max: BigDecimal? = null,
        val defaultValue: BigDecimal? = null,
    ) : FormField

    data class YesNo(
        override val key: String,
        override val label: String,
        override val description: String? = null,
        override val required: Boolean = false,
        override val visibleWhen: FieldCondition? = null,
        val defaultValue: Boolean? = null,
    ) : FormField

    /** A field type this app cannot draw. The server's `external` type lands here too. */
    data class Unknown(
        override val key: String,
        override val label: String,
        override val description: String? = null,
        override val required: Boolean = false,
        override val visibleWhen: FieldCondition? = null,
        val rawType: String,
    ) : FormField
}

data class FormOption(
    val value: String,
    val label: String,
    val description: String? = null,
)

/** When a field is visible, in terms of the answers to other fields. */
sealed interface FieldCondition {
    data class Equals(val key: String, val value: DataValue) : FieldCondition

    data class NotEquals(val key: String, val value: DataValue) : FieldCondition

    data class OneOf(val key: String, val values: List<DataValue>) : FieldCondition

    data class AllOf(val conditions: List<FieldCondition>) : FieldCondition

    data class AnyOf(val conditions: List<FieldCondition>) : FieldCondition

    /** A condition this app cannot evaluate. [raw] describes it, for diagnostics. */
    data class Unknown(val raw: String) : FieldCondition
}
