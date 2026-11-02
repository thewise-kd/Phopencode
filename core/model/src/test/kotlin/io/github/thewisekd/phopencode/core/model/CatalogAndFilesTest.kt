package io.github.thewisekd.phopencode.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CatalogAndFilesTest {
    @Test
    fun `a session without a parent is a root session`() {
        assertTrue(Session(id = SessionId("s1")).isRoot)
        assertFalse(Session(id = SessionId("s2"), parentId = SessionId("s1")).isRoot)
    }

    @Test
    fun `skill, command and agent hold what the catalog shows`() {
        val skill = Skill(id = "s", name = "review", path = "skills/review.md", content = "# Review")
        val command = Command(name = "init")
        val agent = Agent(name = "plan", mode = AgentMode.Primary, hidden = true)

        assertFalse(skill.autoInvoke)
        assertNull(command.description)
        assertTrue(agent.hidden)
    }

    @Test
    fun `a model without limits or pricing is representable`() {
        val model = Model(id = "m", providerId = "p", name = "Model")

        assertNull(model.limit.context)
        assertNull(model.pricing)
    }

    @Test
    fun `a model holds limits and exact pricing`() {
        val model = Model(
            id = "m",
            providerId = "p",
            name = "Model",
            limit = ModelLimit(context = 200_000, output = 8_000),
            pricing = ModelPricing(input = Cost.parse("0.15"), output = Cost.parse("0.6")),
            variants = listOf("high"),
        )

        assertEquals(200_000, model.limit.context)
        assertEquals(Cost.parse("0.150"), model.pricing?.input)
        assertNull(model.pricing?.cacheRead)
    }

    @Test
    fun `a model reference may name a variant`() {
        assertEquals("high", ModelRef(providerId = "p", modelId = "m", variant = "high").variant)
        assertNull(ModelRef(providerId = "p", modelId = "m").variant)
    }

    @Test
    fun `a changed file holds its status and line counts`() {
        val file = VcsFile(path = "src/App.kt", status = VcsFileStatus.Modified, additions = 3, deletions = 1)

        assertEquals(VcsFileStatus.Modified, file.status)
        assertEquals(3, file.additions)
    }

    @Test
    fun `a binary change may have no line counts`() {
        val file = VcsFile(path = "a.png", status = VcsFileStatus.Added)

        assertNull(file.additions)
        assertNull(file.deletions)
    }

    @Test
    fun `a file entry derives its name from the path`() {
        fun entry(path: String) = FileEntry(path = path, type = FileEntryType.File)

        assertEquals("App.kt", entry("/srv/app/App.kt").name)
        assertEquals("App.kt", entry("App.kt").name)
        assertEquals("app", entry("/srv/app/").name)
        assertEquals("/", entry("/").name)
        assertEquals("", entry("").name)
        assertEquals("kısa adı.txt", entry("/srv/kısa adı.txt").name)
    }

    @Test
    fun `a file entry from the API has no size or time`() {
        val fromApi = FileEntry(path = "a", type = FileEntryType.Directory)

        assertNull(fromApi.sizeBytes)
        assertNull(fromApi.modifiedAt)
        assertFalse(fromApi.ignored)
    }
}
