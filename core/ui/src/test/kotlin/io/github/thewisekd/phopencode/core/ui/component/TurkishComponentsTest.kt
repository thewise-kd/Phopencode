package io.github.thewisekd.phopencode.core.ui.component

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The levels of `PhopencodeThemeTest`: level 36 has no arm64 Linux runtime, so the Pi passes 26 only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "tr")
class TurkishComponentsTest : ComponentLanguageContract() {
    override val strings = ExpectedStrings(
        navigateBack = "Geri dön",
        emptyTitle = "Burada henüz bir şey yok",
        errorGeneric = "Bir sorun oluştu",
        errorDismiss = "Hatayı kapat",
        retry = "Tekrar dene",
        loading = "Yükleniyor…",
        confirm = "Onayla",
        cancel = "İptal",
        dangerousAction = "Tehlikeli işlem",
        statusSuccess = "Başarılı",
        statusWarning = "Uyarı",
        statusDanger = "Hata",
        statusInfo = "Bilgi",
    )
}
