package io.github.thewisekd.phopencode.feature.chat.ui.form

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.unit.Dp
import io.github.thewisekd.phopencode.core.model.FormField
import io.github.thewisekd.phopencode.core.model.FormId
import io.github.thewisekd.phopencode.core.model.FormOption
import io.github.thewisekd.phopencode.core.model.SessionId
import io.github.thewisekd.phopencode.core.model.TextFormat
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatFailure
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.FieldAnswer
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.FieldProblem
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.FormCardState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.FormFieldState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.FormIntent
import java.math.BigDecimal

/** The tag the harness puts on the card's modifier, as the chat puts its slot tag there. */
internal const val CARD_TAG = "test:form"

internal val FORM_ID = FormId("frm_1")
internal val SESSION_ID = SessionId("ses_1")

/** Made-up server texts, with Turkish letters so a lost character would show. */
internal const val FORM_TITLE = "Kurulum ayarları"
internal const val SINGLE_TITLE = "Hangi veritabanı kullanılsın?"
internal const val SINGLE_DESCRIPTION = "Sonradan değiştirilebilir."
internal const val MULTI_TITLE = "Hangi testler çalışsın?"
internal const val NUMBER_TITLE = "Kaç kopya?"
internal const val YES_NO_TITLE = "Değişiklikler kaydedilsin mi?"
internal const val TEXT_TITLE = "Proje adı"
internal const val TEXT_PLACEHOLDER = "ör. çiçek-bahçesi"
internal const val EXTERNAL_TITLE = "Hesabınla giriş yap"
internal const val UNKNOWN_TITLE = "Dosya yükle"
internal const val OWN_TEXT = "Öncelikle MongoDB"

/** A URL with a token in its query: the card shows only its host. */
internal const val EXTERNAL_URL = "https://auth.example.com/login?token=s3cr3t-t0ken"
internal const val EXTERNAL_HOST = "auth.example.com"

internal val POSTGRES = FormOption("pg", "PostgreSQL", "İlişkisel, sunucuda çalışır")
internal val SQLITE = FormOption("sqlite", "SQLite")
internal val UNIT = FormOption("unit", "Birim testleri")
internal val UI = FormOption("ui", "Arayüz testleri")
internal val E2E = FormOption("e2e", "Uçtan uca testler")

internal val singleField = FormField.SingleChoice(
    key = "q0",
    title = SINGLE_TITLE,
    description = SINGLE_DESCRIPTION,
    required = true,
    options = listOf(POSTGRES, SQLITE),
    allowCustom = true,
)

internal val multiField = FormField.MultipleChoice(
    key = "q1",
    title = MULTI_TITLE,
    options = listOf(UNIT, UI, E2E),
    allowCustom = true,
    minSelected = 1,
    maxSelected = 2,
)

internal val numberField = FormField.Number(
    key = "copies",
    title = NUMBER_TITLE,
    integerOnly = true,
    min = BigDecimal("1"),
    max = BigDecimal("10"),
)

internal val yesNoField = FormField.YesNo(key = "save", title = YES_NO_TITLE)

internal val textField = FormField.FreeText(
    key = "name",
    title = TEXT_TITLE,
    placeholder = TEXT_PLACEHOLDER,
    format = TextFormat.Email,
)

internal val externalField = FormField.External(key = "login", title = EXTERNAL_TITLE, url = EXTERNAL_URL)

internal val unknownField = FormField.Unknown(key = "file", title = UNKNOWN_TITLE, required = true, rawType = "file")

internal fun single(answer: FieldAnswer.SingleChoice = FieldAnswer.SingleChoice(), problem: FieldProblem? = null) =
    FormFieldState(singleField, answer, problem)

internal fun multi(answer: FieldAnswer.MultipleChoice = FieldAnswer.MultipleChoice(), problem: FieldProblem? = null) =
    FormFieldState(multiField, answer, problem)

internal fun number(text: String = "", problem: FieldProblem? = null) =
    FormFieldState(numberField, FieldAnswer.Number(text), problem)

internal fun yesNo(value: Boolean? = null, problem: FieldProblem? = null) =
    FormFieldState(yesNoField, FieldAnswer.YesNo(value), problem)

internal fun text(value: String = "", problem: FieldProblem? = null) =
    FormFieldState(textField, FieldAnswer.Text(value), problem)

internal fun external(
    opened: Boolean = false,
    url: String = EXTERNAL_URL,
    problem: FieldProblem? = null,
) = FormFieldState(externalField.copy(url = url), FieldAnswer.External(opened), problem)

internal fun unknown(problem: FieldProblem? = FieldProblem.Unsupported) =
    FormFieldState(unknownField, FieldAnswer.Unsupported, problem)

/** A question from the model with [fields], one step each. */
internal fun question(vararg fields: FormFieldState, step: Int = 0) =
    FormCardState(formId = FORM_ID, sessionId = SESSION_ID, question = true, fields = fields.toList(), step = step)

/** A form that is not a question, all its [fields] at once. */
internal fun form(vararg fields: FormFieldState, title: String? = FORM_TITLE) =
    FormCardState(formId = FORM_ID, sessionId = SESSION_ID, title = title, fields = fields.toList())

internal fun hasRole(role: Role): SemanticsMatcher = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)

/**
 * The card in the app theme, placed the way `ChatScreen` places it: full width in a column that
 * scrolls, capped at [maxHeight] when given. [state] is changeable by the test and every intent is
 * recorded.
 */
internal class FormHarness(initial: FormCardState, private val maxHeight: Dp? = null) {
    val state: MutableState<FormCardState> = mutableStateOf(initial)
    val intents = mutableListOf<FormIntent>()

    @Composable
    fun Content() {
        PhopencodeTheme(darkTheme = false) {
            Surface {
                val capped = maxHeight?.let { Modifier.heightIn(max = it) } ?: Modifier.fillMaxSize()
                Column(Modifier.fillMaxWidth().then(capped).verticalScroll(rememberScrollState())) {
                    FormCard(state.value, { intents += it }, Modifier.fillMaxWidth().testTag(CARD_TAG))
                }
            }
        }
    }
}

internal fun ComposeContentTestRule.setForm(state: FormCardState, maxHeight: Dp? = null): FormHarness {
    val harness = FormHarness(state, maxHeight)
    setContent { harness.Content() }
    return harness
}

/** The list error in the app theme, every intent recorded. */
internal fun ComposeContentTestRule.setListError(error: ChatFailure): MutableList<FormIntent> {
    val intents = mutableListOf<FormIntent>()
    setContent {
        PhopencodeTheme(darkTheme = false) {
            Surface {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    FormListError(error, { intents += it }, Modifier.fillMaxWidth())
                }
            }
        }
    }
    return intents
}
