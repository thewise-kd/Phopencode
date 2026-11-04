package io.github.thewisekd.phopencode.feature.sessions.ui.create

import io.github.thewisekd.phopencode.core.model.ModelRef

/**
 * Test tags of the creation screen's controls. Agent and model tags carry server IDs only, never a
 * folder or a title.
 */
internal object SessionCreateTags {
    const val FOLDER = "sessioncreate:folder"
    const val TITLE = "sessioncreate:title"
    const val AGENT_DEFAULT = "sessioncreate:agent-default"
    const val MODEL = "sessioncreate:model"
    const val MODEL_DEFAULT = "sessioncreate:model-default"
    const val CREATE = "sessioncreate:create"

    fun agent(id: String): String = "sessioncreate:agent:$id"

    fun model(ref: ModelRef): String = "sessioncreate:model:${ref.providerId}/${ref.modelId}"
}
