# Bt-app

Bt-app turns an Android 9 (API 28) or newer phone into a standard Bluetooth HID
keyboard and relative mouse. It uses Android's native `BluetoothHidDevice` API;
the paired computer needs no companion application, server, Wi-Fi connection, or
Web Bluetooth support.

## Status and limitations

This is an MVP for devices whose manufacturers expose the Android HID Device
profile. It provides individual HID key events, common navigation keys, F1–F12,
and a relative touchpad. Keyboard layout and text composition are interpreted by
the host operating system, so typing is deliberately implemented as physical
key events rather than Android text input. HID availability varies by Android
device and vendor. Hardware pairing is not exercised in CI.

The touchpad supports tap-to-left-click and drag movement. The input layer also
recognizes two-finger taps as right-click and vertical scroll events; these are
kept separate from Bluetooth transport so their gesture plumbing can evolve
without changing HID reporting. Movement is split into signed 8-bit HID reports
and all keys/buttons are released on disconnect or gesture cancellation.

## Build and test

Install Android SDK Platform 35 and JDK 17, then run:

```bash
./gradlew --no-daemon --no-build-cache --no-configuration-cache test
./gradlew --no-daemon --no-build-cache --no-configuration-cache lint assembleDebug
```

CI uses one Linux job and intentionally disables every Gradle/GitHub Actions
cache because this repository has a strict Actions storage budget. It uploads
no APKs or build artifacts.

## Pairing and use

1. Install and open the app, grant the Android 12+ Nearby devices/Bluetooth
   permission, and turn Bluetooth on.
2. Tap **Register HID device**.
3. On Windows, open **Settings → Bluetooth & devices**; on Linux use the system
   Bluetooth settings; on macOS use **System Settings → Bluetooth**. Pair the
   phone when it appears as “Bt-app keyboard and mouse”.
4. Return to the app after the host connects, then use Touchpad or Keyboard.

If it cannot register, verify Bluetooth is enabled, re-grant Nearby devices
permission, and check whether the phone supports the HID Device profile. Remove
the pairing at either end before attempting a fresh pairing. The app does not
request location because it does not perform Bluetooth discovery.

## Architecture

```text
Android UI
   |
ViewModel / state
   |
Input mappers and gesture detector
   |
Bluetooth HID controller
   |
Bluetooth HID host on PC
```

## Privacy and security

The app collects no telemetry and contacts no network service. Keyboard and
pointer reports are transmitted only to the connected Bluetooth host. Pair only
with trusted computers: typed key events are received by that host. Logs contain
Bluetooth operational failures but never typed text or input contents.

`BLUETOOTH_CONNECT` is used to register and communicate with the HID profile;
`BLUETOOTH_SCAN` is requested alongside it for Android's Nearby devices group
and future system-mediated pairing interaction. No location permission, desktop
service, background service, or PC-side component is used.