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

## Releasing

`.github/workflows/android-release.yml` builds a minified, versioned, signed
APK. Signing needs a key of your own, because a key invented per build makes
an APK nobody can ever upgrade — only uninstall and reinstall, losing every
profile with it.

Make one once, and keep it. Losing it means no future build can update an
installed copy:

```sh
keytool -genkeypair -v \
  -keystore slipstream.jks -alias slipstream \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=Slipstream, O=SpecFlowdev"
base64 -w0 slipstream.jks     # paste this into the secret below
```

Then add four repository secrets under Settings → Secrets and variables →
Actions:

| Secret | What goes in it |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | the base64 printed above |
| `ANDROID_KEYSTORE_PASSWORD` | the keystore password |
| `ANDROID_KEY_ALIAS` | `slipstream` |
| `ANDROID_KEY_PASSWORD` | the alias password, if it differs |

Keep `slipstream.jks` somewhere safe and out of the repository.

Without those secrets the workflow still runs and produces a **debug-signed**
release APK, named `-debugsigned` so it cannot be mistaken for one. It is
minified exactly like the real thing, which makes it the right build for
finding what R8 broke — and the wrong one to give anybody, since the debug key
is public and an APK carrying it can never be upgraded to a properly signed
one. Publishing to a release is refused for that build.

R8 is the reason release builds need testing of their own: the packet bridge
finds its class and methods **by name, from C**. Nothing in the Kotlin calls
them, so without a keep rule R8 removes them and the library loads and binds
to nothing — a failure that cannot occur in a debug build.

## Status

The debug APK and the release APK are both built in CI, and the unit tests
run against both. Neither has been run on a physical device from here — there
is no emulator or handset in the build environment — so treat the first
install as the first real test.
