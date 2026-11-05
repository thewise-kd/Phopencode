package io.github.thewisekd.phopencode.feature.chat.ui.form

import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatFailure
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.FieldProblem
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Level 36 has no arm64 Linux runtime, so the Pi runs 26 only; CI runs both. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "tr")
class TurkishFormTest : FormLanguageContract() {
    override val strings = ExpectedFormStrings(
        questionHeading = "Model soruyor",
        formHeading = "Yanıt bekleniyor",
        fromSubagent = "Bir alt ajandan",
        step = { step, count -> "Soru $step / $count" },
        required = "Zorunlu",
        ownAnswer = "Kendi yanıtınız",
        ownAnswerField = "Yanıtınızı yazın",
        answerField = "Yanıtınız",
        yes = "Evet",
        no = "Hayır",
        choicesOneToTwo = "1 ile 2 arasında seçim yapın.",
        numberWholeOneToTen = "1 ile 10 arasında · Tam sayı",
        externalOpen = "Sayfayı aç",
        externalHost = { "$it tarayıcıda açılır." },
        externalOpened = "Açıldı. Adımı sayfada tamamlayın, sonra gönderin.",
        externalBlocked = "Bu bağlantı açılamaz: yalnızca web adresleri açılabilir.",
        unsupported = "Bu uygulama bu alanı gösteremiyor.",
        problems = mapOf(
            FieldProblem.Required to "Bu alan zorunlu.",
            FieldProblem.TooFewChoices to "En az 1 seçenek seçin.",
            FieldProblem.TooManyChoices to "En fazla 2 seçenek seçin.",
            FieldProblem.NotANumber to "Bir sayı girin.",
            FieldProblem.NotAnInteger to "Tam sayı girin.",
            FieldProblem.BelowMinimum to "1 veya daha büyük bir sayı girin.",
            FieldProblem.AboveMaximum to "10 veya daha küçük bir sayı girin.",
            FieldProblem.TooShort to "En az 3 karakter girin.",
            FieldProblem.TooLong to "En fazla 5 karakter girin.",
            FieldProblem.WrongFormat to "Yanıt beklenen biçimde değil.",
            FieldProblem.NotOpened to "Önce sayfayı açın.",
            FieldProblem.Unsupported to "Bu alan burada yanıtlanamıyor, bu yüzden form yalnızca reddedilebilir.",
        ),
        back = "Geri",
        next = "İleri",
        submit = "Gönder",
        decline = "Reddet",
        sending = "Yanıt gönderiliyor…",
        declining = "Reddediliyor…",
        declineQuestionHint = "Reddetmek mevcut çalışmayı durdurur.",
        submitFailed = { "Yanıt gönderilemedi. $it" },
        declineFailed = { "Form reddedilemedi. $it" },
        reasons = mapOf(
            ChatFailure.NotConnected to "Sunucuyla bağlantı yok.",
            ChatFailure.Unreachable to "Sunucuya ulaşılamadı. Bağlantıyı denetleyip yeniden deneyin.",
            ChatFailure.Unauthorized to "Sunucu kimlik bilgilerini reddetti.",
            ChatFailure.NotFound to "Form artık yok.",
            ChatFailure.Conflict to "Form zaten yanıtlanmış ya da kapatılmış.",
            ChatFailure.Rejected to "Sunucu yanıtı reddetti.",
            ChatFailure.ServerFailed to "Sunucuda bir hata oluştu.",
            ChatFailure.Unknown to "Beklenmeyen bir hata oluştu.",
        ),
        listError = { "Alt ajanların soruları yüklenemedi. $it" },
        retry = "Tekrar dene",
    )
}
