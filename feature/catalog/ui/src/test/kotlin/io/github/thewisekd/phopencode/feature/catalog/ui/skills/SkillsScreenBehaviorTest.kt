package io.github.thewisekd.phopencode.feature.catalog.ui.skills

import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.CatalogFailure
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.SkillsIntent
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.SkillsState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val indeterminate = SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate)

/** What the skill list draws for each state, and which intents its controls send. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class SkillsScreenBehaviorTest {
    @get:Rule
    val compose = createComposeRule()

    private val strings = ENGLISH_SKILLS_STRINGS

    @Test
    fun `each skill shows its name and description in a card`() {
        compose.setSkills(listedSkills)

        compose.onNodeWithTag(skillCardTag(review.id)).assertIsDisplayed()
        compose.onNodeWithTag(skillCardTag(release.id)).assertIsDisplayed()
        compose.onNodeWithText(review.name, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(review.description!!, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(release.name, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `only the shown skills are drawn`() {
        compose.setSkills(listedSkills.copy(query = "rev", shown = listOf(review)))

        compose.onNodeWithTag(skillCardTag(review.id)).assertIsDisplayed()
        compose.onAllNodesWithTag(skillCardTag(release.id)).assertCountEquals(0)
    }

    @Test
    fun `tapping a skill opens it`() {
        val intents = mutableListOf<SkillsIntent>()
        compose.setSkills(listedSkills, intents::add)

        compose.onNodeWithTag(skillCardTag(release.id)).performClick()

        assertEquals(listOf<SkillsIntent>(SkillsIntent.OpenSkill(release.id)), intents)
    }

    @Test
    fun `typing in the search sends the query`() {
        val intents = mutableListOf<SkillsIntent>()
        compose.setSkills(listedSkills, intents::add)

        compose.onNode(hasSetTextAction()).performTextInput("rev")

        assertEquals(listOf<SkillsIntent>(SkillsIntent.Search("rev")), intents)
    }

    @Test
    fun `the search field shows the state's query`() {
        compose.setSkills(listedSkills.copy(query = "notes", shown = listOf(release)))

        compose.onNode(hasSetTextAction()).assert(SemanticsMatcher("has the query") { node ->
            node.config.getOrElseNullable(SemanticsProperties.EditableText) { null }?.text == "notes"
        })
    }

    @Test
    fun `the reload button reloads`() {
        val intents = mutableListOf<SkillsIntent>()
        compose.setSkills(listedSkills, intents::add)

        compose.onNodeWithContentDescription(strings.reload).assertIsEnabled().performClick()

        assertEquals(listOf<SkillsIntent>(SkillsIntent.Reload), intents)
    }

    @Test
    fun `the reload button is off while refreshing`() {
        compose.setSkills(listedSkills.copy(refreshing = true))

        compose.onNodeWithContentDescription(strings.reload).assertIsNotEnabled()
    }

    @Test
    fun `the reload button is off while loading`() {
        compose.setSkills(SkillsState())

        compose.onNodeWithContentDescription(strings.reload).assertIsNotEnabled()
    }

    @Test
    fun `pulling the list down reloads`() {
        val intents = mutableListOf<SkillsIntent>()
        compose.setSkills(listedSkills, intents::add)

        compose.onNodeWithTag(SKILLS_LIST_TAG).performTouchInput { swipeDown() }
        compose.waitForIdle()

        assertTrue(SkillsIntent.Reload in intents, "intents: $intents")
    }

    @Test
    fun `the back button closes the list`() {
        val intents = mutableListOf<SkillsIntent>()
        compose.setSkills(listedSkills, intents::add)

        compose.onNodeWithContentDescription(strings.back).performClick()

        assertEquals(listOf<SkillsIntent>(SkillsIntent.Close), intents)
    }

    @Test
    fun `a first read shows a still progress and no search`() {
        compose.setSkills(SkillsState())

        compose.onNode(indeterminate).assertIsDisplayed()
        compose.onNodeWithContentDescription(strings.loading).assertIsDisplayed()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
    }

    @Test
    fun `a refresh keeps the skills on screen and says it runs`() {
        compose.setSkills(listedSkills.copy(refreshing = true))

        compose.onNodeWithContentDescription(strings.refreshing).assertIsDisplayed()
        compose.onNodeWithTag(skillCardTag(review.id)).assertIsDisplayed()
    }

    @Test
    fun `a server without skills says so, without a search`() {
        compose.setSkills(SkillsState(loading = false))

        compose.onNodeWithText(strings.emptyTitle).assertIsDisplayed()
        compose.onNodeWithText(strings.emptyMessage).assertIsDisplayed()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
    }

    @Test
    fun `a search that matches nothing says so and keeps the search`() {
        compose.setSkills(listedSkills.copy(query = "zzz", shown = emptyList()))

        compose.onNodeWithText(strings.noMatchTitle).assertIsDisplayed()
        compose.onNode(hasSetTextAction()).assertIsDisplayed()
        compose.onAllNodesWithText(strings.emptyTitle).assertCountEquals(0)
    }

    @Test
    fun `a failure that a new read can fix offers to retry`() {
        val intents = mutableListOf<SkillsIntent>()
        compose.setSkills(SkillsState(loading = false, error = CatalogFailure.Unreachable), intents::add)

        compose.onNodeWithText(strings.retry).performClick()

        assertEquals(listOf<SkillsIntent>(SkillsIntent.Reload), intents)
    }

    @Test
    fun `a lost connection offers no retry`() {
        compose.setSkills(SkillsState(loading = false, error = CatalogFailure.NotConnected))

        compose.onNodeWithText(strings.errors.getValue(CatalogFailure.NotConnected)).assertIsDisplayed()
        compose.onAllNodesWithText(strings.retry).assertCountEquals(0)
    }

    @Test
    fun `an error keeps the skills read before`() {
        compose.setSkills(listedSkills.copy(error = CatalogFailure.Unreachable))

        compose.onNodeWithText(strings.errors.getValue(CatalogFailure.Unreachable)).assertIsDisplayed()
        compose.onNodeWithTag(skillCardTag(review.id)).assertIsDisplayed()
    }

    @Test
    fun `the controls are large enough to tap`() {
        compose.setSkills(listedSkills)

        compose.onNodeWithTag(skillCardTag(release.id)).assertTouchSizeIsAtLeast()
        compose.onNodeWithContentDescription(strings.reload).assertTouchSizeIsAtLeast()
        compose.onNodeWithContentDescription(strings.back).assertTouchSizeIsAtLeast()
    }

    @Test
    fun `a card tells TalkBack what a tap does`() {
        compose.setSkills(listedSkills)

        compose.onNodeWithTag(skillCardTag(review.id)).assert(SemanticsMatcher("has the open label") { node ->
            node.config.getOrElseNullable(SemanticsActions.OnClick) { null }?.label == strings.open
        })
    }
}
