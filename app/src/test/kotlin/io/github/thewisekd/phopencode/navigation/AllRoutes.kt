package io.github.thewisekd.phopencode.navigation

import io.github.thewisekd.phopencode.core.navigation.CatalogRoute
import io.github.thewisekd.phopencode.core.navigation.ChatRoute
import io.github.thewisekd.phopencode.core.navigation.ConnectRoute
import io.github.thewisekd.phopencode.core.navigation.FilesRoute
import io.github.thewisekd.phopencode.core.navigation.GitRoute
import io.github.thewisekd.phopencode.core.navigation.NotifyRoute
import io.github.thewisekd.phopencode.core.navigation.Route
import io.github.thewisekd.phopencode.core.navigation.SessionsRoute
import io.github.thewisekd.phopencode.core.navigation.SettingsRoute

/**
 * The test tag each feature graph puts on the placeholder it opens for [route]. The placeholders
 * live in the features' `<F>Graph.kt` files, which cannot share code with `app`, so the format is
 * repeated there.
 */
fun placeholderTag(route: Route): String = "placeholder:$route"

/**
 * One instance of every route. Arguments hold slashes, spaces and Turkish letters, which a route
 * must survive. A missing route fails `every route has a sample`.
 */
val allRoutes: List<Route> = listOf(
    ConnectRoute.ServerList,
    ConnectRoute.ServerForm(serverId = "server-1"),
    ConnectRoute.ServerForm(),
    ConnectRoute.Keys,
    ConnectRoute.HostKey(serverId = "server-1"),
    ConnectRoute.FolderPicker,
    ConnectRoute.Progress(serverId = "server-1"),
    SessionsRoute.SessionList(directory = "/srv/çalışma alanı"),
    SessionsRoute.SessionList(),
    SessionsRoute.Create(directory = "/srv/app", skillId = "skill-1"),
    SessionsRoute.Manage(sessionId = "ses_1"),
    ChatRoute.Chat(sessionId = "ses_1"),
    ChatRoute.Subagents(sessionId = "ses_1"),
    ChatRoute.ChildSession(sessionId = "ses_2"),
    ChatRoute.TurnDiff(sessionId = "ses_1", messageId = "msg_1"),
    ChatRoute.TokenUsage(sessionId = "ses_1"),
    CatalogRoute.Skills,
    CatalogRoute.SkillDetail(skillId = "skill-1"),
    CatalogRoute.Registry,
    CatalogRoute.CommandDetail(name = "review"),
    CatalogRoute.AgentDetail(name = "build"),
    CatalogRoute.ModelDetail(providerId = "provider", modelId = "model/large"),
    CatalogRoute.Status,
    FilesRoute.Tree(directory = "/srv/app"),
    FilesRoute.Viewer(directory = "/srv/app", path = "src/main kodu.kt", line = 42),
    FilesRoute.Editor(directory = "/srv/app", path = "src/main.kt"),
    FilesRoute.Search(directory = "/srv/app"),
    FilesRoute.Transfer(directory = "/srv/app", path = "/srv/app/docs"),
    GitRoute.Status(directory = "/srv/app"),
    GitRoute.FileDiff(directory = "/srv/app", path = "src/main.kt", staged = true),
    GitRoute.Log(directory = "/srv/app"),
    GitRoute.CommitDetail(directory = "/srv/app", commit = "0123abcd"),
    GitRoute.CommitFileDiff(directory = "/srv/app", commit = "0123abcd", path = "src/main.kt"),
    GitRoute.Commit(directory = "/srv/app"),
    GitRoute.Branches(directory = "/srv/app"),
    GitRoute.Remote(directory = "/srv/app"),
    NotifyRoute.Setup,
    NotifyRoute.Pending,
    SettingsRoute.Preferences,
    SettingsRoute.Licenses,
)
