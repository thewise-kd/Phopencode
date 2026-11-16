# Changelog

All notable changes to Phopencode are listed in this file, newest first.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[Semantic Versioning](https://semver.org/spec/v2.0.0.html). Each version's heading names the
version the app reports (its `versionName`) and the date of the release; the version links to
its release page, where the signed APK and its SHA-256 hash are published.

## [Unreleased]

### Added

#### Live preview

- See a web app that runs on your server, for example a dev server you started with
  `npm run dev`, live in the app, through the same SSH connection. Nothing new is exposed on the
  server's network.
- Open it from the chat's session action menu or the session list's top bar menu, while connected.
- A port list with the server's listening TCP ports that can be reached on its loopback, read
  with `ss -ltnH` or, without `ss`, from `/proc/net/tcp` and `/proc/net/tcp6`. The opencode
  server's own port is shown but cannot be opened. A port can also be typed by hand.
- Reload from the top bar or by pulling the page down; hot reload of dev servers such as Vite and
  webpack works, and so do forms and requests with a body.
- A console panel with the page's console messages, load errors, HTTP errors and refused
  requests, a level filter and an error count on its button. It is kept in memory only and is
  cleared when the preview closes.
- A mobile and desktop viewport toggle; desktop sends a desktop user agent, lays the page out
  wide and allows zoom.
- The page runs in an isolated WebView: no file access, no camera, microphone or location, no
  downloads, and its cookies, storage, cache and history are deleted when the preview closes. It
  can load nothing off its own origin, and another address opens outside the app only after you
  confirm it.
- While the preview screen is open, a temporary bridge listens on the phone's `127.0.0.1`, on a
  random port, and refuses every connection that does not present a random per-session secret.
  This is the one exception to "the phone opens no local port".
- The app never starts, stops or kills a process for the preview: you, or opencode, start the dev
  server.
- A short notice before the first preview, shown once before the port list: start the dev server
  on `127.0.0.1` or `localhost`, not `0.0.0.0`; open only projects you trust; do not use real
  accounts, passwords, tokens or personal data in the preview page; check the address of a link
  that leaves the preview; the app never starts or stops the dev server and clears the preview's
  data when you leave it. "I have read and understood" hides it for good; "Not now" leaves the
  preview, and the notice appears again on the next visit.

#### First-run usage guide

- A short usage guide opens on the first launch: what the app does, what the server needs, adding
  a server, sessions and chat, files and Git, notifications and the live preview. Skip or Start
  ends it for good; Settings, then "Usage guide", opens it again at any time.
- "Delete all data" in Settings shows the usage guide and the live preview notice again.

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

[Unreleased]: https://github.com/thewise-kd/Phopencode/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/thewise-kd/Phopencode/releases/tag/v0.1.0
