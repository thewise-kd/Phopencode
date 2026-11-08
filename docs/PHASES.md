# phopencode phases

Tasks are listed by `P<phase>.<number>` ID. API details: `docs/opencode-api-notes.md`.

## Decisions to make first

| No | Decision | Impact | Recommendation |
|---|---|---|---|
| D1 | Target API: opencode V2 | Decided. This machine was upgraded to 2.2.5; the GLM 5.3 security check runs through Claude Code | - |
| D2 | Install a JDK on the Pi? (requires `sudo`) | P0.2. If refused, all Kotlin tests are left to CI | Yes |
| D3 | GitHub repository and `gh auth login` | CI and checkpoints. `gh` is not logged in at the moment | You create the repository and you do the push |
| D4 | `applicationId`: `io.github.thewisekd.phopencode` | Decided. Does not change after the first release | - |
| D5 | ntfy server | Notifications. The user's phone needs the ntfy app | `ntfy.sh`, changeable in settings |
| D6 | Is sora-editor's LGPL-2.1 acceptable? | File viewer. The app stays MIT, a notice is added | Yes |
| D7 | Real LLM call in P1.7 | Spends a small amount of tokens. Not needed if `OPENCODE_SIMULATE` works | Try simulation first |
| D8 | Signing key | P12.5. The user generates the key and adds it to GitHub Secrets personally. If it is lost, updates cannot be signed | Keep a backup outside the repository |

## Tags

| Test tag | Where | Command | Requirements |
|---|---|---|---|
| `PI` | Pi | `./gradlew :<module>:test` | JDK (P0.2) |
| `PY` | Pi | `python3 -m unittest` | No setup |
| `CI` | GitHub Actions | `testDebugUnitTest`, `assembleDebug`, `lint` | Push (user) |
| `EMU` | GitHub Actions emulator | `connectedDebugAndroidTest` | Push (user) |
| `PHONE` | Real phone | By hand | User |
| `LOCAL` | Pi | The command or file check in the acceptance test | No setup |

`[U]`: cannot finish without a user action or approval. `orchestrator` obtains the approval before starting the task.

`…` = the module's `src/main/kotlin/<pkg>/` root. `<pkg>` is set by D4.

## Local test constraint

- Logic lives in pure Kotlin/JVM modules, Compose screens in thin Android modules.
- Android-dependent modules are included in `settings.gradle.kts` only when an SDK is present. Without one, `./gradlew jvmCheck` tests all JVM modules.
- The Pi has an Android SDK at `/home/ace/android-sdk` (platform 37.0, build-tools 36.0.0). Android modules are included by passing `ANDROID_HOME=/home/ace/android-sdk` on the Gradle command; a project hook blocks writing `local.properties`.
- aapt2 is not official for arm64 Linux. It runs through an x86-64 emulation wrapper set in the user's `~/.gradle/gradle.properties` (`android.aapt2FromMavenOverride`), never in the project.
- The app compiles and the debug APKs build on the Pi, slowly (about 20 minutes under load).
- Robolectric runs on the Pi (aarch64) only at API 26; API 36 runs are CI-only. Instrumented (`EMU`) tests run only in CI.
- `CI` tasks cannot be built on the Pi. `coder` writes the test and the code and commits; verification happens at the checkpoint.
- A phase does not count as finished until its checkpoint is green. A fix is added as a task at the end of the phase with the next number.

## Checkpoints

CI runs only when the user pushes. Sequence:

1. `/phase <n>` finishes, the commits are local.
2. The user pushes.
3. The CI result is read with `gh run list` / `gh run view --log-failed` (D3).
4. If red, a fix task is added and pushed again.

`CP<n>` lines say at the end of which phase a push is needed. P2 and P3 are mostly verified on the Pi; they can be merged without CI.

## Module map

This list is created in one go in P0.4. 32 modules in total.

| Path | Type | Contents |
|---|---|---|
| `core/model` | JVM | Domain types |
| `core/api` | JVM | API interfaces, event types, error types |
| `core/api-http` | JVM | OkHttp implementation |
| `core/session` | JVM | Event reducer and resync |
| `core/ssh` | JVM | SSH, exec, SFTP, tunnel, `ShellQuote` |
| `core/remote` | JVM | Server discovery/startup, connection manager, service installation |
| `core/files` | JVM | File service |
| `core/git` | JVM | Git commands and parsers |
| `core/storage-api` | JVM | Storage interfaces |
| `core/navigation` | JVM | Routes |
| `core/testing` | JVM | Fakes and recorded samples |
| `core/storage` | Android | Keystore + Tink + DataStore |
| `core/ui` | Android | Theme and shared components |
| `core/markdown` | Android | Markdown and code block |
| `core/codeview` | Android | sora-editor wrapper |
| `feature/<f>/logic` | JVM | State and presenters |
| `feature/<f>/ui` | Android | Compose screens |
| `app` | Android | Entry point, NavHost, Koin startup |

`<f>`: `connect`, `sessions`, `chat`, `catalog`, `files`, `git`, `notify`, `settings`. In addition, `helper/` is a Python service, outside Gradle.

## Parallel work rules

`/phase` runs a phase's groups in order and the tasks within a group in parallel. Each parallel task is in its own worktree; commits are cherry-picked one at a time.

- A group has at most 6 tasks; at most 4 of them run Gradle. The Pi has 4 cores and 7.6 GB RAM. This limit is an estimate and can be changed once measured.
- Only one `/phase` runs at a time: cherry-pick uses the main checkout.
- A task writes only to the files under "Owned paths". If it needs another file, it stops and reports.
- The contract task comes first, on its own. Later tasks rely on that contract and do not touch it.

### Frequently conflicting files

| File | Owner | Rule |
|---|---|---|
| `settings.gradle.kts` | P0.4 | All modules are written up front. A module needed later becomes a task of its own |
| `gradle/libs.versions.toml` | P0.4 | All planned libraries go in up front. A new dependency is a task of its own and runs alone in its group |
| `gradle.properties`, `build-logic/` | P0.4 | Tasks do not touch these. Module-specific settings go in the module's own `build.gradle.kts` |
| Routes | P1.5 | All routes are defined up front |
| NavHost | P1.10 | `app` calls each feature's `<f>Graph()` function. A new screen is added only to that feature's graph |
| `<F>Graph.kt`, `<F>Module.kt` | The feature's contract task | All screens and presenters are wired up front as stubs. Later tasks fill in the stub file |
| `strings.xml` | No single file | Each screen keeps a `strings_<f>_<screen>.xml` file under `values/` and `values-tr/`. Key prefix `<f>_<screen>_` |
| `AndroidManifest.xml` | Per module | The `app` manifest does not change after P1.10. Permissions and services go in the feature's own manifest |
| R8 rules | P3.10, P12.4 | Only `app/proguard-rules.pro`, in these two tasks |
| `README.md`, `README.tr.md`, `CLAUDE.md` | P0.1, P0.4, P9.29, P12.6 | No other task touches them |

### What a contract task produces

- The `<Screen>State`, `<Screen>Intent`, `<Screen>Presenter` interface, stub implementation.
- For each screen, an empty Composable file and an empty `strings_<f>_<screen>.xml` pair.
- `<F>Graph.kt` and `<F>Module.kt` wiring.
- The presenter constructor is fixed here. A later task cannot change the constructor.
- Each screen stub keeps, on its root element, the `placeholder:<route>` test tag of the placeholder it replaces (`Modifier.testTag("placeholder:$route")`, the format of `placeholderTag(route)` in `app/src/test/…/navigation/AllRoutes.kt`). The real screen keeps it too. `app`'s navigation tests then keep passing without any task touching `app`.

After the contract, the `logic` and `ui` tasks can run at the same time: `ui` only draws `State` and emits `Intent`.

## Technology choices

Versions were verified on 2026-10-31 against Maven Central, Google Maven and GitHub. Their compatibility with each other is tried in P0.4.

| Need | Choice | Version | Note |
|---|---|---|---|
| Build | Gradle / AGP / Kotlin | 9.8.2 / 9.5.0 / 2.4.21 | Version catalog |
| UI | Compose BOM | 2026.09.00 | Material 3 |
| Navigation | navigation-compose | 2.10.2 | Type-safe routes |
| DI | Koin | 4.2.2 | No code generation |
| SSH, SFTP, tunnel | sshj | 0.41.2 | Apache-2.0, 2026-10 release; includes `newDirectConnection`, SFTP, ed25519 |
| Test SSH server | Apache MINA sshd | 2.20.1 | Test only |
| HTTP, SSE | OkHttp, okhttp-sse | 5.5.1 | Test: mockwebserver3 |
| JSON | kotlinx.serialization | 1.12.0 | `ignoreUnknownKeys = true` |
| Concurrency | kotlinx.coroutines, Turbine | 1.11.0, 1.2.1 | |
| Encrypted storage | Tink, DataStore | 1.24.0, 1.3.0 | Key in Android Keystore |
| Markdown | multiplatform-markdown-renderer `-m3`, `-code` | 0.46.0 | Apache-2.0, 2026-10 release; code block highlighting with Highlights |
| File viewing/editing | sora-editor `editor`, `language-textmate` | 0.24.7 | Group `io.github.rosemoe`. LGPL-2.1 (D6) |
| Images | Coil | 3.6.3 | |
| Android tests | Robolectric | 4.17 | |
| CI actions | checkout, setup-java, gradle/actions, setup-android, android-emulator-runner, action-gh-release | v7, v6, v6, v4, v2.38, v3 | Pinned to SHA |

Reason for choosing sshj: the `DirectConnection` and SFTP APIs were verified in the source code. It has not yet been proven to work on Android; P3.10 does that. If it fails, the fallback is the maintained JSch fork (2.28.8). Since the interface is under `core/ssh/…/api/`, only the implementation changes.

Security decisions:

- The tunnel opens no local port. OkHttp is given a `SocketFactory` that works over sshj `DirectConnection`; other apps on the phone cannot reach the port.
- The password is never written on the command line; it is passed via stdin.
- The host key is approved on first connection; if it changes, the connection stops.
- Test keys are generated during tests and never enter the repository.

## Notification service language

Choice: **Python 3, standard library only**, single-file `zipapp` (`notifyd.pyz`).

| Option | Installation | Testing on the Pi | Decision |
|---|---|---|---|
| Python 3 stdlib | A single file uploaded over SFTP; no dependencies, no build | Works today (`PY`) | Chosen |
| Go static binary | No Python needed, but requires a binary per architecture and release distribution | Requires installing Go | Rejected |
| Shell + curl | SSE parsing and reconnecting are fragile | Easy | Rejected |
| Node/Bun | Most servers have no runtime | Not on this machine | Rejected |

