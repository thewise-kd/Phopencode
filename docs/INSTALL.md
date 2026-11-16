# Installing and setting up Phopencode

<p><strong>English</strong> | <a href="INSTALL.tr.md">Türkçe</a></p>

This guide takes you from an empty phone to a working connection: what the phone and the server
need, how to install and check the app, how the first connection works, how to set up push
notifications with ntfy, and how to preview a web app that runs on your server. How the app protects your data is described in
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

### For the live preview (optional)

- A web app or dev server that you can start on the server yourself, listening on `127.0.0.1`.
- `ss`, or a readable `/proc/net/tcp`, to list the listening ports. Without either you can still
  type the port by hand.
- Nothing else: the preview uses the same SSH connection and the same kind of SSH tunnel as
  opencode.

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

### First launch: the usage guide

The first time you open the app, a short **Usage guide** opens above the server list. It is not
part of v0.1.0; it is listed under *Unreleased* in [CHANGELOG.md](../CHANGELOG.md) and comes with
the next release. Its pages tell you what the app does, what your server needs, how to add a
server, and where to find sessions and chat, files and Git, notifications and the live preview.

Move between the pages with **Back** and **Next**. **Skip**, on every page, and **Start**, on the
last one, both end the guide for good: you land on the server list, and the guide never opens by
itself again, also after a restart.

To read it again at any time, open **Settings** from the server list and tap **Usage guide**.
**Delete all data** in **Settings** shows it again, as on a fresh install.

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

## 5. Live preview (optional)

The live preview shows a web app that runs on your server, for example the dev server of the
frontend that opencode is working on, in a screen of the app. It travels through the same SSH
connection, so nothing new is exposed on the server's network. It is not part of v0.1.0; it is
listed under *Unreleased* in [CHANGELOG.md](../CHANGELOG.md) and comes with the next release.

### Start the dev server yourself

The app does not start your dev server. Start it on the server yourself, in a terminal, or ask
opencode in the chat to start it. Bind it to `127.0.0.1`, so that it is reachable only from the
server itself, for example:

```sh
# on the server, in your project
npm run dev -- --host 127.0.0.1 --port 5173   # Vite
python3 -m http.server --bind 127.0.0.1 8080  # a folder of static files
```

The app reaches the port on the server's loopback through SSH. A server bound to all addresses
(`0.0.0.0` or `::`) works too, but it is then also open to your network; one bound only to
another address, such as the server's LAN address, cannot be previewed.

### Open the preview

While connected, open **Live preview** from either place:

- in a chat, from the **Session actions** menu;
- in the session list, from the top bar's menu.

Both are disabled while the app is not connected.

### The notice before the first preview

The first time you open the live preview, a short notice, **Before you preview**, appears before
the port list can be used. Its points:

- Start your dev server yourself on `127.0.0.1` or `localhost`, not `0.0.0.0`, so it is not
  exposed on the server's network.
- Open only projects you trust. The page's JavaScript runs on your phone, kept apart from the
  app's keys and passwords.
- Do not sign in to real accounts or type real passwords, tokens or personal data into the
  preview page.
- Check the full address before you confirm a link that leaves the preview.
- The app never starts or stops your dev server, and the preview's data is cleared when you
  leave it.

**I have read and understood** hides the notice for good, also after a restart. **Not now**, or
the back button, leaves the preview without accepting, and the notice appears again on your next
visit; tapping outside it does not close it. **Delete all data** in **Settings** shows it again.

### Pick a port

The first screen lists the TCP ports that listen on the server and can be reached on its
loopback, lowest first. Tap one to open it. The opencode server's own port is in the list too,
greyed out: it cannot be previewed. Tap the refresh button in the top bar after starting a dev
server.

When nothing but opencode listens, the screen says so and asks you to start your dev server, or
ask opencode to, and refresh. When the server offers no way to list its ports, the screen says
so as well.

Under **Enter a port** you can type a port by hand, from 1 to 65535, and tap **Open**. This is
useful when the list is empty or cannot be read. The opencode port is refused here too.

### Reload

