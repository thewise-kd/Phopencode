package io.github.thewisekd.phopencode.core.model

import java.math.BigDecimal

/** A reply to a form: field key to value. A choice is [DataValue.Text], several are [DataValue.Items]. */
typealias FormAnswers = Map<String, DataValue>

/**
 * A request for input from the user. A question from the model is a form of kind
 * [FormKind.Question], with one field per question.
 *
 * @property sessionId May be a child session.
 * @property kind From `metadata.kind`.
 * @property state Only the detail route sends it; null elsewhere.
 */
data class Form(
    val id: FormId,
    val sessionId: SessionId,
    val kind: FormKind,
    val title: String? = null,
    val fields: List<FormField> = emptyList(),
    val metadata: Map<String, DataValue> = emptyMap(),
    val state: FormState? = null,
)

sealed interface FormKind {
    data object Question : FormKind

    /** Any other form. [raw] is the kind the server named, if any. */
    data class Other(val raw: String? = null) : FormKind
}

sealed interface FormState {
    data object Pending : FormState

    data class Answered(val answer: FormAnswers) : FormState

    data class Cancelled(val message: String? = null) : FormState

    data class Unknown(val raw: String) : FormState
}

/**
 * @property key What the answer is stored under, for example `q0`.
 * @property title The short header. A field may have none, as a hidden field does.
 */
sealed interface FormField {
    val key: String
    val title: String?
    val description: String?

    /**
     * Every field but [External].
     *
     * @property hidden Not shown to the user.
     * @property visibleWhen Conditions from the server's `when`; empty means always visible. The spec
     *   does not say how several combine, and no recording has more than one.
     */
    sealed interface Input : FormField {
        val required: Boolean
        val hidden: Boolean
        val visibleWhen: List<FieldCondition>
    }

    /**
     * The server's `string` type with `options`.
     *
     * @property allowCustom The user may type an answer of their own instead of picking an option.
     */
    data class SingleChoice(
        override val key: String,
        override val title: String? = null,
        override val description: String? = null,
        override val required: Boolean = false,
        override val hidden: Boolean = false,
        override val visibleWhen: List<FieldCondition> = emptyList(),
        val options: List<FormOption>,
        val allowCustom: Boolean = false,
        val defaultValue: String? = null,
    ) : Input

    /**
     * The server's `multiselect` type.
     *
     * @property allowCustom The user may add answers of their own to the picked options.
     */
    data class MultipleChoice(
        override val key: String,
        override val title: String? = null,
        override val description: String? = null,
        override val required: Boolean = false,
        override val hidden: Boolean = false,
        override val visibleWhen: List<FieldCondition> = emptyList(),
        val options: List<FormOption>,
        val allowCustom: Boolean = false,
        val minSelected: Int? = null,
        val maxSelected: Int? = null,
        val defaultValue: List<String> = emptyList(),
    ) : Input

    /** The server's `string` type with no `options`. */
    data class FreeText(
        override val key: String,
        override val title: String? = null,
        override val description: String? = null,
        override val required: Boolean = false,
        override val hidden: Boolean = false,
        override val visibleWhen: List<FieldCondition> = emptyList(),
        val format: TextFormat? = null,
        val minLength: Int? = null,
        val maxLength: Int? = null,
        val pattern: String? = null,
        val placeholder: String? = null,
        val defaultValue: String? = null,
    ) : Input

    /** The server's `number` and `integer` types. The spec's `Infinity` and `NaN` limits become null. */
    data class Number(
        override val key: String,
        override val title: String? = null,
        override val description: String? = null,
        override val required: Boolean = false,
        override val hidden: Boolean = false,
        override val visibleWhen: List<FieldCondition> = emptyList(),
        val integerOnly: Boolean = false,
        val min: BigDecimal? = null,
        val max: BigDecimal? = null,
        val defaultValue: BigDecimal? = null,
    ) : Input

    data class YesNo(
        override val key: String,
        override val title: String? = null,
        override val description: String? = null,
        override val required: Boolean = false,
        override val hidden: Boolean = false,
        override val visibleWhen: List<FieldCondition> = emptyList(),
        val defaultValue: Boolean? = null,
    ) : Input

    /**
     * A step outside the app, such as signing in on a page. It has only these keys: no `when`,
     * `required` or `hidden`. The app opens [url] itself, and answers with `{key: true}`; an empty
     * answer is refused. [url] comes from the server, so treat it as untrusted.
     */
    data class External(
        override val key: String,
        override val title: String? = null,
        override val description: String? = null,
        val url: String,
    ) : FormField

    /** A field type this app cannot draw. */
    data class Unknown(
        override val key: String,
        override val title: String? = null,
        override val description: String? = null,
        override val required: Boolean = false,
        override val hidden: Boolean = false,
        override val visibleWhen: List<FieldCondition> = emptyList(),
        val rawType: String,
    ) : Input
}

/** The server's `format` of a `string` field. */
sealed interface TextFormat {
    data object Email : TextFormat

    data object Uri : TextFormat

    data object Date : TextFormat

    data object DateTime : TextFormat

    data class Unknown(val raw: String) : TextFormat
}

/** Answers carry [value]. */
data class FormOption(
    val value: String,
    val label: String,
    val description: String? = null,
)

/**
 * One entry of a field's `when`: the server's `{key, op, value}`, with `op` being `eq` or `neq`. The
 * value is a string, a number or a boolean.
 */
sealed interface FieldCondition {
    data class Equals(val key: String, val value: DataValue) : FieldCondition

    data class NotEquals(val key: String, val value: DataValue) : FieldCondition

    /** A condition this app cannot evaluate. [raw] describes it, for diagnostics. */
    data class Unknown(val raw: String) : FieldCondition
}
