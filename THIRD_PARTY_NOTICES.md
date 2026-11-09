# Third-party notices

Phopencode is released under the MIT license (see [`LICENSE`](LICENSE)). The Android app it
builds bundles the third-party software listed here. Each part keeps its own license; the
full texts ship inside the app, where **Settings → Open source licenses** shows them, and are
in this repository under
[`feature/settings/ui/src/main/assets/licenses/`](feature/settings/ui/src/main/assets/licenses/).

The libraries are the runtime classpath of the `:app` module's release build, with every
transitive dependency. Platforms (BOMs) and Kotlin Multiplatform modules that only point to
their `-android` or `-jvm` variant carry no code and are not listed. Each library's license
is the one its published POM declares. The checked-in list
[`release-runtime-classpath.txt`](feature/settings/ui/src/test/resources/licenses/release-runtime-classpath.txt)
holds the same set, and the settings module's tests keep this file, that list and the
licenses screen in step.

## GNU Lesser General Public License notice

The code view uses **sora-editor** 0.24.7
(`io.github.rosemoe:editor` and `io.github.rosemoe:language-textmate`) by Rosemoe,
unmodified, under the GNU Lesser General Public License, version 2.1.

- Source code of the library: https://github.com/Rosemoe/sora-editor
- The library is used as published on Maven Central, unmodified. You may replace it with a
  modified version of your own: the complete source code of this app, with its build files,
  is available at https://github.com/thewise-kd/Phopencode, so you can rebuild the app
  against the library version you choose (the version is set in
  [`gradle/libs.versions.toml`](gradle/libs.versions.toml)).
- The library comes with no warranty. The full license text is
  [`LGPL-2.1.txt`](feature/settings/ui/src/main/assets/licenses/LGPL-2.1.txt).

## Eclipse Public License notice

**Eclipse JDT Annotations** (`org.eclipse.jdt:org.eclipse.jdt.annotation`
2.4.100), a dependency of sora-editor's
`language-textmate`, is used unmodified under the Eclipse Public License 2.0. Its source code
is published inside the same artifact on Maven Central and is available from the Eclipse
Foundation at https://www.eclipse.org. The full license text is
[`EPL-2.0.txt`](feature/settings/ui/src/main/assets/licenses/EPL-2.0.txt).

## Libraries

