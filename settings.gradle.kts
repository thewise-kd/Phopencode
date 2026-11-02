import io.github.thewisekd.phopencode.buildlogic.phopencodeModules

pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("androidx\\..*")
                includeGroupByRegex("com\\.android(\\..*)?")
                includeGroupByRegex("com\\.google\\.(android|testing)\\..*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("phopencode.settings")
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google {
            content {
                includeGroupByRegex("androidx\\..*")
                includeGroupByRegex("com\\.android(\\..*)?")
                includeGroupByRegex("com\\.google\\.(android|testing)\\..*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "phopencode"

// The module map of docs/PHASES.md. Android modules are included only when an
// Android SDK is present, which is what lets the Pi build the rest.
phopencodeModules {
    jvm(
        ":core:model",
        ":core:api",
        ":core:api-http",
        ":core:session",
        ":core:ssh",
        ":core:remote",
        ":core:files",
        ":core:git",
        ":core:storage-api",
        ":core:navigation",
        ":core:testing",
        ":feature:connect:logic",
        ":feature:sessions:logic",
        ":feature:chat:logic",
        ":feature:catalog:logic",
        ":feature:files:logic",
        ":feature:git:logic",
        ":feature:notify:logic",
        ":feature:settings:logic",
    )
    android(
        ":core:storage",
        ":core:ui",
        ":core:markdown",
        ":core:codeview",
        ":feature:connect:ui",
        ":feature:sessions:ui",
        ":feature:chat:ui",
        ":feature:catalog:ui",
        ":feature:files:ui",
        ":feature:git:ui",
        ":feature:notify:ui",
        ":feature:settings:ui",
        ":app",
    )
}
