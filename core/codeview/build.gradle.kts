plugins {
    id("phopencode.android.library")
    id("phopencode.android.compose")
}

dependencies {
    implementation(libs.sora.editor)
    implementation(libs.sora.editor.language.textmate)
}
