package io.github.thewisekd.phopencode.buildlogic

import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.artifacts.dsl.DependencyHandler
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

/** The bytecode and API level of all Kotlin here; Android modules consume the JVM modules. */
internal const val JAVA_RELEASE = 17
internal val JAVA_VERSION = JavaVersion.VERSION_17
internal val KOTLIN_JVM_TARGET = JvmTarget.JVM_17

internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun VersionCatalog.library(alias: String) = findLibrary(alias).get()

internal fun VersionCatalog.sdk(alias: String): Int = findVersion(alias).get().requiredVersion.toInt()

internal fun DependencyHandler.addLibraries(configuration: String, catalog: VersionCatalog, vararg aliases: String) {
    aliases.forEach { add(configuration, catalog.library(it)) }
}

/** A pure Kotlin module: the only kind `./gradlew jvmCheck` builds. */
class JvmLibraryPlugin : Plugin<Project> {
    override fun apply(project: Project): Unit = with(project) {
        pluginManager.apply("java-library")
        pluginManager.apply("org.jetbrains.kotlin.jvm")

        extensions.configure<JavaPluginExtension> {
            sourceCompatibility = JAVA_VERSION
            targetCompatibility = JAVA_VERSION
        }
        tasks.withType<JavaCompile>().configureEach {
            options.release.set(JAVA_RELEASE)
        }
        extensions.configure<KotlinJvmProjectExtension> {
            compilerOptions {
                jvmTarget.set(KOTLIN_JVM_TARGET)
                // Without this, an API newer than Java 17 compiles here and crashes on a phone.
                freeCompilerArgs.add("-Xjdk-release=$JAVA_RELEASE")
            }
        }

        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            maxHeapSize = "512m"
            // Tests create Turkish file names; a POSIX locale cannot represent them.
            environment("LC_ALL", "C.UTF-8")
            testLogging {
                events("failed")
                exceptionFormat = TestExceptionFormat.FULL
            }
        }

        dependencies.add("testImplementation", dependencies.platform(libs.library("junit-bom")))
        dependencies.addLibraries(
            "testImplementation",
            libs,
            "junit-jupiter",
            "kotlin-test",
            "kotlinx-coroutines-test",
            "turbine",
        )
        dependencies.addLibraries("testRuntimeOnly", libs, "junit-platform-launcher", "slf4j-nop")

        tasks.register("jvmCheck") {
            group = "verification"
            description = "Runs all checks of this JVM module."
            dependsOn("check")
        }
    }
}

/** Adds kotlinx.serialization to a JVM or Android module. */
class SerializationPlugin : Plugin<Project> {
    override fun apply(project: Project): Unit = with(project) {
        pluginManager.apply("org.jetbrains.kotlin.plugin.serialization")
        pluginManager.withPlugin("java-library") {
            dependencies.add("api", libs.library("kotlinx-serialization-json"))
        }
        pluginManager.withPlugin("com.android.library") {
            dependencies.add("api", libs.library("kotlinx-serialization-json"))
        }
        // An application has no `api` configuration and no consumers.
        pluginManager.withPlugin("com.android.application") {
            dependencies.add("implementation", libs.library("kotlinx-serialization-json"))
        }
    }
}

/** The JVM half of a feature: state and presenters over the core modules. */
class FeatureLogicPlugin : Plugin<Project> {
    override fun apply(project: Project): Unit = with(project) {
        pluginManager.apply("phopencode.jvm.library")
        listOf(":core:model", ":core:api", ":core:ssh", ":core:remote", ":core:session", ":core:storage-api", ":core:navigation")
            .forEach { dependencies.add("api", dependencies.project(mapOf("path" to it))) }
        dependencies.add("api", libs.library("kotlinx-coroutines-core"))
        dependencies.add("testImplementation", dependencies.project(mapOf("path" to ":core:testing")))
    }
}
