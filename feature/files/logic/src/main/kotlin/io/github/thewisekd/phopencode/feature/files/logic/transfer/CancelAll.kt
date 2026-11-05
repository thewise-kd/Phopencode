package io.github.thewisekd.phopencode.feature.files.logic.transfer

import io.github.thewisekd.phopencode.feature.files.logic.contract.TransferQueue

/**
 * Cancels every job of the queue not yet finished, with the same cleanup as [TransferQueue.cancel].
 *
 * On [DefaultTransferQueue] this is atomic with respect to scheduling: every unfinished job is
 * marked cancelled at once before the running one is stopped, so no other job can start in between.
 * Any other queue gets each job cancelled in turn, newest first, so that ending the running job
 * starts as few as possible of the ones about to be cancelled.
 */
fun TransferQueue.cancelAll() {
    if (this is DefaultTransferQueue) {
        cancelAllJobs()
        return
    }
    jobs.value.filterNot { it.status.isFinished }.asReversed().forEach { cancel(it.id) }
}
