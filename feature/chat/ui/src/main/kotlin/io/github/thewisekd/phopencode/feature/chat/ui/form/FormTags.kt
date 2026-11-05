package io.github.thewisekd.phopencode.feature.chat.ui.form

/** Test tags of a form card's parts. A tag with a field key is unique within one card. */
object FormTags {
    const val HEADING: String = "chat:form:heading"
    const val SUBAGENT: String = "chat:form:subagent"
    const val STEP: String = "chat:form:step"
    const val BACK: String = "chat:form:back"
    const val NEXT: String = "chat:form:next"
    const val SUBMIT: String = "chat:form:submit"
    const val DECLINE: String = "chat:form:decline"
    const val PROGRESS: String = "chat:form:progress"
    const val ERROR: String = "chat:form:error"
    const val LIST_ERROR: String = "chat:form:list-error"

    /** The whole field: title, description, input and problem. */
    fun field(key: String): String = "chat:form:field:$key"

    /** One option's radio button or checkbox row. */
    fun option(key: String, value: String): String = "chat:form:option:$key:$value"

    /** The "your own answer" radio button or checkbox row. */
    fun own(key: String): String = "chat:form:own:$key"

    /** The text field of the own answer, shown while it is picked. */
    fun ownInput(key: String): String = "chat:form:own-input:$key"

    /** The text field of a text or number field. */
    fun input(key: String): String = "chat:form:input:$key"

    fun yes(key: String): String = "chat:form:yes:$key"

    fun no(key: String): String = "chat:form:no:$key"

    /** The button that opens an external step's page. */
    fun open(key: String): String = "chat:form:open:$key"

    /** The field's problem, when shown. */
    fun problem(key: String): String = "chat:form:problem:$key"
}
