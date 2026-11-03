package io.github.thewisekd.phopencode.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.CommonExtension
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType

private const val BASE_PACKAGE = "io.github.thewisekd.phopencode"
private const val TEST_RUNNER = "androidx.test.runner.AndroidJUnitRunner"

/**
 * Robolectric runs in the unit test JVM. On API 36 its FileDescriptorInterceptor.setInt
 * (Robolectric 4.17, called from ApplicationSharedMemory.create) reaches
 * jdk.internal.access.SharedSecrets, which java.base does not export. Its getInt and
 * release use reflection on java.io.FileDescriptor fields; AGP already adds
 * --add-opens=java.base/java.io=ALL-UNNAMED to unit test tasks, so that is not repeated.
 */
private val ROBOLECTRIC_JVM_ARGS = listOf("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")

private fun Project.configureAndroid(android: CommonExtension) = with(android) {
    compileSdk = libs.sdk("android-compileSdk")
    defaultConfig.minSdk = libs.sdk("android-minSdk")
    defaultConfig.testInstrumentationRunner = TEST_RUNNER
    compileOptions.sourceCompatibility = JAVA_VERSION
    compileOptions.targetCompatibility = JAVA_VERSION
    testOptions.unitTests.isIncludeAndroidResources = true
    tasks.withType<Test>().configureEach { jvmArgs(ROBOLECTRIC_JVM_ARGS) }

    dependencies.addLibraries("implementation", libs, "androidx-core-ktx", "kotlinx-coroutines-android")
    dependencies.add("testImplementation", libs.library("kotlin-test"))
    dependencies.addLibraries(
        "testImplementation",
        libs,
        "junit4",
        "robolectric",
        "androidx-test-core",
        "kotlinx-coroutines-test",
        "turbine",
    )
    dependencies.addLibraries(
        "androidTestImplementation",
        libs,
        "androidx-test-runner",
        "androidx-test-ext-junit",
        "androidx-test-espresso-core",
    )
}

/** An Android module's namespace is its Gradle path: `:core:ui` is `<base>.core.ui`. */
private val Project.derivedNamespace: String
    get() = BASE_PACKAGE + path.replace(':', '.').replace('-', '_')

/** A thin Android module. Kotlin is built into AGP 9, so no Kotlin plugin is applied. */
class AndroidLibraryPlugin : Plugin<Project> {
    override fun apply(project: Project): Unit = with(project) {
        pluginManager.apply("com.android.library")
        extensions.configure<LibraryExtension> {
            namespace = derivedNamespace
            configureAndroid(this)
        }
    }
}

/** Compose for an Android library or the application. Apply after one of those plugins. */
class AndroidComposePlugin : Plugin<Project> {
    override fun apply(project: Project): Unit = with(project) {
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
        extensions.getByType<CommonExtension>().buildFeatures.compose = true
        val bom = dependencies.platform(libs.library("compose-bom"))
        dependencies.add("implementation", bom)
        dependencies.add("androidTestImplementation", bom)
        dependencies.addLibraries(
            "implementation",
            libs,
            "compose-ui",
            "compose-foundation",
            "compose-material3",
            "compose-ui-tooling-preview",
            "androidx-lifecycle-runtime-compose",
        )
        dependencies.addLibraries("debugImplementation", libs, "compose-ui-tooling", "compose-ui-test-manifest")
        dependencies.add("testImplementation", bom)
        dependencies.addLibraries("testImplementation", libs, "compose-ui-test-junit4")
        dependencies.addLibraries("androidTestImplementation", libs, "compose-ui-test-junit4")
    }
}

/** The screens of a feature, on top of its `logic` module and the shared UI. */
class FeatureUiPlugin : Plugin<Project> {
    override fun apply(project: Project): Unit = with(project) {
        pluginManager.apply("phopencode.android.library")
        pluginManager.apply("phopencode.android.compose")
        val logic = path.removeSuffix(":ui") + ":logic"
        listOf(logic, ":core:ui", ":core:navigation")
            .forEach { dependencies.add("implementation", dependencies.project(mapOf("path" to it))) }
        dependencies.addLibraries(
            "implementation",
            libs,
            "androidx-navigation-compose",
            "androidx-lifecycle-viewmodel-compose",
            "koin-android",
            "koin-androidx-compose",
        )
    }
}

/** The entry point. Nothing else may set the application ID; it is fixed after the first release. */
class AndroidApplicationPlugin : Plugin<Project> {
    override fun apply(project: Project): Unit = with(project) {
        pluginManager.apply("com.android.application")
        extensions.configure<ApplicationExtension> {
            namespace = BASE_PACKAGE
            defaultConfig.applicationId = BASE_PACKAGE
            defaultConfig.targetSdk = libs.sdk("android-targetSdk")
            configureAndroid(this)
        }
        pluginManager.apply("phopencode.android.compose")
        dependencies.addLibraries(
            "implementation",
            libs,
            "androidx-activity-compose",
            "androidx-core-splashscreen",
            "androidx-navigation-compose",
            // P3.10 replaces Android's stripped BouncyCastle provider from the app.
            "bouncycastle-prov",
            "koin-android",
            "koin-androidx-compose",
        )
        dependencies.add("testImplementation", libs.library("koin-test"))
        dependencies.add("testImplementation", libs.library("koin-test-junit4"))
    }
}
