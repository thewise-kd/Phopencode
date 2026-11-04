plugins {
    id("phopencode.android.library")
}

dependencies {
    implementation(libs.tink.android)
    implementation(libs.androidx.datastore)
    implementation(libs.androidx.datastore.preferences)
    api(project(":core:storage-api"))
    testImplementation(project(":core:testing"))
    // P4.1: the shared SecretStore contract, run against the real store and Keystore.
    androidTestImplementation(project(":core:testing"))
}
