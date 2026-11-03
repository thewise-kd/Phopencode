plugins {
    id("phopencode.android.application")
}

dependencies {
    implementation(project(":core:navigation"))
    implementation(project(":core:storage"))
    implementation(project(":core:ui"))
    implementation(project(":core:markdown"))
    implementation(project(":core:codeview"))
    implementation(project(":feature:connect:ui"))
    implementation(project(":feature:sessions:ui"))
    implementation(project(":feature:chat:ui"))
    implementation(project(":feature:catalog:ui"))
    implementation(project(":feature:files:ui"))
    implementation(project(":feature:git:ui"))
    implementation(project(":feature:notify:ui"))
    implementation(project(":feature:settings:ui"))
}
