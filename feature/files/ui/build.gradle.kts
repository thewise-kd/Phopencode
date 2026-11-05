plugins {
    id("phopencode.feature.ui")
}

dependencies {
    implementation(libs.coil.compose)
    implementation(project(":core:codeview"))
}
