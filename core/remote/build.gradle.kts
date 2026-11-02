plugins {
    id("phopencode.jvm.library")
    id("phopencode.serialization")
}

dependencies {
    api(project(":core:api"))
    api(project(":core:ssh"))
    api(project(":core:storage-api"))

    implementation(project(":core:api-http"))

    testImplementation(project(":core:testing"))
    testImplementation(libs.mina.sshd.core)
    testImplementation(libs.mina.sshd.sftp)
    testImplementation(libs.okhttp)
    testImplementation(libs.okhttp.mockwebserver3)
}