Rationale: zero cost, public and readable source code, no build on the server. Most Linux servers ship with `python3`. If it is missing, the app says so and stops the installation. Minimum version 3.9. Since the service carries no Android dependency, all of it is tested on the Pi.

Notification flow: the service sees the event and sends fixed text to `ntfy.sh/<random-topic>`. The user subscribes to the topic in the ntfy app (`ntfy://ntfy.sh/<topic>`). Tapping opens the app through `phopencode://pending`. The text contains no titles, code, paths or AI output. The `ntfy.sh` daily limit could not be verified from the official docs; the service applies throttling.

## Phases

### P0: Repository and tooling foundation

**G1 (parallel)**

| Task | Goal | Depends on | Owned paths | Acceptance tests | Test |
|---|---|---|---|---|---|
| P0.1 | Repository skeleton, MIT license, README | - | `LICENSE`, `README.md`, `SECURITY.md`, `.editorconfig`, `.gitattributes` | License is MIT with correct year/owner; README contains a TR+EN summary; `SECURITY.md` states how to report vulnerabilities | `LOCAL` |
| P0.2 | JDK installation `[U]` (D2) | - | `docs/dev-setup/jdk.md` | `java -version` is 21; install command is in the doc (`sudo apt install openjdk-21-jdk-headless`) | `LOCAL` |
| P0.3 | Move to opencode V2 `[U]` (D1) | - | `docs/dev-setup/opencode.md` | `opencode --version` is at least 2.2; `/api/info` responds; the GLM session and `.claude/scripts/run-security.sh` keep working; rollback step is in the doc | `LOCAL` |

**G2**