| Library | Version | License |
|---|---|---|
| `androidx.activity:activity` | 1.13.0 | Apache-2.0 |
| `androidx.activity:activity-compose` | 1.13.0 | Apache-2.0 |
| `androidx.activity:activity-ktx` | 1.13.0 | Apache-2.0 |
| `androidx.annotation:annotation-experimental` | 1.4.1 | Apache-2.0 |
| `androidx.annotation:annotation-jvm` | 1.11.0 | Apache-2.0 |
| `androidx.appcompat:appcompat` | 1.8.0 | Apache-2.0 |
| `androidx.appcompat:appcompat-resources` | 1.8.0 | Apache-2.0 |
| `androidx.arch.core:core-common` | 2.2.0 | Apache-2.0 |
| `androidx.arch.core:core-runtime` | 2.2.0 | Apache-2.0 |
| `androidx.autofill:autofill` | 1.0.0 | Apache-2.0 |
| `androidx.collection:collection-jvm` | 1.6.0 | Apache-2.0 |
| `androidx.collection:collection-ktx` | 1.6.0 | Apache-2.0 |
| `androidx.compose.animation:animation-android` | 1.12.1 | Apache-2.0 |
| `androidx.compose.animation:animation-core-android` | 1.12.1 | Apache-2.0 |
| `androidx.compose.foundation:foundation-android` | 1.12.1 | Apache-2.0 |
| `androidx.compose.foundation:foundation-layout-android` | 1.12.1 | Apache-2.0 |
| `androidx.compose.material:material-icons-core-android` | 1.7.8 | Apache-2.0 |
| `androidx.compose.material:material-icons-extended-android` | 1.7.8 | Apache-2.0 |
| `androidx.compose.material:material-ripple-android` | 1.12.1 | Apache-2.0 |
| `androidx.compose.material3:material3-android` | 1.4.0 | Apache-2.0 |
| `androidx.compose.runtime:runtime-android` | 1.12.1 | Apache-2.0 |
| `androidx.compose.runtime:runtime-annotation-android` | 1.12.1 | Apache-2.0 |
| `androidx.compose.runtime:runtime-retain-android` | 1.12.1 | Apache-2.0 |
| `androidx.compose.runtime:runtime-saveable-android` | 1.12.1 | Apache-2.0 |
| `androidx.compose.ui:ui-android` | 1.12.1 | Apache-2.0 |
| `androidx.compose.ui:ui-geometry-android` | 1.12.1 | Apache-2.0 |
| `androidx.compose.ui:ui-graphics-android` | 1.12.1 | Apache-2.0 |
| `androidx.compose.ui:ui-text-android` | 1.12.1 | Apache-2.0 |
| `androidx.compose.ui:ui-tooling-preview-android` | 1.12.1 | Apache-2.0 |
| `androidx.compose.ui:ui-unit-android` | 1.12.1 | Apache-2.0 |
| `androidx.compose.ui:ui-util-android` | 1.12.1 | Apache-2.0 |
| `androidx.concurrent:concurrent-futures` | 1.1.0 | Apache-2.0 |
| `androidx.core:core` | 1.19.1 | Apache-2.0 |
| `androidx.core:core-ktx` | 1.19.1 | Apache-2.0 |
| `androidx.core:core-splashscreen` | 1.2.0 | Apache-2.0 |
| `androidx.core:core-viewtree` | 1.0.0 | Apache-2.0 |
| `androidx.cursoradapter:cursoradapter` | 1.0.0 | Apache-2.0 |
| `androidx.customview:customview` | 1.0.0 | Apache-2.0 |
| `androidx.customview:customview-poolingcontainer` | 1.0.0 | Apache-2.0 |
| `androidx.datastore:datastore-android` | 1.3.0 | Apache-2.0 |
| `androidx.datastore:datastore-core-android` | 1.3.0 | Apache-2.0 |
| `androidx.datastore:datastore-core-okio-jvm` | 1.3.0 | Apache-2.0 |
| `androidx.datastore:datastore-preferences-android` | 1.3.0 | Apache-2.0 |
| `androidx.datastore:datastore-preferences-core-android` | 1.3.0 | Apache-2.0 |
| `androidx.datastore:datastore-preferences-external-protobuf` | 1.3.0 | BSD-3-Clause |
| `androidx.datastore:datastore-preferences-proto` | 1.3.0 | Apache-2.0 |
| `androidx.drawerlayout:drawerlayout` | 1.0.0 | Apache-2.0 |
| `androidx.emoji2:emoji2` | 1.4.0 | Apache-2.0 |
| `androidx.emoji2:emoji2-views-helper` | 1.4.0 | Apache-2.0 |
| `androidx.exifinterface:exifinterface` | 1.4.2 | Apache-2.0 |
| `androidx.fragment:fragment` | 1.8.9 | Apache-2.0 |
| `androidx.fragment:fragment-ktx` | 1.8.9 | Apache-2.0 |
| `androidx.graphics:graphics-path` | 1.0.1 | Apache-2.0 |
| `androidx.interpolator:interpolator` | 1.0.0 | Apache-2.0 |
| `androidx.lifecycle:lifecycle-common-java8` | 2.11.0 | Apache-2.0 |
| `androidx.lifecycle:lifecycle-common-jvm` | 2.11.0 | Apache-2.0 |
| `androidx.lifecycle:lifecycle-livedata` | 2.11.0 | Apache-2.0 |
| `androidx.lifecycle:lifecycle-livedata-core` | 2.11.0 | Apache-2.0 |
| `androidx.lifecycle:lifecycle-livedata-core-ktx` | 2.11.0 | Apache-2.0 |
| `androidx.lifecycle:lifecycle-process` | 2.11.0 | Apache-2.0 |
| `androidx.lifecycle:lifecycle-runtime-android` | 2.11.0 | Apache-2.0 |
| `androidx.lifecycle:lifecycle-runtime-compose-android` | 2.11.0 | Apache-2.0 |
| `androidx.lifecycle:lifecycle-runtime-ktx-android` | 2.11.0 | Apache-2.0 |
| `androidx.lifecycle:lifecycle-viewmodel` | 2.11.0 | Apache-2.0 |
| `androidx.lifecycle:lifecycle-viewmodel-android` | 2.11.0 | Apache-2.0 |
| `androidx.lifecycle:lifecycle-viewmodel-compose-android` | 2.11.0 | Apache-2.0 |
| `androidx.lifecycle:lifecycle-viewmodel-ktx` | 2.11.0 | Apache-2.0 |
| `androidx.lifecycle:lifecycle-viewmodel-savedstate-android` | 2.11.0 | Apache-2.0 |
| `androidx.loader:loader` | 1.0.0 | Apache-2.0 |
| `androidx.navigation:navigation-common-android` | 2.10.2 | Apache-2.0 |
| `androidx.navigation:navigation-compose-android` | 2.10.2 | Apache-2.0 |
| `androidx.navigation:navigation-runtime-android` | 2.10.2 | Apache-2.0 |
| `androidx.navigationevent:navigationevent-android` | 1.1.2 | Apache-2.0 |
| `androidx.navigationevent:navigationevent-compose-android` | 1.1.2 | Apache-2.0 |
| `androidx.profileinstaller:profileinstaller` | 1.4.1 | Apache-2.0 |
| `androidx.resourceinspection:resourceinspection-annotation` | 1.0.1 | Apache-2.0 |
| `androidx.savedstate:savedstate-android` | 1.5.0 | Apache-2.0 |
| `androidx.savedstate:savedstate-compose-android` | 1.5.0 | Apache-2.0 |
| `androidx.savedstate:savedstate-ktx` | 1.5.0 | Apache-2.0 |
| `androidx.startup:startup-runtime` | 1.2.0 | Apache-2.0 |
| `androidx.tracing:tracing-android` | 2.0.1 | Apache-2.0 |
| `androidx.vectordrawable:vectordrawable` | 1.1.0 | Apache-2.0 |
| `androidx.vectordrawable:vectordrawable-animated` | 1.1.0 | Apache-2.0 |
| `androidx.versionedparcelable:versionedparcelable` | 1.1.1 | Apache-2.0 |
| `androidx.viewpager:viewpager` | 1.0.0 | Apache-2.0 |
| `androidx.window:window` | 1.5.0 | Apache-2.0 |
| `androidx.window:window-core-android` | 1.5.0 | Apache-2.0 |
| `co.touchlab:stately-concurrency-jvm` | 2.1.0 | Apache-2.0 |
| `co.touchlab:stately-concurrent-collections-jvm` | 2.1.0 | Apache-2.0 |
| `co.touchlab:stately-strict-jvm` | 2.1.0 | Apache-2.0 |
| `com.google.accompanist:accompanist-drawablepainter` | 0.37.3 | Apache-2.0 |
| `com.google.code.findbugs:jsr305` | 3.0.2 | Apache-2.0 |
| `com.google.code.gson:gson` | 2.14.0 | Apache-2.0 |
| `com.google.crypto.tink:tink-android` | 1.24.0 | Apache-2.0 |
| `com.google.errorprone:error_prone_annotations` | 2.48.0 | Apache-2.0 |
| `com.google.guava:listenablefuture` | 1.0 | Apache-2.0 |
| `com.hierynomus:asn-one` | 0.6.0 | Apache-2.0 |
| `com.hierynomus:sshj` | 0.41.2 | Apache-2.0 |
| `com.mikepenz:multiplatform-markdown-renderer-android` | 0.46.0 | Apache-2.0 |
| `com.mikepenz:multiplatform-markdown-renderer-code-android` | 0.46.0 | Apache-2.0 |
| `com.mikepenz:multiplatform-markdown-renderer-m3-android` | 0.46.0 | Apache-2.0 |
| `com.squareup.okhttp3:okhttp-android` | 5.5.1 | Apache-2.0 |
| `com.squareup.okhttp3:okhttp-sse` | 5.5.1 | Apache-2.0 |
| `com.squareup.okio:okio-jvm` | 3.18.1 | Apache-2.0 |
| `dev.snipme:highlights-jvm` | 1.1.0 | Apache-2.0 |
| `io.coil-kt.coil3:coil-android` | 3.6.3 | Apache-2.0 |
| `io.coil-kt.coil3:coil-compose-android` | 3.6.3 | Apache-2.0 |
| `io.coil-kt.coil3:coil-compose-core-android` | 3.6.3 | Apache-2.0 |
| `io.coil-kt.coil3:coil-core-android` | 3.6.3 | Apache-2.0 |
| `io.github.rosemoe:editor` | 0.24.7 | LGPL-2.1 |
| `io.github.rosemoe:language-textmate` | 0.24.7 | LGPL-2.1 |
| `io.insert-koin:koin-android` | 4.2.2 | Apache-2.0 |
| `io.insert-koin:koin-androidx-compose` | 4.2.2 | Apache-2.0 |
| `io.insert-koin:koin-compose-android` | 4.2.2 | Apache-2.0 |
| `io.insert-koin:koin-compose-viewmodel-android` | 4.2.2 | Apache-2.0 |
| `io.insert-koin:koin-core-jvm` | 4.2.2 | Apache-2.0 |
| `io.insert-koin:koin-core-viewmodel-android` | 4.2.2 | Apache-2.0 |
| `org.bouncycastle:bcpkix-jdk18on` | 1.84 | Bouncy Castle Licence (MIT) |
| `org.bouncycastle:bcprov-jdk18on` | 1.84 | Bouncy Castle Licence (MIT) |
| `org.bouncycastle:bcutil-jdk18on` | 1.84 | Bouncy Castle Licence (MIT) |
| `org.eclipse.jdt:org.eclipse.jdt.annotation` | 2.4.100 | EPL-2.0 |
| `org.jetbrains:annotations` | 23.0.0 | Apache-2.0 |
| `org.jetbrains:markdown-jvm` | 0.7.17 | Apache-2.0 |
| `org.jetbrains.compose.animation:animation-android` | 1.12.1 | Apache-2.0 |
| `org.jetbrains.compose.animation:animation-core-android` | 1.12.1 | Apache-2.0 |
| `org.jetbrains.compose.foundation:foundation-android` | 1.12.1 | Apache-2.0 |
| `org.jetbrains.compose.foundation:foundation-layout-android` | 1.12.1 | Apache-2.0 |
| `org.jetbrains.compose.runtime:runtime-android` | 1.12.1 | Apache-2.0 |
| `org.jetbrains.compose.runtime:runtime-saveable-android` | 1.12.1 | Apache-2.0 |
| `org.jetbrains.compose.ui:ui-android` | 1.12.1 | Apache-2.0 |
| `org.jetbrains.compose.ui:ui-geometry-android` | 1.12.1 | Apache-2.0 |
| `org.jetbrains.compose.ui:ui-graphics-android` | 1.12.1 | Apache-2.0 |
| `org.jetbrains.compose.ui:ui-text-android` | 1.12.1 | Apache-2.0 |
| `org.jetbrains.compose.ui:ui-unit-android` | 1.12.1 | Apache-2.0 |
| `org.jetbrains.compose.ui:ui-util-android` | 1.12.1 | Apache-2.0 |
| `org.jetbrains.kotlin:kotlin-stdlib` | 2.4.21 | Apache-2.0 |
| `org.jetbrains.kotlinx:atomicfu-jvm` | 0.28.0 | Apache-2.0 |
| `org.jetbrains.kotlinx:kotlinx-collections-immutable-jvm` | 0.5.2 | Apache-2.0 |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | 1.11.0 | Apache-2.0 |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm` | 1.11.0 | Apache-2.0 |
| `org.jetbrains.kotlinx:kotlinx-serialization-core-jvm` | 1.12.0 | Apache-2.0 |
| `org.jetbrains.kotlinx:kotlinx-serialization-json-jvm` | 1.12.0 | Apache-2.0 |
| `org.jruby.jcodings:jcodings` | 1.0.64 | MIT |
| `org.jruby.joni:joni` | 2.2.7 | MIT |
| `org.jspecify:jspecify` | 1.0.0 | Apache-2.0 |
| `org.slf4j:slf4j-api` | 2.0.17 | MIT |
| `org.snakeyaml:snakeyaml-engine` | 3.2 | Apache-2.0 |

## License texts

| License | Used by | Text |
|---|---|---|
| Apache License 2.0 | every library marked Apache-2.0 | [`Apache-2.0.txt`](feature/settings/ui/src/main/assets/licenses/Apache-2.0.txt) |
| GNU Lesser General Public License v2.1 | `io.github.rosemoe:editor`, `io.github.rosemoe:language-textmate` | [`LGPL-2.1.txt`](feature/settings/ui/src/main/assets/licenses/LGPL-2.1.txt) |
| Eclipse Public License 2.0 | `org.eclipse.jdt:org.eclipse.jdt.annotation` | [`EPL-2.0.txt`](feature/settings/ui/src/main/assets/licenses/EPL-2.0.txt) |
| BSD 3-Clause License (repackaged Protocol Buffers) | `androidx.datastore:datastore-preferences-external-protobuf` | [`BSD-3-Clause-protobuf.txt`](feature/settings/ui/src/main/assets/licenses/BSD-3-Clause-protobuf.txt) |
| Bouncy Castle Licence | `org.bouncycastle:bcpkix-jdk18on`, `org.bouncycastle:bcprov-jdk18on`, `org.bouncycastle:bcutil-jdk18on` | [`BouncyCastle.txt`](feature/settings/ui/src/main/assets/licenses/BouncyCastle.txt) |
| MIT License (JCodings) | `org.jruby.jcodings:jcodings` | [`MIT-JCodings.txt`](feature/settings/ui/src/main/assets/licenses/MIT-JCodings.txt) |
| MIT License (Joni) | `org.jruby.joni:joni` | [`MIT-Joni.txt`](feature/settings/ui/src/main/assets/licenses/MIT-Joni.txt) |
| MIT License (SLF4J) | `org.slf4j:slf4j-api` | [`MIT-SLF4J.txt`](feature/settings/ui/src/main/assets/licenses/MIT-SLF4J.txt) |

Where each text comes from:

- Apache License 2.0: the `LICENSE.txt` the AndroidX artifacts carry.
- Bouncy Castle Licence (Copyright (c) 2000-2023 The Legion of the Bouncy Castle Inc.): the
  `org.bouncycastle.LICENSE` class inside `bcprov-jdk18on` 1.84.
- MIT License (SLF4J) (Copyright (c) 2004-2022 QOS.ch Sarl): `META-INF/LICENSE.txt` inside
  `slf4j-api` 2.0.17.
- MIT License (JCodings) (Copyright (c) 2025 JRuby Team): `LICENSE.txt` of
  https://github.com/jruby/jcodings at tag `jcodings-1.0.64`. The artifact itself carries no
  license file and its source headers no copyright line.
- MIT License (Joni) (Copyright (c) 2017 JRuby Team): `LICENSE` of
  https://github.com/jruby/joni at tag `joni-2.2.7`, likewise. Joni is a Java port of
  Oniguruma; neither that license, its README nor its sources carry an Oniguruma copyright.
- BSD 3-Clause License (Copyright 2008 Google Inc.): `LICENSE` of
  https://github.com/protocolbuffers/protobuf at tag `v36.1`. The repackaged classes in
  `datastore-preferences-external-protobuf` 1.3.0 are protobuf-java 4.36.1 (their
  `RuntimeVersion` class says so), which that tag releases. The license file inside the
  artifact is the same text without its copyright line.

No artifact carries an Apache `NOTICE` file.

## Contents without a license of their own

These are part of the artifacts above, and are listed so nothing bundled goes unmentioned:

- `com.squareup.okhttp3:okhttp-android` bundles `PublicSuffixDatabase.list`, a compiled copy
  of the Public Suffix List. The artifact states no separate license for it.
- `io.github.rosemoe:language-textmate` also contains classes in the `org.eclipse.tm4e`
  package (Eclipse TM4E, a TextMate engine). Its POM declares LGPL-2.1 only and the artifact
  carries no separate license file for these classes.

## TextMate grammars and themes

The code view (`core:codeview`) ships TextMate grammars and themes as assets, under
[`core/codeview/src/main/assets/codeview/`](core/codeview/src/main/assets/codeview/). Its
[`NOTICE.md`](core/codeview/src/main/assets/codeview/NOTICE.md) records each file's
upstream commit and the changes made. Every directory keeps its license as `LICENSE`; files
that Visual Studio Code converted to JSON also carry VS Code's MIT license as
`LICENSE.vscode` (Copyright (c) 2015 - present Microsoft Corporation). The app also ships
these license files, unchanged, under
[`feature/settings/ui/src/main/assets/licenses/textmate/`](feature/settings/ui/src/main/assets/licenses/textmate/).

| Directory | Upstream | License |
|---|---|---|
| `grammars/c/` (C) | https://github.com/jeff-hykin/better-c-syntax | MIT (Jeff Hykin) |
| `grammars/css/` (CSS) | https://github.com/microsoft/vscode-css | MIT (Microsoft), with the TextMate css bundle notice |
| `grammars/go/` (Go) | https://github.com/worlpaker/go-syntax | MIT (Furkan Ozalp) |
| `grammars/java/` (Java) | https://github.com/atom/language-java | MIT (GitHub Inc.), with the TextMate java bundle notice |
| `grammars/javascript/` (JavaScript) | https://github.com/microsoft/TypeScript-TmLanguage | MIT (Microsoft) |
| `grammars/json/` (JSON) | https://github.com/microsoft/vscode-JSON.tmLanguage | MIT (simonzack, zertosh, benvie) |
| `grammars/kotlin/` (Kotlin) | https://github.com/fwcd/vscode-kotlin | MIT (George Fraser, fwcd) |
| `grammars/markdown/` (Markdown) | https://github.com/microsoft/vscode-markdown-tm-grammar | MIT (Microsoft) |
| `grammars/python/` (Python) | https://github.com/MagicStack/MagicPython | MIT (MagicStack Inc.) |
| `grammars/rust/` (Rust) | https://github.com/dustypomerleau/rust-syntax | MIT (Dustin Pomerleau) |
| `grammars/shell/` (Shell) | https://github.com/jeff-hykin/better-shell-syntax | MIT (Jeff Hykin) |
| `grammars/sql/` (SQL) | https://github.com/microsoft/vscode-mssql | MIT (Microsoft) |
| `grammars/toml/` (TOML) | https://github.com/tamasfe/taplo | MIT (Ferenc Tamás) |
| `grammars/typescript/` (TypeScript) | https://github.com/microsoft/TypeScript-TmLanguage | MIT (Microsoft) |
| `grammars/xml/` (XML) | https://github.com/atom/language-xml | MIT (GitHub Inc.), with the TextMate xml bundle notice |
| `grammars/yaml/` (YAML) | https://github.com/RedCMD/YAML-Syntax-Highlighter | MIT (RedCMD) |
| `themes/` (Visual Studio Code Dark+ and Light+) | https://github.com/microsoft/vscode | MIT (Microsoft) |
