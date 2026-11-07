# OpenLock integration branch

This is the Shizuku source fork used by OpenLock. The upstream Android screens,
pairing implementation, starter and server are the basis of the integration;
the manager UI will not be rewritten in Flutter.

- Fork: <https://github.com/702fbtngus/openlock-shizuku>
- Upstream: <https://github.com/RikkaApps/Shizuku>
- Integration branch: `openlock-integration`
- Initial upstream revision: `b844bc491f1790c72328e1a8e5b2349f8978f0ea`
- Initial API revision: `a27f6e4151ba7b39965ca47edb2bf0aeed7102e5`

## Phase 1: standalone compiler baseline

The application and server source are unchanged in this phase. The fork's
`Shizuku standalone baseline` workflow compiles `:manager:assembleDebug` without
release signing secrets, build-scan publication or APK distribution. The job
uploads build diagnostics only. A successful compilation is not a claim that
pairing, server startup, reboot recovery or embedded operation work on a device.

Required tools, matching this upstream revision:

- JDK 21; Gradle 8.14 from the committed wrapper.
- Android SDK platform 36 and build tools 36.0.0.
- NDK 29.0.13113456 and CMake 3.31.6 (SDK channel 3 is enabled).
- The nested `api` submodule, at its recorded commit.

```sh
git clone --recurse-submodules --branch openlock-integration https://github.com/702fbtngus/openlock-shizuku.git
cd openlock-shizuku
./gradlew --no-daemon -Pandroid.sdk.channel=3 :manager:assembleDebug
```

On Windows, run `gradlew.bat` instead. The initial workflow uses the upstream
application identity strictly as a compilation baseline; it is not an installable
OpenLock integration release or a replacement for an installed official Shizuku.

## Next phases

1. Add an embedded library build while retaining a standalone test host. Keep
   upstream Activities and their normal UI flows.
2. Integrate Application initialization, resources, native packaging, host
   package/provider/permission identifiers, and a native launch bridge.
3. Open the embedded manager from OpenLock's Settings > Advanced > Shizuku entry.
   Maintain one APK and the existing OpenLock application ID/signing identity.
4. Verify first pairing, Binder connection, startup after reboot/Wi-Fi and app
   updates on real devices before connecting permission recovery.
5. Connect OpenLock's own permission monitoring/recovery. Keep existing WSS
   accessibility recovery independent and allow authenticated maintenance stops.

Do not add a second APK installation flow. Review the upstream README's trademark
restrictions and preserve license and attribution notices before distributing an
embedded build. Shipping it requires adapting application/permission identifiers
and auditing component entry points; adding a Git submodule does not do this.

Keep upstream `master` separate from this integration branch. Merge upstream
updates deliberately, verify the standalone and embedded builds, then update the
OpenLock parent repository's submodule pointer. Do not follow upstream HEAD in CI.

## Phase 2: embedded original UI

The new `embedded` library consumes the original manager's Java/Kotlin sources,
resources, assets and native CMake project. The original manager build and this
library share `manager/module.gradle`. Composite consumers select embedded mode;
direct builds keep the standalone manager. The `api` submodule is unchanged.

An init provider initializes the upstream manager in the host's `:shizuku`
process, including for its boot receiver and pairing service. It does not replace
the host Application or initialize ADB/UI globals on the protection main thread.
The native starter, manager Binder identity, declared permissions, server name
and server config are scoped to the OpenLock host. Protocol Binder payload keys
remain compatible internally. Embedded provider retries never force-stop the
host package. Entry Activities are internal; legacy external rish entry points
are disabled in this phase. This is not a drop-in server for existing third-party
clients of the official Shizuku app.

The `Embedded composite host` job builds Debug and Release consumers using the
same composite dependency mechanism as OpenLock. `verify_apk.py` examines the
actual APK for correct host identity, nonexported home, isolated process, custom
permissions, extracted native executables, scoped starter strings and rish dex.
The small host is a compilation/packaging fixture and is never distributed.

```sh
./gradlew --no-daemon -p integration-consumer :app:assembleDebug :app:assembleRelease
python3 integration-consumer/verify_apk.py integration-host/build/outputs/apk/debug/app-debug.apk
```

These checks do not replace the full Flutter host build or real-device startup,
pairing, Back navigation, authentication and reboot tests. OpenLock's own PIN
menu gate and recovery controls belong to the parent repository. Automatic
permission repair is not connected to this backend yet.