| Task | Goal | Depends on | Owned paths | Acceptance tests | Test |
|---|---|---|---|---|---|
| P0.4 | Gradle root and 32 modules | P0.2 | `gradlew*`, `gradle/`, `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `build-logic/`, `build.gradle.kts` files of all JVM modules, `CLAUDE.md` (build section only) | Wrapper SHA-256 verified; `./gradlew jvmCheck` passes without an SDK; Android modules are skipped; catalog contains all planned libraries; a JVM module cannot take an Android dependency | `PI` |

**G3 (parallel)**

| Task | Goal | Depends on | Owned paths | Acceptance tests | Test |
|---|---|---|---|---|---|
| P0.5 | Android module skeletons and empty `app` | P0.4 | `app/`, `core/{storage,ui,markdown,codeview}/`, `feature/*/ui/` | `assembleDebug` produces output; `minSdk` 26; the app opens with an empty screen | `CI` |
| P0.6 | CI workflow | P0.4 | `.github/workflows/ci.yml` | Jobs: `jvm`, `helper`, `android`, `checks`; actions pinned to SHA; permissions `contents: read`; YAML parsing test passes | `PY` |
| P0.7 | Repository check scripts | P0.1 | `scripts/check_strings.py`, `scripts/check_boundaries.py`, `scripts/tests/` | TR/EN key parity; hard-coded text inside Composables; `android.*` imports in JVM modules; secret patterns; each is tested with a faulty sample | `PY` |

`CP0`: the user creates the repository and pushes, CI goes green.

### P1: Contracts

**G1**

| Task | Goal | Depends on | Owned paths | Acceptance tests | Test |
|---|---|---|---|---|---|
| P1.1 | Domain models | P0.4 | `core/model/` | Session, Message, AssistantBlock (Text, Reasoning, Tool), TokenUsage, Form, FormField, PermissionRequest, Skill, Command, Agent, Model, VcsFile, FileEntry, ServerProfile; token summing and cost tests | `PI` |

**G2 (parallel)**

| Task | Goal | Depends on | Owned paths | Acceptance tests | Test |
|---|---|---|---|---|---|
| P1.2 | API interfaces | P1.1 | `core/api/` | `SessionApi`, `MessageApi`, `InteractionApi`, `CatalogApi`, `VcsApi`, `FsApi`, `EventApi` in separate files; `OpencodeClient` aggregator; `ServerEvent` sealed; `ApiError`; no HTTP types in the interfaces | `PI` |
| P1.3 | SSH interfaces and `ShellQuote` | P1.1 | `core/ssh/…/api/`, `core/ssh/…/quote/` | `SshConnector`, `RemoteExec`, `SftpClient`, `HostKeyVerifier`; `ShellQuote` gives correct results with real `sh` for spaces, quotes, `$()`, newlines and a leading `-` | `PI` |
| P1.4 | Storage interfaces | P1.1 | `core/storage-api/` | `SecretStore`, `ProfileRepository`, `KnownHostsStore`, `SettingsRepository`; `toString` output of secret values is masked | `PI` |
| P1.5 | Routes and deep link | P0.4 | `core/navigation/` | All screen routes are `@Serializable`; `phopencode://pending` constant; serialization tests | `PI` |
| P1.6 | Theme | P0.5 | `core/ui/…/theme/`, `core/ui/src/main/res/values*/` | Light/dark theme; color, typography and spacing tokens; screen test opens in both themes | `CI` |
| P1.7 | Real API samples `[U]` (D1, D7) | P0.3 | `scripts/capture_fixtures.py`, `core/testing/src/main/resources/fixtures/`, `docs/api/` | Spec and text, reasoning, tool, subagent, form and permission flows are recorded; paths and IDs are masked; secret scan passes; "Unverified items" in the notes are updated | `PY` |

**G2b**

Runs before G3 by user decision, because the fakes in P1.8 build on these contracts.

| Task | Goal | Depends on | Owned paths | Acceptance tests | Test |
|---|---|---|---|---|---|
| P1.11 | Align contracts with the recorded API | P1.1, P1.2, P1.7 | `core/model/`, `core/api/`, `docs/PHASES.md` (this group and P1.8's dependency only) | Every item in "Changes for the API modules" in `docs/api/README.md` is applied; no model or API type can hold server `settings`, `headers` or an API key; `jvmCheck` passes | `PI` |

**G3 (parallel)**

| Task | Goal | Depends on | Owned paths | Acceptance tests | Test |
|---|---|---|---|---|---|
| P1.8 | Fakes | P1.2, P1.3, P1.4, P1.11 | `core/testing/…/` | A fake for every interface; the event stream can be driven by hand; fakes pass the interface contract tests | `PI` |
| P1.9 | Shared components | P1.6 | `core/ui/…/component/`, `core/ui/src/main/res/values*/strings_ui_common.xml` | Scaffold, EmptyState, ErrorBanner, Loading, StatusChip, ConfirmDialog; screen tests in both languages | `CI` |

**G4**

| Task | Goal | Depends on | Owned paths | Acceptance tests | Test |
|---|---|---|---|---|---|
| P1.10 | App shell | P1.5, P1.9 | `app/`, `feature/*/ui/…/*Graph.kt`, `feature/*/ui/…/*Module.kt` | NavHost calls all feature graphs; every route opens a placeholder; Koin verification test passes; `locales_config.xml` TR+EN; system language is followed; `networkSecurityConfig` and `dataExtractionRules` in the manifest point to stub XML files | `CI` |

**G5 (fixes after CP1 push)**

| Task | Goal | Depends on | Owned paths | Acceptance tests | Test |
|---|---|---|---|---|---|
| P1.12 | CI test diagnostics | P1.10 | `.github/workflows/ci.yml`, `docs/PHASES.md` (this row) | The android job runs every Gradle task with `--continue` and uploads test and lint reports even on failure | `CI` |
| P1.13 | Robolectric module access on JDK 21 | P1.12 | `build-logic/`, `docs/PHASES.md` (this row) | Android unit test JVMs get the module flags Robolectric needs on API 36; CI's android job is green | `CI` |
| P1.14 | Touch target checks use the minimum | P1.13 | `core/ui/src/test/…/component/`, `docs/PHASES.md` (this row) | Touch-target checks assert at least 48dp and still fail below it; CI's android job is green | `CI` |
| P1.15 | Placeholder-tag rule and local build notes | P1.10, P3.12 | `docs/PHASES.md` (contract-task rule, local test constraint, P12.2's owned paths, this row), `core/ssh/…/api/SftpClient.kt` (KDoc only) | Contract tasks keep the `placeholder:<route>` tag; the local build notes match the Pi; SftpClient's KDoc matches P3.12 | `LOCAL` |

`CP1`: push, CI green.

### P2: opencode HTTP client

All tested on the Pi. Endpoints: `docs/opencode-api-notes.md`.

**G1**

| Task | Goal | Depends on | Owned paths | Acceptance tests | Test |
|---|---|---|---|---|---|
| P2.1 | HTTP foundation | P1.2, P1.8 | `core/api-http/…/base/` | Basic auth; `location` encoding; error mapping (401, 404, 409, 5xx); unknown fields are ignored; the password never enters logs | `PI` |

**G2 (parallel)**

| Task | Goal | Depends on | Owned paths | Acceptance tests | Test |
|---|---|---|---|---|---|
| P2.2 | SSE stream | P2.1 | `core/api-http/…/event/stream/` | `: heartbeat` comment; exponential backoff on disconnect; a silent stream counts as disconnected; cancellation leaves no leak | `PI` |
| P2.3 | Event decoder | P2.1, P1.7 | `core/api-http/…/event/decode/` | All recorded events become `ServerEvent`; an unknown type raises no error; delta and ended are kept apart | `PI` |
| P2.4 | `SessionApi` | P2.1 | `core/api-http/…/session/` | Create, list (cursor), get, delete, update, stop, fork, compact; child session filter | `PI` |
| P2.5 | `MessageApi` | P2.1, P1.7 | `core/api-http/…/message/` | Prompt (`steer`, `queue`), message pagination, command, agent/model switching, turn diff, revert; recorded messages decode | `PI` |

**G3 (parallel)**

| Task | Goal | Depends on | Owned paths | Acceptance tests | Test |
|---|---|---|---|---|---|
| P2.6 | `InteractionApi` | P2.1, P1.7 | `core/api-http/…/interaction/` | List, answer and cancel forms; list and answer permissions; `q0` answer encoding is correct for `string` and `multiselect` | `PI` |
| P2.7 | `CatalogApi` | P2.1 | `core/api-http/…/catalog/` | Skill, command, agent, model, provider, MCP and plugin lists | `PI` |
| P2.8 | `VcsApi` and `FsApi` | P2.1 | `core/api-http/…/vcs/`, `core/api-http/…/fs/` | Status, diff modes, branches; file name search | `PI` |
| P2.9 | Event reducer | P1.8 | `core/session/…/reducer/` | Delta merging; reasoning and tool state; token summing; tolerant of out-of-order events; replaying a recorded stream gives the same result as the message list | `PI` |

**G4 (parallel)**

| Task | Goal | Depends on | Owned paths | Acceptance tests | Test |
|---|---|---|---|---|---|
| P2.10 | Resync | P2.9 | `core/session/…/sync/` | On reconnect, messages and pending items are fetched; state stays consistent after a missed event; a duplicate event has no effect | `PI` |
| P2.11 | Client assembly and version gate | P2.2, P2.3, P2.4, P2.5, P2.6, P2.7, P2.8 | `core/api-http/…/client/` | `/api/info` version is read; below 2.2 is rejected with a clear error; warning on a new major | `PI` |
| P2.12 | Untracked mode in `VcsDiffQuery` | P2.8 | `core/api/…/VcsApi.kt` (`VcsDiffQuery.untracked`), `core/testing/…/api/FakeCatalogAndFiles.kt` and vcs contract cases (where they use `untracked`), `core/api-http/…/vcs/` | `untracked` is a files/folders choice matching the server; the KDoc is correct; no value is refused locally | `PI` |
| P2.13 | Stable SSE silence test | P2.2 | `core/api-http/…/event/stream/`, `docs/PHASES.md` (this row) | The silence test passes reliably under load; it still fails if a silent connection's reader leaks | `PI` |
| P2.14 | Client socket factory | P2.11, P3.5 | `core/api-http/…/client/`, `core/remote/…/connection/OpencodeClientFactory.kt` and its test (default factory, after P3.9), `docs/PHASES.md` (this row) | The client can take a SocketFactory; every request and the event stream use it; the connection manager's default client goes through the session tunnel | `PI` |
| P2.15 | Inbox list on resync | P2.10, P2.5 | `core/api/…/MessageApi.kt` (inbox list), `core/api-http/…/message/`, `core/testing/…/api/FakeMessageApi.kt` and message contract cases, `core/testing/…/FakeApiTest.kt`, `core/testing/…/ContractTeethTest.kt`, `core/api-http/…/client/FullServer.kt` (inbox route), `core/api/…/ApiBoundaryTest.kt` (inbox operation), `core/session/…/sync/`, `docs/PHASES.md` (this row) | The inbox list is read from the server; resync restores queued prompts and drops ones delivered or cancelled while disconnected | `PI` |
| P2.16 | Unknown server version accepted with a warning | P2.11 | `core/api/…/OpencodeClient.kt` (`ServerInfo`), `core/api-http/…/client/ServerVersion.kt`, `core/api-http/…/client/HttpOpencodeClient.kt` (KDoc only) and the client tests' version cases, `core/testing/…/api/FakeOpencodeClient.kt`, its test and version contract cases, `docs/PHASES.md` (this row) | An unparseable version connects with `isVersionUnknown`; below 2.2 is still rejected; the new-major warning is unchanged | `PI` |
| P2.17 | Stable timing tests | P2.1, P3.1 | `core/api-http/src/test/…/base/CloseRaceTest.kt`, `core/testing/…/contract/SshContracts.kt` (verifier timing case), `core/ssh/src/test/…/connect/SshjConnectorTest.kt` (verifier timing cases), production fixes in `core/ssh/…/connect/` or `core/api-http/…/base/` if a real bug is found, `docs/PHASES.md` (this row) | Both tests pass reliably under load; each still fails if the bug it guards returns | `PI` |

### P3: SSH and server management

**G1**

| Task | Goal | Depends on | Owned paths | Acceptance tests | Test |
|---|---|---|---|---|---|
| P3.1 | SSH connection | P1.3 | `core/ssh/…/connect/` | Connects to an embedded MINA server with key and password; a wrong host key is rejected; timeout; keep-alive | `PI` |

**G2 (parallel)**

| Task | Goal | Depends on | Owned paths | Acceptance tests | Test |
|---|---|---|---|---|---|
| P3.2 | Remote command | P3.1 | `core/ssh/…/exec/` | stdout, stderr, exit code; stdin; timeout; cancellation ends the process | `PI` |
| P3.3 | SFTP read | P3.1 | `core/ssh/…/sftp/read/` | List, `stat`, symlinks, byte-exact reads, Turkish names and names with spaces | `PI` |
| P3.4 | SFTP write | P3.1 | `core/ssh/…/sftp/write/` | Atomic write via temp file + rename; streaming upload; delete; rename; folder creation; path traversal rejected | `PI` |
| P3.5 | Tunnel | P3.1 | `core/ssh/…/tunnel/` | `SocketFactory` opens `direct-tcpip`; no local port is listened on; OkHttp SSE flows through the tunnel; channels close on shutdown | `PI` |

**G2b (wiring)**

| Task | Goal | Depends on | Owned paths | Acceptance tests | Test |
|---|---|---|---|---|---|
| P3.11 | SSH feature wiring | P3.2, P3.3, P3.4, P3.5 | `core/ssh/…/connect/SessionFeatures.kt`, `core/ssh/…/connect/SshjConnector.kt` (default features only), `core/ssh/…/sftp/` (main and test: the `SftpClient` composite, the shared `SftpRoot` path rules and the read/write edits that use them), `core/ssh/…/exec/SshjRemoteExec.kt` (real-time timeout) and its test, `core/ssh/…/tunnel/` (socket tracking from creation) and its test, `core/ssh/src/test/…/wiring/`, `core/remote/src/test/…/wiring/` (the core/testing contracts; core/ssh cannot depend on core/testing), `docs/PHASES.md` (this row) | The default SshjConnector offers exec, SFTP and tunnels; one SftpClient composes read and write over one channel; core/testing SSH and SFTP contracts pass against the real implementation | `PI` |
| P3.12 | SFTP read confinement | P3.11 | `core/ssh/…/sftp/read/` (main and test), `core/ssh/…/sftp/SftpRoot.kt`, `core/ssh/…/sftp/SshjSftpClient.kt` and their tests, `core/ssh/…/sftp/RealPaths.kt` (moved from `sftp/write/`, with only that move's edits in `sftp/write/`), `core/remote/src/test/…/wiring/` (contract adjustments only), `docs/PHASES.md` (this row) | Reads (list, stat, read) through a symlink leaving the root are refused; links inside the root work; no outside path appears in errors | `PI` |

**G3 (parallel)**

| Task | Goal | Depends on | Owned paths | Acceptance tests | Test |
|---|---|---|---|---|---|
| P3.6 | Key operations | P3.1 | `core/ssh/…/key/` | Generates ed25519; reads OpenSSH format and passphrase-protected keys; public key is exported; private key never enters logs | `PI` |
| P3.7 | Server discovery | P3.2 | `core/remote/…/detect/` | `~/.opencode/bin` outside `PATH` is found; version is read; a running server and its port are found; clear result if opencode is missing | `PI` |
| P3.8 | Server startup | P3.2 | `core/remote/…/start/` | The password is passed via stdin and does not appear in the process list; the URL is taken from the `listening on` line; state file is 0600; binds to 127.0.0.1 only | `PI` |

**G4**

| Task | Goal | Depends on | Owned paths | Acceptance tests | Test |
|---|---|---|---|---|---|
| P3.9 | Connection manager | P3.5, P3.7, P3.8, P2.10, P2.11 | `core/remote/…/connection/` | States: SSH, host approval, server, API, connected, reconnecting, error; reconnects after a drop; no resources remain after closing | `PI` |
| P3.13 | Server and event-stream drop handling | P3.9 | `core/remote/…/connection/`, `core/remote/…/detect/`, `core/remote/…/start/`, `core/remote/…/state/` (shared state-file parser), `docs/PHASES.md` (this row) | A server or event-stream drop reconnects like an SSH drop; a deliberate disconnect does not; one server.state parser serves discovery and startup | `PI` |

**G5**

| Task | Goal | Depends on | Owned paths | Acceptance tests | Test |
|---|---|---|---|---|---|
| P3.10 | SSH verification on Android | P3.9 | `app/…/security/`, `app/src/main/res/xml/network_security_config.xml`, `app/proguard-rules.pro`, `app/src/androidTest/`, `.github/workflows/emulator.yml`, `feature/connect/ui/src/main/AndroidManifest.xml` (INTERNET permission), `app/…/PhopencodeApplication.kt` (`BouncyCastle.install()` at start), `app/build.gradle.kts` (`androidTestImplementation` of `:core:ssh`, release R8 rules file, BouncyCastle OSGi manifest packaging exclude) | BouncyCastle provider replacement; ed25519 connection, SFTP and tunnel from the emulator to sshd on the runner; cleartext only to 127.0.0.1 | `EMU` |

**G6 (fixes after the CP3 push)**

| Task | Goal | Depends on | Owned paths | Acceptance tests | Test |
|---|---|---|---|---|---|
| P3.14 | Emulator disk space | P3.10 | `.github/workflows/emulator.yml`, `docs/PHASES.md` (this row) | The emulator jobs free enough disk for the AVD and boot; `df -h` is logged before the build and before the emulator | `CI` |

`CP3`: push, emulator job green. At this point the plan closes its biggest technical risk.

### P4: Storage and connection screens

**G1 (parallel)**

| Task | Goal | Depends on | Owned paths | Acceptance tests | Test |
|---|---|---|---|---|---|
| P4.1 | Encrypted secret storage | P1.4, P1.10 | `core/storage/…/secret/` | Keystore + Tink; values are not plaintext on disk; unreadable once the installation is removed | `EMU` |
| P4.2 | Profile and settings repositories | P1.4, P1.10 | `core/storage/…/repo/` | DataStore; profiles, known hosts, settings; no password in the profile file | `CI` |
| P4.3 | `connect` contract | P3.9, P1.10, P4.16, P4.17, P4.18 | `feature/connect/logic/…/contract/`, `feature/connect/ui/…/ConnectGraph.kt`, `feature/connect/ui/…/ConnectModule.kt`, screen stubs | Contract outputs complete; stubs compile (`logic`) | `PI` |

**G1b (storage and connection gaps found by P4.3)**

| Task | Goal | Depends on | Owned paths | Acceptance tests | Test |
|---|---|---|---|---|---|
| P4.17 | Key index and recent folders stores | P1.4, P4.2 | `core/storage-api/…/` (new key index and recent folders interfaces only), `core/testing/…/storage/FakeStorage.kt` and `core/testing/…/contract/StorageContracts.kt` (their fakes and contract cases), `core/testing/src/test/…` (fake and contract tests for them), `core/storage/…/repo/`, `docs/PHASES.md` (G1b rows, P4.3's dependencies) | Each key's name, type, fingerprint, public key and the `SecretRef` of its private key persist; no private key material is in the index; recent folders are kept per profile, newest first, bounded; the contract cases pass on the fake and the real store | `CI` |
| P4.18 | SFTP on the active connection | P3.12, P3.13 | `core/remote/…/connection/`, `docs/PHASES.md` (this row) | Opens a root-confined `SftpClient` over the connected SSH session; fails clearly when not connected; every client opened is closed on disconnect, reconnect and close | `PI` |
| P4.16 | App wiring for storage and connection | P4.1, P4.2, P4.17, P4.18 | `app/…/di/`, `app/build.gradle.kts`, `docs/PHASES.md` (this row) | Koin provides the secret store, profile, known hosts, settings, key index and recent folders stores, the SSH connector, `ServerResolver`, `OpencodeClientFactory`, `ConnectionManager` and an app scope; the Koin verification test and the app's navigation tests stay green | `CI` |

**G2 (parallel)**

| Task | Goal | Depends on | Owned paths | Acceptance tests | Test |
|---|---|---|---|---|---|
| P4.4 | Server list logic | P4.3 | `feature/connect/logic/…/serverlist/` | List, select, delete; most recently used on top | `PI` |
| P4.5 | Server list screen | P4.3 | `feature/connect/ui/…/serverlist/`, `strings_connect_serverlist.xml` | Empty state; swipe to delete with confirmation | `CI` |
| P4.6 | Server form logic | P4.3 | `feature/connect/logic/…/serverform/` | Host, port and user validation; key or password; saved to `SecretStore` | `PI` |
| P4.7 | Server form screen | P4.3 | `feature/connect/ui/…/serverform/`, `strings_connect_serverform.xml` | Error messages; password field hidden; both languages | `CI` |
| P4.8 | Key management logic | P4.3 | `feature/connect/logic/…/keys/` | Generate, import, delete; copy public key; private key is never shown | `PI` |
| P4.9 | Key management screen | P4.3 | `feature/connect/ui/…/keys/`, `strings_connect_keys.xml` | Import via file picker; fingerprint visible | `CI` |

**G3 (parallel)**

| Task | Goal | Depends on | Owned paths | Acceptance tests | Test |
|---|---|---|---|---|---|
| P4.10 | Host key logic | P4.3 | `feature/connect/logic/…/hostkey/` | First connection asks for approval; a changed key stops the connection | `PI` |
| P4.11 | Host key screen | P4.3 | `feature/connect/ui/…/hostkey/`, `strings_connect_hostkey.xml` | SHA-256 fingerprint; red warning on change | `CI` |
| P4.12 | Folder picker logic | P4.3 | `feature/connect/logic/…/folder/` | Browsing over SFTP; recent folders; error on a folder without permission | `PI` |
| P4.13 | Folder picker screen | P4.3 | `feature/connect/ui/…/folder/`, `strings_connect_folder.xml` | Go up to the parent folder; select a folder | `CI` |
| P4.14 | Connection flow logic | P4.3 | `feature/connect/logic/…/progress/` | Connect if running, otherwise start; ask for a password on a foreign server; short, actionable errors | `PI` |
| P4.15 | Connection flow screen | P4.3 | `feature/connect/ui/…/progress/`, `strings_connect_progress.xml` | Step indicator; retry; cancel | `CI` |

**G4 (fixes from CI)**

| Task | Goal | Depends on | Owned paths | Acceptance tests | Test |
|---|---|---|---|---|---|
| P4.19 | Server form test on API 36 | P4.7 | `feature/connect/ui/…/serverform/` (main and test), `docs/PHASES.md` (this row) | The server form tests pass at API 26 and API 36 in CI | `CI` |
| P4.20 | Connection states carry their server | P4.18, P3.13 | `core/remote/…/connection/`, `docs/PHASES.md` (P4.20 and P4.21 rows) | Connected, host-approval and host-key-changed states carry the profile and endpoint; a changed key is replaced atomically by the manager and never for a stale request | `PI` |
| P4.21 | Folder picker uses the connection's server | P4.20, P4.12 | `feature/connect/logic/…/folder/` | The folder picker serves only a connection whose state names the active profile; the generation heuristics are removed | `PI` |
| P4.22 | Folder picker starts at home | P3.12, P4.21 | `core/ssh/…/api/SftpClient.kt`, `core/ssh/…/sftp/`, `core/testing/…` (SFTP fake and contract cases), `core/remote/…/connection/` (wrappers only), `core/remote/…/wiring/` (test SFTP harness), `feature/connect/logic/…/folder/`, `docs/PHASES.md` (this row) | `SftpClient` reports the login home inside its root; the folder picker opens there and falls back to `/` | `PI` |

`CP4`: push, CI green, `PHONE`: connect to a real server.

### P5: Session management

**G1**

| Task | Goal | Depends on | Owned paths | Acceptance tests | Test |
|---|---|---|---|---|---|
| P5.1 | `sessions` contract | P3.9, P1.10 | `feature/sessions/logic/…/contract/`, `feature/sessions/ui/…/SessionsGraph.kt`, `feature/sessions/ui/…/SessionsModule.kt`, screen stubs | Contract outputs complete | `PI` |

**G2 (parallel)**

| Task | Goal | Depends on | Owned paths | Acceptance tests | Test |
|---|---|---|---|---|---|
| P5.2 | List logic | P5.1 | `feature/sessions/logic/…/list/` | Pagination, search; root sessions only; live updates; tokens and cost on the row | `PI` |
| P5.3 | List screen | P5.1 | `feature/sessions/ui/…/list/`, `strings_sessions_list.xml` | Status badge; empty state; pull to refresh | `CI` |
| P5.4 | Creation logic | P5.1 | `feature/sessions/logic/…/create/` | Folder, agent, model, title; error state | `PI` |
| P5.5 | Creation screen | P5.1 | `feature/sessions/ui/…/create/`, `strings_sessions_create.xml` | Pickers; goes to chat after creating | `CI` |
| P5.6 | Delete and edit logic | P5.1 | `feature/sessions/logic/…/manage/` | Delete (with a warning about child sessions and irreversibility), rename, archive | `PI` |
| P5.7 | Delete and edit screen | P5.1 | `feature/sessions/ui/…/manage/`, `strings_sessions_manage.xml` | Confirmation dialog; naming field | `CI` |

`CP5`: push, CI green.

### P6: Chat core

**G1 (parallel)**

| Task | Goal | Depends on | Owned paths | Acceptance tests | Test |
|---|---|---|---|---|---|
| P6.1 | `chat` contract | P2.10, P5.1 | `feature/chat/logic/…/contract/`, `feature/chat/ui/…/ChatGraph.kt`, `feature/chat/ui/…/ChatModule.kt`, `feature/chat/ui/…/ChatScreen.kt`, card stubs | `TimelineItem` sealed; each card is a stub in its own file; `ChatScreen` slots wired | `PI` |
| P6.2 | Markdown component | P1.9 | `core/markdown/` | Headings, lists, tables, code blocks; partial Markdown does not break during streaming; supported code languages are verified; links open after confirmation | `CI` |

**G2 (parallel)**

| Task | Goal | Depends on | Owned paths | Acceptance tests | Test |
|---|---|---|---|---|---|
| P6.3 | Timeline logic | P6.1 | `feature/chat/logic/…/timeline/` | Messages turn into items; pagination of older messages; auto-scroll rule | `PI` |
| P6.4 | Timeline screen | P6.1, P6.2, P6.15 | `feature/chat/ui/…/timeline/`, `strings_chat_timeline.xml` | Scrolling does not jump during streaming; user and assistant messages look distinct | `CI` |
| P6.5 | Composer logic | P6.1 | `feature/chat/logic/…/composer/` | Draft; `steer`/`queue` while running; text is not lost on a send failure; stop | `PI` |
| P6.6 | Composer screen | P6.1 | `feature/chat/ui/…/composer/`, `strings_chat_composer.xml` | Send/Stop button; keyboard layout | `CI` |
| P6.7 | Reasoning logic | P6.1 | `feature/chat/logic/…/reasoning/` | Deltas merge; duration is computed; empty reasoning is hidden; expanded/collapsed state | `PI` |
| P6.8 | Reasoning card | P6.1, P6.2, P6.15 | `feature/chat/ui/…/reasoning/`, `strings_chat_reasoning.xml` | Streams live; collapsible; Markdown renders | `CI` |
| P6.15 | Feature screens can use Markdown | P6.2 | `build-logic/`, `docs/PHASES.md` (this row and the P6.4/P6.8 dependencies) | The `phopencode.feature.ui` plugin adds `:core:markdown`; a feature UI module can call `MarkdownView` | `PI` |

**G3 (parallel)**

| Task | Goal | Depends on | Owned paths | Acceptance tests | Test |
|---|---|---|---|---|---|
| P6.9 | Tool card logic | P6.1 | `feature/chat/logic/…/tool/` | Shell, edit, read and search summaries; generic display for an unknown tool; error state | `PI` |
| P6.10 | Tool card screen | P6.1 | `feature/chat/ui/…/tool/`, `strings_chat_tool.xml` | Status icon; input/output expand; long output is truncated | `CI` |
| P6.11 | Token logic | P6.1 | `feature/chat/logic/…/usage/` | Input, output, reasoning, cache, cost; context percentage from `limit.context`; hidden if there is no limit | `PI` |
| P6.12 | Token screen | P6.1 | `feature/chat/ui/…/usage/`, `strings_chat_usage.xml` | Top bar; tapping opens a detail page; number format follows the language | `CI` |
| P6.13 | Status logic | P6.1 | `feature/chat/logic/…/status/` | Running, retrying, error, disconnected; retry counter | `PI` |
| P6.14 | Status banner | P6.1 | `feature/chat/ui/…/status/`, `strings_chat_status.xml` | Banner on disconnect; disappears on reconnect | `CI` |

`CP6`: push, CI green, `PHONE`: send a prompt, watch the stream.

### P7: Questions, permissions, subagents

**G1**

| Task | Goal | Depends on | Owned paths | Acceptance tests | Test |
|---|---|---|---|---|---|
| P7.1 | Interaction contract | P6.1 | `feature/chat/logic/…/interaction/contract/`, `feature/chat/ui/…/ChatGraph.kt`, `feature/chat/ui/…/ChatModule.kt`, `feature/chat/ui/…/ChatScreen.kt`, card stubs | All cards and pages of this phase are wired into slots as stubs | `PI` |

**G2 (parallel)**

| Task | Goal | Depends on | Owned paths | Acceptance tests | Test |
|---|---|---|---|---|---|
| P7.2 | Form logic | P7.1 | `feature/chat/logic/…/form/` | Single and multiple choice; free text; `required`, `when`, min/max; `q<n>` answer is encoded correctly; decline; unknown field types are safe | `PI` |
| P7.3 | Question card | P7.1 | `feature/chat/ui/…/form/`, `strings_chat_form.xml` | Radio button, checkbox, "kendi yanıtın" (your own answer); steps for multiple questions; number and yes/no fields | `CI` |
| P7.4 | Permission logic | P7.1 | `feature/chat/logic/…/permission/` | `once`, `always`, `reject`; rejection message; child session permissions are visible | `PI` |
| P7.5 | Permission card | P7.1 | `feature/chat/ui/…/permission/`, `strings_chat_permission.xml` | Action and resource are spelled out; `always` needs a separate confirmation | `CI` |
| P7.6 | Subagent logic | P7.1 | `feature/chat/logic/…/subagent/` | `parentID` tree; status, duration, tokens; tool progress is mapped; nested subagents | `PI` |
| P7.7 | Subagent screen | P7.1 | `feature/chat/ui/…/subagent/`, `strings_chat_subagent.xml` | List and live badge; tapping opens the child session timeline; return to the parent session | `CI` |

**G3 (parallel)**

| Task | Goal | Depends on | Owned paths | Acceptance tests | Test |
|---|---|---|---|---|---|
| P7.8 | Command palette logic | P7.1 | `feature/chat/logic/…/palette/` | `/` for commands and skills, `@` for agents and files; filtering; turns into prompt attachments | `PI` |
| P7.9 | Command palette screen | P7.1 | `feature/chat/ui/…/palette/`, `strings_chat_palette.xml` | List that opens while typing | `CI` |
| P7.10 | Agent/model logic | P7.1 | `feature/chat/logic/…/switcher/` | Agent, model and variant switching; hidden agents are not listed | `PI` |
| P7.11 | Agent/model screen | P7.1 | `feature/chat/ui/…/switcher/`, `strings_chat_switcher.xml` | Bottom sheet picker; current selection is visible | `CI` |
| P7.12 | Turn diff and revert logic | P7.1 | `feature/chat/logic/…/revert/` | Diff per turn; revert, confirm, cancel | `PI` |
| P7.13 | Turn diff screen | P7.1 | `feature/chat/ui/…/revert/`, `strings_chat_revert.xml` | File list; revert needs confirmation | `CI` |

**G4 (parallel)**

| Task | Goal | Depends on | Owned paths | Acceptance tests | Test |
|---|---|---|---|---|---|
| P7.14 | Compaction and fork logic | P7.1 | `feature/chat/logic/…/actions/` | Compact; fork from a message; switch to the new session | `PI` |
| P7.15 | Session action menu | P7.1 | `feature/chat/ui/…/actions/`, `strings_chat_actions.xml` | Menu, confirmation and status | `CI` |

`CP7`: push, CI green, `PHONE`: answer a multiple-choice question, watch a subagent.

### P8: File browser

**G1 (parallel)**

| Task | Goal | Depends on | Owned paths | Acceptance tests | Test |
|---|---|---|---|---|---|
| P8.1 | File service | P3.3, P3.4, P2.8 | `core/files/` | List, read (size-limited), atomic write, delete, move; name search via API, content search via `rg`/`grep`; paths pass through `ShellQuote` | `PI` |
| P8.2 | `files` contract | P3.9, P1.10 | `feature/files/logic/…/contract/`, `feature/files/ui/…/FilesGraph.kt`, `feature/files/ui/…/FilesModule.kt`, screen stubs | Contract outputs complete | `PI` |
| P8.3 | Code view | P1.9 | `core/codeview/` | sora-editor Compose wrapper; language from the extension; read-only and edit modes; TextMate grammars and their licenses under `assets`; dark/light theme | `EMU` |

**G1b**

| Task | Goal | Depends on | Owned paths | Acceptance tests | Test |
|---|---|---|---|---|---|
| P8.16 | Wire the file service | P8.1, P8.2 | `core/remote/…/ConnectionManager.kt`, `feature/files/logic/…/service/`, `feature/files/ui/…/FilesModule.kt` | The ports reach the connected server's file service; no server means NotConnected; errors map completely | `PI` |

**G2 (parallel)**

| Task | Goal | Depends on | Owned paths | Acceptance tests | Test |
|---|---|---|---|---|---|
| P8.4 | Tree logic | P8.1, P8.2 | `feature/files/logic/…/tree/` | Sorting; hidden files toggle; git badge; `.gitignore` marker; refresh | `PI` |
| P8.5 | Tree screen | P8.2 | `feature/files/ui/…/tree/`, `strings_files_tree.xml` | Path bar; icons; smooth in large folders | `CI` |
| P8.6 | Viewer logic | P8.1, P8.2 | `feature/files/logic/…/viewer/` | Type detection (text, binary, image, Markdown); warning above the limit; encoding errors are safe | `PI` |
| P8.7 | Viewer screen | P8.2, P8.3, P6.2 | `feature/files/ui/…/viewer/`, `strings_files_viewer.xml` | Highlighted code; Markdown and raw views; image preview | `CI` |
| P8.8 | Editor logic | P8.1, P8.2 | `feature/files/logic/…/editor/` | Dirty state; `mtime` conflict warning; atomic save; line endings are preserved | `PI` |
| P8.9 | Editor screen | P8.2, P8.3 | `feature/files/ui/…/editor/`, `strings_files_editor.xml` | Save; confirmation when leaving without saving | `CI` |

**G2b**

| Task | Goal | Depends on | Owned paths | Acceptance tests | Test |
|---|---|---|---|---|---|
| P8.17 | Code view go-to-line | P8.3, P8.7 | `core/codeview/`, `feature/files/ui/…/viewer/` | The viewer opens a file at the requested line; a line past the end opens at the last line; a missing, zero or negative line opens at the top; nothing crashes | `CI` |

**G3 (parallel)**

| Task | Goal | Depends on | Owned paths | Acceptance tests | Test |
|---|---|---|---|---|---|
| P8.10 | File operations logic | P8.1, P8.2 | `feature/files/logic/…/ops/` | Delete, rename, create folder; invalid names; folder deletion needs confirmation | `PI` |
| P8.11 | File operations screen | P8.2 | `feature/files/ui/…/ops/`, `strings_files_ops.xml` | Context menu, dialogs | `CI` |
| P8.12 | Transfer logic | P8.1, P8.2 | `feature/files/logic/…/transfer/` | Queue; progress; cancel; overwrite prompt; no partial file remains | `PI` |
| P8.13 | Transfer screen and service | P8.2 | `feature/files/ui/…/transfer/`, `feature/files/ui/src/main/AndroidManifest.xml`, `strings_files_transfer.xml` | Upload/download via the system file picker; foreground service; progress in a notification | `EMU` |
| P8.14 | Search logic | P8.1, P8.2 | `feature/files/logic/…/search/` | Name and content; result limit; `grep` if `rg` is missing | `PI` |
| P8.15 | Search screen | P8.2 | `feature/files/ui/…/search/`, `strings_files_search.xml` | Goes from a result to the file and line | `CI` |

**G4 (CP8 fixes, parallel)**

| Task | Goal | Depends on | Owned paths | Acceptance tests | Test |
|---|---|---|---|---|---|
| P8.18 | Old-Android crash in atomic writes | P8.1 | `core/ssh/…/sftp/write/SftpWriter.kt`, `core/ssh/src/test/…`, `feature/files/logic/…/viewer/ViewerDetection.kt` and its tests, `docs/PHASES.md` (G4 rows) | No Java API missing below Android 14 is used; no covariant Buffer method is called; an atomic write, an upload and the viewer's text decode work on API 26; the temporary name keeps its form | `EMU` |
| P8.19 | Lint errors | P5.1, P5.3, P6.12, P7.7, P7.13, P8.7 | `feature/sessions/ui/…/SessionsGraph.kt`, `…/list/SessionUsageText.kt`, `feature/chat/ui/…/revert/RevertText.kt`, `…/subagent/SubagentText.kt`, `…/usage/UsageText.kt`, `feature/files/ui/…/viewer/ViewerText.kt`, their tests | `lint` passes in the sessions, chat and files UI modules; numbers and dates follow the app's language when it changes | `CI` |
| P8.20 | List tests on a small screen | P5.3, P7.7, P8.5, P8.15 | the list tests of `feature/sessions/ui`, `feature/chat/ui` and `feature/files/ui` | The list tests pass at API 36 in CI and on a small screen at API 26; rows below the visible part are reached by scrolling the list | `CI` |
| P8.21 | API 36 emulator boot | — | `.github/workflows/emulator.yml` | The API 36 emulator boots in CI and runs the connected tests; the API 26 job is unchanged | `CI` |
| P8.22 | Chat timeline on a short window | P6.1, P7.1 | `feature/chat/ui/…/ChatScreen.kt`, `ChatShortWindowTest.kt` | On a short window the timeline keeps a usable height, pending cards scroll in their own area, and the composer stays visible; tall windows are unchanged | `CI` |

`CP8`: push, CI and emulator green, `PHONE`: upload and download a file.

### P9: Git

**G1 (parallel)**

| Task | Goal | Depends on | Owned paths | Acceptance tests | Test |
|---|---|---|---|---|---|
| P9.1 | Git commands | P3.2 | `core/git/…/command/` | Every argument passes through `ShellQuote`; `--` separator; `GIT_TERMINAL_PROMPT=0`; real git tests with `$(...).txt`, `-rf` and file names containing newlines | `PI` |
| P9.2 | Git parsers | P1.1 | `core/git/…/parse/` | `status --porcelain=v2 -z`, `log -z`, branch list, unified diff; with real repository samples | `PI` |
| P9.3 | `git` contract | P3.9, P1.10 | `feature/git/logic/…/contract/`, `feature/git/ui/…/GitGraph.kt`, `feature/git/ui/…/GitModule.kt`, screen stubs | Contract outputs complete | `PI` |

**G2 (parallel)**

| Task | Goal | Depends on | Owned paths | Acceptance tests | Test |
|---|---|---|---|---|---|
| P9.4 | Status logic | P9.1, P9.2, P9.3 | `feature/git/logic/…/status/` | Staged, unstaged and untracked separated; ahead/behind; branch name; clean repository | `PI` |
| P9.5 | Status screen | P9.3 | `feature/git/ui/…/status/`, `strings_git_status.xml` | Grouped list; stage/unstage from the row | `CI` |
| P9.6 | Diff logic | P9.1, P9.2, P9.3 | `feature/git/logic/…/diff/` | Hunk model; binary and large file limit; staged and working | `PI` |
| P9.7 | Diff screen | P9.3, P8.3 | `feature/git/ui/…/diff/`, `strings_git_diff.xml` | Line colors; horizontal scrolling; addition/deletion counts | `CI` |
| P9.8 | History logic | P9.1, P9.2, P9.3 | `feature/git/logic/…/log/` | Pagination; commit details and files; branch filtering | `PI` |
| P9.9 | History screen | P9.3 | `feature/git/ui/…/log/`, `strings_git_log.xml` | List; details; from a file to its diff | `CI` |

**G3 (parallel)**

| Task | Goal | Depends on | Owned paths | Acceptance tests | Test |
|---|---|---|---|---|---|
| P9.10 | Stage and commit logic | P9.1, P9.3 | `feature/git/logic/…/commit/` | Stage, unstage, discard changes (with confirmation), commit; empty message rejected; hook errors are shown | `PI` |
| P9.11 | Commit screen | P9.3 | `feature/git/ui/…/commit/`, `strings_git_commit.xml` | Message field; discard confirmation | `CI` |
| P9.12 | Branch logic | P9.1, P9.2, P9.3 | `feature/git/logic/…/branch/` | List, create, switch, delete; warning on a dirty tree; deleting an unmerged branch needs confirmation | `PI` |
| P9.13 | Branch screen | P9.3 | `feature/git/ui/…/branch/`, `strings_git_branch.xml` | Local/remote separation; search | `CI` |
| P9.14 | Remote operations logic | P9.1, P9.3 | `feature/git/logic/…/remote/` | Fetch, pull (`--ff-only`), push; no force; credential prompts do not hang, an error is returned; long operations can be cancelled | `PI` |
| P9.15 | Remote operations screen | P9.3 | `feature/git/ui/…/remote/`, `strings_git_remote.xml` | Confirmation before push; explanation on conflict | `CI` |

**G4 (CP9 fixes, parallel)**

| Task | Goal | Depends on | Owned paths | Acceptance tests | Test |
|---|---|---|---|---|---|
| P9.16 | Git core gaps | P9.6, P9.8, P9.12 | `core/git/…/command/`, `feature/git/logic/…/diff/`, `feature/git/logic/…/log/`, `feature/git/logic/…/branch/`, `docs/PHASES.md` (this block) | Merge commits diff and list files against their first parent; an untracked file's diff shows its content; a remote branch tap tracks it even with branch.autoSetupMerge=false | `PI` |
| P9.17 | Gone upstream | P9.4, P9.5, P9.15 | `feature/git/logic/…/contract/GitModels.kt` (GitBranchStatus field only), `feature/git/logic/…/status/`, `feature/git/ui/…/status/`, `feature/git/ui/…/remote/` (upstream line only), `strings_git_status.xml`, `strings_git_remote.xml` | A deleted upstream shows as gone, not as up to date or as no upstream | `CI` |

**G5 (CP9 fixes)**

| Task | Goal | Depends on | Owned paths | Acceptance tests | Test |
|---|---|---|---|---|---|
| P9.18 | Optional opencode port | P3.7, P3.9, P4.6, P4.7 | `core/model/…/ServerProfile.kt` (opencodePort only), `core/remote/…/detect/`, `core/remote/…/connection/`, `core/storage/src/test/`, `feature/connect/logic/…/contract/`, `feature/connect/logic/…/serverform/`, `feature/connect/logic/…/progress/`, `feature/connect/ui/…/serverform/`, `feature/connect/ui/…/progress/`, `strings_connect_serverform.xml`, `strings_connect_progress.xml`, `docs/PHASES.md` (this block) | With a port set, only the opencode server on that loopback port is used; with none there the app says so, and starts one on it only when no opencode serve runs; an empty port keeps automatic detection | `CI` |
| P9.19 | Folder listing error detail | P4.12, P4.13 | `feature/connect/logic/…/contract/FolderContract.kt` (errorDetail only), `feature/connect/logic/…/folder/`, `feature/connect/ui/…/folder/`, `strings_connect_folder.xml`, `docs/PHASES.md` (this block) | A generic folder listing failure shows the failing exception class chain, never messages, paths or hosts | `CI` |
| P9.20 | No SSH writes on the caller's thread | P3.1, P3.2, P3.3, P3.4, P3.9, P4.18 | `core/ssh/…/connect/`, `core/ssh/…/sftp/`, `core/ssh/…/exec/` (and their tests), `core/remote/…/connection/`, `docs/PHASES.md` (this block) | Closing SFTP, exec or a connection from the main thread sends no SSH packet from it; a failed socket write ends the session cleanly instead of corrupting it | `CI` |
| P9.21 | Folder picker survives a dropped connection | P4.12, P9.19 | `feature/connect/logic/…/folder/` | A listing whose client the connection closed shows NotConnected, not Failed, and lists again after the reconnect | `CI` |
| P9.22 | Folder picker names the connection error | P9.19, P9.21 | `feature/connect/logic/…/folder/`, `feature/connect/ui/…/folder/`, `core/remote/…/connection/` (ConnectionError.Unexpected cause only), `strings_connect_folder.xml`, `docs/PHASES.md` (this block) | When the picker shows NotConnected and the connection ended in an error, the error type and its cause class chain show, never messages or addresses | `CI` |
| P9.23 | Tunnel streams run on API 26 | P3.6 | `core/ssh/…/tunnel/` (and its tests) | Tunnel socket reads and writes use no API above minSdk 26 and reject bad offsets with IndexOutOfBoundsException | `CI` |
| P9.24 | A stray cancellation does not close the connection manager | P3.9, P9.22 | `core/remote/…/connection/`, `feature/connect/logic/…/folder/`, `feature/connect/logic/…/contract/FolderContract.kt` (KDoc only), `docs/PHASES.md` (this block) | A cancellation that does not come from the app scope or a stop ends the connection with an Unexpected error instead of closing the manager for good; the folder picker names Idle and Closed | `CI` |
| P9.25 | Debug-only detailed connection diagnostics | P9.22, P9.24 | `app/…/di/`, `core/remote/…/connection/`, `feature/connect/logic/…/folder/`, `feature/connect/logic/…/contract/FolderContract.kt`, `feature/connect/ui/…/folder/`, `strings_connect_folder.xml`, `docs/PHASES.md` (this block) | In a debuggable build the folder picker shows the full failure chain with stack frames and why the connection last ended; a release build shows only class names, as before; no password or key appears in either | `CI` |
| P9.26 | App singletons keep the app scope | P4.16, P3.9, P8.2 | `app/…/di/`, `feature/files/ui/…/FilesModule.kt`, feature `*Module.kt` KDoc, `docs/PHASES.md` (this block) | A singleton first created while a screen's presenter is resolved runs on the app scope, not the screen's; leaving that screen does not close the connection manager or the transfer queue | `CI` |
| P9.27 | App logo and launcher icon | P1.10 | `app/src/main/res/drawable/ic_launcher_*.xml`, `app/src/main/res/mipmap-anydpi-v26/`, `app/src/main/res/values/ic_launcher_background.xml`, `app/src/main/AndroidManifest.xml` (icon attributes only), `docs/assets/logo*.svg`, `docs/PHASES.md` (this row) | Vector-only adaptive icon (background, foreground inside the 66 dp safe zone, monochrome) wired as `icon` and `roundIcon`; `docs/assets/logo.svg` is the same mark with no raster, external reference or script; the app builds and lint passes | `CI` |
| P9.28 | App and APK named Phopencode | P1.10 | `app/src/main/res/values*/strings_app.xml` (`app_name`), `app/build.gradle.kts`, `docs/PHASES.md` (this row) | The launcher label is `Phopencode` in English and Turkish; the build writes `Phopencode-debug.apk` and `Phopencode-release.apk`; the Gradle project, modules, packages and application ID keep their names | `CI` |
| P9.29 | Public README | P9.27, P9.28 | `README.md`, `README.tr.md`, `docs/PHASES.md` (this row and the README entry of "Frequently conflicting files") | English `README.md` and Turkish `README.tr.md` with the same content and a language switch; centred logo from `docs/assets/logo.svg`, real CI and MIT badges; what and why, done features and P10–P12 roadmap, how it works over SSH, requirements, building from source, module map, security summary linking `SECURITY.md`, contributing, early status with no release, MIT license, not affiliated with opencode or SST; no real addresses or secrets; every relative link resolves | `LOCAL` |
| P9.30 | Files and git entry points | P5.3, P6.6, P8.5, P9.5 | `feature/chat/logic/…/interaction/contract/ActionsContract.kt`, `feature/chat/logic/…/actions/`, `feature/chat/ui/…/ChatScreen.kt`, `feature/chat/ui/…/ChatGraph.kt`, `feature/chat/ui/src/main/res/values*/strings_chat_actions.xml`, `feature/sessions/logic/…/contract/SessionListContract.kt`, `feature/sessions/logic/…/list/`, `feature/sessions/ui/…/list/`, `feature/sessions/ui/…/SessionsGraph.kt`, `feature/sessions/ui/src/main/res/values*/strings_sessions_list.xml`, their tests, `docs/PHASES.md` (this row) | The chat's top bar has "Open files" and "Open git" icon buttons (48 dp, named in TR/EN) for the session's `directory`, disabled until it is loaded; they open `FilesRoute.Tree(directory)` and `GitRoute.Status(directory)` through `ActionsIntent`/`ActionsEvent.OpenFiles`/`OpenGit`; the child session screen gets none. A session list limited to a folder shows the same two buttons before Refresh, through `SessionListIntent`/`SessionListEvent.OpenFiles`/`OpenGit`; the list of every folder hides them. Navigation only through `core/navigation` routes; boundary and string checks pass; logic, screen and graph tests | `CI` |
| P9.31 | Folder picker hides dot folders | P4.12, P9.25 | `feature/connect/logic/…/contract/FolderContract.kt`, `feature/connect/logic/…/folder/`, `feature/connect/ui/…/folder/`, `strings_connect_folder.xml` (EN and TR), their tests, `docs/PHASES.md` (this row) | Entries whose name starts with `.` are hidden by default; a 48 dp "Show hidden folders" toggle (named in TR/EN, with its on/off state exposed to accessibility) on the picker's top bar reveals them so they can be opened and selected, through `FolderIntent.SetShowHidden`, without listing over SFTP again; the choice holds while the picker is open, across navigation between folders and a refresh; recent folders and typed or selected paths are never filtered; when every entry is hidden the screen says so and offers to show them instead of looking empty; boundary and string checks pass; logic and screen tests | `CI` |
| P9.32 | Built-in slash commands in the chat | P7.8, P7.9, P7.11, P7.13, P7.15, P9.30 | `feature/chat/logic/…/interaction/contract/` (`BuiltInContract.kt`, `PaletteContract.kt`, `ActionsContract.kt`), `feature/chat/logic/…/palette/`, `feature/chat/logic/…/actions/`, `feature/chat/ui/…/palette/`, `feature/chat/ui/…/actions/SessionActionsMenu.kt`, `feature/chat/ui/…/ChatGraph.kt`, `feature/chat/ui/src/main/res/values*/strings_chat_palette.xml`, `strings_chat_actions.xml`, their tests, `docs/PHASES.md` (this row) | The `/` palette lists the app's built-ins between the server's commands and the skills, under the TUI's names with a "Built-in"/"Yerleşik" kind label, a short description and their aliases: `/new` (`/clear`) creates a session in the current session's folder with the server's defaults and switches to it; `/compact` (`/summarize`) and `/fork` request the existing actions with their confirmation; `/undo` opens the latest turn not already staged for revert on the turn diff page, whose revert still asks twice; `/redo` opens the staged revert's page, whose cancel drops it; `/models` and `/agents` open the switcher; `/sessions` (`/resume`, `/continue`) goes back to the session list on the back stack, else opens the folder's list in place of the chat. Built-ins match by a name or alias prefix and show while the server's lists load or after they fail. Sending a draft whose first word is a slash word goes to the palette: an exact server command name runs through `MessageApi.command` with the rest as text, else a built-in name or alias (any case) runs, else the draft is sent as a prompt; a server command of the same name wins, and hides the built-in in the list. Built-ins follow their buttons' enabled rules; one that cannot run keeps the draft and shows a TR/EN notice (unavailable, nothing to undo, nothing to redo); one that runs clears the draft. No new server API; boundary and string checks pass; logic, palette, actions and graph tests | `CI` |
| P9.33 | Skills from the chat | P7.8, P7.9, P6.6, P9.32 | `feature/chat/logic/…/interaction/contract/` (`PaletteContract.kt`, `PromptAttachment.kt`), `feature/chat/logic/…/contract/ComposerContract.kt`, `feature/chat/logic/…/palette/`, `feature/chat/logic/…/composer/`, `feature/chat/ui/…/palette/`, `feature/chat/ui/…/composer/`, `feature/chat/ui/…/ChatGraph.kt`, `strings_chat_palette.xml` and `strings_chat_composer.xml` (EN and TR, only if needed), `core/api/…/MessageApi.kt` (KDoc only), their tests, `docs/PHASES.md` (this row) | Skills are handled by `Skill.id`, the ID `PromptInput.SkillAttachment` takes, not by `Skill.name`: palette skill items carry both, show the name (plus `/id` when it differs, as id `opencode` for name `OpenCode`), match a query against both case-insensitively, and a pick attaches `PromptAttachment.Skill` by ID; chips and the timeline keep working. Sending a draft whose first slash word exactly equals a skill's ID (case-sensitive) and is neither a server command nor a built-in (server command > built-in > skill > plain prompt) sends the rest of the draft as a prompt with that skill attached, together with the composer's attachments, through the composer's normal send path (delivery, busy guard, errors), only while the composer still holds that draft, which clears once accepted; a still-loading skill list is waited for, sharing the palette's request, and skills that cannot be had fall through to a built-in or a plain prompt. A skill attached with nothing typed can be sent, as an empty `text`; no other send guard is weakened. Docs of the palette, attachment, composer and `Prompt.skills` say "by ID". No new server API; boundary and string checks pass; logic tests (id vs name, matching, pick, submit precedence, loading wait, skills failure, empty text) and UI/graph tests (submit to send, empty-text skill send) | `CI` |
| P9.34 | Visual refresh: theme, icons and shared components | P1.6, P1.9 | `core/ui/…/theme/`, `core/ui/…/component/`, `core/ui/…/icon/`, `core/ui/src/main/res/values*/strings_ui_common.xml` (EN and TR), `core/ui/src/test/**`, `app/src/main/res/values*/themes.xml` (window background only), `docs/PHASES.md` (these rows) | New light and dark schemes keep the logo's teal as primary, add a warm coral tertiary accent, softly teal-tinted neutrals, a deep ink (not black) dark background and clearly layered surface containers so cards stand out; every role is set explicitly in both schemes, every existing contrast check still passes and new checks cover the layering, the accent and icon-in-container pairs; status colors are retuned with their checks kept. `PhopencodeShapes` (6, 10, 16, 24, 28 dp) is passed to `MaterialTheme`; typography stays on the system font in `sp` with semi-bold titles and headlines and medium labels, code stays monospace; spacing tokens keep their names and values. `PhopencodeIcons` is a lazy set of 24 dp vectors for the feature screens (Send, Stop, Add, Folder, FolderOpen, Git, Terminal, Chat, Server, Key, Settings, MoreVert, Refresh, Sparkle, Agent, Attach, File, Check, Close, ChevronRight, Edit, History, Search, Warning, Info) with no new icon dependency. `PhopencodeScaffold` stays source-compatible and adds an optional one-line `subtitle`; `EmptyState` stays source-compatible and takes an optional `icon` in a soft tinted circle; `ErrorBanner`, `StatusChip`, `Loading` and `ConfirmDialog` are restyled with the same API, semantics and 48 dp targets. The app window matches the new background in light and dark. Light and dark both work; theme, token, icon and component tests | `CI` |
| P9.35 | Chat top bar and composer redesign | P9.34, P9.33 | `feature/chat/ui/…/ChatScreen.kt`, `…/composer/`, `…/switcher/`, `…/palette/`, `…/usage/UsageBar.kt`, `…/actions/SessionActionsMenu.kt`, `…/subagent/SubagentBadge.kt`, `strings_chat_composer.xml`, `strings_chat_switcher.xml`, `strings_chat_usage.xml`, `strings_chat_actions.xml`, `strings_chat_palette.xml` (EN and TR), their tests, `docs/PHASES.md` (this row) | The chat gets a top bar with a back arrow when a screen is under it, the session's title and a one-line usage subtitle that opens the usage detail, then the subagent badge, files, git and the action menu as icons; the composer is one rounded card: a filled field with a placeholder hint for `/` and `@` instead of a floating label, a round icon button that sends (arrow up) or stops (square), named for TalkBack in TR/EN, and the agent and model as compact chips under the field; steer/queue shows only while running as two small chips with no heading; the palette is a rounded card with a kind icon per item and section headers; attachment chips show a kind icon. Behaviour, enabled rules, tags and accessibility semantics are unchanged; logic is untouched; boundary and string checks pass; screen and graph tests | `CI` |
| P9.36 | Chat timeline and cards redesign | P9.34, P9.33 | `feature/chat/ui/…/timeline/`, `…/reasoning/`, `…/tool/`, `…/permission/`, `…/form/`, `…/status/StatusBanner.kt`, `strings_chat_timeline.xml`, `strings_chat_tool.xml`, `strings_chat_reasoning.xml`, `strings_chat_permission.xml`, `strings_chat_form.xml`, `strings_chat_status.xml` (EN and TR), their tests, `docs/PHASES.md` (this row) | Prompts stay end-side bubbles and replies full-width text, without the visible "You"/"Assistant" captions (TalkBack still hears who wrote each); a successful turn end draws nothing, a failed or interrupted one a compact tinted chip; tool calls are one compact row each (icon, tool name, short target, state) that expands to the details; reasoning is a collapsed "Thinking"/"Thought" row that expands; permission and form cards are rounded, accent-bordered cards with one clear primary button; the status banner is a slim tinted strip; an empty timeline shows a friendly welcome with hints for `/` (commands, skills) and `@` (files, agents). Behaviour, tags and accessibility semantics are unchanged; logic is untouched; boundary and string checks pass; screen tests | `CI` |
| P9.37 | Server and session lists redesign | P9.34 | `feature/connect/ui/…/serverlist/`, `feature/sessions/ui/…/list/`, `feature/sessions/ui/…/create/`, `strings_connect_serverlist.xml`, `strings_sessions_list.xml`, `strings_sessions_create.xml` (EN and TR), their tests, `docs/PHASES.md` (this row) | Servers and sessions are rounded cards: a server shows a letter avatar, its name, its address muted and its mode; a session its title, relative time, its folder when the list spans folders and a running mark; top bar actions are named icons instead of text buttons; the main action is an extended FAB ("Add server", "New chat"); empty lists show `EmptyState` with an icon, a one-line explanation and the main action; the create screen groups its fields in cards. Behaviour, tags and accessibility semantics are unchanged; logic is untouched; boundary and string checks pass; screen tests | `CI` |

`CP9`: push, CI green, `PHONE`: commit and push in a test repository.

### P10: Catalog and settings

**G1**

| Task | Goal | Depends on | Owned paths | Acceptance tests | Test |
|---|---|---|---|---|---|
| P10.1 | `catalog` and `settings` contract | P3.9, P1.10 | `feature/catalog/logic/…/contract/`, `feature/settings/logic/…/contract/`, `feature/catalog/ui/…/CatalogGraph.kt`, `feature/catalog/ui/…/CatalogModule.kt`, `feature/settings/ui/…/SettingsGraph.kt`, `feature/settings/ui/…/SettingsModule.kt`, screen stubs | Contract outputs complete | `PI` |

**G2 (parallel)**

| Task | Goal | Depends on | Owned paths | Acceptance tests | Test |
|---|---|---|---|---|---|
| P10.2 | Skill logic | P10.1 | `feature/catalog/logic/…/skills/` | List cache; search; source path; add to a new prompt | `PI` |
| P10.3 | Skill screen | P10.1, P6.2 | `feature/catalog/ui/…/skills/`, `strings_catalog_skills.xml` | List; Markdown details | `CI` |
| P10.4 | Command, agent, model logic | P10.1 | `feature/catalog/logic/…/registry/` | Three lists; agent permissions; model limits and pricing | `PI` |
| P10.5 | Command, agent, model screen | P10.1 | `feature/catalog/ui/…/registry/`, `strings_catalog_registry.xml` | Tabs; details | `CI` |
| P10.6 | MCP and plugin logic | P10.1 | `feature/catalog/logic/…/status/` | MCP connection status; plugin list; view only | `PI` |
| P10.7 | MCP and plugin screen | P10.1 | `feature/catalog/ui/…/status/`, `strings_catalog_status.xml` | Status badges; error text | `CI` |

**G3 (parallel)**

| Task | Goal | Depends on | Owned paths | Acceptance tests | Test |
|---|---|---|---|---|---|
| P10.8 | Settings logic | P10.1 | `feature/settings/logic/…/prefs/` | Language (system, TR, EN), theme, file size limit, delete data | `PI` |
| P10.9 | Settings screen | P10.1 | `feature/settings/ui/…/prefs/`, `strings_settings_prefs.xml` | Language changes instantly; data deletion needs confirmation | `CI` |

`CP10`: push, CI green.

### P11: Notifications

**G1 (parallel)**

| Task | Goal | Depends on | Owned paths | Acceptance tests | Test |
|---|---|---|---|---|---|
| P11.1 | Service config | P0.1 | `helper/notifyd/config.py`, `helper/tests/test_config.py` | Rejects the file if it is not 0600; error on a missing field; the password never enters logs | `PY` |
| P11.2 | SSE client | P0.1 | `helper/notifyd/sse.py`, `helper/tests/test_sse.py` | Basic auth; comment lines; `types` filter; exponential reconnect; with a local test server | `PY` |
| P11.3 | ntfy sender | P0.1 | `helper/notifyd/ntfy.py`, `helper/tests/test_ntfy.py` | Title, priority, Click; timeout; retry; HTTPS only (except in tests) | `PY` |
| P11.4 | Event rules | P0.1, P1.7 | `helper/notifyd/rules.py`, `helper/tests/test_rules.py` | `form.created`, `permission.asked`, root session finish and error are notified; fixed text; sentinel text in an event never appears in the output; throttling works | `PY` |

**G2 (parallel)**

| Task | Goal | Depends on | Owned paths | Acceptance tests | Test |
|---|---|---|---|---|---|
| P11.5 | Service main loop and package | P11.1, P11.2, P11.3, P11.4 | `helper/notifyd/__main__.py`, `helper/tests/test_main.py`, `scripts/build_helper.py` | `zipapp` produces a single file; stdlib only; shuts down cleanly on SIGTERM; waits while there is no server; `ast.parse(feature_version=(3, 9))` test passes | `PY` |
| P11.6 | Install scripts | P11.1 | `helper/install.sh`, `helper/uninstall.sh`, `helper/tests/test_install.py` | `systemd --user`; otherwise `cron @reboot`; tested with a temporary `HOME`; uninstall leaves no trace | `PY` |
| P11.7 | `notify` contract | P3.9, P1.10 | `feature/notify/logic/…/contract/`, `feature/notify/ui/…/NotifyGraph.kt`, `feature/notify/ui/…/NotifyModule.kt`, screen stubs | Contract outputs complete | `PI` |

**G3 (parallel)**

| Task | Goal | Depends on | Owned paths | Acceptance tests | Test |
|---|---|---|---|---|---|
| P11.8 | Remote installation | P11.6, P3.2, P3.4 | `core/remote/…/notify/`, `feature/notify/ui/build.gradle.kts` | `python3` version is checked; upload, 0600 config, start, status, removal; clear error if missing; `.pyz` is produced by a Gradle task under `build/` and does not enter the repository | `PI` |
| P11.9 | Notification logic | P11.7 | `feature/notify/logic/…/setup/` | Random topic of at least 128 bits; in `SecretStore`; never enters logs; test notification | `PI` |
| P11.10 | Notification screen | P11.7 | `feature/notify/ui/…/setup/`, `strings_notify_setup.xml` | The ntfy requirement is explained; `ntfy://` subscription; status | `CI` |
| P11.11 | Pending items logic | P11.7 | `feature/notify/logic/…/pending/` | Forms and permissions from all sessions are combined; removed once answered | `PI` |
| P11.12 | Pending items screen and deep link | P11.7 | `feature/notify/ui/…/pending/`, `feature/notify/ui/src/main/AndroidManifest.xml`, `strings_notify_pending.xml` | `phopencode://pending` opens the screen; tapping goes to the session | `EMU` |

