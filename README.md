<div align="center">

**English** · [Русский](README.ru.md)

<img src="assets/banner.svg" alt="Slipstream Client" width="100%">

### Desktop client for the [slipstream](https://github.com/Mygod/slipstream-rust) DNS tunnel

Linux · Windows

[![License](https://img.shields.io/badge/license-Apache--2.0-3b82f6?style=flat-square)](LICENSE)
[![Built with](https://img.shields.io/badge/built%20with-Tauri%202-22d3a8?style=flat-square)](https://tauri.app)
[![Tunnel](https://img.shields.io/badge/slipstream-v0.1.1-38bdf8?style=flat-square)](https://github.com/Mygod/slipstream-rust/releases/tag/v0.1.1)
[![Server](https://img.shields.io/badge/server-installer-a78bfa?style=flat-square)](https://github.com/SpecFlowdev/slipstream-installer)

[**Download**](https://github.com/SpecFlowdev/slipstream-client/releases/latest) · [Server installer](https://github.com/SpecFlowdev/slipstream-installer)

</div>

---

<div align="center">
  <img src="assets/screenshot-connection.png" alt="Connection screen" width="90%">
</div>

---

## Download

Grab the latest build from [Releases](https://github.com/SpecFlowdev/slipstream-client/releases/latest).

| Platform | File |
| --- | --- |
| Windows | `…-setup.exe` |
| Debian, Ubuntu, Mint | `…-linux-x86_64.deb` |
| Fedora, RHEL, openSUSE | `…-linux-x86_64.rpm` |
| Any Linux, no install | `…-linux-x86_64.tar.gz` or `…AppImage` |

Every file ships with a `.sha256` next to it:

```sh
sha256sum -c slipstream-client-0.1.0-linux-x86_64.deb.sha256
```

---

## What it does

Keeps your tunnel servers in one place and turns the one you pick into a **local SOCKS5 proxy**. Point a browser or any SOCKS-aware application at that port and its traffic leaves through your server, carried inside ordinary DNS queries.

The tunnel engine is the upstream `slipstream-client` binary, shipped alongside the app and supervised by it. The app owns the port you configure and forwards to the engine, so the throughput figures on screen are measured rather than guessed.

Set up the other end with the [server installer](https://github.com/SpecFlowdev/slipstream-installer); it prints every value this app asks for.

---

## Features

- **Server profiles** — domain, resolver, pinned certificate, local port and proxy credentials, stored per server. Paste a certificate's contents directly, or pick the file — either way it's pinned
- **Live statistics** — download and upload rates as smoothed, glowing graphs over the last minute, session totals, open connection count and uptime
- **Log viewer** — the tunnel's own output, filtered by level, with follow-the-tail
- **Runs in the tray on Windows** — closing the window leaves the tunnel up; connect on launch is optional. On Linux, closing the window quits: the system tray backend the app would otherwise use is known to crash on some Wayland desktops, so it's not built there at all
- **Dark gray, blue or light theme, English and Russian** — follows the system or pins any of the three; the dark gray theme takes a custom wallpaper, shown behind frosted glass panels
- **Kill switch** — refuses new connections and drops active ones on this app's own SOCKS5 port while the tunnel is down, so it fails closed instead of quietly hanging. Scoped to this app's port, not the whole system — that needs the TUN-based routing on the roadmap
- **Nothing leaves the device** — profiles, certificates and credentials live in your config directory only

---

## Screens

<table>
  <tr>
    <td width="50%"><img src="assets/screenshot-servers.png" alt="Servers"></td>
    <td width="50%"><img src="assets/screenshot-settings.png" alt="Settings"></td>
  </tr>
  <tr>
    <td align="center"><strong>Servers</strong> — profiles and the editor</td>
    <td align="center"><strong>Settings</strong> — kill switch, theme and wallpaper</td>
  </tr>
  <tr>
    <td width="50%"><img src="assets/screenshot-logs.png" alt="Logs"></td>
    <td width="50%"><img src="assets/screenshot-light.png" alt="Light theme"></td>
  </tr>
  <tr>
    <td align="center"><strong>Logs</strong> — tunnel output by level</td>
    <td align="center"><strong>Light theme</strong> — or follow the system</td>
  </tr>
  <tr>
    <td width="50%"><img src="assets/screenshot-ru.png" alt="Russian interface"></td>
    <td width="50%"><img src="assets/screenshot-connection.png" alt="Blue theme"></td>
  </tr>
  <tr>
    <td align="center"><strong>Russian</strong> — switched in settings</td>
    <td align="center"><strong>Blue theme</strong> — the third option</td>
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

## Tuning the socket buffers (Linux)

The tunnel moves a lot of small UDP datagrams. On a busy link the kernel's
default socket buffers overflow, and the packets it drops look like loss to
QUIC, which backs off. Raising them to 25 MiB:

```sh
sudo tee /etc/sysctl.d/99-slipstream.conf >/dev/null <<'EOF'
net.core.rmem_max=26214400
net.core.wmem_max=26214400
net.core.rmem_default=26214400
net.core.wmem_default=26214400
EOF
sudo sysctl --system
```

The two `default` values are what change anything here: the tunnel never calls
`setsockopt(SO_RCVBUF)`, so its UDP socket gets whatever the default is. The
`max` pair only raises the ceiling an application may ask for.

They apply to **every** socket on the machine, so this trades memory across all
processes for tunnel throughput. Worth doing on a machine that is mostly running
the tunnel; on a shared one, set just the `max` pair. Windows needs no
equivalent — it sizes UDP buffers on its own.

Do the same on the server; its [installer](https://github.com/SpecFlowdev/slipstream-installer) documents it too.

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
