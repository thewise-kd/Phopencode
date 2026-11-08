package io.github.thewisekd.phopencode.feature.chat.ui.palette

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.BuiltInCommand
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PaletteIntent
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PaletteItem
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PaletteState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PaletteTrigger
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PromptAttachment

/** The tags the harness puts on the modifiers, as the chat puts its slot tags there. */
internal const val PALETTE_TAG = "test:palette"
internal const val CHIPS_TAG = "test:attachments"

/** Made-up names, with Turkish letters so a lost character would show. */
internal const val REVIEW_DESCRIPTION = "Değişiklikleri gözden geçir"
internal val INIT = PaletteItem.Command("init", "Create AGENTS.md")
internal val REVIEW = PaletteItem.Command("review", REVIEW_DESCRIPTION)
internal val PDF = PaletteItem.Skill("pdf-özet")

/** One of the server's own skills: its ID, what is sent and typed, is not its name. */
internal val OPENCODE = PaletteItem.Skill(id = "opencode", name = "OpenCode", description = "Çalışma alanını yönet")
internal val NEW = PaletteItem.BuiltIn(BuiltInCommand.New)
internal val UNDO = PaletteItem.BuiltIn(BuiltInCommand.Undo)
internal val EXPLORE = PaletteItem.Agent(id = "explore", name = "Explore", description = "Searches the code")
internal val SOURCE = PaletteItem.File("src/çekirdek/Main.kt")
internal val FOLDER = PaletteItem.File("src/çekirdek", directory = true)

/**
 * An open `/` palette with commands, built-ins (one with an alias, one without) then skills (one
 * named as its ID, one not).
 */
internal val SLASH = PaletteState(trigger = PaletteTrigger.Slash, query = "", items = listOf(INIT, REVIEW, NEW, UNDO, PDF, OPENCODE))

/** An open `/` palette with every built-in. */
internal val BUILT_INS = PaletteState(
    trigger = PaletteTrigger.Slash,
    query = "",
    items = BuiltInCommand.entries.map { PaletteItem.BuiltIn(it) },
)

/** An open `@` palette with an agent then files. */
internal val AT = PaletteState(trigger = PaletteTrigger.At, query = "src", items = listOf(EXPLORE, SOURCE, FOLDER))

internal val FILE_ATTACHMENT = PromptAttachment.File("src/çekirdek/Main.kt")
internal val AGENT_ATTACHMENT = PromptAttachment.Agent("explore")
internal val SKILL_ATTACHMENT = PromptAttachment.Skill("pdf-özet")
internal val ATTACHMENTS = listOf(FILE_ATTACHMENT, AGENT_ATTACHMENT, SKILL_ATTACHMENT)

/** The palette in the app theme, in a scrolling column as tests need; every intent is recorded. */
internal class PaletteHarness(initial: PaletteState) {
    val state: MutableState<PaletteState> = mutableStateOf(initial)
    val intents = mutableListOf<PaletteIntent>()

    @Composable
    fun Content() {
        PhopencodeTheme(darkTheme = false) {
            Surface {
                // Scrollable so tests can scroll a node into view first: at API 35+ the insets push content down.
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    CommandPalette(state.value, { intents += it }, Modifier.fillMaxWidth().testTag(PALETTE_TAG))
                }
            }
        }
    }
}

/** The chips in the app theme, placed full width as the chat places them; every detach is recorded. */
internal class ChipsHarness(initial: List<PromptAttachment>) {
    val attachments: MutableState<List<PromptAttachment>> = mutableStateOf(initial)
    val detached = mutableListOf<PromptAttachment>()

    @Composable
    fun Content() {
        PhopencodeTheme(darkTheme = false) {
            Surface {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    AttachmentChips(attachments.value, { detached += it }, Modifier.fillMaxWidth().testTag(CHIPS_TAG))
                }
            }
        }
    }
}

internal fun ComposeContentTestRule.setPalette(state: PaletteState): PaletteHarness =
    PaletteHarness(state).also { harness -> setContent { harness.Content() } }

internal fun ComposeContentTestRule.setChips(attachments: List<PromptAttachment>): ChipsHarness =
    ChipsHarness(attachments).also { harness -> setContent { harness.Content() } }

private val TOUCH_SIZE_TOLERANCE = 0.5.dp

/** The node's touch bounds are at least [minimum] in both directions. */
internal fun SemanticsNodeInteraction.assertTouchSizeIsAtLeast(minimum: Dp): SemanticsNodeInteraction {
    val node = fetchSemanticsNode("Failed to check the touch size.")
    val bounds: Rect = node.touchBoundsInRoot
    val (width, height) = with(node.layoutInfo.density) { bounds.width.toDp() to bounds.height.toDp() }
    if (width < minimum - TOUCH_SIZE_TOLERANCE || height < minimum - TOUCH_SIZE_TOLERANCE) {
        throw AssertionError("Touch size is $width x $height, expected at least $minimum")
    }
    return this
}
