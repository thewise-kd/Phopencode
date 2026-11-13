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
- The phone opens no local port, with one exception: while the live preview screen is open, a
  bridge listens on the phone's `127.0.0.1`, on a random port, and answers only requests that
  carry a secret drawn for that session. See [Live preview](#live-preview).
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
                                     |- opencode serve           default)
                                     |  on 127.0.0.1
                                     '- your dev server (live preview)
                                        on 127.0.0.1
```

- **Phone to your server: SSH only.** Commands, SFTP file transfers and the opencode HTTP API
  share one SSH connection. The API and its event stream go through an SSH channel to
  `127.0.0.1` on the server; the phone opens no local port for them, so other apps on the phone
  cannot reach them.
- **The live preview is the one exception.** While the preview screen is open, its bridge
  listens on the phone's `127.0.0.1` only, on a random port, and relays the preview's requests
  through the same SSH connection to the one port on the server's loopback that you picked.
  Every connection to the bridge must carry a secret drawn for that session; without it the
  answer is 403 and nothing reaches the server. Leaving the screen closes the port. Commands,
  SFTP and the opencode API still open no local port. Details are in
  [Live preview](#live-preview).
- **No other connections from the app.** The app's network security configuration forbids
  unencrypted traffic, except to `127.0.0.1`, which on the phone is either the SSH channel or the
  preview's bridge. Images in chat Markdown are not loaded. A link in a chat message, or a link
  in the preview that leaves the previewed site, opens in your browser only after you confirm a
  dialog that shows its full address.
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
server it has just started that did not come up in time. It never starts or stops the dev
server you preview; to show which ports you can preview, it only reads the list of listening
ports. Every command is fixed shell text with
each variable part shell-quoted. Git commands never force-push, never run `git reset --hard` or
`git clean`, and pull fast-forward only. The full list is in
[INSTALL.md](INSTALL.md#what-the-app-does-on-your-server).

## Live preview

The live preview shows, in a WebView on the phone, a web app that a dev server runs on your
server's loopback, for example `npm run dev` on `127.0.0.1`. Its traffic goes through a temporary
bridge on the phone. That is option (b) of decision D9, chosen over a design without a local port
because it lets forms, requests with a body and hot reload work; the two options are compared in
[preview-transport.md](dev-setup/preview-transport.md).

### What it connects to

- **Only the port you pick.** The preview screen lists the TCP ports that listen on the server's
  loopback, and you pick one. The list comes from a fixed, read-only command (`ss`, or the
  kernel's `/proc/net/tcp` tables when `ss` is missing); nothing is started, stopped or signalled,
  and `sudo` is never used.
- **Only on the server's loopback.** The preview's SSH channel reaches only `127.0.0.1`, `::1` or
  `localhost` on that port, as seen from the server. It cannot reach another host, and it refuses
  the port `opencode serve` listens on.
- **Never the dev server's process.** The app never starts, stops or restarts your dev server. If
  nothing listens on the port, the preview says so.
- The channel belongs to the SSH connection it was opened on: it closes when that connection
  drops and never moves to a later one. Reloading after a reconnect opens a new one.

### The bridge on the phone

- When the preview screen opens, the bridge listens on `127.0.0.1` only, never on a network
  interface, on a port the system picks at random. The release audit checks that this is the only
  place in the app that listens on a socket, and only on the loopback address.
- Each bridge draws a new secret of 256 bits from a secure random source. The WebView presents it
  once, in its first address; the bridge moves it into a cookie marked `HttpOnly` and
  `SameSite=Strict` and sends the WebView on to the same page without the secret in the address.
- Every request must carry the secret, in that cookie or in the first address, and the secret is
  compared in constant time. A request without it gets 403, and its connection is closed before
  any byte reaches the SSH channel. Such refusals are not reported anywhere, so other apps cannot
  fill the preview's console.
- The secret never reaches the dev server: the bridge removes its cookie from every request and
  drops any cookie of that name the dev server tries to set. It is never logged, and the console
  removes it from every address and message it shows.
- The bridge limits what one connection may cost: the size of request and response heads and
  bodies, the number of headers, how long a client or the dev server may stall, and the number of
  connections at once.
- Leaving the preview screen closes the bridge's port, every connection through it and the SSH
  channel. Opening the preview again draws a new port and a new secret.

### The WebView

The preview's WebView is the only WebView in the app, and it is isolated from the rest of it:

- **No bridge into the app.** Pages run JavaScript, which dev servers need, but no app object,
  message channel or other interface is handed to them.
- **No files or content.** File and content access are switched off, for the page and for
  `file:` addresses. The file chooser and downloads are refused.
- **No device access.** Camera, microphone and every other permission request is denied, and
  geolocation is off. Pop-up windows are not opened.
- **No credentials.** The WebView never sees your SSH keys, passwords or ntfy settings. HTTP
  authentication requests, client certificate requests and pages with TLS errors are cancelled.
  Form data and passwords are not saved, and the autofill service is not offered the page.
- **Nothing kept.** The page may use cookies and web storage while the preview is open. Closing
  the preview clears its cookies, web storage, HTTP cache, history, form data and saved HTTP
  authentication, and destroys the WebView. Rotating the phone keeps the page; leaving the
  screen does not.
- **No debugging in releases.** The WebView can be debugged remotely only in a debug build of the
  app.

### Requests off the preview origin

The preview origin is `http://127.0.0.1:<bridge port>`. Everything the page loads must come from
it:

