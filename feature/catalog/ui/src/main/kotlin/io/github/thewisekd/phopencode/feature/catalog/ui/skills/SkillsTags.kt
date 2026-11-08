package io.github.thewisekd.phopencode.feature.catalog.ui.skills

/** The scrolling list of skills, which a pull down reloads. */
internal const val SKILLS_LIST_TAG = "catalog_skills:list"

/** A skill's card in the list. Carries the skill's ID, never its name or text. */
internal fun skillCardTag(id: String): String = "catalog_skills:skill:$id"

/** The scrolling body of a skill's details. */
internal const val SKILL_DETAIL_BODY_TAG = "catalog_skills:detail"

/** The line that says where a skill's file is. Carries no path. */
internal const val SKILL_SOURCE_TAG = "catalog_skills:source"

/** The skill's text, rendered as Markdown. */
internal const val SKILL_CONTENT_TAG = "catalog_skills:content"
