package io.github.thewisekd.phopencode.core.navigation

import kotlinx.serialization.Serializable

/**
 * Forms, permissions, the command palette, the agent and model picker and the action menu are
 * cards and sheets inside [Chat], so they have no route.
 */
sealed interface ChatRoute : Route {
    @Serializable
    data class Chat(val sessionId: String) : ChatRoute

    /** The subagents of a session. */
    @Serializable
    data class Subagents(val sessionId: String) : ChatRoute

    /**
     * The timeline of a subagent's session. Its parent comes from the session itself, because a
     * pending item can open this route with no parent on the back stack.
     */
    @Serializable
    data class ChildSession(val sessionId: String) : ChatRoute

    /** The files one turn changed, and its revert. [messageId] identifies the turn. */
    @Serializable
    data class TurnDiff(val sessionId: String, val messageId: String) : ChatRoute

    /** The detail page of the session's token use. */
    @Serializable
    data class TokenUsage(val sessionId: String) : ChatRoute
}
