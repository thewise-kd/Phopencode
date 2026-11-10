plugins {
    id("phopencode.android.application")
}

// P9.28: AGP names the APKs after the archives name: Phopencode-debug.apk, Phopencode-release.apk.
base {
    archivesName = "Phopencode"
}

android {
    buildTypes {
        release {
            // P12.4: R8 shrinks, optimises and obfuscates the release, and its rules strip every log
            // call. Signing comes from P12.5; the release is never signed with the debug key.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        // P12.4: the release as the emulator tests it. The same R8 setup and rules as release and
        // not debuggable, but signed with the debug key so that the instrumented SSH tests can
        // install it next to their test APK. Never published; libraries use their release variant.
        // Its only extra rules keep what the test APK calls in the app and R8 would remove.
        create("releaseCheck") {
            initWith(getByName("release"))
            proguardFile("proguard-release-check.pro")
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }
    // The connected tests run against debug unless a run asks for the R8 build:
    // ./gradlew :app:connectedReleaseCheckAndroidTest -Pphopencode.testBuildType=releaseCheck
    testBuildType = providers.gradleProperty("phopencode.testBuildType").getOrElse("debug")
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
    // P4.16: the app wires the SSH connector and the connection manager into Koin.
    implementation(project(":core:ssh"))
    implementation(project(":core:remote"))
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
