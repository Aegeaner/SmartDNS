# SmartDNS

Switch Android's Private DNS based on the Wi-Fi network you are on: automatic on one hotspot, a
fixed DNS-over-TLS host everywhere else.

| Default network | `private_dns_mode` | `private_dns_specifier` |
|---|---|---|
| configured hotspot SSID | `opportunistic` | cleared |
| any other network (Wi-Fi, cellular, ethernet, VPN) | `hostname` | configured host |
| no default network | untouched | untouched |

The system value is re-read on every reconcile and written only when it differs, so external edits,
reboots and process restarts are corrected at the next event.

## Permissions are the API

Android has no public way to change Private DNS — `DevicePolicyManager` requires a device owner — so
the app writes the hidden keys directly:

- `Settings.Global.private_dns_mode` / `private_dns_specifier` (`Settings.java:15681,15687`, `@hide`).
- `WRITE_SECURE_SETTINGS` is `signature|privileged|development|role|installer`
  (`core/res/AndroidManifest.xml:5153`); the `development` bit is what makes `pm grant` work
  (`PermissionManagerServiceImpl.java:1405`).
- The SSID is location-gated: `WifiPermissionsUtil.checkCallersLocationPermission()` requires
  `ACCESS_FINE_LOCATION` plus an allowed FINE_LOCATION app-op (lines 405–450), and a "while in use"
  grant is not enough in the background. Hence `ACCESS_BACKGROUND_LOCATION`.
- `ConnectivityManager.getNetworkCapabilities()` passes `includeLocationSensitiveInfo=false`
  (`ConnectivityService.java:2933`), so Wi-Fi transport info from that call is always redacted. The
  SSID has to come from `WifiManager.getConnectionInfo()`.

No location API is used: `LocationManagerCompat.isLocationEnabled()` only feeds a status row, and
`NEARBY_WIFI_DEVICES` is declared `neverForLocation`.

## Install

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant dev.smartdns android.permission.WRITE_SECURE_SETTINGS
adb shell pm grant dev.smartdns android.permission.ACCESS_BACKGROUND_LOCATION
adb shell settings put secure location_mode 3
adb shell am start -n dev.smartdns/.MainActivity
```

Enter the hotspot SSID and the DoT host, save, then enable monitoring. The permission card reflects
the real grant state and shows the same commands.

## Design invariants

- **Event-driven, no polling.** Default-network callbacks, a `ContentObserver` on both settings keys
  and `location_mode`, `ACTION_SCREEN_ON`, service start, boot, rule save, and activity foreground.
  Bursts coalesce behind a 400 ms debounce.
- **Read before write.** `Settings.Global` is the source of truth: a matching value costs one read
  and zero writes.
- **Ordered writes.** Specifier before mode when switching to `hostname`; mode before specifier when
  switching back. The resolver never observes hostname mode without a host.
- **Read-back verification.** A refused `putString` and a value that does not stick are distinct,
  reported errors.
- **Fail closed.** An unreadable SSID (`<unknown ssid>`) is treated as "not the target" and forces
  DoT, with a visible warning.
- **Testable core.** `core/` imports no `android.*`; validation, policy and reconciliation run on the
  JVM.

## Build and test

```bash
./gradlew :app:lintDebug :app:testDebugUnitTest :app:assembleDebug
```

Lint is a gate (`warningsAsErrors = true`, no baseline). 28 JVM tests cover name/SSID boundaries, the
connection-to-target matrix, and reconcile idempotence, denial and no-op paths.

## Limits

- A foreground service is the only way to keep monitoring; its notification cannot be removed by the
  system (dismissible on Android 13+, service keeps running).
- Battery "unrestricted" is not a substitute: it exempts Doze/App Standby only, and background
  services are still stopped after the app idles.
- `hostname` mode fails closed, so a wrong host breaks DNS device-wide. Names are validated as RFC
  1035 hostnames, and an optional probe performs a real 853 handshake with SNI and hostname
  verification.
- Some OEMs override `private_dns_mode`. Every reconcile corrects it, but a ROM that keeps rewriting
  wins.
- minSdk 28: Private DNS starts at Android 9.
