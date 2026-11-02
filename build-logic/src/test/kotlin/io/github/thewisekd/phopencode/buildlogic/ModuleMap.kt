package io.github.thewisekd.phopencode.buildlogic

/** The 32 modules of "Module map" in docs/PHASES.md, spelled out on the test side. */
object ModuleMap {
    private val FEATURES = listOf("connect", "sessions", "chat", "catalog", "files", "git", "notify", "settings")

    val jvm: List<String> = listOf(
        ":core:model", ":core:api", ":core:api-http", ":core:session", ":core:ssh", ":core:remote",
        ":core:files", ":core:git", ":core:storage-api", ":core:navigation", ":core:testing",
    ) + FEATURES.map { ":feature:$it:logic" }

    val androidCore: List<String> = listOf(":core:storage", ":core:ui", ":core:markdown", ":core:codeview")

    val featureUi: List<String> = FEATURES.map { ":feature:$it:ui" }

    val android: List<String> = androidCore + featureUi + ":app"
}
