plugins {
    id("phopencode.jvm.library")
}

dependencies {
    api(project(":core:model"))
    api(libs.kotlinx.coroutines.core)

    // Behind the interfaces of core/ssh/.../api, so that JSch can replace sshj.
    implementation(libs.sshj)
    implementation(libs.bouncycastle.prov)
    implementation(libs.bouncycastle.pkix)

    testImplementation(libs.mina.sshd.core)
    testImplementation(libs.mina.sshd.sftp)
    testImplementation(libs.okhttp)
    testImplementation(libs.okhttp.sse)
    testImplementation(libs.okhttp.mockwebserver3)
}
