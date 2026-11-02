package io.github.thewisekd.phopencode.core.navigation

import kotlinx.serialization.Serializable

sealed interface CatalogRoute : Route {
    @Serializable
    data object Skills : CatalogRoute

    @Serializable
    data class SkillDetail(val skillId: String) : CatalogRoute

    /** The command, agent and model tabs. */
    @Serializable
    data object Registry : CatalogRoute

    @Serializable
    data class CommandDetail(val name: String) : CatalogRoute

    @Serializable
    data class AgentDetail(val name: String) : CatalogRoute

    @Serializable
    data class ModelDetail(val providerId: String, val modelId: String) : CatalogRoute

    /** MCP and plugin status. View only, with any error on the row, so there is no detail page. */
    @Serializable
    data object Status : CatalogRoute
}
