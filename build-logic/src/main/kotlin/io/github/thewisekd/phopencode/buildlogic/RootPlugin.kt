package io.github.thewisekd.phopencode.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.Exec
import org.gradle.kotlin.dsl.register

/**
 * Root tasks. Gradle runs a task name in every project that has it, so
 * `./gradlew jvmCheck` reaches each JVM module without any project listing another.
 */
class RootPlugin : Plugin<Project> {
    override fun apply(project: Project): Unit = with(project) {
        val buildLogic = gradle.includedBuild("build-logic")
        val verifyWrapper = tasks.register<Exec>("verifyWrapper") {
            group = "verification"
            description = "Checks the Gradle wrapper jar and properties against their pinned SHA-256."
            commandLine("sh", layout.projectDirectory.file("gradle/verify-wrapper.sh").asFile.path)
        }
        tasks.register("jvmCheck") {
            group = "verification"
            description = "Checks the wrapper, the catalog and, by name, every JVM module. Needs no Android SDK."
            dependsOn(verifyWrapper)
            dependsOn(buildLogic.task(":test"))
        }
        tasks.register("functionalTest") {
            group = "verification"
            description = "Proves the module map, the Android guard and library compatibility on a copy of the build."
            dependsOn(buildLogic.task(":functionalTest"))
        }
    }
}
