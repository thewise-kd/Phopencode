package io.github.thewisekd.phopencode.feature.chat.ui.switcher

/** The tag on the compact control above the composer. */
internal const val SWITCHER_CONTROL_TAG = "switcher:control"

/** The tags on the control's current agent, model and variant. */
internal const val SWITCHER_AGENT_TAG = "switcher:agent"
internal const val SWITCHER_MODEL_TAG = "switcher:model"
internal const val SWITCHER_VARIANT_TAG = "switcher:variant"

/** The tag on the bottom sheet's content. */
internal const val SWITCHER_SHEET_TAG = "switcher:sheet"

/** The tags on the sheet's title and its close button. */
internal const val SWITCHER_TITLE_TAG = "switcher:title"
internal const val SWITCHER_CLOSE_TAG = "switcher:close"

/** The tags on the still notes while the lists load and while a switch is on its way. */
internal const val SWITCHER_LOADING_TAG = "switcher:loading"
internal const val SWITCHER_SWITCHING_TAG = "switcher:switching"

/** The tag on the failed load's or switch's banner. */
internal const val SWITCHER_ERROR_TAG = "switcher:error"

/** The tags on the two sections' titles. */
internal const val SWITCHER_AGENTS_TAG = "switcher:agents"
internal const val SWITCHER_MODELS_TAG = "switcher:models"

/** The tags on the notes drawn when a loaded list is empty. */
internal const val SWITCHER_NO_AGENTS_TAG = "switcher:no_agents"
internal const val SWITCHER_NO_MODELS_TAG = "switcher:no_models"

/** The tag on the agent [id]'s row in the sheet. */
internal fun switcherAgentTag(id: String): String = "switcher:agent:$id"

/** The tag on the model's row in the sheet. */
internal fun switcherModelTag(providerId: String, modelId: String): String = "switcher:model:$providerId/$modelId"

/** The tag on the model's [variant] choice; null is its default variant. */
internal fun switcherVariantTag(providerId: String, modelId: String, variant: String?): String =
    if (variant == null) "switcher:variant_default:$providerId/$modelId" else "switcher:variant:$providerId/$modelId:$variant"
