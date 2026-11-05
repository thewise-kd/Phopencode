package io.github.thewisekd.phopencode.feature.chat.logic.form

import io.github.thewisekd.phopencode.core.model.DataValue
import io.github.thewisekd.phopencode.core.model.FieldCondition
import io.github.thewisekd.phopencode.core.model.FormAnswers
import io.github.thewisekd.phopencode.core.model.FormField
import io.github.thewisekd.phopencode.core.model.TextFormat
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.FieldAnswer
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.FieldProblem
import java.math.BigDecimal
import java.net.URI
import java.net.URISyntaxException
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * The rules of one form's fields, as the contract (`FormContract.kt`) states them: the answer a field
 * starts from, which answers it takes, whether it is shown, what is wrong with an answer, and how an
 * answer is sent. Pure functions; nothing here calls the server or logs.
 *
 * A field's `pattern` is enforced by the server only: it is never compiled or run here, so no
 * regular expression from the server runs on the phone, and a pattern never yields
 * [FieldProblem.WrongFormat]. An answer the server refuses comes back as a failed reply. The
 * `format` checks below use this app's own fixed expressions and parsers.
 */
internal object FormFields {
    /** The answer [field] starts from: its default when it has one that fits, else none. */
    fun initialAnswer(field: FormField): FieldAnswer = when (field) {
        is FormField.SingleChoice -> {
            val default = field.defaultValue
            when {
                default == null -> FieldAnswer.SingleChoice()
                field.options.any { it.value == default } -> FieldAnswer.SingleChoice(option = default)
                field.allowCustom -> FieldAnswer.SingleChoice(own = default)
                else -> FieldAnswer.SingleChoice()
            }
        }
        is FormField.MultipleChoice -> {
            val offered = field.options.map { it.value }.toSet()
            val picked = field.defaultValue.filter { it in offered }.toSet()
            val own = if (field.allowCustom) field.defaultValue.firstOrNull { it !in offered } else null
            FieldAnswer.MultipleChoice(options = picked, own = own)
        }
        is FormField.FreeText -> FieldAnswer.Text(field.defaultValue.orEmpty())
        is FormField.Number -> FieldAnswer.Number(field.defaultValue?.toPlainString().orEmpty())
        is FormField.YesNo -> FieldAnswer.YesNo(field.defaultValue)
        is FormField.External -> FieldAnswer.External()
        is FormField.Unknown -> FieldAnswer.Unsupported
    }

    /**
     * Whether the user may set [answer] on [field]: it is of the field's kind, picks only offered
     * options, and has an own answer only where one is allowed. `External` is set by opening its URL
     * only, and an unknown type takes no answer.
     */
    fun accepts(field: FormField, answer: FieldAnswer): Boolean = when (field) {
        is FormField.SingleChoice -> answer is FieldAnswer.SingleChoice &&
            (answer.option == null || answer.own == null) &&
            (answer.option == null || field.options.any { it.value == answer.option }) &&
            (answer.own == null || field.allowCustom)
        is FormField.MultipleChoice -> answer is FieldAnswer.MultipleChoice &&
            field.options.map { it.value }.containsAll(answer.options) &&
            (answer.own == null || field.allowCustom)
        is FormField.FreeText -> answer is FieldAnswer.Text
        is FormField.Number -> answer is FieldAnswer.Number
        is FormField.YesNo -> answer is FieldAnswer.YesNo
        is FormField.External, is FormField.Unknown -> false
    }

    /**
     * The fields shown now, in the form's order: not `hidden`, and every `when` condition holds
     * against the answers that would be sent. A condition this app cannot evaluate holds.
     *
     * A condition may name a field that is itself conditional, so this settles in rounds; it stops
     * after one round per field at most, which ends even a set of conditions that never settles.
     */
    fun shown(fields: List<FormField>, answers: Map<String, FieldAnswer>): List<FormField> {
        val candidates = fields.filter { it.isCandidate() }
        var current = candidates
        repeat(fields.size + 1) {
            val values = encodeAll(current, answers)
            val next = candidates.filter { conditionsHold(it, values) }
            if (next == current) return current
            current = next
        }
        return current
    }

    private fun FormField.isCandidate(): Boolean = this !is FormField.Input || !hidden

    private fun conditionsHold(field: FormField, values: FormAnswers): Boolean {
        if (field !is FormField.Input) return true
        return field.visibleWhen.all { condition ->
            when (condition) {
                is FieldCondition.Equals -> values[condition.key] == condition.value
                is FieldCondition.NotEquals -> values[condition.key] != condition.value
                is FieldCondition.Unknown -> true
            }
        }
    }

    /** The reply for [fields] as shown: each one with an answer, under its own key. */
    fun encodeAll(fields: List<FormField>, answers: Map<String, FieldAnswer>): FormAnswers {
        val reply = LinkedHashMap<String, DataValue>()
        fields.forEach { field ->
            val value = encode(field, answers[field.key] ?: initialAnswer(field))
            if (value != null) reply[field.key] = value
        }
        return reply
    }

