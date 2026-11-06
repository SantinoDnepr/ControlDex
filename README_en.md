# ControlDex

An unofficial Android app for controlling the **Widex COM-DEX** streamer and the hearing aids connected to it.
The protocol was reverse-engineered from the official COM-DEX 1.3.0 app (`com.widex.comdex`).

> ControlDex is not affiliated with or endorsed by Widex A/S. Widex and COM-DEX are trademarks of their respective owners. Use at your own risk.

Current version: **0.9** (`versionCode 9`), package `ua.controldex`, Android 8.0+ (minSdk 26, targetSdk 34).

## Why this app was written

1. The developers removed the Com dex app from the Google Play Market. None of their other Widex apps work with the Com dex 427 Bluetooth streamer. You have the hardware but no software, simply because the developers decided so. Frankly, that's unpleasant.
2. There is no documentation at all for the Com dex 427. Commands, modes, power-on, settings — nothing.
3. The original Com dex app demands that location be turned on at every connection. Why do they need it? What for? It's annoying.
4. The Com dex battery level indicator is buried in such a remote corner that you have to wonder who wrote this software. Are they even aware of the requirements and trends of modern smartphone apps? It's sad.
5. The app's interface. Having only 2 buttons on the main screen is awful.

## Features

- Hearing aid **volume**: `+` / `−` (auto-repeat while held) and Mute.
- **Programs**: next program, Normal / Zen+ toggle.
- Microphone **focus**: center, front, rear, left, right.
- **Boost COM-DEX media volume** (requires COM-DEX firmware ≥ 1.2.51).
- **Hearing aid microphones** during streaming (call, music, Remote Mic): on / off.
- **Status**: COM-DEX battery and charging, neck loop, current profile (Idle / call / music / Remote Mic), firmware, serial number.
- **Background operation**: a foreground service with a status-bar notification, started by the «Під'єднатись» / Connect button or by auto-connect to the last COM-DEX on launch, and stopped by «Від'єднатись» / Disconnect (also available in the notification itself). Also automatic reconnection after the link is lost.
- **Green mode** (default): GATT over the existing Classic Bluetooth (BR/EDR) connection, like the original app's hidden mode for "problematic" phones. Eliminates audio stuttering when music starts on Redmi and similar phones.
- **Themes**: light, dark, system.
- **Built-in help** (texts taken from the original app) and an «Про програму» (About) screen.
- **Diagnostics** (enabled in settings): GATT/A2DP/HFP event log, writing to a file, copying and sharing the log as a zip archive.

The app's user interface is in Ukrainian.

## How it works

```
Phone ──Classic BT (A2DP + HFP, audio; paired in Android settings)──► COM-DEX
Phone ──GATT (control, ControlDex)──────────────────────────────────► COM-DEX
COM-DEX ──proprietary 2.4 GHz radio link────────────────────────────► hearing aids
```

The phone does not control the hearing aids directly: ControlDex writes "remote control" commands to a COM-DEX
characteristic, and the COM-DEX relays them to the hearing aids. The protocol has no application-level encryption or authentication.

Key points:

| What | UUID / value |
|---|---|
| Widex service | `95986f2d-d705-4421-9890-2d32bff70000` |
| `MICROPHONE_CONTROL` | `…70003`, `1` on / `0` off |
| `NECKLOOP_STATUS` | `…70004`, `0` open / `1` closed |
| `CURRENT_PROFILE` | `…70005`, `0` not connected, `1` Idle, `2` HFP, `3` A2DP, `4` Remote Mic |
| `CHARGING_STATUS` | `…70007` |
| `PI_COMMAND` | `…70100`, volume/program/focus command frames |
| `BOOST_MEDIA_VOLUME` | `…70202`, `2` on / `0` off |
| Battery, Device Information | standard `180F` / `180A` |

