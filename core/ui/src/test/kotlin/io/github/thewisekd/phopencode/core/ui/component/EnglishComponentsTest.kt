package io.github.thewisekd.phopencode.core.ui.component

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The levels of `PhopencodeThemeTest`: level 36 has no arm64 Linux runtime, so the Pi passes 26 only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class EnglishComponentsTest : ComponentLanguageContract() {
    override val strings = ExpectedStrings(
        navigateBack = "Go back",
        emptyTitle = "Nothing here yet",
        errorGeneric = "Something went wrong",
        errorDismiss = "Dismiss error",
        retry = "Try again",
        loading = "Loading…",
        confirm = "Confirm",
        cancel = "Cancel",
        dangerousAction = "Dangerous action",
        statusSuccess = "Success",
        statusWarning = "Warning",
        statusDanger = "Error",
        statusInfo = "Info",
    )
}
