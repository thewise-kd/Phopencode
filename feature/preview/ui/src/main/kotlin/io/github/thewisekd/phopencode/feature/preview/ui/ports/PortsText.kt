package io.github.thewisekd.phopencode.feature.preview.ui.ports

import androidx.annotation.StringRes
import io.github.thewisekd.phopencode.feature.preview.logic.contract.ManualPortProblem
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewFailure
import io.github.thewisekd.phopencode.feature.preview.ui.R

/**
 * Listing again cannot help without a connection: going back to the connection fixes it. Every other
 * failure of the listing may pass on a second try.
 */
internal val PreviewFailure.canRetry: Boolean
    get() = this != PreviewFailure.NotConnected

/**
 * What the screen says about a failed listing, with what to do next where there is something. The
 * failures of the tunnel and the bridge cannot come from a listing; they, and an unknown one, read
 * as a plain "could not be listed".
 */
@StringRes
internal fun PreviewFailure.listingMessageRes(): Int = when (this) {
    PreviewFailure.NotConnected -> R.string.preview_ports_error_not_connected
    PreviewFailure.ConnectionLost -> R.string.preview_ports_error_connection_lost
    PreviewFailure.TimedOut -> R.string.preview_ports_error_timed_out
    PreviewFailure.OutputTooLarge -> R.string.preview_ports_error_output_too_large
    PreviewFailure.CommandFailed -> R.string.preview_ports_error_command_failed
    PreviewFailure.OpencodePort,
    PreviewFailure.NothingListens,
    PreviewFailure.BridgeFailed,
    PreviewFailure.Unknown,
    -> R.string.preview_ports_error_other
}

/** Why the manual entry cannot be opened, in place of the field's hint. */
@get:StringRes
internal val ManualPortProblem.messageRes: Int
    get() = when (this) {
        ManualPortProblem.Empty -> R.string.preview_ports_manual_empty
        ManualPortProblem.NotANumber -> R.string.preview_ports_manual_not_a_number
        ManualPortProblem.OutOfRange -> R.string.preview_ports_manual_out_of_range
        ManualPortProblem.OpencodePort -> R.string.preview_ports_manual_opencode
    }
