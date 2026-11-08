# Host-designed management UI

The recommended integration is a host-owned management screen backed by the
embedded engine. The original native manager is retained as an optional UI.
The current single `embedded` AAR still packages both; no core-only artifact is
published yet. Neither this API nor the engine depends on a host's design system.

## Trust and process boundary

After the host authenticates its user, call `EmbeddedShizuku.manage` on a worker
thread. The provider verifies both embedded mode and the exact host UID. Every
settings read/write and operation executes in the manager process. A foreground
service protects a user-requested startup/pairing operation when the host screen
is backgrounded. Host-specific authentication or permission policy is not
part of this repository.

```kotlin
// Run on a worker thread; deliver UI updates on your UI thread.
val state = EmbeddedShizuku.manage(context, "snapshot")
check(state.getInt("protocolVersion") == 1)
val connected = state.getBoolean("running")

EmbeddedShizuku.manage(context, "start", Bundle().apply {
    putBoolean("root", false)
    putBoolean("restart", connected)
})
// Poll snapshot while the screen is visible. Stop polling when it is hidden.
```

No Activity launch is required. Preserve the original native manager entry as a
fallback if desired. Opening it is a separate host navigation/authentication
decision. Android permission dialogs and Developer options remain system UI.

## Protocol version 1

`manage(context, command, arguments = Bundle())` returns a primitive-valued Bundle
with `protocolVersion=1`. Unknown commands and invalid settings are rejected.
Treat a failure to reach the provider as unavailable, not as confirmed stopped.
Do not run ContentResolver/Binder work on the main thread.

| Command | Arguments | Behavior |
| --- | --- | --- |
| `snapshot` | none | Live Binder status, settings, operation and capabilities. |
| `refresh_connection` | none | Requests a fresh, asynchronous ADB access check and returns the current snapshot. |
| `configure` | partial settings Bundle | Validates all fields before applying an update. |
| `start` | optional boolean `root`, `restart` | Starts asynchronously through the native starter, which replaces any existing server. |
| `pair` | integer `port`, six ASCII digits in string `code` | Pairs `127.0.0.1`; remote host addresses are not accepted. Does not automatically start. |
| `pair_notification` | none | Starts local pairing-port discovery and a notification with six-digit code input. Requires visible pairing notifications. |
| `cancel_pair_notification` | none | Cancels notification discovery and its own pairing request while preserving a running server. |
| `cancel` | none | Cancels the active request. Cancelling pairing preserves an already running backend. Cancelling startup suspends automatic recovery. |
| `stop` | none | Cancels the active request and asks the backend to exit; manual Start resumes recovery. |
| `report` | none | Snapshot plus a bounded `report` string with recovery and offline diagnostics. |

Snapshots include:

- `running`, `uid` and `launchMode`: actual current Binder state and saved launch
  method (`root`, `adb`, `unknown`). Preserve the user's method when restarting.
  Do not infer success merely
  from a successful command submission. Disconnection can occur after a read.
- `busy`, `operation`, `errorCode`, `output`: one accepted host operation at a
  time; `output` is capped at 16,000 characters. Busy requests reject new starts,
  pairing and settings changes. Stop/cancel remain available.
- `startOnBoot`, `watchdog`, `tcpEnabled`, `tcpPort`, `offlineStartup`.
- `androidApi`, `pairingSupported`, `writeSecureSettings`, `hotspotPermission`,
  `applicationId`, `lastPairedAt`, `recoveryStatus`, `readAtElapsedMs`.
- Optional `wirelessDebuggingEnabled` reflects Android's setting. Omission means
  the setting could not be read or is unsupported.
- `adbAccess` is `checking`, `authorized`, `pairing_required`, `unreachable`,
  `locked`, `key_unavailable` or `unknown`. The check uses this installation's
  saved key and never prompts for authorization or enables debugging. Status
  checks cannot create or replace a saved ADB key. `snapshot` refreshes this check
  at most every 30 seconds while the host requests snapshots. `refresh_connection`
  invalidates that cache. Startup/pairing operations take precedence over probes.
- `pairingNotificationsEnabled` includes app, channel and channel-group visibility.
  `notificationPairingState` is `idle`, `searching`, `code_required`, `pairing`,
  `paired` or `error`. The last successful pairing timestamp is history, and
  should be displayed separately from verified current ADB access.

Operation values are `idle`, `starting`, `pairing`, `cancelling`, `cancelled`,
`paired`, `running`, `stopped`, `error`. A stop request can return while the old
Binder is still exiting; keep reading live status. A successful pairing timestamp
only records this API's last success and does not prove Android still trusts the
key. Older notification-based pairing does not populate that timestamp.

