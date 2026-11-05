package io.github.thewisekd.phopencode.feature.chat.ui

import io.github.thewisekd.phopencode.core.model.SessionId
import io.github.thewisekd.phopencode.core.navigation.ChatRoute
import io.github.thewisekd.phopencode.feature.chat.logic.actions.DefaultActionsPresenter
import io.github.thewisekd.phopencode.feature.chat.logic.composer.DefaultComposerPresenter
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ActiveChatSession
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatSession
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ComposerPresenter
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ReasoningPresenter
import io.github.thewisekd.phopencode.feature.chat.logic.contract.StatusPresenter
import io.github.thewisekd.phopencode.feature.chat.logic.contract.TimelinePresenter
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ToolPresenter
import io.github.thewisekd.phopencode.feature.chat.logic.contract.UsagePresenter
import io.github.thewisekd.phopencode.feature.chat.logic.form.DefaultFormPresenter
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.ActionsPresenter
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.FormPresenter
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PalettePresenter
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PermissionPresenter
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.RevertPresenter
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SubagentPresenter
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SwitcherPresenter
import io.github.thewisekd.phopencode.feature.chat.logic.palette.DefaultPalettePresenter
import io.github.thewisekd.phopencode.feature.chat.logic.permission.DefaultPermissionPresenter
import io.github.thewisekd.phopencode.feature.chat.logic.reasoning.DefaultReasoningPresenter
import io.github.thewisekd.phopencode.feature.chat.logic.revert.DefaultRevertPresenter
import io.github.thewisekd.phopencode.feature.chat.logic.status.DefaultStatusPresenter
import io.github.thewisekd.phopencode.feature.chat.logic.subagent.DefaultSubagentPresenter
import io.github.thewisekd.phopencode.feature.chat.logic.switcher.DefaultSwitcherPresenter
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
 *   so that all the parts of one chat share one event stream and one resync. The interaction
 *   presenters of P7 are built with exactly these three, so their logic tasks never change this
 *   file.
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
    factory<FormPresenter> { (route: ChatRoute, scope: CoroutineScope, session: ChatSession) ->
        DefaultFormPresenter(route = route, scope = scope, session = session)
    }
    factory<PermissionPresenter> { (route: ChatRoute, scope: CoroutineScope, session: ChatSession) ->
        DefaultPermissionPresenter(route = route, scope = scope, session = session)
    }
    factory<SubagentPresenter> { (route: ChatRoute, scope: CoroutineScope, session: ChatSession) ->
        DefaultSubagentPresenter(route = route, scope = scope, session = session)
    }
    factory<PalettePresenter> { (route: ChatRoute, scope: CoroutineScope, session: ChatSession) ->
        DefaultPalettePresenter(route = route, scope = scope, session = session)
    }
    factory<SwitcherPresenter> { (route: ChatRoute, scope: CoroutineScope, session: ChatSession) ->
        DefaultSwitcherPresenter(route = route, scope = scope, session = session)
    }
    factory<RevertPresenter> { (route: ChatRoute, scope: CoroutineScope, session: ChatSession) ->
        DefaultRevertPresenter(route = route, scope = scope, session = session)
    }
    factory<ActionsPresenter> { (route: ChatRoute, scope: CoroutineScope, session: ChatSession) ->
        DefaultActionsPresenter(route = route, scope = scope, session = session)
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