`CP11`: push, CI green, `PHONE`: receive a real notification, tap it and switch to the app.

### P12: Release

**G1**

| Task | Goal | Depends on | Owned paths | Acceptance tests | Test |
|---|---|---|---|---|---|
| P12.1 | Accessibility and visual review | P11.12, P10.9, P9.15, P8.15, P7.15 | `feature/*/ui/`, `core/ui/` | Content descriptions; large text; 48 dp targets; TalkBack order; empty and error states on all screens | `CI` |

**G2 (parallel)**

| Task | Goal | Depends on | Owned paths | Acceptance tests | Test |
|---|---|---|---|---|---|
| P12.2 | Icon and splash screen | P1.10 | `app/src/main/res/mipmap-*/`, `app/src/main/res/drawable/`, `app/src/main/res/values/ic_launcher_background.xml`, `app/src/main/res/values*/themes.xml` | Adaptive icon; splash screen | `CI` |
| P12.3 | License notices | P10.9 | `THIRD_PARTY_NOTICES.md`, `feature/settings/ui/…/licenses/`, `strings_settings_licenses.xml` | All dependencies are listed; LGPL notice; TextMate grammar licenses; viewable on a screen | `CI` |
| P12.4 | Release hardening | P3.10 | `app/build.gradle.kts`, `app/proguard-rules.pro`, `app/src/main/res/xml/data_extraction_rules.xml` | R8 on; backup off; no logs in the release; the `release` build establishes an SSH connection on the emulator | `EMU` |