Start and Restart first establish their ADB connection or root shell, then run
the native starter. That starter already kills the previous server with the same
configured process identity before launching its replacement. ADB authentication
failure therefore leaves the current server running. Successful startup requires
a live Binder distinct from the one captured before the attempt, with a bounded
15-second wait after the starter runs. Cancellation remains active throughout.

The diagnostic report identifies this flow as `native replacement v2`. Failed
manual starts include bounded stack-frame locations without argument values.
Original manager and embedded host installations have separate app-private ADB
keys. A successful ADB Start demonstrates an accepted connection for that attempt,
independently of how another installation was paired.

Operation error codes include `invalid_pairing_code`, `key_unavailable`,
`permission_required`, `notifications_required`, `foreground_start_denied`,
`connection_timeout`, `pairing_failed`, `start_failed`.
Validation errors include `invalid_settings`, `invalid_port`, `busy` and
`unknown_command`. Hosts own localized explanations and retry UI. Pairing codes
are never included in operation snapshots, diagnostics or preferences. Native
ADB errors are not copied into pairing diagnostics. Requests are not redelivered
after process death; reconnect and re-read live state instead.

## Notification pairing

Begin `pair_notification` while the host is visible, then open Developer options.
The user opens Wireless debugging > Pair device with pairing code, keeps that
dialog open and enters only its six-digit code in the notification. The existing
local-only mDNS discovery resolves `_adb-tls-pairing._tcp` and supplies the port.
There is no Activity launch when the reply is submitted. Some Android Settings
implementations close pairing when their Activity pauses, so returning to the
host to type a port/code can invalidate that endpoint.

Discovery is bounded to two minutes and a submitted request to 45 seconds. The
short foreground service stops before Android's three-minute limit. Reply actions
are scoped to one discovery session and port. Duplicate/stale replies are rejected.
The service uses the same serialized pairing engine as explicit `pair` requests,
within its existing foreground-service lifetime.
Automatic recovery waits during notification pairing. Starting discovery
interrupts any in-flight automatic transport attempt.
Stop, timeout and destruction cancel only its own request. RemoteInput codes are
removed from the received Intent and never persisted, logged, reported or redelivered.
Notifications require Android 11+ and a visible app/channel. Consumers should offer
app or channel notification settings when `notifications_required` is returned.

After pairing completes, the user returns to the host and starts the backend.

[Android Settings pairing lifecycle](https://android.googlesource.com/platform/packages/apps/Settings/+/refs/heads/main/src/com/android/settings/development/WirelessDebuggingFragment.java),
[Android notification reply actions](https://developer.android.com/develop/ui/views/notifications/build-notification#reply-action),
[Short foreground-service lifetime](https://developer.android.com/develop/background-work/services/fgs/service-types#short-service).

## Settings and consent

Partial updates accept only boolean `startOnBoot`, `watchdog`, `tcpEnabled`,
`offlineStartup` and integer `tcpPort` (1–65535). Missing fields are unchanged;
there is no arbitrary preferences/file/command access. Both UIs use the same
manager-process store and existing engine lifecycle. Hosts must request consent
and required Android permissions before enabling consequential options:

- TCP may expose an authenticated ADB listener on connected networks. Disabling
  the preference prevents future activation; it does not close a current listener.
  Reboot can clear it.
- Experimental offline startup needs Android 11+, WRITE_SECURE_SETTINGS and
  Nearby devices (Location on older Android). The host requests runtime access.
  Explain the temporary hotspot, wireless debugging writes, bounded startup wait
  and device-dependent failure. The engine checks permissions again before enable.
- Start after reboot still depends on first unlock and a usable ADB endpoint.
  A watchdog foreground notification and persisted job do not bypass OEM policy.

The library does not grant itself shell/root privileges. Existing pairing, ADB
startup or user-approved root are still required. APK packaging checks alone do
not prove pairing, background service or reboot behavior on a physical device.

## Regression checks for consumers

Test first pairing, invalid code/port, successful start/restart/stop, cancellation
during socket I/O and offline hotspot preparation, repeated taps, screen closure,
host/manager process death, and permission revocation. Confirm code/key secrecy
in copied reports. Switch between the host screen and default UI and verify the
same settings. Recheck existing API Binder clients and boot/watchdog recovery.
Build both generic example identities in Debug and optimized Release before
shipping; perform device tests on each supported OEM/Android release.
