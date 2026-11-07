# Shizuku Embed

Embed Shizuku's native manager UI, wireless ADB pairing, starter and privileged
server in an Android application. The consuming app supplies its identity and
opens the manager from its own UI.

This is a community-maintained fork of [RikkaApps/Shizuku](https://github.com/RikkaApps/Shizuku).
The original authors retain their copyright and attribution. Development of the
embedding layer takes place on `main` in [shizuku-embed](https://github.com/702fbtngus/shizuku-embed).

## What is included

- Original native Android screens and pairing/startup flows.
- An `embedded` Android library built from the original manager sources.
- A private manager entry Activity and initialization in the host's `:shizuku` process.
- Host-scoped provider authorities, permissions, native starter identity and server configuration.
- A host-UID-only bridge to the running server from the consuming app's process.
- Composite-build test hosts with Debug and optimized Release APK checks.
- Upstream `manager`, `server`, `starter`, `shell` and nested `api` source history.

Host authentication, application policy and permission-recovery decisions belong
in the consuming application. The embedding library manages Shizuku's UI and
server lifecycle. Its provider retries avoid force-stopping the host package.

## Use in an application

Clone recursively or add this repository as a recursive Git submodule. Pin a
tested commit when distributing an application.

```sh
git clone --recurse-submodules https://github.com/702fbtngus/shizuku-embed.git
```

Include this build in the host's Gradle settings, add
`dev.shizuku.embed:embedded:1.0`, and set `shizukuHostApplicationId` to the host's
actual application ID. The coordinate is resolved from the local composite
build. Maven publication is future work.

The host also configures native-library extraction, the AppCompat dependency
choice and hidden-API compile stubs. See [EMBEDDING.md](EMBEDDING.md) for complete
Gradle configuration, native Activity launch code and upgrade considerations.

## Build and verify

The current toolchain uses JDK 21, Gradle 8.14, Android Gradle Plugin 8.11.1,
Android SDK/build tools 36, NDK 29.0.13113456 and CMake 3.31.6. Composite hosts
must align their Android Gradle Plugin version with this build.

Build the example consumer with an explicit application identity.

```sh
./gradlew -p integration-consumer \
  -PshizukuHostApplicationId=com.example.shizuku.host \
  :app:assembleDebug :app:assembleRelease

python3 integration-consumer/verify_apk.py \
  integration-host/build/outputs/apk/debug/app-debug.apk \
  --application-id com.example.shizuku.host

python3 integration-consumer/verify_apk.py \
  integration-host/build/outputs/apk/release/app-release-unsigned.apk \
  --application-id com.example.shizuku.host
```

Use `gradlew.bat` on Windows. APK verification requires `apkanalyzer` on `PATH`.
The example host is a packaging fixture. Test pairing, backend responses,
navigation, reboot/reconnection and updates in the actual consuming app.

The original standalone compiler baseline remains available through
`./gradlew :manager:assembleDebug`. It retains upstream's standalone identity
for compilation. Distribution requires adapting the branding and identifiers
under the upstream terms.

CI compiles this baseline and embeds the library into two independently named
example hosts. It checks both Debug and R8/resource-shrunk Release APKs for
manager isolation, permissions, starter identity and native/DEX assets.

## Runtime scope

Users start the privileged server through the original ADB or root flow.
Embedding supplies the implementation and UI. Actual operations depend on the
running server's UID, Android version and manufacturer restrictions.

The manager home Activity is internal. The host chooses the authentication
policy before opening it and reviews notification, Recents and authorization
entry paths for its threat model. Legacy external rish entry points are disabled.
Compatibility with existing third-party clients of the official Shizuku app
requires separate integration work.

## Optional startup recovery

The original manager settings now include **Enable TCP on start**, a TCP port,
**Watchdog**, **Start / restart Shizuku** and **Recovery status**. TCP and watchdog
are off by default. After pairing, enable TCP and explicitly start again while
wireless debugging works. The ADB daemon switches to the selected TCP port
before starting the backend. Later starts try a verified local socket first.

TCP can support offline restarts during the same boot. It may expose an
authenticated ADB listener to connected networks. Disabling the preference stops
future activation. An existing listener remains until ADB restarts. Rebooting
can clear the listener and require Wi-Fi/wireless debugging again.

Start on boot retries missing endpoints with backoff. The opt-in foreground
watchdog checks server health, while a persisted 15-minute-minimum Android job
backs up process loss. Manufacturer restrictions and Android scheduling still
apply. Stop suspends recovery until a manual start, or the next boot when Start
on boot is enabled. Authentication failures and repeated starter failures pause
automatic attempts and appear in Recovery status.

The transport ordering, TCP-before-starter sequence and recovery failure handling
were adapted from [Shizuku Next v14.0.33-next](https://github.com/rushiranpise/Shizuku-Next/tree/8508e1d50a293cec82eb60465bcc17022d7a5926).
See the bundled Next attribution. The embedded manager retains the original UI
and API revision. Test startup/recovery on each consuming app and actual device.

The separate **Experimental startup without Wi-Fi** option uses a temporary
local-only hotspot and bounded wireless-debugging requests when no ADB endpoint
or Wi-Fi connection exists. It includes runtime permission consent, cancellation
cleanup and local diagnostics. It is off by default and device-dependent. See the
[experimental startup guide](EMBEDDING.md#experimental-startup-without-wi-fi).

## Upstream and license

- [Original project](https://github.com/RikkaApps/Shizuku)
- [Original documentation and user guide](https://shizuku.rikka.app/)
- [Shizuku API](https://github.com/RikkaApps/Shizuku-API)
- [Apache 2.0 license](LICENSE)
- [Preserved upstream README and branding terms](embedded/src/main/assets/open_source/shizuku/UPSTREAM-README.md)

Upstream's branding terms restrict use of its launcher icons, the standalone
app name, official application ID and `moe.shizuku.manager.permission.*`
permission names. Retain the bundled licenses, attribution and modification
notices. The embedded build uses the consuming app's identity and permissions.