**G3 (parallel)**

| Task | Goal | Depends on | Owned paths | Acceptance tests | Test |
|---|---|---|---|---|---|
| P12.5 | Release workflow `[U]` (signing key) | P12.4 | `.github/workflows/release.yml`, `docs/dev-setup/release.md` | Triggered by a `v*` tag; keystore comes from Secrets and never enters logs; signed APK and SHA-256 are added to the Release; minimal permissions | `PY` |
| P12.6 | User documentation | P11.12, P10.9, P9.15, P8.15 | `README.md`, `docs/INSTALL.md`, `docs/SECURITY-MODEL.md` | Installation, server requirements, ntfy, security model; TR+EN | `LOCAL` |
| P12.7 | End-to-end test | P12.4 | `app/src/androidTest/…/e2e/`, `.github/workflows/emulator.yml` | sshd and opencode on the runner; connect, open/delete a session, upload a file, git status; no LLM | `EMU` |

**G4**

| Task | Goal | Depends on | Owned paths | Acceptance tests | Test |
|---|---|---|---|---|---|
| P12.8 | Release audit | P12.5, P12.6, P12.7 | `scripts/audit_release.py`, `scripts/tests/test_audit_release.py` | Secret scan across the full history; license headers; no analytics or tracking; network targets are SSH and ntfy only | `PY` |

