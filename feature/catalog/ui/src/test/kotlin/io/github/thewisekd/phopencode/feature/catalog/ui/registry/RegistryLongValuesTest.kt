package io.github.thewisekd.phopencode.feature.catalog.ui.registry

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import io.github.thewisekd.phopencode.core.model.PermissionEffect
import io.github.thewisekd.phopencode.core.model.PermissionRule
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.AgentDetailState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** One unbroken word far wider than any phone, as a server may send for a name or an ID. */
private val LONG = "x".repeat(400)

/**
 * A label paired with a server's value keeps room to be read however long the value is: a long
 * value wraps or ellipsizes in its own share of the row.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class RegistryLongValuesTest {
    @get:Rule
    val compose = createComposeRule()

    private val strings = ENGLISH_REGISTRY_STRINGS

    /**
     * The label is laid out whole: wider than nothing and on one line. A label squeezed by its value
     * collapses or breaks into many lines. Robolectric's fonts are not real, so neither a width in dp
     * nor the layout's overflow flags say anything here.
     */
    private fun SemanticsNodeInteraction.assertLaidOutWhole(): SemanticsNodeInteraction {
        val node = fetchSemanticsNode("Failed to read the text layout.")
        val layouts = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        val layout = layouts.single()
        if (node.size.width <= 0 || layout.lineCount != 1) {
            throw AssertionError(
                "Label squeezed: width ${node.size.width}px, ${layout.lineCount} lines",
            )
        }
        return this
    }

    @Test
    fun `a long provider name and model ID leave their labels readable`() {
        compose.setModel(modelShown.copy(modelId = LONG, model = large.copy(id = LONG), providerName = LONG))

        compose.onNodeWithText(strings.provider, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
            .assertLaidOutWhole()
        compose.onNodeWithText(strings.modelId, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
            .assertLaidOutWhole()
    }

    @Test
    fun `a long resource or unknown effect leaves the permission's action readable`() {
        val rule = PermissionRule(action = "edit", resource = LONG, effect = PermissionEffect.Unknown(LONG))
        compose.setAgent(AgentDetailState(agentId = "build", loading = false, agent = build.copy(permissions = listOf(rule))))

        compose.onNodeWithText("edit", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
            .assertLaidOutWhole()
    }

    @Test
    fun `a long model reference keeps the agent's model row tappable`() {
        val agent = build.copy(model = build.model!!.copy(providerId = LONG, modelId = LONG, variant = LONG))
        compose.setAgent(AgentDetailState(agentId = "build", loading = false, agent = agent))

        compose.onNode(hasText(LONG, substring = true) and hasClickAction()).performScrollTo().assertTouchSizeIsAtLeast()
    }
}
