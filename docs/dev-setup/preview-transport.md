# Live preview transport (D9)

Decision for P13.1. The live preview (P13) shows a web frontend that runs on the server's
loopback (for example `npm run dev` on `127.0.0.1:8080`) in a WebView on the phone, through the
existing SSH connection. This document compares the two ways the WebView's traffic can reach the
pinned tunnel (P13.3) and records the choice.

**Decision: (b), a temporary HTTP bridge on the phone's `127.0.0.1`.** Decided on 2026-11-12;
the user delegated the decision.

## The rule at stake

`docs/SECURITY-MODEL.md`, "What goes over the network", says:

> The API and its event stream go through an SSH channel to `127.0.0.1` on the server; the phone
> opens no local port, so other apps on the phone cannot reach it.

`docs/PHASES.md`, "Security decisions", says the same of the tunnel: "The tunnel opens no local
port." Option (a) keeps this rule as it is; option (b) needs one documented exception.

Both options share what P13.10 lists for "Both options": the request reaches the dev server
through the pinned tunnel with `Host: localhost:<port>`, `Origin` and `Referer` rewritten the
same way, loopback `Location` headers mapped back to the preview origin, an added
`Content-Security-Policy` that limits `connect-src` to the preview origin, limits on response
size and header count, and a typed "nothing listens" error. Both keep the WebView isolation of
P13.13 (no JavaScript bridge, no file or content access, off-origin requests refused, data
cleared on close).

## (a) No local port: intercepted requests

### How a request travels

1. The WebView loads a synthetic preview origin. Every request it makes is handed to
   `WebViewClient.shouldInterceptRequest`.
2. The transport checks the origin, rebuilds the request (method, URL, headers) and sends it as
   HTTP/1.1 over a socket from the pinned tunnel's `SocketFactory`, that is over an sshj
   `DirectConnection` to `127.0.0.1:<port>` on the server.
3. The response is returned to the WebView as a `WebResourceResponse`. Cookies are kept in the
   preview's own cookie store and added to the next request by the transport.

No socket is bound or listened on on the phone.

### What works

- `GET`, `HEAD` and `OPTIONS`: pages, scripts, styles, images, fonts, plain JSON reads.
- Reloading by hand after a change.

### What does not work

- **WebSocket hot reload (Vite/webpack HMR).** `shouldInterceptRequest` never sees WebSocket
  traffic, so the HMR socket cannot be connected; the user taps reload to see a change.
- **Requests with a body.** `WebResourceRequest` does not carry the body of a `POST`, `PUT` or
  `PATCH`, whether from a form or from `fetch`/`XMLHttpRequest`. Getting it would need a
  JavaScript bridge into the app, which P13's isolation rule forbids. Such requests get a 501
  with a typed reason. In practice every form submit, login, GraphQL call and write through
  `fetch` fails.
- Streaming responses (server-sent events) arrive only when the whole response is buffered, if
  at all.

### What the page can reach

Only the preview origin, mapped to the one loopback port on the server that the user picked;
everything else is refused in `shouldInterceptRequest`. Nothing else on the phone can reach the
transport, because it has no address: it lives inside the app's process.

### What changes in `docs/SECURITY-MODEL*.md`

The rule "the phone opens no local port" stays true without exception. The new "Live preview"
section (P13.18) describes the isolation, the refused off-origin requests and the
`Content-Security-Policy`; "In short" and "What goes over the network" need no change beyond a
mention of the preview tunnel.

## (b) Temporary HTTP bridge on `127.0.0.1` (chosen)

### How a request travels

1. When the preview screen opens, the transport binds a `ServerSocket` to `127.0.0.1` only, on a
   random port chosen by the system, and draws a random per-session secret of at least 128 bits
   from `SecureRandom`.
2. The WebView loads `http://127.0.0.1:<bridge port>/` with the secret in the first URL, once.
   The bridge sets the secret in a cookie marked `HttpOnly` and `SameSite=Strict`; from then on
   the WebView presents it through the cookie.