The page shows a progress line while it loads. To load it again, tap the reload button in the
top bar, or pull the page down from its top. Most dev servers (Vite, webpack and similar) also
update the page by themselves when a file changes, through hot reload.

If the SSH connection drops, the preview shows **Connection lost** and does not retry on its own.
Once the app has reconnected, tap **Reload**.

### Console

The console button in the top bar shows how many errors the page has logged; tap it to open the
console panel over the bottom of the page. It lists:

- the page's own console messages (`console.log`, `console.error` and the rest) with their
  source and line;
- pages and resources that failed to load, and HTTP errors;
- requests the preview refused because they leave the page's origin.

You can filter by level and clear the list. The text can be selected and copied. The console
keeps at most the 500 newest entries, cuts each at 4 KiB, holds them in memory only, never sends
them anywhere and drops them when the preview closes.

### Mobile and desktop view

The **Desktop view** toggle in the top bar switches between:

- **Mobile:** the WebView's own user agent and phone layout, so the page sees a phone;
- **Desktop:** a user agent without `Mobile` and `Android`, a wide page that is zoomed out to fit
  the screen, and pinch zoom.

Switching reloads the page. The choice holds while the preview is open, also when you rotate the
phone, and goes back to mobile when you close it.

### How it works and its limits

- **A temporary bridge on the phone.** While the preview screen is open, the app runs a small
  bridge on the phone's own `127.0.0.1`, on a random port, and the preview loads the page from
  there; the bridge passes each request through the SSH tunnel to the port you picked. Every
  connection to the bridge must carry a random secret made for that preview; without it the
  bridge answers 403 and passes nothing on. The bridge closes when you leave the screen, and the
  next preview gets a new port and a new secret. This is the one place where the app opens a
  local port on the phone; see [Live preview](SECURITY-MODEL.md#live-preview) in the security
  model.
- **Hot reload and request bodies work.** WebSocket connections on the page's own origin are
  passed through, so hot reload works, and so do forms and `fetch` calls that send a body (POST,
  PUT and the rest).
- **The page can load nothing off its own origin.** Fonts, scripts, styles and images from a CDN
  or any other address are refused, and each refused request shows in the console. Serve such
  files from the dev server itself while you preview.
- **External links ask first.** When the page opens another `http` or `https` address, the app
  shows it in full and asks whether to open it outside the app, for example in your browser.
  Other kinds of links are refused.
- **The preview leaves nothing behind.** The page has no access to files, the camera, the
  microphone or the location, and cannot download files. Its cookies, storage, cache and history
  are deleted when you close the preview, so a login or a value in `localStorage` does not
  survive it.

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
- installs and removes the notification service only when you tap the buttons for it;
- for the live preview, lists the listening TCP ports read-only with `ss -ltnH`, falling back to
  reading `/proc/net/tcp` and `/proc/net/tcp6` when `ss` is missing, and opens an SSH tunnel to
  the loopback port you pick. For the preview the app never starts, stops or kills a process:
  your dev server is yours to start and stop.

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
- **"Nothing listens on port …" in the preview:** the dev server is not running on that port, or
  it listens only on another address. Start it on the server on `127.0.0.1` with that port, or
  ask opencode to, check that it stays running, then tap **Reload**.
- **"No dev server found" in the port list:** the same: start the dev server on `127.0.0.1`, then
  refresh the list.
- **"Ports cannot be listed":** the server has neither `ss` nor a readable `/proc/net/tcp`. Type
  your dev server's port under **Enter a port**.
- **A font, script or style is missing in the preview:** it comes from another address, such as
  a CDN, and was refused; the console shows it. Serve the file from the dev server instead.

## Removing everything

- On the server: remove the notification service from the app first (see
  [Remove the service](#remove-the-service)). The app's state folder,
  `~/.local/state/phopencode/`, can then be deleted by hand. A running `opencode serve` is yours
  to stop.
- On the phone: **Delete all data** in **Settings** (opened from the server list) erases every
  server, key, password and setting, and shows the usage guide and the live preview notice
  again; uninstalling the app does the same.

Security questions are answered in [SECURITY-MODEL.md](SECURITY-MODEL.md). To report a
vulnerability, follow [SECURITY.md](../SECURITY.md).
