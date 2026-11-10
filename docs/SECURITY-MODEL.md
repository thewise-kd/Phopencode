# Security model

<p><strong>English</strong> | <a href="SECURITY-MODEL.tr.md">Türkçe</a></p>

This document explains what Phopencode protects, how, and where its protection ends: what is
stored on the phone, what travels over the network, how server keys are trusted, and what someone
who gets hold of your phone, your server or your ntfy topic can do. Setup is described in
[INSTALL.md](INSTALL.md); vulnerabilities are reported as described in
[SECURITY.md](../SECURITY.md).

## In short

- The phone talks only to your server, and only over SSH. The opencode API is reached through
  that SSH connection; `opencode serve` listens on `127.0.0.1` only.
- A server's host key is approved by you on the first connection; a changed key stops the
  connection.
- Passwords, private keys, key passphrases and the ntfy settings are encrypted on the phone with
  a key held in the Android Keystore, and nothing the app stores goes into a backup.
- Notifications travel from your server through ntfy and carry fixed text only.
- There is no analytics, crash reporting, advertising or tracking of any kind.

## Threat model

### What is protected

- Your SSH credentials: passwords, private keys and their passphrases.
- The passwords of your `opencode serve` servers.
- Your code, sessions and AI conversations, which stay on your server.
- The ntfy topic, which gives access to your notifications.
- Your server itself, which the app must not damage or take over.

### What is trusted

- **Your server and its SSH user.** The app acts as that user and shows what the server sends.
  Whoever controls the server controls what the app sees.
- **Android and the phone's lock screen.** The app relies on Android's app sandbox, its
  file-based encryption and the Android Keystore. It has no lock of its own.
- **The SSH host key you approve.** If you approve an attacker's key, the protection against a
  man in the middle is gone.
- **The ntfy server** (`ntfy.sh` by default) for the delivery of notifications, not for their
  secrecy: it sees the topic and the fixed text.

### What is out of scope

- A phone that is rooted, compromised by malware with high privileges, or used by someone else
  while it is unlocked.
- A compromised server or SSH account.
- The security of opencode itself, of the model providers it calls, and of the code the agent
  runs on your server.

## What is stored on the phone

Everything lives in the app's private storage, which other apps cannot read.

| Data | How it is stored |
|---|---|
| SSH passwords, private keys, key passphrases, opencode server passwords, ntfy topic and ntfy server | Encrypted, see below |
| Server profiles: name, host, port, user name, opencode port | DataStore file; it holds references to secrets, never the secrets |
| Approved host keys: host, port, SHA-256 fingerprint | DataStore file |
| SSH key list: name, type, fingerprint, public key | DataStore file; it holds references to the private keys, never the keys |
| Recent folders per server, and settings (language, theme, file size limit) | DataStore files |

