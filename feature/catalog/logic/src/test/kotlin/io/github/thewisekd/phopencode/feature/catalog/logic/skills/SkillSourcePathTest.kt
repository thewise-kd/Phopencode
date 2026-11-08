package io.github.thewisekd.phopencode.feature.catalog.logic.skills

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SkillSourcePathTest {
    @Test
    fun `a home folder shows as a tilde`() {
        assertEquals("~/.config/opencode/skill/review/SKILL.md", skillSourcePath("/home/ayşe/.config/opencode/skill/review/SKILL.md"))
        assertEquals("~/.claude/skills/review/SKILL.md", skillSourcePath("/Users/ayse/.claude/skills/review/SKILL.md"))
        assertEquals("~/.config/opencode/skill/review/SKILL.md", skillSourcePath("/root/.config/opencode/skill/review/SKILL.md"))
        assertEquals("~\\.config\\opencode\\skill\\review\\SKILL.md", skillSourcePath("C:\\Users\\ayse\\.config\\opencode\\skill\\review\\SKILL.md"))
        assertEquals("~", skillSourcePath("/home/ayse"))
        assertEquals("~/", skillSourcePath("/home/ayse/"))
    }

    @Test
    fun `a path outside a home folder stays as it is`() {
        assertEquals("/srv/project/.opencode/skill/review/SKILL.md", skillSourcePath("/srv/project/.opencode/skill/review/SKILL.md"))
        assertEquals("/home", skillSourcePath("/home"))
        assertEquals("/home/", skillSourcePath("/home/"))
        assertEquals("/homework/review/SKILL.md", skillSourcePath("/homework/review/SKILL.md"))
        assertEquals("/rootfs/review/SKILL.md", skillSourcePath("/rootfs/review/SKILL.md"))
        assertEquals(".opencode/skill/review/SKILL.md", skillSourcePath(".opencode/skill/review/SKILL.md"))
    }

    @Test
    fun `surrounding blanks go, and a blank path is none`() {
        assertEquals("~/x/SKILL.md", skillSourcePath("  /home/ayse/x/SKILL.md\n"))
        assertNull(skillSourcePath(""))
        assertNull(skillSourcePath("   "))
    }
}
