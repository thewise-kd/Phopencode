// An init script that prints the third-party artifacts the release app bundles, one per line as
// "ART group:artifact:version". It refreshes release-runtime-classpath.txt next to it; see there.
// Needs an Android SDK, as :app does.
allprojects {
    if (path == ":app") {
        tasks.register("listReleaseArtifacts") {
            val classpath = configurations.getByName("releaseRuntimeClasspath")
            val ids = classpath.incoming.artifactView {
                attributes {
                    attribute(Attribute.of("artifactType", String::class.java), "android-classes-jar")
                }
                lenient(true)
            }.artifacts.resolvedArtifacts.map { artifacts -> artifacts.map { it.id.componentIdentifier.displayName } }
            doLast {
                ids.get().filterNot { it.startsWith("project ") }.toSortedSet().forEach { println("ART $it") }
            }
        }
    }
}
