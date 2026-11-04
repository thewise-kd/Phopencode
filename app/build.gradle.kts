plugins {
    id("phopencode.android.application")
}

android {
    buildTypes {
        release {
            // R8 is switched on in P12.4; the SSH stack's rules are already in place.
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    packaging {
        resources {
            // bcprov, bcutil and bcpkix each carry this OSGi manifest; the APK needs none of them.
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }
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

    // P3.10: the instrumented tests drive the real SSH stack.
    androidTestImplementation(project(":core:ssh"))
}
