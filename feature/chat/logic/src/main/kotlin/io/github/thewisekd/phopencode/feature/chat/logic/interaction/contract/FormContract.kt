package io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract

import io.github.thewisekd.phopencode.core.model.FormField
import io.github.thewisekd.phopencode.core.model.FormId
import io.github.thewisekd.phopencode.core.model.SessionId
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatFailure
import io.github.thewisekd.phopencode.feature.chat.logic.contract.Presenter

/**
 * The forms the server waits on, the model's questions among them, as cards in the chat (P7.2
 * builds them, P7.3 draws them). One card per pending form, oldest first.
 *
 * Where they come from:
 * - This session's forms: `SessionState.forms` of the chat's `ChatSession`, kept by its sync.
 * - Its subagents' forms, at any depth: `InteractionApi.listForms(sessionId = null,
 *   location = Location(session.directory))`, kept to the forms whose session descends from this
 *   one (`Session.parentId`, as `SessionParentFilter.ChildrenOf` lists them). Live updates come
 *   from `client.events.events(types)` with `form.created`, `form.replied` and `form.cancelled`,
 *   collected inside `ChatSession.call`; every `ServerEvent.Connected` lists them again, since the
 *   server replays nothing.
 *
 * What a card shows: [FormCardState.fields] are the fields to show now: hidden ones and those whose
 * `when` ([FormField.Input.visibleWhen]) does not hold against the current answers are left out,
 * and come back as soon as it holds. A condition this app cannot evaluate counts as holding, so a
 * field is never lost. Each field starts from its default, if it has one.
 *
 * Checking ([FieldProblem]), on every change: `required` with no answer; choices outside
 * `minSelected`..`maxSelected`; a number that does not parse, is not whole when `integerOnly`, or is
 * outside `min`..`max`; text outside `minLength`..`maxLength`, not matching `pattern` or its
 * `format`; an `External` step not opened yet; an unknown field type that is required.
 *
 * Replying: `InteractionApi.replyForm(card.sessionId, card.formId, answers)` through
 * `ChatSession.call`, to the session the form names, which may be a subagent's. Each shown field
 * with an answer goes under its own key (a question's is `q<n>`): [FieldAnswer.SingleChoice] as
 * `DataValue.Text` of the option's `value` or of the own answer; [FieldAnswer.MultipleChoice] as
 * `DataValue.Items` of `DataValue.Text`, the options in their order, then the own answer;
 * [FieldAnswer.Text] as `DataValue.Text`; [FieldAnswer.Number] as `DataValue.Num`;
 * [FieldAnswer.YesNo] as `DataValue.Bool`; [FieldAnswer.External] as `DataValue.Bool(true)`. A
 * field with no answer, and every field not shown, is left out. An unknown field type is never
 * answered, and a form with a required one can only be declined. Declining is
 * `InteractionApi.cancelForm`; for a question it ends the whole execution.
 *
 * A card leaves once the server settles the form, by an event or a new list; a failed reply keeps
 * the card and its answers.
 *
 * @property cards Oldest first.
 * @property error Listing the subagents' forms failed; this session's still show.
 */
data class FormsState(
    val cards: List<FormCardState> = emptyList(),
    val error: ChatFailure? = null,
)

/**
 * One pending form.
 *
 * @property sessionId The session the form belongs to; replies go there.
 * @property fromSubagent The form belongs to a subagent's session, not this one.
 * @property question A question from the model (`FormKind.Question`), drawn as one step per field.
 * @property title The form's title, as the server sent it. Prints masked.
 * @property fields The fields shown now, in the form's order.
 * @property step The field in view when the card shows one field at a time; 0 until moved.
 * @property attempted A submit was tried with a problem left: the card shows every field's
 *   problem from then on, not only those of fields the user touched.
 * @property submitting A reply is on its way.
 * @property declining A decline is on its way.
 * @property error The last reply or decline failed.
 */
data class FormCardState(
    val formId: FormId,
    val sessionId: SessionId,
    val fromSubagent: Boolean = false,
    val question: Boolean = false,
    val title: String? = null,
    val fields: List<FormFieldState> = emptyList(),
    val step: Int = 0,
    val attempted: Boolean = false,
    val submitting: Boolean = false,
    val declining: Boolean = false,
    val error: FormError? = null,
) {
    val busy: Boolean
        get() = submitting || declining

    /** Whether [FormIntent.Submit] would send: nothing on its way, and no field with a problem. */
    val canSubmit: Boolean
        get() = !busy && fields.all { it.problem == null }

    /** Whether [FormIntent.Decline] would send. */
    val canDecline: Boolean
        get() = !busy

    val stepCount: Int
        get() = fields.size

    val isLastStep: Boolean
        get() = step >= fields.size - 1

    /** The field at [step], or null when nothing is shown. */
    val currentField: FormFieldState?
        get() = fields.getOrNull(step)

    override fun toString(): String =
        "FormCardState(formId=${formId.value}, sessionId=${sessionId.value}, fromSubagent=$fromSubagent, " +
            "question=$question, title=***, fields=$fields, step=$step, attempted=$attempted, " +
            "submitting=$submitting, declining=$declining, error=$error)"
}

