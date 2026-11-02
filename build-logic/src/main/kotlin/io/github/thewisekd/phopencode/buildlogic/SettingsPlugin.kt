package io.github.thewisekd.phopencode.buildlogic

import org.gradle.api.Action
import org.gradle.api.Plugin
import org.gradle.api.initialization.Settings
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.getByType
import java.util.Properties

/**
 * Holds the module map. Android modules are included only when an SDK is present,
 * and every JVM module gets the Android guard whatever its build file says.
 */
class SettingsPlugin : Plugin<Settings> {
    override fun apply(settings: Settings) {
        val modules = settings.extensions.create<ModulesExtension>(
            "phopencodeModules",
            settings,
            androidSdkPresent(settings),
        )
        settings.gradle.settingsEvaluated {
            val jvm = modules.jvmModules.toSet()
            val android = modules.androidModules.toSet()
            gradle.lifecycle.beforeProject {
                if (path in jvm) {
                    pluginManager.apply(JvmModuleGuardPlugin::class.java)
                    extensions.getByType<JvmModuleGuardExtension>().androidModules.set(android)
                }
            }
        }
    }

    private fun androidSdkPresent(settings: Settings): Boolean {
        val providers = settings.providers
        val fromLocalProperties = providers.fileContents(settings.layout.settingsDirectory.file("local.properties"))
            .asText
            .map { text -> Properties().apply { load(text.reader()) }.getProperty("sdk.dir").orEmpty() }
        val locations = listOf(
            providers.environmentVariable("ANDROID_HOME"),
            providers.environmentVariable("ANDROID_SDK_ROOT"),
            fromLocalProperties,
        )
        return locations.mapNotNull { it.orNull }.any { location ->
            location.isNotBlank() && settings.settingsDir.resolve(location).isDirectory
        }
    }
}

abstract class ModulesExtension @javax.inject.Inject constructor(
    private val settings: Settings,
    private val androidSdkPresent: Boolean,
) {
    internal val jvmModules = mutableListOf<String>()
    internal val androidModules = mutableListOf<String>()

    /** Pure Kotlin modules, included on every machine. */
    fun jvm(vararg paths: String) {
        jvmModules += paths
        settings.include(*paths)
    }

    /** Android modules, included only when an SDK is present. */
    fun android(vararg paths: String) {
        androidModules += paths
        if (androidSdkPresent) {
            settings.include(*paths)
        }
    }
}

/** Gives settings.gradle.kts its block: Kotlin DSL has no accessors for settings extensions. */
fun Settings.phopencodeModules(action: Action<ModulesExtension>) {
    action.execute(extensions.getByType<ModulesExtension>())
}
