package io.github.thewisekd.phopencode.feature.chat.ui.form

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.thewisekd.phopencode.core.model.FormField
import io.github.thewisekd.phopencode.core.model.FormId
import io.github.thewisekd.phopencode.core.model.FormOption
import io.github.thewisekd.phopencode.core.model.TextFormat
import io.github.thewisekd.phopencode.core.ui.component.ErrorBanner
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.FieldAnswer
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.FieldProblem
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.FormAction
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.FormCardState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.FormFieldState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.FormIntent
import io.github.thewisekd.phopencode.feature.chat.ui.R
import java.math.BigDecimal
import java.net.URI
import java.net.URISyntaxException

/**
 * One pending form (P7.3): its fields as radio buttons, checkboxes, "your own answer", text, number
 * and yes/no inputs, one step at a time for a question with several fields, and its send and
 * decline. Draws [card], sends [onIntent] for it; the presenter checks and keeps the answers.
 *
 * A field's problem shows once the user has answered that field here, or once a send was tried
 * ([FormCardState.attempted]); a field this app cannot draw shows its problem at once. Which
 * fields were touched is the only thing the card keeps itself.
 *
 * The chat puts the card in a column that scrolls and is capped in height, so the card neither
 * scrolls nor fills its height, and text fields grow rather than scroll. Nothing animates without
 * end: a reply on its way is a still label. The server's texts are drawn, never logged; an
 * external step's URL shows only its host, as it may hold a token, and only an `http` or `https`
 * one can be opened.
 */