3. For each connection the bridge reads the HTTP/1.1 request head, checks the secret in constant
   time and, only if it matches, opens a socket from the pinned tunnel's `SocketFactory` to
   `127.0.0.1:<port>` on the server. A missing or wrong secret gets 403 and the connection is
   closed before any byte reaches the tunnel.
4. The request head is rewritten (P13.10, "Both options"), the body is relayed by
   `Content-Length` or chunked encoding, and the response is relayed back with the added
   `Content-Security-Policy`.
5. A `101 Switching Protocols` response turns the connection into a raw two-way relay, so
   WebSockets, and with them HMR, work.
6. When the preview screen leaves, the `ServerSocket` and every relayed connection are closed.
   A second preview gets a new port and a new secret.

The cleartext `http://127.0.0.1` URL is already allowed by the app's network security
configuration, so `network_security_config.xml` does not change.

### What works

Everything a dev server normally serves to a browser: `GET`/`HEAD`/`OPTIONS`, form submits,
`fetch`/`XMLHttpRequest` with bodies, logins, GraphQL, server-sent events, and WebSockets
including Vite and webpack HMR, so a change made through opencode appears on the phone without a
reload.

### What the page can reach

Only the preview origin (`http://127.0.0.1:<bridge port>`), which leads to the one loopback port
on the server that the user picked. `connect-src` in the added `Content-Security-Policy` keeps
the page's WebSockets on that origin, and off-origin requests are refused as in (a).

What else can reach the bridge, and what it gets:

- **Other apps on the phone** can connect to `127.0.0.1:<bridge port>` while the preview is
  open. Without the secret they get 403 and the connection is closed; nothing reaches the
  tunnel. The port is random and exists only while the preview screen is open.
- **A hostile site in the phone's browser** can also aim requests at the port. It cannot read
  the WebView's cookie (another app's cookie store, `HttpOnly`), the cookie is `SameSite=Strict`,
  and without the secret the answer is 403.
- **The network** cannot reach it: the socket is bound to the loopback address only.

The secret is compared in constant time and never logged.

### What changes in `docs/SECURITY-MODEL*.md`

The rule "the phone opens no local port" gets one documented exception (P13.18): while the
preview screen is open, the bridge listens on `127.0.0.1` on a random port, guarded by a
per-session secret. "In short" and "What goes over the network" say so, and the "Live preview"
section explains who can reach the port and why they get 403. The rule stays true for the
opencode API, its event stream, commands and SFTP.

## Comparison

| | (a) Intercepted requests | (b) Bridge on `127.0.0.1` |
|---|---|---|
| Local port on the phone | None | One, loopback only, random, only while the preview screen is open |
| Hot reload (HMR, WebSockets) | No; the user taps reload | Yes |
| Requests with a body (forms, `fetch` POST/PUT, GraphQL, login) | No; 501 | Yes |
| Who else can reach the transport | Nobody | Other apps and browser pages on the phone, refused with 403 without the secret |
| Security model | Unchanged | One documented exception |
| Transport code | Request rebuilding and buffering | HTTP/1.1 head parsing, body relay, upgrade relay, secret check |

## Rationale

The preview exists to show the app under development actually working. Under (a) every form
submit, `fetch` POST/PUT, GraphQL call and login breaks, so pages look broken when they are not,
and the user cannot tell a bug in their app from a limit of the preview. Hot reload is part of
the intended flow: change something through opencode and see it on the phone.

The exception (b) needs is narrow:

- bound to the loopback address only, on a random port;
- listening only while the preview screen is open;
- guarded by a per-session secret of at least 128 bits, held in an `HttpOnly`,
  `SameSite=Strict` cookie, compared in constant time and never logged;
- other apps on the phone, or a hostile site in the phone's browser, that reach the port get
  403, and nothing reaches the tunnel;
- the release audit (P13.17) allows a `ServerSocket` only in the bridge file and fails if it
  binds anything other than the loopback address.

Cost: one documented exception to "the phone opens no local port", and more transport code
(HTTP parsing and the upgrade relay), covered by the tests of P13.10 and by the bridge checks of
P13.20.
