package io.github.thewisekd.phopencode.feature.preview.logic.contract

/**
 * The live preview's first-visit notice (P13.24), shown over the port picker (`PreviewRoute.Ports`)
 * until the user confirms it once. Whether it was confirmed is
 * `AppSettings.previewNoticeAccepted`, which `SettingsRepository.acceptPreviewNotice` sets for good.
 *
 * Rules the logic keeps:
 * - It starts [NoticePhase.Checking] and reads the setting once the scope runs: confirmed is
 *   [NoticePhase.Hidden], anything else, including a setting that cannot be read, is
 *   [NoticePhase.Showing].
 * - [NoticeIntent.Accept] stores the confirmation ([NoticePhase.Saving]); once stored the notice is
 *   [NoticePhase.Hidden]. If storing fails the notice shows again with [NoticeState.saveFailed], and
 *   the user can try again.
 * - [NoticeIntent.NotNow] (and back) emits [NoticeEvent.Leave] and stores nothing, so the notice
 *   shows on the next visit. It counts only while the dialog waits ([NoticePhase.Showing]): while
 *   the confirmation is stored ([NoticePhase.Saving]) it is ignored, so the user leaves only after
 *   the store has finished or failed, never with an acceptance still on its way. Leaving is final
 *   ([NoticePhase.Left]): one [NoticeEvent.Leave] is sent, and every later intent does nothing.
 *
 * @property phase Where the notice is.
 * @property saveFailed The last attempt to store the confirmation failed.
 */
data class NoticeState(
    val phase: NoticePhase = NoticePhase.Checking,
    val saveFailed: Boolean = false,
) {
    /** The port list must not be used: the notice shows, or it is not known yet whether it must. */
    val blocksScreen: Boolean
        get() = phase != NoticePhase.Hidden

    /** The dialog is on screen; its answers work only in [NoticePhase.Showing]. */
    val dialogShown: Boolean
        get() = phase == NoticePhase.Showing || phase == NoticePhase.Saving || phase == NoticePhase.Left
}

enum class NoticePhase {
    /** The setting is being read; nothing is shown yet, but the port list waits. */
    Checking,

    /** The dialog waits for the user. */
    Showing,

    /** The confirmation is being stored; the dialog stays up. */
    Saving,

    /** Confirmed, now or before: the port list is free to use. */
    Hidden,

    /**
     * The user chose "Not now" or back, and the screen is closing. Final: nothing is stored and every
     * intent is ignored. The dialog stays up, its answers disabled, and the port list stays blocked
     * until the screen is gone.
     */
    Left,
}

sealed interface NoticeIntent {
    /** "I have read and understood". */
    data object Accept : NoticeIntent

    /** "Not now", or back. */
    data object NotNow : NoticeIntent
}

sealed interface NoticeEvent {
    /** Leave the live preview without confirming. */
    data object Leave : NoticeEvent
}

interface NoticePresenter : Presenter<NoticeState, NoticeIntent, NoticeEvent>
