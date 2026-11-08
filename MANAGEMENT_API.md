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
| `configure` | partial settings Bundle | Validates all fields before applying an update. |
| `start` | optional boolean `root`, `restart` | Starts asynchronously; restart waits for the previous Binder to stop. |
| `pair` | integer `port`, six ASCII digits in string `code` | Pairs `127.0.0.1`; remote host addresses are not accepted. Does not automatically start. |
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

Operation values are `idle`, `starting`, `pairing`, `cancelling`, `cancelled`,
`paired`, `running`, `stopped`, `error`. A stop request can return while the old
Binder is still exiting; keep reading live status. A successful pairing timestamp
only records this API's last success and does not prove Android still trusts the
key. Older notification-based pairing does not populate that timestamp.

Operation error codes include `invalid_pairing_code`, `key_unavailable`,
`permission_required`, `connection_timeout`, `pairing_failed`, `start_failed`.
Validation errors include `invalid_settings`, `invalid_port`, `busy` and
`unknown_command`. Hosts own localized explanations and retry UI. Pairing codes
are never included in operation snapshots, diagnostics or preferences. Native
ADB errors are not copied into pairing diagnostics. Requests are not redelivered
after process death; reconnect and re-read live state instead.

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
