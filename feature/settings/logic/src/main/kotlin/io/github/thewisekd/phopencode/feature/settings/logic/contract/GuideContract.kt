package io.github.thewisekd.phopencode.feature.settings.logic.contract

import io.github.thewisekd.phopencode.core.storage_api.AppSettings
import io.github.thewisekd.phopencode.core.storage_api.SettingsRepository

/**
 * The usage guide, `SettingsRoute.Guide` (P14.1): a few short pages on how to set up the server and
 * use the app. It opens by itself on the first launch, while [AppSettings.guideSeen] is false, and
 * from the preferences at any time.
 *
 * Rules the logic keeps:
 * - [GuideIntent.Next] and [GuideIntent.Back] move one page; Next does nothing on the last page, and
 *   Back on the first page leaves the guide as [GuideIntent.Skip] does.
 * - [GuideIntent.Skip] and [GuideIntent.Start] call [SettingsRepository.markGuideSeen], then send
 *   [GuideEvent.Finished], once. A store that fails, or does not answer in time, still sends it: the
 *   user is never kept in the guide.
 * - While [GuideState.finishing], every intent is ignored.
 */
enum class GuidePage {
    /** What the app does. */
    Welcome,

    /** Install opencode; `opencode serve` on loopback with a password; reached through SSH. */
    OnYourServer,

    /** Address, SSH user, key or password, and the host key fingerprint on the first connection. */
    AddServer,

    /** A project folder, a session, and answering questions and permission requests. */
    SessionsAndChat,

    FilesAndGit,

    /** Optional, through ntfy. */
    Notifications,

    /** Start the dev server yourself on `127.0.0.1`, then open the preview from a session menu. */
    LivePreview,

    /** The guide stays under Settings. */
    Done,
}

/**
 * @property page The page shown.
 * @property finishing Skip or Start was chosen: the guide is being recorded as seen, then closes.
 */
data class GuideState(
    val page: GuidePage = GuidePage.entries.first(),
    val finishing: Boolean = false,
) {
    /** The page's place, from 1 to [pageCount], as the page indicator and TalkBack say it. */
    val position: Int
        get() = page.ordinal + 1

    val pageCount: Int
        get() = GuidePage.entries.size

    val isFirst: Boolean
        get() = page == GuidePage.entries.first()

    val isLast: Boolean
        get() = page == GuidePage.entries.last()
}

sealed interface GuideIntent {
    data object Next : GuideIntent

    /** The previous page; on the first page, the same as [Skip]. The system back sends it too. */
    data object Back : GuideIntent

    /** The user swiped to [page]. */
    data class ShowPage(val page: GuidePage) : GuideIntent

    data object Skip : GuideIntent

    /** The last page's button. */
    data object Start : GuideIntent
}

sealed interface GuideEvent {
    /** The guide is done: the graph goes back to the screen below it. */
    data object Finished : GuideEvent
}

interface GuidePresenter : Presenter<GuideState, GuideIntent, GuideEvent>
