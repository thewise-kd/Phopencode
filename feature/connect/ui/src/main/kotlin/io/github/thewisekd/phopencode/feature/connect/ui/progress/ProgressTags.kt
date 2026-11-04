package io.github.thewisekd.phopencode.feature.connect.ui.progress

import io.github.thewisekd.phopencode.feature.connect.logic.contract.ProgressStep

/** The masked field of the `opencode serve` password prompt. */
internal const val SERVER_SECRET_FIELD_TAG = "progress:server-secret"

/** The panel that says why the connect stopped. */
internal const val PROGRESS_ERROR_TAG = "progress:error"

/** One row of the step indicator. */
internal fun stepTag(step: ProgressStep): String = "progress:step:${step.name}"