**Secrets.** Each secret is encrypted with AES-256-GCM using [Tink](https://developers.google.com/tink).
The Tink keyset is itself encrypted with an AES-256-GCM key that lives in the Android Keystore
and never leaves it. The files sit in the app's no-backup folder; file names are hashes, so they
do not tell which secret is which. Android deletes the Keystore key when the app is uninstalled,
so copies of the files that outlive the installation cannot be decrypted.

**No backups.** Backups are off (`android:allowBackup="false"`), and the data extraction rules
exclude every storage area from cloud backup and from device-to-device transfer. Your servers and
keys never reach a backup.

**No copies of your work.** The app does not save your code, sessions or conversations on disk;
it loads them from the server when it shows them. A file you download goes only where you choose
in the system file picker.

**Deleting.** **Settings**, **Delete all data** erases every server, key, password and setting,
the ntfy topic included. Uninstalling the app does the same. Neither removes anything from your
servers; see [INSTALL.md](INSTALL.md#removing-everything).

## What goes over the network

```text
 Phone (Phopencode) ---- SSH ----> your server ---- HTTPS ----> ntfy server ----> ntfy app
                                     |                          (ntfy.sh by
                                     '- opencode serve           default)
                                        on 127.0.0.1
```

- **Phone to your server: SSH only.** Commands, SFTP file transfers and the opencode HTTP API
  share one SSH connection. The API and its event stream go through an SSH channel to
  `127.0.0.1` on the server; the phone opens no local port, so other apps on the phone cannot
  reach it.
- **No other connections from the app.** The app's network security configuration forbids
  unencrypted traffic, except to `127.0.0.1`, which only the SSH channel reaches. Images in chat
  Markdown are not loaded. A link in a chat message opens in your browser only after you confirm
  a dialog that shows its full address.
- **Your server to ntfy: HTTPS only.** The notification service posts each notification to the
  ntfy server over HTTPS; it refuses plain `http://` servers.
- **ntfy to your phone:** the ntfy app receives the notification. Phopencode itself never
  contacts the ntfy server.

**No analytics or tracking.** The app contains no analytics, crash reporting, advertising or
tracking library, and sends nothing about you or your use of it to anyone.

## Trusting server keys

- On the first connection to a host and port, the app shows the server's key type and SHA-256
  fingerprint and asks you to approve it. Compare it with the fingerprint shown on the server
  ([INSTALL.md](INSTALL.md#approve-the-host-key) explains how).
- An approved key is checked silently on every later connection.
- A different key stops the connection before any password is sent or any command runs, and
  shows the old and new fingerprints. Replacing the approved key takes two explicit
  confirmations; do it only when the server's administrator confirms the change.
- If the file of approved keys cannot be read, the app refuses to connect instead of treating
  the server as new.

## The opencode server

- The app starts `opencode serve` only when none of your user runs, bound to `127.0.0.1` so that
  it is not reachable from the network.
- It protects that server with a new random password of 256 bits. The password reaches the
  server on the start command's standard input: never on a command line, where other users could
  see it in the process list, and never in a file. On the phone it is stored encrypted.
- The app reaches the API with HTTP Basic authentication inside the SSH connection.
- The server keeps running after the app disconnects. Anyone who can already act as your user
  on the server can reach it; others cannot reach `127.0.0.1` from outside.

## What the app does to your server

The app runs as your SSH user and never uses `sudo`. It never reboots, shuts down or resets the
server, and never stops, kills or replaces an `opencode serve` that is already running; it starts
one only when none runs. The only processes it stops are its own: a command you cancel, and a
server it has just started that did not come up in time. Every command is fixed shell text with
each variable part shell-quoted. Git commands never force-push, never run `git reset --hard` or
`git clean`, and pull fast-forward only. The full list is in
[INSTALL.md](INSTALL.md#what-the-app-does-on-your-server).

## Notifications

- **Fixed text.** The service sends one of four fixed notices ("Answer needed", "Permission
  needed", "Session finished", "Session failed") and a fixed test notice. Nothing from your
  sessions, such as titles, paths, code or AI output, is put in a notification.
- **A secret topic.** The topic is drawn on the phone from a secure random source, at least 128
  bits, and is never logged or shown in full. The app hands it to the ntfy app through a
  subscription link.
- **Settings on the server.** The service's settings file, `~/.config/phopencode/notifyd.json`,
  holds the opencode address and password and the ntfy server and topic. It is written over SFTP
  with mode 0600, never passed on a command line or in an environment variable, and the service
  refuses to start if the file is readable by others. The service logs fixed text only, never
  the password, the topic, the server address or event content.
- **No privileges.** The service runs as your user from your home folder, through
  `systemd --user` or your crontab, and its scripts never use `sudo` or stop, restart or signal a
  process.

## What an attacker can do

### With your phone

- **Unlocked, or with your lock screen code:** everything you can do in the app, which means
  acting on your servers as your SSH user and reading and changing your code. The app has no lock
  of its own, so the phone's lock screen is what protects it.
- **Locked:** the app's files are encrypted by Android, and the secrets are encrypted again under
  a Keystore key that cannot be exported. Getting at them needs a break of the phone's own
  security.
- **What to do if the phone is lost:** remove the phone's public key from `~/.ssh/authorized_keys`
  on each server, change any SSH password the app knew, and restart `opencode serve` so that its
  password changes. For notifications, remove the service from the server (or delete its settings
  file); a new topic is made after the app's data is deleted.

### With your server

- Someone in control of your server, or of your SSH account on it, already has your code, your
  opencode setup and its provider credentials. They can also read the notification service's
  settings file, and so the opencode password and the ntfy topic, and make the app show whatever
  they like.
- They do not get your SSH private key: SSH key login never sends it to the server. They do see
  your SSH password if you log in with one, as with any SSH client; prefer key login.
- Chat links still need your confirmation before they open, and Markdown images are not loaded,
  so content from the server cannot make the phone fetch an address on its own.

### On the network

- An attacker between the phone and the server sees encrypted SSH traffic only. Posing as your
  server requires its host key, or that you accept a changed key.
- Between your server and ntfy the traffic is HTTPS. The ntfy server's operator sees the topic,
  the fixed text, the time of each notification and your server's address.

### With your ntfy topic

- They can read your notifications: the fixed texts and their times, which tell when your agent
  needs you, finishes or fails. They learn nothing about your code or conversations.
- They can send notifications to your phone through the ntfy app, with text and links of their
  choice. Real ones always carry one of the fixed texts above and open the app's Pending items
  screen; treat anything else as suspect. The app's `phopencode://pending` link only opens that
  screen, which shows what your own server reports.
- They cannot reach your server or the app with the topic.
- To change the topic: remove the service from your servers, delete the app's data, set the app
  up again, and subscribe to the new topic in the ntfy app.

## Release builds

- Release APKs are built by a GitHub Actions workflow from a version tag, shrunk with R8, signed
  with the project's release key and published on GitHub Releases with their SHA-256 hash.
- The release build removes every call to Android's log, in the app and in its libraries, so
  the app does not write diagnostics to the system log.
- Android installs an update only when it is signed with the same key as the installed app.

## Reporting a vulnerability

Do not open a public issue for a security problem. Follow [SECURITY.md](../SECURITY.md) to report
it privately, and leave real server addresses, keys, passwords and topics out of the report.
