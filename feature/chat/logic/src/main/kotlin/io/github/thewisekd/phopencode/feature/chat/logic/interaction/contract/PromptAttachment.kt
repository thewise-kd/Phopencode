package io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract

/**
 * What goes along with a prompt besides its text (P7.8 picks them, the composer sends them). The
 * server does not read `@name` or `/name` out of the text, so an attachment is the only way a file,
 * an agent or a skill reaches it: the composer sends [File] as `Prompt.files`, [Agent] as
 * `Prompt.agents` and [Skill] as `Prompt.skills`, each in the order attached, each once.
 */
sealed interface PromptAttachment {
    /**
     * A file or folder, by the path `FsApi.find` gave for the session's folder. Prints masked: a path
     * can name a user's project or person.
     */
    data class File(val path: String) : PromptAttachment {
        override fun toString(): String = "File(***)"
    }

    /** An agent, by `Agent.id`, the name the API takes. */
    data class Agent(val id: String) : PromptAttachment

    /**
     * A skill, by `Skill.id`, the ID the API takes (`PromptInput.SkillAttachment`). It is not
     * `Skill.name`, the display form: for the server's own skills they differ, as id `opencode` for
     * name `OpenCode`.
     */
    data class Skill(val id: String) : PromptAttachment
}