`PI_COMMAND` frames end with two checksum bytes computed by an unknown algorithm, so only the ready-made frames
from the original app are used (see `ComDex.kt`). The full description of the profile, the connection sequence
and open questions is in the specification `../TZ_COM-DEX.md` (in Russian).

## Source layout

`app/src/main/java/ua/controldex/`:

| File | Purpose |
|---|---|
| `ComDex.kt` | COM-DEX GATT profile: characteristic UUIDs and `PI_COMMAND` frames |
| `GattClient.kt` | Connection (BLE and Green/BR/EDR), GATT operation queue, notifications, reconnection, watchdog |
| `Core.kt` | Process-lifetime app state: GATT client, latest values, log, auto-connect |
| `ComDexService.kt` | Foreground service (`connectedDevice`) with a notification; keeps the system from killing the process; runs from Connect (or auto-connect) to Disconnect |
| `MainActivity.kt` | Main screen: connection, volume, programs, focus, Boost, microphones, status, debug panel |
| `PageActivity.kt` | «Про програму» (About), «Налаштування» (Settings), «Довідка» (Help) screens |
| `HelpTexts.kt` | Help texts |
| `BatteryView.kt` | Battery indicator |
| `FileLog.kt`, `LogProvider.kt` | File log and serving the zip archive via a `ContentProvider` |
| `Ui.kt` | Shared preferences (SharedPreferences), theme, version |

## Building

### GitHub Actions

The workflow `.github/workflows/build.yml` runs `assembleRelease` (JDK 17, Gradle 8.7) on every push and uploads
`ControlDex.apk` as the artifact `ControlDex-v0.9-release-build<N>`.

For a stable signature (so new versions install over old ones), set these repository secrets:

| Secret | Contents |
|---|---|
| `KEYSTORE_BASE64` | keystore encoded in base64 |
| `KEYSTORE_PASSWORD` | keystore password |
| `KEY_ALIAS` | key alias |
| `KEY_PASSWORD` | key password |

Without `KEYSTORE_BASE64`, the APK is signed with a temporary debug key (the build log shows a warning).

### Locally

Requires JDK 17, Android SDK (platform 34, build-tools 34.0.0) and Gradle. Android Studio is not required.

```powershell
"sdk.dir=D:/Android/Sdk" | Out-File -Encoding ascii local.properties
gradle assembleRelease
```

Output: `app/build/outputs/apk/release/ControlDex.apk`. Detailed step-by-step instructions for Windows
(installing JDK, SDK, Gradle, installing on a Redmi via `adb`) are in [BUILD_LOCAL.md](BUILD_LOCAL.md) (in Ukrainian).

To sign locally with the same key as CI, set the environment variables `SIGNING_KEYSTORE`,
`SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`.

## First launch

1. Pair the COM-DEX in the system Bluetooth settings: open and close the neck loop, then select the device named `COM-DEX`.
2. Launch ControlDex, tap «Під'єднатись» (Connect) and grant the "Nearby devices" permission. A notification appears in the
   status bar — that is background operation; «Від'єднатись» (Disconnect) stops it and removes the notification.
3. On Xiaomi/Redmi (MIUI), in the app's «Налаштування» (Settings), disable battery optimization and enable autostart —
   otherwise the system may drop the connection in the background.

While the neck loop is open or the COM-DEX is charging, controls are unavailable and only the status is shown.

## Diagnostics

1. «Налаштування» (Settings) → enable «Налагодження» (Debugging).
2. The main screen will show the «Оновити стан COM-DEX» (Refresh COM-DEX status) button, the log, and «Поділитися (zip)» (Share as zip).
3. Via `adb`: `adb logcat | Select-String -Pattern "ControlDex|Bluetooth"`.

## Limitations

- Only the COM-DEX's own battery can be read; the protocol does not report the hearing aids' charge.
- New `PI_COMMAND` frames cannot be created (the checksum algorithm is unknown).
- The app does not pair with the device: pairing is done in the system Bluetooth settings.

## Acknowledgements

The code was written with the help of Claude (Anthropic).
