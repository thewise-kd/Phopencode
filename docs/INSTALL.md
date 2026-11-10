# Installing and setting up Phopencode

<p><strong>English</strong> | <a href="INSTALL.tr.md">Türkçe</a></p>

This guide takes you from an empty phone to a working connection: what the phone and the server
need, how to install and check the app, how the first connection works, and how to set up push
notifications with ntfy. How the app protects your data is described in
[SECURITY-MODEL.md](SECURITY-MODEL.md); the project overview is in the [README](../README.md).

In the examples, `example.com` stands for your server. Replace it with your own, and never post
your real server address, keys, passwords or ntfy topic in an issue.

## What you need

### On the phone

- Android 8.0 (API 26) or newer.
- For notifications: the free [ntfy](https://ntfy.sh) app.

### On the server

- A Unix-like server (for example Linux) that you can reach with SSH, with a password or an SSH
  key. Any port works; the default is 22.
- [opencode](https://opencode.ai) 2.2 or newer, installed for the user you log in as. The app
  finds it on the `PATH` of your SSH shell or your login shell, and in the usual install folders
  such as `~/.opencode/bin`, `~/.local/bin`, `~/bin`, `/usr/local/bin` and `/usr/bin`. An older
  opencode is refused with a message to update it.
- opencode set up with at least one model provider, as you would for the terminal.
- `git`, for the git screens.
- `rg` (ripgrep) is optional for content search; `grep` is used without it.
- Standard tools that a normal Linux system has: `sh`, `ps` or `/proc`, and `ss`, `/proc/net/tcp`
  or `lsof` to find a running opencode.

### For notifications (optional)

- `python3` 3.9 or newer on the server.
- `pgrep` and `flock` (from procps and util-linux, present on most Linux systems).
- A way to keep the service running: a `systemd --user` manager with lingering enabled for your
  user (`loginctl enable-linger`, which you run yourself; the app never enables it), or `crontab`.
- The server must be able to reach the ntfy server over HTTPS, `ntfy.sh` by default.

You do not need root, `sudo` or any open port besides SSH.

## 1. Install the app

### Download the APK

Releases are published on the
[GitHub Releases page](https://github.com/thewise-kd/Phopencode/releases) of the repository,
where the latest release is listed; [CHANGELOG.md](../CHANGELOG.md) lists the changes in each
version. Each release carries two files:

- `Phopencode-v<version>.apk`, the signed app;
- `Phopencode-v<version>.apk.sha256`, the SHA-256 hash of that APK.

Download the APK only from there. The app is not on Google Play or any other store. You can also
build the app from source as described in the [README](../README.md#building-from-source).

### Check the SHA-256

The hash proves that the download is complete and matches the file the release workflow built.
On a computer, put both files in one folder and run:

```sh
sha256sum -c Phopencode-v0.1.0.apk.sha256      # prints "Phopencode-v0.1.0.apk: OK"
# macOS: shasum -a 256 -c Phopencode-v0.1.0.apk.sha256
```

On the phone, a file manager or hash app that shows a file's SHA-256 does the same: the value
must equal the one in the `.sha256` file.

If you have the Android SDK build tools, `apksigner verify --print-certs` shows the certificate
that signed the APK. Android itself makes sure that every update is signed with the same key as
the app you installed; it refuses an update signed with another key.

### Install (sideload)

1. Open the downloaded APK on the phone, from the browser's downloads or a file manager.
2. Android asks you to allow that app to install unknown apps. Allow it for this install; you
   can turn it off again afterwards in the system settings (Apps, Special app access, Install
   unknown apps).
3. Confirm the installation. Google Play Protect may warn about an app from outside the store;
   that is expected for a sideloaded app.

To update, install the newer APK over the old one in the same way. Your servers, keys and
settings are kept. Uninstalling the app deletes all of them.

## 2. Add a server

1. Open Phopencode and tap the button to add a server.
2. Fill in:
   - **Name**: any label, for example `Home server`.
   - **Host**: the server's address, for example `example.com`.
   - **SSH port**: 22 unless your server uses another one.
   - **User name**: the account that has opencode installed.
   - **Log in**: with a password, or with an SSH key.
   - **opencode serve password** (optional): only if an `opencode serve` that you started
     yourself already runs with a password. Leave it empty otherwise.
   - **opencode port** (optional): only if you want the app to use the `opencode serve` on one
     fixed port. Leave it empty to find the running server automatically.
3. Save.

### SSH keys

Under **Manage keys** you can:

- **Generate** a new ed25519 key on the phone, optionally with a passphrase. Copy its public key
  and add it to `~/.ssh/authorized_keys` on the server, for example:

  ```sh
  # on the server, with the public key you copied from the app
  echo 'ssh-ed25519 AAAA... phone' >> ~/.ssh/authorized_keys
  ```

- **Import** an existing private key file: OpenSSH format (ed25519, RSA, ECDSA), also
  passphrase-protected ones, and the older PEM formats.

Each key shows its type and its SHA-256 fingerprint. Deleting a key removes it from the phone
only; the servers that trust it are not changed.

## 3. Connect: host key and opencode

Tap the server to connect. The app shows each step.

### Approve the host key

On the first connection the app shows the server's host key type and its SHA-256 fingerprint and
asks you to approve it. Compare it with the fingerprint you get on the server itself, for example:

```sh
# on the server
for f in /etc/ssh/ssh_host_*_key.pub; do ssh-keygen -l -f "$f"; done
```

Approve only if they match. The approved key is saved, and later connections check against it
without asking.

If the server later presents a different key, the connection stops with a warning showing both
fingerprints. This happens after a server reinstall or a key change, but it can also mean that
someone is intercepting the connection. Accept the new key only after the server's administrator
confirms the change.

### The opencode server

After logging in, the app looks for a running `opencode serve` of your user:

- **None runs:** the app finds the `opencode` binary and starts `opencode serve` bound to
  `127.0.0.1`, protected with a new random password that it keeps on the phone. You enter
  nothing. The server keeps running when the app disconnects, so sessions continue.
- **One runs that the app started:** the app reconnects with the password it saved.
- **One runs that you started yourself:** the app asks for the password that server was started
  with. You can also save it in the server's profile.
- **One runs but does not answer, or listens on no loopback address:** the app stops with a
  message. It never starts a second server next to it and never stops it; restart it yourself,
  listening on `127.0.0.1`.

Then choose a project folder on the server, and the session list opens.

## 4. Set up notifications (optional)

### What ntfy is

[ntfy](https://ntfy.sh) is a free, open-source push notification service. A program sends a
message to a *topic* on an ntfy server, and every phone subscribed to that topic in the ntfy app
gets it. Phopencode uses it so that you hear about your agent while the app is closed:

- a question or form waits for your answer;
- a permission request waits for you;
- a main session (not a subagent's) finished;
- a main session stopped with an error.

The notification text is fixed: "Answer needed", "Permission needed", "Session finished",
"Session failed" and a short line each. It never contains session titles, file paths, code or AI
output. Tapping it opens the app's **Pending items** screen, which loads the details from your
server over SSH.

The notifications are sent by a small service on your server (notifyd), not by the phone. It is
standard-library Python and uses no other package. To keep a busy agent from flooding the
phone, it sends at most one notification of each kind a minute, 20 an hour and 100 a day; the
rest are dropped.

### The topic is a secret

Anyone who knows the topic can read the notifications. The app makes a random topic of at least
128 bits, stores it encrypted on the phone and never shows it in full: it shows
`ntfy://ntfy.sh/••••••••`. One topic serves all your servers, so you subscribe once. The topic
stays the same until you delete all app data.

### Install the ntfy app and subscribe

1. Connect to a server, open the session list's menu and choose **Notifications**.
2. Install ntfy on the phone; **Get ntfy** opens ntfy's page on how to get the phone app.
3. Tap **Subscribe in ntfy**. The ntfy app opens with your topic filled in; confirm the
   subscription there. The app passes the topic to ntfy through an `ntfy://` link, so you never
   type or copy it.

### Install the service on the server

On the same screen, under **Service on the server**, tap **Install and start**. The app:

1. checks that `python3` 3.9 or newer is there, and stops with a message, installing nothing, if
   it is not;
2. removes an earlier install, if any;
3. writes the service's settings (the opencode address and password, the ntfy server and topic)
   over SFTP into `~/.config/phopencode/notifyd.json`, mode 0600, never on a command line;
4. runs the install script, which copies the service and starts it.

The status line shows **Running**, **Stopped** or **Not installed**. The service runs for the
server of the current connection; install it on each server you want notifications from.

The service keeps the opencode address and password from the moment it was installed. If
`opencode serve` is restarted, for example after the server reboots and the app starts a new one,
the service can no longer sign in and notifications stop; tap **Reinstall** after the next
connection.

### Send a test

Tap **Send a test**. The server sends one test notification to your topic; when the ntfy server
accepts it, the app says so, and it should appear in the ntfy app shortly. The test does not
touch the installed service.

### Change the ntfy server

The default is `ntfy.sh`. To use an ntfy server of your own, enter its address under
**ntfy server**, for example `https://ntfy.example.com`, and save. Only `https://` addresses
without a path or credentials are accepted. **Use ntfy.sh** goes back to the default. An
installed service keeps its old server until you reinstall it, and you need to subscribe again in
the ntfy app.

### Remove the service

Tap **Remove** and confirm. The app runs the uninstall script, which deletes the settings file
(the service sees that and exits by itself), removes the service entry and deletes the files the
install created. Deleting the app's data on the phone does not remove the service from your
servers; remove it first.

### What the service installs, and where

Everything is in your home folder; nothing needs root:

| What | Where |
|---|---|
| Settings (opencode password, ntfy topic), 0600 | `~/.config/phopencode/notifyd.json` |
| The service, 0600 | `~/.local/share/phopencode/notifyd.pyz` |
| Its log (cron only), 0600; fixed text, no secrets | `~/.local/share/phopencode/notifyd.log` |
| systemd user unit, if systemd is used | `~/.config/systemd/user/phopencode-notifyd.service` |
| crontab line, if cron is used | `@reboot /bin/sh -c '...' phopencode-notifyd` |

`$XDG_CONFIG_HOME` and `$XDG_DATA_HOME` replace `~/.config` and `~/.local/share` when they are
set. The install script uses `systemctl --user` when the user manager answers and your user has
lingering enabled, so the service survives logout; otherwise it adds the crontab line and starts
the service right away. It never enables lingering, never uses `sudo` and never stops, restarts
or signals a process: if the service already runs, it refuses, and a reinstall removes the old
one first.

## What the app does on your server

Everything runs as your SSH user. The app:

- runs commands through `sh -c` over SSH, with every variable part shell-quoted;
- reads the process list and listening sockets to find a running `opencode serve`, and changes
  nothing while doing so;
- starts `opencode serve --hostname 127.0.0.1` only when no `opencode serve` of your user runs
  at all; its password goes in on stdin, never on the command line or into a file;
- keeps a small state folder, `~/.local/state/phopencode/` (or under `$XDG_STATE_HOME`), mode
  0700, with `server.state` (the port and process ID of the server it started, no password) and
  that server's log;
- reads and writes files over SFTP in the folders you open, and runs `git`, `rg` or `grep` there
  when you use those screens;
- installs and removes the notification service only when you tap the buttons for it.

It never uses `sudo`, never reboots, shuts down or resets the server, and never stops, kills or
replaces an `opencode serve` that is already running. The only processes it stops are its own: a
command that you cancel, and a server it has just started that did not come up within 30
seconds. Git is safe by default: no force push, no `git reset --hard`, no `git clean`, pull only
fast-forward, and discarding changes needs your confirmation.

## Troubleshooting

- **"opencode was not found on the server":** install opencode for the user you log in as, or
  make sure `opencode --version` works in a login shell of that user.
- **"The opencode on the server is too old":** update it, for example with `opencode upgrade`.
- **"An opencode server that this app did not start is running":** enter the password it was
  started with, or stop that server yourself so that the app can start its own.
- **"An opencode server is running but not answering":** restart it on the server; the app does
  not touch it.
- **The host key has changed:** see [Approve the host key](#approve-the-host-key).
- **The service is installed but no notification arrives:** check the subscription in the ntfy
  app, send a test, and reinstall the service if `opencode serve` was restarted since the
  install.

## Removing everything

- On the server: remove the notification service from the app first (see
  [Remove the service](#remove-the-service)). The app's state folder,
  `~/.local/state/phopencode/`, can then be deleted by hand. A running `opencode serve` is yours
  to stop.
- On the phone: **Delete all data** in **Settings** (opened from the server list) erases every
  server, key, password and setting; uninstalling the app does the same.

Security questions are answered in [SECURITY-MODEL.md](SECURITY-MODEL.md). To report a
vulnerability, follow [SECURITY.md](../SECURITY.md).
