plugins {
    id("phopencode.jvm.library")
}

dependencies {
    api(project(":core:model"))
    api(project(":core:api"))
    api(project(":core:ssh"))
    api(project(":core:storage-api"))
    api(libs.kotlinx.coroutines.test)
}
