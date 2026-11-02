plugins {
    id("phopencode.android.library")
    id("phopencode.android.compose")
}

dependencies {
    implementation(libs.markdown.renderer.m3)
    implementation(libs.markdown.renderer.code)
}
