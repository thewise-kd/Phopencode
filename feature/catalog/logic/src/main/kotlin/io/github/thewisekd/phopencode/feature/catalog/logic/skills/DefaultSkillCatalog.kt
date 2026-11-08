package io.github.thewisekd.phopencode.feature.catalog.logic.skills

import io.github.thewisekd.phopencode.core.model.Skill
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.CatalogConnection
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.SkillCatalog

/**
 * Stub from the contract (P10.1): reads the skills on every call and keeps nothing. P10.2 fills it in
 * without changing the constructor: kept per connection generation, as [SkillCatalog] describes.
 * `CatalogModule` makes one for the app; it takes no scope, so it never captures a screen's.
 *
 * @param connection The catalog of the active connection.
 */
class DefaultSkillCatalog(private val connection: CatalogConnection) : SkillCatalog {
    override suspend fun skills(refresh: Boolean): List<Skill> = connection.call { catalog, _ -> catalog.skills() }

    /** Keeps skills' text and paths out of logs. */
    override fun toString(): String = "DefaultSkillCatalog"
}
