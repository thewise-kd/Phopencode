package io.github.thewisekd.phopencode.feature.catalog.ui.skills

import io.github.thewisekd.phopencode.feature.catalog.logic.contract.CatalogFailure
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Level 36 has no arm64 Linux runtime, so the Pi runs 26 only; CI runs both. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class EnglishSkillsScreenTest : SkillsLanguageContract() {
    override val strings = ENGLISH_SKILLS_STRINGS
}

internal val ENGLISH_SKILLS_STRINGS = ExpectedSkillsStrings(
    title = "Skills",
    back = "Go back",
    search = "Search skills",
    reload = "Reload skills",
    loading = "Loading skills…",
    refreshing = "Refreshing skills…",
    emptyTitle = "No skills yet",
    emptyMessage = "Skills that opencode finds on the server show up here.",
    noMatchTitle = "No matching skills",
    noMatchMessage = "No skill matches your search.",
    open = "Open skill",
    auto = "Used automatically",
    retry = "Try again",
    errors = mapOf(
        CatalogFailure.NotConnected to "Not connected to a server. Go back and connect again.",
        CatalogFailure.Unreachable to "The server could not be reached. Check the connection and try again.",
        CatalogFailure.Unauthorized to "The server refused the credentials.",
        CatalogFailure.NotFound to "The server did not find its skills.",
        CatalogFailure.Rejected to "The server refused the request.",
        CatalogFailure.ServerFailed to "The server ran into an error. Try again later.",
        CatalogFailure.Unknown to "Something went wrong while reading the skills.",
    ),
    detailLoading = "Loading the skill…",
    detailNotFound = "This skill is no longer on the server.",
    source = "Source",
    use = "Use in a new session",
    noContent = "This skill has no text.",
)
