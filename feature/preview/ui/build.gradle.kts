plugins {
    id("phopencode.feature.ui")
}

dependencies {
    // WebViewCompat.addDocumentStartJavaScript, for the preview's WebRTC block (P13.22).
    implementation(libs.androidx.webkit)
}
