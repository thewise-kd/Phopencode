# JDK setup

Target version: OpenJDK 21. Verified version: 21.0.13 on Debian 13, arm64.

## Why it is needed

Gradle and the JVM module tests run on the Pi, and both need a JDK. The headless package is enough because nothing on the Pi opens a window.

The Pi has no Android SDK and the APK is not built here. A JDK does not change that: Android modules and `CI` tasks are still verified by CI. See "Local test constraint" in `docs/PHASES.md`.

## Install

```sh
sudo apt install openjdk-21-jdk-headless
```

## Verification

```sh
java -version
javac -version
```

Both must report version 21. `javac` confirms that the full JDK is installed and not only a runtime.

## Without a JDK

No Kotlin code can be built or tested on the Pi, so all Kotlin tests are left to CI. They run only at a checkpoint, when the user pushes.
