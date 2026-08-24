# Slipstream for Android

The same tunnel as the desktop client, on a phone. Same servers, same routing
rules, same numbers — but arranged the way Android requires, which is not a
detail worth hiding.

## What is different, and why

The desktop client sets a system proxy and points it at a local port.
**Android has no such setting.** An app that wants to carry other apps'
traffic has to become a VPN, which means taking raw packets from a tun
interface and doing the work a kernel would otherwise do.

So there are three pieces inside the service rather than one:

| | what it does |
| --- | --- |
| **the tunnel** | `slipstream-client`, forwarding a loopback port to the proxy at the far end |
| **the proxy** | a SOCKS5 server of the app's own, where counting, attribution and rules happen |
| **the bridge** | turns the interface's packets into proxy requests |

Both native pieces ship as `lib*.so` inside the APK. That is not a disguise:
Android refuses to execute anything out of an app's data directory, and the
library directory is the one place an app's own programs are allowed to run
from. It is also why the manifest asks for legacy packaging — a library that
is only mapped out of the APK has no path to execute.

The app excludes **itself** from the interface it creates. Without that, the
tunnel's own packets to the resolver would be captured by the interface
carrying them, and nothing would move at all.

### UDP

The tunnel carries TCP streams and nothing else. Rather than pretend
otherwise, the proxy answers the one kind of UDP everything depends on — DNS —
by asking the same question over TCP, and drops the rest. QUIC notices and
falls back to TCP, which is why pages still load.

## What it does

- **Servers** — every flag the tunnel takes: domain, recursive resolvers,
  authoritative addresses, congestion control, keep-alive, certificate pinning
- **Live numbers** — download and upload rate over the last minute, totals,
  peaks, open connections, busiest destinations, and every connection as it
  happens
- **Rules** — the same first-match-wins host patterns as the desktop client,
  enforced before a byte of payload moves, with the same tests behind them
- **Per-app routing** — Android's own version of the same idea, and stricter:
  an app kept out of the tunnel never enters it
- **Themes** — graphite, paper and navy, with a wallpaper of your own behind
  any of them
- **A quick settings tile**, a live notification, reconnect-on-failure, and
  start-after-restart

## Building

Everything is built in CI, because the native side needs an NDK:

- `.github/workflows/android-engine.yml` cross-compiles the tunnel
- `.github/workflows/android-apk.yml` builds the bridge, assembles the APK

Locally, with an SDK and NDK installed:

```sh
cd android
./gradlew :app:testDebugUnitTest   # the rule engine and the wire format
./gradlew :app:assembleDebug       # needs jniLibs populated first
```

`app/src/main/jniLibs/<abi>/` is where `libslipstream.so` and `libbridge.so`
go. Without them the app builds and runs, and says it has no tunnel for your
device's processor rather than pretending otherwise.

## Status

The APK is built and its unit tests run in CI. It has **not** been run on a
physical device from here — there is no emulator or handset in the build
environment — so treat the first install as the first real test.
