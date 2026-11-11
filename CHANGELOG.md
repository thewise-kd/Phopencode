# Changelog

All notable changes to Phopencode are listed in this file, newest first.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[Semantic Versioning](https://semver.org/spec/v2.0.0.html). Each version's heading names the
version the app reports (its `versionName`) and the date of the release; the version links to
its release page, where the signed APK and its SHA-256 hash are published.

## [0.1.0] - 2026-11-11

The first public release: work with opencode running on your own server from an Android phone
(Android 8.0 or newer), over SSH. The server needs opencode 2.2 or newer.

### Added

#### Connection and servers

- Server profiles with a name, host, SSH port, user and a password or SSH key, an optional
  password for an `opencode serve` you started yourself, and an optional fixed opencode port.
- SSH key management: generate ed25519 keys on the phone (with an optional passphrase), import
  OpenSSH and older PEM keys (ed25519, RSA, ECDSA, also passphrase-protected ones), see each key's
  SHA-256 fingerprint and copy its public key.
- Host key verification: you approve the server's SHA-256 fingerprint on the first connection;
  a changed key stops the connection with a warning showing both fingerprints.
- Finds a running `opencode serve` of your user, also when `opencode` is not on the `PATH`, and
  starts one bound to the server's loopback address, protected with a random password, only when
  none runs. An `opencode serve` you started yourself is never stopped or replaced.
- The connection steps are shown as they happen, with a clear message when one fails.
- Reconnects after a dropped connection and catches up on what happened meanwhile.
- A project folder picker over SFTP, with recent folders; dot folders are hidden.

#### Sessions

- Session list with search, status, token usage and cost.
- Create a session with a folder, agent, model and title; rename, archive and delete sessions.

#### Chat

- Live streaming chat with Markdown and syntax-highlighted code blocks.
- Cards for reasoning, tool calls and token and context usage; a status banner for running,
  retrying and disconnected states.
- Send while the agent is running, to steer it or to queue the message, and stop it.
- Command palette: `/` for commands, opencode's built-in commands and skills, `@` for agents and
  files.
- Switch the agent and the model; per-turn diffs and revert; compaction and fork.
- Open the project's files and git from the chat and the session list.

#### Questions, permissions and subagents

- Answer the agent's questions: single choice, multiple choice and free text.
- Answer permission requests: once, always or reject.
- Follow subagents as a live tree and open their sessions.

#### Files

- Browse the project; view files with syntax highlighting, rendered Markdown and image preview.
- Edit and save, with a warning when the file changed on the server meanwhile.
- Rename, delete and create folders; search by name and by content (`rg`, or `grep` without it).
- Upload and download through the system file picker, with progress in a notification.
- A configurable size limit for the files the app opens.

#### Git

- Status with staged, unstaged and untracked files, ahead/behind counts and the upstream state,
  including an upstream that was deleted.
- Stage, unstage, discard changes (after confirmation) and commit.
- Diffs with colored lines, history with commit details, and branches (create, switch, delete).
- Fetch, pull (fast-forward only) and push (after confirmation). The app never force-pushes.

#### Catalog

- Skills with search and their Markdown text.
- Commands, agents with their permissions, and models with their limits and pricing.
- The status of MCP servers and plugins. The catalog is view only.

#### Notifications

- Push notifications through the [ntfy](https://ntfy.sh) app when the agent asks a question,
  needs a permission, finishes or fails. They are sent by a small, dependency-free Python service
  on the server, with fixed text that carries no code, paths or AI output, and with a rate limit.
- Install, test, reinstall and remove the service from the app, with `systemd --user` or
  `crontab`; no root needed.
- `ntfy.sh` by default, or your own ntfy server over HTTPS.
- A private random topic that the app keeps encrypted and hands to the ntfy app through a link,
  so you never type or copy it.
- A pending items screen listing every question and permission request waiting in any session;
  tapping a notification opens it.

#### Settings and general

- Turkish and English interface, following the system language or chosen in the settings.
- Light, dark or system theme; a refreshed design with the Phopencode logo, launcher icon and
  splash screen.
- Layouts that work with large text and screen readers.
- Delete all app data from the settings; open source license notices.

#### Security and privacy

- Passwords, private keys, key passphrases and the ntfy settings are encrypted on the phone with
  a key held in the Android Keystore; app data is excluded from cloud backup and device transfer.
- Everything goes through one SSH connection: the opencode API through an SSH tunnel, files over
  SFTP confined to the opened folder, and git and search as shell-quoted commands. Nothing new
  is exposed on the server, and the phone opens no local port.
- The app never uses `sudo`, and git is safe by default: no force push, no `git reset --hard`,
  no `git clean`.
- No analytics, crash reporting, advertising or tracking. Logs never contain passwords, keys or
  file contents.

#### Release and distribution

- Signed release APK on GitHub Releases, built from the version tag by a GitHub Actions
  workflow, with its SHA-256 hash for checking the download.
- The release build is shrunk with R8 and removes every call to Android's log.
- Installation guide and security model in English and Turkish.

### Known limits

- No terminal on the phone.
- No git stash, merge conflict resolution or rebase.
- MCP servers and provider credentials cannot be added or managed from the app.
- Notifications arrive only through the ntfy app; there is no built-in push or UnifiedPush.
- After `opencode serve` restarts on the server, the notification service has to be reinstalled
  from the app.

[0.1.0]: https://github.com/thewise-kd/Phopencode/releases/tag/v0.1.0
