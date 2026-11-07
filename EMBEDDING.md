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
