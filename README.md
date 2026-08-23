<div align="center">

**English** · [Русский](README.ru.md)

<img src="assets/banner.svg" alt="Slipstream Client" width="100%">

### Desktop client for the [slipstream](https://github.com/Mygod/slipstream-rust) DNS tunnel

Linux · Windows

[![License](https://img.shields.io/badge/license-Apache--2.0-3b82f6?style=flat-square)](LICENSE)
[![Built with](https://img.shields.io/badge/built%20with-Tauri%202-22d3a8?style=flat-square)](https://tauri.app)
[![Tunnel](https://img.shields.io/badge/slipstream-v0.1.1-38bdf8?style=flat-square)](https://github.com/Mygod/slipstream-rust/releases/tag/v0.1.1)
[![Server](https://img.shields.io/badge/server-installer-a78bfa?style=flat-square)](https://github.com/SpecFlowdev/slipstream-installer)

</div>

---

<div align="center">
  <img src="assets/screenshot-connection.png" alt="Connection screen" width="90%">
</div>

---

## What it does

Keeps your tunnel servers in one place and turns the one you pick into a **local SOCKS5 proxy**. Point a browser or any SOCKS-aware application at that port and its traffic leaves through your server, carried inside ordinary DNS queries.

The tunnel engine is the upstream `slipstream-client` binary, shipped alongside the app and supervised by it. The app owns the port you configure and forwards to the engine, so the throughput figures on screen are measured rather than guessed.

Set up the other end with the [server installer](https://github.com/SpecFlowdev/slipstream-installer); it prints every value this app asks for.

---

## Features

- **Server profiles** — domain, resolver, pinned certificate, local port and proxy credentials, stored per server
- **Live statistics** — download and upload rates graphed over the last minute, session totals, open connection count and uptime
- **Log viewer** — the tunnel's own output, filtered by level, with follow-the-tail
- **Runs in the tray** — closing the window leaves the tunnel up; connect on launch is optional
- **Light and dark** — follows the system theme or pins either
- **Nothing leaves the device** — profiles, certificates and credentials live in your config directory only

---

## Screens

<table>
  <tr>
    <td width="50%"><img src="assets/screenshot-servers.png" alt="Servers"></td>
    <td width="50%"><img src="assets/screenshot-logs.png" alt="Logs"></td>
  </tr>
  <tr>
    <td align="center"><strong>Servers</strong> — profiles and the editor</td>
    <td align="center"><strong>Logs</strong> — tunnel output by level</td>
  </tr>
</table>

---

## Adding a server

| Field | Where it comes from |
| --- | --- |
| Tunnel domain | The domain you gave the server installer |
| Resolver | Your provider's resolver, e.g. `1.1.1.1:53`, or the server's own address |
| Certificate | `/etc/slipstream/cert.pem` on the server — copy it across |
| Local SOCKS5 port | Anything free; `1080` by default |
| Proxy username, password | Printed by the installer, also in `/etc/slipstream/socks-credentials` |

The certificate is optional but worth setting. Without it the server is not verified at all, so anyone able to answer your DNS queries can impersonate it — and would receive the proxy password your client sends.

---

## Building

Needs [Rust](https://rustup.rs), Node 22+, and on Linux the usual Tauri packages:

```sh
sudo apt install libwebkit2gtk-4.1-dev libgtk-3-dev libayatana-appindicator3-dev librsvg2-dev
```

Then:

```sh
npm ci
node scripts/fetch-sidecar.mjs   # downloads the tunnel binary, verifies its checksum
npm run tauri dev                # or: npm run tauri build
```

`fetch-sidecar.mjs` pulls the prebuilt tunnel from the upstream release and checks it against a SHA256 pinned in the script — verifying only against the `.sha256` published beside it would not catch a swapped release. The binary is never committed here.

---

## How it fits together

```
your app ──► 127.0.0.1:1080 ──► slipstream-tunnel ──► DNS ──► server ──► SOCKS5 ──► internet
             (this app,          (upstream binary,           (your VPS)
              counts bytes)       QUIC over DNS)
```

---

## Roadmap

- System-wide routing through a TUN device, replacing the manual proxy setup
- Android build — the tunnel needs cross-compiling against the NDK first, as upstream ships no Android binary
- Profile import and export by link or QR

---

## License

Apache-2.0. The tunnel engine is [Mygod/slipstream-rust](https://github.com/Mygod/slipstream-rust), distributed under its own licence.