**G5**

| Task | Goal | Depends on | Owned paths | Acceptance tests | Test |
|---|---|---|---|---|---|
| P12.9 | v0.1.0 preparation `[U]` | P12.8 | `CHANGELOG.md`, `app/build.gradle.kts` | Version number; change list; the user creates the tag, the APK appears in the Release, `PHONE`: clean install | `LOCAL` |

`CP12`: the user pushes the `v0.1.0` tag.

## Parallelism summary

| Phase | Tasks | Groups (task count) | Widest group | On Pi | CI/EMU only |
|---|---|---|---|---|---|
| P0 | 7 | 3, 1, 3 | 3 | 6 | 1 |
| P1 | 10 | 1, 6, 2, 1 | 6 | 7 | 3 |
| P2 | 11 | 1, 4, 4, 2 | 4 | 11 | 0 |
| P3 | 10 | 1, 4, 3, 1, 1 | 4 | 9 | 1 |
| P4 | 15 | 3, 6, 6 | 6 | 7 | 8 |
| P5 | 7 | 1, 6 | 6 | 4 | 3 |
| P6 | 14 | 2, 6, 6 | 6 | 7 | 7 |
| P7 | 15 | 1, 6, 6, 2 | 6 | 8 | 7 |
| P8 | 15 | 3, 6, 6 | 6 | 8 | 7 |
| P9 | 15 | 3, 6, 6 | 6 | 9 | 6 |
| P10 | 9 | 1, 6, 2 | 6 | 5 | 4 |
| P11 | 12 | 4, 3, 5 | 5 | 10 | 2 |
| P12 | 9 | 1, 3, 3, 1, 1 | 3 | 4 | 5 |
| Total | 149 | 45 groups | 6 | 95 | 54 |

