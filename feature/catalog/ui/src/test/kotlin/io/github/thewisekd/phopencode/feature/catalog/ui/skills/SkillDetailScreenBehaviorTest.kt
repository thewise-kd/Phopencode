package io.github.thewisekd.phopencode.feature.catalog.ui.skills

import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.CatalogFailure
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.SkillDetailIntent
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.SkillDetailState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

private val indeterminate = SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate)

/** What a skill's details draw for each state, and which intents their controls send. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class SkillDetailScreenBehaviorTest {
    @get:Rule
    val compose = createComposeRule()

    private val strings = ENGLISH_SKILLS_STRINGS

    @Test
    fun `a loaded skill shows its name, description and source path`() {
        compose.setSkillDetail(loadedSkill)

        compose.onNodeWithText(review.name).assertIsDisplayed()
        compose.onNodeWithText(review.description!!).assertIsDisplayed()
        compose.onNodeWithTag(SKILL_SOURCE_TAG).assertIsDisplayed()
        compose.onNodeWithText(SHORT_SOURCE, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(strings.source, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `the path shown is the state's, not the skill's full path`() {
        compose.setSkillDetail(loadedSkill)

        compose.onAllNodesWithText(review.path, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun `the skill's text renders as Markdown`() {
        compose.setSkillDetail(loadedSkill)

        compose.awaitText("Review steps")
        compose.onNodeWithTag(SKILL_CONTENT_TAG).assertExists()
        // Rendered, not raw: the heading's mark and the emphasis stars are gone.
        compose.onAllNodesWithText("# Review steps", substring = true, useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodesWithText("*whole*", substring = true, useUnmergedTree = true).assertCountEquals(0)
        compose.awaitText("Check the tests")
    }

    @Test
    fun `a skill without text says so`() {
        compose.setSkillDetail(loadedSkill.copy(skill = review.copy(content = "  \n")))

        compose.onNode(hasText(strings.noContent)).performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithTag(SKILL_CONTENT_TAG).assertCountEquals(0)
    }

    @Test
    fun `using the skill starts a new session`() {
        val intents = mutableListOf<SkillDetailIntent>()
        compose.setSkillDetail(loadedSkill, intents::add)

        compose.onNodeWithText(strings.use).performScrollTo().performClick()

        assertEquals(listOf<SkillDetailIntent>(SkillDetailIntent.UseInNewSession), intents)
    }

    @Test
    fun `the use button is large enough to tap`() {
        compose.setSkillDetail(loadedSkill)

        compose.onNodeWithText(strings.use).performScrollTo().assertTouchSizeIsAtLeast()
        compose.onNodeWithContentDescription(strings.back).assertTouchSizeIsAtLeast()
    }

    @Test
    fun `the back button closes the skill`() {
        val intents = mutableListOf<SkillDetailIntent>()
        compose.setSkillDetail(loadedSkill, intents::add)

        compose.onNodeWithContentDescription(strings.back).performClick()

        assertEquals(listOf<SkillDetailIntent>(SkillDetailIntent.Close), intents)
    }

    @Test
    fun `a loading skill shows a still progress under the route's ID, and no use button`() {
        compose.setSkillDetail(SkillDetailState(skillId = review.id))

        compose.onNode(indeterminate).assertIsDisplayed()
        compose.onNodeWithContentDescription(strings.detailLoading).assertIsDisplayed()
        compose.onNodeWithText(review.id).assertIsDisplayed()
        compose.onAllNodesWithText(strings.use).assertCountEquals(0)
        compose.onAllNodesWithTag(SKILL_SOURCE_TAG).assertCountEquals(0)
    }

    @Test
    fun `a skill that is gone says so and can be read again`() {
        val intents = mutableListOf<SkillDetailIntent>()
        compose.setSkillDetail(SkillDetailState(skillId = review.id, loading = false, error = CatalogFailure.NotFound), intents::add)

        compose.onNodeWithText(strings.detailNotFound).assertIsDisplayed()
        compose.onAllNodesWithText(strings.use).assertCountEquals(0)
        compose.onNodeWithText(strings.retry).performClick()

        assertEquals(listOf<SkillDetailIntent>(SkillDetailIntent.Retry), intents)
    }

    @Test
    fun `refused credentials offer no retry`() {
        compose.setSkillDetail(SkillDetailState(skillId = review.id, loading = false, error = CatalogFailure.Unauthorized))

        compose.onNodeWithText(strings.errors.getValue(CatalogFailure.Unauthorized)).assertIsDisplayed()
        compose.onAllNodesWithText(strings.retry).assertCountEquals(0)
    }

    @Test
    fun `the body scrolls`() {
        compose.setSkillDetail(loadedSkill)

        compose.onNodeWithTag(SKILL_DETAIL_BODY_TAG).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
    }
}
