package io.github.thewisekd.phopencode.feature.chat.ui

import io.github.thewisekd.phopencode.core.model.SessionId
import io.github.thewisekd.phopencode.core.navigation.ChatRoute
import io.github.thewisekd.phopencode.feature.chat.logic.composer.DefaultComposerPresenter
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ActiveChatSession
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatSession
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ComposerPresenter
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ReasoningPresenter
import io.github.thewisekd.phopencode.feature.chat.logic.contract.StatusPresenter
import io.github.thewisekd.phopencode.feature.chat.logic.contract.TimelinePresenter
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ToolPresenter
import io.github.thewisekd.phopencode.feature.chat.logic.contract.UsagePresenter
import io.github.thewisekd.phopencode.feature.chat.logic.reasoning.DefaultReasoningPresenter
import io.github.thewisekd.phopencode.feature.chat.logic.status.DefaultStatusPresenter
import io.github.thewisekd.phopencode.feature.chat.logic.timeline.DefaultTimelinePresenter
import io.github.thewisekd.phopencode.feature.chat.logic.tool.DefaultToolPresenter
import io.github.thewisekd.phopencode.feature.chat.logic.usage.DefaultUsagePresenter
import kotlinx.coroutines.CoroutineScope
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * The chat feature's definitions, which the app loads at startup. The connection manager comes
 * from the app's core modules.
 *
 * Every definition is a factory, built per destination (see `rememberPresenter` in ChatGraph) with
 * the destination's route and coroutine scope. The scope is a parameter, never `get()`: the graph's
 * unqualified scope is the app's.
 *
 * - [ChatSession] takes `(route, scope)`: one per destination, bound to the active connection's
 *   server.
 * - Each presenter takes `(route, scope, session)`: the third is that destination's [ChatSession],
 *   so that all the parts of one chat share one event stream and one resync.
 */
val chatModule: Module = module {
    factory<ChatSession> { (route: ChatRoute, scope: CoroutineScope) ->
        ActiveChatSession(
            sessionId = SessionId(route.chatSessionId()),
            connection = get(),
            scope = scope,
        )
    }
    factory<StatusPresenter> { (_: ChatRoute, scope: CoroutineScope, session: ChatSession) ->
        DefaultStatusPresenter(session = session, connection = get(), scope = scope)
    }
    factory<UsagePresenter> { (_: ChatRoute, scope: CoroutineScope, session: ChatSession) ->
        DefaultUsagePresenter(session = session, scope = scope)
    }
    factory<TimelinePresenter> { (_: ChatRoute, scope: CoroutineScope, session: ChatSession) ->
        DefaultTimelinePresenter(session = session, scope = scope)
    }
    factory<ReasoningPresenter> { (_: ChatRoute, scope: CoroutineScope, session: ChatSession) ->
        DefaultReasoningPresenter(session = session, scope = scope)
    }
    factory<ToolPresenter> { (_: ChatRoute, scope: CoroutineScope, session: ChatSession) ->
        DefaultToolPresenter(session = session, scope = scope)
    }
    factory<ComposerPresenter> { (_: ChatRoute, scope: CoroutineScope, session: ChatSession) ->
        DefaultComposerPresenter(session = session, scope = scope)
    }
}

/** The session every chat route is about. */
internal fun ChatRoute.chatSessionId(): String = when (this) {
    is ChatRoute.Chat -> sessionId
    is ChatRoute.Subagents -> sessionId
    is ChatRoute.ChildSession -> sessionId
    is ChatRoute.TurnDiff -> sessionId
    is ChatRoute.TokenUsage -> sessionId
}