@Composable
fun FormCard(
    card: FormCardState,
    onIntent: (FormIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = PhopencodeTheme.spacing
    var touched by remember(card.formId) { mutableStateOf(emptySet<String>()) }
    val send: (FormIntent) -> Unit = { intent ->
        when (intent) {
            is FormIntent.Answer -> touched = touched + intent.key
            is FormIntent.OpenExternal -> touched = touched + intent.key
            else -> Unit
        }
        onIntent(intent)
    }
    val stepped = card.question && card.stepCount > 1
    val shown = if (stepped) listOfNotNull(card.currentField) else card.fields
    Surface(
        modifier = modifier.padding(horizontal = spacing.md, vertical = spacing.xs),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Heading(card)
            if (stepped) {
                Text(
                    text = stringResource(R.string.chat_form_step, card.step + 1, card.stepCount),
                    modifier = Modifier.testTag(FormTags.STEP),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            card.error?.let { error ->
                ErrorBanner(
                    modifier = Modifier.testTag(FormTags.ERROR),
                    message = stringResource(
                        if (error.action == FormAction.Submit) R.string.chat_form_error_submit else R.string.chat_form_error_decline,
                        stringResource(error.reason.reasonRes()),
                    ),
                    onDismiss = { onIntent(FormIntent.DismissError(card.formId)) },
                )
            }
            shown.forEach { field ->
                key(field.field.key) {
                    Field(
                        formId = card.formId,
                        state = field,
                        showProblem = card.attempted || field.field.key in touched || field.problem == FieldProblem.Unsupported,
                        enabled = !card.busy,
                        onIntent = send,
                    )
                }
            }
            if (card.busy) {
                Text(
                    text = stringResource(if (card.submitting) R.string.chat_form_sending else R.string.chat_form_declining),
                    modifier = Modifier
                        .testTag(FormTags.PROGRESS)
                        .semantics {
                            progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
                            liveRegion = LiveRegionMode.Polite
                        },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            Buttons(card, stepped, onIntent)
            if (card.question) {
                Text(
                    text = stringResource(R.string.chat_form_decline_question_hint),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** The form's title, or what kind of form it is, and whether a subagent asks. */
@Composable
private fun Heading(card: FormCardState) {
    Column(Modifier.fillMaxWidth()) {
        if (card.fromSubagent) {
            Text(
                text = stringResource(R.string.chat_form_from_subagent),
                modifier = Modifier.testTag(FormTags.SUBAGENT),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelMedium,
            )
        }
        val title = card.title?.takeIf { it.isNotBlank() }
            ?: stringResource(if (card.question) R.string.chat_form_question_heading else R.string.chat_form_form_heading)
        Text(
            text = title,
            modifier = Modifier.testTag(FormTags.HEADING).semantics { heading() },
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleSmall,
        )
    }
}

/** Back and Next between steps; Send on the last one, or always without steps; Decline always. */
@Composable
private fun Buttons(card: FormCardState, stepped: Boolean, onIntent: (FormIntent) -> Unit) {
    val spacing = PhopencodeTheme.spacing
    val touch = Modifier.heightIn(min = spacing.touchTarget)
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing.sm, Alignment.End),
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        TextButton(
            onClick = { onIntent(FormIntent.Decline(card.formId)) },
            enabled = card.canDecline,
            modifier = touch.testTag(FormTags.DECLINE),
        ) {
            Text(stringResource(R.string.chat_form_decline))
        }
        if (stepped && card.step > 0) {
            OutlinedButton(
                onClick = { onIntent(FormIntent.GoToStep(card.formId, card.step - 1)) },
                enabled = !card.busy,
                modifier = touch.testTag(FormTags.BACK),
            ) {
                Text(stringResource(R.string.chat_form_back))
            }
        }
        if (stepped && !card.isLastStep) {
            Button(
                onClick = { onIntent(FormIntent.GoToStep(card.formId, card.step + 1)) },
                enabled = !card.busy,
                modifier = touch.testTag(FormTags.NEXT),
            ) {
                Text(stringResource(R.string.chat_form_next))
            }
        } else {
            // Enabled with a problem left: the presenter then shows every problem and the first one's step.
            Button(
                onClick = { onIntent(FormIntent.Submit(card.formId)) },
                enabled = !card.busy,
                modifier = touch.testTag(FormTags.SUBMIT),
            ) {
                Text(stringResource(R.string.chat_form_submit))
            }
        }
    }
}

/** One field: its title, whether it is required, its description, its input and its problem. */
@Composable
private fun Field(
    formId: FormId,
    state: FormFieldState,
    showProblem: Boolean,
    enabled: Boolean,
    onIntent: (FormIntent) -> Unit,
) {
    val spacing = PhopencodeTheme.spacing
    val field = state.field
    val problem = state.problem?.takeIf { showProblem }?.let { problemText(it, field) }
    val answer: (FieldAnswer) -> Unit = { onIntent(FormIntent.Answer(formId, field.key, it)) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(FormTags.field(field.key))
            .semantics { if (problem != null) error(problem) },
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        val title = field.title?.takeIf { it.isNotBlank() }
        val required = (field as? FormField.Input)?.required == true
        if (title != null || required) {
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                if (title != null) {
                    Text(
                        text = title,
                        modifier = Modifier.weight(1f, fill = false),
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                if (required) {
                    Text(
                        text = stringResource(R.string.chat_form_required),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
        field.description?.takeIf { it.isNotBlank() }?.let {
            Text(text = it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        when (field) {
            is FormField.SingleChoice -> SingleChoice(
                field,
                state.answer as? FieldAnswer.SingleChoice ?: FieldAnswer.SingleChoice(),
                enabled,
                answer,
            )
            is FormField.MultipleChoice -> MultipleChoice(
                field,
                state.answer as? FieldAnswer.MultipleChoice ?: FieldAnswer.MultipleChoice(),
                enabled,
                answer,
            )
            is FormField.FreeText -> FreeText(field, state.answer as? FieldAnswer.Text ?: FieldAnswer.Text(), enabled, problem != null, answer)
            is FormField.Number -> NumberInput(
                field,
                state.answer as? FieldAnswer.Number ?: FieldAnswer.Number(),
                enabled,
                problem != null,
                answer,
            )
            is FormField.YesNo -> YesNo(field, state.answer as? FieldAnswer.YesNo ?: FieldAnswer.YesNo(), enabled, answer)
            is FormField.External -> External(
                field,
                state.answer as? FieldAnswer.External ?: FieldAnswer.External(),
                enabled,
            ) { onIntent(FormIntent.OpenExternal(formId, field.key)) }
            is FormField.Unknown -> Text(
                text = stringResource(R.string.chat_form_unsupported),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (problem != null) {
            Text(
                text = problem,
                modifier = Modifier.testTag(FormTags.problem(field.key)).semantics { liveRegion = LiveRegionMode.Polite },
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun SingleChoice(
    field: FormField.SingleChoice,
    answer: FieldAnswer.SingleChoice,
    enabled: Boolean,
    onAnswer: (FieldAnswer) -> Unit,
) {
    val own = answer.own
    Column(Modifier.fillMaxWidth().selectableGroup()) {
        field.options.forEach { option ->
            Choice(
                label = option.label,
                description = option.description,
                checked = own == null && answer.option == option.value,
                role = Role.RadioButton,
                enabled = enabled,
                tag = FormTags.option(field.key, option.value),
            ) { onAnswer(FieldAnswer.SingleChoice(option = option.value)) }
        }
        if (field.allowCustom) {
            Choice(
                label = stringResource(R.string.chat_form_own_answer),
                description = null,
                checked = own != null,
                role = Role.RadioButton,
                enabled = enabled,
                tag = FormTags.own(field.key),
            ) { onAnswer(FieldAnswer.SingleChoice(own = own ?: "")) }
        }
    }
    if (field.allowCustom && own != null) {
        OwnAnswer(field.key, own, enabled) { onAnswer(FieldAnswer.SingleChoice(own = it)) }
    }
}

@Composable
private fun MultipleChoice(
    field: FormField.MultipleChoice,
    answer: FieldAnswer.MultipleChoice,
    enabled: Boolean,
    onAnswer: (FieldAnswer) -> Unit,
) {
    choicesHint(field)?.let {
        Text(text = it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
    val own = answer.own
    Column(Modifier.fillMaxWidth()) {
        field.options.forEach { option ->
            val checked = option.value in answer.options
            Choice(
                label = option.label,
                description = option.description,
                checked = checked,
                role = Role.Checkbox,
                enabled = enabled,
                tag = FormTags.option(field.key, option.value),
            ) {
                val options = if (checked) answer.options - option.value else answer.options + option.value
                onAnswer(answer.copy(options = options.ordered(field.options)))
            }
        }
        if (field.allowCustom) {
            Choice(
                label = stringResource(R.string.chat_form_own_answer),
                description = null,
                checked = own != null,
                role = Role.Checkbox,
                enabled = enabled,
                tag = FormTags.own(field.key),
            ) { onAnswer(answer.copy(own = if (own == null) "" else null)) }
        }
    }
    if (field.allowCustom && own != null) {
        OwnAnswer(field.key, own, enabled) { onAnswer(answer.copy(own = it)) }
    }
}

/** [this] in the order of [options], unknown values last; a set's order is only for reading. */
private fun Set<String>.ordered(options: List<FormOption>): Set<String> {
    val known = options.map { it.value }.filter { it in this }
    return LinkedHashSet(known + (this - known.toSet()))
}

/**
 * One radio button or checkbox with its label and description, the whole row its touch target.
 * The control itself takes no click, so the row is one node with the [role] and the state.
 */
@Composable
private fun Choice(
    label: String,
    description: String?,
    checked: Boolean,
    role: Role,
    enabled: Boolean,
    tag: String,
    onClick: () -> Unit,
) {
    val spacing = PhopencodeTheme.spacing
    val select = if (role == Role.Checkbox) {
        Modifier.toggleable(value = checked, enabled = enabled, role = role, onValueChange = { onClick() })
    } else {
        Modifier.selectable(selected = checked, enabled = enabled, role = role, onClick = onClick)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = spacing.touchTarget)
            .testTag(tag)
            .then(select)
            .padding(vertical = spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        if (role == Role.Checkbox) {
            Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        } else {
            RadioButton(selected = checked, onClick = null, enabled = enabled)
        }
        Column(Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            if (!description.isNullOrBlank()) {
                Text(
                    text = description,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** The text of the own answer; it grows with its text rather than scroll inside the card. */
@Composable
private fun OwnAnswer(key: String, text: String, enabled: Boolean, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = text,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth().testTag(FormTags.ownInput(key)),
        enabled = enabled,
        label = { Text(stringResource(R.string.chat_form_own_answer_field)) },
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
    )
}

@Composable
private fun FreeText(
    field: FormField.FreeText,
    answer: FieldAnswer.Text,
    enabled: Boolean,
    isError: Boolean,
    onAnswer: (FieldAnswer) -> Unit,
) {
    val placeholder = field.placeholder?.takeIf { it.isNotBlank() }
    OutlinedTextField(
        value = answer.value,
        onValueChange = { onAnswer(FieldAnswer.Text(it)) },
        modifier = Modifier.fillMaxWidth().testTag(FormTags.input(field.key)),
        enabled = enabled,
        label = { Text(stringResource(R.string.chat_form_answer_field)) },
        placeholder = placeholder?.let { { Text(it) } },
        isError = isError,
        singleLine = field.format != null,
        keyboardOptions = when (field.format) {
            TextFormat.Email -> KeyboardOptions(keyboardType = KeyboardType.Email)
            TextFormat.Uri -> KeyboardOptions(keyboardType = KeyboardType.Uri)
            else -> KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)
        },
    )
}

@Composable
private fun NumberInput(
    field: FormField.Number,
    answer: FieldAnswer.Number,
    enabled: Boolean,
    isError: Boolean,
    onAnswer: (FieldAnswer) -> Unit,
) {
    numberHint(field)?.let {
        Text(text = it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
    // The number keyboards have no minus sign: a field that takes a negative gets the text one.
    val min = field.min
    val negative = min == null || min < BigDecimal.ZERO
    val keyboard = when {
        negative -> KeyboardType.Text
        field.integerOnly -> KeyboardType.Number
        else -> KeyboardType.Decimal
    }
    OutlinedTextField(
        value = answer.text,
        onValueChange = { onAnswer(FieldAnswer.Number(it)) },
        modifier = Modifier.fillMaxWidth().testTag(FormTags.input(field.key)),
        enabled = enabled,
        label = { Text(stringResource(R.string.chat_form_answer_field)) },
        isError = isError,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
    )
}

@Composable
private fun YesNo(field: FormField.YesNo, answer: FieldAnswer.YesNo, enabled: Boolean, onAnswer: (FieldAnswer) -> Unit) {
    Column(Modifier.fillMaxWidth().selectableGroup()) {
        Choice(
            label = stringResource(R.string.chat_form_yes),
            description = null,
            checked = answer.value == true,
            role = Role.RadioButton,
            enabled = enabled,
            tag = FormTags.yes(field.key),
        ) { onAnswer(FieldAnswer.YesNo(true)) }
        Choice(
            label = stringResource(R.string.chat_form_no),
            description = null,
            checked = answer.value == false,
            role = Role.RadioButton,
            enabled = enabled,
            tag = FormTags.no(field.key),
        ) { onAnswer(FieldAnswer.YesNo(false)) }
    }
}

/**
 * A step on a page outside the app. Only a web address can be opened, and only its host shows:
 * the rest may hold a token. Opening goes through the presenter, which marks the step opened.
 */
@Composable
private fun External(field: FormField.External, answer: FieldAnswer.External, enabled: Boolean, onOpen: () -> Unit) {
    val page = remember(field.url) { webPage(field.url) }
    if (page == null) {
        Text(
            text = stringResource(R.string.chat_form_external_blocked),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
        )
        return
    }
    page.host?.let { host ->
        Text(
            text = stringResource(R.string.chat_form_external_host, host),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
    OutlinedButton(
        onClick = onOpen,
        enabled = enabled,
        modifier = Modifier.heightIn(min = PhopencodeTheme.spacing.touchTarget).testTag(FormTags.open(field.key)),
    ) {
        Text(stringResource(R.string.chat_form_external_open))
    }
    if (answer.opened) {
        Text(
            text = stringResource(R.string.chat_form_external_opened),
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** A web page's address, as far as the card shows it. */
private class WebPage(val host: String?)

/** [url] when it is `http` or `https`, with its host; null for any other scheme or no URL at all. */
private fun webPage(url: String): WebPage? {
    val uri = try {
        URI(url.trim())
    } catch (_: URISyntaxException) {
        return null
    }
    val scheme = uri.scheme?.lowercase() ?: return null
    if (scheme != "http" && scheme != "https") return null
    return WebPage(uri.host?.takeIf { it.isNotBlank() })
}

/** How many of a multiple choice to pick, when the field says. */
@Composable
private fun choicesHint(field: FormField.MultipleChoice): String? {
    val min = field.minSelected?.takeIf { it > 0 }
    val max = field.maxSelected
    return when {
        min != null && max != null -> stringResource(R.string.chat_form_choices_range, min, max)
        min != null -> pluralStringResource(R.plurals.chat_form_choices_min, min, min)
        max != null -> pluralStringResource(R.plurals.chat_form_choices_max, max, max)
        else -> null
    }
}

/** A number's range and whether it must be whole, when the field says. */
@Composable
private fun numberHint(field: FormField.Number): String? {
    val min = field.min?.toPlainString()
    val max = field.max?.toPlainString()
    val range = when {
        min != null && max != null -> stringResource(R.string.chat_form_number_range, min, max)
        min != null -> stringResource(R.string.chat_form_number_min, min)
        max != null -> stringResource(R.string.chat_form_number_max, max)
        else -> null
    }
    val whole = if (field.integerOnly) stringResource(R.string.chat_form_number_whole) else null
    return when {
        range != null && whole != null -> stringResource(R.string.chat_form_hint_join, range, whole)
        else -> range ?: whole
    }
}

/** What to do about [problem], with the field's own limits where it has them. */
@Composable
private fun problemText(problem: FieldProblem, field: FormField): String = when (problem) {
    FieldProblem.Required -> stringResource(R.string.chat_form_problem_required)
    FieldProblem.TooFewChoices -> (field as? FormField.MultipleChoice)?.minSelected
        ?.let { pluralStringResource(R.plurals.chat_form_problem_too_few, it, it) }
        ?: stringResource(R.string.chat_form_problem_choices)
    FieldProblem.TooManyChoices -> (field as? FormField.MultipleChoice)?.maxSelected
        ?.let { pluralStringResource(R.plurals.chat_form_problem_too_many, it, it) }
        ?: stringResource(R.string.chat_form_problem_choices)
    FieldProblem.NotANumber -> stringResource(R.string.chat_form_problem_not_a_number)
    FieldProblem.NotAnInteger -> stringResource(R.string.chat_form_problem_not_an_integer)
    FieldProblem.BelowMinimum -> (field as? FormField.Number)?.min
        ?.let { stringResource(R.string.chat_form_problem_below_minimum, it.toPlainString()) }
        ?: stringResource(R.string.chat_form_problem_out_of_range)
    FieldProblem.AboveMaximum -> (field as? FormField.Number)?.max
        ?.let { stringResource(R.string.chat_form_problem_above_maximum, it.toPlainString()) }
        ?: stringResource(R.string.chat_form_problem_out_of_range)
    FieldProblem.TooShort -> (field as? FormField.FreeText)?.minLength
        ?.let { pluralStringResource(R.plurals.chat_form_problem_too_short, it, it) }
        ?: stringResource(R.string.chat_form_problem_length)
    FieldProblem.TooLong -> (field as? FormField.FreeText)?.maxLength
        ?.let { pluralStringResource(R.plurals.chat_form_problem_too_long, it, it) }
        ?: stringResource(R.string.chat_form_problem_length)
    FieldProblem.WrongFormat -> stringResource(R.string.chat_form_problem_wrong_format)
    FieldProblem.NotOpened -> stringResource(R.string.chat_form_problem_not_opened)
    FieldProblem.Unsupported -> stringResource(R.string.chat_form_problem_unsupported)
}
