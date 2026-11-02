plugins {
    id("phopencode.jvm.library")
}

dependencies {
    api(project(":core:api"))
    api(project(":core:ssh"))

    testImplementation(project(":core:testing"))
    testImplementation(libs.mina.sshd.core)
    testImplementation(libs.mina.sshd.sftp)
}