`logic` and `ui` tasks run at the same time after the contract. The group limit is for the Pi; on a stronger machine, consecutive groups of the same phase can be merged, since the dependency column is sufficient.

Phases run in numerical order. For a phase to finish, its checkpoint must also be green.

## Traceability

| No | Request | Tasks |
|---|---|---|
| 1 | Connecting through `opencode serve` and running the project | P2.1-P2.11, P3.7-P3.9, P4.12-P4.15, P6.3-P6.6, P6.9, P6.10, P6.13, P6.14 |
| 2 | Using and seeing all features | P2.7, P7.4-P7.5, P7.8-P7.15, P10.2-P10.7 |
| 3 | Token usage | P1.1, P2.9, P5.2, P6.11, P6.12 |
| 4 | AI reasoning | P2.9, P6.7, P6.8 |
| 5 | Viewing folders, highlighting, Markdown | P8.1, P8.3-P8.7, P8.14, P8.15, P6.2 |
| 6 | SSH connection | P3.1-P3.10, P4.1, P4.2, P4.4-P4.11 |
| 7 | Opening sessions, seeing subagents in detail | P5.4, P5.5, P7.6, P7.7 |
| 8 | Nice UI | P1.6, P1.9, P4.5, P6.4, P12.1, P12.2 |
| 9 | Answering multiple-choice questions | P2.6, P7.2, P7.3 |
| 10 | Creating and deleting sessions | P2.4, P5.2-P5.7 |
| 11 | File transfer | P3.4, P8.8-P8.13 |
| 12 | Managing Git changes and history | P2.8, P9.1-P9.15 |
| Extra | Notification service | P11.1-P11.12 |
| Extra | Turkish/English UI | P0.7, P1.10, P10.8, P10.9, the `strings_*.xml` pair of each screen |
| Extra | GitHub Actions and Releases | P0.6, P3.10, P12.5, P12.7, P12.9 |
| Extra | MIT, README, repository skeleton | P0.1, P12.3, P12.6 |
| Extra | Security and release audit | P12.4, P12.8 |
| Extra | Infrastructure and contracts | P0.2-P0.5, P1.2-P1.5, P1.7, P1.8, P4.3, P5.1, P6.1, P7.1, P8.2, P10.1 |

## Out of scope (first release)

- Terminal from the phone (PTY).
- Git stash, merge conflict resolution, rebase.
- Adding or connecting MCP servers, provider credential management.
- UnifiedPush and built-in push; notifications arrive through the ntfy app.
- Building the APK on the Pi.
- opencode V1 support (if D1 went the other way, the P2 and P11 endpoints would change according to the V1 column in the notes; the task structure stays the same).
