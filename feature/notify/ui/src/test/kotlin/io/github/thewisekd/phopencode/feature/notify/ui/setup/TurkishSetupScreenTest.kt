package io.github.thewisekd.phopencode.feature.notify.ui.setup

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.thewisekd.phopencode.feature.notify.logic.contract.NotifyFailure
import io.github.thewisekd.phopencode.feature.notify.logic.contract.NotifyServiceStatus
import io.github.thewisekd.phopencode.feature.notify.logic.contract.NtfyServerProblem
import io.github.thewisekd.phopencode.feature.notify.logic.contract.SetupError
import io.github.thewisekd.phopencode.feature.notify.logic.contract.SetupState
import io.github.thewisekd.phopencode.feature.notify.logic.contract.SetupStep
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The setup speaks Turkish on a Turkish phone: a sample of its strings, the parity check covers the rest. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "tr")
class TurkishSetupScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private fun shown(text: String) = compose.onNodeWithText(text).performScrollTo().assertIsDisplayed()

    @Test
    fun `every section is in Turkish`() {
        compose.showSetup(loadedState.copy(status = NotifyServiceStatus.Stopped))

        compose.onNodeWithText("Bildirimler").assertIsDisplayed()
        compose.onNodeWithContentDescription("Geri dön").assertIsDisplayed()
        shown("ntfy uygulaması")
        shown("ntfy'da abone ol")
        shown("Sunucudaki servis")
        compose.onNodeWithTag(SETUP_STATUS_TAG).performScrollTo().assertTextContains("Durdu")
        shown("Kur ve başlat")
        shown("Kaldır")
        shown("Deneme bildirimi")
        shown("Deneme gönder")
        shown("ntfy sunucusu")
        shown("Kaydet")
        shown("ntfy.sh kullan")
        compose.onNodeWithContentDescription("ntfy.sh üzerindeki abonelik bağlantısı, konu gizli")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `loading and steps are in Turkish`() {
        compose.showSetup(SetupState())

        compose.onNodeWithContentDescription("Bildirim ayarları yükleniyor…").assertIsDisplayed()
    }

    @Test
    fun `an install in progress is in Turkish`() {
        compose.showSetup(loadedState.copy(operation = SetupStep.Install))

        compose.onNodeWithContentDescription("Servis kuruluyor…").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a failure is in Turkish`() {
        compose.showSetup(loadedState.copy(error = SetupError(SetupStep.Install, NotifyFailure.PythonMissing)))

        shown("Servis kurulamadı. Sunucuda bildirim servisinin gerektirdiği python3 yok. Hiçbir şey kurulmadı.")
    }

    @Test
    fun `a server problem is in Turkish`() {
        compose.showSetup(loadedState.copy(serverText = "http://x", serverProblem = NtfyServerProblem.NotHttps))

        shown("Adres https:// ile başlamalı.")
    }

    @Test
    fun `a missing ntfy app is explained in Turkish`() {
        compose.showSetup(loadedState, FakeUriHandler(missing = true))

        compose.onNodeWithText("ntfy'da abone ol").performScrollTo().performClick()

        shown(
            "ntfy uygulaması kurulu olmadığı için bağlantı açılamadı. ntfy'ı kurun, ardından " +
                "\"ntfy'da abone ol\" düğmesine yeniden dokunun.",
        )
        shown("ntfy'ı indir")
    }
}
