<p align="center">
  <img src="docs/assets/logo.svg" alt="Phopencode logo" width="128" height="128">
</p>

<h1 align="center">Phopencode</h1>

<p align="center">
  <strong>Use opencode on your own server from your Android phone, over SSH.</strong>
</p>

<p align="center">
  <a href="https://github.com/thewise-kd/Phopencode/actions/workflows/ci.yml"><img src="https://github.com/thewise-kd/Phopencode/actions/workflows/ci.yml/badge.svg" alt="CI"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-blue.svg" alt="License: MIT"></a>
</p>

<p align="center">
  <strong>English</strong> | <a href="README.tr.md">Türkçe</a>
</p>

> [!NOTE]
> **Download:** signed APKs and their SHA-256 hashes are published on
> [GitHub Releases](https://github.com/thewise-kd/Phopencode/releases); the latest release is
> listed there. What changed in each version is in [CHANGELOG.md](CHANGELOG.md). You can also
> [build the app from source](#building-from-source).

> [!WARNING]
> **Early stage:** Phopencode is a new app at an early stage (v0.1.0). Expect bugs and missing
> features, and behaviour may change between versions. Please report problems on
> [GitHub Issues](https://github.com/thewise-kd/Phopencode/issues).

<!--
Screenshots: none yet. Add them here once they exist, for example:
<p align="center"><img src="docs/assets/screenshot-chat.png" alt="Chat screen" width="260"></p>
-->

## What it is

Phopencode is an Android app for working with [opencode](https://opencode.ai) when it runs on a
server you control. You keep opencode, your code and your credentials on the server; the phone
connects to it over SSH and gives you the opencode sessions, the chat, the project files and git
in a native, touch-friendly app.

Why: an agent can run for a long time, ask questions and wait for permissions. Phopencode lets
you follow it, answer it and review what it changed when you are away from your computer, without
opening any port of your server to the internet.

## Documentation

- [Installation and setup](docs/INSTALL.md): what the phone and the server need, installing and
  checking the APK, the first connection, and notifications with ntfy.
- [Security model](docs/SECURITY-MODEL.md): what is stored where, what goes over the network,
  host key trust, and what an attacker could do.
- [Security policy](SECURITY.md): how to report a vulnerability.

## Features

In this release ([CHANGELOG.md](CHANGELOG.md) lists every change):

**Connection and servers**

- Server profiles: host, port, user, and an SSH key or password; an optional fixed opencode port.
- SSH keys: generate ed25519 keys, import OpenSSH keys (passphrase-protected ones too), see the
  fingerprint and copy the public key.
- Host key verification: you approve the SHA-256 fingerprint on the first connection; if it
  changes later, the connection stops with a warning.
- Finds a running `opencode serve` (also in `~/.opencode/bin` when it is not on `PATH`) and starts
  one, bound to `127.0.0.1`, only when none runs.
- Reconnects after a dropped connection; a folder picker over SFTP with recent folders.

**Sessions and chat**

- Session list with search, status, token usage and cost; create (folder, agent, model, title),
  rename, archive and delete.
- Live streaming chat with Markdown and highlighted code blocks.
- Reasoning, tool call and token/context usage cards; a status banner for running, retrying and
  disconnected states.
- Send while the agent is running (steer or queue) and stop it.
- Answer the agent's questions (single choice, multiple choice, free text) and permission
  requests (once, always, reject).
- Follow subagents as a live tree and open their sessions.
- Command palette (`/` for commands and skills, `@` for agents and files), agent and model
  switching, per-turn diff and revert, compaction and fork.

**Files**

- Browse the project, view files with syntax highlighting, rendered Markdown and image preview.
- Edit and save, with a warning when the file changed on the server meanwhile.
- Rename, delete, create folders; search by name and by content (`rg`, or `grep` without it).
- Upload and download through the system file picker, with progress in a notification.

**Git**

- Status with staged, unstaged and untracked files, ahead/behind and upstream state.
- Stage, unstage, discard changes (after confirmation) and commit.
- Diffs, history with commit details, and branches (create, switch, delete).
- Fetch, pull (fast-forward only) and push. The app never force-pushes.

**Catalog**

- Skills with search and their Markdown text; commands, agents (with their permissions) and
  models (with limits and pricing); MCP server and plugin status. View only.

**Notifications**

- Push notifications through [ntfy](https://ntfy.sh) when the agent asks something, needs a
  permission, finishes or fails, sent by a small, dependency-free Python service on the server.
  The notification text is fixed and carries no code, paths or AI output.
- Install, test and remove the service from the app; `ntfy.sh` by default or your own ntfy
  server; a private random topic that the app hands to the ntfy app.
- A pending items screen that lists every question and permission request waiting in any
  session; tapping a notification opens it.

**General**

- Turkish and English interface following the system language or chosen in the settings; light,
  dark or system theme.
- Settings for the file size limit and deleting all app data; open source license notices.

### Not included

Not included in this release: a terminal, git stash/merge conflict resolution/rebase, and
managing MCP servers or provider credentials
([full list](docs/PHASES.md#out-of-scope-first-release)).

## How it works

```text
 Android phone                                  Your server
+---------------+        SSH (port 22)        +---------------------------------------+
|  Phopencode   | ==========================> | sshd                                  |
|               |   exec:  find or start      |  |- opencode serve on 127.0.0.1:<port>|
|               |          opencode, git, rg  |  |- git, rg/grep in your project      |
|               |   SFTP:  files              |  '- your files                        |
|               |   tunnel: opencode HTTP API |                                       |
+---------------+   and event stream          +---------------------------------------+
```

- Everything goes through one SSH connection. The opencode HTTP API and its live event stream
  travel through an SSH tunnel to `127.0.0.1` on the server.
- `opencode serve` listens on loopback only, so nothing new is exposed to the internet. On the
  phone the tunnel opens no local port either, so other apps cannot reach it.
- When the app starts `opencode serve`, it protects it with a random password that is passed via
  stdin, never on the command line.
- Files use SFTP, confined to the folder you opened. Git and content search run as ordinary
  commands over SSH, with every argument shell-quoted.
- Notifications, when you install them, come from a service on the server that watches opencode
  and posts fixed text to ntfy over HTTPS; the ntfy app on the phone shows them.

### Server requirements

- SSH access with a key or a password.
- [opencode](https://opencode.ai) 2.2 or newer installed for that user.
- `git` for the git screens; `rg` (ripgrep) is optional, `grep` is used without it.
- For notifications only: `python3` 3.9 or newer, and `systemd --user` with lingering or
  `crontab` to keep the service running.

The details, and what the app does on the server, are in [docs/INSTALL.md](docs/INSTALL.md).

## Requirements

- An Android phone with Android 8.0 (API 26) or newer.
- A server as described in [Server requirements](#server-requirements).
- For notifications, the [ntfy](https://ntfy.sh) app on the phone.

## Building from source

Instead of installing a release APK, you can build a debug APK yourself.

You need:

- JDK 21 ([docs/dev-setup/jdk.md](docs/dev-setup/jdk.md)).
- The Android SDK with platform 37 (for example from Android Studio).
- `ANDROID_HOME` (or `ANDROID_SDK_ROOT`) set to the SDK folder, or `sdk.dir` in
  `local.properties`. Without an SDK, Gradle only includes the JVM modules.

```sh
git clone https://github.com/thewise-kd/Phopencode.git
cd Phopencode
export ANDROID_HOME="$HOME/Android/Sdk"   # your SDK folder

./gradlew :app:assembleDebug              # APK in app/build/outputs/apk/debug/
adb install -r app/build/outputs/apk/debug/*.apk
```

Tests and checks:

```sh
./gradlew jvmCheck                        # all JVM module tests; needs no Android SDK
./gradlew :core:api-http:test             # a single module
./gradlew testDebugUnitTest lint          # Android unit tests and lint; needs the SDK
python3 scripts/check_boundaries.py       # module boundaries and secret patterns
python3 scripts/check_strings.py          # Turkish/English string parity
```

`gradle/verify-wrapper.sh --online` compares the Gradle wrapper with the hashes Gradle publishes.

## Project structure

The logic lives in plain Kotlin/JVM modules that are tested without Android; Compose screens live
in thin Android modules.

| Path | Contents |
|---|---|
| `app/` | Entry point, navigation, dependency injection (Koin) |
| `core/model`, `core/api` | Domain types; opencode API interfaces and events |
| `core/api-http` | opencode HTTP client and event stream (OkHttp) |
| `core/session` | Event reducer and resync after a reconnect |
| `core/ssh` | SSH, remote commands, SFTP, tunnel, shell quoting (sshj) |
| `core/remote` | Server discovery and startup, connection manager |
| `core/files`, `core/git` | File service; git commands and parsers |
| `core/storage-api`, `core/storage` | Storage interfaces; encrypted storage (Keystore, Tink, DataStore) |
| `core/navigation`, `core/ui`, `core/markdown`, `core/codeview` | Routes, theme and shared components, Markdown, code viewer |
| `feature/<name>/logic`, `feature/<name>/ui` | Each feature's state and presenters (JVM) and its Compose screens (Android) |
| `helper/` | The notification service (Python) and its install and uninstall scripts |
| `build-logic/` | Gradle convention plugins |

Features: `connect`, `sessions`, `chat`, `files`, `git`, `catalog`, `notify` and `settings`.

## Security

- **Host keys are verified.** A server's key is approved on the first connection; a changed key
  stops the connection.
- **Secrets are encrypted on the phone.** Passwords and private keys are stored with Tink under a
  key in the Android Keystore; profiles hold no passwords. App data is excluded from cloud backup
  and device transfer.
- **Nothing is exposed.** opencode listens on `127.0.0.1` only and is reached through the SSH
  tunnel; the phone opens no local port.
- **The app does not take over your server.** It never uses `sudo`, never stops, kills or
  replaces an `opencode serve` that is already running, and starts one only when none runs. The
  only processes it stops are its own: a command you cancel, or a server it just launched that
  failed to come up.
- **Git is safe by default.** No force push, no `git reset --hard`, no `git clean`; pull is
  fast-forward only; discarding changes needs confirmation.
- **Notifications stay private.** Their text is fixed, the ntfy topic is random and kept
  encrypted, and the service's settings file on the server is readable by your user only.
- **No analytics or tracking.** Logs never contain passwords, keys or file contents, and the
  release build strips the calls to Android's log.

The full picture is in [docs/SECURITY-MODEL.md](docs/SECURITY-MODEL.md). To report a vulnerability, see [SECURITY.md](SECURITY.md). Please do not open a public issue.

## Contributing

Issues and pull requests are welcome. Before opening a pull request:

- Run `./gradlew jvmCheck` and the two `scripts/check_*.py` checks; CI runs the Android tests.
- Add every user-facing string in both English (`values/`) and Turkish (`values-tr/`).
- Keep Android dependencies out of the JVM modules.
- Never include real server addresses, keys, passwords or other secrets, in code, tests or issues;
  use placeholders such as `example.com`.

The task plan and the conventions are in [docs/PHASES.md](docs/PHASES.md).

## License

[MIT](LICENSE) © 2026 thewise-kd

## Disclaimer

Phopencode is an independent project. It is not affiliated with, endorsed by or sponsored by the
opencode project or SST. "opencode" is used only to name the software this app connects to.

The software is provided "as is", without warranty of any kind; see the [LICENSE](LICENSE). Back
up important work on your server before you rely on the app.
