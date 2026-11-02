plugins {
    id("phopencode.jvm.library")
}

dependencies {
    api(project(":core:api"))

    testImplementation(project(":core:testing"))
}
