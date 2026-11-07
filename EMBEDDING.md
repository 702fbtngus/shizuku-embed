# Embedding guide

## Source layout

`embedded` reuses the original manager's Java/Kotlin, resources, assets and CMake
sources. `manager/module.gradle` contains shared build configuration for the
standalone manager and library. The nested `api` submodule stays at its recorded
upstream revision. Keep fork commits and nested submodules pinned when releasing.

Initial source revisions are `b844bc491f1790c72328e1a8e5b2349f8978f0ea` for
Shizuku and `a27f6e4151ba7b39965ca47edb2bf0aeed7102e5` for Shizuku-API.

## Configure the composite build

The examples assume this checkout is at `third_party/shizuku` relative to the
host repository, with the host Android Gradle root in `android`.

In the host's `android/settings.gradle.kts` add the source build.

```kotlin
includeBuild("../third_party/shizuku")
```

In the host's `android/gradle.properties`, pass the actual application ID as a
system project property. Gradle makes this visible to the isolated included build.

```properties
systemProp.org.gradle.project.shizukuHostApplicationId=com.example.host
```

The alternative is a command-line project property such as
`-PshizukuHostApplicationId=com.example.host`. Plain project properties in the
host's `gradle.properties` stay local to that build. See the
[Gradle composite-build property rules](https://docs.gradle.org/current/userguide/composite_builds.html).

Available settings are evaluated at build time.

| Property | Embedded default | Purpose |
| --- | --- | --- |
| `shizukuHostApplicationId` | Required | Exact final host application ID |
| `shizukuServerProcessName` | `<applicationId>_shizuku_server` | Native starter/server process identity |
| `shizukuServerConfigName` | `<applicationId>-shizuku.json` | Server configuration filename |
| `shizukuEmbedded` | Automatic in a composite | Set `true` when building embedded modules directly |

Provider authorities derive from the host ID as `<applicationId>.shizuku` and
`<applicationId>.shizuku.init`. Manager/API permission names use the
`<applicationId>.shizuku.permission` prefix. Supply the exact ID for each
application-ID suffix or flavor. Different host identities require separate
Gradle invocations so the native starter and common constants match the APK.

When migrating an existing embedded app, explicitly retain its previous server
process name and configuration filename. Changing these creates a separate
backend identity/configuration. For example, an existing app can supply
`systemProp.org.gradle.project.shizukuServerProcessName=example_app_server`
and `systemProp.org.gradle.project.shizukuServerConfigName=example-app.json`.

## Configure the application module

The current supported composite toolchain uses AGP 8.11.1, JDK 21, Android SDK
36, NDK 29.0.13113456 and CMake 3.31.6. Align the host's AGP version and use
Java/Kotlin target 21 with minimum Android API 24.

In the host's application build configuration use the following dependencies
and packaging settings.

```kotlin
android {
    ndkVersion = "29.0.13113456"
    packaging {
        jniLibs.useLegacyPackaging = true
        resources.excludes += "META-INF/versions/**/OSGI-INF/MANIFEST.MF"
    }
}

dependencies {
    implementation("dev.shizuku.embed:embedded:1.0")
    compileOnly("dev.rikka.hidden:stub:4.4.0")
}

configurations.configureEach {
    exclude(group = "androidx.appcompat", module = "appcompat")
}
```

Add the filtered repository needed by the upstream libsu dependency.

```kotlin
maven("https://jitpack.io") {
    content { includeGroup("com.github.topjohnwu.libsu") }
}
```

The upstream UI uses Rikka AppCompat, which supplies AppCompat classes. Check
the consuming app's own dependency compatibility with that implementation.
Native extraction lets the original starter executable run from
`applicationInfo.nativeLibraryDir`. Compile-only hidden-API stubs supply R8's
platform signatures. The embedded library contributes targeted consumer rules
for reflective/server entry points and platform APIs outside those stubs.

## Open the manager

After the host's authentication or other entry policy succeeds, open the
original Activity explicitly from a host Activity.

```kotlin
startActivity(Intent().setClassName(packageName, "moe.shizuku.manager.MainActivity"))
```

The embedded manifest supplies Activity themes and places manager components
in `:shizuku`. An initialization provider sets up the upstream application
globals before manager Activities, receivers and services start. The consuming
app retains its Application class. Provider retry logic avoids force-stopping
the host. Check the merged manifest alongside the host's existing services.

The menu's authentication policy belongs to the host. Review Recents, pairing
notifications, boot and system authorization paths separately.

## Verification and upgrades

### Access the running backend from the host

After host authentication, call `EmbeddedShizuku.getService(context)` on a worker
thread. It returns the upstream `IShizukuService` interface, or null while the
server is unavailable. The provider bridge accepts only the host application's
UID. UI/pairing globals continue to initialize in `:shizuku`.

```kotlin
import moe.shizuku.manager.integration.EmbeddedShizuku

val server = EmbeddedShizuku.getService(context)
val uid = server?.uid
```

The host manages process streams, cancellation and authentication before using
privileged operations. Reacquire the interface after server death or restart.

### Build and device checks

`integration-consumer` consumes the library as a separate composite build.
`integration-host` supplies its minimal app module. CI uses different application
IDs for two consumers and inspects both Debug and optimized Release APKs.

Check the actual host APK too, then test pairing, shell/root backend UID,
Binder responses, Back navigation, screen off/on, Wi-Fi changes, reboot and
signed updates on each supported device family. Permission repair and other
privileged operations remain host responsibilities.

Keep upstream `master` as the comparison point and merge upstream changes into
`main` deliberately. Verify standalone and embedded builds before consumers
advance their pinned commit. Preserve source attribution and the bundled
upstream README/license assets during updates.

## Backend recovery

Recovery runs in the same isolated manager process. The foreground service uses
`specialUse` with an explanatory subtype. A persisted JobScheduler backstop
reserves job ID `0x53485A4B` in the host UID. Keep this ID available in the host.
Notification taps launch the host application through its normal launcher entry.

Configure the options in the embedded manager Settings > Startup. TCP and
Watchdog default to off. Start on boot preserves the existing receiver-component
setting. A manual Stop cancels pending starts and suppresses recovery for this
boot until the user explicitly starts again. With Start on boot enabled, a new
boot arms recovery again. Without it, a persisted job cannot start the server
on the next boot. Cached TLS ports expire at reboot. TCP candidates are probed
and authenticated rather than trusting an old system property.

Automatic recovery needs an already authorized endpoint or a working paired
wireless-debugging connection. WRITE_SECURE_SETTINGS can request wireless
debugging, subject to Android and OEM policy. It cannot preserve a TCP listener
across every reboot. The first device unlock is required to decrypt the ADB key.

### Device verification

1. Pair and start normally, confirm shell UID 2000 (or deliberately selected root).
2. Enable TCP on start, choose an unused port, and use Start / restart Shizuku.
   Confirm the terminal responds after disabling Wi-Fi and wireless debugging.
3. Stop manually. Leave the manager and wait. The watchdog must stay stopped.
   Manually start offline and verify the existing authenticated TCP endpoint.
4. Enable Watchdog, then terminate only the backend process from an authorized
   shell. Confirm one recovery attempt, a live binder and actual shell output.
5. Reboot with Start on boot enabled. Unlock once, connect Wi-Fi if needed, and
   verify retry status followed by shell output. Repeat with Start on boot off.
6. Remove pairing authorization. Automatic recovery must pause with a readable
   status. Pair again and manually start to resume. Check notification taps,
   host authentication, guard responsiveness, updates and existing root startup.
7. Repeat after process loss and battery-management restrictions on Samsung and
   Xiaomi separately. Job timing is controlled by Android and may be delayed.

Compilation and APK checks validate packaging. Offline restart, daemon behavior,
battery settings and boot timing require physical-device verification.

## Experimental startup without Wi-Fi

The native manager Settings > Startup has an opt-in **Experimental startup
without Wi-Fi** switch. It is off by default and requires Android 11+, existing
ADB pairing, WRITE_SECURE_SETTINGS, and a runtime hotspot permission. The switch
shows an explanation before requesting Nearby Wi-Fi devices on Android 13+ or
precise Location on Android 11?12. Older devices may also require location
services to be on. The library declares CHANGE_WIFI_STATE, NEARBY_WIFI_DEVICES
with neverForLocation, and ACCESS_FINE_LOCATION with maxSdkVersion 32.

After known ADB candidates fail and no Wi-Fi network is connected, the experiment
requests a local-only hotspot for an interface, then repeatedly asks Android to
enable wireless debugging when the system has turned it off. The hotspot request
has a 12-second timeout. ADB discovery has a 120-second window with bounded socket
timeouts. It probes the current TLS property and mDNS-discovered local ports.
The authenticated connection starts the same backend and verifies its binder.

When Enable TCP on start is selected, the repeated writes stop before switching
adbd to its TCP port. The hotspot reservation stays owned through server startup
and is released on success, failure, cancellation, Stop, or option disable. A
reservation delivered after cancellation is immediately closed. The experiment
can affect local Wi-Fi/hotspot operation while running. Android or OEM policy may
refuse it, particularly on Android 17. It does not establish reboot persistence.

Automatic failed experiments have a five-minute cooldown and stop after three
failures until a successful start, a new boot or a manual start resets the count.
Normal Wi-Fi recovery can continue after that limit. Existing manual-stop policy
applies. Boot recovery waits for the first device unlock to decrypt the ADB key.

**Offline startup diagnostics** displays and copies the last attempt, including
hotspot refusal, timing, setting-write count, endpoint discovery and connection
failure. It stores a bounded local log without Wi-Fi credentials or pairing keys.

Test option consent and denied/revoked permissions, first pairing, Wi-Fi off
manual startup, Wi-Fi off reboot after first unlock, hotspot refusal/timeout,
late callbacks, cancellation/Stop/disable during startup, failed-attempt limits,
Wi-Fi reconnection, and actual terminal shell output. Repeat on Samsung and Xiaomi
and verify existing guards during attempts. Build checks validate packaging and
policy/resource ownership. Device support and offline boot success require
physical-device tests.

### First unlock and report identity

Recovery registers USER_UNLOCKED at runtime while its foreground service is
running. The first unlock requests a fresh attempt, and the periodic health check
also checks credential readiness before applying connection backoff. Waiting for
the first unlock keeps retry state ready, so a long locked boot cannot turn into
a 320-second wait after the user unlocks. Manual Stop and paused recovery still
take precedence.

Recovery status includes its recorded time and boot count. Tap it to copy the
full status with current boot/unlock state, startup policy and readiness trigger.
Offline startup diagnostics records its start time, boot count and trigger. The
viewer labels reports from another boot, and labels legacy reports whose boot
identity is unknown. Compare both reports from the same boot during device tests.