- **Refused in the WebView.** Every request the WebView makes is checked before it leaves. One
  that is not on the preview origin, and is not `data:`, `about:blank`, `about:srcdoc` or a `blob:`
  made on the origin, gets an empty 403 and is listed in the console as refused; the phone never
  fetches it. Another port on the phone's loopback is off the origin too.
- **An added `Content-Security-Policy`.** The bridge adds a policy to every response it relays
  and to its own answers. The WebView does not check a redirected sub-resource again, so this
  policy is what stops a redirect that leaves the origin:
  - `default-src` allows only the origin, `data:` and `blob:`; images, fonts, media, frames,
    workers and the rest fall back to it.
  - `script-src` and `style-src` add inline code, and `script-src` also `eval`, which Vite and
    webpack dev servers rely on.
  - `connect-src` allows the origin over `http` and `ws`, so `fetch`, `XMLHttpRequest`,
    `EventSource` and the hot reload WebSocket stay on it.
  - `form-action` allows only the origin.

  The page's own policy, if it has one, is passed on unchanged and applies as well.
- **Addresses on the server.** The dev server sees requests addressed to `localhost`, as from a
  browser on the server, and its redirects to its own `localhost` address are mapped back to the
  preview origin.

### External links

A link that leaves the preview origin is never loaded in the WebView. If it is an `http` or
`https` address in the main page, the app shows a dialog with the full address and opens it in
your browser only after you confirm. A frame that tries to leave the origin is refused without a
dialog, and every other kind of address (`file:`, `content:`, `intent:`, `javascript:` and the
rest) is refused.

### A hostile page on your server

The dev server and the pages it serves are code that runs on your server as your user, so the
preview trusts them no more and no less than the rest of the server. A hostile or compromised page
can:

- run JavaScript in the preview and show anything, including a page that imitates a login form;
- send any request, with any body, to the dev server on the port you picked, and open WebSockets
  there; what the dev server then does on your server is outside the app;
- keep the WebView busy or use up the bridge's limits, which slows or stops the preview;
- write to the preview's console, and ask to open an external address, which still needs your
  confirmation.

It cannot:

- reach another port or host through the preview: not `opencode serve`, not another port on the
  server, not the phone's other loopback ports, not the internet (but see
  [Known limits](#known-limits));
- read or change anything in the app: your servers, keys, passwords, chats or settings;
- read files on the phone, use the camera, microphone or location, download files or open
  windows;
- keep anything on the phone after you close the preview;
- run commands on your server through the app.

The bridge's secret guards the bridge against other apps, not against the page, which is the
bridge's own client. Its scripts cannot read the secret's cookie, and even with the secret the page
could reach nothing beyond what it already reaches.

### Who else can reach the bridge

- **Other apps on the phone** can connect to the bridge's port while the preview is open. Without
  the secret they get 403, and nothing reaches your server. By opening many connections they can
  slow the preview down.
- **A site open in the phone's browser** can send requests to the port too. It cannot read the
  WebView's cookie, which lives in another app's storage, so it gets 403 as well.
- **The network** cannot reach the bridge: it listens on the loopback address only.

### Known limits

- The WebView cannot be built or tested without the Android SDK. The bridge, its secret check,
  the added policy and the address rules are covered by tests that run on any computer; the
  WebView's own behaviour, such as its settings, the refused requests and the data cleared on
  close, is checked only by tests on an Android emulator in the project's CI.
- WebRTC and DNS prefetching are covered neither by the request check nor by the policy, so they
  are closed separately, and only in part:
  - **WebRTC.** Before a page's own scripts run, `RTCPeerConnection`, its prefixed forms,
    `RTCDataChannel` and the other WebRTC interfaces are made `undefined` in each frame the system
    WebView runs its document start scripts in; the page cannot assign or delete them back. A
    system WebView that cannot run such scripts gets no preview: the screen says it is too old.
  - **DNS prefetching.** The bridge adds `X-DNS-Prefetch-Control: off` to every response, its own
    answers included, in place of any value the page sends. This asks the WebView not to look up
    the host names of the page's links in advance.
  - **What may remain.** A frame the WebView does not run document start scripts in may still have
    WebRTC, and through it a page could make the WebView contact a STUN or TURN server of its
    choice. An empty `about:blank` frame that a script creates and uses at once may be such a
    frame. A hint such as `<link rel="dns-prefetch">` or `<link rel="preconnect">` naming another
    host may still make the phone look that name up, as the header is not certain to cover it. A
    page you preview may therefore still send a small amount of data off the phone.
  - **How it is checked.** Only the tests on an Android emulator in the project's CI check what
    the WebView does. The tests that run on any computer check the header and the script's text,
    not their effect.
- `script-src` allows inline code and `eval`, because dev servers need them. The added policy
  keeps the page on its origin; it does not protect the page from its own scripts.

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
- A page they serve to the live preview stays inside the preview's isolated WebView; see
  [what a hostile page can do](#a-hostile-page-on-your-server).

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
