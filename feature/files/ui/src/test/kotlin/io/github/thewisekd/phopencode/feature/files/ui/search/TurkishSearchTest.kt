package io.github.thewisekd.phopencode.feature.files.ui.search

import io.github.thewisekd.phopencode.feature.files.logic.contract.ContentSearchTool
import io.github.thewisekd.phopencode.feature.files.logic.contract.FilesFailure
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val FAILED = "Arama yapılamadı."

/** Level 36 has no arm64 Linux runtime, so the Pi runs 26 only; CI runs both. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "tr")
class TurkishSearchTest : SearchLanguageContract() {
    override val strings = ExpectedSearchStrings(
        title = "Dosyalarda ara",
        queryLabel = "Aranacak",
        run = "Ara",
        names = "Adlar",
        content = "İçerik",
        hintNames = "Bir dosya ya da klasör adının bir kısmını yazın, sonra arayın.",
        hintContent = "Dosyalarda bulunacak metni yazın, sonra arayın.",
        searching = "Aranıyor…",
        emptyTitle = "Sonuç yok",
        emptyMessage = "Bu projede eşleşen bir şey yok. Başka sözcükler ya da diğer modu deneyin.",
        truncated = "Gösterilenden daha fazla sonuç var. Aramayı daraltın.",
        tools = mapOf(
            ContentSearchTool.Ripgrep to "ripgrep ile arandı.",
            ContentSearchTool.Grep to "Sunucuda ripgrep kurulu olmadığı için grep ile arandı.",
        ),
        line42 = "Satır 42",
        folder = "Klasör",
        file = "Dosya",
        symlink = "Bağlantı",
        other = "Diğer",
        errors = mapOf(
            FilesFailure.NotConnected to "$FAILED Sunucuyla bağlantı yok.",
            FilesFailure.Unreachable to "$FAILED Sunucuya ulaşılamadı.",
            FilesFailure.Unauthorized to "$FAILED Sunucu girişi reddetti.",
            FilesFailure.NotFound to "$FAILED Proje klasörü bulunamadı.",
            FilesFailure.PermissionDenied to "$FAILED Sunucudaki kullanıcının klasörü okuma izni yok.",
            FilesFailure.AlreadyExists to "$FAILED Bu adı taşıyan bir öğe zaten var.",
            FilesFailure.NotEmpty to "$FAILED Klasör boş değil.",
            FilesFailure.PathRejected to "$FAILED Yol projenin dışında ya da geçersiz.",
            FilesFailure.TooLarge to "$FAILED Sonuç çok büyük.",
            FilesFailure.ToolMissing to "$FAILED Sunucuda ne ripgrep ne de grep kurulu.",
            FilesFailure.Rejected to "$FAILED Arama metni reddedildi; tek satır olmalı.",
            FilesFailure.ServerFailed to "$FAILED Sunucu bir hatayla karşılaştı.",
            FilesFailure.Unsupported to "$FAILED Bu henüz desteklenmiyor.",
            FilesFailure.Unknown to "$FAILED Beklenmeyen bir hata oluştu.",
        ),
        retry = "Tekrar dene",
    )
}
