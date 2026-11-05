package io.github.thewisekd.phopencode.feature.files.ui.transfer

import io.github.thewisekd.phopencode.feature.files.logic.contract.TransferDirection
import io.github.thewisekd.phopencode.feature.files.logic.contract.TransferJob
import io.github.thewisekd.phopencode.feature.files.logic.contract.TransferStatus

/** Where the job a notification shows stands. */
internal enum class TransferPhase {
    Waiting,
    Running,
    AwaitingAnswer,
}

/**
 * What the notification shows of the queue: one job, by its file name only, and how many other
 * unfinished jobs wait. Never a folder or a remote path, since a notification shows on the lock
 * screen and other apps can read it. Equal summaries draw the same notification, so progress is
 * kept in whole percent and the notification is redrawn at most a hundred times per job.
 *
 * @property percent Null while the size is not known.
 * @property others The unfinished jobs besides the one shown.
 */
internal data class TransferSummary(
    val name: String,
    val direction: TransferDirection,
    val phase: TransferPhase,
    val percent: Int?,
    val others: Int,
)

/**
 * Sums up [jobs]; null when none is unfinished, which is when the service stops. The running job
 * is shown first; without one, a job waiting for an overwrite answer, which holds up the queue;
 * otherwise the oldest queued one.
 */
internal fun summarize(jobs: List<TransferJob>): TransferSummary? {
    val active = jobs.filterNot { it.status.isFinished }
    if (active.isEmpty()) return null
    val shown = active.firstOrNull { it.status == TransferStatus.Running }
        ?: active.firstOrNull { it.status == TransferStatus.AwaitingOverwrite }
        ?: active.first()
    val phase = when (shown.status) {
        TransferStatus.Running -> TransferPhase.Running
        TransferStatus.AwaitingOverwrite -> TransferPhase.AwaitingAnswer
        else -> TransferPhase.Waiting
    }
    return TransferSummary(
        name = baseName(shown.localName),
        direction = shown.direction,
        phase = phase,
        percent = if (phase == TransferPhase.Running) shown.fraction?.let { (it * 100).toInt() } else null,
        others = active.size - 1,
    )
}

/** The last part of [name], so that a provider's odd name never brings a path along. */
internal fun baseName(name: String): String = name.trimEnd('/').substringAfterLast('/').ifEmpty { name.replace('/', '_') }
