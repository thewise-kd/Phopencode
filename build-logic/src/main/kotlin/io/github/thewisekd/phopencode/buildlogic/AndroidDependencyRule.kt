package io.github.thewisekd.phopencode.buildlogic

/**
 * What a JVM module may not resolve, as coordinates. Group names cover Android and
 * the UI libraries of the catalog; those UI libraries publish JVM variants too, but
 * they belong in the thin Android modules. The name rule catches a JVM group that
 * publishes an Android artifact, such as tink-android or koin-android.
 */
object AndroidDependencyRule {
    private val FORBIDDEN_GROUP = Regex(
        """(android|androidx|com\.android|com\.google\.android|org\.robolectric|org\.jetbrains\.compose""" +
            """|io\.github\.rosemoe|io\.coil-kt|com\.mikepenz)(\..*)?""",
    )
    private val FORBIDDEN_NAME = Regex("""(^|.*[-_.])androidx?([-_.].*|$)""")

    /** Returns why the module is rejected, or null when it is allowed. */
    fun violation(group: String, name: String): String? = when {
        FORBIDDEN_GROUP.matches(group) -> "the group $group is Android or UI only"
        FORBIDDEN_NAME.matches(name) -> "the artifact $name is built for Android"
        else -> null
    }
}
