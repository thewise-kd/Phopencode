plugins {
    id("phopencode.jvm.library")
}

dependencies {
    api(project(":core:model"))
    api(libs.kotlinx.coroutines.core)
}
