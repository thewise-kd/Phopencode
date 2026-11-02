plugins {
    `kotlin-dsl`
}

group = "io.github.thewisekd.phopencode.buildlogic"

// Java 17 bytecode keeps this build runnable on any JDK that runs AGP.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    implementation(libs.android.gradle.plugin)
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.kotlin.serialization.gradle.plugin)
    implementation(libs.compose.compiler.gradle.plugin)

    testImplementation(gradleTestKit())
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

gradlePlugin {
    plugins {
        fun conventionPlugin(pluginId: String, className: String) = register(pluginId) {
            id = pluginId
            implementationClass = "io.github.thewisekd.phopencode.buildlogic.$className"
        }
        conventionPlugin("phopencode.settings", "SettingsPlugin")
        conventionPlugin("phopencode.root", "RootPlugin")
        conventionPlugin("phopencode.jvm.library", "JvmLibraryPlugin")
        conventionPlugin("phopencode.feature.logic", "FeatureLogicPlugin")
        conventionPlugin("phopencode.serialization", "SerializationPlugin")
        conventionPlugin("phopencode.android.library", "AndroidLibraryPlugin")
        conventionPlugin("phopencode.android.compose", "AndroidComposePlugin")
        conventionPlugin("phopencode.android.application", "AndroidApplicationPlugin")
        conventionPlugin("phopencode.feature.ui", "FeatureUiPlugin")
    }
}

val repositoryRoot = rootDir.parentFile

val repositoryBuildFiles = fileTree(repositoryRoot) {
    include("settings.gradle.kts", "build.gradle.kts", "gradle.properties")
    include("gradlew", "gradlew.bat", "gradle/**", "docs/PHASES.md")
    include("core/*/build.gradle.kts", "feature/*/logic/build.gradle.kts")
    // The copy runs these as source. src/test stays out: it reaches the tests as their classpath.
    include("build-logic/**")
    exclude("build-logic/src/test/**")
    exclude("**/build/**", "**/.gradle/**", "**/.kotlin/**")
}

// Lets RepositoryInputsTest compare these inputs with what Repository.copyBuildTo copies.
val listRepositoryBuildFiles = tasks.register("listRepositoryBuildFiles") {
    val root = repositoryRoot
    val files = repositoryBuildFiles
    val list = layout.buildDirectory.file("repository-build-files/repository-build-files.txt")
    inputs.files(files).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("repositoryBuildFiles")
    outputs.file(list)
    doLast {
        list.get().asFile.writeText(files.map { it.relativeTo(root).invariantSeparatorsPath }.sorted().joinToString("\n"))
    }
}

sourceSets.test {
    output.dir(mapOf("builtBy" to listRepositoryBuildFiles), layout.buildDirectory.dir("repository-build-files"))
}

tasks.withType<Test>().configureEach {
    maxHeapSize = "512m"
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    systemProperty("phopencode.repositoryRoot", repositoryRoot.path)

    // These tests read the repository's build files, so those are inputs. Up-to-date
    // checks and the build cache then skip the tests until one of them changes, but
    // only within one worktree path: phopencode.repositoryRoot is part of the cache key.
    inputs.files(repositoryBuildFiles)
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .withPropertyName("repositoryBuildFiles")
}

tasks.test {
    useJUnitPlatform {
        excludeTags("functional")
    }
}

// Runs a copy of the real build through TestKit: about 30 minutes cold on the Pi,
// so `jvmCheck` leaves it out. Results are reused until an input changes, but only
// within one worktree path: a new worktree runs it again.
tasks.register<Test>("functionalTest") {
    group = "verification"
    description = "Proves the module map, the Android guard and library compatibility on a copy of the build."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("functional")
    }
    // Outside build/, so a clean or a new worktree does not download every dependency again.
    systemProperty("phopencode.testKitDir", gradle.gradleUserHomeDir.resolve("phopencode-test-kit").path)
}
