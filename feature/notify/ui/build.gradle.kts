import javax.inject.Inject

plugins {
    id("phopencode.feature.ui")
}

/**
 * Builds `notifyd.pyz` with `scripts/build_helper.py` and puts it, with `helper/install.sh` and
 * `helper/uninstall.sh`, into [outputDirectory] under [resourcePath], from where the app reads them
 * as Java resources (`BundledNotifyHelper` in `setup/RemoteNotifyService.kt`). The archive is built under the repository's
 * root `build/` directory, the only place in the repository the script writes to, and copied from
 * there; nothing generated enters the repository.
 */
abstract class BundleNotifyHelper @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val buildScript: RegularFileProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val installScript: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val uninstallScript: RegularFileProperty

    /** Where `build_helper.py` writes the archive: under the repository's root `build/`. */
    @get:Internal
    abstract val archive: RegularFileProperty

    @get:Input
    abstract val resourcePath: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun bundle() {
        val archiveFile = archive.get().asFile
        archiveFile.parentFile.mkdirs()
        exec.exec {
            commandLine("python3", buildScript.get().asFile.absolutePath, "--output", archiveFile.absolutePath)
        }
        val target = outputDirectory.get().asFile
        target.deleteRecursively()
        val folder = target.resolve(resourcePath.get()).apply { mkdirs() }
        archiveFile.copyTo(folder.resolve("notifyd.pyz"), overwrite = true)
        installScript.get().asFile.copyTo(folder.resolve("install.sh"), overwrite = true)
        uninstallScript.get().asFile.copyTo(folder.resolve("uninstall.sh"), overwrite = true)
    }
}

val bundleNotifyHelper = tasks.register<BundleNotifyHelper>("bundleNotifyHelper") {
    val root = rootProject.layout.projectDirectory
    buildScript.set(root.file("scripts/build_helper.py"))
    sources.set(root.dir("helper/notifyd"))
    installScript.set(root.file("helper/install.sh"))
    uninstallScript.set(root.file("helper/uninstall.sh"))
    archive.set(rootProject.layout.buildDirectory.file("notify-helper/notifyd.pyz"))
    resourcePath.set("io/github/thewisekd/phopencode/feature/notify/ui/helper")
    outputDirectory.set(layout.buildDirectory.dir("generated/notifyHelper"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.resources?.addGeneratedSourceDirectory(bundleNotifyHelper, BundleNotifyHelper::outputDirectory)
    }
}

dependencies {
    testImplementation(project(":core:testing"))
}
