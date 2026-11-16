package io.github.thewisekd.phopencode.feature.connect.ui.progress

import io.github.thewisekd.phopencode.feature.connect.logic.contract.ProgressStep

/** The masked field of the `opencode serve` password prompt. */
internal const val SERVER_SECRET_FIELD_TAG = "progress:server-secret"

/** The panel that says why the connect stopped. */
internal const val PROGRESS_ERROR_TAG = "progress:error"

/** The toggle that opens and closes the debug detail (P9.38). */
internal const val DETAILS_TOGGLE_TAG = "progress:details-toggle"

/** The debug detail's text, while open. */
internal const val DETAILS_TEXT_TAG = "progress:details"

/** The button that copies the debug detail, while open. */
internal const val DETAILS_COPY_TAG = "progress:details-copy"

/** One row of the step indicator. */
internal fun stepTag(step: ProgressStep): String = "progress:step:${step.name}"
