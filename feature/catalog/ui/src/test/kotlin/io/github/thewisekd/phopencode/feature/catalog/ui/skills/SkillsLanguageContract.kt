package io.github.thewisekd.phopencode.feature.catalog.ui.skills

import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import io.github.thewisekd.phopencode.core.navigation.CatalogRoute
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.CatalogFailure
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.SkillDetailState
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.SkillsState
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/** The texts of the skill screens in one language. */
internal data class ExpectedSkillsStrings(
    val title: String,
    val back: String,
    val search: String,
    val reload: String,
    val loading: String,
    val refreshing: String,
    val emptyTitle: String,
    val emptyMessage: String,
    val noMatchTitle: String,
    val noMatchMessage: String,
    val open: String,
    val auto: String,
    val retry: String,
    /** The list's text for each failure; the details share them but for [CatalogFailure.NotFound]. */
    val errors: Map<CatalogFailure, String>,
    val detailLoading: String,
    val detailNotFound: String,
    val source: String,
    val use: String,
    val noContent: String,
)

/** What the skill screens show, the same in each language; a subclass sets the language and its texts. */
abstract class SkillsLanguageContract {
    @get:Rule
    val compose = createComposeRule()

    internal abstract val strings: ExpectedSkillsStrings

    @Test
    fun `the list shows its title and controls`() {
        compose.setSkills(listedSkills)

        compose.onNodeWithText(strings.title).assertIsDisplayed()
        compose.onNodeWithText(strings.search).assertExists()
        compose.onNodeWithContentDescription(strings.reload).assertIsDisplayed()
        compose.onNodeWithContentDescription(strings.back).assertIsDisplayed()
        compose.onNodeWithText(strings.auto, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `the list says it loads`() {
        compose.setSkills(SkillsState())

        compose.onNodeWithContentDescription(strings.loading).assertIsDisplayed()
    }

    @Test
    fun `the list says it refreshes`() {
        compose.setSkills(listedSkills.copy(refreshing = true))

        compose.onNodeWithContentDescription(strings.refreshing).assertIsDisplayed()
    }

    @Test
    fun `an empty list explains itself`() {
        compose.setSkills(SkillsState(loading = false))

        compose.onNodeWithText(strings.emptyTitle).assertIsDisplayed()
        compose.onNodeWithText(strings.emptyMessage).assertIsDisplayed()
    }

    @Test
    fun `a search without matches explains itself`() {
        compose.setSkills(listedSkills.copy(query = "zzz", shown = emptyList()))

        compose.onNodeWithText(strings.noMatchTitle).assertIsDisplayed()
        compose.onNodeWithText(strings.noMatchMessage).assertIsDisplayed()
    }

    @Test
    fun `every failure has its own text`() {
        assertEquals(CatalogFailure.entries.toSet(), strings.errors.keys)
        assertEquals(strings.errors.size, strings.errors.values.toSet().size, "texts must differ")
        var failure by mutableStateOf(CatalogFailure.entries.first())
        compose.setContent {
            PhopencodeTheme(darkTheme = false) {
                Surface { SkillsScreen(CatalogRoute.Skills, SkillsState(loading = false, error = failure), onIntent = {}) }
            }
        }
        CatalogFailure.entries.forEach {
            failure = it
            compose.onNodeWithText(strings.errors.getValue(it)).assertIsDisplayed()
        }
    }

    @Test
    fun `a skill's failures read as the list's, but for a missing skill`() {
        var failure by mutableStateOf(CatalogFailure.entries.first())
        compose.setContent {
            PhopencodeTheme(darkTheme = false) {
                Surface {
                    SkillDetailScreen(
                        CatalogRoute.SkillDetail(review.id),
                        SkillDetailState(skillId = review.id, loading = false, error = failure),
                        onIntent = {},
                    )
                }
            }
        }
        CatalogFailure.entries.forEach {
            failure = it
            val expected = if (it == CatalogFailure.NotFound) strings.detailNotFound else strings.errors.getValue(it)
            compose.onNodeWithText(expected).assertIsDisplayed()
        }
    }

    @Test
    fun `a retryable failure offers a retry`() {
        compose.setSkills(SkillsState(loading = false, error = RETRYABLE.first()))

        compose.onNodeWithText(strings.retry).assertIsDisplayed()
    }

    @Test
    fun `a skill shows its source and the use button`() {
        compose.setSkillDetail(loadedSkill)

        compose.onNodeWithText(strings.source, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(strings.use).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a skill says it loads`() {
        compose.setSkillDetail(SkillDetailState(skillId = review.id))

        compose.onNodeWithContentDescription(strings.detailLoading).assertIsDisplayed()
    }

    @Test
    fun `a missing skill says it is gone`() {
        compose.setSkillDetail(SkillDetailState(skillId = review.id, loading = false, error = CatalogFailure.NotFound))

        compose.onNodeWithText(strings.detailNotFound).assertIsDisplayed()
    }

    @Test
    fun `a skill without text says so`() {
        compose.setSkillDetail(loadedSkill.copy(skill = review.copy(content = "")))

        compose.onNodeWithText(strings.noContent).performScrollTo().assertIsDisplayed()
    }
}