    /** How [answer] is sent for [field]; null when there is no answer to send. */
    fun encode(field: FormField, answer: FieldAnswer): DataValue? = when {
        field is FormField.SingleChoice && answer is FieldAnswer.SingleChoice -> {
            val own = answer.own?.trim()
            when {
                own != null -> own.takeIf { it.isNotEmpty() }?.let(DataValue::Text)
                answer.option != null -> DataValue.Text(answer.option)
                else -> null
            }
        }
        field is FormField.MultipleChoice && answer is FieldAnswer.MultipleChoice ->
            picks(field, answer).takeIf { it.isNotEmpty() }?.let { values -> DataValue.Items(values.map(DataValue::Text)) }
        field is FormField.FreeText && answer is FieldAnswer.Text ->
            answer.value.takeIf { it.isNotBlank() }?.let(DataValue::Text)
        field is FormField.Number && answer is FieldAnswer.Number -> parseNumber(answer.text)?.let(DataValue::Num)
        field is FormField.YesNo && answer is FieldAnswer.YesNo -> answer.value?.let(DataValue::Bool)
        field is FormField.External && answer is FieldAnswer.External -> if (answer.opened) DataValue.Bool(true) else null
        else -> null
    }

    /** The options picked, in the field's order, then the own answer. */
    private fun picks(field: FormField.MultipleChoice, answer: FieldAnswer.MultipleChoice): List<String> {
        val own = answer.own?.trim()?.takeIf { it.isNotEmpty() }
        return field.options.map { it.value }.filter { it in answer.options } + listOfNotNull(own)
    }

    /** Why [answer] would not do for [field] as shown; null when it would. */
    fun problem(field: FormField, answer: FieldAnswer): FieldProblem? {
        if (field is FormField.External) return if (answer is FieldAnswer.External && answer.opened) null else FieldProblem.NotOpened
        field as FormField.Input
        if (field is FormField.Unknown) return if (field.required) FieldProblem.Unsupported else null
        if (field is FormField.Number && answer is FieldAnswer.Number && answer.text.isNotBlank()) return numberProblem(field, answer.text)
        val value = encode(field, answer) ?: return if (field.required) FieldProblem.Required else null
        return when (field) {
            is FormField.MultipleChoice -> {
                val count = (value as DataValue.Items).values.size
                when {
                    field.minSelected != null && count < (field.minSelected ?: 0) -> FieldProblem.TooFewChoices
                    field.maxSelected != null && count > (field.maxSelected ?: Int.MAX_VALUE) -> FieldProblem.TooManyChoices
                    else -> null
                }
            }
            is FormField.FreeText -> textProblem(field, (value as DataValue.Text).value)
            else -> null
        }
    }

    private fun numberProblem(field: FormField.Number, text: String): FieldProblem? {
        val number = parseNumber(text) ?: return FieldProblem.NotANumber
        val min = field.min
        val max = field.max
        return when {
            field.integerOnly && number.stripTrailingZeros().scale() > 0 -> FieldProblem.NotAnInteger
            min != null && number < min -> FieldProblem.BelowMinimum
            max != null && number > max -> FieldProblem.AboveMaximum
            else -> null
        }
    }

    /** Length and `format`; the `pattern` is left to the server. */
    private fun textProblem(field: FormField.FreeText, text: String): FieldProblem? {
        val minLength = field.minLength
        val maxLength = field.maxLength
        return when {
            minLength != null && text.length < minLength -> FieldProblem.TooShort
            maxLength != null && text.length > maxLength -> FieldProblem.TooLong
            !matchesFormat(field.format, text) -> FieldProblem.WrongFormat
            else -> null
        }
    }

    /** Plain decimals only, with a comma accepted for the point: no exponent, no `NaN` or `Infinity`. */
    private val NUMBER = Regex("[+-]?(\\d+([.,]\\d*)?|[.,]\\d+)")

    fun parseNumber(text: String): BigDecimal? {
        val trimmed = text.trim()
        if (trimmed.length > MAX_NUMBER_LENGTH || !NUMBER.matches(trimmed)) return null
        return trimmed.replace(',', '.').toBigDecimalOrNull()
    }

    private val EMAIL = Regex("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")

    private fun matchesFormat(format: TextFormat?, text: String): Boolean = when (format) {
        null, is TextFormat.Unknown -> true
        TextFormat.Email -> EMAIL.matches(text)
        TextFormat.Uri -> try {
            URI(text).isAbsolute
        } catch (_: URISyntaxException) {
            false
        }
        TextFormat.Date -> parses { LocalDate.parse(text) }
        TextFormat.DateTime -> parses { OffsetDateTime.parse(text) }
    }

    private inline fun parses(parse: () -> Unit): Boolean = try {
        parse()
        true
    } catch (_: DateTimeParseException) {
        false
    }

    /** Whether the app may open [url]: an absolute `http` or `https` address with a host. */
    fun isWebUrl(url: String): Boolean = try {
        val uri = URI(url)
        val scheme = uri.scheme?.lowercase()
        (scheme == "http" || scheme == "https") && !uri.host.isNullOrEmpty()
    } catch (_: URISyntaxException) {
        false
    }

    private const val MAX_NUMBER_LENGTH = 100
}
