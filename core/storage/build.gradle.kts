plugins {
    id("phopencode.android.library")
}

dependencies {
    implementation(libs.tink.android)
    implementation(libs.androidx.datastore)
    implementation(libs.androidx.datastore.preferences)
    api(project(":core:storage-api"))
    testImplementation(project(":core:testing"))
}
