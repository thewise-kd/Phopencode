package io.github.thewisekd.phopencode.core.navigation

/**
 * One instance of every route, with every string argument set to [text] and every optional
 * argument given. A route missing here fails `every concrete route has a sample`.
 */
internal fun routesWith(text: String): List<Route> = listOf(
    ConnectRoute.ServerList,
    ConnectRoute.ServerForm(serverId = text),
    ConnectRoute.Keys,
    ConnectRoute.HostKey(serverId = text),
    ConnectRoute.FolderPicker,
    ConnectRoute.Progress(serverId = text),
    SessionsRoute.SessionList(directory = text),
    SessionsRoute.Create(directory = text, skillId = text),
    SessionsRoute.Manage(sessionId = text),
    ChatRoute.Chat(sessionId = text),
    ChatRoute.Subagents(sessionId = text),
    ChatRoute.ChildSession(sessionId = text),
    ChatRoute.TurnDiff(sessionId = text, messageId = text),
    ChatRoute.TokenUsage(sessionId = text),
    CatalogRoute.Skills,
    CatalogRoute.SkillDetail(skillId = text),
    CatalogRoute.Registry,
    CatalogRoute.CommandDetail(name = text),
    CatalogRoute.AgentDetail(name = text),
    CatalogRoute.ModelDetail(providerId = text, modelId = text),
    CatalogRoute.Status,
    FilesRoute.Tree(directory = text),
    FilesRoute.Viewer(directory = text, path = text, line = 42),
    FilesRoute.Editor(directory = text, path = text),
    FilesRoute.Search(directory = text),
    FilesRoute.Transfer(directory = text, path = text),
    GitRoute.Status(directory = text),
    GitRoute.FileDiff(directory = text, path = text, staged = true),
    GitRoute.Log(directory = text),
    GitRoute.CommitDetail(directory = text, commit = text),
    GitRoute.CommitFileDiff(directory = text, commit = text, path = text),
    GitRoute.Commit(directory = text),
    GitRoute.Branches(directory = text),
    GitRoute.Remote(directory = text),
    NotifyRoute.Setup,
    NotifyRoute.Pending,
    SettingsRoute.Preferences,
    SettingsRoute.Licenses,
)

/** The routes with optional arguments, with those arguments left out. */
internal val routesWithDefaults: List<Route> = listOf(
    ConnectRoute.ServerForm(),
    SessionsRoute.SessionList(),
    SessionsRoute.Create(),
    FilesRoute.Viewer(directory = "/srv/app", path = "/srv/app/main.kt"),
    GitRoute.FileDiff(directory = "/srv/app", path = "main.kt"),
)
