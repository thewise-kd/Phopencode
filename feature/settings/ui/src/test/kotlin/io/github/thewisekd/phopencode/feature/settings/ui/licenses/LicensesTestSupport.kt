package io.github.thewisekd.phopencode.feature.settings.ui.licenses

import androidx.compose.material3.Surface
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import io.github.thewisekd.phopencode.core.navigation.SettingsRoute
import io.github.thewisekd.phopencode.feature.settings.ui.setThemedAt

/** Shows the licenses at [fontScale] and returns how many times they asked to close. */
internal fun ComposeContentTestRule.showLicenses(fontScale: Float = 1f): () -> Int {
    var closed = 0
    setThemedAt(fontScale) { Surface { LicensesScreen(SettingsRoute.Licenses, onClose = { closed++ }) } }
    return { closed }
}

/** Scrolls the list to the node tagged [tag] and returns it. */
internal fun ComposeContentTestRule.scrolledTo(tag: String): SemanticsNodeInteraction {
    onNodeWithTag(LICENSES_LIST_TAG).performScrollToNode(hasTestTag(tag))
    return onNodeWithTag(tag)
}

/** Scrolls the list to the node showing exactly [text] and returns it. */
internal fun ComposeContentTestRule.scrolledToText(text: String): SemanticsNodeInteraction {
    onNodeWithTag(LICENSES_LIST_TAG).performScrollToNode(hasText(text))
    return onNodeWithText(text)
}