/**
 * One field as shown: what the server asks ([field], its title, options and limits) and the
 * answer so far.
 *
 * @property field The server's field; its kind tells which [answer] goes with it.
 * @property answer The [FieldAnswer] kind that matches [field]: [FieldAnswer.Unsupported] for
 *   `FormField.Unknown`.
 * @property problem Why the answer would not do now; null when it would.
 */
data class FormFieldState(
    val field: FormField,
    val answer: FieldAnswer,
    val problem: FieldProblem? = null,
) {
    /** Field titles and options are the model's text. */
    override fun toString(): String =
        "FormFieldState(key=${field.key}, type=${field::class.simpleName}, answer=$answer, problem=$problem)"
}

/** The answer to one field so far, by the field's kind. Prints no answer. */
sealed interface FieldAnswer {
    /**
     * For `FormField.SingleChoice`.
     *
     * @property option The `FormOption.value` picked; null when none is.
     * @property own The own answer ("your own answer"), when that is picked instead of an option,
     *   with the text typed so far; null while an option or nothing is picked.
     */
    data class SingleChoice(val option: String? = null, val own: String? = null) : FieldAnswer {
        override fun toString(): String = "SingleChoice(option=${option.mark()}, own=${own.mark()})"
    }

    /**
     * For `FormField.MultipleChoice`.
     *
     * @property options The `FormOption.value`s picked.
     * @property own An answer of the user's own added to them; null when none is.
     */
    data class MultipleChoice(val options: Set<String> = emptySet(), val own: String? = null) : FieldAnswer {
        override fun toString(): String = "MultipleChoice(options=${options.size}, own=${own.mark()})"
    }

    /** For `FormField.FreeText`. */
    data class Text(val value: String = "") : FieldAnswer {
        override fun toString(): String = "Text(***)"
    }

    /** For `FormField.Number`: the text as typed, checked and sent as a number. */
    data class Number(val text: String = "") : FieldAnswer {
        override fun toString(): String = "Number(***)"
    }

    /** For `FormField.YesNo`; null until picked. */
    data class YesNo(val value: Boolean? = null) : FieldAnswer

    /**
     * For `FormField.External`: whether the app has opened its URL. Only then can the field be
     * answered, with `true`.
     */
    data class External(val opened: Boolean = false) : FieldAnswer

    /** For a field type this app cannot draw: it takes no answer. */
    data object Unsupported : FieldAnswer
}

private fun String?.mark(): String = if (this == null) "null" else "***"

/** Why a field's answer would not do. */
enum class FieldProblem {
    /** `required`, with no answer. */
    Required,

    /** Fewer choices than `minSelected`. */
    TooFewChoices,

    /** More choices than `maxSelected`. */
    TooManyChoices,

    /** The text is not a number. */
    NotANumber,

    /** `integerOnly`, and the number is not whole. */
    NotAnInteger,

    /** Below `min`. */
    BelowMinimum,

    /** Above `max`. */
    AboveMaximum,

    /** Shorter than `minLength`. */
    TooShort,

    /** Longer than `maxLength`. */
    TooLong,

    /** Does not match `pattern` or `format`. */
    WrongFormat,

    /** An `External` step whose URL was not opened yet. */
    NotOpened,

    /** A required field of a type this app cannot draw: the form can only be declined. */
    Unsupported,
}

enum class FormAction {
    Submit,
    Decline,
}

/** @property reason `Rejected` for an answer the server refused, `Conflict` for a settled form. */
data class FormError(val action: FormAction, val reason: ChatFailure)

sealed interface FormIntent {
    /** Sets the answer of the field [key] of the form [formId]. An answer of the wrong kind is ignored. */
    data class Answer(val formId: FormId, val key: String, val answer: FieldAnswer) : FormIntent

    /** Shows the field at [step]; out of range is clamped. */
    data class GoToStep(val formId: FormId, val step: Int) : FormIntent

    /**
     * Opens the URL of the `External` field [key]: sends [FormEvent.OpenUrl] and marks the field
     * opened. Only an `http` or `https` URL is opened; any other is ignored.
     */
    data class OpenExternal(val formId: FormId, val key: String) : FormIntent

    /**
     * Sends the answers when [FormCardState.canSubmit]; with a problem left, sets
     * [FormCardState.attempted] and moves [FormCardState.step] to the first field with one.
     */
    data class Submit(val formId: FormId) : FormIntent

    /** Declines the form. */
    data class Decline(val formId: FormId) : FormIntent

    data class DismissError(val formId: FormId) : FormIntent

    /** Lists the subagents' forms again after [FormsState.error]. */
    data object Retry : FormIntent
}

sealed interface FormEvent {
    /** The chat opens [url] outside the app. Always `http` or `https`. Prints masked: it may hold a token. */
    data class OpenUrl(val url: String) : FormEvent {
        override fun toString(): String = "OpenUrl(***)"
    }
}

interface FormPresenter : Presenter<FormsState, FormIntent, FormEvent>
