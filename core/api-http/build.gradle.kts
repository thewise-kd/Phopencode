plugins {
    id("phopencode.jvm.library")
    id("phopencode.serialization")
}

dependencies {
    api(project(":core:api"))

    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)

    testImplementation(project(":core:testing"))
    testImplementation(libs.okhttp.mockwebserver3)
}
