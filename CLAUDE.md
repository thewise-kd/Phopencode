# phopencode

Public Android app for managing, from a phone, opencode running on a server with `opencode serve`.

Stack: Kotlin + Jetpack Compose, Android only.

## Build

Needs JDK 21 (`docs/dev-setup/jdk.md`). No Android SDK on the Pi: Android modules are included in Gradle only when `ANDROID_HOME`, `ANDROID_SDK_ROOT` or `sdk.dir` in `local.properties` names an existing directory.

- `./gradlew jvmCheck`: tests all JVM modules and checks the wrapper and the version catalog. Needs no SDK.
- `./gradlew :<module>:test`: one module, for example `:core:api-http:test` or `:feature:chat:logic:test`.
- `./gradlew functionalTest`: runs copies of the real build to prove the module map, the Android guard and library compatibility. Slow (minutes); run it after changing `build-logic/`, the catalog or a module build file.
- `gradle/verify-wrapper.sh --online`: compares the wrapper with the hashes Gradle publishes. Run it after a wrapper upgrade, before `./gradlew`.

Modules apply convention plugins from `build-logic/`, never AGP or Kotlin directly: `phopencode.jvm.library`, `phopencode.feature.logic`, `phopencode.serialization`, `phopencode.android.library`, `phopencode.android.compose`, `phopencode.feature.ui`, `phopencode.android.application`. A JVM module that takes an Android dependency, directly or transitively, fails its own compile.

## Tasks

The task list is in `docs/PHASES.md`; IDs have the form `P<phase>.<number>` (e.g. `P2.3`).

## Agents

- `orchestrator`: writes no code; assigns tasks and runs the flow.
- `coder`: writes tests and code, fixes findings, commits.
- `reviewer`: reviews code with GPT, read-only (`.claude/scripts/run-review.sh`).
- `security`: runs the security check with GLM, read-only (`.claude/scripts/run-security.sh`).

`reviewer` and `security` do not write long reports: they briefly report negative findings only, and return a single line `OK` when clean.

## Task flow

1. `coder`: test first, then code.
2. `reviewer`: reviews.
3. If there are findings and a fix is needed, `coder` fixes them, then `reviewer` runs again.
4. `security`: security check at the very end; `coder` fixes any findings.
5. When the task is done, make one commit: per task, not per test. Do not push; the user does the pushing.

## Rules

- Language: everything in the repository is in English: code, commit messages, agent, skill and hook instructions, and documents. The app's user-facing strings ship in Turkish and English, because `docs/PHASES.md` requires TR/EN string parity.
- Secrets: the repository is public. Never commit server addresses, keys, passwords or the ntfy topic.
