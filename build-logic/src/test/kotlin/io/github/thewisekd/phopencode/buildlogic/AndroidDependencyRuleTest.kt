package io.github.thewisekd.phopencode.buildlogic

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AndroidDependencyRuleTest {
    @ParameterizedTest
    @CsvSource(
        "androidx.core, core-ktx",
        "androidx.compose.ui, ui",
        "androidx.datastore, datastore",
        "android.arch.core, common",
        "com.android.tools, desugar_jdk_libs",
        "com.google.android.material, material",
        "com.google.android, android",
        "com.google.crypto.tink, tink-android",
        "org.jetbrains.kotlinx, kotlinx-coroutines-android",
        "io.insert-koin, koin-android",
        "io.insert-koin, koin-androidx-compose",
        "com.squareup.okhttp3, okhttp-android",
        "org.robolectric, robolectric",
        "org.jetbrains.compose.runtime, runtime",
        "io.github.rosemoe, editor",
        "io.coil-kt.coil3, coil-compose",
        "com.mikepenz, multiplatform-markdown-renderer-m3",
    )
    fun `Android and UI libraries are rejected`(group: String, name: String) {
        assertNotNull(AndroidDependencyRule.violation(group, name))
    }

    @ParameterizedTest
    @CsvSource(
        "org.jetbrains.kotlin, kotlin-stdlib",
        "org.jetbrains.kotlinx, kotlinx-coroutines-core",
        "org.jetbrains.kotlinx, kotlinx-serialization-json",
        "com.squareup.okhttp3, okhttp",
        "com.squareup.okhttp3, okhttp-sse",
        "com.squareup.okhttp3, mockwebserver3",
        "com.hierynomus, sshj",
        "org.apache.sshd, sshd-core",
        "org.bouncycastle, bcprov-jdk18on",
        "app.cash.turbine, turbine",
        "io.insert-koin, koin-core",
        "org.junit.jupiter, junit-jupiter",
        "org.slf4j, slf4j-api",
    )
    fun `JVM libraries pass`(group: String, name: String) {
        assertNull(AndroidDependencyRule.violation(group, name))
    }
}
