package io.github.thewisekd.phopencode.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.NamedDomainObjectProvider
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.DependencySubstitution
import org.gradle.api.artifacts.component.ModuleComponentSelector
import org.gradle.api.artifacts.component.ProjectComponentSelector
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.attributes.Attribute
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register

private const val MESSAGE = "must stay free of Android"
private const val CONVENTION_PLUGIN = "phopencode.jvm.library"
private val ANDROID_PLUGINS = listOf(
    "com.android.application",
    "com.android.library",
    "com.android.test",
    "com.android.dynamic-feature",
    "com.android.kotlin.multiplatform.library",
    "org.jetbrains.kotlin.android",
    "org.jetbrains.kotlin.plugin.compose",
    "org.jetbrains.compose",
)
private val KOTLIN_PLATFORM = Attribute.of("org.jetbrains.kotlin.platform.type", String::class.java)

abstract class JvmModuleGuardExtension {
    abstract val androidModules: SetProperty<String>
}

/**
 * Enforces "a JVM module cannot take an Android dependency". [SettingsPlugin] applies
 * it to each JVM module, so a module cannot opt out by editing its build file.
 *
 * There are two checks because they see different things:
 * - A substitution rule on each classpath rejects a forbidden coordinate or module on
 *   any edge, direct or transitive, while the graph is resolved. It has to run there:
 *   an Android library often has no JVM variant, and Gradle then fails while working
 *   out the task graph, before any task could report the reason.
 * - [VerifyNoAndroidDependencies] looks at the result: AAR files, whatever their group,
 *   and variants built for Android. Every compile depends on it, and it resolves all
 *   classpaths strictly, because a compile alone never resolves a runtime classpath.
 *   A rejected edge therefore fails the compile with the rule's message. Any other
 *   resolution failure fails it too, with Gradle's own message: a graph the guard
 *   cannot see is not verified, but the cause is not Android.
 */
class JvmModuleGuardPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val guard = project.extensions.create<JvmModuleGuardExtension>("phopencodeJvmGuard")

        ANDROID_PLUGINS.forEach { plugin ->
            project.pluginManager.withPlugin(plugin) {
                throw GradleException("${project.path} $MESSAGE: it applies the plugin $plugin.")
            }
        }

        project.afterEvaluate {
            if (!pluginManager.hasPlugin(CONVENTION_PLUGIN)) {
                throw GradleException("$path must apply $CONVENTION_PLUGIN, directly or through a plugin that does.")
            }
        }

        project.pluginManager.withPlugin("java") {
            val verify = project.tasks.register<VerifyNoAndroidDependencies>("verifyNoAndroidDependencies") {
                modulePath.set(project.path)
                stamp.set(project.layout.buildDirectory.file("phopencode/verify-no-android-dependencies.txt"))
            }
            project.extensions.getByType<SourceSetContainer>().configureEach {
                listOf(compileClasspathConfigurationName, runtimeClasspathConfigurationName).forEach { name ->
                    val classpath = project.configurations.named(name)
                    classpath.configure { rejectForbiddenEdges(project.path, guard) }
                    verify.watch(classpath)
                }
            }
            project.tasks.named { it == "check" || it.startsWith("compile") }.configureEach {
                dependsOn(verify)
            }
        }
    }

    private fun Configuration.rejectForbiddenEdges(modulePath: String, guard: JvmModuleGuardExtension) {
        val configuration = name
        resolutionStrategy.dependencySubstitution.all {
            reasonToReject(guard.androidModules.get())?.let { reason ->
                throw GradleException("$modulePath $MESSAGE, in $configuration: $reason")
            }
        }
    }

    private fun DependencySubstitution.reasonToReject(androidModules: Set<String>): String? =
        when (val selector = requested) {
            is ModuleComponentSelector ->
                AndroidDependencyRule.violation(selector.group, selector.module)
                    ?.let { "${selector.group}:${selector.module} is rejected, $it." }
            is ProjectComponentSelector ->
                "project ${selector.projectPath} is an Android module.".takeIf { selector.projectPath in androidModules }
            else -> null
        }

    private fun TaskProvider<VerifyNoAndroidDependencies>.watch(
        classpath: NamedDomainObjectProvider<Configuration>,
    ) = configure {
        val configuration = classpath.name
        androidVariants.addAll(
            classpath.flatMap { it.incoming.resolutionResult.rootComponent }
                .map { root -> androidVariantsIn(root).map { "$it in $configuration: its variant is built for Android" } },
        )
        artifactFiles.addAll(
            classpath.flatMap { it.incoming.artifacts.resolvedArtifacts }
                .map { artifacts -> artifacts.map { it.file.name } },
        )
    }

    private fun androidVariantsIn(root: ResolvedComponentResult): Set<String> {
        val found = sortedSetOf<String>()
        val visited = mutableSetOf<ResolvedComponentResult>()

        fun visit(component: ResolvedComponentResult) {
            if (!visited.add(component)) return
            component.dependencies.filterIsInstance<ResolvedDependencyResult>().forEach { dependency ->
                val variant = dependency.resolvedVariant
                if (variant.attributes.getAttribute(KOTLIN_PLATFORM) == "androidJvm") {
                    found += variant.owner.displayName
                }
                visit(dependency.selected)
            }
        }
        visit(root)
        return found
    }
}

@CacheableTask
abstract class VerifyNoAndroidDependencies : DefaultTask() {
    @get:Input
    abstract val modulePath: Property<String>

    @get:Input
    abstract val androidVariants: ListProperty<String>

    @get:Input
    abstract val artifactFiles: ListProperty<String>

    @get:OutputFile
    abstract val stamp: RegularFileProperty

    @TaskAction
    fun verify() {
        val artifacts = artifactFiles.get().filter { it.endsWith(".aar") || it == "android.jar" }
            .map { "$it is an Android artifact" }
        val problems = (androidVariants.get() + artifacts).distinct()
        if (problems.isNotEmpty()) {
            throw GradleException(
                "${modulePath.get()} $MESSAGE, because logic lives in pure Kotlin/JVM modules:\n" +
                    problems.joinToString("\n") { "  - $it" },
            )
        }
        stamp.get().asFile.writeText("verified\n")
    }
}
