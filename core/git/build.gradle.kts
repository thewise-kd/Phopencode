plugins {
    id("phopencode.jvm.library")
}

dependencies {
    api(project(":core:ssh"))

    testImplementation(project(":core:testing"))
    testImplementation(libs.mina.sshd.core)
}
