package io.github.thewisekd.phopencode.feature.files.ui.search

import android.os.Bundle
import androidx.compose.ui.test.junit4.v2.createComposeRule
import io.github.thewisekd.phopencode.core.model.FileEntry
import io.github.thewisekd.phopencode.core.model.FileEntryType
import io.github.thewisekd.phopencode.feature.files.logic.contract.ContentHit
import io.github.thewisekd.phopencode.feature.files.logic.contract.SearchResult
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

private fun name(path: String) = SearchResult.Name(FileEntry(path = path, type = FileEntryType.File))

private fun content(path: String, line: Int) = SearchResult.Content(ContentHit(path = path, line = line, text = "x"))

/**
 * The lazy list's keys never collide, whatever characters a path holds: equal keys crash it. They
 * also go into a Bundle, as a lazy list saves its keys there.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class SearchKeysTest {
    @get:Rule
    val compose = createComposeRule()

    private fun assertDistinct(results: List<SearchResult>) {
        val keys = stableKeys(results).map { it.first }
        assertEquals(results.size, keys.toSet().size, "keys collide")
    }

    @Test
    fun `a path that looks like a counted duplicate does not collide`() {
        assertDistinct(listOf(name("/a"), name("/a"), name("/a#2")))
        assertDistinct(listOf(content("/a", 1), content("/a", 1), content("/a#2", 1)))
    }

    @Test
    fun `separators in paths do not collide`() {
        assertDistinct(
            listOf(
                content("2:x", 1),
                content("x", 12),
                content("1:2:x", 1),
                content("x:1", 2),
                content("x", 1),
                name("content:1:x"),
                name("1:x"),
                name("x#2"),
                name("x"),
                name("x"),
                content("name:x", 1),
            ),
        )
    }

    @Test
    fun `a name and a content hit of the same path do not collide`() {
        assertDistinct(listOf(name("/a"), content("/a", 0), content("/a", 1)))
    }

    @Test
    fun `a key stays with its hit when others come before it`() {
        val hit = content("/a", 3)
        val alone = stableKeys(listOf(hit)).single().first
        val after = stableKeys(listOf(name("/b"), content("/c", 1), hit)).last().first

        assertEquals(alone, after)
    }

    @Test
    fun `keys fit in a Bundle`() {
        val bundle = Bundle()
        stableKeys(listOf(name("/a"), content("/a", 1), content("/a", 1))).forEachIndexed { index, (key, _) ->
            bundle.putSerializable("k$index", key as java.io.Serializable)
        }

        assertEquals(3, bundle.size())
    }

    @Test
    fun `the colliding list from the review draws every hit`() {
        val results = listOf(name("$ROOT/a"), name("$ROOT/a"), name("$ROOT/a#2"))
        compose.setSearch(NAMES.copy(results = results))

        compose.assertEachHitDrawn(results, SearchTags.NAME_ROW)
    }

    @Test
    fun `colliding content hits draw every hit`() {
        val results = listOf(content("$ROOT/a", 1), content("$ROOT/a", 1), content("$ROOT/a#2", 1))
        compose.setSearch(CONTENT.copy(results = results))

        compose.assertEachHitDrawn(results, SearchTags.CONTENT_ROW)
    }
}
